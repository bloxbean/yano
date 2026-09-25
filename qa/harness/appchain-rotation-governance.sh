#!/bin/bash
# test-app-chain-rotation-governance, adapted: isolated ports, state triple,
# window-slots=50 (devnet slotLength is 0.2s, so 50 slots ~= the skill's intended ~10s window).
source "$(dirname "$0")/appchain-common.sh"
RUN=$SP/runs/appchain-rotgov; rm -rf "$RUN"; mkdir -p "$RUN"
assert_ports_free $HTTP_A $N2N_A $HTTP_B $N2N_B || exit 2
CH=rotgov-chain
COMMON=(-Dyano.app-chain.enabled=true -Dyano.app-chain.chain-id=$CH
  -Dyano.app-chain.members=$PUB_A,$PUB_B -Dyano.app-chain.sequencer.mode=rotating
  -Dyano.app-chain.sequencer.window-slots=${WINDOW:-50} -Dyano.app-chain.membership.mode=governed
  -Dyano.app-chain.threshold=2 -Dyano.app-chain.block.interval-ms=1000 $(state_triple yano.app-chain))
start_node_a "$RUN" "${COMMON[@]}" -Dyano.app-chain.signing-key=$SEED_A -Dyano.app-chain.peers=localhost:$N2N_B
wait_ready $HTTP_A 60 || { tail -30 "$RUN/node-a.log"; kill_tracked; echo "VERDICT: FAIL (node A not ready)"; exit 1; }
sleep 3
start_node_b "$RUN" "${COMMON[@]}" -Dyano.app-chain.signing-key=$SEED_B -Dyano.app-chain.peers=localhost:$N2N_A
wait_ready $HTTP_B 60 || { tail -30 "$RUN/node-b.log"; kill_tracked; echo "VERDICT: FAIL (node B not ready)"; exit 1; }
wait_l1_sync_b; sleep 5

echo "== rotating status"
S=$(curl -s $A/app-chain/status); echo "  A sequencer: $(echo "$S" | jq -c '.sequencer')"
check "mode rotating, window>0, proposer in {A,B}, no split votes" $(echo "$S" | jq -e --arg a $PUB_A --arg b $PUB_B \
  '.sequencer.mode=="rotating" and .sequencer.currentWindow>0 and (.sequencer.currentProposer==$a or .sequencer.currentProposer==$b) and ((.sequencer.splitVotesObserved // 0)==0)' >/dev/null; echo $?)
check "peers connected (A,B)" $(for u in $A $B; do curl -s $u/app-chain/status | jq -e '(.peers|to_entries|length)>0 and (.peers|to_entries|all(.value==true))' >/dev/null || echo x; done | grep -q x; [ $? -ne 0 ]; echo $?)

echo "== rotation across windows"
for i in 1 2 3 4; do submit $A/app-chain/messages t "rot-$i" | jq -c . ; sleep 12; done
TA=$(curl -s $A/app-chain/tip); TB=$(curl -s $B/app-chain/tip); H=$(echo "$TA" | jq .height)
echo "  tips A=$(echo $TA | jq -c '{height}') B=$(echo $TB | jq -c '{height}')"
check "tips identical" $(jq -en --argjson a "$TA" --argjson b "$TB" '$a.height>=3 and $a.height==$b.height and $a.stateRoot==$b.stateRoot' >/dev/null; echo $?)
PROPS=""; CERTOK=0
for h in $(seq 1 "$H"); do
  BL=$(curl -s $A/app-chain/blocks/$h); p=$(echo "$BL" | jq -r .proposer); c=$(echo "$BL" | jq -r .certSignatures)
  echo "  block $h proposer=${p:0:8} certs=$c"; PROPS="$PROPS $p"; [ "$c" -ge 2 ] || CERTOK=1
done
check "all blocks certSignatures>=2" $CERTOK
check ">=2 distinct proposers across blocks" $([ "$(echo $PROPS | tr ' ' '\n' | sort -u | grep -c .)" -ge 2 ]; echo $?)

echo "== governed member-add"
echo "  approval 1 (A): $(add_member $A/app-chain/chains/$CH/admin/members/add $PUB_C | jq -c . | cut -c1-200)"
sleep 10
NA=$(curl -s $A/app-chain/status | jq '.members|length'); NB=$(curl -s $B/app-chain/status | jq '.members|length')
echo "  after 1 approval: A=$NA B=$NB"
check "one approval changes nothing" $([ "$NA" = 2 ] && [ "$NB" = 2 ]; echo $?)
echo "  approval 2 (B): $(add_member $B/app-chain/chains/$CH/admin/members/add $PUB_C | jq -c . | cut -c1-200)"
sleep 15
NA=$(curl -s $A/app-chain/status | jq '.members|length'); NB=$(curl -s $B/app-chain/status | jq '.members|length')
echo "  after 2 approvals: A=$NA B=$NB"
check "member-add activated on both (3 members)" $([ "$NA" = 3 ] && [ "$NB" = 3 ]; echo $?)
GA=$(grep -oE 'Governance ACTIVATED: add member [0-9a-f]{8}.*from height [0-9]+' "$RUN/node-a.log" | head -1)
GB=$(grep -oE 'Governance ACTIVATED: add member [0-9a-f]{8}.*from height [0-9]+' "$RUN/node-b.log" | head -1)
echo "  A: $GA"; echo "  B: $GB"
check "same activation height in both logs" $([ -n "$GA" ] && [ "$(echo $GA | grep -oE '[0-9]+$')" = "$(echo $GB | grep -oE '[0-9]+$')" ]; echo $?)
MID=$(post $A/app-chain/messages '{"topic":"t","body":"after-governance"}' | jq -r .messageId); sleep 25
check "ordinary message finalizes after governance" $(curl -s $B/app-chain/messages/$MID/proof | jq -e '.blockHeight>=1' >/dev/null; echo $?)
TA=$(curl -s $A/app-chain/tip); TB=$(curl -s $B/app-chain/tip)
check "final tips identical" $(jq -en --argjson a "$TA" --argjson b "$TB" '$a.height==$b.height and $a.stateRoot==$b.stateRoot' >/dev/null; echo $?)
echo "  split votes: A=$(curl -s $A/app-chain/status | jq '.sequencer.splitVotesObserved') B=$(curl -s $B/app-chain/status | jq '.sequencer.splitVotesObserved')"
error_scan "$RUN"
kill_tracked
[ ${#FAILS[@]} -eq 0 ] && echo "VERDICT: PASS" || echo "VERDICT: FAIL (${FAILS[*]})"
