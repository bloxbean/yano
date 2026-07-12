#!/usr/bin/env bash
# =============================================================================
# Yano app-chain cluster launcher — spin up an N-node app-chain cluster for a
# quick demo. Defaults to a self-contained devnet (node 0 produces L1 blocks,
# the rest follow); can also run every node as a relay to a public network.
#
# Runs with whatever Yano build is available — the uber-jar (app/build/yano.jar)
# or the native binary (app/build/yano) — auto-detected.
#
# Quick start:
#   ./cluster.sh start 3            # 3-node devnet, chains from application-appchain.yml
#   ./cluster.sh status
#   ./cluster.sh submit orders-chain demo "hello"
#   ./cluster.sh stop               # keep data     |  clean = stop + wipe
#
# See ./README.md for the full guide.
# =============================================================================
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"          # app/ (dev layout): holds build/ + config/
REPO_DIR="$(cd "$APP_DIR/.." && pwd)"

# Yano "home" = the tree that holds config/ (application-appchain.yml + the
# network genesis referenced by relative paths). Nodes launch with cwd = HOME so
# the app resolves ./config/*. Defaults to the repo's app/ for local-dev use;
# override to run a RELEASED tree on a machine with no build:
#   YANO_HOME=/opt/yano ./cluster.sh start 3          # /opt/yano/config/... + a binary
# The binary is auto-detected under HOME (build/yano.jar, build/quarkus-app, or a
# release-root yano.jar / yano), or pointed at explicitly — anywhere on disk:
#   YANO_JAR=/downloads/yano.jar    YANO_NATIVE=/downloads/yano
YANO_HOME="${YANO_HOME:-$APP_DIR}"
CONFIG_FILE="$YANO_HOME/config/application-appchain.yml"

# --- Tunables (override via env) ---------------------------------------------
CLUSTER_DIR="${YANO_CLUSTER_DIR:-/tmp/yano-appchain-cluster}"
HTTP_BASE="${YANO_CLUSTER_HTTP_BASE:-7070}"       # node i HTTP  = HTTP_BASE + i
SERVER_BASE="${YANO_CLUSTER_SERVER_BASE:-13337}"  # node i n2n   = SERVER_BASE + i
NETWORK="devnet"
NETWORK_EXPLICIT=0                                # set when --network is passed
RUNTIME="auto"                                    # auto | jar | native
THRESHOLD=""                                      # default: majority
ENABLE_ANCHOR=0
ANCHOR_MODE="script"                              # metadata | script (--anchor-mode)
ANCHOR_KEY=""                                     # --anchor-key: funded wallet seed (hex, 32 bytes)
ANCHOR_EVERY=""                                   # --anchor-every: default 2 devnet / 30 public

# Deterministic demo member identities: node i uses seed = byte(i+1) x32.
# Precomputed Ed25519 public keys (standard Ed25519 == Yano app-chain keys).
# Covers up to 16 nodes without any crypto tooling; beyond that we derive live.
PUBKEYS=(
  8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c
  8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394
  ed4928c628d1c2c6eae90338905995612959273a5c63f93636c14614ac8737d1
  ca93ac1705187071d67b83c7ff0efe8108e8ec4530575d7726879333dbdabe7c
  6e7a1cdd29b0b78fd13af4c5598feff4ef2a97166e3ca6f2e4fbfccd80505bf1
  8a875fff1eb38451577acd5afee405456568dd7c89e090863a0557bc7af49f17
  ea4a6c63e29c520abef5507b132ec5f9954776aebebe7b92421eea691446d22c
  1398f62c6d1a457c51ba6a4b5f3dbd2f69fca93216218dc8997e416bd17d93ca
  fd1724385aa0c75b64fb78cd602fa1d991fdebf76b13c58ed702eac835e9f618
  43a72e714401762df66b68c26dfbdf2682aaec9f2474eca4613e424a0fbafd3c
  66be7e332c7a453332bd9d0a7f7db055f5c5ef1a06ada66d98b39fb6810c473a
  0b513ad9b4924015ca0902ed079044d3ac5dbec2306f06948c10da8eb6e39f2d
  91a28a0b74381593a4d9469579208926afc8ad82c8839b7644359b9eba9a4b3a
  0beef5a9e679e6a3e134fe27837bff32c7cb5f5d44ea09bcb0e542bad6a4c0cc
  d9bf2148748a85c89da5aad8ee0b0fc2d105fd39d41a4c796536354f0ae2900c
  5c9c6df261c9cb840475776aaefcd944b405328fab28f9b3a95ef40490d3de84
)

c_red()   { printf '\033[31m%s\033[0m\n' "$*"; }
c_grn()   { printf '\033[32m%s\033[0m\n' "$*"; }
c_ylw()   { printf '\033[33m%s\033[0m\n' "$*"; }
die()     { c_red "error: $*" >&2; exit 1; }

