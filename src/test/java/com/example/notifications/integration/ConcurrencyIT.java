package com.example.notifications.integration;

import com.example.notifications.service.NotificationStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two invariants the exercise says will be probed live, proved against a real
 * Postgres over real HTTP.
 *
 * <h2>Why these tests are shaped like this</h2>
 *
 * <p>Both tests use a {@link CountDownLatch} as a starting gate: every thread is
 * created, warmed and parked on the latch, then released at once. Firing requests
 * in a loop would let the first complete before the last began, which is not a
 * race and would pass against code with no concurrency control at all. The point
 * is to maximise the overlap that a broken implementation needs in order to fail.
 *
 * <p>They assert on the <em>database</em>, not only on the responses. Counting 201s
 * proves what the service said; counting {@code delivery} rows proves what it did.
 * A dedup bug that returned the right status codes while writing two deliveries
 * would pass the first check and fail the second, so the second is the one that
 * matters.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        // Small enough to burst past comfortably, large enough that an off-by-one
        // is visible rather than hidden by a limit of 1.
        "app.rate-limit.per-recipient=5",
        "app.rate-limit.window=60s",
        // No LLM: these tests are about the concurrency control, and a network
        // call would add latency and flakiness that has nothing to do with what is
        // being proved. The personalisation paths have their own tests.
        "app.llm.api-key="
})
class ConcurrencyIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("notifications")
                    .withUsername("notifications")
                    .withPassword("notifications");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @LocalServerPort
    private int port;

    @Autowired
    private NotificationStore store;

    @Autowired
    private JdbcTemplate jdbc;

    private HttpResponse<String> post(String body) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/notifications"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String sendBody(String recipient, String idempotencyKey, String amount) {
        return """
                {"recipient_id":"%s","template":"payment_received",
                 "variables":{"name":"Aastha","amount":"%s","order_id":"A-1001",
                              "receipt_url":"https://example.com/r/1001"},
                 "idempotency_key":"%s"}
                """.formatted(recipient, amount, idempotencyKey);
    }

    /**
     * Gate 1: the same idempotency key fired concurrently must send exactly once.
     *
     * <p>The strongest assertion here is the delivery-row count. Response codes
     * describe intent; the {@code delivery} table is the durable record of what
     * actually went out, and it carries a unique index on {@code notification_id}
     * so the invariant is enforced by the database independently of the service
     * logic being tested.
     */
    @Test
    void sameIdempotencyKeyFiredConcurrentlySendsExactlyOnce() throws Exception {
        String recipient = "rcpt-dedup-" + UUID.randomUUID();
        String key = "idem-" + UUID.randomUUID();
        int threads = 40;

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    return post(sendBody(recipient, key, "₹2,499.00"));
                }));
            }
            startGate.countDown();

            AtomicInteger created = new AtomicInteger();
            AtomicInteger replayed = new AtomicInteger();
            Set<String> ids = new HashSet<>();
            for (Future<HttpResponse<String>> f : futures) {
                HttpResponse<String> response = f.get(60, TimeUnit.SECONDS);
                // Zero 5xx is part of the gate: losing a race is an expected
                // outcome with a defined response, not an error.
                assertThat(response.statusCode())
                        .as("no request may fail with a server error")
                        .isLessThan(500);
                assertThat(response.statusCode()).isIn(200, 201);
                if (response.statusCode() == 201) {
                    created.incrementAndGet();
                } else {
                    replayed.incrementAndGet();
                }
                ids.add(JSON.readTree(response.body()).get("id").asText());
            }

            assertThat(created.get()).as("exactly one caller may create").isEqualTo(1);
            assertThat(replayed.get()).isEqualTo(threads - 1);
            // Every caller must be told about the *same* notification.
            assertThat(ids).as("all callers see one notification id").hasSize(1);

            UUID notificationId = UUID.fromString(ids.iterator().next());
            assertThat(store.countDeliveries(notificationId))
                    .as("exactly one delivery was recorded")
                    .isEqualTo(1);
            assertThat(rowsInNotificationTableFor(key))
                    .as("the idempotency key produced exactly one row")
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Gate 2: a concurrent burst to one recipient admits exactly the limit.
     *
     * <p>Distinct idempotency keys, so dedup cannot mask an over-admit - each
     * request is a genuinely different send competing for the same budget. This is
     * the hot-recipient case: every thread contends on one counter row.
     */
    @Test
    void concurrentBurstToOneRecipientAdmitsExactlyTheLimit() throws Exception {
        String recipient = "rcpt-burst-" + UUID.randomUUID();
        int limit = 5;
        int threads = 40;

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                String key = "burst-" + UUID.randomUUID();
                futures.add(pool.submit(() -> {
                    startGate.await();
                    return post(sendBody(recipient, key, "₹100.00"));
                }));
            }
            startGate.countDown();

            AtomicInteger admitted = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            for (Future<HttpResponse<String>> f : futures) {
                HttpResponse<String> response = f.get(60, TimeUnit.SECONDS);
                assertThat(response.statusCode())
                        .as("zero server errors under burst")
                        .isLessThan(500);
                switch (response.statusCode()) {
                    case 201 -> admitted.incrementAndGet();
                    case 429 -> rejected.incrementAndGet();
                    default -> throw new AssertionError(
                            "unexpected status " + response.statusCode() + ": " + response.body());
                }
            }

            assertThat(admitted.get())
                    .as("exactly the limit is admitted - never over, never under")
                    .isEqualTo(limit);
            assertThat(rejected.get()).isEqualTo(threads - limit);

            // The counter itself must agree. A limiter that returned the right
            // statuses while losing increments would pass the check above.
            Integer counted = jdbc.queryForObject(
                    "SELECT count FROM rate_limit_window WHERE recipient_id = ?",
                    Integer.class, recipient);
            assertThat(counted).as("no lost counts").isEqualTo(limit);

            // And the durable record must match: exactly `limit` deliveries.
            Integer deliveries = jdbc.queryForObject("""
                    SELECT count(*) FROM delivery d
                    JOIN notification n ON n.id = d.notification_id
                    WHERE n.recipient_id = ?
                    """, Integer.class, recipient);
            assertThat(deliveries).as("never over-admitted in the durable record")
                    .isEqualTo(limit);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Same key, different body, is a 409 - and must stay a 409 under concurrency
     * rather than racing into a second send.
     */
    @Test
    void sameKeyWithADifferentBodyConflicts() throws Exception {
        String recipient = "rcpt-conflict-" + UUID.randomUUID();
        String key = "idem-" + UUID.randomUUID();

        HttpResponse<String> first = post(sendBody(recipient, key, "₹10.00"));
        assertThat(first.statusCode()).isEqualTo(201);

        HttpResponse<String> conflicting = post(sendBody(recipient, key, "₹999999.00"));
        assertThat(conflicting.statusCode()).isEqualTo(409);
        assertThat(JSON.readTree(conflicting.body()).get("error").asText())
                .isEqualTo("IDEMPOTENCY_KEY_CONFLICT");

        // The conflict must not have produced a second delivery.
        UUID id = UUID.fromString(JSON.readTree(first.body()).get("id").asText());
        assertThat(store.countDeliveries(id)).isEqualTo(1);
    }

    /** A replay returns the original body and does not increment attempts. */
    @Test
    void replayReturnsTheOriginalOutcomeUnchanged() throws Exception {
        String recipient = "rcpt-replay-" + UUID.randomUUID();
        String key = "idem-" + UUID.randomUUID();

        JsonNode first = JSON.readTree(post(sendBody(recipient, key, "₹42.00")).body());
        HttpResponse<String> second = post(sendBody(recipient, key, "₹42.00"));
        JsonNode replay = JSON.readTree(second.body());

        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(replay.get("deduped").asBoolean()).isTrue();
        assertThat(replay.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(replay.get("body").asText()).isEqualTo(first.get("body").asText());
        assertThat(replay.get("attempts").asInt())
                .as("a replay is not another attempt")
                .isEqualTo(first.get("attempts").asInt());
    }

    private int rowsInNotificationTableFor(String idempotencyKey) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM notification WHERE idempotency_key = ?",
                Integer.class, idempotencyKey);
        return count == null ? 0 : count;
    }

    /** Distinct recipients must not contend with each other. */
    @Test
    void rateLimitIsPerRecipientNotGlobal() throws Exception {
        Map<String, Integer> admittedByRecipient = new java.util.LinkedHashMap<>();
        for (int r = 0; r < 3; r++) {
            String recipient = "rcpt-isolated-" + UUID.randomUUID();
            int admitted = 0;
            for (int i = 0; i < 5; i++) {
                HttpResponse<String> response =
                        post(sendBody(recipient, "iso-" + UUID.randomUUID(), "₹5.00"));
                if (response.statusCode() == 201) {
                    admitted++;
                }
            }
            admittedByRecipient.put(recipient, admitted);
        }
        assertThat(admittedByRecipient.values())
                .as("each recipient gets their own full budget")
                .allMatch(count -> count == 5);
    }
}
