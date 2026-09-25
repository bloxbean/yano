#!/bin/bash
# test-app-chain-cluster, adapted: isolated ports, state triple, per-node app storage.
source "$(dirname "$0")/appchain-common.sh"
RUN=$SP/runs/appchain-cluster; rm -rf "$RUN"; mkdir -p "$RUN"
assert_ports_free $HTTP_A $N2N_A $HTTP_B $N2N_B || exit 2
COMMON=(-Dyano.app-chain.enabled=true -Dyano.app-chain.chain-id=test-chain
  -Dyano.app-chain.members=$PUB_A,$PUB_B -Dyano.app-chain.sequencer.proposer=$PUB_A
  -Dyano.app-chain.threshold=2 -Dyano.app-chain.block.interval-ms=1000
  -Dyano.app-chain.l1.stability-depth=5 $(state_triple yano.app-chain))

start_node_a "$RUN" "${COMMON[@]}" -Dyano.app-chain.signing-key=$SEED_A \
  -Dyano.app-chain.peers=localhost:$N2N_B -Dyano.app-chain.anchor.enabled=true \
  -Dyano.app-chain.anchor.signing-key=$SEED_ANCHOR -Dyano.app-chain.anchor.every-blocks=2
wait_ready $HTTP_A 60 || { tail -30 "$RUN/node-a.log"; kill_tracked; echo "VERDICT: FAIL (node A not ready)"; exit 1; }
sleep 3
start_node_b "$RUN" "${COMMON[@]}" -Dyano.app-chain.signing-key=$SEED_B -Dyano.app-chain.peers=localhost:$N2N_A
wait_ready $HTTP_B 60 || { tail -30 "$RUN/node-b.log"; kill_tracked; echo "VERDICT: FAIL (node B not ready)"; exit 1; }
wait_l1_sync_b; sleep 5

ANCHOR_ADDR=$(grep -oE 'anchor wallet address: (addr[a-z0-9_]+)' "$RUN/node-a.log" | head -1 | sed 's/.*: //')
echo "anchor wallet: $ANCHOR_ADDR"
echo "fund: $(fund $A $ANCHOR_ADDR 100 | cut -c1-200)"

echo "== status"
SA=$(curl -s $A/app-chain/status); SB=$(curl -s $B/app-chain/status)
echo "A: $(echo "$SA" | jq -c '{role,sequencing,peers}')"; echo "B: $(echo "$SB" | jq -c '{role,sequencing,peers}')"
check "A role=proposer" $(echo "$SA" | jq -e '.role=="proposer"' >/dev/null; echo $?)
check "B role=member" $(echo "$SB" | jq -e '.role=="member"' >/dev/null; echo $?)
check "peers connected both ways" $(echo "$SA $SB" | jq -se 'all(.[]; (.peers|to_entries|length)>0 and (.peers|to_entries|all(.value==true)))' >/dev/null; echo $?)
check "sequencing true" $(echo "$SA" | jq -e '.sequencing==true' >/dev/null; echo $?)

echo "== submit"
MA=$(post $A/app-chain/messages '{"topic":"orders","body":"hello from node A"}'); echo "A submit: $MA"
MB=$(post $B/app-chain/messages '{"topic":"orders","body":"hello from node B"}'); echo "B submit: $MB"
IDA=$(echo "$MA" | jq -r '.messageId // .id // empty'); IDB=$(echo "$MB" | jq -r '.messageId // .id // empty')
sleep 8

echo "== ledger"
TA=$(curl -s $A/app-chain/tip); TB=$(curl -s $B/app-chain/tip); echo "tipA=$(echo $TA | jq -c .)"; echo "tipB=$(echo $TB | jq -c .)"
check "tips equal height>=1 + identical stateRoot" $(jq -en --argjson a "$TA" --argjson b "$TB" '$a.height>=1 and $a.height==$b.height and $a.stateRoot==$b.stateRoot' >/dev/null; echo $?)
BA=$(curl -s $A/app-chain/blocks/1); BB=$(curl -s $B/app-chain/blocks/1)
echo "block1 A: $(echo "$BA" | jq -c '{height,proposer,certSignatures,stateRoot,messagesRoot}')"
check "block1 identical roots, certSignatures>=2, proposer=A" $(jq -en --argjson a "$BA" --argjson b "$BB" --arg p $PUB_A '$a.stateRoot==$b.stateRoot and $a.messagesRoot==$b.messagesRoot and $a.certSignatures>=2 and $a.proposer==$p' >/dev/null; echo $?)
MSGS_B=$(curl -s $B/app-chain/messages); MSGS_A=$(curl -s $A/app-chain/messages)
check "A's message visible on B (source PEER)" $(echo "$MSGS_B" | jq -e --arg id "$IDA" '[.. | objects | select((.messageId? // .id?)==$id)] | any(.source=="PEER")' >/dev/null; echo $?)
check "B's message visible on A (source PEER)" $(echo "$MSGS_A" | jq -e --arg id "$IDB" '[.. | objects | select((.messageId? // .id?)==$id)] | any(.source=="PEER")' >/dev/null; echo $?)
PR=$(curl -s $B/app-chain/messages/$IDA/proof); echo "proof(B,msgA): $(echo "$PR" | jq -c '{blockHeight,messagesRoot,siblings:(.siblings|length)}' 2>/dev/null || echo "$PR" | cut -c1-200)"
check "MPF proof on B for A's message" $(echo "$PR" | jq -e '.blockHeight>=1 and .messagesRoot!=null' >/dev/null; echo $?)

echo "== anchor (up to 120s)"
for i in $(seq 1 24); do grep -q 'Anchor CONFIRMED on L1' "$RUN/node-a.log" && break; submit $A/app-chain/messages orders "filler $i" >/dev/null; sleep 5; done
grep -m1 'Anchor CONFIRMED on L1' "$RUN/node-a.log" | cut -c1-220
SA=$(curl -s $A/app-chain/status); echo "anchor: $(echo "$SA" | jq -c '.anchor')"
check "anchor confirmed (anchoredCount>=1)" $(echo "$SA" | jq -e '.anchor.anchoredCount>=1 and .anchor.lastAnchorTx!=null' >/dev/null; echo $?)

echo "== final consistency"
sleep 3; TA=$(curl -s $A/app-chain/tip); TB=$(curl -s $B/app-chain/tip)
echo "tipA=$(echo $TA | jq -c '{height,stateRoot}') tipB=$(echo $TB | jq -c '{height,stateRoot}')"
check "final tips identical" $(jq -en --argjson a "$TA" --argjson b "$TB" '$a.height==$b.height and $a.stateRoot==$b.stateRoot' >/dev/null; echo $?)
l1_lockstep; check "L1 lock-step + advancing" $?
error_scan "$RUN"
kill_tracked
[ ${#FAILS[@]} -eq 0 ] && echo "VERDICT: PASS" || echo "VERDICT: FAIL (${FAILS[*]})"
