#!/usr/bin/env bash
# =============================================================================
# Yano app-chain SUSTAINED load / soak test.
#
# Unlike loadtest.sh (a single burst of N messages), this drives CONTINUOUS
# parallel traffic at a target offered rate for a fixed DURATION — a standing
# high-traffic workload — and samples the chain the whole time. It reports
# steady-state finalize throughput, success rate, backpressure, and pool-depth
# stability over the run (does it hold, or creep / degrade / stall?).
#
#   ./soaktest.sh orders-chain --duration 2700 --rate 500 --conc 40 --spread
#   ./soaktest.sh orders-chain --duration 60 --rate 400 --conc 30 --node 0
#
# Workers are backpressure-aware: a 429 (pool full) makes the worker back off
# briefly rather than spin, so the offered rate self-limits near capacity.
# Targets any-bytes (ordered-log) chains.
# =============================================================================
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
HTTP_BASE="${YANO_CLUSTER_HTTP_BASE:-7070}"

CHAIN=""; DURATION=2700; RATE=500; CONC=40; SIZE=256; TOPIC="soak"; TARGET="spread"
SAMPLE=15; OUTDIR=""

c_grn() { printf '\033[32m%s\033[0m\n' "$*"; }
c_ylw() { printf '\033[33m%s\033[0m\n' "$*"; }
c_red() { printf '\033[31m%s\033[0m\n' "$*"; }
die()   { c_red "error: $*" >&2; exit 1; }
now()   { python3 -c 'import time;print(time.time())'; }
http_port() { echo $(( HTTP_BASE + $1 )); }
ready() { curl -sf "http://localhost:$(http_port "$1")/q/health/ready" >/dev/null 2>&1; }
ready_nodes() { local i=0 out=(); while ready "$i"; do out+=("$i"); i=$((i+1)); [ "$i" -ge 64 ] && break; done; printf '%s\n' "${out[@]:-}"; }
tip_of()  { curl -s "http://localhost:$(http_port "$1")/api/v1/app-chain/chains" 2>/dev/null \
  | jq -r --arg c "$CHAIN" '.[]|select(.chainId==$c)|.tipHeight' 2>/dev/null; }
pool_of() { curl -s "http://localhost:$(http_port "$1")/api/v1/app-chain/chains/$CHAIN/status" 2>/dev/null \
  | jq -r '[.. | .poolSize? // empty] | first // 0' 2>/dev/null; }
root_of() { curl -s "http://localhost:$(http_port "$1")/api/v1/app-chain/chains/$CHAIN/status" 2>/dev/null \
  | jq -r '.stateRoot // ""' 2>/dev/null; }

usage() {
  cat <<EOF
Usage: ./soaktest.sh <chain-id> [options]

  --duration <sec>   how long to sustain load        (default 2700 = 45 min)
  --rate <msg/s>     target aggregate offered rate    (default 500)
  --conc <workers>   concurrent submitters            (default 40)
  -s <bytes>         payload size in bytes            (default 256)
  --sample <sec>     time-series sample interval      (default 15)
  --node <i>         submit all to node i             (default: spread)
  --spread           round-robin submits across nodes (default)
  --out <dir>        write CSV + report here          (default: /tmp/yano-soak-<ts>)
  -h, --help
EOF
}

[ $# -ge 1 ] || { usage; exit 1; }
CHAIN="$1"; shift
while [ $# -gt 0 ]; do case "$1" in
  --duration) DURATION="$2"; shift 2;;
  --rate) RATE="$2"; shift 2;;
  --conc) CONC="$2"; shift 2;;
  -s) SIZE="$2"; shift 2;;
  --sample) SAMPLE="$2"; shift 2;;
  --node) TARGET="$2"; shift 2;;
  --spread) TARGET="spread"; shift;;
  --out) OUTDIR="$2"; shift 2;;
  -h|--help) usage; exit 0;;
  *) die "unknown option: $1";;
esac; done
[[ "$DURATION" =~ ^[0-9]+$ && "$DURATION" -ge 1 ]] || die "--duration must be a positive integer"
[[ "$RATE"     =~ ^[0-9]+$ && "$RATE"     -ge 1 ]] || die "--rate must be a positive integer"
[[ "$CONC"     =~ ^[0-9]+$ && "$CONC"     -ge 1 ]] || die "--conc must be a positive integer"

ready 0 || die "no running cluster on http $(http_port 0) — start one with ./cluster.sh start"
NODES=(); while IFS= read -r n; do [ -n "$n" ] && NODES+=("$n"); done < <(ready_nodes)
[ "${#NODES[@]}" -ge 1 ] || die "no ready nodes found"
[ "$TARGET" = "spread" ] || ready "$TARGET" || die "node $TARGET not ready"
nnodes="${#NODES[@]}"

# Per-worker pacing: target per-worker interval so the fleet offers ~RATE/s.
INTERVAL="$(python3 -c "print(max(0.0,$CONC/$RATE))")"

