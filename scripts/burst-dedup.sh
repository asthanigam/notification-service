#!/usr/bin/env bash
#
# GATE 1 - deduplication under concurrency.
#
# Fires N concurrent requests carrying the SAME idempotency_key and asserts that
# exactly one send happened.
#
#   ./scripts/burst-dedup.sh                              # against localhost:8080
#   BASE_URL=https://your-app.onrender.com ./scripts/burst-dedup.sh
#   CONCURRENCY=100 ./scripts/burst-dedup.sh
#
# Exits non-zero if the invariant is violated, so it can be used as a check
# rather than only read.

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
CONCURRENCY="${CONCURRENCY:-40}"
KEY="${IDEMPOTENCY_KEY:-dedup-$(date +%s)-$RANDOM}"
RECIPIENT="${RECIPIENT:-rcpt-dedup-$(date +%s)-$RANDOM}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

PAYLOAD=$(cat <<JSON
{"recipient_id":"$RECIPIENT",
 "template":"payment_received",
 "variables":{"name":"Aastha","amount":"INR 2,499.00","order_id":"A-1001",
              "receipt_url":"https://example.com/r/1001"},
 "idempotency_key":"$KEY"}
JSON
)

# --- wait for READINESS before measuring anything ------------------------------
# Render's free tier sleeps after ~15 minutes idle, and a deploy swaps instances.
# Firing 40 concurrent requests at a starting instance produces a wall of 5xx and
# the script reports a failure that is really a platform still coming up.
#
# This polls /readyz, not /healthz, and that distinction is the whole point.
# /healthz answers as soon as the process is alive - which on this service is
# before the connection pool has reached the database, so a burst launched on a
# healthz-only signal still gets 5xx. /readyz is the endpoint that says the
# datastore is actually reachable, so it is the only honest gate to start from.
# (Learned by running it: a burst fired during a deploy swap returned 40 server
# errors against a service that was completely fine thirty seconds later.)
# Bounded on wall clock, not on attempt count: a host that black-holes packets
# would make each curl burn its full --max-time, so "40 attempts" could mean
# twelve minutes. A deadline says what is actually meant - wait up to two
# minutes, then get on with it.
warmup() {
  local deadline=$(( $(date +%s) + 120 ))
  printf '  waking %s ' "$BASE_URL"
  while [ "$(date +%s)" -lt "$deadline" ]; do
    # Requires the body to actually report the database as ok - a 200 with
    # {"status":"unavailable"} is a readiness failure, not readiness.
    if curl -sS --max-time 10 "$BASE_URL/readyz" 2>/dev/null | grep -q '"database":"ok"'; then
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

echo "GATE 1: dedup under concurrency"
echo "  target      : $BASE_URL"
echo "  concurrency : $CONCURRENCY  (all sharing one idempotency_key)"
echo "  key         : $KEY"
echo

# One curl per slot, all launched before any is awaited. Each writes its status
# and body to its own file so nothing is interleaved or lost.
fire() {
  local i="$1"
  # --max-time so a hung service fails loudly rather than hanging this script
  # forever. 120s is far above the observed p95 (~6s under a 40-way burst on a
  # 0.1-CPU free instance), so it cannot cause a false failure; a request that
  # exceeds it is a real problem, and curl reports 000, which counts as "other"
  # and fails the gate.
  curl -sS --max-time 120 -o "$WORK/body.$i" -w '%{http_code}' \
       -X POST "$BASE_URL/notifications" \
       -H 'Content-Type: application/json' \
       --data-binary "$PAYLOAD" > "$WORK/code.$i" 2>"$WORK/err.$i"
}

for i in $(seq 1 "$CONCURRENCY"); do fire "$i" & done
wait

created=0; replayed=0; other=0; server_error=0
declare -a ids=()

for i in $(seq 1 "$CONCURRENCY"); do
  code="$(cat "$WORK/code.$i" 2>/dev/null || echo 000)"
  body="$(cat "$WORK/body.$i" 2>/dev/null || echo '')"
  id="$(printf '%s' "$body" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')"
  [ -n "$id" ] && ids+=("$id")
  case "$code" in
    201) created=$((created+1)) ;;
    200) replayed=$((replayed+1)) ;;
    5*)  server_error=$((server_error+1)); other=$((other+1)) ;;
    *)   other=$((other+1)); echo "  unexpected $code: $body" ;;
  esac
done

unique_ids=$(printf '%s\n' "${ids[@]:-}" | sort -u | grep -c . || true)

echo "  201 created (a send happened) : $created"
echo "  200 replayed (deduped)        : $replayed"
echo "  5xx server errors             : $server_error"
echo "  distinct notification ids     : $unique_ids"
echo

fail=0
[ "$created"      -eq 1 ]              || { echo "FAIL: expected exactly 1 send, got $created"; fail=1; }
[ "$replayed"     -eq $((CONCURRENCY-1)) ] || { echo "FAIL: expected $((CONCURRENCY-1)) replays, got $replayed"; fail=1; }
[ "$server_error" -eq 0 ]              || { echo "FAIL: $server_error server errors"; fail=1; }
[ "$unique_ids"   -eq 1 ]              || { echo "FAIL: callers saw $unique_ids different ids"; fail=1; }

# Independent confirmation from the service's own view, rather than trusting the
# response codes we just counted.
if [ ${#ids[@]} -gt 0 ]; then
  final="$(curl -sS "$BASE_URL/notifications/${ids[0]}")"
  attempts="$(printf '%s' "$final" | sed -n 's/.*"attempts":\([0-9]*\).*/\1/p')"
  echo "  attempts recorded on the notification: ${attempts:-?}  (1 = sent once)"
  [ "${attempts:-0}" -eq 1 ] || { echo "FAIL: attempts=$attempts, expected 1"; fail=1; }
fi

echo
if [ "$fail" -eq 0 ]; then
  echo "PASS - fired $CONCURRENCY concurrent identical requests, sent exactly once."
else
  echo "FAILED"
fi
exit "$fail"
