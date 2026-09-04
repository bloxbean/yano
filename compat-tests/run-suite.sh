#!/usr/bin/env bash
#
# Runs the SDK compatibility and load suite against ONE already-running Yano devnet.
#
# The suite never starts or stops a node. Start a devnet yourself (see README.md),
# then point this script at it. Stacks run sequentially - CCL, then MeshJS, then
# Evolution - because they share one node and one mempool, so running them
# concurrently would make load numbers meaningless.
#
# Written for bash 3.2 (the macOS system bash), so no associative arrays and every
# array expansion is length-guarded for `set -u`.
#
# See adr/053-sdk-compatibility-and-load-suite.md.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ---------------------------------------------------------------- defaults ----
URL="${YANO_URL:-http://localhost:7070/api/v1}"
LABEL=""
ONLY=""
KINDS="compat load info"
CHECK_ENDPOINTS=0
SKIP_INSTALL=0
SKIP_PREFLIGHT=0
LIST_ONLY=0
NODE_PID=""
PROXY_PORT="${PROXY_PORT:-7099}"

# Load knobs. Identical across deployments so two runs stay comparable.
CCL_MIN="${CCL_MIN:-3}"
CCL_WORKERS="${CCL_WORKERS:-8}"
CCL_CHAIN_WORKERS="${CCL_CHAIN_WORKERS:-4}"
CCL_CHAIN_DEPTH="${CCL_CHAIN_DEPTH:-8}"
JS_SECONDS="${JS_SECONDS:-90}"
JS_WORKERS="${JS_WORKERS:-4}"
JS_CHAIN_WORKERS="${JS_CHAIN_WORKERS:-2}"
JS_CHAIN_DEPTH="${JS_CHAIN_DEPTH:-5}"
# Chaining cases are only meaningful while a parent stays unconfirmed long enough
# for its child to be built and submitted. Below this cadence their result is noise.
CHAIN_MIN_BLOCK_MS="${CHAIN_MIN_BLOCK_MS:-5000}"
CHAINING_RELIABLE=1

usage() {
  cat <<'USAGE'
Usage: run-suite.sh [options]

  --url URL          Yano API base (default: http://localhost:7070/api/v1,
                     or $YANO_URL). Must be a devnet-mode node.
  --label NAME       names results/<NAME>/ (default: a UTC timestamp)
  --only SPEC        comma-separated stacks or cases, e.g.
                       --only mesh
                       --only ccl:blsProbe,evolution:compat
  --compat-only      run only the deterministic pass/fail cases
  --load-only        run only the reporting load cases
  --check-endpoints  also record which endpoints each JS SDK calls, through
                     shared/proxy.mjs, and diff against
                     shared/endpoint-baseline-<stack>.json
  --node-pid PID     sample RSS/CPU of this process around each phase
  --skip-install     do not run `npm ci` before the JS stacks
  --skip-preflight   do not probe the node before starting, which also skips the
                     block-cadence check that guards the chaining cases
  --list             print the case registry and exit
  -h, --help         this message

Exit code: 0 when every asserting case matched its expectation in KNOWN-FAILS.md.
USAGE
}

while [ $# -gt 0 ]; do
  case "$1" in
    --url) URL="$2"; shift 2 ;;
    --label) LABEL="$2"; shift 2 ;;
    --only) ONLY="$2"; shift 2 ;;
    --compat-only) KINDS="compat"; shift ;;
    --load-only) KINDS="load"; shift ;;
    --check-endpoints) CHECK_ENDPOINTS=1; shift ;;
    --node-pid) NODE_PID="$2"; shift 2 ;;
    --skip-install) SKIP_INSTALL=1; shift ;;
    --skip-preflight) SKIP_PREFLIGHT=1; shift ;;
    --list) LIST_ONLY=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

URL="${URL%/}"
ROOT_URL="${URL%/api/v1}"
[ -n "$LABEL" ] || LABEL="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="$ROOT/results/$LABEL"