PAD=""; if [ "$SIZE" -gt 12 ]; then PAD="$(head -c "$((SIZE-12))" </dev/zero | tr '\0' x)"; fi

[ -n "$OUTDIR" ] || OUTDIR="/tmp/yano-soak-$(date +%s)"
mkdir -p "$OUTDIR"
CSV="$OUTDIR/samples.csv"
echo "t_sec,tip,pool_max,accepted,throttled,errors,finalized" > "$CSV"

H0="$(tip_of 0)"; [ -n "$H0" ] || die "cannot read tip for chain '$CHAIN'"
ROOT0="$(root_of 0)"

echo "Soak: chain=$CHAIN  duration=${DURATION}s  target=${RATE} msg/s  conc=$CONC  payload≈${SIZE}B  submit=$([ "$TARGET" = spread ] && echo "spread($nnodes)" || echo "node $TARGET")"
echo "      per-worker interval=${INTERVAL}s  sample=${SAMPLE}s  out=$OUTDIR  startTip=$H0"

T_START="$(now)"
DEADLINE_S=$(( $(date +%s) + DURATION ))
# One-time: does the pacing interval call for a per-request sleep? (avoid a
# python spawn per iteration — that alone throttles the generator.)
SLEEP_ON="$(python3 -c "print(1 if $INTERVAL>0 else 0)")"

# --- Workers: continuous, paced, 429-aware ----------------------------------
# Hot loop stays pure-bash: an integer `date +%s` deadline check, no python.
# Counters are flushed to w.$w every few requests (newline-terminated so the
# sampler's `read` succeeds) and once more at exit.
for (( w=0; w<CONC; w++ )); do
  (
    acc=0; thr=0; err=0; seq=0
    while [ "$(date +%s)" -lt "$DEADLINE_S" ]; do
      if [ "$TARGET" = "spread" ]; then node="${NODES[$(( (w+seq) % nnodes ))]}"; else node="$TARGET"; fi
      code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 \
        -X POST "http://localhost:$(( HTTP_BASE + node ))/api/v1/app-chain/chains/$CHAIN/messages" \
        -H 'Content-Type: application/json' \
        -d "{\"topic\":\"$TOPIC\",\"body\":\"w${w}-${seq}-$PAD\"}" 2>/dev/null)"
      seq=$((seq+1))
      case "$code" in
        20[0-2]) acc=$((acc+1));;
        429)     thr=$((thr+1)); sleep 0.2;;
        *)       err=$((err+1));;
      esac
      [ $(( seq % 5 )) -eq 0 ] && printf '%s %s %s\n' "$acc" "$thr" "$err" > "$OUTDIR/w.$w"
      [ "$SLEEP_ON" = "1" ] && sleep "$INTERVAL"
    done
    printf '%s %s %s\n' "$acc" "$thr" "$err" > "$OUTDIR/w.$w"
  ) &
done

# --- Sampler: time-series until deadline ------------------------------------
FIN=0; FROM=$(( H0 + 1 ))
# Robust against missing-newline files: awk ignores the trailing-newline rule
# that trips bash `read`.
sum_workers() { # -> "accepted throttled errors"
  awk '{a+=$1;t+=$2;e+=$3} END{print a+0, t+0, e+0}' "$OUTDIR"/w.* 2>/dev/null || echo "0 0 0"
}
advance_finalized() { # sum messageCount for new blocks (FROM .. tip); updates FIN, FROM
  local tip="$1" page cs cnt sm
  while [ "$FROM" -le "$tip" ]; do
    page="$(curl -s "http://localhost:$(http_port 0)/api/v1/app-chain/chains/$CHAIN/blocks?from=$FROM&limit=200" 2>/dev/null)"
    # cs = "<blocks_read> <messageCount_sum>"; advance FROM by blocks actually
    # read (NOT the page size) so we never skip past the tip.
    cs="$(printf '%s' "$page" | jq -r '.blocks[]?.messageCount' 2>/dev/null | awk '{n++; s+=$1} END{print (n+0), (s+0)}')"
    cnt="${cs%% *}"; sm="${cs##* }"
    [ "${cnt:-0}" -eq 0 ] && break
    FIN=$(( FIN + sm )); FROM=$(( FROM + cnt ))
  done
}

while :; do
  t_now="$(now)"
  el="$(python3 -c "print(int($t_now-$T_START))")"
  tip="$(tip_of 0)"; [ -n "$tip" ] || tip="$H0"
  pmax=0; for i in "${NODES[@]}"; do p="$(pool_of "$i")"; [ -n "$p" ] || p=0; [ "$p" -gt "$pmax" ] && pmax="$p"; done
  advance_finalized "$tip"
  read -r sa st se < <(sum_workers)
  echo "$el,$tip,$pmax,$sa,$st,$se,$FIN" >> "$CSV"
  printf '  t=%4ss tip=%s pool=%-6s accepted=%-8s throttled=%-6s finalized=%s\n' "$el" "$tip" "$pmax" "$sa" "$st" "$FIN"
  [ "$(date +%s)" -lt "$DEADLINE_S" ] || break
  sleep "$SAMPLE"
