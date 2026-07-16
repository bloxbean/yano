#!/usr/bin/env bash
# Focused regression for operator-provisioned member/anchor keys.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CLUSTER="$SCRIPT_DIR/cluster.sh"
WORK="$(mktemp -d /tmp/yano-private-key-test.XXXXXX)"

die_test() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT INT TERM

# Load launcher functions without executing its command parser.
# shellcheck disable=SC1090
source /dev/stdin <<< "$(sed '/^# --- Arg parsing/,$d' "$CLUSTER")"

DEMO_SEED_0="$(repeat_byte 01)"
DEMO_PUB_0="${PUBKEYS[0]}"
DEMO_ANCHOR_0="$(repeat_byte 30)"

# Unset inputs preserve the launcher identities byte-for-byte.
MEMBER_KEY_DIR=""
ANCHOR_KEY_FILE=""
ANCHOR_KEY=""
validate_cluster_key_inputs 2
[ "$(node_seed 0)" = "$DEMO_SEED_0" ] || die_test "unset member input changed the demo seed"
[ "$(node_pub 0)" = "$DEMO_PUB_0" ] || die_test "unset member input changed the demo public key"
[ "$(anchor_signing_seed 0)" = "$DEMO_ANCHOR_0" ] \
  || die_test "unset anchor input changed the demo anchor seed"

KEYS="$WORK/member-keys"
mkdir -m 700 "$KEYS"
SEED_0="$(repeat_byte 04)"
SEED_1="$(repeat_byte 05)"
PUB_0="${PUBKEYS[3]}"
PUB_1="${PUBKEYS[4]}"
printf '%s\n' "$SEED_0" > "$KEYS/node0.seed"
printf '%s\n' "$PUB_0" > "$KEYS/node0.public"
printf '%s\n' "$SEED_1" > "$KEYS/node1.seed"
printf '%s\n' "$PUB_1" > "$KEYS/node1.public"
chmod 600 "$KEYS"/node0.seed "$KEYS"/node0.public \
  "$KEYS"/node1.seed "$KEYS"/node1.public

MEMBER_KEY_DIR="$KEYS"
validate_member_key_inputs 2
[ "$(node_seed 0)" = "$SEED_0" ] || die_test "node 0 did not use its supplied seed"
[ "$(node_seed 1)" = "$SEED_1" ] || die_test "node 1 did not use its supplied seed"
[ "$(node_pub 0)" = "$PUB_0" ] || die_test "node 0 did not use its supplied public key"
[ "$(node_pub 1)" = "$PUB_1" ] || die_test "node 1 did not use its supplied public key"

# Public identities remain in launcher arguments; supplied private seeds do not.
chain_indices() { printf '0\n'; }
NETWORK="preprod"
ENABLE_ANCHOR=0
THRESHOLD=""
SERVER_BASE=23337
PROPS="$WORK/member.properties"
chain_props 2 1 > "$PROPS"
! grep -q "$SEED_1" "$PROPS" || die_test "chain properties exposed the supplied member seed"
grep -Fqx -- "-Dyano.app-chain.chains[0].members=$PUB_0,$PUB_1" "$PROPS" \
  || die_test "chain properties omitted the supplied membership"
grep -Fqx -- "-Dyano.app-chain.chains[0].sequencer.proposer=$PUB_0" "$PROPS" \
  || die_test "chain properties omitted the supplied proposer"

# An anchor file may coexist with private support material in the member key
# directory and replaces the CLI seed without enabling anchoring by itself.
ANCHOR_SEED="$(repeat_byte c1)"
printf '%s\n' "$ANCHOR_SEED" > "$KEYS/anchor.seed"
chmod 400 "$KEYS/anchor.seed"
ANCHOR_KEY_FILE="$KEYS/anchor.seed"
ANCHOR_KEY=""
validate_anchor_key_input
[ "$ANCHOR_KEY_FILE_VALUE" = "$ANCHOR_SEED" ] || die_test "anchor seed was not cached"
[ "$(anchor_signing_seed 0)" = "$ANCHOR_SEED" ] || die_test "anchor file seed was not selected"
ENABLE_ANCHOR=1
chain_props 2 0 > "$WORK/anchor.properties"
! grep -q "$ANCHOR_SEED" "$WORK/anchor.properties" \
  || die_test "chain properties exposed the supplied anchor seed"