# ------------------------------------------------------------- case registry --
# id | stack | kind | working dir | command
# kind: compat = asserts, load = reports only, info = exploratory, never asserts.
CASES=(
  "ccl:compat|ccl|compat|ccl|./gradlew --quiet --console=plain compat -Dyano.url=@URL@"
  "ccl:blsProbe|ccl|compat|ccl|./gradlew --quiet --console=plain blsProbe -Dyano.url=@URL@"
  "ccl:plutusProbe|ccl|info|ccl|./gradlew --quiet --console=plain plutusProbe -Dyano.url=@URL@"
  "ccl:queryOverlay|ccl|info|ccl|./gradlew --quiet --console=plain queryOverlayProbe -Dyano.url=@URL@"
  "ccl:load|ccl|load|ccl|./gradlew --quiet --console=plain load -Dyano.url=@URL@ -Dload.duration.minutes=@CCL_MIN@ -Dload.workers=@CCL_WORKERS@ -Dload.utxos.per.worker=60 -Dload.chain.workers=@CCL_CHAIN_WORKERS@ -Dload.chain.depth=@CCL_CHAIN_DEPTH@ -Dload.throttle.ms=20 -Dload.report.dir=@OUT@/ccl"
  "mesh:compat|mesh|compat|mesh|npm run --silent compat"
  "mesh:vesting|mesh|compat|mesh|npm run --silent vesting"
  "mesh:load|mesh|load|mesh|npm run --silent load"
  "evolution:compat|evolution|compat|evolution|npm run --silent compat"
  "evolution:vesting|evolution|compat|evolution|npm run --silent vesting"
  "evolution:awaitTx|evolution|compat|evolution|npm run --silent await-tx"
  "evolution:load|evolution|load|evolution|npm run --silent load"
)
# Derived cases are asserted from a load report rather than run directly.
# id | stack | report file | meaning
DERIVED=(
  "mesh:chained|mesh|mesh-load-report.json|chained transactions reach full depth"
  "evolution:chained|evolution|evolution-load-report.json|chained transactions reach full depth"
)

if [ "$LIST_ONLY" = "1" ]; then
  printf '%-22s %-9s %s\n' CASE KIND DETAIL
  for c in "${CASES[@]}"; do
    IFS='|' read -r id cstack kind dir cmd <<<"$c"
    printf '%-22s %-9s (%s) %s\n' "$id" "$kind" "$dir" "$cmd"
  done
  for d in "${DERIVED[@]}"; do
    IFS='|' read -r id dstack report meaning <<<"$d"
    printf '%-22s %-9s %s\n' "$id" "derived" "$meaning"
  done
  if [ "$CHECK_ENDPOINTS" = "1" ]; then
    printf '%-22s %-9s %s\n' "mesh:endpoints" "compat" "endpoint capture vs baseline"
    printf '%-22s %-9s %s\n' "evolution:endpoints" "compat" "endpoint capture vs baseline"
  fi
  exit 0
fi

