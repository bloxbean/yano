#!/usr/bin/env bash
# =============================================================================
# Yano app-chain parallel load / throughput test.
#
# Fires many messages concurrently at a running cluster (see ./cluster.sh) and
# reports both SUBMIT throughput (how fast the API accepts) and FINALIZATION
# throughput (how fast messages actually land in certified blocks — the real
# chain throughput), plus backpressure drops.
#
#   ./loadtest.sh orders-chain                 # 1000 msgs, 20 concurrent, node 0
#   ./loadtest.sh orders-chain -n 5000 -c 50   # heavier
#   ./loadtest.sh orders-chain -n 5000 --spread  # spread submits across all nodes
#   ./loadtest.sh orders-chain -n 2000 -s 512  # 512-byte payloads
#   ./loadtest.sh registry-chain --kv -n 2000  # kv-registry chain: real PUTs
#
# Plain mode targets any-bytes (ordered-log) chains — a kv-registry chain
# rejects unstructured payloads at admission. --kv submits real CBOR PUT
# commands ([0, key, value]) with run-unique keys instead: use it to load
# (and anchor-) test kv-registry chains.
# =============================================================================
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
HTTP_BASE="${YANO_CLUSTER_HTTP_BASE:-7070}"

CHAIN=""; TOTAL=1000; CONC=20; SIZE=0; TOPIC="load"; TARGET="0"   # node index or "spread"
KV=0                                                              # --kv: CBOR PUT commands

c_grn() { printf '\033[32m%s\033[0m\n' "$*"; }
c_ylw() { printf '\033[33m%s\033[0m\n' "$*"; }
c_red() { printf '\033[31m%s\033[0m\n' "$*"; }
die()   { c_red "error: $*" >&2; exit 1; }
now()   { python3 -c 'import time;print(time.time())'; }        # fractional seconds
http_port() { echo $(( HTTP_BASE + $1 )); }

ready() { curl -sf "http://localhost:$(http_port "$1")/q/health/ready" >/dev/null 2>&1; }

# Discover ready node indices (contiguous from 0).
ready_nodes() {
  local i=0 out=()
  while ready "$i"; do out+=("$i"); i=$((i+1)); [ "$i" -ge 64 ] && break; done
  printf '%s\n' "${out[@]:-}"
}

tip_of() { curl -s "http://localhost:$(http_port "$1")/api/v1/app-chain/chains" 2>/dev/null \
  | jq -r --arg c "$CHAIN" '.[]|select(.chainId==$c)|.tipHeight' 2>/dev/null; }
pool_of() { curl -s "http://localhost:$(http_port "$1")/api/v1/app-chain/chains/$CHAIN/status" 2>/dev/null \
  | jq -r '[.. | .poolSize? // empty] | first // 0' 2>/dev/null; }

usage() {
  cat <<EOF
Usage: ./loadtest.sh <chain-id> [options]

  -n <total>     total messages to submit          (default 1000)
  -c <conc>      concurrent submitters             (default 20)
  -s <bytes>     payload size in bytes             (default ~8)
  -t <topic>     message topic                     (default "load"; "kv" in --kv mode)
  --kv           kv-registry mode: submit CBOR PUT commands with run-unique
                 keys (-s sizes the value). Required for kv-registry chains.
  --node <i>     submit all to node i              (default 0)
  --spread       round-robin submits across all ready nodes
  -h, --help
EOF
}

[ $# -ge 1 ] || { usage; exit 1; }
CHAIN="$1"; shift
while [ $# -gt 0 ]; do case "$1" in
  -n) TOTAL="$2"; shift 2;;
  -c) CONC="$2"; shift 2;;
  -s) SIZE="$2"; shift 2;;
  -t) TOPIC="$2"; shift 2;;
  --kv) KV=1; shift;;
  --node) TARGET="$2"; shift 2;;
  --spread) TARGET="spread"; shift;;
  -h|--help) usage; exit 0;;
  *) die "unknown option: $1";;
esac; done
[[ "$TOTAL" =~ ^[0-9]+$ && "$TOTAL" -ge 1 ]] || die "-n must be a positive integer"
[[ "$CONC"  =~ ^[0-9]+$ && "$CONC"  -ge 1 ]] || die "-c must be a positive integer"

