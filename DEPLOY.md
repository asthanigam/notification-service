# Deploy runbook

Everything below is free tier. Total spend: ₹0.

Order matters — Render deploys *from* GitHub, so the repo has to exist first.
Budget ~25 minutes end to end.

---

## 0. Push the repo (prerequisite)

```bash
cd ~/Downloads/notification-service
gh repo create notification-service --public --source=. --remote=origin --push
```

Render needs to read this repo, so it must be pushed before step 2.

---

## 1. Neon — Postgres

1. [neon.tech](https://neon.tech) → sign in with GitHub → **Create project**.
2. Name it anything; pick the region closest to where you'll put Render (keeping
   both in the same region saves a cross-continent round trip on every query).
3. On the dashboard, open **Connection string** and copy it. It looks like:

```
postgresql://neondb_owner:npg_AbC123xyz@ep-cool-name-a1b2c3.us-east-2.aws.neon.tech/neondb?sslmode=require
```

### Split it into three values

This is the step people get wrong. The app takes the host in a **JDBC** URL and
the credentials **separately** — so the username and password come *out* of the
string:

| From the Neon string | Env var | Value |
|---|---|---|
| everything after `@`, prefixed `jdbc:postgresql://` | `DATABASE_URL` | `jdbc:postgresql://ep-cool-name-a1b2c3.us-east-2.aws.neon.tech/neondb?sslmode=require` |
| between `//` and `:` | `DATABASE_USER` | `neondb_owner` |
| between `:` and `@` | `DATABASE_PASSWORD` | `npg_AbC123xyz` |

Two things that will bite you if you skip them:

- **Keep `?sslmode=require`.** Neon rejects unencrypted connections; without it
  the app starts and then fails every query.
- **Do not leave the credentials inside `DATABASE_URL`.** The JDBC driver will
  read them, but they'd then also be sitting in a variable you're more likely to
  paste into a screenshot or a support ticket.

You do **not** need to create any tables. Flyway runs the three migrations on
first boot.

---

## 2. Render — the app

