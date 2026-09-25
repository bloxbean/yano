#!/bin/bash
# test-past-time-travel / test-native-past-time-travel on isolated ports.
# pv10: epochLength=1200 x 0.2s -> shift 4 epochs = 960000 ms, catch-up ~4800 blocks.
MODE=${1:-jvm}
source "$(dirname "$0")/common.sh"
RUN=$SP/runs/ptt-$MODE
rm -rf "$RUN"; mkdir -p "$RUN"
G=$RUN/genesis; HS=$RUN/haskell; LOGF=$RUN/yano.log
cp -R "$REPO/app/config/network/devnet/pv10" "$G"
assert_ports_free $HTTP_A $N2N_A $HS_PORT $HS_EKG $HS_PROM || exit 2
FAIL=()

start_yano "$MODE" "$RUN" $HTTP_A $N2N_A "$LOGF" -Dquarkus.profile=devnet \
  -Dyano.block-producer.past-time-travel-mode=true \
  -Dyano.genesis.shelley-genesis-file=$G/shelley-genesis.json \
  -Dyano.genesis.byron-genesis-file=$G/byron-genesis.json \
  -Dyano.genesis.alonzo-genesis-file=$G/alonzo-genesis.json \
  -Dyano.genesis.conway-genesis-file=$G/conway-genesis.json \
  -Dyano.genesis.protocol-parameters-file=$G/protocol-param.json
wait_ready $HTTP_A 60 || { kill_tracked; echo "VERDICT: FAIL (not ready)"; exit 1; }
sleep 3
grep -q 'Past time travel mode: block production deferred' "$LOGF" && echo "deferred-mode log: yes" || { echo "deferred-mode log: NO"; FAIL+=(deferred-log); }

SHIFT=$(curl -s -X POST http://localhost:$HTTP_A/api/v1/devnet/epochs/shift -H 'Content-Type: application/json' -d '{"epochs": 4}')
echo "shift: $SHIFT"
[ "$(echo "$SHIFT" | jq -r .genesis_slot)" = 0 ] || FAIL+=(genesis_slot)
[ "$(echo "$SHIFT" | jq -r .shift_millis)" = 960000 ] || FAIL+=(shift_millis)
sleep 5
echo "sequential prefix:"; grep -oE '(Genesis block|Block #[0-9]+) produced: slot=[0-9]+' "$LOGF" | head -4
NONSEQ=$(grep -oE 'Block #[0-9]+ produced: slot=[0-9]+' "$LOGF" | head -20 | awk -F'[# :=]+' '{ if ($2 != $5) print }' | wc -l | tr -d ' ')
echo "non-sequential in first 20: $NONSEQ"; [ "$NONSEQ" = 0 ] || FAIL+=(sequential)
echo "pre-catch-up tip: $(yano_tip $HTTP_A)"

T0=$(date +%s)
CU=$(curl -s -X POST http://localhost:$HTTP_A/api/v1/devnet/epochs/catch-up)
echo "catch-up (${SECONDS}s total, took $(( $(date +%s) - T0 ))s): $CU"
BP=$(echo "$CU" | jq -r '.blocks_produced // empty')
[ -n "$BP" ] && [ "$BP" -ge 4500 ] && [ "$BP" -le 5100 ] || FAIL+=(catchup-count)
sleep 4
T1=$(yano_tip $HTTP_A); echo "post-catch-up tip: $T1"
[ "$(echo "$T1" | jq '.slot > .blockNumber')" = true ] || FAIL+=(wallclock-mode)
echo "epoch transitions: $(grep -c 'Epoch transition detected' "$LOGF")"

prepare_haskell "$HS" $N2N_A "$G"
SS=$(jq -r .systemStart $HS/files/shelley-genesis.json)
AGO=$(python3 -c "import datetime,sys;print(int(datetime.datetime.now(datetime.timezone.utc).timestamp()-datetime.datetime.fromisoformat(sys.argv[1].replace('Z','+00:00')).timestamp()))" "$SS")
echo "haskell systemStart=$SS (${AGO}s ago)"
[ "$AGO" -ge 900 ] && [ "$AGO" -le 1200 ] || FAIL+=(systemStart)
start_haskell "$HS" "$RUN/haskell.log"

HS_T0=$(date +%s); CAUGHT=""
for i in $(seq 1 30); do
  sleep 2
  ht=$(hs_tip "$HS"); hb=$(echo "$ht" | jq -r '.block // 0' 2>/dev/null)
  yb=$(yano_tip $HTTP_A | jq -r .blockNumber)
  if [ -n "$hb" ] && [ "$hb" != null ] && [ $(( yb - hb )) -le 2 ] && [ "$hb" -gt 100 ]; then CAUGHT=$(( $(date +%s) - HS_T0 )); break; fi
done
echo "haskell caught up to yano tip in: ${CAUGHT:-NOT within 60}s"
[ -n "$CAUGHT" ] || FAIL+=(haskell-catchup)
echo "haskell first chain extended: $(grep -m1 -oE 'Chain extended, new tip: [0-9a-f]+ at slot [0-9]+' "$RUN/haskell.log")"
sleep 10
ht=$(hs_tip "$HS"); hblk=$(echo "$ht" | jq -r .block); hhash=$(echo "$ht" | jq -r .hash)
yhash=$(curl -s "http://localhost:$HTTP_A/api/v1/blocks/$hblk" | jq -r '.hash // empty')
echo "final haskell tip: $(echo "$ht" | jq -c .) yano tip: $(yano_tip $HTTP_A)"
echo "hash match at block $hblk: $([ "$hhash" = "$yhash" ] && echo yes || echo NO)"
[ "$hhash" = "$yhash" ] || FAIL+=(hash)
HERR=$(grep -iE 'error|invalid|reject' "$RUN/haskell.log" | grep -vE 'Node configuration|EKGView|TraceNoLedgerView')
echo "haskell error-like lines: $(printf '%s' "$HERR" | grep -c .)"; printf '%s\n' "$HERR" | cut -c1-260 | head -5
[ -z "$HERR" ] || FAIL+=(haskell-errors)
echo "yano ERROR lines: $(grep -c ' ERROR ' "$LOGF")"; grep ' ERROR ' "$LOGF" | cut -c1-220 | head -5
NATIVE_ERRS=$(grep -cE 'NoClassDefFoundError|ClassNotFoundException|UnsupportedFeatureError|MissingReflectionRegistrationError' "$LOGF")
echo "native-init errors: $NATIVE_ERRS"; [ "$NATIVE_ERRS" -eq 0 ] || FAIL+=(native-init)
kill_tracked
if [ ${#FAIL[@]} -eq 0 ]; then echo "VERDICT: PASS"; else echo "VERDICT: FAIL (${FAIL[*]})"; fi
