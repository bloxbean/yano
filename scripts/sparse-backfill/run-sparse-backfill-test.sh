#!/bin/bash
# Sparse-backfill regression runner (see .claude/skills/test-sparse-backfill/SKILL.md).
#
# Validates fast devnet backfill across values of
# yano.block-producer.backfill-block-interval-slots, and proves a downstream Haskell
# cardano-node can sync the resulting history from genesis and keep following live blocks.
#
#   ./scripts/sparse-backfill/run-sparse-backfill-test.sh                    # devkit matrix
#   ./scripts/sparse-backfill/run-sparse-backfill-test.sh --cases auto
#   ./scripts/sparse-backfill/run-sparse-backfill-test.sh --cases all --slot-leader
#   ./scripts/sparse-backfill/run-sparse-backfill-test.sh --no-haskell       # Yano-only smoke
#
# Isolation: every case gets its own genesis copy, chainstate, history archive, Haskell
# database, socket, logs and probed ports. Only processes started here are stopped.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
TOOLS="$SCRIPT_DIR/tools"
# shellcheck source=lib/common.sh
source "$SCRIPT_DIR/lib/common.sh"
# shellcheck source=lib/yano.sh
source "$SCRIPT_DIR/lib/yano.sh"
# shellcheck source=lib/haskell.sh
source "$SCRIPT_DIR/lib/haskell.sh"

# --- defaults ---------------------------------------------------------------
CASES="dense,interval2,auto,auto-1s,reject"
WITH_SLOT_LEADER=0
WITH_HASKELL=1
EPOCHS=3
EPOCH_LENGTH=1200
SECURITY_PARAM=100
ACTIVE_SLOTS_COEFF=1.0
SLOTS_PER_KES_PERIOD=0   # 0 = keep the genesis value; Sum6KES covers only 64 periods
SOURCE_GENESIS="$PROJECT_ROOT/app/config/network/devnet/pv10"
YANO_JAR="$PROJECT_ROOT/app/build/yano.jar"
MAGIC=42
PORT_BASE=21000
SEQUENTIAL_SECONDS=3        # scheduled sequential blocks before catch-up
LIVE_FOLLOW_SECONDS=12      # Haskell live-follow observation window
SKIP_BUILD=0
LEGACY_SLOT_LENGTH_MILLIS=""
RUN_DIR=""
# Slot-leader scenario genesis: 3k/f and 10k/f must both stay <= epochLength or
# cardano-node refuses the Shelley genesis before any block is exchanged.
SL_SECURITY_PARAM=50
SL_ACTIVE_SLOTS_COEFF=0.5

usage() {
  sed -n '2,15p' "$0"
  cat <<'USAGE'
Options:
  --cases <list>       dense,interval2,auto,auto-1s,reject | all (default: all but slot-leader)
  --slot-leader        also run the optional slot-leader scenario
  --no-haskell         skip the downstream Haskell node (Yano-side checks only)
  --run-dir <dir>      output directory (default: test-data-dir/sparse-backfill/<timestamp>)
  --epochs <n>         epochs to shift genesis back (default: 3)
  --epoch-length <n>   slots per epoch (default: 1200; mainnet shape is 432000)
  --security-param <k> Shelley securityParam; automatic spacing is floor(3k/f)-1 (default: 100)
  --slots-per-kes-period <n>  override slotsPerKESPeriod (Sum6KES signs only 64 periods total)
  --skip-build         do not rebuild the uber-jar even if sources are newer
  --legacy-slot-length-millis <n>  pass the obsolete property; verify genesis still determines slot duration
  -h, --help
USAGE
}

while [ $# -gt 0 ]; do
  case "$1" in
    --cases) CASES="$2"; shift 2 ;;
    --slot-leader) WITH_SLOT_LEADER=1; shift ;;
    --no-haskell) WITH_HASKELL=0; shift ;;
    --run-dir) RUN_DIR="$2"; shift 2 ;;
    --epochs) EPOCHS="$2"; shift 2 ;;
    --epoch-length) EPOCH_LENGTH="$2"; shift 2 ;;
    --security-param) SECURITY_PARAM="$2"; shift 2 ;;
    --slots-per-kes-period) SLOTS_PER_KES_PERIOD="$2"; shift 2 ;;
    --skip-build) SKIP_BUILD=1; shift ;;
    --legacy-slot-length-millis) LEGACY_SLOT_LENGTH_MILLIS="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown option: $1 (try --help)" ;;
  esac
done
[ "$CASES" = "all" ] && CASES="dense,interval2,auto,auto-1s,reject"
[ "$WITH_SLOT_LEADER" = "1" ] && CASES="$CASES,slot-leader"

if [ -z "$RUN_DIR" ]; then
  RUN_DIR="$PROJECT_ROOT/test-data-dir/sparse-backfill/$(date +%Y%m%d-%H%M%S)"
