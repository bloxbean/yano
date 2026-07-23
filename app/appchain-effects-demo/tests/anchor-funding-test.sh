#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TOOL="$SCRIPT_DIR/../tools/anchor_funding.py"
DEMO_SH="$SCRIPT_DIR/../demo.sh"

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
ready() { printf '%s' "$2" | python3 "$TOOL" $1; }

TX1="$(printf '11%.0s' {1..32})"
TX2="$(printf '22%.0s' {1..32})"
ONE_ADA="[{\"tx_hash\":\"$TX1\",\"output_index\":0,\"amount\":[{\"unit\":\"lovelace\",\"quantity\":1000000}]}]"
ONE_ADA_STRING="[{\"tx_hash\":\"$TX1\",\"output_index\":0,\"amount\":[{\"unit\":\"lovelace\",\"quantity\":\"1000000\"}]}]"
ONE_LARGE="[{\"tx_hash\":\"$TX1\",\"output_index\":0,\"amount\":[{\"unit\":\"lovelace\",\"quantity\":20000000}]}]"
PUBLIC_READY="[{\"tx_hash\":\"$TX1\",\"output_index\":0,\"amount\":[{\"unit\":\"lovelace\",\"quantity\":5000000}]},{\"tx_hash\":\"$TX2\",\"output_index\":1,\"amount\":[{\"unit\":\"lovelace\",\"quantity\":15000000}]}]"
PUBLIC_LOW_TOTAL="[{\"tx_hash\":\"$TX1\",\"output_index\":0,\"amount\":[{\"unit\":\"lovelace\",\"quantity\":5000000}]},{\"tx_hash\":\"$TX2\",\"output_index\":1,\"amount\":[{\"unit\":\"lovelace\",\"quantity\":10000000}]}]"
WITH_ASSET="[{\"tx_hash\":\"$TX1\",\"output_index\":0,\"amount\":[{\"unit\":\"lovelace\",\"quantity\":50000000},{\"unit\":\"policy.asset\",\"quantity\":1}]},{\"tx_hash\":\"$TX2\",\"output_index\":1,\"amount\":[{\"unit\":\"lovelace\",\"quantity\":10000000}]}]"
NON_INTEGER="[{\"tx_hash\":\"$TX1\",\"output_index\":0,\"amount\":[{\"unit\":\"lovelace\",\"quantity\":1000000.0}]}]"
BOOLEAN="[{\"tx_hash\":\"$TX1\",\"output_index\":0,\"amount\":[{\"unit\":\"lovelace\",\"quantity\":true}]}]"
LEADING_ZERO="[{\"tx_hash\":\"$TX1\",\"output_index\":0,\"amount\":[{\"unit\":\"lovelace\",\"quantity\":\"01000000\"}]}]"
TOO_LARGE="[{\"tx_hash\":\"$TX1\",\"output_index\":0,\"amount\":[{\"unit\":\"lovelace\",\"quantity\":9223372036854775808}]}]"
DUPLICATE_OUTPOINT="[{\"tx_hash\":\"$TX1\",\"output_index\":0,\"amount\":[{\"unit\":\"lovelace\",\"quantity\":10000000}]},{\"tx_hash\":\"$TX1\",\"output_index\":0,\"amount\":[{\"unit\":\"lovelace\",\"quantity\":10000000}]}]"
MISSING_OUTPOINT='[{"amount":[{"unit":"lovelace","quantity":20000000}]}]'

ready '' "$ONE_ADA" || fail "devnet readiness rejected one 1 ADA UTxO"
ready '' "$ONE_ADA_STRING" || fail "devnet readiness rejected a canonical decimal-string quantity"
if ready --public "$ONE_LARGE"; then fail "public readiness accepted one reusable UTxO"; fi
ready --public "$PUBLIC_READY" || fail "public readiness rejected collateral + spend UTxOs"
if ready --public "$PUBLIC_LOW_TOTAL"; then fail "public readiness ignored total reserve"; fi
if ready --public "$WITH_ASSET"; then fail "public readiness treated a native-asset UTxO as pure ADA"; fi
if ready '' "$NON_INTEGER"; then fail "devnet readiness accepted a non-integer JSON quantity"; fi
if ready '' "$BOOLEAN"; then fail "devnet readiness accepted a boolean JSON quantity"; fi
if ready '' "$LEADING_ZERO"; then fail "devnet readiness accepted a non-canonical decimal string"; fi
if ready '' "$TOO_LARGE"; then fail "devnet readiness accepted an out-of-range quantity"; fi
if ready --public "$DUPLICATE_OUTPOINT"; then fail "public readiness counted one outpoint twice"; fi
if ready '' "$MISSING_OUTPOINT"; then fail "devnet readiness accepted a UTxO without an identity"; fi
if printf '%s' '{invalid' | python3 "$TOOL" --public; then fail "invalid JSON was ready"; fi

bash -n "$DEMO_SH" || fail "demo launcher has invalid shell syntax"
if grep -Fq 'funding_args[@]' "$DEMO_SH"; then
  fail "demo launcher uses an empty-array expansion that fails under macOS Bash 3.2 with set -u"
fi

printf 'PASS: anchor funding readiness requires collateral, spend and reserve UTxOs\n'
