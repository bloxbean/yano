#!/bin/bash
# e2e-tests/*.py against disposable devnets. Usage: e2e.sh <jvm|native> [http] [n2n]
MODE=${1:-jvm}
source "$(dirname "$0")/common.sh"
HP=${2:-7181}; NP=${3:-13451}
PY="python3 -u"
BASE=http://localhost:$HP/api/v1
assert_ports_free $HP $NP || exit 2
FAIL=()

echo "######## [1] endpoint smoke (debug+mutating) + functional (+catch-up) on regular devnet ($MODE)"
RUN=$SP/runs/e2e-$MODE-regular; rm -rf "$RUN"; mkdir -p "$RUN"; cp "$REPO/app/config/network/devnet/shelley-genesis.json" "$RUN/"
start_yano "$MODE" "$RUN" $HP $NP "$RUN/yano.log" -Dquarkus.profile=devnet -Dyano.genesis.shelley-genesis-file=$RUN/shelley-genesis.json
wait_ready $HP 60 || { tail -20 "$RUN/yano.log"; kill_tracked; echo "VERDICT: FAIL (regular devnet not ready)"; exit 1; }
sleep 5
cd "$REPO/e2e-tests"
$PY yano_endpoint_smoke.py --base-url $BASE --include-debug --include-mutating > "$RUN/smoke.out" 2>&1; RC=$?; echo "smoke exit=$RC"; [ $RC = 0 ] || FAIL+=(smoke)
tail -25 "$RUN/smoke.out"
$PY yano_devnet_functional.py --base-url $BASE --include-catch-up > "$RUN/functional.out" 2>&1; RC=$?; echo "functional exit=$RC"; [ $RC = 0 ] || FAIL+=(functional)
grep -E '^(PASS|FAIL|SKIP)|passed|failed' "$RUN/functional.out" | tail -30
cd - >/dev/null
echo "yano ERROR lines: $(grep -c ' ERROR ' "$RUN/yano.log")"; grep ' ERROR ' "$RUN/yano.log" | cut -c1-220 | sed -E 's/^[0-9-]+ [0-9:,]+ //' | sort | uniq -c | sort -rn | head -8
kill_tracked

echo "######## [2] functional --include-shift --require-shift on fresh past-time-travel devnet ($MODE)"
RUN=$SP/runs/e2e-$MODE-ptt; rm -rf "$RUN"; mkdir -p "$RUN"; cp "$REPO/app/config/network/devnet/shelley-genesis.json" "$RUN/"
start_yano "$MODE" "$RUN" $HP $NP "$RUN/yano.log" -Dquarkus.profile=devnet -Dyano.genesis.shelley-genesis-file=$RUN/shelley-genesis.json -Dyano.block-producer.past-time-travel-mode=true
wait_ready $HP 60 || { tail -20 "$RUN/yano.log"; kill_tracked; echo "VERDICT: FAIL (past-time-travel devnet not ready)"; exit 1; }
sleep 3
cd "$REPO/e2e-tests"
$PY yano_devnet_functional.py --base-url $BASE --include-shift --require-shift --include-catch-up > "$RUN/functional.out" 2>&1; RC=$?; echo "functional-shift exit=$RC"; [ $RC = 0 ] || FAIL+=(functional-shift)
grep -E '^(PASS|FAIL|SKIP)|passed|failed' "$RUN/functional.out" | tail -30
cd - >/dev/null
echo "yano ERROR lines: $(grep -c ' ERROR ' "$RUN/yano.log")"; grep ' ERROR ' "$RUN/yano.log" | cut -c1-220 | sed -E 's/^[0-9-]+ [0-9:,]+ //' | sort | uniq -c | sort -rn | head -8
kill_tracked
if [ ${#FAIL[@]} -eq 0 ]; then echo "VERDICT: PASS"; else echo "VERDICT: FAIL (${FAIL[*]})"; fi