# --- Identities --------------------------------------------------------------
# Repeat a 2-hex-digit byte 32 times → a 32-byte hex seed.
repeat_byte() { local b="$1" out="" k; for ((k=0;k<32;k++)); do out+="$b"; done; printf '%s' "$out"; }
# 32-byte hex seed for node i (0-based): (i+1) as one byte, repeated 32 times.
node_seed()   { local b; printf -v b '%02x' "$(( $1 + 1 ))"; repeat_byte "$b"; }
# 32-byte anchor-wallet seed for node i (distinct from the member seed).
anchor_seed() { local b; printf -v b '%02x' "$(( $1 + 48 ))"; repeat_byte "$b"; }

# Member public key (hex) for node i: precomputed table, else derived live.
node_pub() {
  local i="$1"
  if [ "$i" -lt "${#PUBKEYS[@]}" ]; then
    printf '%s' "${PUBKEYS[$i]}"; return
  fi
  local pk
  pk="$(python3 - "$(node_seed "$i")" <<'PY' 2>/dev/null
import sys
try:
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    seed = bytes.fromhex(sys.argv[1])
    print(Ed25519PrivateKey.from_private_bytes(seed).public_key().public_bytes_raw().hex())
except Exception:
    pass
PY
)"
  [ -n "$pk" ] || die "cannot derive member key for node $i (>16 nodes needs python3 'cryptography')"
  printf '%s' "$pk"
}

# --- Runtime detection (jar vs native) ---------------------------------------
# Emits the launch prefix to stdout; nodes run with cwd = APP_DIR.
JAR=""; NATIVE=""
resolve_runtime() {
  # Explicit path overrides win (a released binary anywhere on disk); otherwise
  # auto-detect under YANO_HOME — dev layout (build/…) or a release-root binary.
  NATIVE="${YANO_NATIVE:-}"
  if [ -z "$NATIVE" ]; then
    if   [ -x "$YANO_HOME/build/yano" ]; then NATIVE="$YANO_HOME/build/yano"
    elif [ -x "$YANO_HOME/yano" ];       then NATIVE="$YANO_HOME/yano"
    fi
  fi
  JAR="${YANO_JAR:-}"
  if [ -z "$JAR" ]; then
    if   [ -f "$YANO_HOME/build/yano.jar" ];                    then JAR="$YANO_HOME/build/yano.jar"
    elif [ -f "$YANO_HOME/build/quarkus-app/quarkus-run.jar" ]; then JAR="$YANO_HOME/build/quarkus-app/quarkus-run.jar"
    elif [ -f "$YANO_HOME/yano.jar" ];                          then JAR="$YANO_HOME/yano.jar"
    fi
  fi
  case "$RUNTIME" in
    native) [ -n "$NATIVE" ] && [ -x "$NATIVE" ] || die "native binary not found — set YANO_NATIVE=/path/to/yano, or build: ./gradlew :app:build -Dquarkus.native.enabled=true";;
    jar)    [ -n "$JAR" ] && [ -f "$JAR" ]       || die "jar not found — set YANO_JAR=/path/to/yano.jar, or build: ./gradlew :app:quarkusBuild";;
    auto)
      if   [ -n "$JAR" ] && [ -f "$JAR" ];          then RUNTIME="jar"
      elif [ -n "$NATIVE" ] && [ -x "$NATIVE" ];    then RUNTIME="native"
      else die "no Yano binary found under $YANO_HOME. Build it (./gradlew :app:quarkusBuild), or point YANO_JAR=/path/to/yano.jar (or YANO_NATIVE) at a released binary and YANO_HOME at its config tree."; fi;;
    *) die "unknown runtime: $RUNTIME";;
  esac
}

# Chain indices defined in the config file (0,1,2,...), ignoring commented lines.
chain_indices() {
  [ -f "$CONFIG_FILE" ] || die "config not found: $CONFIG_FILE"
  grep -vE '^[[:space:]]*#' "$CONFIG_FILE" | grep -oE 'chains\[[0-9]+\]' | grep -oE '[0-9]+' | sort -un
}
# Chain ids in authored order, ignoring commented lines.
chain_ids() {
  [ -f "$CONFIG_FILE" ] || die "config not found: $CONFIG_FILE"
  grep -vE '^[[:space:]]*#' "$CONFIG_FILE" | grep -oE 'chain-id:[[:space:]]*"[^"]+"' | sed -E 's/.*"([^"]+)".*/\1/'
}

# --- CBOR helpers for the kv-registry state machine --------------------------
# A kv-registry command is CBOR [op(uint), key(bstr), value(bstr)]; op 0=PUT 1=DEL.
_hex_of()   { printf '%s' "$1" | od -An -v -tx1 | tr -d ' \n'; }
_bstr_cbor() {                          # ascii -> CBOR byte-string hex
  local s="$1" n; n="$(printf '%s' "$s" | wc -c | tr -d ' ')"
  if [ "$n" -lt 24 ]; then printf '%02x%s' "$(( 0x40 + n ))" "$(_hex_of "$s")"
  else printf '58%02x%s' "$n" "$(_hex_of "$s")"; fi
}
kv_cbor() {                             # <op> <key> <value> -> CBOR array hex
  printf '83%02x%s%s' "$1" "$(_bstr_cbor "$2")" "$(_bstr_cbor "$3")"
}

