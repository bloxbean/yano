#!/bin/bash
# Verify epoch nonces against reference data by polling the yaci node's REST API.
#
# Usage:
#   bash verify-nonces.sh <network> <rest-port> <target-epochs>
#   Example: bash verify-nonces.sh preprod 8080 8

set -e

NETWORK="${1:?Usage: verify-nonces.sh <network> <rest-port> <target-epochs>}"
REST_PORT="${2:?Provide REST port}"
TARGET_EPOCHS="${3:-8}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REFERENCE_FILE="$SCRIPT_DIR/epoch-nonce-data/epoch_param_nonce_${NETWORK}.json"

if [ ! -f "$REFERENCE_FILE" ]; then
    echo "ERROR: Reference file not found: $REFERENCE_FILE"
    exit 1
fi

echo "=== Epoch Nonce Verification: $NETWORK ==="
echo "REST endpoint: http://localhost:${REST_PORT}/api/v1/node/epoch-nonce"
echo "Reference file: $REFERENCE_FILE"
echo "Target: verify nonces for $TARGET_EPOCHS epochs"
echo ""

VERIFIED=0
FAILED=0
LAST_EPOCH=-1
SEEN_EPOCHS=""

while true; do
    # Poll the epoch nonce endpoint
    RESPONSE=$(curl -s "http://localhost:${REST_PORT}/api/v1/node/epoch-nonce" 2>/dev/null || echo "")

    if [ -z "$RESPONSE" ] || echo "$RESPONSE" | grep -q "error"; then
        echo "$(date +%H:%M:%S) Waiting for nonce state to initialize..."
        sleep 5
        continue
    fi

    CURRENT_EPOCH=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['epoch'])" 2>/dev/null || echo "")
    CURRENT_NONCE=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['nonce'])" 2>/dev/null || echo "")

    if [ -z "$CURRENT_EPOCH" ] || [ -z "$CURRENT_NONCE" ]; then
        echo "$(date +%H:%M:%S) Waiting for valid epoch data..."
        sleep 5
        continue
    fi

    # Check if we've already verified this epoch
    if [ "$CURRENT_EPOCH" = "$LAST_EPOCH" ]; then
        sleep 2
        continue
    fi
    LAST_EPOCH=$CURRENT_EPOCH

    # Look up reference nonce for this epoch
    REF_NONCE=$(python3 -c "
import json, sys
with open('$REFERENCE_FILE') as f:
    data = json.load(f)
for entry in data:
    if entry['epoch_no'] == $CURRENT_EPOCH:
        print(entry['nonce'])
        sys.exit(0)
print('NOT_FOUND')
" 2>/dev/null || echo "NOT_FOUND")

    if [ "$REF_NONCE" = "NOT_FOUND" ]; then
        echo "$(date +%H:%M:%S) Epoch $CURRENT_EPOCH: nonce=$CURRENT_NONCE (no reference - skipping)"
    elif [ "$CURRENT_NONCE" = "$REF_NONCE" ]; then
        VERIFIED=$((VERIFIED + 1))
        echo "$(date +%H:%M:%S) Epoch $CURRENT_EPOCH: MATCH ✓  nonce=$CURRENT_NONCE"
    else
        FAILED=$((FAILED + 1))
        echo "$(date +%H:%M:%S) Epoch $CURRENT_EPOCH: MISMATCH ✗"
        echo "  got:      $CURRENT_NONCE"
        echo "  expected: $REF_NONCE"
    fi

    if [ $VERIFIED -ge "$TARGET_EPOCHS" ]; then
        echo ""
        echo "=== RESULT: $VERIFIED/$TARGET_EPOCHS epochs verified, $FAILED failures ==="
        if [ $FAILED -eq 0 ]; then
            echo "ALL PASSED"
            exit 0
        else
            echo "SOME FAILURES"
            exit 1
        fi
    fi

    sleep 2
done
