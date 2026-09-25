#!/bin/bash
# test-app-chain-extensions, adapted:
#  - kv-registry moved out of core (Yano X split) -> chain 2 is a second ordered-log chain
#  - isolated ports (A 7171/13441, B 7172/13442, webhook 9199), state triple per chain
source "$(dirname "$0")/appchain-common.sh"
RUN=$SP/runs/appchain-ext; rm -rf "$RUN"; mkdir -p "$RUN"
WH=9199
assert_ports_free $HTTP_A $N2N_A $HTTP_B $N2N_B $WH || exit 2
C1=test-chain; C2=log-chain-2
GENID2=$(printf 'log-chain-2' | shasum -a 256 | cut -d' ' -f1)

# webhook sink
cat > "$RUN/sink.py" <<EOF
import http.server
class H(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get('Content-Length', 0)); b = self.rfile.read(n)
        with open('$RUN/webhook.log', 'ab') as f: f.write(b.replace(b'\n', b' ') + b'\n')
        self.send_response(200); self.end_headers()
    def log_message(self, *a): pass
http.server.HTTPServer(('127.0.0.1', $WH), H).serve_forever()
EOF
python3 "$RUN/sink.py" & track_pid $! webhook-sink

chain_args() { # <idx> <chain-id> <genesis-id> <seed> <peer-port>
  local p="yano.app-chain.chains[$1]"
  echo "-D$p.chain-id=$2 -D$p.signing-key=$4 -D$p.members=$PUB_A,$PUB_B -D$p.peers=localhost:$5
    -D$p.sequencer.proposer=$PUB_A -D$p.threshold=2 -D$p.block.interval-ms=1000 -D$p.l1.stability-depth=5
    -D$p.state-machine=ordered-log -D$p.state.commitment-profile=mpf-blake2b256-v1
    -D$p.state.format-fingerprint=$FPRINT -D$p.state.genesis-id=$3"
}
start_node_a "$RUN" $(chain_args 0 $C1 $GENID $SEED_A $N2N_B) $(chain_args 1 $C2 $GENID2 $SEED_A $N2N_B) \
  "-Dyano.app-chain.chains[0].anchor.enabled=true" "-Dyano.app-chain.chains[0].anchor.signing-key=$SEED_ANCHOR" \
  "-Dyano.app-chain.chains[0].anchor.every-blocks=2" "-Dyano.app-chain.chains[0].webhooks=http://localhost:$WH/hook"
wait_ready $HTTP_A 60 || { tail -30 "$RUN/node-a.log"; kill_tracked; echo "VERDICT: FAIL (node A not ready)"; exit 1; }
sleep 3
start_node_b "$RUN" $(chain_args 0 $C1 $GENID $SEED_B $N2N_A) $(chain_args 1 $C2 $GENID2 $SEED_B $N2N_A)
wait_ready $HTTP_B 60 || { tail -30 "$RUN/node-b.log"; kill_tracked; echo "VERDICT: FAIL (node B not ready)"; exit 1; }
wait_l1_sync_b; sleep 5
ANCHOR_ADDR=$(grep -oE 'anchor wallet address: (addr[a-z0-9_]+)' "$RUN/node-a.log" | head -1 | sed 's/.*: //')
echo "fund $ANCHOR_ADDR: $(fund $A $ANCHOR_ADDR 100 | cut -c1-120)"