http_port()   { echo $(( HTTP_BASE + $1 )); }
server_port() { echo $(( SERVER_BASE + $1 )); }
node_dir()    { echo "$CLUSTER_DIR/node$1"; }
pid_file()    { echo "$CLUSTER_DIR/node$1.pid"; }
log_file()    { echo "$(node_dir "$1")/node.log"; }

# `start` records the cluster's network/anchor settings here so later commands
# (anchor-bootstrap, ...) act on the RUNNING cluster without re-passing flags.
env_file()    { echo "$CLUSTER_DIR/cluster.env"; }
save_cluster_env() {
  { echo "NETWORK=$NETWORK"; echo "ENABLE_ANCHOR=$ENABLE_ANCHOR"
    echo "ANCHOR_MODE=$ANCHOR_MODE"; } > "$(env_file)"
}
load_cluster_env() {
  [ -f "$(env_file)" ] || return 0
  local saved_network="$NETWORK"
  # shellcheck disable=SC1090
  . "$(env_file)"
  # An explicit --network on THIS invocation still wins over the saved value.
  [ "$NETWORK_EXPLICIT" = "1" ] && NETWORK="$saved_network"
}

# Anchor wallet address for chain $1: from node 0's status when the build
# exposes it (metadata mode always; script mode from newer builds), else from
# node 0's startup log (script mode on older builds logs it but omits it from
# status). All chains share node 0's wallet key, so the last log match is fine.
anchor_wallet_addr() {
  local cid="$1" a
  a="$(curl -s "http://localhost:$(http_port 0)/api/v1/app-chain/chains/$cid/status" 2>/dev/null \
    | jq -r '.anchor.walletAddress // .anchor.address // empty' 2>/dev/null)"
  if [ -z "$a" ] && [ -f "$(log_file 0)" ]; then
    a="$(grep -E 'script-anchor wallet address:|anchoring enabled \(address:' "$(log_file 0)" 2>/dev/null \
      | grep -oE 'addr(_test)?1[a-z0-9]+' | tail -1)"
  fi
  printf '%s' "$a"
}

# Members CSV (all N pubkeys) and default threshold (majority).
members_csv() { local n="$1" i out=""; for ((i=0;i<n;i++)); do out+="${out:+,}$(node_pub "$i")"; done; echo "$out"; }
default_threshold() { echo $(( $1 / 2 + 1 )); }

# App-chain peers for node i: every OTHER node's localhost:server-port.
peers_csv() {
  local n="$1" self="$2" j out=""
  for ((j=0;j<n;j++)); do [ "$j" -eq "$self" ] && continue; out+="${out:+,}localhost:$(server_port "$j")"; done
  echo "$out"
}

# -D system properties wiring the multi-chain config for node i.
chain_props() {
  local n="$1" i="$2" members threshold peers proposer idx
  members="$(members_csv "$n")"
  threshold="${THRESHOLD:-$(default_threshold "$n")}"
  peers="$(peers_csv "$n" "$i")"
  proposer="$(node_pub 0)"
  # Anchoring is LEADER-ONLY (node 0): script-mode followers co-sign and adopt
  # the identity with zero anchor config (008.4); metadata-mode followers need
  # nothing. Cadence default: every 2 app blocks on devnet (snappy demo), every
  # 30 on public networks (each anchor is a real fee-paying L1 tx).
  local anchor_every="${ANCHOR_EVERY:-$([ "$NETWORK" = devnet ] && echo 2 || echo 30)}"
  local -a props=()
  for idx in $(chain_indices); do
    props+=("-Dyano.app-chain.chains[$idx].signing-key=$(node_seed "$i")")
    props+=("-Dyano.app-chain.chains[$idx].members=$members")
    props+=("-Dyano.app-chain.chains[$idx].threshold=$threshold")
    props+=("-Dyano.app-chain.chains[$idx].peers=$peers")
    # Injected for every chain; rotating chains ignore it (sequencer.mode wins).
    props+=("-Dyano.app-chain.chains[$idx].sequencer.proposer=$proposer")
    if [ "$ENABLE_ANCHOR" = "1" ] && [ "$i" -eq 0 ]; then
      props+=("-Dyano.app-chain.chains[$idx].anchor.enabled=true")
      props+=("-Dyano.app-chain.chains[$idx].anchor.mode=$ANCHOR_MODE")
      props+=("-Dyano.app-chain.chains[$idx].anchor.signing-key=${ANCHOR_KEY:-$(anchor_seed "$i")}")
      props+=("-Dyano.app-chain.chains[$idx].anchor.every-blocks=$anchor_every")
    fi
  done
  printf '%s\n' "${props[@]}"
}

