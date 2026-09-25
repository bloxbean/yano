#!/bin/bash
# test-haskell-sync / test-native-haskell-sync on isolated ports.
# pv10 epochLength=1200, slotLength=0.2 -> 240 s/epoch; 2-epoch bar = slot >= 2400.
MODE=${1:-jvm}
source "$(dirname "$0")/common.sh"
RUN=$SP/runs/haskell-sync-$MODE
rm -rf "$RUN"; mkdir -p "$RUN"
G=$RUN/genesis; HS=$RUN/haskell
cp -R "$REPO/app/config/network/devnet/pv10" "$G"
assert_ports_free $HTTP_A $N2N_A $HS_PORT $HS_EKG $HS_PROM || exit 2

start_yano "$MODE" "$RUN" $HTTP_A $N2N_A "$RUN/yano.log" -Dquarkus.profile=devnet \
  -Dyano.genesis.shelley-genesis-file=$G/shelley-genesis.json \
  -Dyano.genesis.byron-genesis-file=$G/byron-genesis.json \
  -Dyano.genesis.alonzo-genesis-file=$G/alonzo-genesis.json \
  -Dyano.genesis.conway-genesis-file=$G/conway-genesis.json \
  -Dyano.genesis.protocol-parameters-file=$G/protocol-param.json
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
while :; do
  sleep 60; check "t+$(( $(date +%s) - HS_START ))s"
  [ "${LAST_SLOT:-0}" != null ] && [ "${LAST_SLOT:-0}" -ge 2450 ] && break
  [ $(( $(date +%s) - HS_START )) -gt 900 ] && { echo "TIMEOUT"; break; }
  kill -0 $YANO_PID 2>/dev/null || { echo "yano died"; break; }
  kill -0 $HS_PID 2>/dev/null || { echo "haskell died"; break; }
done
sleep 5; check final
BLOCKS=$(yano_tip $HTTP_A | jq -r .blockNumber)
echo "missed slots (yano): $(( LAST_YSLOT - BLOCKS )) of $LAST_YSLOT"
HERR=$(grep -iE 'error|invalid|reject' "$RUN/haskell.log" | grep -vE 'Node configuration|EKGView|TraceNoLedgerView' )
echo "haskell error-like lines: $(printf '%s' "$HERR" | grep -c .)"; printf '%s\n' "$HERR" | cut -c1-260 | head -5
echo "yano ERROR lines: $(grep -c ' ERROR ' "$RUN/yano.log")"; grep ' ERROR ' "$RUN/yano.log" | cut -c1-220 | head -5
echo "yano epoch transitions: $(grep -c 'Epoch transition detected' "$RUN/yano.log")"
NATIVE_ERRS=$(grep -cE 'NoClassDefFoundError|ClassNotFoundException|UnsupportedFeatureError|MissingReflectionRegistrationError' "$RUN/yano.log")
echo "native-init errors: $NATIVE_ERRS"
kill_tracked
DELTA=$(( LAST_YSLOT - LAST_SLOT ))
echo "tip slot delta yano-haskell=$DELTA"
FAIL=()
[ "$LAST_MATCH" = yes ] || FAIL+=(hash-mismatch)
[ "${LAST_SLOT:-0}" != null ] && [ "${LAST_SLOT:-0}" -ge 2400 ] || FAIL+=("haskell slot ${LAST_SLOT:-none} < 2400")
[ -z "$HERR" ] || FAIL+=(haskell-errors)
[ "$NATIVE_ERRS" -eq 0 ] || FAIL+=("$NATIVE_ERRS native-init errors")
if [ ${#FAIL[@]} -eq 0 ]; then echo "VERDICT: PASS"; else echo "VERDICT: FAIL (${FAIL[*]})"; fi