echo "== multi-chain"
echo "A chains: $(curl -s $A/app-chain/chains | jq -c '[.. | objects | .chainId? // empty]')"
N=0; for u in $A $B; do curl -s $u/app-chain/chains | jq -e '[.. | objects | .chainId? // empty] | unique | length==2' >/dev/null || N=1; done
check "2 chains on A and B" $N
CODE=$(curl -s -o /dev/null -w '%{http_code}' $A/app-chain/tip); echo "chain-less /tip -> $CODE"
check "chain-less path rejected as ambiguous (400)" $([ "$CODE" = 400 ]; echo $?)
M1=$(post $A/app-chain/chains/$C1/messages '{"topic":"orders","body":"ext msg 1"}'); ID1=$(echo "$M1" | jq -r .messageId); echo "submit c1: $M1"
M2=$(post $A/app-chain/chains/$C2/messages '{"topic":"logs","body":"chain two msg"}'); ID2=$(echo "$M2" | jq -r .messageId); echo "submit c2: $M2"
sleep 8
check "c1 msg readable on B" $(curl -s $B/app-chain/chains/$C1/messages/$ID1 | jq -e '.messageId? // .id? // .position? // empty' >/dev/null; echo $?)
P2=$(curl -s $B/app-chain/chains/$C2/messages/$ID2/proof); echo "c2 proof on B: $(echo "$P2" | jq -c '{blockHeight,messagesRoot}' 2>/dev/null)"
check "c2 proof on B" $(echo "$P2" | jq -e '.blockHeight>=1' >/dev/null; echo $?)
for c in $C1 $C2; do
  TA=$(curl -s $A/app-chain/chains/$c/tip); TB=$(curl -s $B/app-chain/chains/$c/tip)
  check "$c tips+stateRoot identical ($(echo $TA | jq -c '{height}'))" $(jq -en --argjson a "$TA" --argjson b "$TB" '$a.height>=1 and $a.height==$b.height and $a.stateRoot==$b.stateRoot' >/dev/null; echo $?)
done

echo "== query surface (B)"
check "by-topic/orders" $(curl -s $B/app-chain/chains/$C1/messages/by-topic/orders | jq -e '[.. | objects | select(.topic?=="orders")] | length>=1' >/dev/null; echo $?)
echo "  message: $(curl -s $B/app-chain/chains/$C1/messages/$ID1 | jq -c . | cut -c1-200)"
check "blocks?limit=5" $(curl -s "$B/app-chain/chains/$C1/blocks?limit=5" | jq -e '[.. | objects | select(.height?)] | length>=1' >/dev/null; echo $?)

echo "== SSE"
( curl -s -N --max-time 10 "$B/app-chain/chains/$C1/stream?fromHeight=1" > "$RUN/sse.txt" ) &
sleep 2; MLIVE=$(post $A/app-chain/chains/$C1/messages '{"topic":"orders","body":"live sse msg"}' | jq -r .messageId); sleep 9
echo "  sse events: $(grep -c '^event: *app-message' "$RUN/sse.txt"), heartbeats: $(grep -c '^event: *heartbeat' "$RUN/sse.txt")"
check "SSE replay event (msg1)" $(grep -q "$ID1" "$RUN/sse.txt"; echo $?)
check "SSE live event" $(grep -q "$MLIVE" "$RUN/sse.txt"; echo $?)