# Launch node i in the background. Node 0 is the devnet block producer; the
# rest follow it (or, with --network <net>, every node relays that network).
launch_node() {
  local n="$1" i="$2"
  local dir; dir="$(node_dir "$i")"; mkdir -p "$dir"
  local -a args=(
    "-Dquarkus.profile=${PROFILE}"
    "-Dquarkus.http.port=$(http_port "$i")"
    "-Dyano.server.port=$(server_port "$i")"
    "-Dyano.storage.path=$dir/chainstate"
    # Relay source-port reuse binds every upstream dial to the node's own server
    # port — a NAT-traversal aid for real relays, but on a localhost cluster all
    # followers dialing node 0 (plus app-peers) collide on the 4-tuple and wedge
    # L1 sync. Off for cluster nodes; real relay deployments keep the default.
    "-Dyano.relay.connection.source-port-reuse=false"
    # Every cluster node shares 127.0.0.1, and each pair holds several sockets
    # (L1 chain-sync + app-peer gossip + catch-up). The default per-IP cap (5)
    # is a real-deployment guard (distinct IPs) that a localhost cluster blows
    # past around the 3rd node — node 0 then drops later followers. Raise it.
    "-Dyano.relay.connection.max-connections-per-ip=500"
  )
  if [ "$NETWORK" = "devnet" ]; then
    args+=("-Dyano.genesis.shelley-genesis-file=$dir/shelley-genesis.json")
    if [ "$i" -ne 0 ]; then
      # Follower: no block production, sync L1 from node 0's server.
      args+=("-Dyano.block-producer.enabled=false" "-Dyano.dev-mode=false"
             "-Dyano.client.enabled=true"
             "-Dyano.remote.host=localhost" "-Dyano.remote.port=$(server_port 0)")
    fi
  fi
  # App-chain multi-chain wiring (per node).
  local -a cprops=(); while IFS= read -r p; do cprops+=("$p"); done < <(chain_props "$n" "$i")
  args+=("${cprops[@]}")

  local log; log="$(log_file "$i")"
  if [ "$RUNTIME" = "native" ]; then
    ( cd "$YANO_HOME" && exec "$NATIVE" "${args[@]}" ${YANO_EXTRA_ARGS:-} ) >"$log" 2>&1 &
  else
    ( cd "$YANO_HOME" && exec java ${JAVA_OPTS:-} "${args[@]}" -jar "$JAR" ${YANO_EXTRA_ARGS:-} ) >"$log" 2>&1 &
  fi
  echo "$!" > "$(pid_file "$i")"
}

wait_ready() {
  local i="$1" port; port="$(http_port "$i")"
  local deadline=$(( $(date +%s) + 180 ))
  until curl -sf "http://localhost:$port/q/health/ready" >/dev/null 2>&1; do
    [ "$(date +%s)" -gt "$deadline" ] && die "node $i not ready within 180s (see $(log_file "$i"))"
    if ! kill -0 "$(cat "$(pid_file "$i")" 2>/dev/null)" 2>/dev/null; then
      die "node $i exited during startup (see $(log_file "$i"))"
    fi
    sleep 2
  done
}

# Non-fatal readiness poll: returns 0 when ready, 1 on timeout/exit. $2=timeout(s).
wait_ready_soft() {
  local i="$1" secs="${2:-90}" port; port="$(http_port "$i")"
  local deadline=$(( $(date +%s) + secs ))
  until curl -sf "http://localhost:$port/q/health/ready" >/dev/null 2>&1; do
    [ "$(date +%s)" -gt "$deadline" ] && return 1
    kill -0 "$(cat "$(pid_file "$i")" 2>/dev/null)" 2>/dev/null || return 1
    sleep 2
  done
  return 0
}

# Start a follower, tolerating the known devnet first-boot chain-sync wedge
# (a follower that connects during the producer's earliest blocks can hang
# short of readiness). Restart it by PID up to $CLUSTER_FOLLOWER_TRIES times.
start_follower_resilient() {
  local n="$1" i="$2" tries="${CLUSTER_FOLLOWER_TRIES:-3}" a=1
  while [ "$a" -le "$tries" ]; do
    launch_node "$n" "$i"
    if wait_ready_soft "$i" 90; then return 0; fi
    [ "$a" -lt "$tries" ] && printf '(retry %d) ' "$a"
    kill -9 "$(cat "$(pid_file "$i")" 2>/dev/null)" 2>/dev/null
    sleep 2
    a=$(( a + 1 ))
  done
  die "node $i not ready after $tries attempts (see $(log_file "$i"))"
}

