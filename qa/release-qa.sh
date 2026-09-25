#!/bin/bash
# Release QA orchestrator: builds Yano once, runs the selected regression tests one
# at a time on isolated ports, and writes results plus a shareable report.
#
#   qa/release-qa.sh                         # l1, appchain, e2e, compat, docker (JVM + native)
#   qa/release-qa.sh --quick                 # JVM variants only
#   qa/release-qa.sh --categories l1,e2e
#   qa/release-qa.sh --only haskell-sync-jvm,compat-jvm
#   qa/release-qa.sh --list
#
# Output: qa/results/<run-id>/{results.json,report.md,report.html,logs/,runs/}
# See qa/README.md.

REPO=$(cd "$(dirname "$0")/.." && pwd)
QA_DIR=$REPO/qa
export REPO QA_HOME=${QA_HOME:-$QA_DIR/work}
export QA_BIN=${QA_BIN:-$QA_HOME/bin}

# id|category|mode|title|timeout-minutes|command (run from $REPO; $RUN_ID and $OUT are expanded)
REGISTRY='epoch-crossing-jvm|l1|jvm|Devnet epoch crossing|10|qa/harness/epoch-crossing.sh jvm
epoch-crossing-native|l1|native|Devnet epoch crossing|10|qa/harness/epoch-crossing.sh native
haskell-sync-jvm|l1|jvm|Haskell node sync, 2 epochs|25|qa/harness/haskell-sync.sh jvm
haskell-sync-native|l1|native|Haskell node sync, 2 epochs|25|qa/harness/haskell-sync.sh native
past-time-travel-jvm|l1|jvm|Past time travel + Haskell sync from slot 0|25|qa/harness/past-time-travel.sh jvm
past-time-travel-native|l1|native|Past time travel + Haskell sync from slot 0|25|qa/harness/past-time-travel.sh native
sparse-backfill|l1|jvm|Sparse backfill matrix + Haskell sync|60|scripts/sparse-backfill/run-sparse-backfill-test.sh --skip-build --run-dir "$OUT/runs/sparse-backfill"
appchain-cluster|appchain|jvm|Two-node cluster, proofs, L1 anchor|20|qa/harness/appchain-cluster.sh
appchain-extensions|appchain|jvm|Multi-chain, query, SSE, admin, rotation, snapshot|25|qa/harness/appchain-extensions.sh
appchain-rotation-governance|appchain|jvm|Rotating sequencer + governed membership|20|qa/harness/appchain-rotation-governance.sh
appchain-script-anchor|appchain|jvm|Script anchors + L1 deposit observations|25|qa/harness/appchain-script-anchor.sh
e2e-jvm|e2e|jvm|Endpoint smoke + devnet functional + time travel|25|qa/harness/e2e.sh jvm
e2e-native|e2e|native|Endpoint smoke + devnet functional + time travel|25|qa/harness/e2e.sh native
compat-jvm|compat|jvm|SDK compatibility: CCL, Mesh, Evolution|40|qa/harness/compat.sh jvm "qa-$RUN_ID-compat-jvm" --compat-only
compat-native|compat|native|SDK compatibility: CCL, Mesh, Evolution|40|qa/harness/compat.sh native "qa-$RUN_ID-compat-native" --compat-only
load-jvm|load|jvm|SDK load and chained transactions|60|qa/harness/compat.sh jvm "qa-$RUN_ID-load-jvm" --load-only
docker-dist|docker|jvm|Docker Compose bundle on devnet: launcher, mounts, API, snapshots, upgrade|30|qa/harness/docker-dist.sh
docker-public|docker-public|jvm|Docker Compose bundle on preprod, preview and mainnet|75|qa/harness/docker-public.sh'
DEFAULT_CATEGORIES=l1,appchain,e2e,compat,docker
HARNESS_PORTS="7171 7172 13441 13442 3103 12889 12899 7181 13451 7191 13461 9199 7281 7282 7283 13551 13552 13553"

usage() {
  cat <<'USAGE'
Usage: qa/release-qa.sh [options]

  --categories LIST  comma-separated: l1, appchain, e2e, compat, docker, load,
                     docker-public (default: l1,appchain,e2e,compat,docker;
                     load and docker-public are opt-in)
  --only IDS         run exactly these test ids (see --list); overrides --categories
  --quick            skip native variants
  --build            always rebuild the JVM jar and native binary
  --no-build         use the existing build in qa/work/bin, whatever commit it came from
  --out DIR          results directory (default: qa/results/<run-id>)
  --list             print the test registry and exit
  --render DIR       re-render the report of an existing run (after adding triage/)
  -h, --help         this message

By default the build is reused only when it was made from the current commit and
the same uncommitted changes. Native builds use $QA_GRAALVM_HOME (or $GRAALVM_HOME).
Exit code: 0 when every test passed or failed only as a listed known issue.
USAGE
}