ready 0 || die "no running cluster on http $(http_port 0) — start one with ./cluster.sh start"
NODES=()   # bash 3.2 (macOS) has no mapfile — read the list into an array
while IFS= read -r n; do [ -n "$n" ] && NODES+=("$n"); done < <(ready_nodes)
[ "${#NODES[@]}" -ge 1 ] || die "no ready nodes found"
[ "$TARGET" = "spread" ] || ready "$TARGET" || die "node $TARGET not ready"

# Fixed-size pad so each body is ~SIZE bytes ("<seq>" prefix + pad).
PAD=""
if [ "$SIZE" -gt 8 ]; then PAD="$(head -c "$((SIZE-8))" </dev/zero | tr '\0' x)"; fi

# kv mode: each message is CBOR [0(PUT), key(bstr8), value(bstr)]. The key is
# (run-tag, seq) — 8 bytes, unique across runs so every PUT writes a fresh
# entry — and is assembled with pure printf (no subprocess in the hot loop).
# The value ('x' pad, UTF-8-safe for value-format: utf8) is encoded ONCE here.
if [ "$KV" = "1" ]; then
  [ "$TOPIC" = "load" ] && TOPIC="kv"
  RUN_TAG="$(printf '%08x' "$(( $(date +%s) & 0xffffffff ))")"
  VAL="${PAD:-v}"
  VALUE_HEX="$(printf '%s' "$VAL" | od -An -v -tx1 | tr -d ' \n')"
  vlen="${#VAL}"
  if   [ "$vlen" -lt 24 ];  then VALUE_BSTR="$(printf '%02x' $((0x40 + vlen)))$VALUE_HEX"
  elif [ "$vlen" -lt 256 ]; then VALUE_BSTR="$(printf '58%02x' "$vlen")$VALUE_HEX"
  else                           VALUE_BSTR="$(printf '59%04x' "$vlen")$VALUE_HEX"; fi
fi

WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

