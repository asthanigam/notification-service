package com.example.notifications.config;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ships log events to a hosted backend (Better Stack, Axiom, anything that takes
 * {@code POST} of a JSON array with a bearer token).
 *
 * <h2>Why the application ships its own logs</h2>
 *
 * <p>The obvious route is to let the platform forward stdout - but Render's log
 * streaming to an external destination is a paid feature, and this has to run on
 * free tiers. Shipping from inside the process is free, works identically on any
 * host, and has the side benefit of carrying the MDC through as real fields
 * rather than relying on the platform to parse a line back into structure.
 *
 * <h2>Logging must never be able to hurt the request path</h2>
 *
 * <p>This is the same argument as the click recorder in the previous service, and
 * it is the reason this class is more than an HTTP call in a loop:
 *
 * <ul>
 *   <li><b>Bounded queue.</b> An unbounded one turns a slow log backend into an
 *       out-of-memory kill. Ten thousand events is a generous buffer for a blip
 *       and far too small to hide a sustained outage.</li>
 *   <li><b>Drop on full, and count the drops.</b> {@code offer} rather than
 *       {@code put}: a request thread must never block because a logging
 *       endpoint is slow. Silent loss would be worse than loud loss, so drops
 *       are counted and reported.</li>
 *   <li><b>A daemon thread does the network work.</b> No request thread ever
 *       makes the HTTP call, and being a daemon means a stuck shipper cannot
 *       hold the JVM open on shutdown.</li>
 *   <li><b>Failures are swallowed.</b> A logging appender that throws into the
 *       logging framework can turn one bad response into an error storm.</li>
 * </ul>
 *
 * <p>stdout keeps its own structured output regardless, so the platform's own log
 * view stays useful and this is purely additive - if the token is unset, or the
 * backend is down, the service is unaffected.
 */
public class LogShipper extends AppenderBase<ILoggingEvent> {

    /** Roughly ten seconds of headroom at a busy-but-sane event rate. */
    private static final int QUEUE_CAPACITY = 10_000;
    private static final int MAX_BATCH = 100;
    private static final Duration FLUSH_INTERVAL = Duration.ofSeconds(2);

    private final BlockingQueue<ILoggingEvent> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String endpoint;
    private final String token;
    private final String serviceName;
    private final HttpClient httpClient;

    private volatile boolean running = true;
    private Thread worker;

    public LogShipper(String endpoint, String token, String serviceName) {
        this.endpoint = endpoint;
        this.token = token;
        this.serviceName = serviceName;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public void start() {
        worker = new Thread(this::drainLoop, "log-shipper");
        // Daemon: a wedged shipper must not keep the JVM alive during shutdown.
        worker.setDaemon(true);
        worker.start();
        super.start();
    }

    @Override
    protected void append(ILoggingEvent event) {
        // offer, never put. Dropping a log line is always better than stalling a
        // request thread on a third party's availability.
        if (!queue.offer(event)) {
            long total = dropped.incrementAndGet();
            // Report the first drop and then powers of ten - a saturated queue
            // would otherwise generate more noise than the traffic causing it.
            if (total == 1 || Long.toString(total).matches("10*")) {
                addWarn("Log shipping queue full; dropped " + total
                        + " events so far. The service is unaffected.");
            }
        }
    }

    private void drainLoop() {
        List<ILoggingEvent> batch = new ArrayList<>(MAX_BATCH);
        while (running) {
            try {
                ILoggingEvent first = queue.poll(FLUSH_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, MAX_BATCH - 1);
                send(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // Never propagate: an appender that throws can turn one bad
                // response into a cascade through the logging framework itself.
                addWarn("Log shipping failed: " + e.getClass().getSimpleName());
            } finally {
                batch.clear();
            }
        }
    }

    private void send(List<ILoggingEvent> batch) {
        try {
            ArrayNode payload = objectMapper.createArrayNode();
            for (ILoggingEvent event : batch) {
                payload.add(toJson(event));
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                addWarn("Log shipping rejected with HTTP " + response.statusCode());
            }
        } catch (Exception e) {
            addWarn("Log shipping request failed: " + e.getClass().getSimpleName());
        }
    }

    /**
     * One event as a flat JSON object.
     *
     * <p>MDC entries are promoted to top-level fields, which is what makes
     * {@code event:llm_call AND fallback_taken:true} a filter in the log backend
     * rather than a regex over a message string. See {@link Events}.
     */
    private ObjectNode toJson(ILoggingEvent event) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("dt", Instant.ofEpochMilli(event.getTimeStamp()).toString());
        node.put("level", event.getLevel().toString());
        node.put("message", event.getFormattedMessage());
        node.put("logger", event.getLoggerName());
        node.put("thread", event.getThreadName());
        node.put("service", serviceName);

        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc != null) {
            mdc.forEach((key, value) -> {
                if (value != null) {
                    node.put(key, value);
                }
            });
        }

        if (event.getThrowableProxy() != null) {
            node.put("error", event.getThrowableProxy().getClassName());
            node.put("error_message", event.getThrowableProxy().getMessage());
        }
        return node;
    }

    @Override
    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
        super.stop();
    }

    public long droppedCount() {
        return dropped.get();
    }
}
