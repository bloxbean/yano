#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEMO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/yano-operation-lock-test.XXXXXX")"
TMP="$(cd "$TMP" && pwd -P)"
BACKGROUND_PIDS=()

cleanup() {
  local pid
  set +e
  for pid in "${BACKGROUND_PIDS[@]-}"; do
    kill -KILL "$pid" 2>/dev/null || true
  done
  pkill -KILL -f "$TMP" 2>/dev/null || true
  rm -rf "$TMP"
}
trap cleanup EXIT

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
wait_for_file() {
  local path="$1" deadline=$((SECONDS + 15))
  while [ ! -e "$path" ] && [ "$SECONDS" -lt "$deadline" ]; do sleep 0.05; done
  [ -e "$path" ] || fail "timed out waiting for $path"
}
wait_for_exit() {
  local pid="$1" deadline=$((SECONDS + 15))
  while kill -0 "$pid" 2>/dev/null && [ "$SECONDS" -lt "$deadline" ]; do sleep 0.05; done
  ! kill -0 "$pid" 2>/dev/null
}
seed_artifacts() {
  local runtime="$1" name
  mkdir -p "$runtime/plugins"
  for name in appchain-kafka appchain-ipfs appchain-objectstore-s3 appchain-evidence-registry; do
    : > "$runtime/plugins/$name-bundle.jar"
  done
  : > "$runtime/runner.jar"
  : > "$runtime/yano.jar"
}

command -v python3 >/dev/null 2>&1 || fail "python3 is required"
bash -n "$DEMO_DIR/demo.sh"
python3 -m py_compile "$DEMO_DIR/tools/lifecycle.py"
if python3 "$DEMO_DIR/tools/lifecycle.py" operation-run \
    --data-root "$TMP/arbitrary/data" --network devnet --deployment compose -- \
    /bin/sh -c 'sleep 60 &' >"$TMP/arbitrary.out" 2>&1; then
  fail "internal operation supervisor accepted an arbitrary command"
fi
grep -Fq 'accepts only the bundled demo.sh' "$TMP/arbitrary.out" \
  || fail "arbitrary operation command rejection is unclear"
[ ! -e "$TMP/arbitrary" ] \
  || fail "rejected arbitrary operation command created managed state"

# A stop for the same network/deployment cannot release the deterministic L1
# lease while `up` is still inside preparation/config validation.
RACE="$TMP/stop-vs-up"
RACE_INSTANCE=race
RACE_RUNTIME="$RACE/runtime/networks/devnet/$RACE_INSTANCE/compose"
RACE_LEASE="$RACE/data/networks/devnet/l1/compose/demo-owner.json"
RACE_BIN="$RACE/bin"
mkdir -p "$RACE_BIN"
seed_artifacts "$RACE_RUNTIME"
cat > "$RACE_BIN/docker" <<'SH'
#!/bin/sh
if [ "${1:-}" = compose ]; then
  operation=
  shift
  for argument in "$@"; do
    case "$argument" in config|up|down) operation="$argument";; esac
  done
  case "$operation" in
    config)
      : > "$TEST_CONFIG_ENTERED"
      while [ ! -e "$TEST_CONFIG_RELEASE" ]; do sleep 0.05; done
      exit 42
      ;;
    down)
      : > "$TEST_STOP_ENTERED"
      exit 0
      ;;
  esac
fi
if [ "${1:-}" = ps ]; then exit 0; fi
exit 97
SH
chmod 755 "$RACE_BIN/docker"

RACE_ENV=(
  DEMO_DATA_ROOT="$RACE/data"
  DEMO_SECRET_ROOT="$RACE/secrets"
  DEMO_RUNTIME_ROOT="$RACE/runtime"
  DEMO_SKIP_BUILD=true
  TEST_CONFIG_ENTERED="$RACE/config-entered"
  TEST_CONFIG_RELEASE="$RACE/config-release"
  TEST_STOP_ENTERED="$RACE/stop-entered"
)
env "${RACE_ENV[@]}" PATH="$RACE_BIN:$PATH" "$DEMO_DIR/demo.sh" up \
  --instance "$RACE_INSTANCE" >"$RACE/up.out" 2>&1 &
up_pid=$!
BACKGROUND_PIDS+=("$up_pid")
wait_for_file "$RACE/config-entered"
wait_for_file "$RACE_LEASE"
lease_before="$(shasum -a 256 "$RACE_LEASE")"
env "${RACE_ENV[@]}" PATH="$RACE_BIN:$PATH" "$DEMO_DIR/demo.sh" stop \
  --instance "$RACE_INSTANCE" >"$RACE/stop.out" 2>&1 &
stop_pid=$!
BACKGROUND_PIDS+=("$stop_pid")
sleep 0.5
kill -0 "$stop_pid" 2>/dev/null || fail "stop did not wait for the active up command"
[ ! -e "$RACE/stop-entered" ] || fail "stop entered Docker while up still held the operation lock"
[ "$lease_before" = "$(shasum -a 256 "$RACE_LEASE")" ] \
  || fail "waiting stop changed the active up command's L1 lease"
: > "$RACE/config-release"
if wait "$up_pid"; then fail "blocked fake up unexpectedly succeeded"; fi
wait "$stop_pid" || fail "serialized stop failed"
[ ! -e "$RACE_LEASE" ] || fail "serialized stop did not leave the L1 lease absent"
wait_for_file "$RACE/stop-entered"