# --- Commands ----------------------------------------------------------------
cmd_start() {
  local n="${1:-3}"
  [[ "$n" =~ ^[0-9]+$ && "$n" -ge 1 ]] || die "node count must be a positive integer"
  resolve_runtime
  [ -f "$CONFIG_FILE" ] || die "config not found: $CONFIG_FILE (set YANO_HOME to a tree containing config/application-appchain.yml)"
  local -a cids=(); while IFS= read -r c; do cids+=("$c"); done < <(chain_ids)
  [ "${#cids[@]}" -ge 1 ] || die "no chains defined in $CONFIG_FILE"

  PROFILE="appchain"
  [ "$NETWORK" = "devnet" ] && PROFILE="devnet,appchain" || PROFILE="${NETWORK},appchain"

  if [ -f "$(pid_file 0)" ] && kill -0 "$(cat "$(pid_file 0)" 2>/dev/null)" 2>/dev/null; then
    die "a cluster is already running (./cluster.sh stop first)"
  fi
  if [ "$ENABLE_ANCHOR" = "1" ] && [ -n "$ANCHOR_KEY" ]; then
    [[ "$ANCHOR_KEY" =~ ^[0-9a-fA-F]{64}$ ]] \
      || die "--anchor-key must be a 32-byte Ed25519 seed as 64 hex chars"
  fi
  # The demo anchor seed is PUBLIC (checked into the repo) — on a public
  # network anyone could sweep its address. Require a user-supplied key there.
  if [ "$ENABLE_ANCHOR" = "1" ] && [ "$NETWORK" != "devnet" ] && [ -z "$ANCHOR_KEY" ]; then
    die "anchoring on $NETWORK needs your own wallet key: --anchor-key \$(openssl rand -hex 32) — the default demo seed is publicly known"
  fi
  mkdir -p "$CLUSTER_DIR"
  save_cluster_env

  c_grn "Starting $n-node app-chain cluster"
  echo  "  runtime : $RUNTIME ($([ "$RUNTIME" = native ] && echo "$NATIVE" || echo "$JAR"))"
  echo  "  home    : $YANO_HOME"
  echo  "  network : $NETWORK"
  echo  "  chains  : ${cids[*]}"
  echo  "  members : $n   threshold: ${THRESHOLD:-$(default_threshold "$n")}"
  [ "$ENABLE_ANCHOR" = "1" ] && echo "  anchor  : $ANCHOR_MODE mode (leader: node 0)"
  echo  "  data    : $CLUSTER_DIR"
  echo

  if [ "$NETWORK" = "devnet" ]; then
    # Node 0 (dev-mode) shifts + persists the genesis systemStart in place;
    # followers must reuse node 0's shifted copy, so we copy AFTER it is ready.
    mkdir -p "$(node_dir 0)"
    jq '.epochLength = 500' "$YANO_HOME/config/network/devnet/shelley-genesis.json" \
        > "$(node_dir 0)/shelley-genesis.json" 2>/dev/null \
        || cp "$YANO_HOME/config/network/devnet/shelley-genesis.json" "$(node_dir 0)/shelley-genesis.json"
  fi

  printf 'node 0 (%s) ... ' "$([ "$NETWORK" = devnet ] && echo 'L1 producer + member' || echo 'relay + member')"
  launch_node "$n" 0; wait_ready 0; c_grn "ready (http $(http_port 0), n2n $(server_port 0))"

  # Let the producer build a few blocks before followers connect — avoids the
  # devnet first-boot chain-sync wedge (a follower joining during node 0's
  # earliest blocks can hang). Skipped for relay networks and single-node.
  if [ "$NETWORK" = "devnet" ] && [ "$n" -gt 1 ]; then
    local warmup="${CLUSTER_WARMUP:-25}"
    printf 'warming up producer %ss before followers join ... ' "$warmup"
    sleep "$warmup"; c_grn "go"
  fi

  local i
  for ((i=1;i<n;i++)); do
    if [ "$NETWORK" = "devnet" ]; then
      mkdir -p "$(node_dir "$i")"
      cp "$(node_dir 0)/shelley-genesis.json" "$(node_dir "$i")/shelley-genesis.json"
    fi
    printf 'node %d (follower + member) ... ' "$i"
    start_follower_resilient "$n" "$i"; c_grn "ready (http $(http_port "$i"), n2n $(server_port "$i"))"
  done

  echo; c_grn "Cluster up."
  echo "  status : $0 status"
  echo "  submit : $0 submit ${cids[0]} <topic> <payload>"
  echo "  logs   : $0 logs <node>"
  echo "  stop   : $0 stop        (clean = stop + wipe data)"

  if [ "$ENABLE_ANCHOR" = "1" ]; then
    echo
    local aaddr
    aaddr="$(anchor_wallet_addr "${cids[0]}")"
    c_ylw "L1 anchoring ($ANCHOR_MODE mode, node 0):"
    [ -n "$aaddr" ] && echo "  anchor wallet : $aaddr" \
      || echo "  anchor wallet : (see node 0 log: 'anchoring enabled')"
    if [ "$NETWORK" = "devnet" ]; then
      if [ "$ANCHOR_MODE" = "script" ]; then
        echo "  next          : $0 anchor-bootstrap <chain>   (auto-funds via devnet faucet)"
      else
        echo "  next          : fund it via the faucet, then anchors start automatically:"
        echo "                  curl -s -X POST http://localhost:$(http_port 0)/api/v1/devnet/fund \\"
        echo "                    -H 'Content-Type: application/json' -d '{\"address\":\"$aaddr\",\"ada\":500}'"
      fi
    else
      echo "  next          : send funds (tADA/ADA) to the anchor wallet address above"
      if [ "$ANCHOR_MODE" = "script" ]; then
        echo "                  then: $0 anchor-bootstrap <chain>   (one-time, per chain)"
      else
        echo "                  anchors then start automatically (no bootstrap in metadata mode)"
      fi
    fi
  fi
}

