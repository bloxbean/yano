#!/bin/bash
# test-haskell-sync / test-native-haskell-sync on isolated ports.
# pv10 epochLength=1200, slotLength=0.2 -> 240 s/epoch; 2-epoch bar = slot >= 2400.
# Optional knobs (defaults keep the standard test):
#   YANO_EXTRA_OPTS   extra -D options for Yano, e.g. "-Dyano.validation.engine=amaru-scalus" (ADR-056 Phase 6b)
#   HS_GENESIS_PATCH  a command run with G=<genesis copy> before either node starts
#   HS_WORKLOAD       a command run in the background once the Haskell node follows, with
#                     YANO_URL=http://localhost:<http>; its output goes to workload.log and a
#                     non-zero exit fails the run
#   HS_MIN_SLOT       the Haskell slot bar (default 2400); HS_TIMEOUT the follow timeout (900 s)
MODE=${1:-jvm}
source "$(dirname "$0")/common.sh"
RUN=$SP/runs/haskell-sync-$MODE
rm -rf "$RUN"; mkdir -p "$RUN"
G=$RUN/genesis; HS=$RUN/haskell
cp -R "$REPO/app/config/network/devnet/pv10" "$G"
MIN_SLOT=${HS_MIN_SLOT:-2400}
if [ -n "${HS_GENESIS_PATCH:-}" ]; then
  G="$G" bash -c "$HS_GENESIS_PATCH" || { echo "VERDICT: FAIL (genesis patch)"; exit 1; }
fi
assert_ports_free $HTTP_A $N2N_A $HS_PORT $HS_EKG $HS_PROM || exit 2

start_yano "$MODE" "$RUN" $HTTP_A $N2N_A "$RUN/yano.log" -Dquarkus.profile=devnet \
  -Dyano.genesis.shelley-genesis-file=$G/shelley-genesis.json \
  -Dyano.genesis.byron-genesis-file=$G/byron-genesis.json \
  -Dyano.genesis.alonzo-genesis-file=$G/alonzo-genesis.json \
  -Dyano.genesis.conway-genesis-file=$G/conway-genesis.json \
  -Dyano.genesis.protocol-parameters-file=$G/protocol-param.json ${YANO_EXTRA_OPTS:-}
wait_ready $HTTP_A 60 || { kill_tracked; echo "VERDICT: FAIL (not ready)"; exit 1; }
sleep 5
echo "genesis: $(grep -m1 'Genesis block produced' "$RUN/yano.log" | grep -oE 'slot=[0-9]+')"
echo "yano tip after start: $(yano_tip $HTTP_A)"
echo "systemStart yano-file: $(jq -r .systemStart $G/shelley-genesis.json)"
prepare_haskell "$HS" $N2N_A "$G"
echo "systemStart haskell : $(jq -r .systemStart $HS/files/shelley-genesis.json)"
start_haskell "$HS" "$RUN/haskell.log"
HS_START=$(date +%s)

check() { # label
  local ht yt hslot hblk hhash yhash
  ht=$(hs_tip "$HS"); yt=$(yano_tip $HTTP_A)
  hslot=$(echo "$ht" | jq -r .slot); hblk=$(echo "$ht" | jq -r .block); hhash=$(echo "$ht" | jq -r .hash)
  yhash=$(curl -s "http://localhost:$HTTP_A/api/v1/blocks/$hblk" | jq -r '.hash // .blockHash // empty')
  echo "[$1] yano=$(echo "$yt" | jq -c '{slot,blockNumber}') haskell={slot:$hslot,block:$hblk,epoch:$(echo "$ht" | jq -r .epoch),sync:$(echo "$ht" | jq -r .syncProgress)} hash@$hblk match=$([ -n "$hhash" ] && [ "$hhash" = "$yhash" ] && echo yes || echo NO)"
  LAST_SLOT=$hslot; LAST_MATCH=$([ -n "$hhash" ] && [ "$hhash" = "$yhash" ] && echo yes || echo no)
  LAST_YSLOT=$(echo "$yt" | jq -r .slot)
}
sleep 20; check t+20s
echo "haskell chain extended lines: $(grep -c 'Chain extended' "$RUN/haskell.log")"
WL_PID=
if [ -n "${HS_WORKLOAD:-}" ]; then
  YANO_URL="http://localhost:$HTTP_A" bash -c "$HS_WORKLOAD" > "$RUN/workload.log" 2>&1 &
  WL_PID=$!; track_pid $WL_PID "workload"
  log "started workload pid=$WL_PID (log: $RUN/workload.log)"
fi
while :; do
  sleep 60; check "t+$(( $(date +%s) - HS_START ))s"
  [ "${LAST_SLOT:-0}" != null ] && [ "${LAST_SLOT:-0}" -ge $(( MIN_SLOT + 50 )) ] && break
  [ $(( $(date +%s) - HS_START )) -gt ${HS_TIMEOUT:-900} ] && { echo "TIMEOUT"; break; }
  kill -0 $YANO_PID 2>/dev/null || { echo "yano died"; break; }
  kill -0 $HS_PID 2>/dev/null || { echo "haskell died"; break; }
done
WL_STATUS=
if [ -n "$WL_PID" ]; then
  for _ in $(seq 1 120); do kill -0 $WL_PID 2>/dev/null || break; sleep 5; done
  if kill -0 $WL_PID 2>/dev/null; then WL_STATUS=running; else wait $WL_PID; WL_STATUS=$?; fi
  echo "workload exit: $WL_STATUS"; grep -E 'WORKLOAD' "$RUN/workload.log" | cut -c1-200 | tail -30
fi
sleep 5; check final
BLOCKS=$(yano_tip $HTTP_A | jq -r .blockNumber)
echo "missed slots (yano): $(( LAST_YSLOT - BLOCKS )) of $LAST_YSLOT"
HERR=$(grep -iE 'error|invalid|reject' "$RUN/haskell.log" | grep -vE 'Node configuration|EKGView|TraceNoLedgerView' )
echo "haskell error-like lines: $(printf '%s' "$HERR" | grep -c .)"; printf '%s\n' "$HERR" | cut -c1-260 | head -5
echo "yano ERROR lines: $(grep -c ' ERROR ' "$RUN/yano.log")"; grep ' ERROR ' "$RUN/yano.log" | cut -c1-220 | head -5
echo "yano epoch transitions: $(grep -c 'Epoch transition detected' "$RUN/yano.log")"
NATIVE_ERRS=$(grep -cE "$NATIVE_IMAGE_ERRORS" "$RUN/yano.log")
echo "native-init errors: $NATIVE_ERRS"
kill_tracked
DELTA=$(( LAST_YSLOT - LAST_SLOT ))
echo "tip slot delta yano-haskell=$DELTA"
FAIL=()
[ "$LAST_MATCH" = yes ] || FAIL+=(hash-mismatch)
[ "${LAST_SLOT:-0}" != null ] && [ "${LAST_SLOT:-0}" -ge $MIN_SLOT ] || FAIL+=("haskell slot ${LAST_SLOT:-none} < $MIN_SLOT")
[ -z "$WL_PID" ] || [ "$WL_STATUS" = 0 ] || FAIL+=("workload $WL_STATUS")
[ -z "$HERR" ] || FAIL+=(haskell-errors)
[ "$NATIVE_ERRS" -eq 0 ] || FAIL+=("$NATIVE_ERRS native-init errors")
if [ ${#FAIL[@]} -eq 0 ]; then echo "VERDICT: PASS"; else echo "VERDICT: FAIL (${FAIL[*]})"; fi