fi
mkdir -p "$RUN_DIR"
# Absolute from here on: each node runs with cwd = its case directory, so a relative
# run-dir would make -Dyano.storage.path resolve inside the case dir and RocksDB would
# fail with "While mkdir if missing: ... No such file or directory".
RUN_DIR="$(cd "$RUN_DIR" && pwd)"
PID_FILE="$RUN_DIR/started-pids"
: > "$PID_FILE"
export PID_FILE YANO_JAR PROJECT_ROOT

trap 'log "cleanup: stopping processes started by this run"; cleanup_tracked' EXIT INT TERM

# --- pre-flight -------------------------------------------------------------
command -v java >/dev/null || die "java not on PATH"
command -v curl >/dev/null || die "curl not on PATH"
command -v python3 >/dev/null || die "python3 not on PATH"
[ -d "$SOURCE_GENESIS" ] || die "genesis source not found: $SOURCE_GENESIS"

if [ ! -f "$YANO_JAR" ]; then
  [ "$SKIP_BUILD" = "1" ] && die "jar missing: $YANO_JAR"
  log "building uber-jar (:app:quarkusBuild)"
  (cd "$PROJECT_ROOT" && ./gradlew :app:quarkusBuild) || die "build failed"
elif [ "$SKIP_BUILD" != "1" ]; then
  STALE=$(find "$PROJECT_ROOT/runtime" "$PROJECT_ROOT/core-api" "$PROJECT_ROOT/app" \
    -name '*.java' -newer "$YANO_JAR" -print -quit 2>/dev/null)
  if [ -n "$STALE" ]; then
    log "jar is older than $STALE — rebuilding so the test exercises the working tree"
    (cd "$PROJECT_ROOT" && ./gradlew :app:quarkusBuild) || die "build failed"
  fi
fi

log "expected-slot helper self-test"
python3 "$TOOLS/backfill_expect.py" --self-test || die "backfill_expect self-test failed — the runtime algorithm changed"

GENESIS_BEFORE=$(cd "$PROJECT_ROOT" && git status --porcelain app/config)
log "run directory: $RUN_DIR"

# --- per-case helpers -------------------------------------------------------
# The cursor lives in a file: next_port is called from command substitution, so a
# shell variable would be incremented in a subshell and lost, handing two services
# the same port.
PORT_STATE="$RUN_DIR/.port-cursor"
echo "$PORT_BASE" > "$PORT_STATE"
next_port() {
  local cursor port
  cursor=$(cat "$PORT_STATE")
  port=$(pick_port "$cursor")
  echo $((port + 1)) > "$PORT_STATE"
  echo "$port"
}

record_crash_check() { # <case-dir> <stage> <work-dir> <log-file>...
  local case_dir="$1" stage="$2" work_dir="$3"; shift 3
  local detail
  if detail=$(jvm_crash_detail "$work_dir" "$@"); then
    record_stage "$case_dir" "$stage" FAIL "JVM crashed: $detail"
  else
    record_stage "$case_dir" "$stage" PASS "no JVM crash"
  fi
}

record_stage() { # <case-dir> <stage> <status> <detail>
  printf '%s\t%s\t%s\n' "$2" "$3" "${4:-}" >> "$1/stages.tsv"
  case "$3" in
    PASS) log "  [PASS] $2" ;;
    NOTE) log "  [NOTE] $2 ${4:-}" ;;
    *)    warn "  [$3] $2 ${4:-}" ;;
  esac
}

write_meta() { # <case-dir> <json-body>
  printf '%s\n' "$2" > "$1/meta.json"
}

case_status() { # <case-dir>
  local file="$1/stages.tsv"
  [ -f "$file" ] || { echo "BLOCKED"; return; }
  if grep -q $'\tFAIL\t' "$file"; then echo FAIL
  elif grep -q $'\tBLOCKED\t' "$file"; then echo BLOCKED
  else echo PASS; fi
}

