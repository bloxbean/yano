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
  documents-chain workflow-chain roles-chain payments-chain authenticated-map-chain)
AUTHMAP_GENERATOR=com.bloxbean.cardano.yano.appchain.showcase.ShowcaseAuthenticatedMapConfig
AUTHMAP_CHAIN_INDEX=8

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
ANCHOR_CHAINS="workflow-chain"
ANCHOR_MODE_EXPLICIT=false
ANCHOR_KEY_EXPLICIT=false
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
      --anchor-mode) ANCHOR=true; ANCHOR_MODE="${2:-}"; ANCHOR_MODE_EXPLICIT=true; shift 2;;
      --anchor-key-file) ANCHOR=true; ANCHOR_KEY_FILE="${2:-}"; ANCHOR_KEY_EXPLICIT=true; shift 2;;
      --anchor-chain|--anchor-chains) ANCHOR=true; ANCHOR_CHAINS="${2:-}"; shift 2;;
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

chain_in_csv() {
  case ",${1}," in *",$2,"*) return 0;; *) return 1;; esac
}

canonical_anchor_chains() {
  local requested="$1" item cid seen="" result=""
  local -a values=()
  [ "$requested" != all ] || requested="$(IFS=,; printf '%s' "${LIGHT_CHAINS[*]}")"
  [ -n "$requested" ] || die "anchor chain scope cannot be empty"
  IFS=',' read -r -a values <<< "$requested"
  for item in "${values[@]}"; do
    [[ "$item" =~ ^[A-Za-z0-9._~-]{1,128}$ ]] || die "invalid anchor chain id: $item"
    chain_in_csv "$seen" "$item" && die "duplicate anchor chain: $item"
    seen+="${seen:+,}$item"
    local found=false
    for cid in "${LIGHT_CHAINS[@]}"; do [ "$cid" != "$item" ] || found=true; done
    [ "$found" = true ] || die "unknown showcase anchor chain: $item"
  done
  for cid in "${LIGHT_CHAINS[@]}"; do
    chain_in_csv "$seen" "$cid" && result+="${result:+,}$cid"
  done
  printf '%s' "$result"
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
authenticated_map_properties() { printf '%s/authenticated-map.properties' "$(instance_root)"; }
authenticated_map_genesis() { printf '%s/authenticated-map-genesis.hex' "$(instance_root)"; }

plugin_file() {
  local matches=("$YANO_HOME"/plugins/yano-appchain-showcase-*-bundle.jar)
  [ "${#matches[@]}" -eq 1 ] && [ -f "${matches[0]}" ] \
    || die "expected exactly one showcase plugin bundle"
  printf '%s' "${matches[0]}"
}

generate_authenticated_map_candidate() {
  [ -z "${YANO_CLUSTER_MEMBER_KEY_DIR:-}" ] \
    || die "the light showcase uses deterministic demo membership; custom member keys need a custom generated profile"
  local root="$(instance_root)" members candidate
  members="$("$CLUSTER" keys "$NODES" | awk 'NR > 1 {print $3}' | paste -sd, -)"
  [ -n "$members" ] || die "could not derive authenticated-map bootstrap member keys"
  candidate="$(mktemp "$root/.authenticated-map.properties.XXXXXX")"
  chmod 600 "$candidate"
  if ! java -cp "$(plugin_file):$YANO_HOME/yano.jar" "$AUTHMAP_GENERATOR" \
      --runtime-jar "$YANO_HOME/yano.jar" \
      --chain-id authenticated-map-chain \
      --members "$members" \
      --threshold "$THRESHOLD" > "$candidate"; then
    rm -f -- "$candidate"
    die "could not generate the release-matched authenticated-map genesis"
  fi
  python3 - "$candidate" "$AUTHMAP_CHAIN_INDEX" <<'PY' >/dev/null || {
import pathlib
import re
import sys

path = pathlib.Path(sys.argv[1])
prefix = f"yano.app-chain.chains[{sys.argv[2]}]."
expected = {
    prefix + "state.commitment-profile",
    prefix + "state.format-fingerprint",
    prefix + "state.genesis-id",
    prefix + "machines.authenticated-map.genesis-cbor-hex",
}
values = {}
for line in path.read_text(encoding="ascii").splitlines():
    if "=" not in line:
        raise ValueError("invalid generated property")
    key, value = line.split("=", 1)
    if key in values or key not in expected:
        raise ValueError("unknown or duplicate generated property")
    values[key] = value
if set(values) != expected:
    raise ValueError("generated property set is incomplete")
if values[prefix + "state.commitment-profile"] != "mpf-blake2b256-v1":
    raise ValueError("unexpected commitment profile")
for name in ("state.format-fingerprint", "state.genesis-id"):
    if not re.fullmatch(r"[0-9a-f]{64}", values[prefix + name]):
        raise ValueError("invalid generated identity digest")
genesis = values[prefix + "machines.authenticated-map.genesis-cbor-hex"]
if not genesis or len(genesis) > 32 * 1024 * 1024 or not re.fullmatch(r"(?:[0-9a-f]{2})+", genesis):
    raise ValueError("invalid generated genesis")
PY
    rm -f -- "$candidate"
    die "authenticated-map generator returned an invalid configuration"
  }
  printf '%s' "$candidate"
}

install_authenticated_map_candidate() {
  local candidate="$1" properties="$(authenticated_map_properties)"
  mv -f -- "$candidate" "$properties"
  chmod 600 "$properties"
  python3 - "$properties" "$(authenticated_map_genesis)" "$AUTHMAP_CHAIN_INDEX" <<'PY'
import os
import pathlib
import sys

source = pathlib.Path(sys.argv[1])
target = pathlib.Path(sys.argv[2])
key = f"yano.app-chain.chains[{sys.argv[3]}].machines.authenticated-map.genesis-cbor-hex="
matches = [line[len(key):] for line in source.read_text(encoding="ascii").splitlines()
           if line.startswith(key)]
if len(matches) != 1:
    raise ValueError("authenticated-map genesis property is missing or duplicated")
temporary = target.with_name(target.name + f".tmp.{os.getpid()}")
descriptor = os.open(temporary, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
try:
    os.write(descriptor, (matches[0] + "\n").encode("ascii"))
    os.fsync(descriptor)
finally:
    os.close(descriptor)
os.replace(temporary, target)
os.chmod(target, 0o600)
PY
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

marker_anchor_chains() {
  python3 - "$(marker)" <<'PY'
import json,sys
anchor=json.load(open(sys.argv[1], encoding="utf-8")).get("anchor", {})
if not anchor.get("enabled"):
    print("")
elif isinstance(anchor.get("chainIds"), list):
    print(",".join(anchor["chainIds"]))
elif anchor.get("chainId"):
    print(anchor["chainId"])
else:
    print("all")
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
  if [ "$ANCHOR" = true ]; then
    ANCHOR_CHAINS="$(marker_anchor_chains)"
    ANCHOR_CHAINS="$(canonical_anchor_chains "$ANCHOR_CHAINS")"
  fi
  local retained_key
  retained_key="$(marker_value anchor.keyReference)"
  if [ "$retained_key" != None ] && [ "$retained_key" != null ] && [ -n "$retained_key" ]; then
    ANCHOR_KEY_FILE="$retained_key"
  fi
}

write_node_configs() {
  local count="$1" directory="$(node_config_dir)" i file
  [ -f "$(authenticated_map_properties)" ] \
    || die "authenticated-map genesis is missing; prepare the instance first"
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
      while IFS= read -r property || [ -n "$property" ]; do
        printf '%s\n' "$property"
      done < "$(authenticated_map_properties)"
    } > "$file"
    chmod 600 "$file"
  done
}

prepare_light() {
  need java; need python3; need jq; need curl
  [ -x "$CLUSTER" ] || die "packaged cluster launcher is missing"
  local root="$(instance_root)" plugin="$(plugin_file)" candidate args=()
  if [ -e "$root" ] && [ -L "$root" ]; then die "instance root must not be a symlink"; fi
  mkdir -p "$root"; chmod 700 "$root"
  candidate="$(generate_authenticated_map_candidate)"
  args=(ensure --marker "$(marker)" --version "$VERSION" --profile light
    --variant default --network "$NETWORK" --nodes "$NODES" --threshold "$THRESHOLD"
    --http-base "$HTTP_BASE" --server-base "$SERVER_BASE"
    --config "$YANO_HOME/config/application-appchain.yml" --plugin "$plugin"
    --authenticated-map-config "$candidate")
  if [ "$ANCHOR" = true ]; then args+=(--anchor --anchor-mode "$ANCHOR_MODE"); fi
  if [ "$ANCHOR" = true ]; then
    local -a anchor_chains=()
    IFS=',' read -r -a anchor_chains <<< "$ANCHOR_CHAINS"
    local anchor_chain
    for anchor_chain in "${anchor_chains[@]}"; do args+=(--anchor-chain "$anchor_chain"); done
  fi
  if [ -n "$ANCHOR_KEY_FILE" ]; then args+=(--anchor-key-file "$ANCHOR_KEY_FILE"); fi
  if ! python3 "$IDENTITY" "${args[@]}"; then
    rm -f -- "$candidate"
    return 1
  fi
  install_authenticated_map_candidate "$candidate"
  write_node_configs "$NODES"
  note "Prepared light instance '$INSTANCE'"
  note "  data:    $root"
  note "  config:  $YANO_HOME/config/application-appchain.yml"
  note "  marker:  $(marker)"
  note "  plugin:  $plugin"
  note "  map:     $(authenticated_map_genesis)"
}

cluster_env() {
  export YANO_HOME YANO_CLUSTER_DIR="$(cluster_dir)"
  export YANO_CLUSTER_HTTP_BASE="$HTTP_BASE" YANO_CLUSTER_SERVER_BASE="$SERVER_BASE"
  export YANO_CLUSTER_NODE_CONFIG_DIR="$(node_config_dir)"
  export YANO_CLUSTER_PRIVATE_CONFIG_DIR="$(instance_root)/private-config"
  export YANO_CLUSTER_API_KEY="$API_KEY"
  unset YANO_CLUSTER_ANCHOR_CHAIN YANO_CLUSTER_ANCHOR_CHAINS
  [ "$ANCHOR" != true ] || export YANO_CLUSTER_ANCHOR_CHAINS="$ANCHOR_CHAINS"
  if [ -n "$ANCHOR_KEY_FILE" ]; then export YANO_CLUSTER_ANCHOR_KEY_FILE="$ANCHOR_KEY_FILE"; fi
}

up_light() {
  prepare_light
  cluster_env
  local args=(start "$NODES" --network "$NETWORK" --threshold "$THRESHOLD"
    --http-base "$HTTP_BASE" --server-base "$SERVER_BASE")
  if [ "$ANCHOR" = true ]; then
    args+=(--anchor-mode "$ANCHOR_MODE")
    local -a anchor_chains=()
    IFS=',' read -r -a anchor_chains <<< "$ANCHOR_CHAINS"
    local anchor_chain
    for anchor_chain in "${anchor_chains[@]}"; do args+=(--anchor-chain "$anchor_chain"); done
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

expect_authenticated_map_filtered() {
  local body="$1" collection="$2" key="$3" label="$4"
  local rejected_id barrier_key barrier_value barrier_body barrier_id status state_key proof
  rejected_id="$(submit_hex authenticated-map-chain authenticated-map.command.v1 "$body")"

  # HTTP 202 is ingress/pool acceptance. A later valid message is the barrier
  # proving the candidate-height validator had an opportunity to filter the
  # invalid message before we assert non-finalization and state absence.
  barrier_key="validation-barrier-${rejected_id:0:16}"
  barrier_value="$(python3 "$CODEC" authmap-value opaque "$barrier_key")"
  barrier_body="$(authmap_basic_body owner "$(python3 "$CODEC" authmap put attachments \
    "$barrier_key" "$barrier_value")")"
  barrier_id="$(submit_hex authenticated-map-chain \
    authenticated-map.command.v1 "$barrier_body")"
  wait_message authenticated-map-chain "$barrier_id" >/dev/null

  status="$(curl -sS -o /dev/null -w '%{http_code}' \
    "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/authenticated-map-chain/messages/$rejected_id")"
  [ "$status" = 404 ] || die "$label unexpectedly finalized (HTTP $status)"
  state_key="$(python3 "$CODEC" authmap state-key "$collection" "$key")"
  proof="$(proof_key authenticated-map-chain "$state_key")"
  printf '%s' "$proof" | jq -e \
    '.presence == "ABSENT" and .valueHex == null and (.proofWireHex | length > 0)' \
    >/dev/null || die "$label changed authenticated state"
  note "REJECTED before finalization as expected: $label"
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

run_authenticated_map() {
  local suffix="${1:-$(date +%s)}" key value body id query state_key result

  key="attachment-$suffix"
  value="$(python3 "$CODEC" authmap-value opaque "demo-bytes-$suffix")"
  body="$(authmap_basic_body owner "$(python3 "$CODEC" authmap put attachments "$key" "$value")")"
  id="$(submit_hex authenticated-map-chain authenticated-map.command.v1 "$body")"
  wait_message authenticated-map-chain "$id" >/dev/null

  key="event-$suffix"
  value="$(python3 "$CODEC" authmap-value event created 1)"
  body="$(authmap_basic_body member "$(python3 "$CODEC" authmap put canonical-events "$key" "$value")")"
  id="$(submit_hex authenticated-map-chain authenticated-map.command.v1 "$body")"
  wait_message authenticated-map-chain "$id" >/dev/null

  key="sku-$suffix"
  value="$(python3 "$CODEC" authmap-value product "$key" 5 active "showcase product")"
  body="$(authmap_basic_body owner "$(python3 "$CODEC" authmap put products "$key" "$value")")"
  id="$(submit_hex authenticated-map-chain authenticated-map.command.v1 "$body")"
  wait_message authenticated-map-chain "$id" >/dev/null

  value="$(python3 "$CODEC" authmap-value gtin 95012346)"
  body="$(authmap_basic_body owner "$(python3 "$CODEC" authmap put gtins 95012346 "$value")")"
  id="$(submit_hex authenticated-map-chain authenticated-map.command.v1 "$body")"
  wait_message authenticated-map-chain "$id" >/dev/null

  value="$(python3 "$CODEC" authmap-value product invalid-sku 5 unknown)"
  body="$(authmap_basic_body owner "$(python3 "$CODEC" authmap put products invalid-sku "$value")")"
  expect_authenticated_map_filtered \
    "$body" products invalid-sku "product schema violation"

  value="$(python3 "$CODEC" authmap-value gtin 95012345)"
  body="$(authmap_basic_body owner "$(python3 "$CODEC" authmap put gtins 95012345 "$value")")"
  expect_authenticated_map_filtered \
    "$body" gtins 95012345 "custom GTIN validator violation"

  query="$(python3 "$CODEC" authmap query products "$key")"
  result="$(curl -fsS -X POST \
    "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/authenticated-map-chain/query/authenticated-map/entry-v1" \
    -H 'Content-Type: application/json' \
    -d "$(jq -nc --arg params "$query" '{paramsHex:$params}')")"
  note "root-attested schema-validated product entry:"
  printf '%s' "$result" | jq '{chainId,stateMachineId,committedHeight,stateRoot,payloadHex}'

  state_key="$(python3 "$CODEC" authmap state-key products "$key")"
  note "native proof for the product collection entry:"
  proof_key authenticated-map-chain "$state_key" \
    | jq '{chainId,committedHeight,stateRoot,valueHex,proofWireHex}'
  note "AUTHENTICATED MAP: opaque, canonical CBOR, schema, and plugin validation passed"

  run_authenticated_map_governed "$suffix"
  note "Inspect every record in the packaged console:"
  note "  http://127.0.0.1:$((HTTP_BASE + NODE))/ui/app-chain/authenticated-map/?chain=authenticated-map-chain"
}

# The composite authenticated-map machine admits only the final v1 command
# envelope (action + evidence). Wrap a legacy single-mutation command from the
# codec into a basic (evidence-free) envelope with the release-matched CLI.
authmap_basic_body() {
  local kind="$1" legacy_hex="$2" action
  [ -x "$YANO_HOME/yano.sh" ] || die "packaged yano.sh CLI is required for authenticated-map commands"
  action="$("$YANO_HOME/yano.sh" appchain authenticated-map action \
    --command-hex "$legacy_hex" --assignments "0:$kind::0")"
  "$YANO_HOME/yano.sh" appchain authenticated-map command \
    --action-hex "$action" --evidence-hex ''
}

# Demo-only deterministic actor seed shared with ShowcaseAuthenticatedMapConfig.
demo_actor_seed() {
  python3 -c 'import hashlib,sys
print(hashlib.sha256(("yano-showcase-demo-actor:"+sys.argv[1]).encode()).hexdigest())' "$1"
}

authmap_domain() {
  curl -fsS "http://127.0.0.1:$((HTTP_BASE + NODE))/api/v1/plugins/com.bloxbean.cardano.yano.appchain.stdlib/$1?chain=authenticated-map-chain"
}

authmap_tip_height() {
  curl -fsS "http://127.0.0.1:$((HTTP_BASE + NODE))/api/v1/app-chain/chains/authenticated-map-chain/tip" \
    | jq -r '.height'
}

expect_receipt_applied() {
  local id="$1" label="$2" deadline=$(( $(date +%s) + 60 )) receipt
  while :; do
    receipt="$(authmap_domain "authenticated-map/receipts/$id" 2>/dev/null || true)"
    if printf '%s' "$receipt" | jq -e '.record.presence == 1' >/dev/null 2>&1; then
      printf '%s' "$receipt" | jq -e '.record.status == 0' >/dev/null \
        || die "$label was rejected: $(printf '%s' "$receipt" | jq -c .record)"
      return 0
    fi
    [ "$(date +%s)" -le "$deadline" ] || die "timed out waiting for $label receipt"
    sleep 1
  done
}

# ADR-025.2 Phase F: direct-role and two-distinct-organization approval flows
# exercised end to end through offline CLI authoring and the demo signer. The
# node API and console never see actor private keys.
run_authenticated_map_governed() {
  local suffix="$1" yano="$YANO_HOME/yano.sh" signer="$SHOWCASE_HOME/tools/showcase_signer.py"
  local genesis_id issuer_seed issuer_pub key value cmd action height deadline
  [ -x "$yano" ] || die "packaged yano.sh CLI is required for the governed showcase"
  genesis_id="$(grep 'state.genesis-id=' "$(authenticated_map_properties)" | cut -d= -f2)"
  [ -n "$genesis_id" ] || die "authenticated-map genesis identity is missing; run prepare first"
  issuer_seed="$(demo_actor_seed issuer-a)"
  issuer_pub="$(python3 "$signer" public-key "$issuer_seed")"

  # --- direct-role: issuer-a writes to governed-catalog under issuer-write ---
  key="catalog-$suffix"
  value="$(python3 "$CODEC" authmap-value opaque "governed-entry-$suffix")"
  cmd="$(python3 "$CODEC" authmap put governed-catalog "$key" "$value")"
  action="$("$yano" appchain authenticated-map action \
    --command-hex "$cmd" --assignments 0:governed-role:issuer-write:1)"
  local authz_id preimage signature evidence governed_cmd id
  authz_id="$(python3 -c 'import hashlib,sys
print(hashlib.sha256(("yano-showcase-authz:"+sys.argv[1]).encode()).hexdigest())' "$suffix")"
  height="$(authmap_tip_height)"
  deadline=$((height + 90))
  local direct_args=(--action-hex "$action" --authorization-id "$authz_id"
    --chain authenticated-map-chain --genesis-id "$genesis_id" --indexes 0
    --policy issuer-write --policy-revision 1 --actor issuer-a --actor-revision 1
    --key issuer-a-k1 --public-key "$issuer_pub"
    --issued-height "$height" --deadline-height "$deadline")
  preimage="$("$yano" appchain authenticated-map direct-preimage "${direct_args[@]}")"
  signature="$(python3 "$signer" sign "$issuer_seed" "$preimage")"
  evidence="$("$yano" appchain authenticated-map direct-complete \
    "${direct_args[@]}" --signature "$signature")"
  governed_cmd="$("$yano" appchain authenticated-map command \
    --action-hex "$action" --evidence-hex "$evidence")"
  id="$(submit_hex authenticated-map-chain authenticated-map.command.v1 "$governed_cmd")"
  wait_message authenticated-map-chain "$id" >/dev/null
  expect_receipt_applied "$id" "direct-role governed-catalog write"
  authmap_domain "authenticated-map/direct-consumptions/issuer-a/$authz_id" \
    | jq -e '.record.policyId == "issuer-write" and .record.appliedHeight > 0' >/dev/null \
    || die "direct authorization consumption record is missing"
  note "DIRECT ROLE: issuer-a wrote governed-catalog/$key with externally signed evidence"

  # --- approval: issuer-a proposes, auditors from two organizations approve ---
  local proposal payload_hash statement approve_seed reference approval_cmd
  key="release-$suffix"
  value="$(python3 "$CODEC" authmap-value opaque "released-product-$suffix")"
  cmd="$(python3 "$CODEC" authmap put released-products "$key" "$value")"
  action="$("$yano" appchain authenticated-map action \
    --command-hex "$cmd" --assignments 0:approval:product-release:1)"
  payload_hash="$("$yano" appchain authenticated-map approval-payload \
    --action-hex "$action" --genesis-id "$genesis_id")"
  proposal="release-$suffix"
  height="$(authmap_tip_height)"
  deadline=$((height + 300))
  local seed_file
  seed_file="$(mktemp "$(instance_root)/.demo-seed.XXXXXX")"
  chmod 600 "$seed_file"
  sign_statement() {
    local actor="$1" seed="$2" statement_action="$3" clause="$4"
    printf '%s' "$seed" > "$seed_file"
    "$yano" appchain role sign --action "$statement_action" \
      --chain authenticated-map-chain --proposal "$proposal" \
      --policy product-release --policy-revision 1 \
      --payload-domain yano.authenticated-map.action.v1 \
      --payload-hash "$payload_hash" --deadline-height "$deadline" \
      --actor "$actor" --actor-revision 1 --key "$actor-k1" \
      --clause "$clause" --seed-file "$seed_file"
  }
  statement="$(sign_statement issuer-a "$issuer_seed" PROPOSE '')"
  id="$(submit_hex authenticated-map-chain role-approvals.command.v1 "$statement")"
  wait_message authenticated-map-chain "$id" >/dev/null
  for auditor in auditor-a auditor-b; do
    approve_seed="$(demo_actor_seed "$auditor")"
    statement="$(sign_statement "$auditor" "$approve_seed" APPROVE independent-auditors)"
    id="$(submit_hex authenticated-map-chain role-approvals.command.v1 "$statement")"
    wait_message authenticated-map-chain "$id" >/dev/null
  done
  rm -f -- "$seed_file"
  authmap_domain "proposals/$proposal" \
    | jq -e '.record.status == "APPROVED" and (.record.decisions | length) == 2' >/dev/null \
    || die "product-release proposal did not reach APPROVED with two distinct-organization decisions"
  reference="$("$yano" appchain authenticated-map approval-reference \
    --action-hex "$action" --proposal "$proposal" --indexes 0 \
    --policy product-release --policy-revision 1)"
  approval_cmd="$("$yano" appchain authenticated-map command \
    --action-hex "$action" --evidence-hex "$reference")"
  id="$(submit_hex authenticated-map-chain authenticated-map.command.v1 "$approval_cmd")"
  wait_message authenticated-map-chain "$id" >/dev/null
  expect_receipt_applied "$id" "approved released-products execution"
  authmap_domain "authenticated-map/approval-consumptions/$proposal" \
    | jq -e '.record.policyId == "product-release" and .record.appliedHeight > 0' >/dev/null \
    || die "approval consumption record is missing"
  note "APPROVAL: $proposal approved by auditors in two organizations and executed exactly once"
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
  local cid="$1" suffix="$2" body sender result value
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
    authenticated-map-chain)
      value="$(python3 "$CODEC" authmap-value opaque "$suffix")"
      body="$(authmap_basic_body owner \
        "$(python3 "$CODEC" authmap put attachments "$suffix" "$value")")"
      submit_hex "$cid" authenticated-map.command.v1 "$body" 0;;
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
  for cid in "${LIGHT_CHAINS[@]}"; do
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
  note "map genesis   : $(authenticated_map_genesis)"
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

resume_joined_nodes() {
  local joined
  if [ -f "$(joined_file)" ]; then
    while IFS= read -r joined; do
      [ -z "$joined" ] || {
        write_node_configs "$((joined + 1))"
        cluster_env
        "$CLUSTER" node resume "$joined"
      }
    done < "$(joined_file)"
  fi
}

anchor_enable() {
  local requested="${1:-}" requested_mode="$ANCHOR_MODE" requested_key="$ANCHOR_KEY_FILE"
  local mode_explicit="$ANCHOR_MODE_EXPLICIT" key_explicit="$ANCHOR_KEY_EXPLICIT"
  [ -n "$requested" ] || die "usage: anchor enable <chain-id|all>"
  adopt_marker
  [ "$PROFILE" = light ] || die "anchor enable applies only to the light profile"

  local current="" desired target combined cid
  [ "$ANCHOR" != true ] || current="$ANCHOR_CHAINS"
  target="$(canonical_anchor_chains "$requested")"
  combined="$current"
  local -a target_chains=()
  IFS=',' read -r -a target_chains <<< "$target"
  for cid in "${target_chains[@]}"; do
    chain_in_csv "$combined" "$cid" || combined+="${combined:+,}$cid"
  done
  desired="$(canonical_anchor_chains "$combined")"

  if [ "$ANCHOR" = true ]; then
    [ "$mode_explicit" != true ] || [ "$requested_mode" = "$ANCHOR_MODE" ] \
      || die "retained anchor mode is '$ANCHOR_MODE' and cannot be changed"
    [ "$key_explicit" != true ] || [ "$requested_key" = "$ANCHOR_KEY_FILE" ] \
      || die "retained anchor key reference cannot be changed"
  else
    ANCHOR_MODE="$requested_mode"
    ANCHOR_KEY_FILE="$requested_key"
  fi

  if [ "$NETWORK" = preprod ] && [ "$desired" != "$current" ]; then
    [ "$PUBLIC_CONFIRM" = preprod ] \
      || die "adding a preprod anchor requires --confirm-public-anchor preprod"
    [ -n "$ANCHOR_KEY_FILE" ] \
      || die "adding a preprod anchor requires --anchor-key-file (or a retained anchor key)"
  fi
  [ -f "$(cluster_dir)/cluster-appchain-identity.json" ] \
    || die "cluster app-chain identity marker is missing"
  [ -f "$(cluster_dir)/cluster.env" ] || die "cluster environment record is missing"

  cluster_env
  "$CLUSTER" stop

  local -a migrate=(anchor-enable --marker "$(marker)"
    --cluster-marker "$(cluster_dir)/cluster-appchain-identity.json"
    --cluster-env "$(cluster_dir)/cluster.env"
    --config "$YANO_HOME/config/application-appchain.yml" --plugin "$(plugin_file)"
    --authenticated-map-config "$(authenticated_map_properties)"
    --anchor-mode "$ANCHOR_MODE")
  local -a desired_chains=()
  IFS=',' read -r -a desired_chains <<< "$desired"
  for target in "${desired_chains[@]}"; do migrate+=(--anchor-chain "$target"); done
  [ -z "$ANCHOR_KEY_FILE" ] || migrate+=(--anchor-key-file "$ANCHOR_KEY_FILE")
  python3 "$IDENTITY" "${migrate[@]}" >/dev/null

  adopt_marker
  up_light
  resume_joined_nodes
  note "Anchor scope now includes: $ANCHOR_CHAINS"
  if [ "$ANCHOR_MODE" = script ]; then
    note "Newly enabled chains require a one-time bootstrap transaction."
    note "Run: ./showcase.sh anchor bootstrap all --instance $INSTANCE"
  else
    note "Metadata anchors need no bootstrap; funded chains begin anchoring automatically."
  fi
}

anchor_bootstrap() {
  local requested="${1:-workflow-chain}" cid
  [ "$ANCHOR" = true ] || die "anchoring is not enabled for this instance"
  cluster_env
  if [ "$requested" = all ]; then
    local -a selected=()
    IFS=',' read -r -a selected <<< "$ANCHOR_CHAINS"
    for cid in "${selected[@]}"; do
      "$CLUSTER" anchor-bootstrap "$cid"
      wait_anchor_bootstrapped "$cid"
    done
    return
  fi
  cid="$(canonical_anchor_chains "$requested")"
  [ "$cid" = "$requested" ] || die "anchor bootstrap accepts one chain id or all"
  chain_in_csv "$ANCHOR_CHAINS" "$cid" \
    || die "anchoring is not enabled for '$cid' (enabled: $ANCHOR_CHAINS)"
  "$CLUSTER" anchor-bootstrap "$cid"
  wait_anchor_bootstrapped "$cid"
}

wait_anchor_bootstrapped() {
  local cid="$1" deadline=$(( $(date +%s) + 900 )) status
  while :; do
    status="$(curl -fsS --connect-timeout 2 --max-time 5 \
      "http://127.0.0.1:$HTTP_BASE/api/v1/app-chain/chains/$cid/status" 2>/dev/null || true)"
    if [ -n "$status" ] && printf '%s' "$status" | jq -e '.anchor.bootstrapped == true' \
        >/dev/null 2>&1; then
      note "Script anchor confirmed for $cid"
      return
    fi
    [ "$(date +%s)" -le "$deadline" ] \
      || die "timed out waiting for the $cid bootstrap to become L1-visible"
    sleep 5
  done
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
  ./showcase.sh run orders|registry|authenticated-map|approvals|balances|documents|composite|roles|eutxo|anchor|all
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
  ./showcase.sh anchor enable <chain-id|all>
  ./showcase.sh anchor bootstrap [chain-id|all]

Common options: --instance, --network devnet|preprod, --nodes, --threshold,
--http-base, --server-base, --data-dir, --variant, --anchor-mode, --anchor-chain,
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
      light) note "Nine chains, including authenticated-map collections/validation, MPF proofs, governance, effects, and optional L1 anchor.";;
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
      run_authenticated_map quickstart
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
    resume_joined_nodes;;
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
        authenticated-map|authmap) run_authenticated_map "${POSITIONAL[1]:-}";;
        approvals) run_approvals "${POSITIONAL[1]:-}";; balances) run_balances;;
        documents) run_documents "${POSITIONAL[1]:-}";; composite) run_composite "${POSITIONAL[1]:-}";;
        roles) run_roles;; eutxo) run_eutxo;; anchor) cluster_env; "$CLUSTER" status;;
        all) run_orders; run_registry; run_authenticated_map; run_approvals; run_balances; run_documents; run_composite; run_roles; run_eutxo;;
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
    case "${POSITIONAL[0]:-}" in
      enable) anchor_enable "${POSITIONAL[1]:-}";;
      bootstrap) adopt_marker; anchor_bootstrap "${POSITIONAL[1]:-workflow-chain}";;
      *) die "usage: anchor enable <chain-id|all> | anchor bootstrap [chain-id|all]";;
    esac;;
  help|-h|--help) usage;;
  *) usage; exit 1;;
esac
