#!/usr/bin/env bash
# Unified, copied-distribution-safe facade for the Yano app-chain showcase.
set -euo pipefail

SHOWCASE_HOME="$(cd "$(dirname "$0")" && pwd -P)"
YANO_HOME="$SHOWCASE_HOME/yano"
CLUSTER="$YANO_HOME/appchain-cluster/cluster.sh"
CODEC="$SHOWCASE_HOME/tools/showcase_codec.py"
IDENTITY="$SHOWCASE_HOME/tools/showcase_identity.py"
VERSION="$(tr -d '\r\n' < "$SHOWCASE_HOME/VERSION" 2>/dev/null || printf development)"
API_KEY="${YANO_CLUSTER_API_KEY:-yano-local-cluster-full-key}"
LIGHT_CHAINS=(orders-chain registry-chain approvals-chain balances-chain
  documents-chain workflow-chain roles-chain payments-chain)

die() { printf 'error: %s\n' "$*" >&2; exit 1; }
note() { printf '%s\n' "$*"; }
need() { command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"; }

PROFILE="light"
VARIANT="default"
INSTANCE="default"
NETWORK="devnet"
NODES=3
THRESHOLD=""
HTTP_BASE=7070
SERVER_BASE=13337
ANCHOR=false
ANCHOR_MODE="script"
ANCHOR_KEY_FILE=""
PUBLIC_CONFIRM=""
DATA_ROOT=""
NODE=0
COUNT=""
CONCURRENCY=20
PAYLOAD_SIZE=128
DURATION=60
RATE=25
SAMPLE=5
SPREAD=false
REPORT_DIR=""
FOLLOW=false
YES=false
OUTPUT=""
POSITIONAL=()

parse() {
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --profile) PROFILE="${2:-}"; shift 2;;
      --variant) VARIANT="${2:-}"; shift 2;;
      --instance) INSTANCE="${2:-}"; shift 2;;
      --network) NETWORK="${2:-}"; shift 2;;
      --nodes) NODES="${2:-}"; shift 2;;
      --threshold) THRESHOLD="${2:-}"; shift 2;;
      --http-base) HTTP_BASE="${2:-}"; shift 2;;
      --server-base) SERVER_BASE="${2:-}"; shift 2;;
      --anchor) ANCHOR=true; shift;;
      --anchor-mode) ANCHOR=true; ANCHOR_MODE="${2:-}"; shift 2;;
      --anchor-key-file) ANCHOR=true; ANCHOR_KEY_FILE="${2:-}"; shift 2;;
      --confirm-public-anchor) PUBLIC_CONFIRM="${2:-}"; shift 2;;
      --data-dir) DATA_ROOT="${2:-}"; shift 2;;
      --node) NODE="${2:-}"; shift 2;;
      --count) COUNT="${2:-}"; shift 2;;
      --concurrency) CONCURRENCY="${2:-}"; shift 2;;
      --payload-bytes) PAYLOAD_SIZE="${2:-}"; shift 2;;
      --duration) DURATION="${2:-}"; shift 2;;
      --rate) RATE="${2:-}"; shift 2;;
      --sample) SAMPLE="${2:-}"; shift 2;;
      --spread) SPREAD=true; shift;;
      --report-dir) REPORT_DIR="${2:-}"; shift 2;;
      --follow|-f) FOLLOW=true; shift;;
      --yes) YES=true; shift;;
      --output) OUTPUT="${2:-}"; shift 2;;
      --) shift; while [ "$#" -gt 0 ]; do POSITIONAL+=("$1"); shift; done;;
      *) POSITIONAL+=("$1"); shift;;
    esac
  done
}

validate_common() {
  [[ "$INSTANCE" =~ ^[a-z0-9][a-z0-9-]{0,31}$ ]] || die "invalid instance name"
  case "$PROFILE" in light|evidence|eutxo) ;; *) die "profile must be light, evidence, or eutxo";; esac
  case "$NETWORK" in devnet|preprod) ;; *) die "showcase supports devnet or preprod";; esac
  [[ "$NODES" =~ ^[0-9]+$ ]] && [ "$NODES" -ge 1 ] && [ "$NODES" -le 16 ] \
    || die "--nodes must be between 1 and 16"
  if [ -z "$THRESHOLD" ]; then THRESHOLD=$((NODES / 2 + 1)); fi
  [[ "$THRESHOLD" =~ ^[0-9]+$ ]] && [ "$THRESHOLD" -ge 1 ] && [ "$THRESHOLD" -le "$NODES" ] \
    || die "--threshold must be between 1 and the bootstrap node count"
  if [ "$PROFILE" = evidence ]; then
    [ "$NODES" -eq 3 ] && [ "$THRESHOLD" -eq 2 ] \
      || die "the maintained evidence profile uses exactly 3 nodes with threshold 2"
  fi
  if [ "$PROFILE" = eutxo ] && [ "$NETWORK" != devnet ]; then
    die "the maintained EUTxO showcase variants are devnet-only"
  fi
  if [ "$NETWORK" = preprod ] && [ "$ANCHOR" = true ]; then
    [ "$PUBLIC_CONFIRM" = preprod ] \
      || die "preprod anchoring requires --confirm-public-anchor preprod"
    [ -n "$ANCHOR_KEY_FILE" ] || die "preprod anchoring requires --anchor-key-file"
  fi
  case "$ANCHOR_MODE" in script|metadata) ;; *) die "anchor mode must be script or metadata";; esac
}