# --- main backfill case -----------------------------------------------------
run_backfill_case() { # <name> <requested-interval> <slot-length-seconds> <restart:0|1>
  local name="$1" requested="$2" slot_len="$3" do_restart="$4"
  local case_dir="$RUN_DIR/case-$name"
  local genesis="$case_dir/genesis"
  mkdir -p "$case_dir"
  : > "$case_dir/stages.tsv"
  log "=== case $name: backfill-block-interval-slots=$requested, slotLength=${slot_len}s ==="

  python3 "$TOOLS/prepare_genesis.py" --source "$SOURCE_GENESIS" --dest "$genesis" \
    --slot-length "$slot_len" --epoch-length "$EPOCH_LENGTH" \
    --security-param "$SECURITY_PARAM" --active-slots-coeff "$ACTIVE_SLOTS_COEFF" \
    --epochs "$EPOCHS" --slots-per-kes-period "$SLOTS_PER_KES_PERIOD" \
    > "$case_dir/genesis-params.json" \
    || { record_stage "$case_dir" genesis-prep FAIL "prepare_genesis.py failed"; return; }
  record_stage "$case_dir" genesis-prep PASS

  local slot_ms expected_shift expected_target bounds_ok
  slot_ms=$(json_get "$case_dir/genesis-params.json" slot_length_millis)
  expected_shift=$(json_get "$case_dir/genesis-params.json" expected_shift_millis)
  expected_target=$(json_get "$case_dir/genesis-params.json" expected_target_slot)
  bounds_ok=$(json_get "$case_dir/genesis-params.json" genesis_bounds_ok)
  [ "$bounds_ok" = "True" ] || record_stage "$case_dir" genesis-bounds FAIL \
    "epochLength=$EPOCH_LENGTH is too short for k=$SECURITY_PARAM/f=$ACTIVE_SLOTS_COEFF"

  local http n2n hnode ekg prom
  http=$(next_port); n2n=$(next_port); hnode=$(next_port); ekg=$(next_port); prom=$(next_port)
  log "ports: http=$http n2n=$n2n haskell=$hnode ekg=$ekg prometheus=$prom"

  local yano_log="$case_dir/yano.log" pid
  pid=$(start_yano "$case_dir" "$genesis" "$http" "$n2n" "$requested" past-time-travel \
    "$yano_log" "ptt")
  if ! wait_yano_ready "$http" 180 "$yano_log" "$pid"; then
    record_stage "$case_dir" yano-start FAIL "node did not become ready (see yano.log)"
    stop_tracked_pid "$pid" "yano:$name" 60
    return
  fi
  record_stage "$case_dir" yano-start PASS
  if wait_for_log "$yano_log" "Past time travel mode" 30 "yano.log"; then
    record_stage "$case_dir" past-time-travel-deferred PASS
  else
    record_stage "$case_dir" past-time-travel-deferred FAIL "deferral line not logged"
  fi

  curl -sS "http://127.0.0.1:$http/api/v1/node/config" > "$case_dir/node-config.json" 2>/dev/null

  # --- epoch shift ---------------------------------------------------------
  local shift_start shift_elapsed
  shift_start=$(now_ms)
  http_status_and_body "http://127.0.0.1:$http/api/v1/devnet/epochs/shift" POST \
    "{\"epochs\": $EPOCHS}" "$case_dir/shift.json" > "$case_dir/shift.status"
  shift_elapsed=$(( $(now_ms) - shift_start ))
  local shift_status shift_millis genesis_slot
  shift_status=$(cat "$case_dir/shift.status")
  if [ "$shift_status" != "200" ]; then
    record_stage "$case_dir" epoch-shift FAIL "HTTP $shift_status: $(head -c 300 "$case_dir/shift.json")"
    stop_tracked_pid "$pid" "yano:$name" 60
    return
  fi
  shift_millis=$(json_get "$case_dir/shift.json" shift_millis)
  genesis_slot=$(json_get "$case_dir/shift.json" genesis_slot)
  if [ "$shift_millis" = "$expected_shift" ] && [ "$genesis_slot" = "0" ]; then
    record_stage "$case_dir" epoch-shift PASS "shift_millis=$shift_millis genesis_slot=0"
  else
    record_stage "$case_dir" epoch-shift FAIL \
      "shift_millis=$shift_millis (expected $expected_shift), genesis_slot=$genesis_slot"
  fi

  # Let the scheduler forge a few sequential blocks so the catch-up starts from a
  # non-zero tip — the derivation must handle it without hardcoded numbers.
  sleep "$SEQUENTIAL_SECONDS"
  curl -sS "http://127.0.0.1:$http/api/v1/node/tip" > "$case_dir/pre-catchup-tip.json" 2>/dev/null

  # --- catch-up ------------------------------------------------------------
  local t0 t1 elapsed
  t0=$(now_ms)
  http_status_and_body "http://127.0.0.1:$http/api/v1/devnet/epochs/catch-up" POST "" \
    "$case_dir/catchup.json" > "$case_dir/catchup.status"
  t1=$(now_ms)
  elapsed=$((t1 - t0))
  if [ "$(cat "$case_dir/catchup.status")" != "200" ]; then
    record_stage "$case_dir" catch-up FAIL \
      "HTTP $(cat "$case_dir/catchup.status"): $(head -c 300 "$case_dir/catchup.json")"
    stop_tracked_pid "$pid" "yano:$name" 60
    return
  fi
  record_stage "$case_dir" catch-up PASS "elapsed=${elapsed}ms"

  if python3 "$TOOLS/verify_chain.py" \
      --base-url "http://127.0.0.1:$http" \
      --yano-log "$yano_log" \
      --catchup-json "$case_dir/catchup.json" \
      --requested-interval "$requested" \
      --security-param "$SECURITY_PARAM" \
      --active-slots-coeff "$ACTIVE_SLOTS_COEFF" \
      --epoch-length "$EPOCH_LENGTH" \
      --slot-length-ms "$slot_ms" \
      --elapsed-ms "$elapsed" \
      --out "$case_dir/verify.json" > "$case_dir/verify.log" 2>&1; then
    record_stage "$case_dir" chain-verification PASS
  else
    record_stage "$case_dir" chain-verification FAIL "see verify.log"
  fi
  cat "$case_dir/verify.log"

  local produced_blocks target_slot epoch_starts expect_slots
  produced_blocks=$(json_get "$case_dir/catchup.json" blocks_produced 2>/dev/null || echo 0)
  target_slot=$(python3 -c "import json;print(json.load(open('$case_dir/verify.json'))['target_slot'])" 2>/dev/null || echo "$expected_target")
  epoch_starts=$(python3 -c "import json;print(','.join(str(s) for s in json.load(open('$case_dir/verify.json'))['epoch_start_slots']))" 2>/dev/null || echo "")
  expect_slots="0${epoch_starts:+,$epoch_starts},$target_slot"

  # --- downstream Haskell node ---------------------------------------------
  local hpid=0 haskell_elapsed=0 hdir="$case_dir/haskell"
  if [ "$WITH_HASKELL" = "1" ]; then
    ensure_haskell_binary > "$case_dir/haskell-version.txt" 2>&1
    create_haskell_instance "$hdir" "$genesis" "$n2n" "$ekg" "$prom"
    # Same bytes on both sides, proven not assumed.
    if [ "$(shasum -a 256 "$genesis/shelley-genesis.json" | cut -d' ' -f1)" = \
         "$(shasum -a 256 "$hdir/files/shelley-genesis.json" | cut -d' ' -f1)" ]; then
      record_stage "$case_dir" genesis-bytes-identical PASS
    else
      record_stage "$case_dir" genesis-bytes-identical FAIL "shelley-genesis.json differs"
    fi
    shasum -a 256 "$genesis"/*.json "$hdir/files"/*.json > "$case_dir/genesis-sha256.txt"

    local hstart
    hstart=$(now_ms)
    hpid=$(start_haskell_instance "$hdir" "$hnode" "$case_dir/haskell.log" "$name")
    if ! wait_haskell_socket "$hdir" 120 "$hpid"; then
      record_stage "$case_dir" haskell-start FAIL "socket never appeared (see haskell.log)"
    else
      record_stage "$case_dir" haskell-start PASS
      # ~50 blocks/s is a conservative floor for bulk sync of sparse history;
      # the stall detector inside wait_haskell_slot ends a genuinely stuck sync early.
      local hsync_cap
      hsync_cap=$(python3 -c "print(max(300, int($produced_blocks / 20) + 120))")
      log "haskell sync cap: ${hsync_cap}s for $produced_blocks blocks"
      if wait_haskell_slot "$hdir" "$MAGIC" "$target_slot" "$hsync_cap"; then
        haskell_elapsed=$(( $(now_ms) - hstart ))
        record_stage "$case_dir" haskell-sync PASS "elapsed=${haskell_elapsed}ms"
      else
        haskell_elapsed=$(( $(now_ms) - hstart ))
        record_stage "$case_dir" haskell-sync FAIL "did not reach slot $target_slot"
      fi
      if python3 "$TOOLS/haskell_check.py" --haskell-log "$case_dir/haskell.log" \
          --cli "$HASKELL_SHARED_DIR/bin/cardano-cli" --node-dir "$hdir" \
          --magic "$MAGIC" --yano-base-url "http://127.0.0.1:$http" \
          --expect-slots "$expect_slots" --phase initial --reach-slot "$target_slot" \
          --out "$case_dir/haskell-initial.json" > "$case_dir/haskell-initial.log" 2>&1; then
        record_stage "$case_dir" haskell-history-match PASS
      else
        record_stage "$case_dir" haskell-history-match FAIL "see haskell-initial.log"
      fi
      cat "$case_dir/haskell-initial.log"

      log "observing live follow for ${LIVE_FOLLOW_SECONDS}s"
      sleep "$LIVE_FOLLOW_SECONDS"
      if python3 "$TOOLS/haskell_check.py" --haskell-log "$case_dir/haskell.log" \
          --cli "$HASKELL_SHARED_DIR/bin/cardano-cli" --node-dir "$hdir" \
          --magic "$MAGIC" --yano-base-url "http://127.0.0.1:$http" \
          --expect-slots "$expect_slots" --phase live --min-live-slot "$target_slot" \
          --out "$case_dir/haskell-live.json" > "$case_dir/haskell-live.log" 2>&1; then
        record_stage "$case_dir" haskell-live-follow PASS
      else
        record_stage "$case_dir" haskell-live-follow FAIL "see haskell-live.log"
      fi
      cat "$case_dir/haskell-live.log"
    fi
  fi

  # --- graceful restart ----------------------------------------------------
  if [ "$do_restart" = "1" ]; then
    curl -sS "http://127.0.0.1:$http/api/v1/node/tip" > "$case_dir/pre-restart-tip.json" 2>/dev/null
    if stop_tracked_pid "$pid" "yano:$name" 60; then
      record_stage "$case_dir" graceful-shutdown PASS
      record_crash_check "$case_dir" shutdown-clean "$case_dir" "$yano_log"
      # past-time-travel is a first-boot mode: ProducerStartupPlan defers whenever the
      # flag is set, so the restart runs the regular devnet producer against the same
      # chainstate and the already-shifted systemStart in the case genesis copy.
      local pid2
      pid2=$(start_yano "$case_dir" "$genesis" "$http" "$n2n" "$requested" live \
        "$case_dir/yano-restart.log" "restart")
      if wait_yano_ready "$http" 180 "$case_dir/yano-restart.log" "$pid2"; then
        if grep -q "Block producer resuming from existing tip" "$case_dir/yano-restart.log"; then
          record_stage "$case_dir" restart-resume PASS
        else
          record_stage "$case_dir" restart-resume FAIL "no resume-from-tip line in the new log"
        fi
        sleep 6
        curl -sS "http://127.0.0.1:$http/api/v1/node/tip" > "$case_dir/post-restart-tip.json" 2>/dev/null
        if python3 - "$case_dir/pre-restart-tip.json" "$case_dir/post-restart-tip.json" <<'PY'
import json, sys
before = json.load(open(sys.argv[1]))
after = json.load(open(sys.argv[2]))
sys.exit(0 if after["blockNumber"] > before["blockNumber"] and after["slot"] > before["slot"] else 1)
PY
        then
          record_stage "$case_dir" restart-live-production PASS
        else
          record_stage "$case_dir" restart-live-production FAIL "tip did not advance after restart"
        fi
        if [ "$WITH_HASKELL" = "1" ] && [ "$hpid" != "0" ] && kill -0 "$hpid" 2>/dev/null; then
          sleep "$LIVE_FOLLOW_SECONDS"
          local restart_min
          restart_min=$(json_get "$case_dir/pre-restart-tip.json" slot)
          if python3 "$TOOLS/haskell_check.py" --haskell-log "$case_dir/haskell.log" \
              --cli "$HASKELL_SHARED_DIR/bin/cardano-cli" --node-dir "$hdir" \
              --magic "$MAGIC" --yano-base-url "http://127.0.0.1:$http" \
              --expect-slots "$expect_slots" --phase live --min-live-slot "$restart_min" \
              --out "$case_dir/haskell-post-restart.json" \
              > "$case_dir/haskell-post-restart.log" 2>&1; then
            record_stage "$case_dir" haskell-follow-after-restart PASS
          else
            record_stage "$case_dir" haskell-follow-after-restart FAIL "see haskell-post-restart.log"
          fi
        fi
      else
        record_stage "$case_dir" restart-resume FAIL "node did not become ready after restart"
      fi
      pid="$pid2"
    else
      record_stage "$case_dir" graceful-shutdown BLOCKED \
        "SIGTERM did not stop the node within 60s (DuckDB/ducklake flush?); SIGKILL used"
    fi
  fi

  [ "$hpid" != "0" ] && stop_tracked_pid "$hpid" "haskell:$name" 60
  stop_tracked_pid "$pid" "yano:$name" 60
  record_crash_check "$case_dir" final-shutdown-clean "$case_dir" \
    "$yano_log" "$case_dir/yano-restart.log"

  write_meta "$case_dir" "$(python3 - <<PY
import json
print(json.dumps({
  "case": "$name", "kind": "backfill",
  "requested_interval": $requested, "slot_length_ms": $slot_ms,
  "epoch_length": $EPOCH_LENGTH, "security_param": $SECURITY_PARAM,
  "active_slots_coeff": $ACTIVE_SLOTS_COEFF, "epochs_shifted": $EPOCHS,
  "shift_millis": $shift_millis, "shift_api_millis": $shift_elapsed,
  "catch_up_elapsed_ms": $elapsed, "haskell_sync_elapsed_ms": $haskell_elapsed,
  "haskell_enabled": $WITH_HASKELL, "restart_checked": $do_restart,
  "ports": {"http": $http, "n2n": $n2n, "haskell": $hnode}
}, indent=2))
PY
)"
  log "case $name: $(case_status "$case_dir")"
}

# --- rejection case ---------------------------------------------------------
run_reject_case() {
  local case_dir="$RUN_DIR/case-reject"
  mkdir -p "$case_dir"
  : > "$case_dir/stages.tsv"
  local window
  window=$(python3 -c "print(int(3*$SECURITY_PARAM/$ACTIVE_SLOTS_COEFF))")
  log "=== case reject: negative interval and interval == forecast window ($window) ==="

  run_reject_startup() { # <sub> <interval> <mode> <expected-message>
    local sub="$1" interval="$2" mode="$3" expect="$4"
    local dir="$case_dir/$sub"
    mkdir -p "$dir"
    python3 "$TOOLS/prepare_genesis.py" --source "$SOURCE_GENESIS" --dest "$dir/genesis" \
      --slot-length 0.3 --epoch-length "$EPOCH_LENGTH" --security-param "$SECURITY_PARAM" \
      --active-slots-coeff "$ACTIVE_SLOTS_COEFF" --epochs "$EPOCHS" > "$dir/genesis-params.json"
    local http n2n pid
    http=$(next_port); n2n=$(next_port)
    pid=$(start_yano "$dir" "$dir/genesis" "$http" "$n2n" "$interval" "$mode" "$dir/yano.log" "$sub")

    # The node reports YANO_STARTUP_FAILURE and refuses to initialize, but it does NOT
    # exit the JVM: a plugin-metrics-cache thread retries ensureYano() every second.
    # The assertion is therefore on the message and on production never starting.
    if wait_for_log "$dir/yano.log" "$expect" 120 "$sub/yano.log"; then
      record_stage "$case_dir" "$sub-rejected-with-error" PASS \
        "$(grep -m1 -o "$expect.\{0,70\}" "$dir/yano.log" | head -1)"
    else
      record_stage "$case_dir" "$sub-rejected-with-error" FAIL \
        "'$expect' never logged; last lines: $(tail -3 "$dir/yano.log" | tr '\n' ' ' | head -c 300)"
    fi
    if grep -q "Block producer started" "$dir/yano.log"; then
      record_stage "$case_dir" "$sub-production-blocked" FAIL "block production started despite the invalid interval"
    else
      record_stage "$case_dir" "$sub-production-blocked" PASS "no block producer started"
    fi
    if kill -0 "$pid" 2>/dev/null; then
      record_stage "$case_dir" "$sub-process-exit" NOTE \
        "JVM stays alive after the startup failure and retries ensureYano() every second (SKILL.md 'Code vs. expectations' #8)"
    else
      record_stage "$case_dir" "$sub-process-exit" NOTE "JVM exited on its own"
    fi
    stop_tracked_pid "$pid" "yano:$sub" 30

    # A rejected configuration must fail cleanly. A JVM-level crash on this path is a
    # defect in its own right, so it is reported, never folded into "the node stopped".
    # Checked after shutdown so a crash during teardown is caught too (see #8: an
    # intermittent SIGSEGV in librocksdbjni RocksDB_iterator was observed here).
    record_crash_check "$case_dir" "$sub-clean-failure" "$dir" "$dir/yano.log"
  }

  # (a) negative interval — rejected by YanoConfig.validate() at startup
  run_reject_startup negative -1 past-time-travel "Backfill block interval must be non-negative"
  # (c) interval == forecast window without past-time-travel — rejected at producer creation
  run_reject_startup limit-live "$window" live "forecast window"

  # (b) interval == forecast window WITH past-time-travel — the producer is created
  # during /epochs/shift, so the rejection only surfaces on that call.
  local dir="$case_dir/limit-ptt" http n2n pid status
  mkdir -p "$dir"
  python3 "$TOOLS/prepare_genesis.py" --source "$SOURCE_GENESIS" --dest "$dir/genesis" \
    --slot-length 0.3 --epoch-length "$EPOCH_LENGTH" --security-param "$SECURITY_PARAM" \
    --active-slots-coeff "$ACTIVE_SLOTS_COEFF" --epochs "$EPOCHS" > "$dir/genesis-params.json"
  http=$(next_port); n2n=$(next_port)
  pid=$(start_yano "$dir" "$dir/genesis" "$http" "$n2n" "$window" past-time-travel "$dir/yano.log" "limit-ptt")
  if wait_yano_ready "$http" 180 "$dir/yano.log" "$pid"; then
    http_status_and_body "http://127.0.0.1:$http/api/v1/devnet/epochs/shift" POST \
      "{\"epochs\": $EPOCHS}" "$dir/shift.json" > "$dir/shift.status"
    status=$(cat "$dir/shift.status")
    if [ "$status" -ge 400 ] 2>/dev/null && grep -qi "forecast window" "$dir/shift.json" \
        && grep -q "$window" "$dir/shift.json"; then
      record_stage "$case_dir" limit-ptt-shift-rejected PASS \
        "HTTP $status: $(head -c 200 "$dir/shift.json")"
    else
      record_stage "$case_dir" limit-ptt-shift-rejected FAIL \
        "HTTP $status: $(head -c 300 "$dir/shift.json")"
    fi
    record_stage "$case_dir" limit-ptt-late-rejection-note PASS \
      "explicit out-of-range interval is only rejected at /epochs/shift in past-time-travel mode (HTTP $status)"
  else
    record_stage "$case_dir" limit-ptt-shift-rejected FAIL "node did not start"
  fi
  stop_tracked_pid "$pid" "yano:limit-ptt" 60

  write_meta "$case_dir" "$(python3 - <<PY
import json
print(json.dumps({"case": "reject", "kind": "rejection", "forecast_window_slots": $window,
                  "requested_interval": "-1 and $window", "slot_length_ms": 300}, indent=2))
PY
)"
  log "case reject: $(case_status "$case_dir")"
}

slot_leader_meta() { # <slot-ms> <catch-up-elapsed-ms or empty>
  python3 - "$1" "${2:-}" "$SL_SECURITY_PARAM" "$SL_ACTIVE_SLOTS_COEFF" <<'META'
import json, sys
slot_ms, elapsed, k, f = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
meta = {"case": "slot-leader", "kind": "slot-leader", "requested_interval": 0,
        "slot_length_ms": int(slot_ms), "security_param": int(k),
        "active_slots_coeff": float(f)}
if elapsed:
    meta["catch_up_elapsed_ms"] = int(elapsed)
print(json.dumps(meta, indent=2))
META
}

# --- optional slot-leader scenario -----------------------------------------
run_slot_leader_case() {
  local case_dir="$RUN_DIR/case-slot-leader"
  local genesis="$case_dir/genesis"
  mkdir -p "$case_dir"
  : > "$case_dir/stages.tsv"
  log "=== case slot-leader: past-time-travel slot-leader backfill, k=$SL_SECURITY_PARAM f=$SL_ACTIVE_SLOTS_COEFF ==="

  python3 "$TOOLS/prepare_genesis.py" --source "$SOURCE_GENESIS" --dest "$genesis" \
    --slot-length 0.3 --epoch-length "$EPOCH_LENGTH" --security-param "$SL_SECURITY_PARAM" \
    --active-slots-coeff "$SL_ACTIVE_SLOTS_COEFF" --epochs "$EPOCHS" \
    > "$case_dir/genesis-params.json" \
    || { record_stage "$case_dir" genesis-prep FAIL "prepare_genesis.py failed"; return; }
  if [ "$(json_get "$case_dir/genesis-params.json" genesis_bounds_ok)" != "True" ]; then
    record_stage "$case_dir" genesis-bounds BLOCKED \
      "epochLength=$EPOCH_LENGTH too short for k=$SL_SECURITY_PARAM/f=$SL_ACTIVE_SLOTS_COEFF; cardano-node would refuse the genesis"
    return
  fi
  record_stage "$case_dir" genesis-prep PASS

  local http n2n hnode ekg prom slot_ms expected_target
  http=$(next_port); n2n=$(next_port); hnode=$(next_port); ekg=$(next_port); prom=$(next_port)
  slot_ms=$(json_get "$case_dir/genesis-params.json" slot_length_millis)
  expected_target=$(json_get "$case_dir/genesis-params.json" expected_target_slot)

  local pid
  pid=$(start_yano "$case_dir" "$genesis" "$http" "$n2n" 0 slot-leader-time-travel \
    "$case_dir/yano.log" "slot-leader")
  if ! wait_yano_ready "$http" 180 "$case_dir/yano.log" "$pid"; then
    record_stage "$case_dir" yano-start FAIL "node did not become ready"
    stop_tracked_pid "$pid" "yano:slot-leader" 60
    return
  fi
  record_stage "$case_dir" yano-start PASS

  http_status_and_body "http://127.0.0.1:$http/api/v1/devnet/epochs/shift" POST \
    "{\"epochs\": $EPOCHS}" "$case_dir/shift.json" > "$case_dir/shift.status"
  local status
  status=$(cat "$case_dir/shift.status")
  if [ "$status" != "200" ]; then
    if grep -qi "Canonical block hash is required" "$case_dir/shift.json" "$case_dir/yano.log"; then
      # Known pre-existing bootstrap defect: DevnetGenesisShiftService stores genesis
      # UTXOs before the slot-leader path creates a canonical genesis block.
      record_stage "$case_dir" slot-leader-bootstrap BLOCKED \
        "fresh slot-leader bootstrap fails before backfill: 'Canonical block hash is required' (genesis UTXOs stored before a canonical genesis block exists). NOT worked around by disabling UTXOs."
    else
      record_stage "$case_dir" slot-leader-shift FAIL "HTTP $status: $(head -c 300 "$case_dir/shift.json")"
    fi
    stop_tracked_pid "$pid" "yano:slot-leader" 60
    write_meta "$case_dir" "$(slot_leader_meta "$slot_ms" "")"
    log "case slot-leader: $(case_status "$case_dir")"
    return
  fi
  record_stage "$case_dir" slot-leader-shift PASS

  sleep "$SEQUENTIAL_SECONDS"
  local t0 elapsed
  t0=$(now_ms)
  http_status_and_body "http://127.0.0.1:$http/api/v1/devnet/epochs/catch-up" POST "" \
    "$case_dir/catchup.json" > "$case_dir/catchup.status"
  elapsed=$(( $(now_ms) - t0 ))
  if [ "$(cat "$case_dir/catchup.status")" != "200" ]; then
    record_stage "$case_dir" slot-leader-catch-up FAIL \
      "HTTP $(cat "$case_dir/catchup.status"): $(head -c 300 "$case_dir/catchup.json")"
  else
    record_stage "$case_dir" slot-leader-catch-up PASS "elapsed=${elapsed}ms"
    # Slot-leader backfill forges only ELIGIBLE slots, so the tip may sit behind the
    # processed target and the spacing is a lower bound, not an equality.
    if python3 - "$case_dir" "$EPOCH_LENGTH" "$SL_SECURITY_PARAM" "$SL_ACTIVE_SLOTS_COEFF" \
        "http://127.0.0.1:$http" <<'PY' > "$case_dir/slot-leader-verify.log" 2>&1
import json, re, sys, urllib.request
case_dir, epoch_length, k, f, base = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), float(sys.argv[4]), sys.argv[5]
log = open(case_dir + "/yano.log", errors="replace").read()
done = re.findall(r"Slot-leader backfill complete: blocks=(\d+), checks=(\d+), processedSlot=(\d+), elapsedMillis=(\d+)", log)
if not done:
    print("FAIL: no 'Slot-leader backfill complete' line"); sys.exit(1)
blocks, checks, processed, millis = (int(x) for x in done[-1])
window = int(3 * k / f)
tip = json.load(urllib.request.urlopen(base + "/api/v1/node/tip"))
slots = []
for number in range(0, tip["blockNumber"] + 1):
    block = json.load(urllib.request.urlopen("%s/api/v1/blocks/%d" % (base, number)))
    slots.append(block["slot"])
gaps = [b - a for a, b in zip(slots, slots[1:])]
widest = max(gaps) if gaps else 0
print(json.dumps({"blocks": blocks, "leadership_checks": checks, "processed_slot": processed,
                  "backfill_millis": millis, "tip": tip, "widest_gap": widest,
                  "forecast_window": window}, indent=2))
ok = blocks > 0 and checks > 0 and widest < window
print("PASS" if ok else "FAIL: widest gap %d vs forecast window %d, blocks=%d checks=%d"
      % (widest, window, blocks, checks))
sys.exit(0 if ok else 1)
PY
    then
      record_stage "$case_dir" slot-leader-eligibility PASS
    else
      record_stage "$case_dir" slot-leader-eligibility FAIL "see slot-leader-verify.log"
    fi
    cat "$case_dir/slot-leader-verify.log"
  fi

  if [ "$WITH_HASKELL" = "1" ]; then
    local hdir="$case_dir/haskell" hpid
    ensure_haskell_binary > "$case_dir/haskell-version.txt" 2>&1
    create_haskell_instance "$hdir" "$genesis" "$n2n" "$ekg" "$prom"
    hpid=$(start_haskell_instance "$hdir" "$hnode" "$case_dir/haskell.log" "slot-leader")
    if wait_haskell_socket "$hdir" 120 "$hpid" && \
       wait_haskell_slot "$hdir" "$MAGIC" "$((expected_target / 2))" 900; then
      record_stage "$case_dir" haskell-accepts-slot-leader-history PASS
    else
      record_stage "$case_dir" haskell-accepts-slot-leader-history FAIL "see haskell.log"
    fi
    stop_tracked_pid "$hpid" "haskell:slot-leader" 60
  fi
  stop_tracked_pid "$pid" "yano:slot-leader" 60

  write_meta "$case_dir" "$(slot_leader_meta "$slot_ms" "$elapsed")"
  log "case slot-leader: $(case_status "$case_dir")"
}

# --- dispatch ---------------------------------------------------------------
IFS=',' read -r -a CASE_LIST <<< "$CASES"
for case_name in "${CASE_LIST[@]}"; do
  case "$case_name" in
    dense)       run_backfill_case dense 1 0.3 0 ;;
    interval2)   run_backfill_case interval2 2 0.3 0 ;;
    auto)        run_backfill_case auto 0 0.3 1 ;;
    auto-1s)     run_backfill_case auto-1s 0 1.0 0 ;;
    reject)      run_reject_case ;;
    slot-leader) run_slot_leader_case ;;
    "") ;;
    *) die "unknown case: $case_name" ;;
  esac
done

# --- report -----------------------------------------------------------------
GENESIS_AFTER=$(cd "$PROJECT_ROOT" && git status --porcelain app/config)
if [ "$GENESIS_BEFORE" = "$GENESIS_AFTER" ]; then
  log "tracked genesis fixtures under app/config are unchanged"
else
  warn "app/config changed during the run — the tracked fixtures must stay read-only!"
  (cd "$PROJECT_ROOT" && git status --porcelain app/config) | tee "$RUN_DIR/app-config-dirty.txt"
fi

python3 "$TOOLS/render_report.py" --run-dir "$RUN_DIR" --out "$RUN_DIR/report.md"
cat "$RUN_DIR/report.md"
log "artifacts: $RUN_DIR"

if grep -q '^\*\*Overall: PASS\*\*$' "$RUN_DIR/report.md"; then
  exit 0
fi
exit 1
