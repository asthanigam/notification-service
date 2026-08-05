package com.example.notifications.service;

import com.example.notifications.domain.BodySource;
import com.example.notifications.domain.Notification;
import com.example.notifications.domain.NotificationStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * All persistence for notifications and deliveries.
 *
 * <p>Every method here is one statement. That is not a style preference: the
 * dedup guarantee is "exactly one caller may claim an idempotency key", and the
 * only way to state that without a lock is to let the database's unique index
 * arbitrate a single {@code INSERT}. Anything spread across two statements would
 * need a transaction boundary to reason about, and anything holding a lock would
 * have to hold it across the LLM call.
 */
@Component
public class NotificationStore {

    /**
     * Claims an idempotency key.
     *
     * <p>{@code ON CONFLICT DO NOTHING} with {@code RETURNING} is the whole trick.
     * Under a concurrent burst of identical keys, Postgres admits exactly one
     * insert; every other caller gets an empty result and knows, without having
     * read anything first, that it lost the race and must take the replay path.
     *
     * <p>Rejected alternative: {@code SELECT} by key, then {@code INSERT} if
     * absent. That is check-then-act - two callers can both see "absent" and both
     * insert, and only one survives the unique index, so the loser has to handle
     * an exception anyway. Same outcome, more code, and a window where the wrong
     * thing can happen.
     */
    private static final String CLAIM_KEY = """
            INSERT INTO notification (
                id, recipient_id, template, variables,
                idempotency_key, request_fingerprint,
                status, attempts, created_at, updated_at)
            VALUES (?, ?, ?, ?::jsonb, ?, ?, 'PENDING', 0, ?, ?)
            ON CONFLICT (idempotency_key) DO NOTHING
            RETURNING id
            """;

    private static final String SELECT_COLUMNS = """
            SELECT id, recipient_id, template, variables, idempotency_key,
                   request_fingerprint, status, personalized_body, body_source,
                   attempts, failure_reason, created_at, updated_at
            FROM notification
            """;

    private static final String FIND_BY_KEY = SELECT_COLUMNS + " WHERE idempotency_key = ?";
    private static final String FIND_BY_ID = SELECT_COLUMNS + " WHERE id = ?";
    private static final String FIND_BY_RECIPIENT =
            SELECT_COLUMNS + " WHERE recipient_id = ? ORDER BY created_at DESC LIMIT ?";

    /**
     * Moves a reservation to a terminal state.
     *
     * <p>Guarded on {@code status = 'PENDING'} so a completion can never overwrite
     * an already-terminal row. Without the guard, a delayed retry of the winner's
     * own work could rewrite a notification somebody has already been told about.
     */
    private static final String COMPLETE = """
            UPDATE notification
            SET status = ?, personalized_body = ?, body_source = ?,
                attempts = attempts + 1, failure_reason = ?, updated_at = ?
            WHERE id = ? AND status = 'PENDING'
            """;

    /**
     * Records the send itself.
     *
     * <p>{@code ON CONFLICT DO NOTHING} against the unique index on
     * {@code notification_id}: even if the service layer were wrong and called
     * this twice, the second call is a no-op rather than a second delivery. The
     * dedup gate reads this table, so it must be true independently of the logic
     * it is checking.
     */
    private static final String RECORD_DELIVERY = """
            INSERT INTO delivery (notification_id, channel, delivered_at)
            VALUES (?, ?, ?)
            ON CONFLICT (notification_id) DO NOTHING
            """;

    private static final String COUNT_DELIVERIES =
            "SELECT count(*) FROM delivery WHERE notification_id = ?";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public NotificationStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Attempts to claim {@code idempotencyKey}.
     *
     * @return the new notification id if this caller won the race, or empty if the
     *         key was already claimed - in which case the caller should read the
     *         existing row with {@link #findByIdempotencyKey}.
     */
    public Optional<UUID> claim(UUID id, String recipientId, String template,
                                Map<String, String> variables, String idempotencyKey,
                                String requestFingerprint, Instant now) {
        List<UUID> claimed = jdbc.query(CLAIM_KEY,
                (rs, rowNum) -> rs.getObject(1, UUID.class),
                id, recipientId, template, toJson(variables),
                idempotencyKey, requestFingerprint,
                Timestamp.from(now), Timestamp.from(now));
        return claimed.stream().findFirst();
    }

    public Optional<Notification> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.query(FIND_BY_KEY, MAPPER, idempotencyKey).stream().findFirst();
    }

    public Optional<Notification> findById(UUID id) {
        return jdbc.query(FIND_BY_ID, MAPPER, id).stream().findFirst();
    }

    public List<Notification> findRecentForRecipient(String recipientId, int limit) {
        return jdbc.query(FIND_BY_RECIPIENT, MAPPER, recipientId, limit);
    }

    /** @return true if this call moved the row out of PENDING. */
    public boolean complete(UUID id, NotificationStatus status, String body,
                            BodySource source, String failureReason, Instant now) {
        return jdbc.update(COMPLETE,
                status.name(), body, source == null ? null : source.name(),
                failureReason, Timestamp.from(now), id) == 1;
    }

    public void recordDelivery(UUID notificationId, String channel, Instant now) {
        jdbc.update(RECORD_DELIVERY, notificationId, channel, Timestamp.from(now));
    }

    /** Used by the concurrency tests to assert exactly-once delivery. */
    public int countDeliveries(UUID notificationId) {
        Integer count = jdbc.queryForObject(COUNT_DELIVERIES, Integer.class, notificationId);
        return count == null ? 0 : count;
    }

    private String toJson(Map<String, String> variables) {
        try {
            return objectMapper.writeValueAsString(variables);
        } catch (Exception e) {
            throw new IllegalArgumentException("variables are not serialisable", e);
        }
    }

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private final RowMapper<Notification> MAPPER = (ResultSet rs, int rowNum) -> new Notification(
            rs.getObject("id", UUID.class),
            rs.getString("recipient_id"),
            rs.getString("template"),
            // getString on a jsonb column returns the JSON text. Reading it this
            // way rather than casting to the driver's PGobject keeps the
            // Postgres driver at runtime scope, so nothing in the application
            // compiles against a specific database vendor's classes.
            readVariables(rs.getString("variables")),
            rs.getString("idempotency_key"),
            rs.getString("request_fingerprint"),
            NotificationStatus.valueOf(rs.getString("status")),
            rs.getString("personalized_body"),
            rs.getString("body_source") == null
                    ? null : BodySource.valueOf(rs.getString("body_source")),
            rs.getInt("attempts"),
            rs.getString("failure_reason"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private Map<String, String> readVariables(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, STRING_MAP);
        } catch (Exception e) {
            throw new IllegalStateException("stored variables are not readable", e);
        }
    }
}