# Running `keys` in a fresh launcher process remains demo-only even when the
# production directory variable is present, preventing accidental disclosure.
YANO_CLUSTER_MEMBER_KEY_DIR="$KEYS" "$CLUSTER" keys 2 > "$WORK/printed-keys"
grep -q "$DEMO_SEED_0" "$WORK/printed-keys" || die_test "keys command stopped printing demo identities"
! grep -q "$SEED_0" "$WORK/printed-keys" || die_test "keys command disclosed a supplied member seed"

# Generated secret overlays live in an explicit owner-only secret directory,
# override the ordinal-275 operator overlay, and are referenced only by URI.
CLUSTER_DIR="$WORK/default-launch-data"
mkdir -m 700 "$CLUSTER_DIR"
PRIVATE_CONFIG_DIR_REQUESTED=""
prepare_private_configs 2
[ "$PRIVATE_CONFIG_DIR" = "$(canonical_path "$CLUSTER_DIR/private-config")" ] \
  || die_test "standalone private-config default moved outside cluster data"

# A normal shell umask creates an existing cluster root as 0755. The generated
# private subdirectory must still be created securely instead of requiring its
# non-writable parent to be owner-only.
CLUSTER_DIR="$WORK/default-umask-launch-data"
(umask 022; mkdir "$CLUSTER_DIR")
PRIVATE_CONFIG_DIR_REQUESTED=""
prepare_private_configs 2
[ "$(posix_mode "$PRIVATE_CONFIG_DIR")" = 700 ] \
  || die_test "default private-config directory was not secured under umask 022"

CLUSTER_DIR="$WORK/launch-data"
mkdir -m 700 "$CLUSTER_DIR"
PRIVATE_CONFIG_DIR_REQUESTED="$WORK/generated-private-config"
prepare_private_configs 2
[ "$PRIVATE_CONFIG_DIR" = "$(canonical_path "$PRIVATE_CONFIG_DIR_REQUESTED")" ] \
  || die_test "explicit private-config directory was not selected"
[ ! -e "$CLUSTER_DIR/private-config" ] \
  || die_test "explicit private-config directory leaked into cluster data"
if ( PRIVATE_CONFIG_DIR_REQUESTED="$CLUSTER_DIR/forbidden-private"; prepare_private_configs 2 ) \
    > "$WORK/private-config-inside.log" 2>&1; then
  die_test "explicit private-config directory inside cluster data was accepted"
fi
grep -q 'must be outside the cluster data directory' "$WORK/private-config-inside.log" \
  || die_test "inside-cluster private-config rejection produced the wrong diagnostic"
for i in 0 1; do
  PRIVATE_FILE="$PRIVATE_CONFIG_DIR/node$i-private.properties"
  [ -f "$PRIVATE_FILE" ] && [ ! -L "$PRIVATE_FILE" ] \
    || die_test "node $i private config was not a regular file"
  [ "$(posix_mode "$PRIVATE_FILE")" = 600 ] || die_test "node $i private config was not chmod 600"
  grep -Fqx "config_ordinal=350" "$PRIVATE_FILE" || die_test "private config has wrong ordinal"
  grep -Fqx "yano.app-chain.chains[0].signing-key=${MEMBER_SEEDS[$i]}" "$PRIVATE_FILE" \
    || die_test "node $i private config omitted its member seed"
done
grep -Fqx "yano.app-chain.chains[0].anchor.signing-key=$ANCHOR_SEED" \
  "$PRIVATE_CONFIG_DIR/node0-private.properties" \
  || die_test "node 0 private config omitted the anchor seed"