echo "== webhook"
echo "  webhook deliveries: $(wc -l < "$RUN/webhook.log" 2>/dev/null)"
HEIGHTS=$(jq -r '[.. | objects | .height? // empty] | .[0]' "$RUN/webhook.log" 2>/dev/null | tr '\n' ' '); echo "  heights: $HEIGHTS"
check "webhook heights ascending, non-empty" $(python3 -c "
import sys; h=[int(x) for x in '''$HEIGHTS'''.split() if x!='null']
sys.exit(0 if h and h==sorted(h) else 1)"; echo $?)
check "webhook contains submitted message" $(grep -q "$ID1\|ext msg 1\|$(printf 'ext msg 1' | xxd -p)" "$RUN/webhook.log"; echo $?)
echo "  A sinks: $(curl -s $A/app-chain/chains/$C1/status | jq -c '.sinks' | cut -c1-200)"

echo "== admin"
echo "  pause: $(post $A/app-chain/chains/$C1/admin/pause '{}')"
R=$(curl -s -w ' HTTP%{http_code}' -X POST $A/app-chain/chains/$C1/messages -H 'Content-Type: application/json' -d '{"topic":"orders","body":"while paused"}'); echo "  submit while paused: $R"
check "submit rejected while paused" $(echo "$R" | grep -qiE 'HTTP5[0-9][0-9]|paused'; echo $?)
echo "  resume: $(post $A/app-chain/chains/$C1/admin/resume '{}')"
R=$(curl -s -w ' HTTP%{http_code}' -X POST $A/app-chain/chains/$C1/messages -H 'Content-Type: application/json' -d '{"topic":"orders","body":"after resume"}'); echo "  submit after resume: $R"
check "submit accepted after resume" $(echo "$R" | grep -q 'HTTP202'; echo $?)
FA=$(post $A/app-chain/chains/$C1/admin/force-anchor '{}'); echo "  force-anchor: $FA"
check "force-anchor responds" $(echo "$FA" | jq -e 'has("anchorTriggered")' >/dev/null; echo $?)

echo "== rotation (static admin add/remove on both nodes)"
for u in $A $B; do add_member $u/app-chain/chains/$C1/admin/members/add $PUB_C >/dev/null; done
MA=$(curl -s -H "X-API-Key: $APIKEY" $A/app-chain/chains/$C1/admin/members); MB=$(curl -s -H "X-API-Key: $APIKEY" $B/app-chain/chains/$C1/admin/members); echo "  A: $(echo $MA | jq -c '{n:(.members|length),threshold}') B: $(echo $MB | jq -c '{n:(.members|length),threshold}')"
check "3 members threshold 2 on both" $(jq -en --argjson a "$MA" --argjson b "$MB" '($a.members|length)==3 and ($b.members|length)==3 and $a.threshold==2 and $b.threshold==2' >/dev/null; echo $?)
MR=$(post $A/app-chain/chains/$C1/messages '{"topic":"orders","body":"after rotation"}' | jq -r .messageId); sleep 6
check "message finalizes with 3 members" $(curl -s $B/app-chain/chains/$C1/messages/$MR/proof | jq -e '.blockHeight>=1' >/dev/null; echo $?)
for u in $A $B; do add_member $u/app-chain/chains/$C1/admin/members/remove $PUB_C >/dev/null; done
check "members back to 2" $(curl -s -H "X-API-Key: $APIKEY" $A/app-chain/chains/$C1/admin/members | jq -e '(.members|length)==2' >/dev/null; echo $?)

echo "== anchor + evidence (up to 120s)"
for i in $(seq 1 24); do grep -q 'Anchor CONFIRMED on L1' "$RUN/node-a.log" && break; submit $A/app-chain/chains/$C1/messages orders "filler $i" >/dev/null; sleep 5; done
S=$(curl -s $A/app-chain/chains/$C1/status); echo "  anchor: $(echo "$S" | jq -c '.anchor | {anchoredCount,lastAnchorTx}')"
check "anchor confirmed" $(echo "$S" | jq -e '.anchor.anchoredCount>=1' >/dev/null; echo $?)
EV=$(curl -s $B/app-chain/chains/$C1/evidence/$ID1); echo "  evidence: $(echo "$EV" | jq -c '{threshold, members:(.members|length), blocksCbor:(.blocksCbor|length), anchor:(.anchor|tostring|.[0:80])}' 2>/dev/null || echo "$EV" | cut -c1-200)"
check "evidence bundle (threshold 2, blocksCbor, members)" $(echo "$EV" | jq -e '.threshold==2 and (.blocksCbor|length)>0 and (.members|length)>=2' >/dev/null; echo $?)

echo "== snapshot"
SN=$(post $A/app-chain/chains/$C1/snapshot "$(jq -nc --arg p "$RUN/snap" '{path:$p}')"); echo "  $SN" | cut -c1-200
check "snapshot height + dir non-empty" $(echo "$SN" | jq -e '.height>=1' >/dev/null && [ -n "$(ls -A "$RUN/snap" 2>/dev/null)" ]; echo $?)

echo "== metrics"
MET=$(curl -s http://localhost:$HTTP_A/q/metrics | grep '^yano_appchain_tip_height'); echo "$MET" | sed 's/^/  /'
check "metrics for both chains" $(echo "$MET" | grep -q "chain=\"$C1\"" && echo "$MET" | grep -q "chain=\"$C2\""; echo $?)

echo "== invariants"
for c in $C1 $C2; do
  TA=$(curl -s $A/app-chain/chains/$c/tip); TB=$(curl -s $B/app-chain/chains/$c/tip)
  check "final $c identical ($(echo $TA | jq -c '{height}'))" $(jq -en --argjson a "$TA" --argjson b "$TB" '$a.height==$b.height and $a.stateRoot==$b.stateRoot' >/dev/null; echo $?)
done
check "block1 certSignatures>=2" $(curl -s $A/app-chain/chains/$C1/blocks/1 | jq -e '.certSignatures>=2' >/dev/null; echo $?)
l1_lockstep; check "L1 lock-step + advancing" $?
error_scan "$RUN"
kill_tracked
[ ${#FAILS[@]} -eq 0 ] && echo "VERDICT: PASS" || echo "VERDICT: FAIL (${FAILS[*]})"