done

c_ylw "load window done; stopping workers + draining..."
wait 2>/dev/null

# Drain: let the proposer finalize what's pooled (bounded wait).
STABLE=0; LAST="$(tip_of 0)"; DRAIN_DEADLINE=$(( $(date +%s) + 180 ))
while :; do
  sleep 2
  cur="$(tip_of 0)"; [ -n "$cur" ] || cur="$LAST"
  pool="$(pool_of 0)"; [ -n "$pool" ] || pool=0
  if [ "$cur" = "$LAST" ]; then STABLE=$((STABLE+1)); else STABLE=0; LAST="$cur"; fi
  { [ "$pool" -eq 0 ] && [ "$STABLE" -ge 2 ]; } && break
  [ "$(date +%s)" -gt "$DRAIN_DEADLINE" ] && { c_ylw "drain wait timed out"; break; }
done
TIP_FINAL="$(tip_of 0)"; advance_finalized "$TIP_FINAL"
T_END="$(now)"
read -r ACC THR ERRs < <(sum_workers)

# Final per-node consistency
CONSISTENT="yes"; ROOT_REF="$(root_of 0)"; TIPS=""
for i in "${NODES[@]}"; do
  ri="$(root_of "$i")"; ti="$(tip_of "$i")"; TIPS="$TIPS $i:$ti"
  [ "$ri" = "$ROOT_REF" ] || CONSISTENT="no"
done

dur="$(python3 -c "print(max(0.001,$T_END-$T_START))")"
load_dur="$DURATION"
fmt() { python3 -c "print(f'{$1:,.1f}')" 2>/dev/null || echo "$1"; }
pct() { python3 -c "print(f'{(100.0*$1/$2) if $2 else 0:.3f}')" 2>/dev/null || echo 0; }

# Steady-state finalize rate: use the CSV, skip first & last 10% (ramp/drain).
read -r SS_RATE SS_POOL_AVG SS_POOL_MAX < <(python3 - "$CSV" <<'PY'
import sys,csv
rows=list(csv.DictReader(open(sys.argv[1])))
rows=[r for r in rows if r['t_sec'].strip()!='']
if len(rows)<3: print("0 0 0"); sys.exit()
n=len(rows); lo=max(1,int(n*0.1)); hi=max(lo+1,int(n*0.9))
seg=rows[lo:hi]
t0=float(seg[0]['t_sec']); t1=float(seg[-1]['t_sec'])
f0=float(seg[0]['finalized']); f1=float(seg[-1]['finalized'])
rate=(f1-f0)/max(1.0,(t1-t0))
pools=[float(r['pool_max']) for r in seg]
print(f"{rate:.1f} {sum(pools)/len(pools):.0f} {max(pools):.0f}")
PY
)

echo
c_grn "================ SOAK THROUGHPUT REPORT ================"
printf '  chain / nodes         : %s / %s (threshold majority)\n' "$CHAIN" "$nnodes"
printf '  load window / total   : %s s / %s s (incl. drain)\n' "$load_dur" "$(fmt "$dur")"
printf '  target offered rate   : %s msg/s   payload≈%sB\n' "$RATE" "$SIZE"
echo
printf '  accepted (2xx)        : %s\n' "$ACC"
printf '  throttled (429)       : %s\n' "$THR"
printf '  errors                : %s\n' "$ERRs"
printf '  finalized msgs        : %s   (tip %s→%s, %s blocks)\n' "$FIN" "$H0" "$TIP_FINAL" "$(( TIP_FINAL - H0 ))"
echo
printf '  offered rate (actual) : %s msg/s (accepted/load-window)\n' "$(fmt "$(python3 -c "print($ACC/$load_dur)")")"
printf '  FINALIZE rate (avg)   : %s msg/s (finalized/load-window)\n' "$(fmt "$(python3 -c "print($FIN/$load_dur)")")"
printf '  FINALIZE rate (steady): %s msg/s (mid-run, ramp/drain excluded)\n' "$(fmt "$SS_RATE")"
printf '  success rate          : %s %% (finalized/accepted)\n' "$(pct "$FIN" "$ACC")"
echo
printf '  pool depth (steady)   : avg %s / max %s   (cap 10000)\n' "$SS_POOL_AVG" "$SS_POOL_MAX"
if [ "$(( TIP_FINAL - H0 ))" -gt 0 ]; then
  printf '  msgs / block (avg)    : %s\n' "$(fmt "$(python3 -c "print($FIN/max(1,$TIP_FINAL-$H0))")")"
fi
printf '  cross-node consistency: state roots %s%s ; tips%s\n' \
  "$([ "$CONSISTENT" = yes ] && echo IDENTICAL || echo DIVERGED)" \
  "$([ "$CONSISTENT" = yes ] && echo '' || echo ' !!!')" "$TIPS"
c_grn "======================================================="
printf 'CSV time-series: %s\n' "$CSV"
exit 0
