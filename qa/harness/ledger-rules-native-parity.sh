#!/bin/bash
# ADR-056 Phase 7c / ADR-057 Phase E: the validation stack must behave in the native image exactly as on the JVM.
#
# For each configuration and protocol version it runs a JVM devnet producer, then a native one, with the same genesis
# and settings, and runs NativeParityWorkloadTest (tx-services) against each: the same transactions, built offline
# from the genesis funds, so byte-identical in both runs. It then compares
#   - every observation line of the workload (verdict, rule names, messages, tx hashes, evaluate response),
#   - the admission-shadow disagreement counters and the number of dump bundles,
#   - shadow sync on the producer and on a follower of the same kind (counters, zero findings, JSONL summary),
# and checks a clean shutdown and that no log shows a native-image failure. Bundles the native node dumps are replayed
# on the JVM (ShadowBundleReplayTest).
#
# Usage: qa/harness/ledger-rules-native-parity.sh [configs] [pvs]  (defaults: "java-julc java-scalus scalus" "11 10 9")
#   java-julc    engine java-julc (the default: the Java rules, julc phase 2), admission shadow scalus with dumps,
#                shadow sync java-julc and java-scalus with report and dumps, plus a follower with shadow sync; the
#                evaluate endpoint uses the Julc evaluator (script-evaluator=julc)
#   java-scalus  engine java-scalus (the same rules, Scalus phase 2), admission shadow scalus, shadow sync
#                java-scalus, plus a follower
#   scalus  engine scalus: legacy Scalus admission, no engines
#   amaru-scalus, amaru  that engine (Amaru phase one with Scalus Plutus, or Amaru for both phases), admission shadow
#           java-julc, shadow sync java-julc and the engine, plus a follower (JAR and NATIVE built with
#           -PwithAmaru=true; protocol version 10 or later)
# Binaries: $JAR and $NATIVE (common.sh defaults: the release-QA build in qa/work/bin). Ports: producer
# HTTP_A/N2N_A, follower HTTP_B/N2N_B. Runs under $SP/runs/ledger-rules-native-parity.
CONFIGS=${1:-java-julc java-scalus scalus}
PVS=${2:-11 10 9}
source "$(dirname "$0")/common.sh"
EPOCH_LENGTH=100
OUT=$SP/runs/ledger-rules-native-parity
mkdir -p "$OUT"
[ -x "$NATIVE" ] && [ -f "$JAR" ] || { echo "VERDICT: FAIL (need JAR=$JAR and NATIVE=$NATIVE)"; exit 2; }
FAILURES=()
fail() { FAILURES+=("$1"); echo "  FAIL  $1"; }

