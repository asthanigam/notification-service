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
 * Per-recipient token-bucket rate limiter.
 *
 * <h2>The correctness argument</h2>
 *
 * <p>Admission is decided by exactly one SQL statement, which refills the bucket
 * and consumes a token together:
 *
 * <pre>
 *   INSERT INTO rate_limit_bucket AS b (recipient_id, tokens, updated_at)
 *   VALUES (?, capacity - 1, now)
 *   ON CONFLICT (recipient_id) DO UPDATE
 *      SET tokens = LEAST(capacity, b.tokens + elapsed_seconds * refill_rate) - 1,
 *          updated_at = now
 *    WHERE LEAST(capacity, b.tokens + elapsed_seconds * refill_rate) &gt;= 1
 *   RETURNING tokens
 * </pre>
 *
 * <p>A returned row means a token was consumed and the caller is admitted. No row
 * means the {@code WHERE} guard found the refilled bucket empty, and the caller
 * gets a 429. Refill and consume happen inside the same statement, so there is no
 * moment at which application code holds a stale token count - the lost-update
 * race this design exists to avoid.
 *
 * <p>Postgres takes a row-level lock for the duration of the {@code ON CONFLICT DO
 * UPDATE}, so concurrent callers for one recipient serialise on that single row.
 * That is the hot-recipient case working as intended rather than in spite of the
 * design: contention is one row wide, the lock is held for one statement, and the
 * LLM call is deliberately far downstream of this method returning.
 *
 * <h2>Why a bucket rather than a fixed window</h2>
 *
 * <p>This started as a fixed-window counter, whose known cost was that a caller
 * could land up to {@code 2 * limit} across a window boundary. That was written
 * down as bounded and understood - and then it happened: a 40-request burst
 * against a managed Postgres (~150ms per round trip rather than ~1ms locally)
 * took long enough to straddle a minute boundary and admitted 8 against a limit
 * of 5. The weakness was real all along; latency is what made it reachable.
 *
 * <p>A bucket has no boundary to straddle. Tokens accrue continuously at
 * {@code limit / window} per second, so the guarantee holds at every instant
 * rather than only within an arbitrary alignment. Under a burst that completes in
 * far less than one refill interval - which is every burst a limit of five per
 * minute will ever see - exactly {@code limit} callers are admitted.
 *
 * <p>Refill is lazy: it is derived from the gap between {@code updated_at} and
 * now, at the moment the row is next touched. No background job tops buckets up,
 * and a recipient nobody is sending to costs nothing.
 */
@Component
public class RateLimiter {

    /**
     * Refill and consume in one guarded statement.
     *
     * <p>The refill expression appears twice - once in {@code SET} and once in
     * {@code WHERE} - because Postgres evaluates the {@code WHERE} against the
     * pre-update row and there is no way to bind an intermediate value inside an
     * {@code ON CONFLICT DO UPDATE}. Duplicated deliberately rather than split
     * into two statements, which would reopen the race this closes.
     */
    private static final String REFILL_AND_CONSUME = """
            INSERT INTO rate_limit_bucket AS b (recipient_id, tokens, updated_at)
            VALUES (?, ? - 1, ?)
            ON CONFLICT (recipient_id) DO UPDATE
               SET tokens = LEAST(?, b.tokens
                     + EXTRACT(EPOCH FROM (? - b.updated_at)) * ?) - 1,
                   updated_at = ?
             WHERE LEAST(?, b.tokens
                     + EXTRACT(EPOCH FROM (? - b.updated_at)) * ?) >= 1
            RETURNING tokens
            """;

    private static final String PEEK = """
            SELECT LEAST(?, tokens + EXTRACT(EPOCH FROM (? - updated_at)) * ?)
            FROM rate_limit_bucket
            WHERE recipient_id = ?
            """;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final int capacity;
    private final Duration window;
    private final double refillPerSecond;

    public RateLimiter(JdbcTemplate jdbc, Clock clock, AppProperties properties) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.capacity = properties.rateLimit().perRecipient();
        this.window = properties.rateLimit().window();
        // A full bucket refills in exactly one window, which is what makes
        // "limit per window" the steady-state rate.
        this.refillPerSecond = capacity / (double) window.toSeconds();
    }

    /**
     * Consumes one token for {@code recipientId}.
     *
     * <p>Returns the decision rather than throwing: being rate limited is an
     * ordinary outcome of a well-formed request, and the caller records it on the
     * notification row. Exceptions are for things that went wrong.
     */
    public Decision tryAcquire(String recipientId) {
        Timestamp now = Timestamp.from(clock.instant());
        try {
            Double remaining = jdbc.queryForObject(
                    REFILL_AND_CONSUME, Double.class,
                    recipientId, capacity, now,
                    capacity, now, refillPerSecond, now,
                    capacity, now, refillPerSecond);
            double left = remaining == null ? 0 : remaining;
            return new Decision(true, used(left), capacity, nextTokenAt(left));
        } catch (EmptyResultDataAccessException bucketEmpty) {
            // The guard refused: no token was available. Nothing was written, so
            // there is no partial state to clean up.
            return new Decision(false, capacity, capacity, nextTokenAt(0));
        }
    }

    /** Current position without consuming, for the recipient history endpoint. */
    public Decision peek(String recipientId) {
        Timestamp now = Timestamp.from(clock.instant());
        Double remaining;
        try {
            remaining = jdbc.queryForObject(PEEK, Double.class,
                    capacity, now, refillPerSecond, recipientId);
        } catch (EmptyResultDataAccessException noBucketYet) {
            remaining = null;
        }
        // No bucket yet means nothing has been sent, so the full budget is free.
        double left = remaining == null ? capacity : remaining;
        return new Decision(left >= 1, used(left), capacity, nextTokenAt(left));
    }

    /**
     * Whole tokens consumed, for reporting. Rounded up so a bucket sitting at 4.2
     * of 5 reports 1 used rather than 0 - the caller has spent a token and the
     * number they see should say so.
     */
    private int used(double remaining) {
        return Math.max(0, Math.min(capacity, (int) Math.ceil(capacity - remaining)));
    }

    /** When the next whole token becomes available, for {@code Retry-After}. */
    private Instant nextTokenAt(double remaining) {
        if (remaining >= 1) {
            return clock.instant();
        }
        double secondsUntilNext = (1 - remaining) / refillPerSecond;
        return clock.instant().plusMillis((long) Math.ceil(secondsUntilNext * 1000));
    }

    /**
     * @param admitted    whether this caller may proceed
     * @param used        whole tokens consumed of the capacity
     * @param limit       bucket capacity
     * @param windowReset when the next token is available
     */
    public record Decision(boolean admitted, int used, int limit, Instant windowReset) {
        public int remaining() {
            return Math.max(0, limit - used);
        }
    }
}