1. [render.com](https://render.com) → sign in with GitHub → **New** → **Web Service**.
2. Connect the `notification-service` repo.
3. Settings:
   - **Language / Runtime:** `Docker` — this is the one that matters. It must
     read the `Dockerfile`, not a buildpack.
   - **Instance type:** `Free`
   - **Branch:** `main`
   - Leave build and start commands **empty** — the Dockerfile defines both.
4. **Environment variables** (Advanced → Add Environment Variable):

| Key | Value |
|---|---|
| `DATABASE_URL` | the JDBC URL from step 1 |
| `DATABASE_USER` | from step 1 |
| `DATABASE_PASSWORD` | from step 1 |
| `LOG_FORMAT` | `ecs` |
| `LLM_API_KEY` | from step 3 — or leave unset for now |

5. **Create Web Service.** First build takes ~5 minutes (Maven downloads its
   dependencies inside the build stage).

Do **not** set `PORT` — Render injects it, and the app already binds to
`${PORT:8080}`. Verified: with `PORT=10000` the container binds 10000.

### Confirm it's alive

```bash
curl -sS https://<your-service>.onrender.com/readyz
# {"status":"ok","database":"ok"}   <- "database":"ok" means Neon is wired correctly
```

`/readyz` is the one to check, not `/healthz` — `/healthz` is liveness only and
returns ok even if the database is unreachable. That distinction is deliberate
(see `HealthController`), and it means `/readyz` is your real smoke test.

### Then run the gates against it

```bash
BASE_URL=https://<your-service>.onrender.com ./scripts/burst-dedup.sh
BASE_URL=https://<your-service>.onrender.com ./scripts/burst-ratelimit.sh
```

> **Wake it up first.** Render's free tier sleeps after ~15 minutes idle. The
> first request cold-starts the container and can take ~30–60s, which will make
> the burst scripts time out and look like a failure that isn't one. Always
> `curl .../healthz` once and wait for a response before firing a burst.

---

## 3. Groq — the LLM key (optional)

The service works fully without this — it takes the deterministic path and every
notification is delivered from the template. But the model path is what the
exercise is asking to see, so it's worth the two minutes.

1. [console.groq.com](https://console.groq.com) → sign in → **API Keys** →
   **Create API Key**.
2. Copy it (shown once) and add it to Render as `LLM_API_KEY`. Render redeploys.

No card required. If you hit the free rate limit, the service **takes the
fallback path and keeps sending** — that's the designed behaviour, and it shows
up as `event:llm_call outcome:http_429 fallback_taken:true` in the logs.

### Confirm the model path is live

```bash
curl -sS -X POST https://<your-service>.onrender.com/notifications \
  -H 'Content-Type: application/json' \
  -d '{"recipient_id":"demo","template":"payment_received",
       "variables":{"name":"Aastha","amount":"INR 2,499.00","order_id":"A-1001",
                    "receipt_url":"https://example.com/r/1001"},
       "idempotency_key":"llm-check-1"}'
```

`"body_source":"LLM"` means the rewrite passed the guard. `"FALLBACK"` means it
didn't — check the logs for the reason (`outcome:guard_rejected:link_altered`,
`outcome:timeout`, …). Both are correct behaviour; only the first proves the key
is working.

---

## 4. Better Stack — public logs

1. [betterstack.com/logs](https://betterstack.com/logs) → sign in → **Sources** →
   **Connect source**.
2. Platform: choose **Vector** or **HTTP** — either gives you an ingest token.
   Copy the **source token** and the ingest host.
3. In Render: your service → **Settings** → **Log Streams** → add an endpoint
   pointing at Better Stack.

> If Log Streams isn't available on the free plan, the fallback is to ship from
> the app instead: add Better Stack's logback appender and a `BETTERSTACK_TOKEN`
> env var. The app already emits ECS JSON on stdout, so nothing about the log
> *content* changes either way — only the transport.

4. **Share it read-only:** Better Stack → the dashboard/source view → **Share** →
   create a public link. Put that link at the top of `README.md`.

### Queries worth putting in the shared view

Because events are emitted as real fields rather than sentences, these are
filters, not regexes:

```
event:llm_call AND fallback_taken:true      # every time the model was bypassed
event:notification_rate_limited             # who is being throttled
event:notification_deduped_replay           # retries that correctly did not re-send
correlation_id:"<id from any response>"     # one request, end to end
```

Every HTTP response carries `X-Correlation-Id`, so that last query is how you go
from "this request looked wrong" to the exact log lines in one step.

---

## 5. Fill in the three links

Edit the table at the top of `README.md`:

```markdown
| **Live app** | https://<your-service>.onrender.com |
| **Logs (public)** | https://logs.betterstack.com/... |
```

Then send: the live URL, the repo URL, the logs URL, and the two burst scripts.

---

## Sanity checklist before you send it

```bash
BASE="https://<your-service>.onrender.com"

curl -sS "$BASE/healthz"                        # wake it, then:
curl -sS "$BASE/readyz"                         # {"status":"ok","database":"ok"}
curl -sS -o /dev/null -w '%{http_code}\n' "$BASE/"        # 200 — the UI
curl -sS "$BASE/metrics" | grep -c notifications_          # >0
BASE_URL="$BASE" ./scripts/burst-dedup.sh       # PASS
BASE_URL="$BASE" ./scripts/burst-ratelimit.sh   # PASS
```

- [ ] `/readyz` reports `"database":"ok"`
- [ ] Both burst scripts print PASS against the live URL
- [ ] A send returns `"body_source":"LLM"` (or FALLBACK with a logged reason)
- [ ] The public logs link opens without logging in — **check it in a private
      window**, otherwise you're testing your own session, not the share
- [ ] README's three links are filled in
