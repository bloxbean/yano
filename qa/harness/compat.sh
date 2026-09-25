#!/bin/bash
# compat-tests/run-suite.sh against a disposable devnet (block-time 20000 as the suite expects).
# Usage: compat.sh <jvm|native> <label> [extra run-suite args]
MODE=${1:-jvm}; LABEL=${2:-qa-$MODE}; shift 2
source "$(dirname "$0")/common.sh"
HP=7191; NP=13461
assert_ports_free $HP $NP || exit 2
RUN=$SP/runs/compat-$MODE; rm -rf "$RUN"; mkdir -p "$RUN"; cp "$REPO/app/config/network/devnet/shelley-genesis.json" "$RUN/"
start_yano "$MODE" "$RUN" $HP $NP "$RUN/yano.log" -Dquarkus.profile=devnet -Dyano.genesis.shelley-genesis-file=$RUN/shelley-genesis.json -Dyano.block-producer.block-time-millis=20000
wait_ready $HP 90 || { tail -20 "$RUN/yano.log"; kill_tracked; echo "VERDICT: FAIL (devnet not ready)"; exit 1; }
sleep 5
"$REPO/compat-tests/run-suite.sh" --url http://localhost:$HP/api/v1 --label "$LABEL" --node-pid $YANO_PID "$@"
RC=$?
echo "run-suite exit=$RC"
echo "yano ERROR lines: $(grep -c ' ERROR ' "$RUN/yano.log")"; grep ' ERROR ' "$RUN/yano.log" | cut -c1-220 | sed -E 's/^[0-9-]+ [0-9:,]+ //' | sort | uniq -c | sort -rn | head -8
kill_tracked
# run-suite exits 0 when every asserting case matches compat-tests/KNOWN-FAILS.md.
if [ $RC = 0 ]; then echo "VERDICT: PASS"; else echo "VERDICT: FAIL (run-suite exit $RC)"; fi
exit $RC