# The devnet genesis set (the pv10 files for PV 9 and 10) with the protocol version, 100-slot epochs and a 1,000 ADA
# action deposit (each genesis address holds 10,000 ADA).
prepare_genesis() { # <dir> <pv>
  local g=$1 pv=$2 src=$REPO/app/config/network/devnet
  [ "$pv" != 11 ] && src=$src/pv10
  mkdir -p "$g"; cp "$src"/*.json "$g/"
  jq --argjson pv "$pv" --argjson el $EPOCH_LENGTH '.protocolParams.protocolVersion.major = $pv | .epochLength = $el' \
    "$g/shelley-genesis.json" > "$g/t" && mv "$g/t" "$g/shelley-genesis.json"
  jq --argjson pv "$pv" '.protocol_major_ver = $pv | .gov_action_deposit = "1000000000"
    | if $pv == 9 then .protocol_minor_ver = 0 else . end' "$g/protocol-param.json" > "$g/t" && mv "$g/t" "$g/protocol-param.json"
  jq '.govActionDeposit = 1000000000' "$g/conway-genesis.json" > "$g/t" && mv "$g/t" "$g/conway-genesis.json"
}

engine_opts() { # <config> <dir> <producer|follower>
  local sync="-Dyano.validation.shadow-sync=true -Dyano.validation.shadow-sync-report=$2/shadow-sync.jsonl
    -Dyano.validation.shadow-sync-dump-dir=$2/sync-dumps -Dyano.validation.shadow-sync-summary-seconds=15"
  case $1 in
    java-julc) [ "$3" = producer ] && echo "-Dyano.validation.engine=java-julc
            -Dyano.validation.shadow-engines=scalus -Dyano.validation.shadow-dump-dir=$2/shadow-dumps
            -Dyano.block-producer.script-evaluator=julc"
          echo "$sync -Dyano.validation.shadow-sync-engines=java-julc,java-scalus" ;;
    java-scalus) [ "$3" = producer ] && echo "-Dyano.validation.engine=java-scalus
            -Dyano.validation.shadow-engines=scalus -Dyano.validation.shadow-dump-dir=$2/shadow-dumps"
          echo "$sync -Dyano.validation.shadow-sync-engines=java-scalus" ;;
    scalus) echo "-Dyano.validation.engine=scalus" ;;
    amaru|amaru-scalus) [ "$3" = producer ] && echo "-Dyano.validation.engine=$1
            -Dyano.validation.shadow-engines=java-julc -Dyano.validation.shadow-dump-dir=$2/shadow-dumps"
           echo "$sync -Dyano.validation.shadow-sync-engines=java-julc,$1" ;;
  esac
}

stop_node() { # <pid> <dir>: SIGTERM and wait, keeping the exit code
  kill "$1" 2>/dev/null; wait "$1" 2>/dev/null; echo $? > "$2/exit-code"
}

tip_hash() { yano_tip "$1" | jq -r '.blockHash // empty'; }

run_mode() { # <config> <pv> <jvm|native>
  local c=$1 pv=$2 mode=$3 base=$OUT/$1-pv$2/$3 producer follower=""
  rm -rf "$base"; mkdir -p "$base"
  # pdir, not p: common.sh's assert_ports_free assigns a global p.
  local pdir=$base/producer fdir=$base/follower g=$base/genesis
  prepare_genesis "$g" "$pv"
  assert_ports_free $HTTP_A $N2N_A $HTTP_B $N2N_B || { fail "$c pv$pv $mode: ports busy"; return; }
  local genesis=(-Dquarkus.profile=devnet -Dyano.genesis.shelley-genesis-file=$g/shelley-genesis.json
    -Dyano.genesis.byron-genesis-file=$g/byron-genesis.json -Dyano.genesis.alonzo-genesis-file=$g/alonzo-genesis.json
    -Dyano.genesis.conway-genesis-file=$g/conway-genesis.json -Dyano.genesis.protocol-parameters-file=$g/protocol-param.json)
  # shellcheck disable=SC2046
  start_yano "$mode" "$pdir" $HTTP_A $N2N_A "$pdir/stdout.log" "${genesis[@]}" -Dyano.block-producer.block-time-millis=2000 \
    $(engine_opts "$c" "$pdir" producer)
  producer=$YANO_PID
  wait_ready $HTTP_A 120 || { fail "$c pv$pv $mode: producer not ready"; tail -20 "$pdir/stdout.log"; stop_node $producer "$pdir"; return; }
  if [ "$c" != scalus ]; then
    # shellcheck disable=SC2046
    start_yano "$mode" "$fdir" $HTTP_B $N2N_B "$fdir/stdout.log" "${genesis[@]}" -Dyano.block-producer.enabled=false \
      -Dyano.client.enabled=true -Dyano.dev-mode=false -Dyano.remote.host=localhost -Dyano.remote.port=$N2N_A \
      $(engine_opts "$c" "$fdir" follower)
    follower=$YANO_PID
    wait_ready $HTTP_B 120 || fail "$c pv$pv $mode: follower not ready"
  fi
  log "$c pv$pv $mode: running the workload"
  # The legacy validator cannot see pending certificates: confirm each before its child.
  (cd "$REPO" && ./gradlew :tx-services:test --tests '*NativeParityWorkloadTest' --rerun -q \
      -Dyano.parity.remote-url=http://localhost:$HTTP_A -Dyano.parity.report="$base/observations.txt" \
      -Dyano.parity.epoch-length=$EPOCH_LENGTH -Dyano.parity.chain-certificates=$([ "$c" = scalus ] && echo false || echo true) \
      > "$base/workload.log" 2>&1)
  echo $? > "$base/workload-exit"
  sleep 10
  if [ -n "$follower" ]; then
    for _ in $(seq 1 60); do [ "$(tip_hash $HTTP_A)" = "$(tip_hash $HTTP_B)" ] && break; sleep 1; done
    # A follower that stops following after the producer's rollback is a sync problem, not a validation one: it is
    # reported, and its shadow-sync findings are still checked.
    if [ "$(tip_hash $HTTP_A)" = "$(tip_hash $HTTP_B)" ]; then log "$c pv$pv $mode: follower at the producer's tip"
    else log "$c pv$pv $mode: WARNING follower behind the producer"; fi
    curl -s "http://localhost:$HTTP_B/q/metrics" | grep '^yano_validation' | grep -v '_seconds' > "$fdir/metrics.txt"
    stop_node $follower "$fdir"
  fi
  curl -s "http://localhost:$HTTP_A/q/metrics" | grep '^yano_validation' | grep -v '_seconds' > "$pdir/metrics.txt"
  stop_node $producer "$pdir"
}

check_mode() { # <config> <pv> <jvm|native>
  local label="$1 pv$2 $3" base=$OUT/$1-pv$2/$3 d
  for d in "$base/producer" "$base/follower"; do
    [ -f "$d/exit-code" ] || continue
    [ "$(cat "$d/exit-code")" = 143 ] || fail "$label $(basename "$d"): exit code $(cat "$d/exit-code") after SIGTERM"
    if grep -Eq "$NATIVE_IMAGE_ERRORS" "$d/stdout.log"; then
      fail "$label $(basename "$d"): native-image or initialisation failure"; grep -E "$NATIVE_IMAGE_ERRORS" "$d/stdout.log" | head -3
    fi
    if [ -f "$d/shadow-sync.jsonl" ]; then
      grep '"type":"summary"' "$d/shadow-sync.jsonl" | tail -1 > "$d/shadow-sync-summary.json"
      [ -s "$d/shadow-sync-summary.json" ] || fail "$label $(basename "$d"): no shadow-sync summary line at stop"
      [ "$(grep -vc '"type":"summary"' "$d/shadow-sync.jsonl")" = 0 ] || fail "$label $(basename "$d"): shadow-sync findings"
    fi
  done
  # Engine configurations must pass the workload's own checks. The legacy path admits a vote on a missing action
  # (it has no GOV rules), which the workload records as a problem on the JVM and in native alike.
  [ "$1" = scalus ] || [ "$(cat "$base/workload-exit")" = 0 ] || fail "$label: the workload failed ($base/workload.log)"
  log "$label: $(grep -c ' ACCEPTED' "$base/observations.txt") accepted, $(grep -c ' REJECTED' "$base/observations.txt") rejected, $(grep -c '^PROBLEM' "$base/observations.txt") problems"
}

parity_view() { # <mode dir>: what must be equal between the JVM and the native run
  local d=$1 role
  echo "== observations"; cat "$d/observations.txt"
  echo "== workload exit $(cat "$d/workload-exit")"
  echo "== admission shadows"; grep -h '^yano_validation_disagreements\|^yano_validation_engine_healthy' "$d/producer/metrics.txt" | sort
  echo "== admission-shadow bundles $(ls "$d/producer/shadow-dumps" 2>/dev/null | wc -l | tr -d ' ')"
  echo "== producer shadow sync"; jq -S -c '.stats.byEngine' "$d/producer/shadow-sync-summary.json" 2>/dev/null
  for role in producer follower; do
    echo "== $role shadow-sync failures and block rules"
    grep -h '^yano_validation_shadow_sync_txs_total\|^yano_validation_shadow_sync_block_rule\|^yano_validation_shadow_sync_engine_healthy' \
      "$d/$role/metrics.txt" 2>/dev/null | grep -v 'outcome="validated"\|outcome="agreed"' | sort
  done
}

for c in $CONFIGS; do
  for pv in $PVS; do
    case $c in amaru*) [ "$pv" -lt 10 ] && { log "$c pv$pv: skipped (Amaru validates Conway from PV 10)"; continue; } ;; esac
    for mode in jvm native; do run_mode "$c" "$pv" "$mode"; check_mode "$c" "$pv" "$mode"; done
    d=$OUT/$c-pv$pv
    if [ ! -s "$d/jvm/observations.txt" ] || [ ! -s "$d/native/observations.txt" ]; then
      fail "$c pv$pv: no workload observations to compare"
    elif diff -u <(parity_view "$d/jvm") <(parity_view "$d/native") > "$d/parity.diff"; then
      echo "  PASS  $c pv$pv: JVM and native identical ($(grep -c ' ACCEPTED\| REJECTED' "$d/jvm/observations.txt") verdicts)"
    else
      fail "$c pv$pv: JVM and native differ ($d/parity.diff)"; head -30 "$d/parity.diff"
    fi
    for dumps in "$d/native/producer/shadow-dumps" "$d/native/producer/sync-dumps"; do
      [ -n "$(ls "$dumps" 2>/dev/null)" ] || continue
      if (cd "$REPO" && ./gradlew :tx-services:test --tests '*ShadowBundleReplayTest' --rerun -q \
          -Dyano.shadow.bundles="$dumps" > "$dumps.replay.log" 2>&1); then
        echo "  PASS  $c pv$pv: $(ls "$dumps" | wc -l | tr -d ' ') native bundles ($(basename "$dumps")) replay on the JVM"
      else
        fail "$c pv$pv: native bundles in $(basename "$dumps") do not replay ($dumps.replay.log)"
      fi
    done
  done
done
kill_tracked
if [ ${#FAILURES[@]} -eq 0 ]; then echo "VERDICT: PASS"; else echo "VERDICT: FAIL (${#FAILURES[@]} failures: ${FAILURES[0]})"; exit 1; fi
