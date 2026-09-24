#!/bin/bash
# test-devnet-epoch-crossing / test-native-devnet-epoch-crossing, on isolated ports.
MODE=${1:-jvm}
source "$(dirname "$0")/common.sh"
RUN=$SP/runs/epoch-crossing-$MODE
rm -rf "$RUN"; mkdir -p "$RUN"
LOGF=$RUN/yano.log
assert_ports_free $HTTP_A $N2N_A || exit 2

jq '.epochLength = 50' "$REPO/app/config/network/devnet/shelley-genesis.json" > "$RUN/shelley-genesis.json"
start_yano "$MODE" "$RUN" $HTTP_A $N2N_A "$LOGF" -Dquarkus.profile=devnet \
  -Dyano.genesis.shelley-genesis-file=$RUN/shelley-genesis.json
T0=$(date +%s)
wait_ready $HTTP_A 30 || READY=fail
for i in $(seq 1 60); do
  last_slot=$(grep -oE 'Block #[0-9]+ produced: slot=[0-9]+' "$LOGF" | tail -1 | sed -E 's/.*slot=([0-9]+).*/\1/')
  errs=$(grep -c 'Effective protocol parameters are unavailable\|Error producing block' "$LOGF")
  [ -n "$last_slot" ] && [ "$last_slot" -ge 110 ] && break
  sleep 1
done
echo "elapsed=$(( $(date +%s) - T0 ))s last_slot=${last_slot:-?} errs=$errs"
echo "genesis: $(grep -m1 -oE '(Genesis|genesis) block[^,]*slot=?[ ]?[0-9]+' "$LOGF" | head -1)"
echo "first blocks:"; grep -oE 'Block #[0-9]+ produced: slot=[0-9]+' "$LOGF" | head -3
echo "last block: $(grep 'Block #' "$LOGF" | tail -1 | cut -c1-200)"
echo "epoch transitions: $(grep -c 'Epoch transition detected' "$LOGF")"
grep -oE 'Epoch transition detected[^)]*' "$LOGF" | head -3
echo "tip: $(yano_tip $HTTP_A)"
NATIVE_ERRS=$(grep -cE 'NoClassDefFoundError|ClassNotFoundException|UnsupportedFeatureError|MissingReflectionRegistrationError' "$LOGF")
echo "native-init errors: $NATIVE_ERRS"
echo "ERROR lines: $(grep -c ' ERROR ' "$LOGF")"; grep ' ERROR ' "$LOGF" | cut -c1-220 | sort | uniq -c | sort -rn | head -8
echo "listening:"; lsof -nP -a -p $YANO_PID -iTCP -sTCP:LISTEN | awk 'NR>1{print $9}'
kill_tracked
FAIL=()
[ "${READY:-ok}" = ok ] || FAIL+=(not-ready)
[ "${last_slot:-0}" -ge 100 ] || FAIL+=("last slot ${last_slot:-none} < 100")
[ "${errs:-0}" -eq 0 ] || FAIL+=("$errs block-production errors")
[ "$NATIVE_ERRS" -eq 0 ] || FAIL+=("$NATIVE_ERRS native-init errors")
if [ ${#FAIL[@]} -eq 0 ]; then echo "VERDICT: PASS"; else echo "VERDICT: FAIL (${FAIL[*]})"; fi