CATEGORIES=$DEFAULT_CATEGORIES; ONLY=""; QUICK=0; BUILD=auto; OUT=""; RENDER_ONLY=""
while [ $# -gt 0 ]; do
  case "$1" in
    --categories) CATEGORIES=$2; shift 2 ;;
    --only) ONLY=$2; shift 2 ;;
    --quick) QUICK=1; shift ;;
    --build) BUILD=always; shift ;;
    --no-build) BUILD=never; shift ;;
    --out) OUT=$2; shift 2 ;;
    --render) RENDER_ONLY=$2; shift 2 ;;
    --list) printf '%s\n' "$REGISTRY" | awk -F'|' '{printf "%-30s %-9s %-7s %3s min  %s\n",$1,$2,$3,$5,$4}'; exit 0 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage; exit 2 ;;
  esac
done

render() { # <run-dir>: each test is compared with its latest earlier result under qa/results
  python3 "$QA_DIR/lib/render_report.py" "${1%/}" --history "$QA_DIR/results" --known-issues "$QA_DIR/KNOWN-ISSUES.md"
}
if [ -n "$RENDER_ONLY" ]; then render "$(cd "$RENDER_ONLY" && pwd)"; exit $?; fi

TIMEOUT=$(command -v gtimeout || command -v timeout) \
  || { echo "GNU timeout is required (brew install coreutils)" >&2; exit 2; }
RUN_ID=$(date +%Y%m%d-%H%M%S)
RUN_STARTED=$(date +%Y-%m-%dT%H:%M:%S%z)
OUT=${OUT:-$QA_DIR/results/$RUN_ID}
mkdir -p "$OUT/logs" "$OUT/results" "$OUT/runs"
OUT=$(cd "$OUT" && pwd)   # harness nodes run with cwd app/, so paths must be absolute
export RUN_ID OUT
PROGRESS=$OUT/progress.log
say() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$PROGRESS"; }

# ------------------------------------------------------------------ selection ----
contains() { case ",$1," in *",$2,"*) return 0 ;; esac; return 1; }
SELECTED=""
while IFS='|' read -r id cat mode _; do
  if [ -n "$ONLY" ]; then contains "$ONLY" "$id" || continue
  else contains "$CATEGORIES" "$cat" || continue
       [ "$QUICK" = 1 ] && [ "$mode" = native ] && continue
  fi
  SELECTED="$SELECTED $id"
done <<EOF
$REGISTRY
EOF
SELECTED=${SELECTED# }
[ -n "$SELECTED" ] || { echo "No tests selected." >&2; exit 2; }
if [ -n "$ONLY" ]; then
  for id in $(echo "$ONLY" | tr ',' ' '); do
    contains "$(echo $SELECTED | tr ' ' ',')" "$id" || { echo "Unknown test id: $id (see --list)" >&2; exit 2; }
  done
fi
needs_native=0
for id in $SELECTED; do case $id in *-native) needs_native=1 ;; esac; done

# ------------------------------------------------------------------ preflight ----
say "run $RUN_ID: $SELECTED"
MISSING=""
need() { command -v "$1" >/dev/null 2>&1 || MISSING="$MISSING $1"; }
for t in java jq python3 curl lsof unzip git; do need $t; done
case " $SELECTED " in *" compat-"*|*" load-"*) need node; need npm ;; esac
[ -n "$MISSING" ] && { say "PREFLIGHT FAIL: missing tools:$MISSING"; exit 3; }
BUSY=""
for p in $HARNESS_PORTS; do lsof -nP -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1 && BUSY="$BUSY $p"; done
[ -n "$BUSY" ] && { say "PREFLIGHT FAIL: harness ports busy:$BUSY"; exit 3; }
HASKELL_NODE_DIR=${HASKELL_NODE_DIR:-$REPO/test-data-dir/haskell-node}
HASKELL_OK=1   # everything prepare_haskell and hs_tip in harness/common.sh read
DOCKER_OK=1; case " $SELECTED " in *" docker-"*) docker info >/dev/null 2>&1 || DOCKER_OK=0 ;; esac
for f in bin/cardano-node bin/cardano-cli tip.sh configuration.json files/topology.json files/dijkstra-genesis.json; do
  [ -e "$HASKELL_NODE_DIR/$f" ] || { HASKELL_OK=0; HASKELL_MISSING="$HASKELL_NODE_DIR/$f"; break; }
done

