#!/usr/bin/env bash
# Focused regression for strict cluster.env parsing and PID-safe status/stop.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CLUSTER="$SCRIPT_DIR/cluster.sh"
WORK="$(mktemp -d /tmp/yano-cluster-state-test.XXXXXX)"

die_test() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
cleanup() {
  local pid
  for pid in $(jobs -pr); do kill "$pid" 2>/dev/null || true; done
  rm -rf "$WORK"
}
trap cleanup EXIT INT TERM

# Load launcher functions without executing its command parser.
# shellcheck disable=SC1090
source /dev/stdin <<< "$(sed '/^# --- Arg parsing/,$d' "$CLUSTER")"

# cluster.env is an owner-controlled data record, never shell code.
CLUSTER_DIR="$WORK/environment"
mkdir -m 700 "$CLUSTER_DIR"
NETWORK="preprod"
ENABLE_ANCHOR=1
ANCHOR_MODE="metadata"
HTTP_BASE=18070
SERVER_BASE=18337
save_cluster_env
[ "$(posix_mode "$(env_file)")" = 600 ] || die_test "cluster.env was not chmod 600"

NETWORK="devnet"; ENABLE_ANCHOR=0; ANCHOR_MODE="script"; HTTP_BASE=1; SERVER_BASE=2
NETWORK_EXPLICIT=0; HTTP_BASE_EXPLICIT=0; SERVER_BASE_EXPLICIT=0
load_cluster_env
[ "$NETWORK" = preprod ] && [ "$ENABLE_ANCHOR" = 1 ] && [ "$ANCHOR_MODE" = metadata ] \
  || die_test "strict cluster.env parser did not restore network/anchor values"
[ "$HTTP_BASE" = 18070 ] && [ "$SERVER_BASE" = 18337 ] \
  || die_test "strict cluster.env parser did not restore ports"

NETWORK="mainnet"; HTTP_BASE=28070; SERVER_BASE=28337
NETWORK_EXPLICIT=1; HTTP_BASE_EXPLICIT=1; SERVER_BASE_EXPLICIT=1
load_cluster_env
[ "$NETWORK" = mainnet ] && [ "$HTTP_BASE" = 28070 ] && [ "$SERVER_BASE" = 28337 ] \
  || die_test "explicit invocation values did not override cluster.env"
NETWORK_EXPLICIT=0; HTTP_BASE_EXPLICIT=0; SERVER_BASE_EXPLICIT=0

VALID_ENV="$WORK/cluster.env.valid"
cp "$(env_file)" "$VALID_ENV"
chmod 600 "$VALID_ENV"

assert_env_rejected() {
  local name="$1" expected="$2" log="$WORK/env-$1.log"
  if ( load_cluster_env ) > "$log" 2>&1; then die_test "$name cluster.env was accepted"; fi
  grep -q "$expected" "$log" || die_test "$name cluster.env produced the wrong diagnostic"
}

SENTINEL="$WORK/must-not-execute"
{ printf 'NETWORK=$(touch %s)\n' "$SENTINEL"
  printf '%s\n' 'ENABLE_ANCHOR=1' 'ANCHOR_MODE=metadata' 'HTTP_BASE=18070' 'SERVER_BASE=18337'; } \
  > "$(env_file)"
chmod 600 "$(env_file)"
assert_env_rejected "injection" "invalid line"
[ ! -e "$SENTINEL" ] || die_test "cluster.env content executed as shell code"

printf '%s\n' \
  'NETWORK=preprod' 'NETWORK=preview' 'ENABLE_ANCHOR=1' 'ANCHOR_MODE=metadata' \
  'HTTP_BASE=18070' 'SERVER_BASE=18337' > "$(env_file)"
assert_env_rejected "duplicate" "duplicate NETWORK"

printf '%s\n' \
  'NETWORK=preprod' 'ENABLE_ANCHOR=1' 'ANCHOR_MODE=metadata' \
  'HTTP_BASE=18070' 'UNEXPECTED=value' > "$(env_file)"
assert_env_rejected "unknown" "unknown key"