running_node_count() {
  local n=0 i=0
  while [ -f "$(pid_file "$i")" ]; do
    kill -0 "$(cat "$(pid_file "$i")" 2>/dev/null)" 2>/dev/null && n=$((n+1))
    i=$((i+1))
  done
  echo "$n"
}

cmd_status() {
  [ -d "$CLUSTER_DIR" ] || die "no cluster (start one first)"
  local i=0 any=0
  declare -a roots=()
  while [ -f "$(pid_file "$i")" ]; do
    local port up="down"; port="$(http_port "$i")"
    kill -0 "$(cat "$(pid_file "$i")" 2>/dev/null)" 2>/dev/null && up="up"
    if [ "$up" = "up" ] && curl -sf "http://localhost:$port/q/health/ready" >/dev/null 2>&1; then
      any=1
      local chains; chains="$(curl -s "http://localhost:$port/api/v1/app-chain/chains" 2>/dev/null)"
      printf 'node %d  [ready]  http %s  n2n %s\n' "$i" "$port" "$(server_port "$i")"
      printf '%s' "$chains" | jq -r '.[] | "    \(.chainId)  tip=\(.tipHeight)  root=\(.stateRoot[0:16])..."' 2>/dev/null \
        || echo "    (chains unavailable)"
    else
      printf 'node %d  [%s]  http %s\n' "$i" "starting/down" "$port"
    fi
    i=$((i+1))
  done
  [ "$any" = "1" ] || c_ylw "no nodes are ready yet"
  # Cross-node root agreement per chain
  echo; echo "Consistency (per chain, roots must match across nodes):"
  local cid
  for cid in $(chain_ids); do
    local seen="" mism=0 j=0
    while [ -f "$(pid_file "$j")" ]; do
      local r; r="$(curl -s "http://localhost:$(http_port "$j")/api/v1/app-chain/chains" 2>/dev/null \
        | jq -r --arg c "$cid" '.[]|select(.chainId==$c)|.stateRoot' 2>/dev/null)"
      [ -n "$r" ] || { j=$((j+1)); continue; }
      [ -z "$seen" ] && seen="$r"
      [ "$r" != "$seen" ] && mism=1
      j=$((j+1))
    done
    if [ "$mism" = "0" ] && [ -n "$seen" ]; then c_grn "  $cid: AGREED (${seen:0:16}...)"; else c_red "  $cid: MISMATCH"; fi
  done
}

cmd_submit() {
  local cid="${1:-}" topic="${2:-}" payload="${3:-}"; shift 3 2>/dev/null || true
  [ -n "$cid" ] && [ -n "$topic" ] && [ -n "$payload" ] || die "usage: $0 submit <chain-id> <topic> <payload> [--node i] [--count n]"
  local node=0 count=1
  while [ $# -gt 0 ]; do case "$1" in
    --node) node="$2"; shift 2;; --count) count="$2"; shift 2;; *) die "unknown submit option: $1";; esac; done
  local port; port="$(http_port "$node")"
  curl -sf "http://localhost:$port/q/health/ready" >/dev/null 2>&1 || die "node $node not ready"
  local k
  for ((k=1;k<=count;k++)); do
    local body="$payload"; [ "$count" -gt 1 ] && body="$payload-$k"
    local json; json="$(jq -nc --arg t "$topic" --arg b "$body" '{topic:$t,body:$b}')"
    local resp; resp="$(curl -s -X POST "http://localhost:$port/api/v1/app-chain/chains/$cid/messages" \
      -H 'Content-Type: application/json' -d "$json")"
    local mid; mid="$(printf '%s' "$resp" | jq -r '.messageId // empty' 2>/dev/null)"
    if [ -n "$mid" ]; then echo "submitted ${mid:0:16}... to $cid"; else c_red "submit failed: $resp"; return 1; fi
  done
}