# ------------------------------------------------------- known-fail registry --
# Parses the table in KNOWN-FAILS.md. A row looks like:
#   | `mesh:chained` | MeshJS 1.9.1 | ... | FAIL | ... |
# Anything not listed there is expected to PASS.
KF_IDS=()
KF_EXPECT=()
KNOWN_FAILS_FILE="$ROOT/KNOWN-FAILS.md"
if [ -f "$KNOWN_FAILS_FILE" ]; then
  while IFS= read -r line; do
    id="$(printf '%s' "$line" | sed -n 's/^|[[:space:]]*`\([^`]*\)`[[:space:]]*|.*/\1/p')"
    [ -n "$id" ] || continue
    expect="$(printf '%s' "$line" | awk -F'|' '{gsub(/^[ \t]+|[ \t]+$/, "", $5); print $5}')"
    case "$expect" in
      PASS|FAIL)
        KF_IDS[${#KF_IDS[@]}]="$id"
        KF_EXPECT[${#KF_EXPECT[@]}]="$expect"
        ;;
      *) echo "WARN: KNOWN-FAILS.md row for '$id' has an unparseable Expected column: '$expect'" >&2 ;;
    esac
  done < "$KNOWN_FAILS_FILE"
fi

expectation_for() {
  local want="$1" i
  if [ ${#KF_IDS[@]} -gt 0 ]; then
    for i in $(seq 0 $((${#KF_IDS[@]} - 1))); do
      if [ "${KF_IDS[$i]}" = "$want" ]; then printf '%s' "${KF_EXPECT[$i]}"; return; fi
    done
  fi
  printf 'PASS'
}

selected() { # id kind -> 0 when this case should run
  local id="$1" kind="$2" stack="${1%%:*}" want
  case " $KINDS " in *" $kind "*) ;; *) return 1 ;; esac
  [ -z "$ONLY" ] && return 0
  local oldifs="$IFS"; IFS=','
  for want in $ONLY; do
    IFS="$oldifs"
    want="$(printf '%s' "$want" | tr -d '[:space:]')"
    [ "$want" = "$id" ] && return 0
    [ "$want" = "$stack" ] && return 0
    IFS=','
  done
  IFS="$oldifs"
  return 1
}

# ------------------------------------------------------------------ preflight --
mkdir -p "$OUT"
echo "############ yano compat suite: $LABEL ############"
echo "node    : $URL"
echo "results : $OUT"
echo "kinds   : $KINDS${ONLY:+   only: $ONLY}"
echo

if [ "$SKIP_PREFLIGHT" = "0" ]; then
  tip="$(curl -fsS --max-time 10 "$URL/blocks/latest" 2>/dev/null)"
  if [ -z "$tip" ]; then
    echo "PREFLIGHT FAILED: no Yano REST API at $URL" >&2
    echo "Start a devnet first - see compat-tests/README.md - or pass --url." >&2
    exit 3
  fi
  height="$(printf '%s' "$tip" | sed -n 's/.*"height"[[:space:]]*:[[:space:]]*\([0-9]*\).*/\1/p')"
  echo "preflight: node reachable, tip height=${height:-?}"
  # The suite funds wallets through /devnet/fund, which is absent off devnet.
  fund_code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 \
    -X POST -H 'Content-Type: application/json' -d '{}' "$URL/devnet/fund" 2>/dev/null)"
  if [ "$fund_code" = "403" ] || [ "$fund_code" = "404" ]; then
    echo "PREFLIGHT FAILED: POST /devnet/fund returned $fund_code - not a devnet-mode node." >&2
    echo "The suite funds test wallets through the devnet faucet and cannot run without it." >&2
    exit 3
  fi
  echo "preflight: devnet faucet present (POST /devnet/fund -> $fund_code)"

  # Block cadence decides whether the chaining cases can mean anything. A fast
  # devnet confirms the parent mid-test, the child then succeeds for the wrong
  # reason, and the chaining verdict is a false positive. Detect it rather than
  # leaving it as a README footnote.
  h0="$(printf '%s' "$tip" | sed -n 's/.*"height"[[:space:]]*:[[:space:]]*\([0-9]*\).*/\1/p')"
  sleep 6
  h1="$(curl -fsS --max-time 10 "$URL/blocks/latest" 2>/dev/null \
        | sed -n 's/.*"height"[[:space:]]*:[[:space:]]*\([0-9]*\).*/\1/p')"
  if [ -n "$h0" ] && [ -n "$h1" ] && [ "$h1" -gt "$h0" ]; then
    BLOCK_MS=$(( 6000 / (h1 - h0) ))
    echo "preflight: block cadence ~${BLOCK_MS}ms"
    if [ "$BLOCK_MS" -lt "$CHAIN_MIN_BLOCK_MS" ]; then
      CHAINING_RELIABLE=0
      echo
      echo "WARNING: blocks are ~${BLOCK_MS}ms apart, below the ${CHAIN_MIN_BLOCK_MS}ms this suite needs"
      echo "         for chaining. A parent will confirm mid-test, so *:chained results would"
      echo "         be false positives - they will be SKIPPED rather than asserted."
      echo "         Restart the node with -Dyano.block-producer.block-time-millis=20000,"
      echo "         or set CHAIN_MIN_BLOCK_MS=0 to assert them anyway."
      echo
    fi
  else
    echo "preflight: block cadence unknown (tip did not advance in 6s)"
  fi
fi
echo

snap() { # node-side counters around each phase
  curl -fsS --max-time 10 "$ROOT_URL/q/metrics" 2>/dev/null \
    | grep -E "^yano_node_mempool" | sort > "$OUT/metrics-$1.txt"
  if [ -n "$NODE_PID" ]; then
    ps -o rss=,%cpu= -p "$NODE_PID" 2>/dev/null \
      | awk -v t="$1" '{printf "%s rss_mb=%.0f cpu=%s\n", t, $1/1024, $2}' >> "$OUT/process.txt"
  fi
}

js_ready() {
  local stack="$1" rc
  [ "$SKIP_INSTALL" = "1" ] && return 0
  [ -d "$ROOT/$stack/node_modules" ] && return 0
  echo "--- installing $stack dependencies (npm ci) ---"
  ( cd "$ROOT/$stack" && npm ci ) > "$OUT/$stack-install.log" 2>&1
  rc=$?
  [ $rc -eq 0 ] || echo "npm ci failed for $stack, see $OUT/$stack-install.log" >&2
  return $rc
}

# ------------------------------------------------------------------- runner ----
REPORT_IDS=()
REPORT_KIND=()
REPORT_ACTUAL=()
REPORT_VERDICT=()
FAILURES=0

record() { # id kind actual
  local id="$1" kind="$2" actual="$3" expect verdict
  expect="$(expectation_for "$id")"
  if [ "$kind" = "load" ] || [ "$kind" = "info" ]; then
    verdict="RECORDED"
  elif [ "$actual" = "SKIP" ]; then
    verdict="SKIPPED"
  elif [ "$actual" = "$expect" ]; then
    if [ "$expect" = "FAIL" ]; then verdict="KNOWN-FAIL"; else verdict="OK"; fi
  elif [ "$expect" = "FAIL" ]; then
    verdict="UNEXPECTED-PASS"; FAILURES=$((FAILURES + 1))
  else
    verdict="REGRESSION"; FAILURES=$((FAILURES + 1))
  fi
  REPORT_IDS[${#REPORT_IDS[@]}]="$id"
  REPORT_KIND[${#REPORT_KIND[@]}]="$kind"
  REPORT_ACTUAL[${#REPORT_ACTUAL[@]}]="$actual"
  REPORT_VERDICT[${#REPORT_VERDICT[@]}]="$verdict"
}

run_case() { # id stack kind dir cmd [url-override]
  local id="$1" stack="$2" kind="$3" dir="$4" cmd="$5" url="${6:-$URL}"
  local log="$OUT/${id//:/-}.log" rc actual
  # Commands carry placeholders so each case's full command line lives in one
  # place in the registry, and nothing has to be appended conditionally here.
  cmd="${cmd//@URL@/$url}"
  cmd="${cmd//@OUT@/$OUT}"
  cmd="${cmd//@CCL_MIN@/$CCL_MIN}"
  cmd="${cmd//@CCL_WORKERS@/$CCL_WORKERS}"
  cmd="${cmd//@CCL_CHAIN_WORKERS@/$CCL_CHAIN_WORKERS}"
  cmd="${cmd//@CCL_CHAIN_DEPTH@/$CCL_CHAIN_DEPTH}"
  echo "=== $id ($kind) ==="
  (
    cd "$ROOT/$dir" || exit 99
    # The JS stacks read every knob from the environment; the CCL build ignores
    # these, so one environment serves all three stacks.
    export YANO_URL="$url"
    export DURATION_SECONDS="$JS_SECONDS"
    export WORKERS="$JS_WORKERS"
    export UTXOS_PER_WORKER=12
    export CHAIN_WORKERS="$JS_CHAIN_WORKERS"
    export CHAIN_DEPTH="$JS_CHAIN_DEPTH"
    export REPORT="$OUT/$stack-load-report.json"
    bash -c "$cmd"
  ) > "$log" 2>&1
  rc=$?
  if [ $rc -eq 0 ]; then actual=PASS; else actual=FAIL; fi
  echo "    exit=$rc -> $log"
  tail -3 "$log" | sed 's/^/    | /'
  record "$id" "$kind" "$actual"
}

for stack in ccl mesh evolution; do
  stack_selected=0
  for c in "${CASES[@]}"; do
    IFS='|' read -r id cstack kind dir cmd <<<"$c"
    [ "$cstack" = "$stack" ] || continue
    if selected "$id" "$kind"; then stack_selected=1; fi
  done
  [ "$stack_selected" = "1" ] || continue

  echo "---------------- stack: $stack ----------------"
  if [ "$stack" != "ccl" ] && ! js_ready "$stack"; then
    for c in "${CASES[@]}"; do
      IFS='|' read -r id cstack kind dir cmd <<<"$c"
      [ "$cstack" = "$stack" ] || continue
      selected "$id" "$kind" && record "$id" "$kind" "SKIP"
    done
    continue
  fi

  for c in "${CASES[@]}"; do
    IFS='|' read -r id cstack kind dir cmd <<<"$c"
    [ "$cstack" = "$stack" ] || continue
    selected "$id" "$kind" || continue
    run_case "$id" "$cstack" "$kind" "$dir" "$cmd"
  done
  snap "after-$stack"
  echo
done

# ------------------------------------------------- derived chaining verdicts ---
# `chains.fullDepth` is the honest signal: > 0 means the SDK completed at least one
# chain of unconfirmed parent -> child against Yano's mempool overlay.
for d in "${DERIVED[@]}"; do
  IFS='|' read -r id dstack report meaning <<<"$d"
  selected "$id" "compat" || continue
  if [ "$CHAINING_RELIABLE" = "0" ]; then
    record "$id" "compat" "SKIP"
    continue
  fi
  if [ ! -f "$OUT/$report" ]; then
    record "$id" "compat" "SKIP"
    continue
  fi
  full="$(sed -n 's/.*"fullDepth"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$OUT/$report" | head -1)"
  if [ -z "$full" ]; then
    record "$id" "compat" "SKIP"
  elif [ "$full" -gt 0 ]; then
    record "$id" "compat" "PASS"
  else
    record "$id" "compat" "FAIL"
  fi
done

# ------------------------------------------------------ endpoint capture -------
# Re-runs each JS compat probe behind the recording proxy and diffs the captured
# endpoint set against the checked-in baseline. The signal that matters is a
# captured endpoint answering 4xx/5xx: that is a REST surface an SDK depends on
# and Yano no longer satisfies.
if [ "$CHECK_ENDPOINTS" = "1" ]; then
  echo "---------------- endpoint capture ----------------"
  for stack in mesh evolution; do
    selected "$stack:endpoints" "compat" || continue
    if [ ! -d "$ROOT/$stack/node_modules" ] && ! js_ready "$stack"; then
      record "$stack:endpoints" "compat" "SKIP"; continue
    fi
    capture="$OUT/endpoints-$stack.json"
    echo "=== $stack:endpoints ==="
    YANO_URL="$ROOT_URL" PROXY_PORT="$PROXY_PORT" PROXY_LOG="$capture" \
      node "$ROOT/shared/proxy.mjs" > "$OUT/proxy-$stack.log" 2>&1 &
    proxy_pid=$!
    # Wait for the listener rather than sleeping a guessed interval.
    for _ in $(seq 1 50); do
      curl -fsS --max-time 2 "http://localhost:$PROXY_PORT/api/v1/blocks/latest" >/dev/null 2>&1 && break
      sleep 0.2
    done
    ( cd "$ROOT/$stack" && env YANO_URL="http://localhost:$PROXY_PORT/api/v1" \
        npm run --silent compat ) > "$OUT/$stack-endpoints-compat.log" 2>&1
    kill -TERM "$proxy_pid" 2>/dev/null
    wait "$proxy_pid" 2>/dev/null
    if [ -f "$capture" ]; then
      node "$ROOT/shared/diff-endpoints.mjs" \
        "$ROOT/shared/endpoint-baseline-$stack.json" "$capture" > "$OUT/endpoints-$stack.diff.txt" 2>&1
      rc=$?
      cat "$OUT/endpoints-$stack.diff.txt" | sed 's/^/    | /'
      if [ $rc -eq 0 ]; then record "$stack:endpoints" "compat" "PASS"; else record "$stack:endpoints" "compat" "FAIL"; fi
    else
      echo "    no capture produced"
      record "$stack:endpoints" "compat" "SKIP"
    fi
  done
  echo
fi

# ------------------------------------------------------------------ summary ----
SUMMARY="$OUT/SUMMARY.md"
{
  echo "# Compat suite: $LABEL"
  echo
  echo "- node: \`$URL\`"
  echo "- finished: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo
  echo "| case | kind | result | verdict |"
  echo "| --- | --- | --- | --- |"
  if [ ${#REPORT_IDS[@]} -gt 0 ]; then
    for i in $(seq 0 $((${#REPORT_IDS[@]} - 1))); do
      echo "| \`${REPORT_IDS[$i]}\` | ${REPORT_KIND[$i]} | ${REPORT_ACTUAL[$i]} | ${REPORT_VERDICT[$i]} |"
    done
  fi
} > "$SUMMARY"

echo "---------------- summary ----------------"
printf '%-22s %-8s %-7s %s\n' CASE KIND RESULT VERDICT
if [ ${#REPORT_IDS[@]} -gt 0 ]; then
  for i in $(seq 0 $((${#REPORT_IDS[@]} - 1))); do
    printf '%-22s %-8s %-7s %s\n' \
      "${REPORT_IDS[$i]}" "${REPORT_KIND[$i]}" "${REPORT_ACTUAL[$i]}" "${REPORT_VERDICT[$i]}"
  done
fi
echo
echo "results  : $OUT"
echo "summary  : $SUMMARY"

if [ "$FAILURES" -gt 0 ]; then
  echo
  echo "FAILED: $FAILURES asserting case(s) did not match KNOWN-FAILS.md."
  echo "A REGRESSION means Yano or the SDK broke. An UNEXPECTED-PASS means a known"
  echo "failure was fixed upstream - update KNOWN-FAILS.md and re-run."
  exit 1
fi
echo
echo "OK: every asserting case matched its expectation."
exit 0
