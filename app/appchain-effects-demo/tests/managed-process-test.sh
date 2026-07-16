#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
DEMO_DIR="$(cd "$SCRIPT_DIR/.." && pwd -P)"
TOOL="$DEMO_DIR/tools/managed_process.py"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/yano-managed-process-test.XXXXXX")"
PIDS_FILE="$TEST_ROOT/test-pids"

cleanup() {
  if [ -f "$PIDS_FILE" ]; then
    while IFS= read -r pid; do
      [[ "$pid" =~ ^[1-9][0-9]*$ ]] || continue
      kill -KILL "$pid" 2>/dev/null || true
    done < "$PIDS_FILE"
  fi
  rm -rf "$TEST_ROOT"
}
trap cleanup EXIT HUP INT TERM

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

expect_failure() {
  label="$1"
  shift
  if "$@" >"$TEST_ROOT/failure.out" 2>"$TEST_ROOT/failure.err"; then
    fail "$label unexpectedly succeeded"
  fi
}

make_root() {
  directory="$1"
  mkdir -m 0700 "$directory"
}

start_process() {
  runtime="$1"
  shift
  python3 "$TOOL" start --runtime-root "$runtime" --exec-timeout 3 -- "$@"
}

status_process() {
  runtime="$1"
  shift
  python3 "$TOOL" status --runtime-root "$runtime" -- "$@"
}

stop_process() {
  runtime="$1"
  shift
  python3 "$TOOL" stop --runtime-root "$runtime" \
    --term-timeout 0.25 --kill-timeout 3 -- "$@"
}

assert_stopped() {
  runtime="$1"
  shift
  status=0
  status_process "$runtime" "$@" >"$TEST_ROOT/status.out" 2>"$TEST_ROOT/status.err" || status=$?
  [ "$status" -eq 3 ] || fail "expected stopped status (3), got $status"
  [ "$(cat "$TEST_ROOT/status.out")" = stopped ] || fail "stopped status output changed"
}

WORKER="$TEST_ROOT/worker.py"
cat > "$WORKER" <<'PY'
#!/usr/bin/env python3
import os
from pathlib import Path
import signal
import sys
import time

mode, ready = sys.argv[1:]
if mode == "ignore-term":
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
elif mode == "exec-on-usr1":
    def replace(_signum, _frame):
        os.execl("/bin/sleep", "sleep", "300")
    signal.signal(signal.SIGUSR1, replace)
Path(ready).write_text(str(os.getpid()) + "\n", encoding="ascii")
if mode == "exit-soon":
    time.sleep(0.15)
    raise SystemExit(0)
while True:
    time.sleep(1)
PY
chmod 0700 "$WORKER"

# Normal start is idempotent, status binds exact argv, and stop removes only the
# process record while retaining the expected lock and log files.
basic="$TEST_ROOT/basic"
make_root "$basic"
basic_ready="$TEST_ROOT/basic.ready"
basic_command=(python3 "$WORKER" normal "$basic_ready")
basic_pid="$(start_process "$basic" "${basic_command[@]}")"
echo "$basic_pid" >> "$PIDS_FILE"
[[ "$basic_pid" =~ ^[1-9][0-9]*$ ]] || fail "start did not return a PID"
[ "$(start_process "$basic" "${basic_command[@]}")" = "$basic_pid" ] \
  || fail "idempotent start returned a different PID"
[ "$(status_process "$basic" "${basic_command[@]}")" = "running $basic_pid" ] \
  || fail "running status output changed"
python3 - "$basic" <<'PY'
import json
import os
from pathlib import Path
import stat
import sys

root = Path(sys.argv[1])
if stat.S_IMODE(root.stat(follow_symlinks=False).st_mode) != 0o700:
    raise SystemExit("runtime root mode changed")
for name in (".host-ui.lock", "host-ui.log", "host-ui.process.json"):
    info = (root / name).stat(follow_symlinks=False)
    if not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600:
        raise SystemExit(f"unsafe artifact mode: {name}")
    if info.st_uid != os.geteuid() or info.st_nlink != 1:
        raise SystemExit(f"unsafe artifact identity: {name}")
