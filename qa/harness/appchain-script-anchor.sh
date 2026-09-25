#!/bin/bash
# test-app-chain-script-anchor, adapted: isolated ports, state triple, per-node app storage.
# FIXED observation check: count .messages|length (summaries carry no .topic), and make a
# fresh deposit with a REAL L1 tx (the devnet faucet only injects a synthetic UTxO).
source "$(dirname "$0")/appchain-common.sh"
RUN=$SP/runs/appchain-anchor; rm -rf "$RUN"; mkdir -p "$RUN"
assert_ports_free $HTTP_A $N2N_A $HTTP_B $N2N_B || exit 2
WALLET=addr_test1vz9ftj8dtzpsd65gsc94f6cvvhnhm74en9ufe30xegqg0xgkplq9s
COMMON=(-Dyano.app-chain.enabled=true -Dyano.app-chain.chain-id=anchor-chain
  -Dyano.app-chain.members=$PUB_A,$PUB_B -Dyano.app-chain.sequencer.proposer=$PUB_A
  -Dyano.app-chain.threshold=2 -Dyano.app-chain.block.interval-ms=1000 -Dyano.app-chain.l1.stability-depth=8
  -Dyano.app-chain.observation.l1-network-genesis-id=a0f766a5508a14c7574c8ab6c46b95158ea52f45b790b7f621095beb5e69b6a0 -Dyano.app-chain.observers.deposits.type=address-deposit -Dyano.app-chain.observers.deposits.address=$WALLET
  $(state_triple yano.app-chain))
start_node_a "$RUN" "${COMMON[@]}" -Dyano.app-chain.signing-key=$SEED_A -Dyano.app-chain.peers=localhost:$N2N_B \
  -Dyano.app-chain.anchor.enabled=true -Dyano.app-chain.anchor.signing-key=$SEED_ANCHOR \
  -Dyano.app-chain.anchor.mode=script -Dyano.app-chain.anchor.every-blocks=2
wait_ready $HTTP_A 60 || { tail -30 "$RUN/node-a.log"; kill_tracked; echo "VERDICT: FAIL (node A not ready)"; exit 1; }
grep -m1 -oE 'L1 SCRIPT anchoring enabled[^,]*' "$RUN/node-a.log"; grep -m1 -oE 'L1 observers configured: .*' "$RUN/node-a.log" | cut -c1-120
check "script anchoring + observers configured (log)" $(grep -q 'L1 SCRIPT anchoring enabled' "$RUN/node-a.log" && grep -q 'L1 observers configured' "$RUN/node-a.log"; echo $?)
check "anchor wallet address matches fixture" $(grep -q "$WALLET" "$RUN/node-a.log"; echo $?)
sleep 3
start_node_b "$RUN" "${COMMON[@]}" -Dyano.app-chain.signing-key=$SEED_B -Dyano.app-chain.peers=localhost:$N2N_A
wait_ready $HTTP_B 60 || { tail -30 "$RUN/node-b.log"; kill_tracked; echo "VERDICT: FAIL (node B not ready)"; exit 1; }
wait_l1_sync_b || echo "  (B L1 sync lagging — first-boot gap?)"; sleep 5

echo "fund: $(fund $A $WALLET 500 | cut -c1-150)"
sleep 5
echo "== bootstrap"
for i in $(seq 1 10); do
  BS=$(curl -s -w ' HTTP%{http_code}' -H "X-API-Key: $APIKEY" -X POST $A/app-chain/admin/anchor/bootstrap); echo "  $BS" | cut -c1-300
  echo "$BS" | grep -q HTTP202 && break; sleep 5
done
for i in $(seq 1 30); do grep -q 'Script-anchor bootstrap CONFIRMED on L1' "$RUN/node-a.log" && break; sleep 2; done
check "bootstrap CONFIRMED on L1" $(grep -q 'Script-anchor bootstrap CONFIRMED on L1' "$RUN/node-a.log"; echo $?)
echo "  A anchor: $(curl -s $A/app-chain/status | jq -c '.anchor | {bootstrapped,threadPolicyId,anchoredCount}')"