! grep -q 'anchor.signing-key' "$PRIVATE_CONFIG_DIR/node1-private.properties" \
  || die_test "follower private config received the leader anchor seed"

OVERLAYS="$WORK/operator-overlays"
mkdir -m 700 "$OVERLAYS"
for i in 0 1; do
  printf 'config_ordinal=275\noperator.test=node%s\n' "$i" > "$OVERLAYS/node$i.properties"
  chmod 600 "$OVERLAYS/node$i.properties"
done
NODE_CONFIG_DIR="$OVERLAYS"
validate_node_config_overlays 2

# Exercise both native and JVM launch shapes with capture-only processes. The
# captured ps-style argument vector must not contain any supplied private seed.
FAKE_BIN="$WORK/fake-bin"
mkdir -m 700 "$FAKE_BIN"
printf '%s\n' '#!/bin/sh' \
  'printf "%s" "${QUARKUS_CONFIG_LOCATIONS-}" > "$CAPTURE_LOCATION_FILE"' \
  ': > "$CAPTURE_FILE"' \
  'for arg in "$@"; do printf "%s\\n" "$arg" >> "$CAPTURE_FILE"; done' \
  'sleep 1' > "$FAKE_BIN/capture"
chmod 700 "$FAKE_BIN/capture"
cp "$FAKE_BIN/capture" "$FAKE_BIN/java"
install -m 600 /dev/null "$WORK/yano.jar"
PROFILE="private-key-test"
TRANSPORT=""
HTTP_BASE=29070
SERVER_BASE=29337
YANO_HOME="$WORK"

assert_private_launch_capture() {
  local i="$1" expected_operator expected_private expected_locations pid attempt=0
  while { [ ! -f "$CAPTURE_FILE" ] || [ ! -f "$CAPTURE_LOCATION_FILE" ]; } \
      && [ "$attempt" -lt 50 ]; do
    sleep 0.02
    attempt=$((attempt + 1))
  done
  [ -f "$CAPTURE_FILE" ] && [ -f "$CAPTURE_LOCATION_FILE" ] \
    || die_test "node $i capture process did not record its launch"
  expected_operator="$(node_config_location "$i")"
  expected_private="${PRIVATE_CONFIG_URIS[$i]}"
  expected_locations="$expected_operator,$expected_private"
  [ "$(cat "$CAPTURE_LOCATION_FILE")" = "$expected_locations" ] \
    || die_test "node $i did not combine operator/private config locations"
  ! grep -Eq "$SEED_0|$SEED_1|$ANCHOR_SEED" "$CAPTURE_FILE" \
    || die_test "node $i process arguments exposed a supplied private seed"
  grep -Fqx -- "-Dyano.app-chain.chains[0].members=$PUB_0,$PUB_1" "$CAPTURE_FILE" \
    || die_test "node $i process arguments omitted public membership"
  pid="$(cat "$(pid_file "$i")")"
  wait "$pid" || die_test "capture-only node $i launch failed"
  remove_pid_record "$i"
}

CAPTURE_FILE="$WORK/native.args"; CAPTURE_LOCATION_FILE="$WORK/native.location"
export CAPTURE_FILE CAPTURE_LOCATION_FILE
NETWORK="devnet"
DEVNET_GENESIS_TIMESTAMP_MILLIS=1767225600000
RUNTIME="native"; NATIVE="$FAKE_BIN/capture"
launch_node 2 0
assert_private_launch_capture 0
grep -Fqx -- "-Dyano.block-producer.genesis-timestamp=$DEVNET_GENESIS_TIMESTAMP_MILLIS" \
  "$CAPTURE_FILE" || die_test "supplied devnet systemStart was not fixed in the launch arguments"

