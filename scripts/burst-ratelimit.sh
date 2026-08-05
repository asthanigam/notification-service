#!/usr/bin/env bash
#
# GATE 2 - rate limiting under concurrency (the hot-recipient case).
#
# Fires N concurrent requests at ONE recipient, each with a DISTINCT
# idempotency_key so dedup cannot mask an over-admit, and asserts that exactly
# the configured limit succeed and the rest are cleanly rejected with 429.
#
#   ./scripts/burst-ratelimit.sh
#   BASE_URL=https://your-app.onrender.com ./scripts/burst-ratelimit.sh
#   CONCURRENCY=100 LIMIT=5 ./scripts/burst-ratelimit.sh
#
# The recipient is randomised per run so the script is re-runnable immediately
# without waiting for a window to roll over.

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
CONCURRENCY="${CONCURRENCY:-40}"
LIMIT="${LIMIT:-5}"
RECIPIENT="${RECIPIENT:-rcpt-burst-$(date +%s)-$RANDOM}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# --- wake the service before measuring anything -------------------------------
# Render's free tier sleeps after ~15 minutes idle. Firing 40 concurrent requests
# at a sleeping instance means the first few absorb a ~30s cold start, time out,
# and the script reports a failure that is really just a platform waking up.
#
# So the script waits for the service to answer before it starts. This is not a
# courtesy - a benchmark that cannot tell "wrong" from "asleep" is not a
# measurement, and the person running it has no reason to know the difference.
# Bounded on wall clock, not on attempt count: a host that black-holes packets
# would make each curl burn its full --max-time, so "40 attempts" could mean
# twelve minutes. A deadline says what is actually meant - wait up to two
# minutes, then get on with it.
warmup() {
  local deadline=$(( $(date +%s) + 120 ))
  printf '  waking %s ' "$BASE_URL"
  while [ "$(date +%s)" -lt "$deadline" ]; do
    if curl -sS -o /dev/null --max-time 10 "$BASE_URL/healthz" 2>/dev/null; then
      printf ' awake\n\n'
      return 0
    fi
    printf '.'
    sleep 3
  done
  printf '\n'
  echo "  WARNING: no response within 120s; running anyway." >&2
  echo "  A failure below means the service is down rather than merely asleep." >&2
  echo >&2
}
warmup

echo "GATE 2: rate limit under concurrency (hot recipient)"
echo "  target      : $BASE_URL"
echo "  recipient   : $RECIPIENT   (fresh, so the window starts empty)"
echo "  concurrency : $CONCURRENCY  (distinct idempotency keys)"
echo "  limit       : $LIMIT per window"
echo

fire() {
  local i="$1"
  local payload
  payload=$(cat <<JSON
{"recipient_id":"$RECIPIENT",
 "template":"payment_received",
 "variables":{"name":"Aastha","amount":"INR 100.00","order_id":"B-$i",
              "receipt_url":"https://example.com/r/$i"},
 "idempotency_key":"burst-$RECIPIENT-$i"}
JSON
)
  # --max-time so a hung service fails loudly rather than hanging this script
  # forever. 120s is far above the observed p95 (~6s under a 40-way burst on a
  # 0.1-CPU free instance), so it cannot cause a false failure; a request that
  # exceeds it is a real problem, and curl reports 000, which counts as "other"
  # and fails the gate.
  curl -sS --max-time 120 -o "$WORK/body.$i" -w '%{http_code}' \
       -X POST "$BASE_URL/notifications" \
       -H 'Content-Type: application/json' \
       --data-binary "$payload" > "$WORK/code.$i" 2>"$WORK/err.$i"
}

for i in $(seq 1 "$CONCURRENCY"); do fire "$i" & done
wait

admitted=0; limited=0; server_error=0; other=0
for i in $(seq 1 "$CONCURRENCY"); do
  code="$(cat "$WORK/code.$i" 2>/dev/null || echo 000)"
  case "$code" in
    201) admitted=$((admitted+1)) ;;
    429) limited=$((limited+1)) ;;
    5*)  server_error=$((server_error+1)); other=$((other+1))
         echo "  5xx: $(cat "$WORK/body.$i" 2>/dev/null | head -c 200)" ;;
    *)   other=$((other+1))
         echo "  unexpected $code: $(cat "$WORK/body.$i" 2>/dev/null | head -c 200)" ;;
  esac
done

echo "  201 admitted      : $admitted"
echo "  429 rate limited  : $limited"
echo "  5xx server errors : $server_error"
echo "  other             : $((other - server_error))"
echo

fail=0
[ "$admitted"     -eq "$LIMIT" ]                || { echo "FAIL: expected exactly $LIMIT admitted, got $admitted"; fail=1; }
[ "$limited"      -eq $((CONCURRENCY-LIMIT)) ]  || { echo "FAIL: expected $((CONCURRENCY-LIMIT)) rejections, got $limited"; fail=1; }
[ "$server_error" -eq 0 ]                       || { echo "FAIL: $server_error server errors"; fail=1; }

# Cross-check against the service's own accounting, so a limiter that returned
# the right statuses while losing counts would still be caught.
state="$(curl -sS "$BASE_URL/recipients/$RECIPIENT/notifications?limit=1")"
used="$(printf '%s' "$state" | sed -n 's/.*"used":\([0-9]*\).*/\1/p')"
echo "  rate-limit counter reported by the service: used=${used:-?} / limit=$LIMIT"
[ "${used:-0}" -eq "$LIMIT" ] || { echo "FAIL: counter says $used, expected $LIMIT (lost or double counts)"; fail=1; }

echo
if [ "$fail" -eq 0 ]; then
  echo "PASS - $CONCURRENCY concurrent sends to one recipient, exactly $LIMIT admitted, no over-admit, zero 5xx."
else
  echo "FAILED"
fi
exit "$fail"