# ---------------------------------------------------------------------- build ----
cd "$REPO" || exit 2
mkdir -p "$QA_BIN"
BUILD_KEY="$(git rev-parse HEAD)-$(git diff HEAD | shasum | cut -c1-12)"
BUILD_FAILED=""
build_native() {
  local graal=${QA_GRAALVM_HOME:-${GRAALVM_HOME:-}}
  say "build: native distribution (GRAALVM_HOME=${graal:-unset})"
  GRAALVM_HOME=$graal ./gradlew :app:yanoNativeDistZip -Dquarkus.native.enabled=true \
    -Dquarkus.package.jar.enabled=false -PskipSigning=true > "$OUT/logs/build-native.log" 2>&1 || return 1
  local zip; zip=$(ls -t app/build/distributions/yano-native-*.zip | head -1)
  rm -rf "$QA_BIN/native-dist" && mkdir -p "$QA_BIN/native-dist" && unzip -q "$zip" -d "$QA_BIN/native-dist" || return 1
  echo "$BUILD_KEY" > "$QA_BIN/build-key-native"
}
build_jvm() {
  say "build: JVM uber-jar"
  ./gradlew :app:yanoDistZip -PskipSigning=true > "$OUT/logs/build-jvm.log" 2>&1 || return 1
  cp app/build/yano.jar "$QA_BIN/yano.jar" && echo "$BUILD_KEY" > "$QA_BIN/build-key-jvm"
}
stale() { [ "$BUILD" = always ] || { [ "$BUILD" = auto ] && [ "$(cat "$QA_BIN/build-key-$1" 2>/dev/null)" != "$BUILD_KEY" ]; }; }
# Native first: the JVM build then leaves app/build/yano.jar current for sparse-backfill.
if [ "$BUILD" = never ]; then
  say "build: skipped (--no-build)"
else
  if [ "$needs_native" = 1 ]; then
    if stale native || [ -z "$(ls "$QA_BIN"/native-dist/yano-native-*/yano 2>/dev/null)" ]; then
      build_native || { BUILD_FAILED="$BUILD_FAILED native"; say "build: native FAILED (see logs/build-native.log)"; }
    else say "build: reusing native binary ($(cat "$QA_BIN/build-key-native"))"; fi
  fi
  if stale jvm || [ ! -f "$QA_BIN/yano.jar" ]; then
    build_jvm || { BUILD_FAILED="$BUILD_FAILED jvm"; say "build: JVM FAILED (see logs/build-jvm.log)"; }
  else
    say "build: reusing JVM jar ($(cat "$QA_BIN/build-key-jvm"))"
  fi
fi
[ -f "$QA_BIN/yano.jar" ] && { cmp -s "$QA_BIN/yano.jar" app/build/yano.jar || cp "$QA_BIN/yano.jar" app/build/yano.jar; }

JAVA_BIN=${JAVA:-${JAVA_HOME:+$JAVA_HOME/bin/java}}; JAVA_BIN=${JAVA_BIN:-java}
NATIVE_BIN=$(ls "$QA_BIN"/native-dist/yano-native-*/yano 2>/dev/null | head -1)
RUN_STARTED=$RUN_STARTED CATEGORIES=$CATEGORIES ONLY=$ONLY QUICK=$QUICK BUILD=$BUILD SELECTED=$SELECTED \
  BUILD_KEY=$BUILD_KEY BUILD_FAILED=$BUILD_FAILED JAVA_BIN=$JAVA_BIN NATIVE_BIN=$NATIVE_BIN \
  HASKELL_NODE_DIR=$HASKELL_NODE_DIR python3 - "$OUT/meta.json" <<'PY'
import json, os, subprocess, sys, platform
e = os.environ
def run(*cmd):
    try:
        r = subprocess.run(list(cmd), capture_output=True, text=True, cwd=e["REPO"])
        return (r.stdout or r.stderr).strip().splitlines()[0] if (r.stdout or r.stderr).strip() else ""
    except Exception:
        return ""
version = ""
for line in open(os.path.join(e["REPO"], "gradle.properties")):
    if line.startswith("version"):
        version = line.split("=", 1)[1].strip()