cp "$VALID_ENV" "$(env_file)"
chmod 666 "$(env_file)"
assert_env_rejected "mode" "must not be group or world writable"

rm "$(env_file)"
ln -s "$VALID_ENV" "$(env_file)"
assert_env_rejected "symlink" "regular non-symlink"
rm "$(env_file)"
cp "$VALID_ENV" "$(env_file)"
chmod 600 "$(env_file)"

# A supplied devnet genesis is validated without creating cluster state and is
# then installed byte-for-byte. Its systemStart is retained as an explicit
# runtime value, and any later byte-level identity change fails closed.
GENESIS_PARENT="$WORK/shared-genesis"
GENESIS_SOURCE="$GENESIS_PARENT/shelley-genesis.json"
mkdir -m 700 "$GENESIS_PARENT"
printf '%s\n' '{' '  "systemStart": "2026-01-01T00:00:00Z",' \
  '  "networkMagic": 42,' '  "epochLength": 500' '}' > "$GENESIS_SOURCE"
chmod 600 "$GENESIS_SOURCE"
CLUSTER_DIR="$WORK/genesis-cluster"
NETWORK=devnet
DEVNET_GENESIS_FILE="$GENESIS_SOURCE"
validate_devnet_genesis_input
[ ! -e "$CLUSTER_DIR" ] || die_test "genesis validation created cluster state"
[ "$DEVNET_GENESIS_TIMESTAMP_MILLIS" = 1767225600000 ] \
  || die_test "supplied devnet systemStart was not converted to exact epoch millis"
prepare_devnet_node0_genesis
cmp -s "$GENESIS_SOURCE" "$(node_dir 0)/shelley-genesis.json" \
  || die_test "supplied devnet genesis was not copied byte-for-byte"
printf '%s\n' '{"runtime":"retained identity"}' > "$(node_dir 0)/shelley-genesis.json"
if ( prepare_devnet_node0_genesis ) > "$WORK/genesis-retained-mismatch.log" 2>&1; then
  die_test "retained node 0 genesis silently diverged from the supplied identity"
fi
grep -q "retained node 0 genesis differs" "$WORK/genesis-retained-mismatch.log" \
  || die_test "retained genesis mismatch produced the wrong diagnostic"
cp "$GENESIS_SOURCE" "$(node_dir 0)/shelley-genesis.json"

assert_genesis_rejected() {
  local name="$1" expected="$2" log="$WORK/genesis-$1.log"
  if ( validate_devnet_genesis_input ) > "$log" 2>&1; then
    die_test "$name devnet genesis input was accepted"
  fi
  grep -q "$expected" "$log" || die_test "$name devnet genesis input produced the wrong diagnostic"
}

NETWORK=preprod
assert_genesis_rejected "public-network" "supported only"
NETWORK=devnet
INVALID_GENESIS="$GENESIS_PARENT/invalid.json"
printf '%s\n' '{"duplicate":1,"duplicate":2}' > "$INVALID_GENESIS"
chmod 600 "$INVALID_GENESIS"
DEVNET_GENESIS_FILE="$INVALID_GENESIS"
assert_genesis_rejected "invalid-json" "unique keys"
chmod 666 "$INVALID_GENESIS"
assert_genesis_rejected "writable" "must not be group/world writable"
chmod 600 "$INVALID_GENESIS"
GENESIS_SYMLINK="$GENESIS_PARENT/symlink.json"
ln -s "$GENESIS_SOURCE" "$GENESIS_SYMLINK"
DEVNET_GENESIS_FILE="$GENESIS_SYMLINK"
assert_genesis_rejected "symlink" "must not be a symbolic link"

# Revalidation prevents a source change between preflight and installation.
CHANGED_SOURCE="$GENESIS_PARENT/changed.json"
cp "$GENESIS_SOURCE" "$CHANGED_SOURCE"
chmod 600 "$CHANGED_SOURCE"
DEVNET_GENESIS_FILE="$CHANGED_SOURCE"
validate_devnet_genesis_input
printf '%s\n' '{"changed":true}' > "$CHANGED_SOURCE"
CLUSTER_DIR="$WORK/changed-genesis-cluster"
if ( prepare_devnet_node0_genesis ) > "$WORK/genesis-changed.log" 2>&1; then
  die_test "changed devnet genesis input was installed"