instance_root() {
  if [ -n "$DATA_ROOT" ]; then
    case "$DATA_ROOT" in /*) printf '%s/%s' "${DATA_ROOT%/}" "$INSTANCE";;
      *) die "--data-dir must be absolute";;
    esac
  else
    printf '%s/data/showcase/%s' "$SHOWCASE_HOME" "$INSTANCE"
  fi
}
marker() { printf '%s/showcase-identity.json' "$(instance_root)"; }
cluster_dir() { printf '%s/cluster' "$(instance_root)"; }
node_config_dir() { printf '%s/node-config' "$(instance_root)"; }
outbox_dir() { printf '%s/outbox' "$(instance_root)"; }
joined_file() { printf '%s/joined-nodes' "$(instance_root)"; }

plugin_file() {
  local matches=("$YANO_HOME"/plugins/yano-appchain-showcase-*-bundle.jar)
  [ "${#matches[@]}" -eq 1 ] && [ -f "${matches[0]}" ] \
    || die "expected exactly one showcase plugin bundle"
  printf '%s' "${matches[0]}"
}

marker_value() {
  python3 - "$(marker)" "$1" <<'PY'
import json,sys
value=json.load(open(sys.argv[1], encoding="utf-8"))
for segment in sys.argv[2].split('.'):
    value=value[segment]
print("true" if value is True else "false" if value is False else value)
PY
}

adopt_marker() {
  [ -f "$(marker)" ] || return 0
  PROFILE="$(marker_value profile)"
  VARIANT="$(marker_value variant)"
  NETWORK="$(marker_value network)"
  NODES="$(marker_value bootstrapNodeCount)"
  THRESHOLD="$(marker_value bootstrapThreshold)"
  HTTP_BASE="$(marker_value httpBase)"
  SERVER_BASE="$(marker_value serverBase)"
  ANCHOR="$(marker_value anchor.enabled)"
  ANCHOR_MODE="$(marker_value anchor.mode)"
  [ "$ANCHOR_MODE" = none ] && ANCHOR_MODE=script
  local retained_key
  retained_key="$(marker_value anchor.keyReference)"
  if [ "$retained_key" != None ] && [ "$retained_key" != null ] && [ -n "$retained_key" ]; then
    ANCHOR_KEY_FILE="$retained_key"
  fi
}

write_node_configs() {
  local count="$1" directory="$(node_config_dir)" i file
  mkdir -p "$directory" "$(outbox_dir)"
  chmod 700 "$directory" "$(outbox_dir)"
  find "$directory" -maxdepth 1 -type f -name 'node*.properties' -delete
  for ((i=0;i<count;i++)); do
    file="$directory/node$i.properties"
    {
      printf 'config_ordinal=275\n'
      if [ "$i" -eq 0 ]; then
        printf 'yano.app-chain.chains[5].effects.executor.enabled=true\n'
        printf 'yano.app-chain.chains[5].effects.executors.showcase-outbox.enabled=true\n'
        printf 'yano.app-chain.chains[5].effects.executors.showcase-outbox.directory=%s\n' "$(outbox_dir)"
      else
        printf 'yano.app-chain.chains[5].effects.executor.enabled=false\n'
        printf 'yano.app-chain.chains[5].effects.executors.showcase-outbox.enabled=false\n'
      fi
    } > "$file"
    chmod 600 "$file"
  done
}

prepare_light() {
  need python3; need jq; need curl
  [ -x "$CLUSTER" ] || die "packaged cluster launcher is missing"
  local root="$(instance_root)" plugin="$(plugin_file)" args=()
  if [ -e "$root" ] && [ -L "$root" ]; then die "instance root must not be a symlink"; fi
  mkdir -p "$root"; chmod 700 "$root"
  args=(ensure --marker "$(marker)" --version "$VERSION" --profile light
    --variant default --network "$NETWORK" --nodes "$NODES" --threshold "$THRESHOLD"
    --http-base "$HTTP_BASE" --server-base "$SERVER_BASE"
    --config "$YANO_HOME/config/application-appchain.yml" --plugin "$plugin")
  if [ "$ANCHOR" = true ]; then args+=(--anchor --anchor-mode "$ANCHOR_MODE"); fi
  if [ -n "$ANCHOR_KEY_FILE" ]; then args+=(--anchor-key-file "$ANCHOR_KEY_FILE"); fi
  python3 "$IDENTITY" "${args[@]}"
  write_node_configs "$NODES"
  note "Prepared light instance '$INSTANCE'"
  note "  data:    $root"
  note "  config:  $YANO_HOME/config/application-appchain.yml"
  note "  marker:  $(marker)"
  note "  plugin:  $plugin"
}

cluster_env() {
  export YANO_HOME YANO_CLUSTER_DIR="$(cluster_dir)"
  export YANO_CLUSTER_HTTP_BASE="$HTTP_BASE" YANO_CLUSTER_SERVER_BASE="$SERVER_BASE"
  export YANO_CLUSTER_NODE_CONFIG_DIR="$(node_config_dir)"
  export YANO_CLUSTER_PRIVATE_CONFIG_DIR="$(instance_root)/private-config"
  export YANO_CLUSTER_API_KEY="$API_KEY"
  if [ -n "$ANCHOR_KEY_FILE" ]; then export YANO_CLUSTER_ANCHOR_KEY_FILE="$ANCHOR_KEY_FILE"; fi
}

up_light() {
  prepare_light
  cluster_env
  local args=(start "$NODES" --network "$NETWORK" --threshold "$THRESHOLD"
    --http-base "$HTTP_BASE" --server-base "$SERVER_BASE")
  if [ "$ANCHOR" = true ]; then
    args+=(--anchor-mode "$ANCHOR_MODE" --anchor-chain workflow-chain)
  fi
  "$CLUSTER" "${args[@]}"
}

wait_message() {
  local chain="$1" id="$2" deadline=$(( $(date +%s) + 120 )) response
  while :; do
    response="$(curl -fsS --connect-timeout 2 --max-time 5 \
      "http://127.0.0.1:$((HTTP_BASE + NODE))/api/v1/app-chain/chains/$chain/messages/$id" \
      2>/dev/null || true)"
    if [ -n "$response" ]; then
      printf '%s' "$response" | jq -e '.height > 0' >/dev/null 2>&1 && {
        printf '%s\n' "$response"; return 0; }
    fi
    [ "$(date +%s)" -le "$deadline" ] || die "timed out waiting for message $id on $chain"
    sleep 1
  done
}

submit_hex() {
  local chain="$1" topic="$2" body="$3" node="${4:-$NODE}" response id
  response="$(curl -fsS -X POST \
    "http://127.0.0.1:$((HTTP_BASE + node))/api/v1/app-chain/chains/$chain/messages" \
    -H 'Content-Type: application/json' \
    -d "$(jq -nc --arg topic "$topic" --arg body "$body" '{topic:$topic,bodyHex:$body}')")" \
    || die "submission failed on $chain through node $node"
  id="$(printf '%s' "$response" | jq -r '.messageId // empty')"
  [ -n "$id" ] || die "submission returned no message id: $response"
  note "submitted $topic via node $node: $id" >&2
  printf '%s' "$id"
}

submit_text() {
  local chain="$1" topic="$2" body="$3" node="${4:-$NODE}" response id
  response="$(curl -fsS -X POST \
    "http://127.0.0.1:$((HTTP_BASE + node))/api/v1/app-chain/chains/$chain/messages" \
    -H 'Content-Type: application/json' \
    -d "$(jq -nc --arg topic "$topic" --arg body "$body" '{topic:$topic,body:$body}')")" \
    || die "submission failed on $chain through node $node"
  id="$(printf '%s' "$response" | jq -r '.messageId // empty')"
  [ -n "$id" ] || die "submission returned no message id"
  note "submitted $topic via node $node: $id" >&2
  printf '%s' "$id"
}

proof_key() {
  curl -fsS "http://127.0.0.1:$((HTTP_BASE + NODE))/api/v1/app-chain/chains/$1/proof/$2"
}

run_orders() {
  local value="${1:-showcase-order-$(date +%s)}" id finalized
  id="$(submit_text orders-chain orders.command.v1 "$value")"
  finalized="$(wait_message orders-chain "$id")"
  proof_key orders-chain "$id" | jq '{chainId,committedHeight,stateRoot,finalizedAtHeight}'
  note "ORDERED: message finalized at height $(printf '%s' "$finalized" | jq -r .height)"
}

run_registry() {
  local key="${1:-demo-key}" value="${2:-demo-value}" body id
  body="$(python3 "$CODEC" kv put "$key" "$value")"
  id="$(submit_hex registry-chain kv.command.v1 "$body")"; wait_message registry-chain "$id" >/dev/null
  proof_key registry-chain "$(printf '%s' "$key" | od -An -v -tx1 | tr -d ' \n')" \
    | jq '{chainId,committedHeight,stateRoot,valueHex}'
}

approval_propose() {
  local item="$1" payload="$2" required="${3:-2}" body id
  body="$(python3 "$CODEC" approval propose "$item" "$payload" --required "$required")"
  id="$(submit_hex approvals-chain approvals.command.v1 "$body" 0)"
  wait_message approvals-chain "$id" >/dev/null
  note "proposal: $item (requires $required separate approval messages)"
}

approval_approve() {
  local item="$1" member="$2" body id
  body="$(python3 "$CODEC" approval approve "$item")"
  id="$(submit_hex approvals-chain approvals.command.v1 "$body" "$member")"
  wait_message approvals-chain "$id" >/dev/null
  note "approval: $item by member/node $member"
}

run_approvals() {
  local item="${1:-approval-$(date +%s)}" required=2 first=1 member
  if [ "$NODES" -lt 3 ]; then required="$NODES"; first=0; fi
  approval_propose "$item" '{"action":"release-demo"}' "$required"
  for ((member=first;member<first+required;member++)); do
    approval_approve "$item" "$member"
  done
  proof_key approvals-chain "$(python3 "$CODEC" state-key approval "$item")" \
    | jq '{chainId,committedHeight,stateRoot,valueHex}'
}

run_balances() {
  local sender body id
  sender="$(curl -fsS "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/balances-chain/status" \
    | jq -r .memberKey)"
  body="$(python3 "$CODEC" balance mint "$sender" 1000)"
  id="$(submit_hex balances-chain balances.command.v1 "$body" 0)"; wait_message balances-chain "$id" >/dev/null
  body="$(python3 "$CODEC" balance transfer demo-recipient 250)"
  id="$(submit_hex balances-chain balances.command.v1 "$body" 0)"; wait_message balances-chain "$id" >/dev/null
  proof_key balances-chain "$(python3 "$CODEC" state-key balance demo-recipient)" \
    | jq '{chainId,committedHeight,stateRoot,valueHex}'
}

run_documents() {
  local entity="${1:-case-$(date +%s)}" body id
  body="$(python3 "$CODEC" document "$entity" 'demo-document-v1' --reference 'demo://document/1')"
  id="$(submit_hex documents-chain doc-trail.command.v1 "$body")"; wait_message documents-chain "$id" >/dev/null
  proof_key documents-chain "$(python3 "$CODEC" state-key document "$entity")" \
    | jq '{chainId,committedHeight,stateRoot,valueHex}'
}

composite_register() {
  local key="$1" order="$2" canonical body id
  canonical="$(python3 "$CODEC" order "$order")"
  body="$(python3 "$CODEC" kv put "$key" "$canonical")"
  id="$(submit_hex workflow-chain orders.command.v1 "$body" 0)"; wait_message workflow-chain "$id" >/dev/null
  note "registered order '$key': $canonical"
}

composite_propose() {
  local item="$1" order="$2" required="${3:-2}" canonical body id
  canonical="$(python3 "$CODEC" order "$order")"
  body="$(python3 "$CODEC" approval propose "$item" "$canonical" --required "$required")"
  id="$(submit_hex workflow-chain approvals.command.v1 "$body" 0)"; wait_message workflow-chain "$id" >/dev/null
  note "proposed '$item' bound to order hash $(python3 "$CODEC" blake2b "$canonical")"
}

composite_approve() {
  local item="$1" member="$2" body id
  body="$(python3 "$CODEC" approval approve "$item")"
  id="$(submit_hex workflow-chain approvals.command.v1 "$body" "$member")"; wait_message workflow-chain "$id" >/dev/null
  note "application approval '$item' sent by node/member $member"
}

composite_release() {
  local release="$1" key="$2" item="$3" body id
  body="$(python3 "$CODEC" release "$release" "$key" "$item")"
  id="$(submit_hex workflow-chain showcase.release.v1 "$body" 0)"; wait_message workflow-chain "$id" >/dev/null
  note "release command '$release' finalized"
}

composite_verify() {
  local release="$1" deadline=$(( $(date +%s) + 120 )) status outbox
  local effects effect height ordinal effect_id detail proof
  while :; do
    status="$(curl -fsS -X POST \
      "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/workflow-chain/query/components/release/get" \
      -H 'Content-Type: application/json' \
      -d "$(jq -nc --arg p "$(printf '%s' "$release" | od -An -v -tx1 | tr -d ' \n')" '{paramsHex:$p}')" \
      2>/dev/null || true)"
    [ -n "$status" ] && [ "$(printf '%s' "$status" | jq -r '.payloadHex // empty')" != "" ] && break
    [ "$(date +%s)" -le "$deadline" ] || die "release state did not appear"
    sleep 1
  done
  while :; do
    effects="$(curl -fsS --connect-timeout 2 --max-time 5 \
      "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/workflow-chain/effects?fromHeight=0&limit=1000" \
      2>/dev/null || true)"
    effect="$(printf '%s' "$effects" | jq -c --arg scope "showcase/order-release/$release" \
      '.effects[]? | select(.scope == $scope and .type == "showcase.outbox.write")' | tail -1)"
    [ -n "$effect" ] && break
    [ "$(date +%s)" -le "$deadline" ] || die "release effect did not appear"
    sleep 1
  done
  height="$(printf '%s' "$effect" | jq -r .height)"
  ordinal="$(printf '%s' "$effect" | jq -r .ordinal)"
  effect_id="$(printf '%s' "$effect" | jq -r .effectIdHashHex)"
  while :; do
    detail="$(curl -fsS --connect-timeout 2 --max-time 5 \
      "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/workflow-chain/effects/$height/$ordinal" \
      2>/dev/null || true)"
    [ "$(printf '%s' "$detail" | jq -r '.execution.status // empty' 2>/dev/null)" = DONE ] && break
    [ "$(date +%s)" -le "$deadline" ] || die "outbox effect did not reach DONE"
    sleep 1
  done
  outbox="$(outbox_dir)/$effect_id.json"
  [ -f "$outbox" ] || die "executor finished without its deterministic outbox receipt"
  proof="$(curl -fsS --connect-timeout 2 --max-time 5 \
    "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/workflow-chain/effects/$height/$ordinal/proof")"
  printf '%s' "$proof" | jq -e \
    '.effectHashHex and .effectsRootHex and .stateRootHex and .stateProofWireHex' >/dev/null \
    || die "effect commitment proof is incomplete"
  while :; do
    status="$(curl -fsS -X POST \
      "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/workflow-chain/query/components/release/get" \
      -H 'Content-Type: application/json' \
      -d "$(jq -nc --arg p "$(printf '%s' "$release" | od -An -v -tx1 | tr -d ' \n')" '{paramsHex:$p}')" \
      2>/dev/null || true)"
    printf '%s' "$status" | jq -e '.payloadHex | startswith("890101")' >/dev/null 2>&1 && break
    printf '%s' "$status" | jq -e '.payloadHex | startswith("890102")' >/dev/null 2>&1 \
      && die "release effect result was finalized as failed"
    [ "$(date +%s)" -le "$deadline" ] || die "confirmed effect result did not finalize on chain"
    sleep 1
  done
  note "root-attested release query:"
  printf '%s' "$status" | jq '{chainId,stateMachineId,committedHeight,stateRoot,payloadHex}'
  note "effect execution and commitment proof:"
  printf '%s' "$detail" | jq '{record:{height:.record.height,ordinal:.record.ordinal,type:.record.type,scope:.record.scope},execution}'
  printf '%s' "$proof" | jq '{effectHashHex,effectsRootHex,stateRootHex}'
  note "idempotent external receipt: $outbox"
  jq . "$outbox"
}

run_composite() {
  local suffix key item release order required=2 first=1 member
  suffix="${1:-$(date +%s)}"
  key="order-$suffix"
  item="proposal-$suffix"
  release="release-$suffix"
  if [ "$NODES" -lt 3 ]; then required="$NODES"; first=0; fi
  order="{\"id\":\"$key\",\"amount\":42,\"currency\":\"USD\"}"
  composite_register "$key" "$order"
  composite_propose "$item" "$order" "$required"
  for ((member=first;member<first+required;member++)); do
    composite_approve "$item" "$member"
  done
  composite_release "$release" "$key" "$item"
  composite_verify "$release"
}

run_roles() {
  note "roles-chain is running the generic role-approvals-v1 profile."
  curl -fsS "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/roles-chain/status" \
    | jq '{chainId,stateMachine,stateMachineStatus,tipHeight,stateRoot}'
  note "For the complete governed actor/policy/signature lifecycle run:"
  note "  ./showcase.sh run roles --profile evidence --variant role --instance $INSTANCE"
}

accepted_eutxo_receipt() {
  local transaction_id="$1" query payload receipt_status
  query="$(curl -fsS -X POST \
    "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/payments-chain/query/transactions/receipt" \
    -H 'Content-Type: application/json' \
    -d "$(jq -nc --arg p "$(printf '%s' "$transaction_id" | od -An -v -tx1 | tr -d ' \n')" \
      '{paramsHex:$p}')")"
  payload="$(printf '%s' "$query" | jq -r '.payloadHex // empty')"
  [ -n "$payload" ] || die "payments-chain returned no transaction receipt"
  receipt_status="$(eutxo_tool receipt "$payload")"
  [ "$receipt_status" = ACCEPTED ] \
    || die "payments-chain transaction outcome is $receipt_status, expected ACCEPTED"
  printf '%s\n' "$query"
}

run_eutxo() {
  local result id transaction_id query
  result="$(submit_eutxo_payment)"
  id="$(printf '%s' "$result" | jq -r .messageId)"
  transaction_id="$(printf '%s' "$result" | jq -r .transactionId)"
  wait_message payments-chain "$id" >/dev/null
  query="$(accepted_eutxo_receipt "$transaction_id")"
  note "payments-chain accepted a signed, zero-fee virtual-funds transfer:"
  printf '%s' "$query" | jq '{chainId,stateMachineId,committedHeight,stateRoot,payloadHex}'
  note "For a signed payment lifecycle use the dedicated profile:"
  note "  ./showcase.sh run eutxo --profile eutxo --variant ledger --instance $INSTANCE"
}

run_load_test() {
  [ "$PROFILE" = light ] || die "load-test applies to the light profile"
  [ -n "$COUNT" ] && [[ "$COUNT" =~ ^[0-9]+$ ]] && [ "$COUNT" -ge 1 ] \
    || die "load-test requires --count N"
  [[ "$CONCURRENCY" =~ ^[0-9]+$ ]] && [ "$CONCURRENCY" -ge 1 ] \
    || die "--concurrency must be a positive integer"
  [[ "$PAYLOAD_SIZE" =~ ^[0-9]+$ ]] \
    || die "--payload-bytes must be a non-negative integer"
  local scenario="${1:-orders}" chain topic mode="" target=()
  case "$scenario" in
    orders|orders-chain)
      chain=orders-chain; topic=orders.command.v1;;
    registry|registry-chain)
      chain=registry-chain; topic=kv.command.v1; mode=kv;;
    *) die "load-test supports orders or registry";;
  esac
  cluster_env
  if [ "$SPREAD" = true ]; then target=(--spread); else target=(--node "$NODE"); fi
  local args=("$chain" -n "$COUNT" -c "$CONCURRENCY" -s "$PAYLOAD_SIZE" -t "$topic")
  [ "$mode" != kv ] || args+=(--kv)
  args+=("${target[@]}")
  "$YANO_HOME/appchain-cluster/loadtest.sh" "${args[@]}"
}

run_soak_test() {
  [ "$PROFILE" = light ] || die "soak-test applies to the light profile"
  case "${1:-orders}" in orders|orders-chain) ;; \
    *) die "soak-test currently supports the ordered-log orders chain";; esac
  [[ "$DURATION" =~ ^[0-9]+$ ]] && [ "$DURATION" -ge 1 ] \
    || die "--duration must be a positive integer"
  [[ "$RATE" =~ ^[0-9]+$ ]] && [ "$RATE" -ge 1 ] \
    || die "--rate must be a positive integer"
  [[ "$CONCURRENCY" =~ ^[0-9]+$ ]] && [ "$CONCURRENCY" -ge 1 ] \
    || die "--concurrency must be a positive integer"
  [[ "$PAYLOAD_SIZE" =~ ^[0-9]+$ ]] \
    || die "--payload-bytes must be a non-negative integer"
  [[ "$SAMPLE" =~ ^[0-9]+$ ]] && [ "$SAMPLE" -ge 1 ] \
    || die "--sample must be a positive integer"
  local target=() report="${REPORT_DIR:-$(instance_root)/reports/soak-$(date +%Y%m%d-%H%M%S)}"
  cluster_env
  if [ "$SPREAD" = true ]; then target=(--spread); else target=(--node "$NODE"); fi
  "$YANO_HOME/appchain-cluster/soaktest.sh" orders-chain \
    --duration "$DURATION" --rate "$RATE" --conc "$CONCURRENCY" \
    -s "$PAYLOAD_SIZE" --sample "$SAMPLE" --out "$report" "${target[@]}"
}

eutxo_tool() {
  java -cp "$YANO_HOME/yano.jar:$(plugin_file)" \
    com.bloxbean.cardano.yano.appchain.showcase.ShowcaseEutxoTransactions "$@"
}

eutxo_current_outpoint() {
  local identity address status tip params response payload
  identity="$(eutxo_tool identity)"
  address="$(printf '%s' "$identity" | jq -r .address)"
  status="$(curl -fsS \
    "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/payments-chain/status")"
  tip="$(printf '%s' "$status" | jq -r '.tipHeight // 0')"
  if [ "$tip" -eq 0 ]; then
    printf '%s' "$identity" | jq -r .genesisOutpoint
    return
  fi
  params="$(printf '%s' "$address" | od -An -v -tx1 | tr -d ' \n')"
  response="$(curl -fsS -X POST \
    "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/payments-chain/query/utxos/address" \
    -H 'Content-Type: application/json' -d "$(jq -nc --arg p "$params" '{paramsHex:$p}')")"
  payload="$(printf '%s' "$response" | jq -r '.payloadHex // empty')"
  [ -n "$payload" ] || die "payments-chain returned no virtual EUTxO query payload"
  eutxo_tool outpoint "$payload"
}

submit_eutxo_payment() {
  local outpoint transaction id identity
  outpoint="$(eutxo_current_outpoint)"
  transaction="$(eutxo_tool payment "$outpoint")"
  id="$(submit_hex payments-chain eutxo.transactions \
    "$(printf '%s' "$transaction" | jq -r .transactionHex)" 0)"
  identity="$(eutxo_tool identity)"
  jq -nc --arg messageId "$id" \
    --arg transactionId "$(printf '%s' "$transaction" | jq -r .transactionId)" \
    --arg address "$(printf '%s' "$identity" | jq -r .address)" \
    '{messageId:$messageId,transactionId:$transactionId,address:$address}'
}

activation_submit() {
  local cid="$1" suffix="$2" body sender result
  case "$cid" in
    orders-chain)
      submit_text "$cid" showcase.governance-heartbeat.v1 "$suffix" 0;;
    registry-chain)
      body="$(python3 "$CODEC" kv put "$suffix" active)"
      submit_hex "$cid" kv.command.v1 "$body" 0;;
    approvals-chain)
      body="$(python3 "$CODEC" approval propose "$suffix" activation --required 1)"
      submit_hex "$cid" approvals.command.v1 "$body" 0;;
    balances-chain)
      sender="$(curl -fsS \
        "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/$cid/status" | jq -r .memberKey)"
      body="$(python3 "$CODEC" balance mint "$sender" 1)"
      submit_hex "$cid" balances.command.v1 "$body" 0;;
    documents-chain)
      body="$(python3 "$CODEC" document "$suffix" activation \
        --reference showcase://governance-activation)"
      submit_hex "$cid" doc-trail.command.v1 "$body" 0;;
    workflow-chain)
      body="$(python3 "$CODEC" kv put "$suffix" activation)"
      submit_hex "$cid" orders.command.v1 "$body" 0;;
    roles-chain)
      body="$(python3 "$CODEC" role-probe "$suffix")"
      submit_hex "$cid" actors.command.v1 "$body" 0;;
    payments-chain)
      result="$(submit_eutxo_payment)"
      wait_message "$cid" "$(printf '%s' "$result" | jq -r .messageId)" >/dev/null
      accepted_eutxo_receipt "$(printf '%s' "$result" | jq -r .transactionId)" >/dev/null
      printf '%s' "$result" | jq -r .messageId;;
    *) die "unsupported light-profile chain: $cid";;
  esac
}

governance_activate() {
  local deadline=$(( $(date +%s) + 240 )) round=0 pending cid status id index suffix
  local -a submitted_chains=() submitted_ids=()
  while :; do
    pending=0
    submitted_chains=()
    submitted_ids=()
    round=$((round + 1))
    for cid in "${LIGHT_CHAINS[@]}"; do
      status="$(curl -fsS --connect-timeout 2 --max-time 5 \
        "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/$cid/status")" \
        || die "cannot inspect governed epoch on $cid"
      if [ "$(printf '%s' "$status" | jq -r '.membershipEpochActive // false')" != true ]; then
        pending=$((pending + 1))
        suffix="activate-$round-$(date +%s)"
        id="$(activation_submit "$cid" "$suffix")"
        submitted_chains+=("$cid")
        submitted_ids+=("$id")
      fi
    done
    [ "$pending" -gt 0 ] || break
    for ((index=0;index<${#submitted_ids[@]};index++)); do
      wait_message "${submitted_chains[$index]}" "${submitted_ids[$index]}" >/dev/null
    done
    [ "$(date +%s)" -le "$deadline" ] \
      || die "timed out advancing governed membership epochs"
  done

  # Finalize one block under the newly active profile. This makes the latest
  # certificate itself prove the new member set/threshold, rather than merely
  # leaving the next block poised at the activation boundary.
  submitted_ids=()
  for cid in "${LIGHT_CHAINS[@]}"; do
    id="$(activation_submit "$cid" "proof-$round-$(date +%s)")"
    submitted_ids+=("$id")
  done
  for ((index=0;index<${#LIGHT_CHAINS[@]};index++)); do
    wait_message "${LIGHT_CHAINS[$index]}" "${submitted_ids[$index]}" >/dev/null
    status="$(curl -fsS --connect-timeout 2 --max-time 5 \
      "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/${LIGHT_CHAINS[$index]}/status")"
    printf '%s' "$status" | jq -e \
      '.membershipEpochActive == true
       and .membershipActiveMembers == .members
       and .membershipActiveThreshold == .threshold' >/dev/null \
      || die "governed epoch did not activate consistently on ${LIGHT_CHAINS[$index]}"
  done
  note "all light-profile governed epochs are active; proof blocks finalized"
}

verify_light() {
  cluster_env
  "$CLUSTER" status
  local cid identity total="$NODES" joined i deadline converged reference view
  local reference_tip reference_root threshold block cert="" anchor_status
  if [ -f "$(joined_file)" ]; then
    while IFS= read -r joined; do
      [[ "$joined" =~ ^[0-9]+$ ]] || die "retained joined-node index is malformed"
      [ "$((joined + 1))" -le "$total" ] || total=$((joined + 1))
    done < "$(joined_file)"
  fi
  for cid in orders-chain registry-chain approvals-chain balances-chain documents-chain \
      workflow-chain roles-chain payments-chain; do
    deadline=$(( $(date +%s) + 120 ))
    while :; do
      converged=true
      reference="$(curl -fsS --connect-timeout 2 --max-time 5 \
        "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/$cid/status" 2>/dev/null || true)"
      reference_tip="$(printf '%s' "$reference" | jq -r '.tipHeight // -1' 2>/dev/null)"
      reference_root="$(printf '%s' "$reference" | jq -r '.stateRoot // empty' 2>/dev/null)"
      [[ "$reference_tip" =~ ^[0-9]+$ ]] && [ -n "$reference_root" ] || converged=false
      for ((i=0;i<total;i++)); do
        curl -fsS --connect-timeout 2 --max-time 5 \
          "http://127.0.0.1:$((HTTP_BASE + i))/q/health/ready" >/dev/null 2>&1 \
          || { converged=false; break; }
        view="$(curl -fsS --connect-timeout 2 --max-time 5 \
          "http://127.0.0.1:$((HTTP_BASE + i))/api/v1/app-chain/chains/$cid/status" \
          2>/dev/null || true)"
        [ "$(printf '%s' "$view" | jq -r '.tipHeight // -1' 2>/dev/null)" = "$reference_tip" ] \
          && [ "$(printf '%s' "$view" | jq -r '.stateRoot // empty' 2>/dev/null)" = "$reference_root" ] \
          || { converged=false; break; }
      done
      [ "$converged" = true ] && break
      [ "$(date +%s)" -le "$deadline" ] || die "$cid did not converge across $total nodes"
      sleep 1
    done
    threshold="$(printf '%s' "$reference" | jq -r '.membershipActiveThreshold // 0')"
    if [ "$reference_tip" -gt 0 ]; then
      block="$(curl -fsS --connect-timeout 2 --max-time 5 \
        "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/$cid/blocks/$reference_tip")"
      cert="$(printf '%s' "$block" | jq -r '.certSignatures // 0')"
      [ "$cert" -ge "$threshold" ] \
        || die "$cid tip certificate has $cert signatures below active threshold $threshold"
    else
      cert="n/a"
    fi
    identity="$(curl -fsS -H "X-API-Key: $API_KEY" \
      "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/$cid/identity")"
    printf '%-18s nodes=%d tip=%d cert=%s/%s config=%s plugin=%s\n' "$cid" "$total" \
      "$reference_tip" "$cert" "$threshold" \
      "$(printf '%s' "$identity" | jq -r '.resolvedConfigDigest[0:12] // "n/a"')" \
      "$(printf '%s' "$identity" | jq -r '.pluginCatalogFingerprint[0:12] // "n/a"')"
  done
  if [ "$ANCHOR" = true ]; then
    deadline=$(( $(date +%s) + 120 ))
    while :; do
      anchor_status="$(curl -fsS --connect-timeout 2 --max-time 5 \
        "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/workflow-chain/status" \
        2>/dev/null || true)"
      printf '%s' "$anchor_status" | jq -e \
        '.anchor.enabled == true and .anchor.bootstrapped == true
         and (.anchor.lastAnchoredHeight // 0) > 0
         and (.anchor.lagBlocks // 999999) < 2
         and ((.anchor.lastAnchorTx // "") | length > 0)' \
        >/dev/null 2>&1 && break
      [ "$(date +%s)" -le "$deadline" ] || die "workflow-chain has no confirmed L1 anchor"
      sleep 2
    done
    note "workflow-chain L1 anchor confirmed: $(printf '%s' "$anchor_status" | jq -r '.anchor.lastAnchorTx')"
  fi
}

delegate_evidence() {
  local command="$1" demo="$SHOWCASE_HOME/profiles/evidence/demo/demo.sh" machine args=()
  local evidence_id
  [ -x "$demo" ] || die "packaged evidence harness is missing"
  case "$VARIANT" in composite|default) machine=composite;; role) machine=role;;
    *) die "evidence variant must be composite or role";; esac
  if [ "$machine" = role ]; then
    case "$command" in run) command=role-lifecycle;; verify) command=probe;; esac
  fi
  export DEMO_PREBUILT_ARTIFACT_ROOT="$SHOWCASE_HOME/profiles/evidence/artifacts"
  args=("$command" --deployment compose --machine "$machine" --network "$NETWORK"
    --instance "$INSTANCE" --data-dir "$(instance_root)/evidence")
  [ -z "$ANCHOR_KEY_FILE" ] || args+=(--anchor-key-file "$ANCHOR_KEY_FILE")
  [ -z "$PUBLIC_CONFIRM" ] || args+=(--confirm-public-anchor "$PUBLIC_CONFIRM")
  if [ "$command" = verify ]; then
    evidence_id="$(sed -n 's/^DEMO_EVIDENCE_ID=//p' \
      "$SHOWCASE_HOME/profiles/evidence/demo/config/common.env")"
    [ -n "$evidence_id" ] || die "packaged evidence default id is missing"
    args+=(--evidence-id "$evidence_id")
  fi
  [ -z "$COUNT" ] || args+=(--count "$COUNT")
  "$demo" "${args[@]}"
}

delegate_eutxo() {
  local command="$1"
  case "$VARIANT" in ledger|bridge|zk) ;; default) VARIANT=ledger;; *) die "eutxo variant must be ledger, bridge, or zk";; esac
  case "$command" in
    prepare) command=setup;;
    run) command=round-trip;;
    up|status|stop|verify) ;;
    *) die "unsupported EUTxO command: $command";;
  esac
  "$YANO_HOME/yano.sh" appchain eutxo demo "$command" --scenario "$VARIANT" \
    --workspace "$(instance_root)/eutxo-$VARIANT" --members "$NODES" \
    --http-port-base "$HTTP_BASE" --server-port-base "$SERVER_BASE"
}

show_config() {
  local cid status_json members_json
  [ -f "$(marker)" ] || die "instance '$INSTANCE' is not prepared"
  note "Resolved showcase deployment (secrets redacted):"
  python3 "$IDENTITY" show --marker "$(marker)"
  note "Runtime chain identities:"
  if curl -fsS "http://127.0.0.1:$HTTP_BASE/q/health/ready" >/dev/null 2>&1; then
    curl -fsS "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains" \
      | jq -r '.[].chainId' | while IFS= read -r cid; do
        status_json="$(curl -fsS \
          "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/$cid/status")"
        members_json="$(curl -fsS -H "X-API-Key: $API_KEY" \
          "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/$cid/admin/members")"
        jq -nc --argjson s "$status_json" --argjson g "$members_json" \
          '{chainId:$s.chainId,stateMachine:$s.stateMachine,tipHeight:$s.tipHeight,
          stateRoot:$s.stateRoot,activeMembers:$s.membershipActiveMembers,
          activeThreshold:$s.membershipActiveThreshold,scheduledMembers:($g.members|length),
          scheduledThreshold:$g.threshold,activationHeight:$s.membershipEpochFromHeight,
          epochActiveForNextBlock:$s.membershipEpochActive}'
      done | jq -s .
  else
    note "  (instance is stopped)"
  fi
}

config_paths() {
  note "showcase marker : $(marker)"
  note "shared YAML     : $YANO_HOME/config/application-appchain.yml"
  note "node overlays  : $(node_config_dir)/node<N>.properties (secret values are not printed)"
  note "cluster state  : $(cluster_dir)"
  note "outbox receipts: $(outbox_dir)"
  note "plugin bundle  : $(plugin_file)"
  note "logs           : $(cluster_dir)/node<N>/node.log"
}

config_export() {
  local output="${1:-${OUTPUT:-}}"
  [ -n "$output" ] || die "usage: showcase.sh config export <file>"
  python3 "$IDENTITY" export --marker "$(marker)" --output "$output"
  note "wrote redacted deployment snapshot: $output"
}

usage() {
  cat <<'EOF'
Yano unified app-chain showcase

  ./showcase.sh profiles
  ./showcase.sh describe <light|evidence|eutxo>
  ./showcase.sh doctor [--profile light]
  ./showcase.sh prepare|up|quickstart [--profile light] [--nodes 3|5|7]
  ./showcase.sh status|ui|logs|restart|stop|reset [--instance name]
  ./showcase.sh config show|paths|export <file>
  ./showcase.sh run orders|registry|approvals|balances|documents|composite|roles|eutxo|anchor|all
  ./showcase.sh approvals propose <id> <payload> [required]
  ./showcase.sh approvals approve <id> <member-node>
  ./showcase.sh composite register-order <key> '<json>'
  ./showcase.sh composite propose <id> '<json>'
  ./showcase.sh composite approve <id> <member-node>
  ./showcase.sh composite release <release> <key> <proposal>
  ./showcase.sh composite verify <release>
  ./showcase.sh load <orders|registry|approvals|documents|composite> --count N
  ./showcase.sh load-test <orders|registry> --count N [--concurrency N] [--spread]
  ./showcase.sh soak-test orders [--duration SEC] [--rate MSG_PER_SEC] [--spread]
  ./showcase.sh member join <next-index>
  ./showcase.sh threshold set <n>
  ./showcase.sh governance activate
  ./showcase.sh anchor bootstrap [workflow-chain]

Common options: --instance, --network devnet|preprod, --nodes, --threshold,
--http-base, --server-base, --data-dir, --variant, --anchor-mode,
--anchor-key-file, --confirm-public-anchor preprod. Load options:
--concurrency, --payload-bytes, --duration, --rate, --sample, --spread,
--node, and --report-dir.
EOF
}

COMMAND="${1:-help}"; [ "$#" -eq 0 ] || shift
parse "$@"
[ "$COMMAND" != quickstart ] || [ "$PROFILE" != light ] || ANCHOR=true
validate_common

case "$COMMAND" in
  profiles)
    note "light     local multi-chain cluster (default 3 nodes; supports 1..16)"
    note "evidence  product demo with Kafka, S3-compatible storage, and IPFS (composite|role)"
    note "eutxo     maintained virtual-ledger, bridge, and ZK demos (ledger|bridge|zk)";;
  describe)
    case "${POSITIONAL[0]:-$PROFILE}" in
      light) note "Eight chains, MPF finality/proofs, governed membership, composite outbox effect, optional L1 anchor.";;
      evidence) note "Maintained evidence product deployment; Docker required. Evidence is the product, this is its demo harness.";;
      eutxo) note "Maintained experimental EUTxO scenarios; no real funds in the ledger variant.";;
      *) die "unknown profile";; esac;;
  doctor)
    need java; need python3; need curl; need jq
    python3 - <<'PY' >/dev/null \
      || die "Python 3 is missing required standard-library hash support"
import hashlib
hashlib.sha256(b"yano-showcase-doctor").digest()
hashlib.blake2b(b"yano-showcase-doctor", digest_size=32).digest()
PY
    java_version="$(java -version 2>&1 | sed -n '1s/.*version "\([^"]*\)".*/\1/p')"
    case "$java_version" in 25|25.*) ;; *) die "Java 25 is required (found ${java_version:-unknown})";; esac
    [ -f "$YANO_HOME/yano.jar" ] || die "yano.jar missing"
    [ -f "$YANO_HOME/config/application-appchain.yml" ] || die "showcase app-chain config missing"
    [ -x "$YANO_HOME/appchain-cluster/loadtest.sh" ] || die "packaged burst load driver missing"
    [ -x "$YANO_HOME/appchain-cluster/soaktest.sh" ] || die "packaged soak driver missing"
    plugin_file >/dev/null
    [ "$PROFILE" != evidence ] || need docker
    [ "$PROFILE" != eutxo ] || [ -x "$YANO_HOME/yano.sh" ] || die "maintained EUTxO CLI missing"
    note "doctor: Java $java_version, $(python3 --version 2>&1), curl, jq, and packaged artifacts are present"
    note "doctor: Python helpers use the standard library only; no pip or virtual environment is needed";;
  prepare)
    case "$PROFILE" in light) prepare_light;; evidence) delegate_evidence prepare;; eutxo) delegate_eutxo prepare;; esac;;
  up)
    case "$PROFILE" in light) up_light;; evidence) delegate_evidence up;; eutxo) delegate_eutxo up;; esac;;
  quickstart)
    if [ "$PROFILE" = light ]; then
      up_light
      [ "$ANCHOR_MODE" != script ] || { cluster_env; "$CLUSTER" anchor-bootstrap workflow-chain; }
      run_composite quickstart
      verify_light
      note "Status UI: http://127.0.0.1:$HTTP_BASE/ui/app-chain/"
    else
      case "$PROFILE" in evidence) delegate_evidence prepare; delegate_evidence up; delegate_evidence run;;
        eutxo) delegate_eutxo prepare; delegate_eutxo up; delegate_eutxo run;; esac
    fi;;
  status)
    adopt_marker
    case "$PROFILE" in light) cluster_env; "$CLUSTER" status;; evidence) delegate_evidence status;; eutxo) delegate_eutxo status;; esac;;
  ui)
    adopt_marker; note "http://127.0.0.1:$((HTTP_BASE + NODE))/ui/app-chain/";;
  logs)
    adopt_marker; cluster_env
    if [ "$FOLLOW" = true ]; then "$CLUSTER" logs "$NODE" -f; else "$CLUSTER" logs "$NODE"; fi;;
  stop)
    adopt_marker
    case "$PROFILE" in light) cluster_env; "$CLUSTER" stop;; evidence) delegate_evidence stop;; eutxo) delegate_eutxo stop;; esac;;
  restart)
    adopt_marker; [ "$PROFILE" = light ] || die "restart currently applies to the light profile"
    cluster_env; "$CLUSTER" stop; write_node_configs "$NODES"; up_light
    if [ -f "$(joined_file)" ]; then
      while IFS= read -r joined; do
        [ -z "$joined" ] || { write_node_configs "$((joined + 1))"; cluster_env; "$CLUSTER" node resume "$joined"; }
      done < "$(joined_file)"
    fi;;
  reset)
    [ "$YES" = true ] || die "reset requires --yes and preserves nothing in the named instance"
    local_root="$(instance_root)"; [ -n "$INSTANCE" ] || die "instance is required"
    if [ -d "$local_root" ]; then
      adopt_marker; if [ "$PROFILE" = light ]; then cluster_env; "$CLUSTER" stop || true; fi
      rm -rf -- "$local_root"; note "removed $local_root (not recoverable by the showcase)"
    fi;;
  config)
    adopt_marker
    case "${POSITIONAL[0]:-show}" in show) show_config;; paths) config_paths;;
      export) config_export "${POSITIONAL[1]:-}";; *) die "config accepts show, paths, or export";; esac;;
  run)
    adopt_marker
    if [ "$PROFILE" = evidence ]; then
      [ "${POSITIONAL[0]:-}" = roles ] && delegate_evidence role-lifecycle || delegate_evidence run
    elif [ "$PROFILE" = eutxo ]; then delegate_eutxo run
    else
      case "${POSITIONAL[0]:-}" in
        orders) run_orders "${POSITIONAL[1]:-}";; registry) run_registry "${POSITIONAL[1]:-}" "${POSITIONAL[2]:-}";;
        approvals) run_approvals "${POSITIONAL[1]:-}";; balances) run_balances;;
        documents) run_documents "${POSITIONAL[1]:-}";; composite) run_composite "${POSITIONAL[1]:-}";;
        roles) run_roles;; eutxo) run_eutxo;; anchor) cluster_env; "$CLUSTER" status;;
        all) run_orders; run_registry; run_approvals; run_balances; run_documents; run_composite; run_roles; run_eutxo;;
        *) die "unknown run scenario";; esac
    fi;;
  approvals)
    adopt_marker
    case "${POSITIONAL[0]:-}" in
      propose) approval_propose "${POSITIONAL[1]:?proposal id required}" "${POSITIONAL[2]:?payload required}" "${POSITIONAL[3]:-2}";;
      approve) approval_approve "${POSITIONAL[1]:?proposal id required}" "${POSITIONAL[2]:?member node required}";;
      *) die "approvals accepts propose or approve";; esac;;
  composite)
    adopt_marker
    case "${POSITIONAL[0]:-}" in
      register-order) composite_register "${POSITIONAL[1]:?order key required}" "${POSITIONAL[2]:?order JSON required}";;
      propose) composite_propose "${POSITIONAL[1]:?proposal id required}" "${POSITIONAL[2]:?order JSON required}";;
      approve) composite_approve "${POSITIONAL[1]:?proposal id required}" "${POSITIONAL[2]:?member node required}";;
      release) composite_release "${POSITIONAL[1]:?release id required}" "${POSITIONAL[2]:?order key required}" "${POSITIONAL[3]:?proposal id required}";;
      verify) composite_verify "${POSITIONAL[1]:?release id required}";;
      *) die "unknown composite operation";; esac;;
  load)
    adopt_marker
    [ -n "$COUNT" ] && [[ "$COUNT" =~ ^[0-9]+$ ]] && [ "$COUNT" -ge 1 ] || die "load requires --count N"
    for ((load_index=1;load_index<=COUNT;load_index++)); do
      suffix="load-$(date +%s)-$load_index"
      case "${POSITIONAL[0]:-}" in orders) run_orders "$suffix";; registry) run_registry "$suffix" "value-$load_index";;
        approvals) run_approvals "$suffix";; documents) run_documents "$suffix";; composite) run_composite "$suffix";;
        *) die "unsupported load scenario";; esac
    done;;
  load-test)
    adopt_marker; run_load_test "${POSITIONAL[0]:-orders}";;
  soak-test)
    adopt_marker; run_soak_test "${POSITIONAL[0]:-orders}";;
  verify)
    adopt_marker
    [ "$PROFILE" = light ] && verify_light || { [ "$PROFILE" = evidence ] && delegate_evidence verify || delegate_eutxo verify; };;
  member)
    adopt_marker; [ "${POSITIONAL[0]:-}" = join ] || die "usage: member join <next-index>"
    joined="${POSITIONAL[1]:?node index required}"
    [[ "$joined" =~ ^[0-9]+$ ]] && [ "$joined" -ge "$NODES" ] && [ "$joined" -le 15 ] \
      || die "joined-node index must be between the bootstrap count and 15"
    write_node_configs "$((joined + 1))"; cluster_env
    if [ -f "$(joined_file)" ] && grep -Fxq "$joined" "$(joined_file)"; then
      "$CLUSTER" node resume "$joined"
    else
      "$CLUSTER" node join "$joined"
      printf '%s\n' "$joined" >> "$(joined_file)"; chmod 600 "$(joined_file)"
    fi;;
  threshold)
    adopt_marker; [ "${POSITIONAL[0]:-}" = set ] || die "usage: threshold set <n>"
    cluster_env; "$CLUSTER" threshold set "${POSITIONAL[1]:?threshold required}";;
  governance)
    adopt_marker; [ "${POSITIONAL[0]:-}" = activate ] \
      || die "usage: governance activate"
    [ "$PROFILE" = light ] || die "governance activate applies to the light profile"
    governance_activate;;
  anchor)
    adopt_marker; [ "${POSITIONAL[0]:-}" = bootstrap ] || die "usage: anchor bootstrap [chain]"
    cluster_env; "$CLUSTER" anchor-bootstrap "${POSITIONAL[1]:-workflow-chain}";;
  help|-h|--help) usage;;
  *) usage; exit 1;;
esac
