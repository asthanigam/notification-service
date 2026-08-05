# Notification service

Dedupes, rate-limits, and personalises notifications through an LLM — with the
concurrency correctness in the SQL rather than in application code.

Java 21 · Spring Boot 3.5 · PostgreSQL 16 · Flyway · Testcontainers · Groq

| | |
|---|---|
| **Live app** | **https://notification-service-1-43ia.onrender.com** |
| **Logs (public, no login)** | **https://telemetry.betterstack.com/dashboards/hdXBjn** |
| **Metrics (public)** | [`/metrics`](https://notification-service-1-43ia.onrender.com/metrics) — Prometheus scrape |
| **Write-up** | [WRITEUP.md](WRITEUP.md) — data model, concurrency argument, LLM trust boundary |
| **Deploy runbook** | [DEPLOY.md](DEPLOY.md) — Neon, Render, Groq, Better Stack, step by step |

> **Cold starts are handled for you.** Render's free tier sleeps after ~15 min
> idle and the next request pays a ~30s cold start. Both burst scripts wait for
> `/healthz` to answer before they measure anything (bounded at 120s), so a
> sleeping service reads as a slow start rather than a failed gate. A scheduled
> GitHub Action also pings every 10 minutes to keep it warm — best-effort, since
> GitHub's scheduler can run late, which is exactly why the scripts do not rely
> on it.

### Verified against the live deployment

```
GET /readyz  → {"database":"ok","status":"ok"}          Neon reachable from Render

GATE 1  40 concurrent, same idempotency_key
        → 1x201, 39x200, one id, attempts=1, zero 5xx                    PASS
GATE 2  40 concurrent, one recipient, distinct keys
        → exactly 5x201, 35x429, counter used=5/5, zero 5xx              PASS

POST /notifications           → body_source: LLM        Groq live, guard passed
POST with a link in `name`    → 400 "must not contain a link"  caller-side guard live
```

---

## Run it in one command

Needs Docker. Nothing else — no JDK, no Maven, **no credentials**.

```bash
docker compose up --build
```

Open <http://localhost:8080>. With no `LLM_API_KEY` set the service runs the
deterministic personaliser, so it works fully out of the box; add a key later to
see the model path.

---

## The two correctness gates

Both scripts run against any URL and **exit non-zero if the invariant breaks**, so
they work as checks, not just demos.

```bash
# Gate 1 — 40 concurrent requests, SAME idempotency_key → sent exactly once
./scripts/burst-dedup.sh

# Gate 2 — 40 concurrent requests, ONE recipient, distinct keys → exactly the limit
./scripts/burst-ratelimit.sh

# against the deployed service
BASE_URL=https://your-app.onrender.com ./scripts/burst-dedup.sh
BASE_URL=https://your-app.onrender.com ./scripts/burst-ratelimit.sh
```

Actual local output:

```
GATE 1: dedup under concurrency
  201 created (a send happened) : 1
  200 replayed (deduped)        : 39
  5xx server errors             : 0
  distinct notification ids     : 1
  attempts recorded on the notification: 1  (1 = sent once)
PASS - fired 40 concurrent identical requests, sent exactly once.

GATE 2: rate limit under concurrency (hot recipient)
  201 admitted      : 5
  429 rate limited  : 35
  5xx server errors : 0
  rate-limit counter reported by the service: used=5 / limit=5
PASS - 40 concurrent sends to one recipient, exactly 5 admitted, no over-admit, zero 5xx.
```

---

## API

| | |
|---|---|
| `POST /notifications` | Send. **201** sent · **200** deduped replay · **429** rate limited · **409** same key, different body |
| `GET /notifications/{id}` | Status, personalised body, attempts, created_at |
| `GET /recipients/{id}/notifications` | Recent notifications + current rate-limit state |
| `GET /healthz` | Liveness — process only, never touches the DB |
| `GET /readyz` | Readiness — includes the datastore |
| `GET /metrics` | Prometheus scrape |

```bash
curl -X POST localhost:8080/notifications \
  -H 'Content-Type: application/json' \
  -d '{"recipient_id":"user-42",
       "template":"payment_received",
       "variables":{"name":"Aastha","amount":"INR 2,499.00",
                    "order_id":"A-1001","receipt_url":"https://example.com/r/1001"},
       "idempotency_key":"key-001"}'
```

Templates are a fixed server-side set (`payment_received`, `order_shipped`,
`payment_failed`, `welcome`) — never caller-supplied, which would be template
injection.

Rate-limit state is also returned as headers: `X-RateLimit-Limit`,
`X-RateLimit-Remaining`, `X-RateLimit-Reset`, plus `Retry-After` on a 429. Every
response carries `X-Correlation-Id`, which is the value to grep the logs by.

---

## How the invariants hold

Both are one SQL statement. No check-then-act, no lock held across a round trip.
Full reasoning and rejected alternatives in [WRITEUP.md](WRITEUP.md#2-dedup-and-rate-limiting-under-concurrency).

**Dedup** — `INSERT … ON CONFLICT (idempotency_key) DO NOTHING RETURNING id`.
Row returned = you won the race and own the send; no row = you lost, replay the
original outcome. Postgres admits exactly one insert out of any burst.

**Rate limit** — a token bucket, refilled and consumed in one guarded upsert:

```sql
INSERT INTO rate_limit_bucket AS b (recipient_id, tokens, updated_at)
VALUES (?, capacity - 1, now)
ON CONFLICT (recipient_id) DO UPDATE
   SET tokens = LEAST(capacity, b.tokens + elapsed_seconds * refill_rate) - 1,
       updated_at = now
 WHERE LEAST(capacity, b.tokens + elapsed_seconds * refill_rate) >= 1
RETURNING tokens;
```

Row returned = admitted; no row = 429. Concurrent callers for one recipient
serialise on a single row for the length of one statement.

This was a fixed-window counter first. Against a managed Postgres the ~150ms
round trip made a 40-request burst straddle a minute boundary and it admitted
**8 against a limit of 5** — the `2 x limit` boundary cost, which passed locally
every time because localhost was too fast to expose it. A bucket has no boundary
to straddle. [Full story in the write-up](WRITEUP.md#2-dedup-and-rate-limiting-under-concurrency).

**The ordering matters:** claim key → consume budget → *then* personalise. The LLM
call is outside every lock and transaction, so a slow model costs one request's
latency and blocks nothing.

---

## The LLM step

The trust boundary is **output validation, not prompt wording**. Facts are
substituted deterministically *before* the model runs; the model may only rewrite
tone; its output must still contain every protected value verbatim and introduce
no new link. A successful prompt injection therefore fails validation and ships
the deterministic body — the attack becomes a metric, not a wrong message.

Never blocks, drops, or duplicates: 4s timeout, no retry, total interface (never
throws), and personalisation runs *after* the idempotency claim.

Details: [WRITEUP.md §3](WRITEUP.md#3-the-llm-step--trust-boundary-injection-defence-fallback).

---

## Tests

```bash
./mvnw test      # 39 unit tests, no Docker needed
./mvnw verify    # + 5 Testcontainers integration tests (needs Docker)
```

`mvn verify` is where the two concurrency invariants are proved, against a real
Postgres over real HTTP, using a `CountDownLatch` starting gate so requests
genuinely overlap. The assertions read the **database** (`delivery` row counts,
the counter value), not only the response codes — a bug that returns correct
statuses while writing two deliveries fails the second check.

CI runs `mvn verify` **and** builds the image on every push.

---

## Deploy

Free tier throughout, ₹0. Deploys the **container image**, not a buildpack.

### 1. Database — Neon

Create a project at [neon.tech](https://neon.tech) and copy the connection string.
Flyway applies the schema on first boot; there is no manual SQL step.

### 2. App — Render

New → **Web Service** → connect this repo → Runtime **Docker**. Render reads the
`Dockerfile`. Set these environment variables:

| Variable | Value |
|---|---|
| `DATABASE_URL` | `jdbc:postgresql://<neon-host>/<db>?sslmode=require` |
| `DATABASE_USER` | your Neon user |
| `DATABASE_PASSWORD` | your Neon password |
| `LLM_API_KEY` | Groq key from [console.groq.com](https://console.groq.com) (optional) |
| `LOG_FORMAT` | `ecs` |
| `LOG_SHIP_URL` / `LOG_SHIP_TOKEN` | Better Stack ingest URL + token (optional) |

> **Note the Neon URL is JDBC-shaped** — Neon gives you
> `postgresql://user:pass@host/db`; this app takes the host in `DATABASE_URL` and
> the credentials separately. See [.env.example](.env.example).

> **Render free tier sleeps after ~15 min idle.** The first request after a quiet
> period is a slow cold start. Hit `/healthz` once before running the gate
> scripts, or keep it warm with a free cron ping.

### 3. Logs — Better Stack

Create a source, copy its ingest URL and token, and set `LOG_SHIP_URL` /
`LOG_SHIP_TOKEN` in Render. The app ships its own logs — Render's log streaming
to an external destination is a paid feature, and shipping from inside the
process also carries the MDC through as real fields rather than hoping the
platform re-parses a line. Unset both and no appender is attached at all.
Share the source read-only and put the link at the top of this README.

Useful queries once connected:

```
event:llm_call AND fallback_taken:true     # every time the model was bypassed
event:notification_rate_limited            # who is getting throttled
correlation_id:"<id from a response>"      # one request, end to end
```

---

## Configuration

Everything is env-driven; nothing sensitive is committed. See
[.env.example](.env.example).

| Variable | Default | Notes |
|---|---|---|
| `DATABASE_URL` / `_USER` / `_PASSWORD` | local compose values | |
| `LLM_API_KEY` | *(empty)* | Empty ⇒ deterministic personaliser. The service is fully functional without it. |
| `LLM_MODEL` | `llama-3.1-8b-instant` | |
| `LLM_REQUEST_TIMEOUT` | `4s` | Bounds the whole exchange |
| `RATE_LIMIT_PER_RECIPIENT` | `5` | |
| `RATE_LIMIT_WINDOW` | `60s` | |
| `LOG_FORMAT` | `ecs` | Structured JSON |

---

## If something goes wrong

| Symptom | Cause |
|---|---|
| First request to the live URL hangs ~30s | Render free tier cold start. Hit `/healthz` first. |
| `Could not find a valid Docker environment` in tests | No Docker daemon — use `./mvnw test`. If `docker run hello-world` works, it's the Docker 29 API-version pin (already set in the pom). |
| Every body is identical to the template | No `LLM_API_KEY` — that's the deterministic fallback working as designed. Check for `llm_call` with `outcome:disabled`. |
| Gate 2 admits fewer than the limit | The recipient already used budget this window. Both scripts randomise the recipient per run; pass `RECIPIENT=` to override. |
