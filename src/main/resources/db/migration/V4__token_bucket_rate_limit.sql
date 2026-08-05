-- Replaces the fixed-window counter with a token bucket.
--
-- WHY THIS MIGRATION EXISTS. The fixed window admitted up to 2 * limit across a
-- window boundary - documented as a known cost when it was written, and then
-- observed for real: a 40-request burst against a managed Postgres (~150ms per
-- round trip rather than ~1ms locally) takes long enough to straddle a minute
-- boundary, so it filled the end of one window and the start of the next and
-- admitted 8 where the limit was 5. Latency is what made a documented weakness
-- reachable, which is why it never showed up against a local database.
--
-- A token bucket has no boundary to straddle. Tokens refill continuously at
-- limit/window per second, so "no more than `limit` in any window" holds at every
-- instant rather than only within an arbitrary alignment.
--
-- Crucially it keeps the property that made the previous design correct: one row
-- per recipient, mutated only by a single atomic guarded upsert, so concurrent
-- callers for one recipient still serialise on one row for the length of one
-- statement and no count can be lost.
--
-- Rejected alternative: sliding-window log (one row per send, count rows inside
-- the window). It is exact, but making it correct under concurrency needs either
-- explicit locking or a CTE that is not actually serialisable - two concurrent
-- transactions read the same snapshot, both count 4, and both insert. That is the
-- lost update again, wearing a different hat.
DROP TABLE IF EXISTS rate_limit_window;

CREATE TABLE rate_limit_bucket (
    recipient_id VARCHAR(64)        PRIMARY KEY,
    -- Fractional on purpose: refill is continuous, so a bucket legitimately sits
    -- at 3.7 tokens. Rounding here would quietly leak or destroy budget.
    tokens       DOUBLE PRECISION   NOT NULL,
    -- Last time tokens were recomputed. Refill is derived from the gap between
    -- this and now, so no background job is needed to top buckets up - a bucket
    -- nobody touches costs nothing and is correct whenever it is next read.
    updated_at   TIMESTAMPTZ        NOT NULL
);

COMMENT ON TABLE rate_limit_bucket IS
    'Per-recipient token bucket; mutated only by an atomic guarded upsert that refills and consumes in one statement';
COMMENT ON COLUMN rate_limit_bucket.tokens IS
    'Tokens remaining after the last consume; refilled lazily from updated_at';
