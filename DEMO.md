# Walkthrough

Every way to run this, and what each step is actually demonstrating.

**Live service:** <https://notification-service-1-43ia.onrender.com>

---

## Before you start: wake it

Render's free tier stops the container after ~15 minutes idle, and the next
request pays a ~30s cold start. Nothing is broken when that happens, but a burst
fired at a starting instance looks alarming.

```bash
curl https://notification-service-1-43ia.onrender.com/readyz
```

Wait for `{"status":"ok","database":"ok"}`. That answer means the process is up
*and* Neon is reachable — `/healthz` would say ok either way, which is the point
of having both.

The burst scripts do this themselves (polling `/readyz`, bounded at 120s), so
this is only needed if you are running individual `curl`s.

---

## Option A — the guided walkthrough

One command, eight steps, pauses between each:

```bash
BASE_URL=https://notification-service-1-43ia.onrender.com ./scripts/demo.sh
```

`AUTO=1` runs it without pauses, which is also the quickest way to warm the
service and confirm everything works.

| Step | What it shows | Why it matters |
|---|---|---|
| 0 | `/readyz` | Readiness includes the datastore; liveness deliberately does not, so a DB blip drains traffic instead of triggering a restart storm |
| 1 | A send, `body_source: LLM` | The model rewrote the tone and every protected fact survived the guard |
| 2 | Same key again → `200`, `deduped: true` | Same id, `attempts` unchanged — a replay, not a second send |
| 3 | Same key, different body → `409` | A key names one intent; two intents cannot share it |
| 4 | Injection in a variable → `400` | The caller-side trust boundary — see below |
| 5 | **Gate 1** — 40 concurrent, one key | Exactly 1 send, 39 replays, one id, zero 5xx |
| 6 | **Gate 2** — 40 concurrent, one recipient | Exactly 5 admitted, 35× `429`, zero 5xx |
| 7 | Recipient history + live rate-limit state | |
| 8 | `/metrics` | Counters the bursts just moved, plus latency histograms |

---

## Option B — the two gates on their own

These are the invariants the exercise says will be probed. Both **exit non-zero
if the invariant breaks**, so they work as checks rather than demos.

```bash
BASE_URL=https://notification-service-1-43ia.onrender.com ./scripts/burst-dedup.sh
BASE_URL=https://notification-service-1-43ia.onrender.com ./scripts/burst-ratelimit.sh
```

Tunable: `CONCURRENCY=100`, `LIMIT=5`, `RECIPIENT=…`. The recipient is randomised
per run, so they are immediately re-runnable without waiting for a window.

---

## Option C — entirely local, no credentials

```bash
docker compose up --build
BASE_URL=http://localhost:8080 ./scripts/demo.sh
```

Brings up app + Postgres. With no `LLM_API_KEY` the deterministic personaliser
runs, so everything works with an empty environment — that is also why the
fallback path is the one exercised on every local run and in CI.

To see the model path locally, pass a key through:

```bash
LLM_API_KEY=gsk_... docker compose up --build
```

---

## Option D — the tests

```bash
./mvnw test      # 39 unit/slice tests, no Docker
./mvnw verify    # + 5 integration tests against a real Postgres (needs Docker)
```

`verify` is where the concurrency invariants are proved. A `CountDownLatch`
starting gate releases every thread at once — a loop would let the first request
finish before the last began, which is not a race and would pass against code
with no concurrency control at all. The assertions read the **database**
(`delivery` row counts, the bucket value), not just response codes.

---

## The two things worth explaining

### Step 4 — why there are *two* trust boundaries, not one

The output guard was built first: the model's rewrite must preserve every
protected value verbatim and exactly the same set of URLs. Prompt wording is a
request to a system willing to be argued with; the guard is the enforcement.

Then a real injection was fired through the `name` variable. **The guard worked
perfectly** — the model obeyed the injected instruction, the rewrite lost its
facts, the guard rejected it, the deterministic fallback shipped.

**And the fallback still contained the attacker's link.**

Because by then that link was part of the deterministic render — which is the
ground truth the guard *defends*, not something it questions. The guard protects
the message from the **model**; it structurally cannot protect it from its own
**inputs**. So the caller became a second, independent boundary: links are
refused in any placeholder not named `*_url`.

That was found by attacking a running service, not by reasoning about the design.

### Step 6 — why a token bucket, not a fixed window

It *was* a fixed window. Its known cost was admitting up to `2 × limit` across a
window boundary, written down as "bounded and understood."

Then the burst ran against managed Postgres instead of localhost. Round trips
went from ~1ms to ~150ms, the burst got slow enough to straddle a minute
boundary, and it admitted **8 against a limit of 5**. It had passed locally every
time.

Latency made a documented weakness reachable — and a documented cost is still a
bug when it violates the requirement. A bucket has no boundary to straddle:
refill and consume happen in the same atomic statement, so the guarantee holds at
every instant.

---

## If something looks wrong

| Symptom | Cause |
|---|---|
| First request hangs ~30s | Free-tier cold start. The scripts wait it out; a bare `curl` does not. |
| A burst returns many 5xx | Almost certainly a deploy swapping instances. Re-run once `/readyz` reports `database: ok`. |
| `body_source: FALLBACK` | The model was slow, failed, or its output was rejected — all correct behaviour. The reason is in the logs as `outcome`. |
| Gate 2 admits fewer than 5 | That recipient already spent budget. Both scripts randomise the recipient per run; pass `RECIPIENT=` to pin one. |

Every response carries `X-Correlation-Id`. Filtering the
[public log dashboard](https://telemetry.betterstack.com/dashboards/hdXBjn) by it
reads one request end to end, LLM call included.