fi
grep -q "changed before installation" "$WORK/genesis-changed.log" \
  || die_test "changed devnet genesis input produced the wrong diagnostic"
[ ! -e "$CLUSTER_DIR" ] || die_test "changed genesis created cluster state before rejection"

# Unset preserves the standalone launcher's historical epochLength rewrite.
if command -v jq >/dev/null 2>&1; then
  DEFAULT_HOME="$WORK/default-home"
  mkdir -p "$DEFAULT_HOME/config/network/devnet"
  printf '%s\n' '{"systemStart":"2026-01-01T00:00:00Z","epochLength":999}' \
    > "$DEFAULT_HOME/config/network/devnet/shelley-genesis.json"
  YANO_HOME="$DEFAULT_HOME"
  DEVNET_GENESIS_FILE=""
  validate_devnet_genesis_input
  CLUSTER_DIR="$WORK/default-genesis-cluster"
  prepare_devnet_node0_genesis
  jq -e '.epochLength == 500' "$(node_dir 0)/shelley-genesis.json" >/dev/null \
    || die_test "unset genesis input changed the standalone epochLength behavior"
fi

# A fake long-running node exposes the exact three launcher-owned signature
# properties without opening sockets or running Yano.
FAKE_NODE="$WORK/fake-node"
printf '%s\n' '#!/bin/sh' \
  "trap 'exit 0' TERM INT" \
  'while :; do sleep 1; done' > "$FAKE_NODE"
chmod 700 "$FAKE_NODE"

CLUSTER_DIR="$WORK/pids"
mkdir -m 700 "$CLUSTER_DIR"
HTTP_BASE=38070
SERVER_BASE=38337
CONFIG_FILE="$WORK/no-chains.yml"
: > "$CONFIG_FILE"
chain_ids() { :; }

start_fake_node() {
  local i="$1"
  "$FAKE_NODE" \
    "-Dquarkus.profile=pid-test" \
    "-Dquarkus.http.port=$(http_port "$i")" \
    "-Dyano.server.port=$(server_port "$i")" \
    "-Dyano.storage.path=$(node_dir "$i")/chainstate" &
  STARTED_PID=$!
}

write_manual_record() {
  local i="$1" pid="$2" start="$3"
  printf '%s\n' "$pid" > "$(pid_file "$i")"
  { printf 'VERSION=1\n'; printf 'PID=%s\n' "$pid"; printf 'START=%s\n' "$start"
    printf 'HTTP_PORT=%s\n' "$(http_port "$i")"
    printf 'SERVER_PORT=%s\n' "$(server_port "$i")"; } \
    > "$(pid_meta_file "$i")"
  chmod 600 "$(pid_file "$i")" "$(pid_meta_file "$i")"
}

start_fake_node 0
GENUINE_PID="$STARTED_PID"
write_pid_record 0 "$GENUINE_PID"
[ "$(posix_mode "$(pid_file 0)")" = 600 ] \
  && [ "$(posix_mode "$(pid_meta_file 0)")" = 600 ] \
  || die_test "launcher PID records were not chmod 600"
managed_node_pid 0 || die_test "launcher-owned fake node was not recognized"
[ "$VALIDATED_PID" = "$GENUINE_PID" ] || die_test "validated PID differs from launched PID"
[ "$(running_node_count)" = 1 ] || die_test "running-node count ignored a managed node"
cmd_stop > "$WORK/stop-genuine.log" 2>&1
wait "$GENUINE_PID" 2>/dev/null || true
! kill -0 "$GENUINE_PID" 2>/dev/null || die_test "stop did not terminate the managed node"
[ ! -e "$(pid_file 0)" ] && [ ! -e "$(pid_meta_file 0)" ] \
  || die_test "stop retained managed PID records"

