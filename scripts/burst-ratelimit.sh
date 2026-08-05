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
  curl -sS -o "$WORK/body.$i" -w '%{http_code}' \
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
