#!/bin/bash
# Focused standalone acceptance, using release QA's process ownership and HTTP helpers.
set -eo pipefail
REPO=$(cd "$(dirname "$0")/../.." && pwd)
ARTIFACT=${1:?Usage: packaged-cluster.sh /absolute/yano-artifact /new/output/dir [jvm|native]}
SP=${2:?A fresh output directory is required}
MODE=${3:-jvm}
case "$MODE" in
  jvm) JAR=$ARTIFACT; NATIVE=unused-for-jvm ;;
  native)
    [ -x "$ARTIFACT" ] || { echo "Native artifact is not executable: $ARTIFACT" >&2; exit 2; }
    NATIVE=$ARTIFACT; JAR=unused-for-native ;;
  *) echo "Invalid execution mode: $MODE (expected jvm or native)" >&2; exit 2 ;;
esac
[ -f "$ARTIFACT" ] || { echo "Missing packaged artifact: $ARTIFACT" >&2; exit 2; }
case "$ARTIFACT:$SP" in /*:/*) ;; *) echo "Use absolute artifact and output paths" >&2; exit 2;; esac
mkdir "$SP" || exit 2
shasum -a 256 "$ARTIFACT" > "$SP/artifact.sha256"
HTTP_A=${HTTP_A:-17771}
HTTP_B=${HTTP_B:-17772}
HTTP_C=${HTTP_C:-17773}
N2N_A=${N2N_A:-23441}
N2N_B=${N2N_B:-23442}
N2N_C=${N2N_C:-23443}
source "$REPO/qa/harness/appchain-common.sh"
finish() {
  local outcome=$?
  trap - EXIT
  set +e
  kill_tracked
  if [ ! -f "$SP/results.json" ]; then
    printf '{"schemaVersion":1,"mode":"%s","passed":false,"exitCode":%s}\n' "$MODE" "$outcome" > "$SP/results.json"
  fi
  exit "$outcome"
}
trap finish EXIT
assert_ports_free "$HTTP_A" "$HTTP_B" "$HTTP_C" "$N2N_A" "$N2N_B" "$N2N_C"
# The shared helper's PUB_C belongs to another fixture; this C uses the 03 seed.
PUB_C=ed4928c628d1c2c6eae90338905995612959273a5c63f93636c14614ac8737d1
C=http://localhost:$HTTP_C/api/v1
COMMON=(-Dyano.mode=app-ledger -Dyano.auto-sync-start=true -Dyano.server.enabled=true
  -Dyano.block-producer.enabled=false -Dyano.bootstrap.enabled=false -Dyano.dev-mode=false
  -Dyano.app-chain.enabled=true -Dyano.app-chain.transport.mode=dedicated
  -Dyano.app-chain.network-magic=424242 -Dyano.app-chain.chain-id=standalone-qa
  -Dyano.app-chain.members=$PUB_A,$PUB_B,$PUB_C -Dyano.app-chain.sequencer.proposer=$PUB_A
  -Dyano.app-chain.threshold=2 -Dyano.app-chain.block.interval-ms=500
  -Dyano.app-chain.l1.stability-depth=0 -Dyano.app-chain.anchor.enabled=false
  -Dyano.app-chain.api.keys=$APIKEY $(state_triple yano.app-chain))
start_app() {
  local name=$1 http=$2 n2n=$3 seed=$4 peers=$5
  start_yano "$MODE" "$SP/$name" "$http" "$n2n" "$SP/$name.log" "${COMMON[@]}" \
    -Dyano.app-chain.storage.path=$SP/$name/appchain -Dyano.app-chain.signing-key=$seed \
    -Dyano.app-chain.peers=$peers
  wait_ready "$http" 60
}
# A/B never dial C, so C's history must come from its outbound protocol-103 session.
start_app a "$HTTP_A" "$N2N_A" "$SEED_A" localhost:$N2N_B
start_app b "$HTTP_B" "$N2N_B" "$SEED_B" localhost:$N2N_A
message=$(post "$B/app-chain/messages" '{"topic":"orders","body":"standalone follower submission"}')
printf '%s\n' "$message" > "$SP/submission.json"
id=$(echo "$message" | jq -er '.messageId // .id')
await_proof() {
  local endpoint=$1 message_id=$2 target=$3
  for _ in $(seq 1 45); do
    curl -sf "$endpoint/app-chain/messages/$message_id/proof" > "$target" && \
      jq -e '.blockHeight>=1 and .messagesRoot!=null' "$target" >/dev/null && return 0
    sleep 1
  done
  echo "Message did not finalize on $endpoint" >&2
  return 1
}
await_proof "$A" "$id" "$SP/a-proof.json"
await_proof "$B" "$id" "$SP/b-proof.json"
start_app c "$HTTP_C" "$N2N_C" "$SEED_ANCHOR" localhost:$N2N_A,localhost:$N2N_B
C_PID=$YANO_PID
await_proof "$C" "$id" "$SP/c-proof.json"
height=$(jq -r .blockHeight "$SP/a-proof.json")
for entry in "a $A" "b $B" "c $C"; do
  read -r name endpoint <<< "$entry"
  curl -sf "$endpoint/app-chain/blocks/$height" > "$SP/$name-block.json"
  jq -e '.certSignatures>=2' "$SP/$name-block.json" >/dev/null
  jq -S '{height,chainId,prevHash,timestamp,messagesRoot,stateRoot,proposer,messages}' \
    "$SP/$name-block.json" > "$SP/$name-identity.json"
  [ "$(curl -s -o "$SP/$name-l1.json" -w '%{http_code}' "$endpoint/node/tip")" = 503 ]
  jq -e '.error=="cardano_unavailable"' "$SP/$name-l1.json" >/dev/null
  [ ! -e "$SP/$name/chainstate" ]
done
cmp "$SP/a-identity.json" "$SP/b-identity.json"
cmp "$SP/a-identity.json" "$SP/c-identity.json"
# Bounded graceful restart, only of a PID owned by this invocation.
kill -TERM "$C_PID"
for _ in $(seq 1 30); do kill -0 "$C_PID" 2>/dev/null || break; sleep 1; done
if kill -0 "$C_PID" 2>/dev/null; then echo 'C did not stop' >&2; exit 1; fi
start_app c "$HTTP_C" "$N2N_C" "$SEED_ANCHOR" localhost:$N2N_A,localhost:$N2N_B
await_proof "$C" "$id" "$SP/c-restarted-proof.json"
next=$(post "$B/app-chain/messages" '{"topic":"orders","body":"after follower restart"}' | jq -er '.messageId // .id')
await_proof "$C" "$next" "$SP/c-next-proof.json"
if rg -n "$NATIVE_IMAGE_ERRORS| ERROR " "$SP"/*.log; then exit 1; fi
printf '{"schemaVersion":1,"mode":"%s","passed":true,"nodes":3,"lateJoin":true,"restart":true,"cardanoStateCreated":false}\n' "$MODE" > "$SP/results.json"
echo "VERDICT: PASS (packaged standalone cluster, $MODE)"