# A launcher-owned process that ignores TERM is escalated to KILL. PID records
# disappear only after the exact PID + start-token process instance is gone.
FAKE_NODE_IGNORE_TERM="$WORK/fake-node-ignore-term"
printf '%s\n' '#!/bin/sh' \
  "trap '' TERM INT" \
  "printf '%s\\n' ready > \"$WORK/ignore-term-ready.\$\$\"" \
  'while :; do sleep 1; done' > "$FAKE_NODE_IGNORE_TERM"
chmod 700 "$FAKE_NODE_IGNORE_TERM"

start_term_ignoring_node() {
  local i="$1" attempt
  "$FAKE_NODE_IGNORE_TERM" \
    "-Dquarkus.profile=pid-test" \
    "-Dquarkus.http.port=$(http_port "$i")" \
    "-Dyano.server.port=$(server_port "$i")" \
    "-Dyano.storage.path=$(node_dir "$i")/chainstate" &
  STARTED_PID=$!
  for attempt in {1..100}; do
    [ -f "$WORK/ignore-term-ready.$STARTED_PID" ] && return 0
    kill -0 "$STARTED_PID" 2>/dev/null \
      || die_test "TERM-ignoring fixture exited before installing its signal handler"
    sleep 0.01
  done
  die_test "TERM-ignoring fixture did not report signal-handler readiness"
}

start_term_ignoring_node 0
TERM_IGNORING_PID="$STARTED_PID"
write_pid_record 0 "$TERM_IGNORING_PID"
cmd_stop > "$WORK/stop-term-ignoring.log" 2>&1 &
STOP_TERM_JOB=$!
sleep 1
kill -0 "$TERM_IGNORING_PID" 2>/dev/null \
  || die_test "stop did not honor the TERM grace period"
[ -e "$(pid_file 0)" ] && [ -e "$(pid_meta_file 0)" ] \
  || die_test "stop removed PID records before confirming process exit"
wait "$STOP_TERM_JOB" \
  || die_test "stop failed to confirm a managed TERM-ignoring process"
wait "$TERM_IGNORING_PID" 2>/dev/null || true
! kill -0 "$TERM_IGNORING_PID" 2>/dev/null \
  || die_test "stop did not KILL a managed TERM-ignoring process"
[ ! -e "$(pid_file 0)" ] && [ ! -e "$(pid_meta_file 0)" ] \
  || die_test "stop removed neither record after confirmed KILL"

# The fatal-log startup path uses the same confirmed-stop primitive. It must
# stop even a TERM-ignoring runtime before reporting failure and removing the
# persistent records that protect shared state ownership.
mkdir -p "$(node_dir 0)"
start_term_ignoring_node 0
FATAL_START_PID="$STARTED_PID"
write_pid_record 0 "$FATAL_START_PID"
printf '%s\n' 'ERROR Failed to start application' > "$(log_file 0)"
( health_ready() { return 1; }; wait_ready 0 ) > "$WORK/wait-ready-fatal.log" 2>&1 &
WAIT_READY_JOB=$!
sleep 1
kill -0 "$FATAL_START_PID" 2>/dev/null \
  || die_test "wait_ready did not honor the TERM grace period"
[ -e "$(pid_file 0)" ] && [ -e "$(pid_meta_file 0)" ] \
  || die_test "wait_ready removed records before confirming runtime exit"
if wait "$WAIT_READY_JOB"; then
  die_test "wait_ready accepted a fatal startup log"
fi
wait "$FATAL_START_PID" 2>/dev/null || true
! kill -0 "$FATAL_START_PID" 2>/dev/null \
  || die_test "wait_ready returned before its failed runtime was stopped"
[ ! -e "$(pid_file 0)" ] && [ ! -e "$(pid_meta_file 0)" ] \
  || die_test "wait_ready retained records after confirmed fatal-runtime shutdown"
grep -q 'runtime failed during startup' "$WORK/wait-ready-fatal.log" \
  || die_test "wait_ready fatal path produced the wrong diagnostic"