# Build a fake Docker client that leaves a TERM-ignoring descendant in the
# command process group. The second invocation is observable and fails fast.
make_crash_docker() {
  local directory="$1"
  mkdir -p "$directory"
  cat > "$directory/docker" <<'SH'
#!/bin/sh
if [ "${1:-}" = compose ]; then
  operation=
  shift
  for argument in "$@"; do
    case "$argument" in config|down) operation="$argument";; esac
  done
  if [ "$operation" = config ]; then
    if mkdir "$TEST_FIRST_CLAIM" 2>/dev/null; then
      printf '%s\n' "$PPID" > "$TEST_COMMAND_SHELL_PID"
      trap '' TERM
      sh -c 'trap "" TERM; while :; do sleep 1; done' &
      printf '%s\n' "$!" > "$TEST_STUBBORN_PID"
      : > "$TEST_FIRST_ENTERED"
      wait
    fi
  fi
  if [ "$operation" = down ]; then
    : > "$TEST_SECOND_ENTERED"
    exit 0
  fi
fi
if [ "${1:-}" = ps ]; then exit 0; fi
exit 97
SH
  chmod 755 "$directory/docker"
}

run_crash_case() {
  local kind="$1" root="$TMP/crash-$1" instance="crash-$1"
  local bin="$root/bin" supervisor second victim stubborn
  make_crash_docker "$bin"
  local -a crash_env=(
    DEMO_DATA_ROOT="$root/data"
    DEMO_SECRET_ROOT="$root/secrets"
    DEMO_RUNTIME_ROOT="$root/runtime"
    DEMO_SKIP_BUILD=true
    TEST_FIRST_CLAIM="$root/first-claim"
    TEST_COMMAND_SHELL_PID="$root/command-shell.pid"
    TEST_STUBBORN_PID="$root/stubborn.pid"
    TEST_FIRST_ENTERED="$root/first-entered"
    TEST_SECOND_ENTERED="$root/second-entered"
  )
  env "${crash_env[@]}" PATH="$bin:$PATH" "$DEMO_DIR/demo.sh" config \
    --instance "$instance" >"$root/first.out" 2>&1 &
  supervisor=$!
  BACKGROUND_PIDS+=("$supervisor")
  wait_for_file "$root/first-entered"
  wait_for_file "$root/stubborn.pid"
  stubborn="$(cat "$root/stubborn.pid")"
  case "$kind" in
    supervisor) victim="$supervisor";;
    child) victim="$(cat "$root/command-shell.pid")";;
    *) fail "unknown crash case $kind";;
  esac
  kill -KILL "$victim"
  [ "$kind" != supervisor ] || wait "$supervisor" 2>/dev/null || true

  env "${crash_env[@]}" PATH="$bin:$PATH" "$DEMO_DIR/demo.sh" stop \
    --instance "$instance" >"$root/second.out" 2>&1 &
  second=$!
  BACKGROUND_PIDS+=("$second")
  sleep 0.5
  kill -0 "$second" 2>/dev/null \
    || fail "$kind crash allowed the second operation to finish before cleanup"
  [ ! -e "$root/second-entered" ] \
    || fail "$kind crash released the operation lock before its process group was gone"
  kill -0 "$stubborn" 2>/dev/null \
    || fail "$kind crash fixture's TERM-ignoring descendant exited too early"

  wait_for_file "$root/second-entered"
  wait "$second" || fail "$kind crash serialized stop failed"
  wait_for_exit "$stubborn" \
    || fail "$kind crash did not KILL the TERM-ignoring descendant"
  [ "$kind" != child ] || { wait "$supervisor" 2>/dev/null || true; }
}

run_crash_case supervisor
run_crash_case child

# A successful command may intentionally leave services behind, but none may
# inherit the operation flock. A second command must enter while that exact
# descendant is still alive.
LEAK="$TMP/fd-leak"
LEAK_BIN="$LEAK/bin"
mkdir -p "$LEAK_BIN"
cat > "$LEAK_BIN/docker" <<'SH'
#!/bin/sh
if [ "${1:-}" = compose ]; then
  for argument in "$@"; do
    if [ "$argument" = config ]; then
      if mkdir "$TEST_FIRST_CLAIM" 2>/dev/null; then
        sh -c 'while :; do sleep 1; done' &
        printf '%s\n' "$!" > "$TEST_DESCENDANT_PID"
      else
        : > "$TEST_SECOND_ENTERED"
      fi
      exit 0
    fi
  done
fi
exit 97
SH
chmod 755 "$LEAK_BIN/docker"
LEAK_ENV=(
  DEMO_DATA_ROOT="$LEAK/data"
  DEMO_SECRET_ROOT="$LEAK/secrets"
  DEMO_RUNTIME_ROOT="$LEAK/runtime"
  DEMO_SKIP_BUILD=true
  TEST_FIRST_CLAIM="$LEAK/first-claim"
  TEST_DESCENDANT_PID="$LEAK/descendant.pid"
  TEST_SECOND_ENTERED="$LEAK/second-entered"
)
env "${LEAK_ENV[@]}" PATH="$LEAK_BIN:$PATH" "$DEMO_DIR/demo.sh" config \
  --instance leak >"$LEAK/first.out" 2>&1
wait_for_file "$LEAK/descendant.pid"
leak_pid="$(cat "$LEAK/descendant.pid")"
BACKGROUND_PIDS+=("$leak_pid")
kill -0 "$leak_pid" 2>/dev/null || fail "FD-leak fixture descendant is not alive"
env "${LEAK_ENV[@]}" PATH="$LEAK_BIN:$PATH" "$DEMO_DIR/demo.sh" config \
  --instance leak >"$LEAK/second.out" 2>&1
wait_for_file "$LEAK/second-entered"
kill -0 "$leak_pid" 2>/dev/null \
  || fail "successful operation unexpectedly terminated its intentional descendant"

printf 'PASS: command-lifetime serialization, crash cleanup and FD non-leak\n'
