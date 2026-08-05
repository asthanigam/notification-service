# Write-up

A notification service that dedupes, rate-limits, and personalises through an LLM.
Both concurrency invariants are decided by **one SQL statement each** — the
correctness argument lives in the statement, not in application code.

## 1. Data model

| Table | Purpose | The constraint that matters |
|---|---|---|
| `notification` | Request + outcome; doubles as the idempotency ledger | `UNIQUE (idempotency_key)` |
| `rate_limit_bucket` | One token bucket per recipient | `PRIMARY KEY (recipient_id)` |
| `delivery` | Append-only proof a send happened | `UNIQUE (notification_id)` |

The key lives on `notification` rather than its own table: a key and the outcome it
names have identical lifetimes, and splitting them opens a window where a crash
leaves a reserved key pointing at nothing. `delivery` is separate from
`notification.status` because they answer different questions — *what state is this
request in* vs *how many times did we actually send*. That is what makes the dedup
gate checkable by counting rows, enforced by an index rather than by the logic under
test. `RATE_LIMITED` is a recorded terminal state, so replaying a key returns the
same answer it gave the first time.

## 2. Dedup and rate limiting under concurrency

**Dedup** — `INSERT … ON CONFLICT (idempotency_key) DO NOTHING RETURNING id`. A row
back means you won the race and own the send; no row means you lost, *without having
read anything first*. The loser then branches: different fingerprint (SHA-256 of the
canonical request) → **409**; same fingerprint, terminal → replay verbatim; same
fingerprint, still `PENDING` → poll up to 3s for the winner, then replay.

**Rate limit** — token bucket, refilled and consumed in the *same* atomic statement:

```sql
INSERT INTO rate_limit_bucket AS b (recipient_id, tokens, updated_at)
VALUES (?, capacity - 1, now)
ON CONFLICT (recipient_id) DO UPDATE
   SET tokens = LEAST(capacity, b.tokens + elapsed_seconds * refill_rate) - 1,
       updated_at = now
 WHERE LEAST(capacity, b.tokens + elapsed_seconds * refill_rate) >= 1
RETURNING tokens;
```

Row back → admitted; no row → the guard found it empty → **429**. This is an
**atomic conditional UPDATE**: the `WHERE` is the condition, and it is evaluated by
the database inside the same statement that mutates the row. Postgres holds a row
lock for that one statement, so hot-recipient contention is one row wide and nothing
slow happens while it is held.

**Alternatives considered.** *Read-compare-write in Java*: the lost update — N
readers all see `limit-1` and all admit themselves. *`SELECT … FOR UPDATE`*: correct,
but holds a lock across a round trip and invites slow work into the critical section.
*Optimistic version + retry*: under a burst every contender retries, turning
contention into retry storms. *Redis `INCR`+`EXPIRE`*: right at scale, rejected here
because it adds a datastore and moves admission truth out of the database that must
already be up. *Sliding-window log*: exact, but correct-under-concurrency needs
locking or a CTE that is not serialisable — the lost update in a different hat.

**A fixed window was the first attempt, and it was a real bug.** Its known cost was
admitting `2 × limit` across a boundary; I wrote that down as "bounded and
understood". Then I ran the burst against *managed* Postgres: round trips went 1ms →
150ms, the burst straddled a minute boundary, and it admitted **8 against a limit of
5**. Latency made a documented weakness reachable. A documented cost is still a bug
when it violates the requirement.

**Ordering is the design:** claim key → consume budget → personalise → complete.
Dedup *before* rate limit, so retries of one intent do not each consume a slot. Steps
1–2 are single autocommit statements, so **the LLM call holds no lock and no
transaction** — a slow model costs one request's latency and blocks nothing else.

## 3. The LLM step — trust boundary, injection defence, fallback

Facts are substituted into the template **before** the model runs, producing a
deterministic ground truth. The model may only rewrite tone. Its output is then
required to still contain **every protected value verbatim** and **exactly the same
set of URLs** (none added, removed or rewritten), within 2.5× the length.

**The boundary is output validation, not prompt wording.** A prompt is where you
*ask* for good behaviour; the guard is where it is *enforced*. Prompt text is a
request to a system that is by construction willing to be argued with — and some of
the text arriving later is caller-supplied. So when an injection succeeds and the
model complies, the rewrite loses the real amount, fails validation, and the
deterministic body ships. **Injection is not prevented, it is made unprofitable** —
the attack becomes a fallback metric rather than a wrong message.