echo "== co-signed advances"
for i in 1 2 3; do submit $A/app-chain/messages t "anchor-msg-$i" >/dev/null; sleep 2; done
for i in $(seq 1 30); do [ "$(curl -s $A/app-chain/status | jq '.anchor.anchoredCount // 0')" -ge 1 ] && break; sleep 5; done
for i in 4 5 6; do submit $A/app-chain/messages t "anchor-msg-$i" >/dev/null; sleep 2; done
for i in $(seq 1 30); do [ "$(curl -s $A/app-chain/status | jq '.anchor.anchoredCount // 0')" -ge 2 ] && break; sleep 5; done
SA=$(curl -s $A/app-chain/status); SB=$(curl -s $B/app-chain/status)
echo "  A anchor: $(echo "$SA" | jq -c '.anchor | {bootstrapped,anchoredCount,lastAnchorTx,threadPolicyId}')"
echo "  B anchor: $(echo "$SB" | jq -c '.anchor | {bootstrapped,threadPolicyId}')"
check "2 member witnesses per advance" $(grep -q 'Script-anchor tx submitted.*2 member witnesses' "$RUN/node-a.log"; echo $?)
check "B verified + sent witness" $(grep -q 'Script-anchor: verified advance body' "$RUN/node-b.log"; echo $?)
check ">=2 advances confirmed (anchoredCount>=2)" $(echo "$SA" | jq -e '.anchor.anchoredCount>=2' >/dev/null; echo $?)
check "B adopted identity (same threadPolicyId, zero anchor config)" $(jq -en --argjson a "$SA" --argjson b "$SB" '$b.anchor.bootstrapped==true and $a.anchor.threadPolicyId==$b.anchor.threadPolicyId' >/dev/null; echo $?)

echo "== L1 observation"
# The bootstrap + anchor txs already pay change to the watched wallet (real L1 txs).
cnt() { curl -s "$1/app-chain/messages/by-topic/~l1%2Fdeposits" | jq '.messages | length'; }
NA0=$(cnt $A); NB0=$(cnt $B); echo "  before fresh deposit: A=$NA0 B=$NB0"
check "anchor-tx change observations finalized (>=1, A==B)" $([ "${NA0:-0}" -ge 1 ] && [ "$NA0" = "$NB0" ]; echo $?)
SEED_DEP=0404040404040404040404040404040404040404040404040404040404040404
DEP_ADDR=$("$JAVA" -cp "$JAR" "$(dirname "$0")/Deposit.java" $SEED_DEP 00 0 1 $WALLET 0 2>&1 >/dev/null | sed -n 's/^from=//p')
FUNDTX=$(fund $A "$DEP_ADDR" 42 | jq -r .tx_hash); echo "  depositor $DEP_ADDR funded (synthetic utxo $FUNDTX#0)"
TXHEX=$("$JAVA" -cp "$JAR" "$(dirname "$0")/Deposit.java" $SEED_DEP "$FUNDTX" 0 42000000 $WALLET 300000 2>/dev/null)
echo "  submit: $(curl -s -X POST "$A/tx/submit" -H 'Content-Type: text/plain' --data "$TXHEX" | cut -c1-160)"
OK=1; for i in $(seq 1 30); do
  NA=$(cnt $A); NB=$(cnt $B)
  [ "${NA:-0}" -gt "${NA0:-0}" ] && [ "$NA" = "$NB" ] && { OK=0; break; }; sleep 3
done
echo "  deposit observations A=$NA B=$NB"
LAST=$(curl -s "$B/app-chain/messages/by-topic/~l1%2Fdeposits" | jq -r '.messages[-1].messageIdHex')
echo "  B last: $(curl -s "$B/app-chain/messages/$LAST" | jq -c '{height,topic,bodyHex}' | cut -c1-400)"
check "fresh real-tx deposit observed + finalized identically on both" $OK
echo "  journals: A=$(curl -s $A/app-chain/status | jq -c '.observers.journal | {entries,cursors}') B=$(curl -s $B/app-chain/status | jq -c '.observers.journal | {entries,cursors}')"
TA=$(curl -s $A/app-chain/tip); TB=$(curl -s $B/app-chain/tip)
check "identical stateRoot ($(echo $TA | jq -c '{height}'))" $(jq -en --argjson a "$TA" --argjson b "$TB" '$a.height==$b.height and $a.stateRoot==$b.stateRoot' >/dev/null; echo $?)
error_scan "$RUN"
[ -n "$KEEP" ] || kill_tracked
[ ${#FAILS[@]} -eq 0 ] && echo "VERDICT: PASS" || echo "VERDICT: FAIL (${FAILS[*]})"