echo "Load test: chain=$CHAIN  total=$TOTAL  concurrency=$CONC  payload≈${SIZE:-8}B  target=$([ "$TARGET" = spread ] && echo "spread(${#NODES[@]} nodes)" || echo "node $TARGET")"

H0="$(tip_of 0)"; [ -n "$H0" ] || die "cannot read tip for chain '$CHAIN' (does it exist?)"
T_START="$(now)"

# Fire TOTAL submits with CONC in flight. We run CONC worker processes in
# parallel; each walks its own contiguous slice sequentially (one request at a
# time), so ~CONC requests are always in flight. (Portable — avoids BSD xargs
# -I command-length limits and bash-4-only constructs.)
nnodes="${#NODES[@]}"
per=$(( (TOTAL + CONC - 1) / CONC ))
for (( w=0; w<CONC; w++ )); do
  start=$(( w * per + 1 )); end=$(( start + per - 1 )); [ "$end" -gt "$TOTAL" ] && end="$TOTAL"
  [ "$start" -gt "$TOTAL" ] && break
  (
    for (( s=start; s<=end; s++ )); do
      if [ "$TARGET" = "spread" ]; then node="${NODES[$(( s % nnodes ))]}"; else node="$TARGET"; fi
      if [ "$KV" = "1" ]; then
        data="$(printf '{"topic":"%s","bodyHex":"830048%s%08x%s"}' "$TOPIC" "$RUN_TAG" "$s" "$VALUE_BSTR")"
      else
        data="{\"topic\":\"$TOPIC\",\"body\":\"$s-$PAD\"}"
      fi
      curl -s -o /dev/null -w '%{http_code}\n' --max-time 30 \
        -X POST "http://localhost:$(( HTTP_BASE + node ))/api/v1/app-chain/chains/$CHAIN/messages" \
        -H 'Content-Type: application/json' \
        -d "$data"
    done
  ) > "$WORK/codes.$w" 2>/dev/null &
done
wait
cat "$WORK"/codes.* > "$WORK/codes.txt" 2>/dev/null
T_SUBMIT_END="$(now)"

ACCEPTED="$(grep -cE '^20[0-2]$' "$WORK/codes.txt" 2>/dev/null)"; ACCEPTED="${ACCEPTED:-0}"
DROPPED="$(grep -cE '^429$'      "$WORK/codes.txt" 2>/dev/null)"; DROPPED="${DROPPED:-0}"
TOTAL_SEEN="$(wc -l < "$WORK/codes.txt" 2>/dev/null | tr -d ' ')"; TOTAL_SEEN="${TOTAL_SEEN:-0}"
ERRORS="$(( TOTAL_SEEN - ACCEPTED - DROPPED ))"

# Wait for finalization to drain: the pending pool empties AND the tip is
# stable for 2 consecutive polls (so late-arriving messages are counted).
c_ylw "submitted; waiting for finalization to drain..."
STABLE=0; LAST="$H0"; H1="$H0"; DRAIN_DEADLINE=$(( $(date +%s) + 180 ))
while :; do
  sleep 1
  cur="$(tip_of 0)"; [ -n "$cur" ] || cur="$LAST"
  pool="$(pool_of 0)"; [ -n "$pool" ] || pool=0
  if [ "$cur" = "$LAST" ]; then STABLE=$((STABLE+1)); else STABLE=0; LAST="$cur"; fi
  H1="$cur"
  { [ "$pool" -eq 0 ] && [ "$STABLE" -ge 2 ]; } && break
  [ "$(date +%s)" -gt "$DRAIN_DEADLINE" ] && { c_ylw "drain wait timed out (partial result)"; break; }
done
T_END="$(now)"

# Sum finalized messages + block timestamps over blocks (H0, H1].
FINALIZED=0; BLOCKS=$(( H1 - H0 )); TS_FIRST=""; TS_LAST=""
if [ "$BLOCKS" -gt 0 ]; then
  from=$(( H0 + 1 ))
  while [ "$from" -le "$H1" ]; do
    page="$(curl -s "http://localhost:$(http_port 0)/api/v1/app-chain/chains/$CHAIN/blocks?from=$from&limit=200" 2>/dev/null)"
    counts="$(printf '%s' "$page" | jq -r '.blocks[]? | "\(.messageCount) \(.timestamp)"' 2>/dev/null)"
    [ -n "$counts" ] || break
    while read -r mc ts; do
      [ -n "$mc" ] || continue
      FINALIZED=$(( FINALIZED + mc ))
      [ -z "$TS_FIRST" ] && TS_FIRST="$ts"; TS_LAST="$ts"
    done <<< "$counts"
    from=$(( from + 200 ))
  done
fi

# --- Report ------------------------------------------------------------------
submit_dur="$(python3 -c "print(max(0.001,$T_SUBMIT_END-$T_START))")"
e2e_dur="$(python3 -c "print(max(0.001,$T_END-$T_START))")"
block_span="$(python3 -c "print(max(0.001,(($TS_LAST-$TS_FIRST) if '$TS_FIRST' and '$TS_LAST' else 0)/1000.0))" 2>/dev/null || echo 0.001)"

fmt() { python3 -c "print(f'{$1:,.1f}')" 2>/dev/null || echo "$1"; }

echo
c_grn "==================== throughput ===================="
printf '  submitted attempts : %s\n' "$TOTAL"
printf '  accepted (2xx)     : %s\n' "$ACCEPTED"
printf '  dropped  (429 pool): %s   %s\n' "$DROPPED" "$([ "$DROPPED" -gt 0 ] && echo '(backpressure — pool full)' || echo '')"
printf '  errors             : %s\n' "$ERRORS"
echo
printf '  submit time        : %s s\n' "$(fmt "$submit_dur")"
printf '  SUBMIT rate        : %s msg/s (accepted/submit-time)\n' "$(fmt "$(python3 -c "print($ACCEPTED/$submit_dur)")")"
echo
printf '  finalized msgs     : %s   in %s block(s)\n' "$FINALIZED" "$BLOCKS"
if [ "$BLOCKS" -gt 0 ]; then
  printf '  msgs / block       : %s\n' "$(fmt "$(python3 -c "print($FINALIZED/$BLOCKS)")")"
  printf '  block-window span  : %s s (first→last finalized block)\n' "$(fmt "$block_span")"
  printf '  FINALIZE rate      : %s msg/s (finalized/block-window)\n' "$(fmt "$(python3 -c "print($FINALIZED/$block_span)")")"
fi
printf '  end-to-end rate    : %s msg/s (finalized/total-time %ss)\n' \
  "$(fmt "$(python3 -c "print($FINALIZED/$e2e_dur)")")" "$(fmt "$e2e_dur")"
c_grn "===================================================="
[ "$FINALIZED" -lt "$ACCEPTED" ] && c_ylw "note: finalized < accepted — some messages may still be pending, deduped, or state-machine-rejected"
exit 0
