-- One row per (recipient, fixed window). The counter is only ever moved by a
-- single atomic statement:
--
--   INSERT ... VALUES (recipient, window, 1)
--   ON CONFLICT (recipient_id, window_start)
--   DO UPDATE SET count = rate_limit_window.count + 1
--   WHERE rate_limit_window.count < :limit
--   RETURNING count;
--
-- Returning a row means admitted; returning nothing means the guard rejected the
-- increment and the caller gets a 429. Postgres takes a row lock for the duration
-- of the ON CONFLICT DO UPDATE, so concurrent callers for one recipient serialise
-- on that row and the count can never be lost or double-counted. Crucially the
-- lock is held for the length of one statement, not for the length of the request
-- - the LLM call happens well after this has committed.
--
-- Rejected alternatives:
--   * SELECT ... FOR UPDATE then UPDATE. Correct, but holds the row lock across a
--     round trip and invites someone to put slow work inside the critical section.
--   * Read count, compare in Java, write it back. The classic lost-update race:
--     N concurrent readers all see count = limit - 1 and all admit themselves.
--   * Redis INCR + EXPIRE. Genuinely good and the answer at scale, but it adds a
--     second datastore to keep alive on a free tier, and it moves the source of
--     truth for admission out of the database that already has to be up for the
--     send to succeed at all.
--
-- Fixed window rather than sliding, deliberately: it is one row and one statement,
-- and the failure mode is bounded and well understood - a caller can land up to
-- 2*limit sends across a window boundary. Sliding-window or token-bucket removes
-- that at the cost of either two counters or refill arithmetic in SQL. Named in
-- the write-up rather than silently ignored.
CREATE TABLE rate_limit_window (
    recipient_id VARCHAR(64) NOT NULL,
    -- Truncated to the window size by the application, using the injected Clock,
    -- so tests can move time instead of sleeping.
    window_start TIMESTAMPTZ NOT NULL,
    count        INT         NOT NULL,

    PRIMARY KEY (recipient_id, window_start)
);

-- Old windows are dead weight; nothing reads them once the window has passed.
-- Cleaned opportunistically rather than by a scheduled job - see RateLimiter.
CREATE INDEX ix_rate_limit_window_start ON rate_limit_window (window_start);

COMMENT ON TABLE rate_limit_window IS
    'Fixed-window per-recipient counters; mutated only by an atomic guarded upsert';