json.dump({
  "run_id": e["RUN_ID"], "started": e["RUN_STARTED"],
  "args": {"categories": e["CATEGORIES"], "only": e["ONLY"], "quick": e["QUICK"] == "1", "build": e["BUILD"]},
  "selected": e["SELECTED"].split(),
  "commit": run("git", "rev-parse", "HEAD"), "commit_short": run("git", "rev-parse", "--short", "HEAD"),
  "branch": run("git", "rev-parse", "--abbrev-ref", "HEAD"), "subject": run("git", "log", "-1", "--format=%s"),
  "dirty": run("git", "status", "--porcelain", "--untracked-files=no") != "",
  "version": version, "build_key": e["BUILD_KEY"], "build_failed": e["BUILD_FAILED"].split(),
  "java": run(e["JAVA_BIN"], "-version"), "native_binary": e["NATIVE_BIN"],
  "haskell_node": run(os.path.join(e["HASKELL_NODE_DIR"], "bin", "cardano-node"), "--version"),
  "host": f"{platform.system()} {platform.machine()}",
}, open(sys.argv[1], "w"), indent=2)
PY

# ---------------------------------------------------------------------- tests ----
write_result() { # id cat mode title status reason rc duration started
  python3 - "$OUT/results/$1.json" "$@" <<'PY'
import json, sys
out, id_, cat, mode, title, status, reason, rc, dur, started = sys.argv[1:11]
json.dump({"id": id_, "category": cat, "mode": mode, "title": title, "status": status,
           "reason": reason, "exit_code": int(rc), "duration_s": int(dur), "started": started,
           "log": f"logs/{id_}.log"}, open(out, "w"), indent=2)
PY
}

for id in $SELECTED; do
  line=$(printf '%s\n' "$REGISTRY" | grep "^$id|")
  IFS='|' read -r _ cat mode title mins cmd <<EOF
$line
EOF
  logf=$OUT/logs/$id.log; started=$(date +%Y-%m-%dT%H:%M:%S%z)
  blocked=""
  case $mode in
    native) case " $BUILD_FAILED " in *" native "*) blocked="native build failed" ;; esac
            [ -z "$NATIVE_BIN" ] && [ -z "$blocked" ] && blocked="no native binary" ;;
  esac
  case " $BUILD_FAILED " in *" jvm "*) [ "$mode" = jvm ] && blocked="JVM build failed" ;; esac
  [ -z "$blocked" ] && [ "$mode" = jvm ] && [ ! -f "$QA_BIN/yano.jar" ] && blocked="no JVM jar"
  case $id in docker-*) [ "$DOCKER_OK" = 1 ] || blocked="Docker is not running" ;; esac
  case $id in haskell-sync-*|past-time-travel-*|sparse-backfill)
    [ "$HASKELL_OK" = 1 ] || blocked="Haskell node setup incomplete: $HASKELL_MISSING missing" ;; esac
  if [ -n "$blocked" ]; then
    say "SKIP  $id ($blocked)"; write_result "$id" "$cat" "$mode" "$title" BLOCKED "$blocked" 0 0 "$started"; continue
  fi

  say "START $id"
  t0=$(date +%s)
  # Each test gets its own PID file so a timeout can clean up exactly what it started.
  SP=$OUT PIDFILE=$OUT/runs/$id.pids HASKELL_NODE_DIR=$HASKELL_NODE_DIR \
    "$TIMEOUT" --kill-after=60 "${mins}m" bash -c "cd \"\$REPO\" && $cmd" > "$logf" 2>&1
  rc=$?
  ( SP=$OUT PIDFILE=$OUT/runs/$id.pids; . "$QA_DIR/harness/common.sh"; kill_tracked
    # The sparse-backfill runner keeps its own PID list.
    [ -f "$OUT/runs/$id/started-pids" ] && PIDFILE=$OUT/runs/$id/started-pids && kill_tracked ) >> "$logf" 2>&1
  dur=$(( $(date +%s) - t0 ))
  verdict=$(grep -E '^VERDICT:' "$logf" | tail -1)
  reason=$(printf '%s' "$verdict" | sed -E 's/^VERDICT: [A-Z]+ *//; s/^\((.*)\)$/\1/')
  if [ "$rc" = 124 ] || [ "$rc" = 137 ]; then status=TIMEOUT; reason="exceeded ${mins} min"
  elif grep -q 'IS BUSY' "$logf" && [ "$rc" = 2 ]; then status=BLOCKED; reason="harness port busy"
  elif [ "${verdict#VERDICT: PASS}" != "$verdict" ]; then status=PASS
  elif [ -n "$verdict" ]; then status=FAIL
  elif [ "$rc" = 0 ]; then status=PASS
  else status=FAIL; reason=${reason:-"exit code $rc, no verdict line"}; fi
  write_result "$id" "$cat" "$mode" "$title" "$status" "$reason" "$rc" "$dur" "$started"
  say "END   $id $status $(( dur / 60 ))m$(( dur % 60 ))s${reason:+ ($reason)}"
done

# --------------------------------------------------------------------- report ----
render "$OUT"
RC=$?
say "DONE  report: $OUT/report.html"
exit $RC