record_path = root / "host-ui.process.json"
record = json.loads(record_path.read_text(encoding="utf-8"))
canonical = json.dumps(record, sort_keys=True, separators=(",", ":")) + "\n"
if record_path.read_text(encoding="utf-8") != canonical:
    raise SystemExit("record is not canonical JSON")
PY
expect_failure "wrong exact argv status" \
  status_process "$basic" python3 "$WORKER" normal "$TEST_ROOT/not-the-ready-file"
kill -0 "$basic_pid" || fail "wrong argv status disturbed the managed process"
stop_process "$basic" "${basic_command[@]}"
[ ! -e "$basic/host-ui.process.json" ] || fail "stop retained the process record"
[ ! -e "$basic/.host-ui.launch.json" ] || fail "stop retained the launch fence"

# A TERM-resistant child is escalated to KILL after the bounded grace period.
escalate="$TEST_ROOT/escalate"
make_root "$escalate"
escalate_ready="$TEST_ROOT/escalate.ready"
escalate_command=(python3 "$WORKER" ignore-term "$escalate_ready")
escalate_pid="$(start_process "$escalate" "${escalate_command[@]}")"
echo "$escalate_pid" >> "$PIDS_FILE"
started_at="$(python3 -c 'import time; print(time.monotonic())')"
stop_process "$escalate" "${escalate_command[@]}"
python3 - "$started_at" <<'PY'
import sys, time
elapsed = time.monotonic() - float(sys.argv[1])
if elapsed < 0.20 or elapsed > 4.0:
    raise SystemExit(f"TERM/KILL escalation was not bounded as expected: {elapsed:.3f}s")
PY
kill -0 "$escalate_pid" 2>/dev/null && fail "TERM-resistant child survived KILL escalation"

# A stale exact record is safely collected once its exact process is absent.
stale="$TEST_ROOT/stale"
make_root "$stale"
stale_ready="$TEST_ROOT/stale.ready"
stale_command=(python3 "$WORKER" exit-soon "$stale_ready")
stale_pid="$(start_process "$stale" "${stale_command[@]}")"
echo "$stale_pid" >> "$PIDS_FILE"
sleep 0.4
assert_stopped "$stale" "${stale_command[@]}"
[ ! -e "$stale/host-ui.process.json" ] || fail "stale record was not collected"