CAPTURE_FILE="$WORK/jar.args"; CAPTURE_LOCATION_FILE="$WORK/jar.location"
export CAPTURE_FILE CAPTURE_LOCATION_FILE
NETWORK="preprod"
DEVNET_GENESIS_TIMESTAMP_MILLIS=""
RUNTIME="jar"; JAR="$WORK/yano.jar"; PATH="$FAKE_BIN:$PATH"
launch_node 2 1
assert_private_launch_capture 1

# Stub all process/network work so cmd_start exercises only preflight ordering
# and property construction. No Yano process or socket is created.
CONFIG_FILE="$WORK/application-appchain.yml"
printf 'yano.app-chain.chains[0].chain-id: "private-key-test"\n' > "$CONFIG_FILE"
resolve_runtime() { RUNTIME="jar"; JAR="$WORK/fake-yano.jar"; }
chain_ids() { printf 'private-key-test\n'; }
resolve_cluster_ports() { :; }
launch_node() { :; }
wait_ready() { :; }
start_follower_resilient() { :; }
anchor_wallet_addr() { :; }
NODE_CONFIG_DIR=""
NODE_CONFIG_DIR_CANON=""
CLUSTER_API_KEY=""
NETWORK="preprod"
ENABLE_ANCHOR=1
ANCHOR_MODE="metadata"
CLUSTER_DIR="$WORK/public-accepted"
cmd_start 2 > "$WORK/public-accepted.log" 2>&1 \
  || die_test "public-network start rejected the private anchor file"
[ -d "$CLUSTER_DIR" ] || die_test "accepted public-network preflight did not reach state creation"
[ -f "$CLUSTER_DIR/cluster-identity.json" ] \
  || die_test "accepted start did not persist a cluster identity marker"
[ -f "$CLUSTER_DIR/cluster-appchain-identity.json" ] \
  || die_test "accepted start did not persist a standalone app-chain identity marker"
[ "$(posix_mode "$CLUSTER_DIR/cluster-identity.json")" = 600 ] \
  || die_test "cluster identity marker was not owner-only"

# Retained state is inseparable from the selected member/anchor profile.
ORIGINAL_ANCHOR_SEED="$ANCHOR_SEED"
chmod 600 "$KEYS/anchor.seed"
printf '%s\n' "$(repeat_byte c2)" > "$KEYS/anchor.seed"
chmod 400 "$KEYS/anchor.seed"
if ( cmd_start 2 ) > "$WORK/retained-anchor-change.log" 2>&1; then
  die_test "retained state accepted a different anchor signer"
fi
grep -q 'app-chain identity differs from retained state' "$WORK/retained-anchor-change.log" \
  || die_test "retained anchor identity change produced the wrong diagnostic"
chmod 600 "$KEYS/anchor.seed"
printf '%s\n' "$ORIGINAL_ANCHOR_SEED" > "$KEYS/anchor.seed"
chmod 400 "$KEYS/anchor.seed"

CLUSTER_DIR="$WORK/unmarked-retained-state"
mkdir -p "$CLUSTER_DIR/node0/chainstate/app-chain/private-key-test"
touch "$CLUSTER_DIR/node0/chainstate/app-chain/private-key-test/CURRENT"
validate_cluster_key_inputs 2
if ( ensure_cluster_identity 2 ) > "$WORK/unmarked-retained.log" 2>&1; then
  die_test "unmarked retained state was silently adopted"
fi
grep -q 'retained app-chain identity state has no identity marker' "$WORK/unmarked-retained.log" \
  || die_test "unmarked retained state produced the wrong diagnostic"

assert_start_rejected_before_state() {
  local name="$1" expected="$2" data="$WORK/rejected-$1" log="$WORK/rejected-$1.log"
  if ( CLUSTER_DIR="$data"; cmd_start 2 ) > "$log" 2>&1; then
    die_test "$name input was accepted"
  fi
  grep -q "$expected" "$log" || die_test "$name rejection produced the wrong diagnostic"
  [ ! -e "$data" ] || die_test "$name validation occurred after state creation"
}