cmd_kv() {
  local cid="${1:-}" op="${2:-}" key="${3:-}" value="${4:-}"; shift 4 2>/dev/null || shift $# 2>/dev/null
  [ -n "$cid" ] && [ -n "$op" ] && [ -n "$key" ] || die "usage: $0 kv <chain-id> set <key> <value> | del <key>  [--node i]"
  local node=0
  while [ $# -gt 0 ]; do case "$1" in --node) node="$2"; shift 2;; *) shift;; esac; done
  local opcode hex
  case "$op" in
    set|put|PUT) [ -n "$value" ] || die "kv set needs a value"; opcode=0; hex="$(kv_cbor 0 "$key" "$value")";;
    del|delete|DEL) opcode=1; hex="$(kv_cbor 1 "$key" "")";;
    *) die "kv op must be 'set' or 'del'";;
  esac
  local port; port="$(http_port "$node")"
  curl -sf "http://localhost:$port/q/health/ready" >/dev/null 2>&1 || die "node $node not ready"
  local json; json="$(jq -nc --arg t kv --arg h "$hex" '{topic:$t,bodyHex:$h}')"
  local resp; resp="$(curl -s -X POST "http://localhost:$port/api/v1/app-chain/chains/$cid/messages" \
    -H 'Content-Type: application/json' -d "$json")"
  local mid; mid="$(printf '%s' "$resp" | jq -r '.messageId // empty' 2>/dev/null)"
  if [ -n "$mid" ]; then echo "kv $op $key -> $cid  (${mid:0:16}...)"; else c_red "kv failed: $resp"; return 1; fi
}

cmd_anchor_bootstrap() {
  local cid="${1:-}"; [ -n "$cid" ] || die "usage: $0 anchor-bootstrap <chain-id>"
  load_cluster_env
  [ "$ANCHOR_MODE" = "script" ] || die "cluster runs anchor mode '$ANCHOR_MODE' — bootstrap only applies to script anchors (metadata mode needs none: fund the wallet and anchors start automatically)"
  local port; port="$(http_port 0)"
  # Learn the wallet address; on devnet fund it from the faucet first — on a
  # public network the wallet must already hold funds (sent externally).
  local addr; addr="$(anchor_wallet_addr "$cid")"
  [ -n "$addr" ] || die "no anchor wallet on '$cid' (start with --anchor / --anchor-mode script)"
  if [ "$NETWORK" = "devnet" ]; then
    c_ylw "Funding anchor wallet via devnet faucet + bootstrapping the script anchor for '$cid'..."
    curl -s -X POST "http://localhost:$port/api/v1/devnet/fund" -H 'Content-Type: application/json' \
      -d "{\"address\":\"$addr\",\"ada\":500}" >/dev/null
    sleep 6
  else
    c_ylw "Bootstrapping the script anchor for '$cid' on $NETWORK..."
    echo "(anchor wallet $addr must already be funded — if bootstrap fails with"
    echo " 'No usable UTxO', send tADA/ADA there and re-run; a just-sent tx may"
    echo " also need a minute to land in the node's UTxO view)"
  fi
  curl -s -X POST "http://localhost:$port/api/v1/app-chain/chains/$cid/admin/anchor/bootstrap" | python3 -m json.tool 2>/dev/null \
    || die "bootstrap failed"
}

cmd_logs() {
  local i="${1:-0}" follow=""; [ "${2:-}" = "-f" ] && follow="-f"
  local f; f="$(log_file "$i")"; [ -f "$f" ] || die "no log for node $i"
  tail $follow -n 60 "$f"
}

cmd_keys() {
  local n="${1:-3}" i
  echo "node  seed(hex, 32 bytes)                                              member-pubkey"
  for ((i=0;i<n;i++)); do printf '%-4d  %s  %s\n' "$i" "$(node_seed "$i")" "$(node_pub "$i")"; done
}

cmd_chains() {
  [ -f "$CONFIG_FILE" ] || die "config not found: $CONFIG_FILE"
  echo "Chains defined in $CONFIG_FILE:"; chain_ids | sed 's/^/  - /'
}

cmd_stop() {
  local wipe="${1:-}"
  local i=0 killed=0
  while [ -f "$(pid_file "$i")" ]; do
    local pid; pid="$(cat "$(pid_file "$i")" 2>/dev/null)"
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then kill "$pid" 2>/dev/null && killed=$((killed+1)); fi
    rm -f "$(pid_file "$i")"; i=$((i+1))
  done
  # Give them a moment, then hard-kill stragglers on the cluster ports.
  sleep 2
  local p; for ((p=0;p<i;p++)); do
    lsof -ti:"$(http_port "$p")" -sTCP:LISTEN 2>/dev/null | xargs kill -9 2>/dev/null || true
  done
  c_grn "stopped $killed node(s)"
  if [ "$wipe" = "wipe" ]; then rm -rf "$CLUSTER_DIR"; c_grn "wiped $CLUSTER_DIR"; fi
}