# Simulate PID reuse by moving a stale token onto an unrelated live PID.  The
# unrelated process must not be signalled because the start token differs.
reuse="$TEST_ROOT/reuse"
make_root "$reuse"
reuse_ready="$TEST_ROOT/reuse.ready"
reuse_command=(python3 "$WORKER" normal "$reuse_ready")
reuse_pid="$(start_process "$reuse" "${reuse_command[@]}")"
echo "$reuse_pid" >> "$PIDS_FILE"
kill "$reuse_pid"
deadline=$((SECONDS + 3))
while kill -0 "$reuse_pid" 2>/dev/null && [ "$SECONDS" -lt "$deadline" ]; do sleep 0.05; done
/bin/sleep 300 &
unrelated_pid=$!
echo "$unrelated_pid" >> "$PIDS_FILE"
python3 - "$reuse/host-ui.process.json" "$unrelated_pid" <<'PY'
import json
from pathlib import Path
import sys
path = Path(sys.argv[1])
value = json.loads(path.read_text(encoding="utf-8"))
value["pid"] = int(sys.argv[2])
path.write_text(json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")
PY
chmod 0600 "$reuse/host-ui.process.json"
stop_process "$reuse" "${reuse_command[@]}"
kill -0 "$unrelated_pid" || fail "PID-reuse protection signalled an unrelated process"
kill "$unrelated_pid"

# The same kernel process identity with changed argv is untrusted: preserve its
# record and do not signal it.
untrusted="$TEST_ROOT/untrusted"
make_root "$untrusted"
untrusted_ready="$TEST_ROOT/untrusted.ready"
untrusted_command=(python3 "$WORKER" exec-on-usr1 "$untrusted_ready")
untrusted_pid="$(start_process "$untrusted" "${untrusted_command[@]}")"
echo "$untrusted_pid" >> "$PIDS_FILE"
kill -USR1 "$untrusted_pid"
sleep 0.2
expect_failure "same-token argv replacement" stop_process "$untrusted" "${untrusted_command[@]}"
kill -0 "$untrusted_pid" || fail "untrusted argv process was signalled"
[ -f "$untrusted/host-ui.process.json" ] || fail "untrusted argv record was removed"
kill -KILL "$untrusted_pid"

# A durable launch fence plus gated fork recovers deterministically from hard
# launcher death before fork, after fork, after publication, and after gate.
for point in fence fork publication; do
  crash="$TEST_ROOT/crash-$point"
  make_root "$crash"
  crash_ready="$TEST_ROOT/crash-$point.ready"
  crash_command=(python3 "$WORKER" normal "$crash_ready")
  expect_failure "crash injection at $point" env \
    YANO_MANAGED_PROCESS_TEST_STOP_AFTER="$point" \
    python3 "$TOOL" start --runtime-root "$crash" --exec-timeout 2 -- \
      "${crash_command[@]}"
  assert_stopped "$crash" "${crash_command[@]}"
  [ ! -e "$crash/.host-ui.launch.json" ] || fail "$point fence was not recovered"
  [ ! -e "$crash/host-ui.process.json" ] || fail "$point record was not recovered"
done

for point in fence-prepared-temp fence-prepared-linked \
    fence-forked-temp fence-forked-renamed record-linked record-published \
    fence-published-temp fence-published-renamed; do
  crash="$TEST_ROOT/crash-$point"
  make_root "$crash"
  crash_ready="$TEST_ROOT/crash-$point.ready"
  crash_command=(python3 "$WORKER" normal "$crash_ready")
  expect_failure "atomic fence crash at $point" env \
    YANO_MANAGED_PROCESS_TEST_STOP_AFTER="$point" \
    python3 "$TOOL" start --runtime-root "$crash" --exec-timeout 2 -- \
      "${crash_command[@]}"
  assert_stopped "$crash" "${crash_command[@]}"
  [ ! -e "$crash/.host-ui.launch.tmp" ] || fail "$point temporary was not recovered"
done

# Partial writes before the initial prepared fence publication cannot have
# forked a child. Recover only the exact private fixed temp and remain stopped.
partial_prepared="$TEST_ROOT/crash-partial-prepared-fence"
make_root "$partial_prepared"
partial_prepared_ready="$TEST_ROOT/crash-partial-prepared-fence.ready"
partial_prepared_command=(python3 "$WORKER" normal "$partial_prepared_ready")
printf '{"incomplete":' > "$partial_prepared/.host-ui.launch.tmp"
chmod 0600 "$partial_prepared/.host-ui.launch.tmp"
assert_stopped "$partial_prepared" "${partial_prepared_command[@]}"
[ ! -e "$partial_prepared/.host-ui.launch.tmp" ] \
  || fail "partial initial-fence temporary was not reconciled"

# A partial record write occurs only after the canonical forked fence exists.
# Its gated child must be proven absent before the exact malformed temp and
# fence can be removed.
partial_record="$TEST_ROOT/crash-partial-record"
make_root "$partial_record"
partial_record_ready="$TEST_ROOT/crash-partial-record.ready"
partial_record_command=(python3 "$WORKER" normal "$partial_record_ready")
expect_failure "fork before partial record" env \
  YANO_MANAGED_PROCESS_TEST_STOP_AFTER=fork \
  python3 "$TOOL" start --runtime-root "$partial_record" --exec-timeout 2 -- \
    "${partial_record_command[@]}"
printf '{"incomplete":' > "$partial_record/.host-ui.process.tmp"
chmod 0600 "$partial_record/.host-ui.process.tmp"
assert_stopped "$partial_record" "${partial_record_command[@]}"
[ ! -e "$partial_record/.host-ui.process.tmp" ] \
  || fail "partial process-record temporary was not reconciled"
[ ! -e "$partial_record/.host-ui.launch.json" ] \
  || fail "forked fence remained after partial process-record recovery"

# If the forked->published fence write is killed before fsync, its malformed
# temp coexists with the already durable unfinalized record and old forked
# fence. Discard only that exact temp, then reconcile the matching gated child,
# record, and fence as one known pre-gate state.
truncated="$TEST_ROOT/crash-truncated-published-fence"
make_root "$truncated"
truncated_ready="$TEST_ROOT/crash-truncated-published-fence.ready"
truncated_command=(python3 "$WORKER" normal "$truncated_ready")
expect_failure "record publication before truncated fence" env \
  YANO_MANAGED_PROCESS_TEST_STOP_AFTER=record-published \
  python3 "$TOOL" start --runtime-root "$truncated" --exec-timeout 2 -- \
    "${truncated_command[@]}"
printf '{"incomplete":' > "$truncated/.host-ui.launch.tmp"
chmod 0600 "$truncated/.host-ui.launch.tmp"
assert_stopped "$truncated" "${truncated_command[@]}"
[ ! -e "$truncated/.host-ui.launch.tmp" ] \
  || fail "truncated published-fence temporary was not reconciled"
[ ! -e "$truncated/.host-ui.launch.json" ] \
  || fail "forked fence remained after truncated published-fence recovery"
[ ! -e "$truncated/host-ui.process.json" ] \
  || fail "unfinalized record remained after truncated published-fence recovery"

gate="$TEST_ROOT/crash-gate"
make_root "$gate"
gate_ready="$TEST_ROOT/crash-gate.ready"
gate_command=(python3 "$WORKER" normal "$gate_ready")
expect_failure "crash injection after gate" env \
  YANO_MANAGED_PROCESS_TEST_STOP_AFTER=gate \
  python3 "$TOOL" start --runtime-root "$gate" --exec-timeout 2 -- \
    "${gate_command[@]}"
deadline=$((SECONDS + 3))
gate_status=""
while [ "$SECONDS" -lt "$deadline" ]; do
  if gate_status="$(status_process "$gate" "${gate_command[@]}" 2>/dev/null)"; then break; fi
  sleep 0.05
done
[[ "$gate_status" =~ ^running\ [1-9][0-9]*$ ]] || fail "post-gate launch did not recover as running"
gate_pid="${gate_status#running }"
echo "$gate_pid" >> "$PIDS_FILE"
stop_process "$gate" "${gate_command[@]}"

for point in record-update-temp record-update-renamed; do
  crash="$TEST_ROOT/crash-$point"
  make_root "$crash"
  crash_ready="$TEST_ROOT/crash-$point.ready"
  crash_command=(python3 "$WORKER" normal "$crash_ready")
  expect_failure "atomic record crash at $point" env \
    YANO_MANAGED_PROCESS_TEST_STOP_AFTER="$point" \
    python3 "$TOOL" start --runtime-root "$crash" --exec-timeout 2 -- \
      "${crash_command[@]}"
  deadline=$((SECONDS + 3))
  crash_status=""
  while [ "$SECONDS" -lt "$deadline" ]; do
    if crash_status="$(status_process "$crash" "${crash_command[@]}" 2>/dev/null)"; then break; fi
    sleep 0.05
  done
  [[ "$crash_status" =~ ^running\ [1-9][0-9]*$ ]] \
    || fail "$point did not recover the running exact child"
  crash_pid="${crash_status#running }"
  echo "$crash_pid" >> "$PIDS_FILE"
  stop_process "$crash" "${crash_command[@]}"
  [ ! -e "$crash/.host-ui.process-update.tmp" ] \
    || fail "$point update temporary was not recovered"
done

# Ordinary startup failure after releasing the gate stops the exact child and
# cleans its artifacts.  If absence cannot be proven, both child and durable
# evidence remain fail-closed for a later validated stop.
failed="$TEST_ROOT/ordinary-failure"
make_root "$failed"
failed_ready="$TEST_ROOT/ordinary-failure.ready"
failed_command=(python3 "$WORKER" normal "$failed_ready")
expect_failure "ordinary post-gate failure" env \
  YANO_MANAGED_PROCESS_TEST_FAIL_AFTER=gate \
  python3 "$TOOL" start --runtime-root "$failed" --exec-timeout 2 -- \
    "${failed_command[@]}"
assert_stopped "$failed" "${failed_command[@]}"
[ ! -e "$failed/.host-ui.launch.json" ] || fail "proven failure retained its fence"
[ ! -e "$failed/host-ui.process.json" ] || fail "proven failure retained its record"

unproven="$TEST_ROOT/unproven-failure"
make_root "$unproven"
unproven_ready="$TEST_ROOT/unproven-failure.ready"
unproven_command=(python3 "$WORKER" normal "$unproven_ready")
expect_failure "unproven post-gate cleanup" env \
  YANO_MANAGED_PROCESS_TEST_FAIL_AFTER=gate \
  YANO_MANAGED_PROCESS_TEST_CLEANUP_UNPROVEN=1 \
  python3 "$TOOL" start --runtime-root "$unproven" --exec-timeout 2 -- \
    "${unproven_command[@]}"
[ -f "$unproven/.host-ui.launch.json" ] || fail "unproven cleanup lost its fence"
[ -f "$unproven/host-ui.process.json" ] || fail "unproven cleanup lost its record"
unproven_pid="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["pid"])' \
  "$unproven/host-ui.process.json")"
echo "$unproven_pid" >> "$PIDS_FILE"
kill -0 "$unproven_pid" || fail "unproven child was not preserved"
stop_process "$unproven" "${unproven_command[@]}"
[ ! -e "$unproven/.host-ui.launch.json" ] || fail "validated retry retained its fence"
[ ! -e "$unproven/host-ui.process.json" ] || fail "validated retry retained its record"

# Unsafe runtime roots and artifacts are rejected without silent repair.
unsafe_mode="$TEST_ROOT/unsafe-mode"
mkdir -m 0755 "$unsafe_mode"
expect_failure "permissive runtime root" \
  start_process "$unsafe_mode" /bin/sleep 30
[ "$(python3 -c 'import os,stat,sys; print(oct(stat.S_IMODE(os.stat(sys.argv[1]).st_mode)))' "$unsafe_mode")" = 0o755 ] \
  || fail "runtime root mode was silently repaired"

real_root="$TEST_ROOT/real-root"
make_root "$real_root"
ln -s "$real_root" "$TEST_ROOT/root-link"
expect_failure "symlink runtime root" \
  start_process "$TEST_ROOT/root-link" /bin/sleep 30

log_link="$TEST_ROOT/log-link"
make_root "$log_link"
touch "$TEST_ROOT/external.log"
chmod 0600 "$TEST_ROOT/external.log"
ln -s "$TEST_ROOT/external.log" "$log_link/host-ui.log"
expect_failure "symlink host UI log" start_process "$log_link" /bin/sleep 30

mode_record="$TEST_ROOT/mode-record"
make_root "$mode_record"
mode_ready="$TEST_ROOT/mode.ready"
mode_command=(python3 "$WORKER" normal "$mode_ready")
mode_pid="$(start_process "$mode_record" "${mode_command[@]}")"
echo "$mode_pid" >> "$PIDS_FILE"
chmod 0644 "$mode_record/host-ui.process.json"
expect_failure "permissive process record" status_process "$mode_record" "${mode_command[@]}"
kill -0 "$mode_pid" || fail "unsafe record validation disturbed the process"
kill -KILL "$mode_pid"

hard_record="$TEST_ROOT/hard-record"
make_root "$hard_record"
hard_ready="$TEST_ROOT/hard.ready"
hard_command=(python3 "$WORKER" normal "$hard_ready")
hard_pid="$(start_process "$hard_record" "${hard_command[@]}")"
echo "$hard_pid" >> "$PIDS_FILE"
ln "$hard_record/host-ui.process.json" "$TEST_ROOT/second-record-link"
expect_failure "hard-linked process record" status_process "$hard_record" "${hard_command[@]}"
kill -0 "$hard_pid" || fail "hard-link validation disturbed the process"
kill -KILL "$hard_pid"

symlink_record="$TEST_ROOT/symlink-record"
make_root "$symlink_record"
printf '{}\n' > "$TEST_ROOT/external-record"
chmod 0600 "$TEST_ROOT/external-record"
ln -s "$TEST_ROOT/external-record" "$symlink_record/host-ui.process.json"
expect_failure "symlink process record" status_process "$symlink_record" /bin/sleep 30

echo "managed-process-test: PASS"