# Files are bounded, hexadecimal, owner-only, regular, non-symlink, and exact.
chmod 640 "$KEYS/node0.seed"
assert_start_rejected_before_state "member-mode" "owner-only and non-executable"
chmod 600 "$KEYS/node0.seed"

cp "$KEYS/node0.public" "$WORK/node0.public.saved"
printf '%064d\n' 0 | sed 's/0/z/g' > "$KEYS/node0.public"
assert_start_rejected_before_state "member-hex" "64-hex-character value"
cp "$WORK/node0.public.saved" "$KEYS/node0.public"
chmod 600 "$KEYS/node0.public"

printf '%s\n' "${PUBKEYS[5]}" > "$KEYS/node0.public"
assert_start_rejected_before_state "member-pair" "public key does not match its seed"
printf '%s\n' "$PUB_0" > "$KEYS/node0.public"
chmod 600 "$KEYS/node0.public"

printf '%s\n' "$SEED_0" > "$KEYS/node1.seed"
printf '%s\n' "$PUB_0" > "$KEYS/node1.public"
assert_start_rejected_before_state "member-duplicate" "public key duplicates node 0"
printf '%s\n' "$SEED_1" > "$KEYS/node1.seed"
printf '%s\n' "$PUB_1" > "$KEYS/node1.public"
chmod 600 "$KEYS/node1.seed" "$KEYS/node1.public"

mv "$KEYS/node1.seed" "$WORK/node1.seed.real"
ln -s "$WORK/node1.seed.real" "$KEYS/node1.seed"
assert_start_rejected_before_state "member-symlink" "must not be a symbolic link"
rm "$KEYS/node1.seed"
mv "$WORK/node1.seed.real" "$KEYS/node1.seed"

install -m 600 /dev/null "$KEYS/node2.seed"
assert_start_rejected_before_state "member-bound" "unexpected member key for node 2"
rm "$KEYS/node2.seed"

install -m 600 /dev/null "$KEYS/node01.seed"
assert_start_rejected_before_state "member-name" "unexpected member key filename"
rm "$KEYS/node01.seed"

chmod 750 "$KEYS"
assert_start_rejected_before_state "member-directory-mode" "directory must be owner-only"
chmod 700 "$KEYS"

chmod 640 "$KEYS/anchor.seed"
assert_start_rejected_before_state "anchor-mode" "owner-only and non-executable"
chmod 400 "$KEYS/anchor.seed"

ANCHOR_KEY="$DEMO_ANCHOR_0"
assert_start_rejected_before_state "anchor-conflict" "conflicts with --anchor-key"
ANCHOR_KEY=""

# Ownership-negative fixtures are portable only when the test runs as root.
if [ "$(id -u)" -eq 0 ]; then
  cp "$KEYS/node0.seed" "$WORK/node0.seed.owner"
  if chown 65534 "$KEYS/node0.seed" 2>/dev/null; then
    assert_start_rejected_before_state "member-owner" "must be owned by the launcher user"
    rm "$KEYS/node0.seed"
    cp "$WORK/node0.seed.owner" "$KEYS/node0.seed"
    chmod 600 "$KEYS/node0.seed"
  fi
fi

# Clearing the secure inputs resets cached material and restores exact defaults.
MEMBER_KEY_DIR=""
ANCHOR_KEY_FILE=""
ANCHOR_KEY=""
validate_cluster_key_inputs 2
[ "$(node_seed 0)" = "$DEMO_SEED_0" ] || die_test "clearing member input retained a supplied seed"
[ "$(node_pub 0)" = "$DEMO_PUB_0" ] || die_test "clearing member input retained a supplied public key"
[ "$(anchor_signing_seed 0)" = "$DEMO_ANCHOR_0" ] \
  || die_test "clearing anchor input retained a supplied seed"

printf 'PASS: private member and anchor key inputs are bounded, exact, and preflighted\n'