usage() {
  cat <<EOF
Yano app-chain cluster launcher

Usage:
  $0 start [N] [options]        start an N-node cluster (default N=3)
  $0 status                     health + per-chain tips/roots + consistency
  $0 submit <chain> <topic> <payload> [--node i] [--count n]
  $0 kv <chain> set <key> <value> [--node i]   put into a kv-registry chain
  $0 kv <chain> del <key> [--node i]           delete from a kv-registry chain
  $0 loadtest <chain> [-n M] [-c C] [--spread]  parallel throughput test
  $0 anchor-bootstrap <chain>   bootstrap a script anchor (one-time; devnet auto-funds)
  $0 logs <node> [-f]           show/tail a node log
  $0 keys [N]                   print derived member seeds + pubkeys
  $0 chains                     list chains from the config
  $0 stop                       stop nodes (keep data)
  $0 clean                      stop nodes + wipe data
  $0 help

start options:
  --network <net>    devnet (default) | preprod | preview | mainnet | sanchonet
                     devnet: node 0 produces L1, others follow. A public network:
                     every node relays that network (needs sync; see README).
  --jar | --native   force runtime (default: auto-detect the available build)
  --threshold <t>    finality threshold (default: majority = N/2 + 1)
  --anchor           enable L1 anchoring on every chain, script mode (node 0
                     is the anchor leader; followers need no anchor config)
  --anchor-mode <m>  metadata | script (implies --anchor).
                     metadata: plain tx with the anchor in tx metadata — just
                       fund the wallet, no bootstrap, works on any network.
                     script: Plutus V3 thread-NFT + threshold co-signed
                       advances — one-time 'anchor-bootstrap <chain>' per chain.
  --anchor-key <hex> anchor wallet key (32-byte Ed25519 seed, 64 hex chars).
                     Default: a deterministic demo seed. On a public network
                     pass your own and fund the enterprise address the node
                     prints (a CIP-1852 wallet mnemonic can NOT be converted
                     to this seed — generate one: openssl rand -hex 32).
  --anchor-every <n> anchor cadence in app blocks (default: 2 devnet, 30 public)
  --data-dir <dir>   cluster data/logs dir (default: $CLUSTER_DIR)
  --http-base <p>    node i HTTP port = p + i (default: $HTTP_BASE)
  --server-base <p>  node i n2n  port = p + i (default: $SERVER_BASE)

Chains come from \$YANO_HOME/config/application-appchain.yml — edit it to add/
remove app chains or change their state machine / sequencer. See ./README.md.

Environment (run a RELEASED build with no local compile):
  YANO_HOME     tree holding config/ (application-appchain.yml + network
                genesis). Nodes launch with cwd=HOME. Default: the repo's app/.
  YANO_JAR      path to a yano uber-jar (overrides auto-detect; any location).
  YANO_NATIVE   path to a yano native binary (overrides auto-detect).
  Examples:
    # local dev (auto): uses app/build/yano.jar + app/config
    ./cluster.sh start 3
    # released tree: /opt/yano/{yano.jar, config/...}
    YANO_HOME=/opt/yano ./cluster.sh start 3
    # binary and config in different places
    YANO_HOME=/data/yano YANO_JAR=/downloads/yano.jar ./cluster.sh start 3
  (loadtest.sh / soaktest.sh need no binary — they just hit the running
  cluster's HTTP ports, so they work against any running Yano.)
EOF
}

# --- Arg parsing -------------------------------------------------------------
[ $# -ge 1 ] || { usage; exit 1; }
CMD="$1"; shift || true

# Global options may appear after the subcommand's positionals; pull them out.
POS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --network)      NETWORK="$2"; NETWORK_EXPLICIT=1; shift 2;;
    --jar)          RUNTIME="jar"; shift;;
    --native)       RUNTIME="native"; shift;;
    --threshold)    THRESHOLD="$2"; shift 2;;
    --anchor)       ENABLE_ANCHOR=1; shift;;
    --anchor-mode)  ANCHOR_MODE="$2"; ENABLE_ANCHOR=1; shift 2
                    case "$ANCHOR_MODE" in metadata|script) ;; *) die "--anchor-mode must be metadata or script";; esac;;
    --anchor-key)   ANCHOR_KEY="$2"; ENABLE_ANCHOR=1; shift 2;;
    --anchor-every) ANCHOR_EVERY="$2"; shift 2;;
    --data-dir)     CLUSTER_DIR="$2"; shift 2;;
    --http-base)    HTTP_BASE="$2"; shift 2;;
    --server-base)  SERVER_BASE="$2"; shift 2;;
    *)              POS+=("$1"); shift;;
  esac
done
set -- "${POS[@]:-}"

case "$CMD" in
  start)             cmd_start "${1:-3}";;
  status)            cmd_status;;
  submit)            cmd_submit "$@";;
  kv)                cmd_kv "$@";;
  loadtest)          exec "$SCRIPT_DIR/loadtest.sh" "$@";;
  anchor-bootstrap)  cmd_anchor_bootstrap "${1:-}";;
  logs)              cmd_logs "${1:-0}" "${2:-}";;
  keys)              cmd_keys "${1:-3}";;
  chains)            cmd_chains;;
  stop)              cmd_stop;;
  clean)             cmd_stop wipe;;
  help|-h|--help)    usage;;
  *)                 usage; exit 1;;
esac
