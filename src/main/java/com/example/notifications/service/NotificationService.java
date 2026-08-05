package com.example.notifications.service;

import com.example.notifications.config.Events;
import com.example.notifications.domain.BodySource;
import com.example.notifications.domain.Notification;
import com.example.notifications.domain.NotificationStatus;
import com.example.notifications.llm.Personalizer;
import com.example.notifications.llm.TemplateRenderer;
import com.example.notifications.web.ConflictException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates a send.
 *
 * <h2>The order of operations is the design</h2>
 *
 * <ol>
 *   <li><b>Claim the idempotency key</b> (one atomic INSERT). Losing this race
 *       means somebody else owns this send; take the replay path.</li>
 *   <li><b>Consume rate-limit budget</b> (one atomic guarded UPDATE).</li>
 *   <li><b>Personalise</b> - the slow, failable, third-party step. No transaction
 *       is open and no row lock is held here.</li>
 *   <li><b>Complete and record delivery.</b></li>
 * </ol>
 *
 * <p><b>Dedup before rate limit, deliberately.</b> If the order were reversed, a
 * burst of retries carrying the <em>same</em> idempotency key would each consume a
 * slot before dedup collapsed them, and a client retrying a timeout would rate
 * limit itself out of a send it had already been promised. Claiming the key first
 * means budget is spent per distinct intent, never per delivery attempt.
 *
 * <p><b>The personalisation step is outside every lock.</b> Steps 1 and 2 are each
 * a single autocommit statement; by the time the model is called, both have
 * committed. A model that takes four seconds costs four seconds of one request's
 * latency and blocks nothing else - not the recipient's other sends, not another
 * recipient's, not a connection held open inside a transaction.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /**
     * How long a replay waits for the in-flight original to finish.
     *
     * <p>The losing caller of a same-key race has nothing useful to return until
     * the winner completes. Polling briefly turns the common case - a client
     * retrying after a timeout while the first attempt is still running - into the
     * correct answer rather than an ambiguous one. Bounded so a stuck winner
     * cannot pin a request thread: past this, the caller gets the honest
     * {@code PENDING} row, which is still not a second send.
     */
    private static final Duration REPLAY_WAIT = Duration.ofSeconds(3);
    private static final Duration REPLAY_POLL_INTERVAL = Duration.ofMillis(40);

    private static final String CHANNEL = "log";

    private final NotificationStore store;
    private final RateLimiter rateLimiter;
    private final TemplateRenderer renderer;
    private final Personalizer personalizer;
    private final RequestFingerprint fingerprint;
    private final Clock clock;
    private final MeterRegistry metrics;

    public NotificationService(NotificationStore store, RateLimiter rateLimiter,
                               TemplateRenderer renderer, Personalizer personalizer,
                               RequestFingerprint fingerprint, Clock clock,
                               MeterRegistry metrics) {
        this.store = store;
        this.rateLimiter = rateLimiter;
        this.renderer = renderer;
        this.personalizer = personalizer;
        this.fingerprint = fingerprint;
        this.clock = clock;
        this.metrics = metrics;
    }

    public SendOutcome send(String recipientId, String template,
                            Map<String, String> variables, String idempotencyKey) {

        // Fail before consuming anything if the request could never succeed.
        // Rendering here also means a bad template never reaches the model.
        TemplateRenderer.Rendered rendered = renderer.render(template, variables);

        String requestHash = fingerprint.of(recipientId, template, variables);
        Instant now = clock.instant();
        UUID candidateId = UUID.randomUUID();

        Optional<UUID> claimed = store.claim(candidateId, recipientId, template, variables,
                idempotencyKey, requestHash, now);

        if (claimed.isEmpty()) {
            return replay(idempotencyKey, requestHash);
        }

        // This caller owns the send from here on.
        RateLimiter.Decision decision = rateLimiter.tryAcquire(recipientId);
        if (!decision.admitted()) {
            store.complete(candidateId, NotificationStatus.RATE_LIMITED, null, null,
                    "per-recipient rate limit exceeded", clock.instant());
            metrics.counter("notifications.rate_limited").increment();
            Events.of("notification_rate_limited")
                    .with("notification_id", candidateId)
                    .with("recipient_id", recipientId)
                    .with("template", template)
                    .with("rate_limit", decision.limit())
                    .info(log);
            return new SendOutcome(store.findById(candidateId).orElseThrow(),
                    Disposition.RATE_LIMITED, decision);
        }

        // --- nothing is locked past this line ---
        Personalizer.Result result =
                personalizer.personalize(rendered.body(), rendered.usedVariables());

        metrics.counter("llm.calls", "outcome", result.outcome()).increment();
        if (result.usedFallback()) {
            metrics.counter("llm.fallback").increment();
        }
        Timer.builder("llm.latency").register(metrics)
                .record(result.latencyMs(), TimeUnit.MILLISECONDS);

        Instant completedAt = clock.instant();
        store.complete(candidateId, NotificationStatus.SENT, result.body(),
                result.source(), null, completedAt);
        // "Sending" here is persist + log. The delivery row is the durable proof
        // the dedup gate counts.
        store.recordDelivery(candidateId, CHANNEL, completedAt);

        metrics.counter("notifications.sent").increment();
        // Two events, not one: the send and the model call are separately
        // interesting. A dashboard filtering event:llm_call can chart fallback
        // rate and latency without also matching every delivery.
        Events.of("llm_call")
                .with("notification_id", candidateId)
                .with("outcome", result.outcome())
                .with("fallback_taken", result.usedFallback())
                .with("llm_latency_ms", result.latencyMs())
                .info(log);
        Events.of("notification_sent")
                .with("notification_id", candidateId)
                .with("recipient_id", recipientId)
                .with("template", template)
                .with("body_source", result.source())
                .with("rate_limit_used", decision.used())
                .with("rate_limit", decision.limit())
                .info(log);

        return new SendOutcome(store.findById(candidateId).orElseThrow(),
                Disposition.SENT, decision);
    }

    /**
     * Handles a caller that lost the race for an idempotency key.
     *
     * <p>Three cases, in order of how much they matter:
     * <ul>
     *   <li><b>Different request, same key</b> - 409. The key names an intent; two
     *       different intents cannot share one. Detected by comparing the stored
     *       fingerprint, so it is exact rather than heuristic.</li>
     *   <li><b>Same request, original finished</b> - replay the original outcome
     *       verbatim. No new send, no new attempt, no rate-limit budget consumed.</li>
     *   <li><b>Same request, original still in flight</b> - wait briefly for it to
     *       land, then replay. If it has not landed, return the PENDING row.</li>
     * </ul>
     */
    private SendOutcome replay(String idempotencyKey, String requestHash) {
        Notification existing = store.findByIdempotencyKey(idempotencyKey)
                // The key was claimed a moment ago; it cannot vanish. If it does,
                // something is deleting rows underneath us and a 500 is honest.
                .orElseThrow(() -> new IllegalStateException(
                        "idempotency key claimed but not readable: " + idempotencyKey));

        if (!existing.requestFingerprint().equals(requestHash)) {
            metrics.counter("notifications.conflict").increment();
            Events.of("notification_conflict")
                    .with("notification_id", existing.id())
                    .with("recipient_id", existing.recipientId())
                    .info(log);
            throw new ConflictException(
                    "idempotency_key was already used for a different request");
        }

        Notification settled = awaitTerminal(existing);

        metrics.counter("notifications.deduped").increment();
        Events.of("notification_deduped_replay")
                .with("notification_id", settled.id())
                .with("recipient_id", settled.recipientId())
                .with("replayed_status", settled.status())
                .info(log);

        return new SendOutcome(settled, Disposition.DEDUPED,
                rateLimiter.peek(settled.recipientId()));
    }

    /** Polls until the winner completes, or the bounded wait expires. */
    private Notification awaitTerminal(Notification notification) {
        if (notification.isTerminal()) {
            return notification;
        }
        Instant deadline = clock.instant().plus(REPLAY_WAIT);
        Notification current = notification;
        while (!current.isTerminal() && clock.instant().isBefore(deadline)) {
            try {
                Thread.sleep(REPLAY_POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return current;
            }
            current = store.findById(current.id()).orElse(current);
        }
        return current;
    }

    public Optional<Notification> findById(UUID id) {
        return store.findById(id);
    }

    public List<Notification> recentFor(String recipientId, int limit) {
        return store.findRecentForRecipient(recipientId, limit);
    }

    public RateLimiter.Decision rateLimitStateFor(String recipientId) {
        return rateLimiter.peek(recipientId);
    }

    /** What happened, for the controller to turn into a status code. */
    public enum Disposition {
        SENT, DEDUPED, RATE_LIMITED
    }

    public record SendOutcome(Notification notification, Disposition disposition,
                              RateLimiter.Decision rateLimit) {
        public boolean sentNow() {
            return disposition == Disposition.SENT;
        }
    }
}
