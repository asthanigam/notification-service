package com.example.notifications.service;

import com.example.notifications.config.AppProperties;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Per-recipient fixed-window rate limiter.
 *
 * <h2>The correctness argument</h2>
 *
 * <p>Admission is decided by exactly one SQL statement:
 *
 * <pre>
 *   INSERT INTO rate_limit_window (recipient_id, window_start, count)
 *   VALUES (?, ?, 1)
 *   ON CONFLICT (recipient_id, window_start)
 *   DO UPDATE SET count = rate_limit_window.count + 1
 *   WHERE rate_limit_window.count &lt; ?
 *   RETURNING count
 * </pre>
 *
 * <p>A returned row means the increment happened and the caller is admitted. No
 * row means the {@code WHERE} guard refused the update because the window was
 * already full, and the caller gets a 429. There is no read-then-write in
 * application code, so there is no interval during which two callers can both
 * observe {@code count = limit - 1} and both admit themselves - the classic
 * lost-update bug this design exists to avoid.
 *
 * <p>Postgres takes a row-level lock for the duration of the {@code ON CONFLICT
 * DO UPDATE}, so concurrent callers for one recipient serialise on that single
 * row. That is the hot-recipient case working as intended rather than in spite
 * of the design: the contention is one row wide, the lock is held for the length
 * of one statement, and nothing slow happens while it is held. The LLM call is
 * deliberately downstream of this method returning.
 *
 * <h2>What fixed window costs</h2>
 *
 * <p>A caller can land up to {@code 2 * limit} sends across a window boundary -
 * {@code limit} at the end of one window and {@code limit} at the start of the
 * next. That is the known price of one row and one statement. A sliding window
 * needs two counters and a weighted read; a token bucket needs refill arithmetic
 * and a last-refill timestamp. Both are strictly better rate limiters and
 * strictly more to get right; the boundary burst is bounded and documented
 * rather than discovered.
 */
@Component
public class RateLimiter {

    private static final String GUARDED_UPSERT = """
            INSERT INTO rate_limit_window (recipient_id, window_start, count)
            VALUES (?, ?, 1)
            ON CONFLICT (recipient_id, window_start)
            DO UPDATE SET count = rate_limit_window.count + 1
            WHERE rate_limit_window.count < ?
            RETURNING count
            """;

    private static final String CURRENT_COUNT = """
            SELECT count FROM rate_limit_window
            WHERE recipient_id = ? AND window_start = ?
            """;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final int limit;
    private final Duration window;

    public RateLimiter(JdbcTemplate jdbc, Clock clock, AppProperties properties) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.limit = properties.rateLimit().perRecipient();
        this.window = properties.rateLimit().window();
    }

    /**
     * Consumes one unit of budget for {@code recipientId}.
     *
     * <p>Returns the decision rather than throwing, because being rate limited is
     * an ordinary outcome of a well-formed request and the caller records it on
     * the notification row. Exceptions are for things that went wrong.
     */
    public Decision tryAcquire(String recipientId) {
        Instant windowStart = currentWindowStart();
        try {
            Integer newCount = jdbc.queryForObject(
                    GUARDED_UPSERT, Integer.class,
                    recipientId, Timestamp.from(windowStart), limit);
            // A row came back, so the guarded update fired and this caller owns
            // one of the slots.
            return new Decision(true, newCount == null ? limit : newCount, limit,
                    windowStart.plus(window));
        } catch (EmptyResultDataAccessException noRowReturned) {
            // The WHERE guard rejected the increment: the window is full. Nothing
            // was written, so this is not a partial failure to clean up.
            return new Decision(false, limit, limit, windowStart.plus(window));
        }
    }

    /**
     * Current usage without consuming anything, for
     * {@code GET /recipients/{id}/notifications}.
     */
    public Decision peek(String recipientId) {
        Instant windowStart = currentWindowStart();
        Integer used;
        try {
            used = jdbc.queryForObject(CURRENT_COUNT, Integer.class,
                    recipientId, Timestamp.from(windowStart));
        } catch (EmptyResultDataAccessException noWindowYet) {
            used = 0;
        }
        int current = used == null ? 0 : used;
        return new Decision(current < limit, current, limit, windowStart.plus(window));
    }

    /**
     * Start of the window {@code now} falls in, truncated to the window size.
     *
     * <p>Derived from the injected {@link Clock} rather than {@code now()} in SQL
     * so a test can move time instead of sleeping through a real minute.
     */
    private Instant currentWindowStart() {
        long windowMillis = window.toMillis();
        long nowMillis = clock.instant().toEpochMilli();
        return Instant.ofEpochMilli(nowMillis - Math.floorMod(nowMillis, windowMillis));
    }

    /**
     * @param admitted    whether this caller may proceed
     * @param used        slots consumed in the current window, after this call
     * @param limit       configured ceiling
     * @param windowReset when the current window rolls over
     */
    public record Decision(boolean admitted, int used, int limit, Instant windowReset) {
        public int remaining() {
            return Math.max(0, limit - used);
        }
    }
}