**The hole the guard structurally could not close.** I fired a real injection through
the `name` variable. The guard worked perfectly — model obeyed, rewrite lost its
facts, guard rejected it, fallback shipped — **and the fallback still contained
`evil.example`**, because by then the attacker's link was part of the deterministic
render, which is the ground truth the guard *defends* rather than questions. The
guard protects the message from the *model*; it cannot protect it from its own
*inputs*. The caller is a second, independent trust boundary, so links are now
refused in any placeholder not named `*_url`. I would not have found this by
reasoning about the design.

**Failure handling.** `Personalizer.personalize()` is **total** — never throws, never
returns null. Timeout (4s, bounding the whole exchange), connection refused, 401,
429, malformed JSON, guard rejection: all return the deterministic body tagged
`FALLBACK` plus an outcome string for metrics. No retry — doubling worst-case latency
to recover *tone* is not worth it. It can never duplicate, because personalisation
happens *after* the idempotency claim. Tested against a stub server that genuinely
sleeps past the deadline; a mocked client throwing `HttpTimeoutException` would only
prove the catch block compiles.

## 4. Scale and degradation

Contention is already per-recipient — one row — so recipients never block each other.
Next, gated on signal: Redis for the counter; shard on `recipient_id` (both tables
already key on it); then async sends, which makes LLM latency invisible rather than
merely bounded.

**If the LLM degrades:** already handled — timeout → deterministic body,
`llm.fallback` climbs, sends keep working with worse prose. `body_source` is a
*column*, not just a log line, so the fallback rate stays answerable by SQL when the
log pipeline is the thing that is broken. **If the DB degrades:** sends fail loudly —
the durable record *is* the send. `/readyz` fails and drains traffic; `/healthz` stays
up so a blip does not trigger a restart storm.

## 5. How the tests prove it

A `CountDownLatch` starting gate releases every thread at once — a loop would let the
first request finish before the last began, which is not a race and would pass
against code with no concurrency control at all. Assertions read the **database**
(`delivery` row counts, the bucket value), not just response codes: a bug returning
correct statuses while writing two deliveries passes the first check and fails the
second. Zero 5xx is part of the gate. 39 unit + 5 integration tests; both live gate
scripts exit non-zero on violation.

## 6. Containerize / deploy / observe

Multi-stage Dockerfile (JDK+Maven → JRE-alpine), non-root uid 10001, `HEALTHCHECK` on
`/healthz` — deliberately not `/readyz`, since a DB blip should drain traffic, not
convince Docker to kill a healthy process. `docker compose up --build` works with an
**entirely empty environment**; no secret has a real default.

Structured ECS JSON, correlation id per request (sanitised — it lands in a response
header and in log lines). Events are **fields, not sentences**, so
`event:llm_call AND fallback_taken:true` is a filter rather than a regex. Never
logged: `variables` or `personalized_body` — they carry caller PII, and the point of a
third-party backend is that logs leave your infrastructure. The app ships its own logs
because Render's log streaming is a paid feature; the appender uses a bounded queue and
`offer` not `put`, so **logging can never block a request thread**. Counters are
registered at startup so a fresh instance shows a line at zero rather than nothing —
an empty query result is indistinguishable from a typo'd metric name. Latency is
published as histogram buckets, which aggregate across instances via
`histogram_quantile()`; client-side p99s cannot be summed.

## 7. AI usage — directed vs. decided

**Decided (mine):** the one-atomic-statement approach for both invariants and the
rejection of read-then-write, `FOR UPDATE`, and optimistic retry; dedup-before-rate-
limit ordering; keeping the LLM call outside every lock; **making the trust boundary
output validation rather than prompt engineering** — the most important call here;
`RATE_LIMITED` as a recorded state; `delivery` as a separate table so the gate is
checkable independently of the logic it checks.

**Directed:** I specified the statements and the ordering, then had the
implementations written, with comments that state *why* and name the rejected
alternative. Then I directed adversarial review — "test the guard from the attacker's
side, not the happy path."

**Where I overrode it:** the guard originally classified a swapped URL as
`protected_value_missing` — a correct rejection with the wrong signal, so I reordered
link checks first. And `/metrics` initially collided with actuator's own endpoint,
which won and served a JSON name-list to anything expecting a scrape — caught by
checking the endpoint rather than trusting the config.

**Both real bugs came from running it, not reading it.** The rate limiter broke only
against real network latency; the injection hole appeared only against a real model.

## 8. Cost: ₹0

Render free (sleeps ~15 min — the burst scripts self-wake, and a scheduled GitHub
Action pings every 10 min), Neon free (0.5 GB, scales to zero), Groq free
(rate-limited — **hitting it takes the fallback path**, which is the designed
behaviour), Better Stack free (~1 GB/month), GitHub Actions free on public repos.
Nothing here bills.