# A child exists before its PID records can be published. If publication
# fails, the exact direct child is stopped first; only then may the launch
# fence and partial records be removed. A stale launcher temp deterministically
# exercises this rollback without relying on filesystem permissions.
create_launch_fence 0
start_term_ignoring_node 0
PUBLICATION_PID="$STARTED_PID"
STALE_PID_TEMP="$(pid_file 0).tmp.$$"
printf '%s\n' 'stale' > "$STALE_PID_TEMP"
chmod 600 "$STALE_PID_TEMP"
( write_pid_record 0 "$PUBLICATION_PID" ) > "$WORK/pid-publication-failure.log" 2>&1 &
PUBLICATION_JOB=$!
sleep 1
kill -0 "$PUBLICATION_PID" 2>/dev/null \
  || die_test "PID publication rollback did not honor the TERM grace period"
[ -e "$(launch_fence_file 0)" ] && [ -e "$STALE_PID_TEMP" ] \
  || die_test "PID publication rollback cleared recovery artifacts before process exit"
if wait "$PUBLICATION_JOB"; then
  die_test "PID publication accepted a stale partial record"
fi
wait "$PUBLICATION_PID" 2>/dev/null || true
! kill -0 "$PUBLICATION_PID" 2>/dev/null \
  || die_test "PID publication rollback left its exact child alive"
for artifact in "$(pid_file 0)" "$(pid_meta_file 0)" "$(launch_fence_file 0)" \
    "$(pid_file 0).tmp.$$" "$(pid_meta_file 0).tmp.$$"; do
  [ ! -e "$artifact" ] && [ ! -L "$artifact" ] \
    || die_test "PID publication rollback retained $artifact after confirmed shutdown"
done
grep -q 'stale PID record temporary file' "$WORK/pid-publication-failure.log" \
  || die_test "PID publication rollback produced the wrong diagnostic"

# Partial publication artifacts are recovery fences, not stale clutter. A
# metadata-only record makes stop fail, and a launch-fence-only record makes
# clean (cmd_stop wipe) fail without deleting the cluster directory.
printf '%s\n' 'VERSION=1' 'PID=999999' \
  'START=p-0000000000000000000000000000000000000000000000000000000000000000' \
  "HTTP_PORT=$(http_port 0)" "SERVER_PORT=$(server_port 0)" > "$(pid_meta_file 0)"
chmod 600 "$(pid_meta_file 0)"
if cmd_stop > "$WORK/stop-metadata-only.log" 2>&1; then
  die_test "stop accepted a metadata-only partial record"
fi
[ -f "$(pid_meta_file 0)" ] && [ -d "$CLUSTER_DIR" ] \
  || die_test "stop removed metadata-only recovery state"
rm -f "$(pid_meta_file 0)"

create_launch_fence 0
if cmd_stop wipe > "$WORK/clean-launch-fence-only.log" 2>&1; then
  die_test "clean accepted a launch-fence-only partial record"
fi
[ -f "$(launch_fence_file 0)" ] && [ -d "$CLUSTER_DIR" ] \
  || die_test "clean removed a launch fence or cluster data"
remove_launch_fence 0

# A malformed publication artifact is still a recovery fence. It must never be
# skipped merely because its suffix/index grammar is unknown, especially on a
# clean request that would otherwise remove the complete cluster directory.
MALFORMED_ARTIFACT="$CLUSTER_DIR/node0.pid.tmp.bad"
printf '%s\n' 'partial' > "$MALFORMED_ARTIFACT"
chmod 600 "$MALFORMED_ARTIFACT"
if cmd_stop wipe > "$WORK/clean-malformed-artifact.log" 2>&1; then
  die_test "clean ignored a malformed PID lifecycle artifact"
fi
[ -f "$MALFORMED_ARTIFACT" ] && [ -d "$CLUSTER_DIR" ] \
  || die_test "clean removed malformed PID recovery state"
grep -q 'malformed PID lifecycle' "$WORK/clean-malformed-artifact.log" \
  || die_test "malformed PID artifact produced the wrong diagnostic"
rm "$MALFORMED_ARTIFACT"

