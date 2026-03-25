#!/bin/bash
# ═══════════════════════════════════════════════════════════════
# Notification Engine - Load Test / Demo Script
# ═══════════════════════════════════════════════════════════════
# Usage: ./load-test.sh [count] [base_url]
#   count    - number of notifications to send (default: 100)
#   base_url - API base URL (default: http://localhost:8080)

set -e

COUNT=${1:-100}
BASE_URL=${2:-http://localhost:8080}
API_URL="$BASE_URL/api/v1/notifications"

CHANNELS=("EMAIL" "SMS" "PUSH")
PRIORITIES=("HIGH" "LOW")
TEMPLATES=("WELCOME_EMAIL" "OTP_SMS" "ORDER_CONFIRMATION_EMAIL" "PAYMENT_SUCCESS_PUSH" "LOW_BALANCE_SMS")

echo "═══════════════════════════════════════════════════════"
echo " Notification Engine Load Test"
echo "═══════════════════════════════════════════════════════"
echo " Target:  $API_URL"
echo " Count:   $COUNT notifications"
echo " Started: $(date)"
echo "═══════════════════════════════════════════════════════"

# Wait for service to be ready
echo ""
echo "Checking service health..."
for i in $(seq 1 30); do
    if curl -sf "$BASE_URL/api/v1/admin/health" > /dev/null 2>&1; then
        echo "✅ Service is ready!"
        break
    fi
    if [ $i -eq 30 ]; then
        echo "❌ Service not available after 30 seconds. Exiting."
        exit 1
    fi
    echo "  Waiting... ($i/30)"
    sleep 1
done

SUCCESS=0
FAILED=0
DUPLICATE=0
START_TIME=$(date +%s%N)

echo ""
echo "Sending $COUNT notifications..."
echo ""

for i in $(seq 1 $COUNT); do
    CHANNEL=${CHANNELS[$((RANDOM % ${#CHANNELS[@]}))]}
    PRIORITY=${PRIORITIES[$((RANDOM % ${#PRIORITIES[@]}))]}
    USER_ID="user-$((RANDOM % 50 + 1))"
    IDEMP_KEY="load-test-$(date +%s%N)-$i"

    # Build recipient based on channel
    case $CHANNEL in
        EMAIL) RECIPIENT="$USER_ID@example.com" ;;
        SMS)   RECIPIENT="+91$(printf '%010d' $((RANDOM * RANDOM % 10000000000)))" ;;
        PUSH)  RECIPIENT="device-token-$USER_ID-$(openssl rand -hex 8 2>/dev/null || echo $RANDOM)" ;;
    esac

    RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$API_URL" \
        -H "Content-Type: application/json" \
        -d "{
            \"idempotencyKey\": \"$IDEMP_KEY\",
            \"userId\": \"$USER_ID\",
            \"channel\": \"$CHANNEL\",
            \"priority\": \"$PRIORITY\",
            \"subject\": \"Load Test Notification #$i\",
            \"body\": \"This is load test notification number $i sent via $CHANNEL channel with $PRIORITY priority.\",
            \"recipient\": \"$RECIPIENT\",
            \"metadata\": {\"testId\": \"$i\", \"batchId\": \"load-test-$(date +%s)\"}
        }")

    HTTP_CODE=$(echo "$RESPONSE" | tail -1)
    BODY=$(echo "$RESPONSE" | head -n -1)

    case $HTTP_CODE in
        202) ((SUCCESS++)) ;;
        409) ((DUPLICATE++)) ;;
        *)   ((FAILED++))
             echo "  ❌ Failed ($HTTP_CODE): $BODY" ;;
    esac

    # Progress indicator
    if (( i % 10 == 0 )); then
        echo "  [$i/$COUNT] ✅=$SUCCESS ❌=$FAILED 🔁=$DUPLICATE"
    fi
done

END_TIME=$(date +%s%N)
ELAPSED_MS=$(( (END_TIME - START_TIME) / 1000000 ))
ELAPSED_SEC=$(echo "scale=2; $ELAPSED_MS / 1000" | bc)
TPS=$(echo "scale=2; $COUNT / $ELAPSED_SEC" | bc 2>/dev/null || echo "N/A")

echo ""
echo "═══════════════════════════════════════════════════════"
echo " Results"
echo "═══════════════════════════════════════════════════════"
echo "  Total:      $COUNT"
echo "  Success:    $SUCCESS"
echo "  Duplicate:  $DUPLICATE"
echo "  Failed:     $FAILED"
echo "  Time:       ${ELAPSED_SEC}s"
echo "  Throughput: ${TPS} req/s"
echo "═══════════════════════════════════════════════════════"

# Fetch stats
echo ""
echo "Fetching delivery stats..."
echo ""
curl -s "$API_URL/stats" | python3 -m json.tool 2>/dev/null || curl -s "$API_URL/stats"

echo ""
echo ""

# Test idempotency: send same key twice
echo "Testing idempotency (sending duplicate key)..."
IDEMP_KEY="idempotency-test-$(date +%s)"
curl -s -o /dev/null -w "  First send:  HTTP %{http_code}\n" -X POST "$API_URL" \
    -H "Content-Type: application/json" \
    -d "{
        \"idempotencyKey\": \"$IDEMP_KEY\",
        \"userId\": \"user-1\",
        \"channel\": \"EMAIL\",
        \"priority\": \"HIGH\",
        \"body\": \"Idempotency test\",
        \"recipient\": \"test@example.com\"
    }"

curl -s -o /dev/null -w "  Second send: HTTP %{http_code} (should be 202, returns existing)\n" -X POST "$API_URL" \
    -H "Content-Type: application/json" \
    -d "{
        \"idempotencyKey\": \"$IDEMP_KEY\",
        \"userId\": \"user-1\",
        \"channel\": \"EMAIL\",
        \"priority\": \"HIGH\",
        \"body\": \"Idempotency test\",
        \"recipient\": \"test@example.com\"
    }"

echo ""
echo "═══════════════════════════════════════════════════════"
echo " Load test completed at $(date)"
echo "═══════════════════════════════════════════════════════"