# A validly shaped record for an unrelated same-owner process fails the node
# argument signature. Status does not query its port and stop never signals it.
sleep 30 &
UNRELATED_PID=$!
UNRELATED_START="$(process_start_token "$UNRELATED_PID")"
write_manual_record 0 "$UNRELATED_PID" "$UNRELATED_START"
FAKE_TOOLS="$WORK/fake-tools"
mkdir -m 700 "$FAKE_TOOLS"
printf '%s\n' '#!/bin/sh' 'touch "$CURL_SENTINEL"' 'exit 1' > "$FAKE_TOOLS/curl"
chmod 700 "$FAKE_TOOLS/curl"
CURL_SENTINEL="$WORK/curl-was-called"; export CURL_SENTINEL
PATH="$FAKE_TOOLS:$PATH" cmd_status > "$WORK/status-unrelated.log" 2>&1
grep -q 'stale/untrusted PID record' "$WORK/status-unrelated.log" \
  || die_test "status did not identify an unrelated PID"
[ ! -e "$CURL_SENTINEL" ] || die_test "status queried a port from an untrusted PID record"
if cmd_stop wipe > "$WORK/stop-unrelated.log" 2>&1; then
  die_test "wipe succeeded with an untrusted live PID record"
fi
kill -0 "$UNRELATED_PID" 2>/dev/null || die_test "stop signalled an unrelated process"
[ -d "$CLUSTER_DIR" ] || die_test "wipe deleted cluster state behind an untrusted process"
[ -f "$(pid_file 0)" ] || die_test "stop removed an untrusted PID record"
kill "$UNRELATED_PID" 2>/dev/null || true
wait "$UNRELATED_PID" 2>/dev/null || true
remove_pid_record 0

# Legacy pid-only records are also fail-closed: an upgraded launcher cannot
# prove ownership of that live process, so clean must preserve its data.
sleep 30 &
LEGACY_PID=$!
printf '%s\n' "$LEGACY_PID" > "$(pid_file 0)"
chmod 600 "$(pid_file 0)"
if cmd_stop wipe > "$WORK/stop-legacy.log" 2>&1; then
  die_test "wipe accepted a live legacy pid-only record"
fi
kill -0 "$LEGACY_PID" 2>/dev/null || die_test "stop signalled a legacy untrusted process"
[ -d "$CLUSTER_DIR" ] || die_test "wipe deleted cluster state for a legacy PID record"
kill "$LEGACY_PID" 2>/dev/null || true
wait "$LEGACY_PID" 2>/dev/null || true
remove_pid_record 0

# A reused/tampered start token blocks signalling even when argv matches.
start_fake_node 0
REUSED_PID="$STARTED_PID"
write_manual_record 0 "$REUSED_PID" "p-$(printf '%064d' 0)"
cmd_stop > "$WORK/stop-reused.log" 2>&1 || true
kill -0 "$REUSED_PID" 2>/dev/null || die_test "stop signalled a start-token mismatch"
kill "$REUSED_PID" 2>/dev/null || true
wait "$REUSED_PID" 2>/dev/null || true
remove_pid_record 0

# PID content is data, not shell, and PID symlinks are never followed.
printf '%s\n' "\$(touch $SENTINEL)" > "$(pid_file 0)"
chmod 600 "$(pid_file 0)"
cmd_stop > "$WORK/stop-injection.log" 2>&1 || true
[ ! -e "$SENTINEL" ] || die_test "PID file content executed as shell code"
remove_pid_record 0

sleep 30 &
SYMLINK_PID=$!
printf '%s\n' "$SYMLINK_PID" > "$WORK/pid-target"
ln -s "$WORK/pid-target" "$(pid_file 0)"
write_manual_record 1 "$SYMLINK_PID" "$(process_start_token "$SYMLINK_PID")"
mv "$(pid_meta_file 1)" "$(pid_meta_file 0)"
cmd_stop > "$WORK/stop-symlink.log" 2>&1 || true
kill -0 "$SYMLINK_PID" 2>/dev/null || die_test "stop followed a symlink PID file"
kill "$SYMLINK_PID" 2>/dev/null || true
wait "$SYMLINK_PID" 2>/dev/null || true
remove_pid_record 0

! grep -q 'lsof -ti' "$CLUSTER" || die_test "launcher retained blanket port-based process killing"

printf 'PASS: cluster environment and PID records are strict and non-destructive\n'
