#!/bin/bash
# Docker Compose distribution on public networks (needs internet). For each network,
# start a fresh bundle with the documented profiles and heap, sync until a target epoch,
# and check epoch boundaries, profile propagation and, with the wallet profile, the
# first-seen index. Preprod is also restarted to prove it resumes from its chainstate.
# Ports: 7283/13553, one network at a time.
source "$(dirname "$0")/docker-common.sh"
RUN=$SP/runs/docker-public; rm -rf "$RUN"; mkdir -p "$RUN"
HP=7283; NP=13553
trap docker_cleanup EXIT

docker info >/dev/null 2>&1 || { echo "VERDICT: FAIL (Docker is not running)"; exit 1; }
assert_ports_free $HP $NP || exit 2
ensure_docker_build || { echo "VERDICT: FAIL (image or ZIP build failed)"; exit 1; }

# CIP-19 example base addresses: valid bech32 on each network, used to probe the index.
ADDR_TESTNET=addr_test1qz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3n0d3vllmyqwsx5wktcd8cc3sq835lu7drv2xwl2wywfgs68faae
ADDR_MAINNET=addr1qx2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3n0d3vllmyqwsx5wktcd8cc3sq835lu7drv2xwl2wywfgse35a3x

epoch() { api $HP epochs/latest | jq -r '.epoch // 0'; }

# run_network <profiles> <heap> <target-epoch> <minutes> [restart]
run_network() {
  local profiles=$1 heap=$2 target=$3 mins=$4 restart=${5:-} net=${1%%,*} dir inst e deadline
  inst=qa-docker-$net; dir=$RUN/$net
  echo "== $profiles (JAVA_OPTS=$heap, target epoch $target)"
  extract_bundle "$dir" "$inst" $HP $NP
  (cd "$dir" && JAVA_OPTS=$heap ./yano.sh start:$profiles) > "$RUN/$net.log" 2>&1
  wait_http_ready $HP 180; check "$net: ready" $?
  ARGS=$(docker exec yano-$inst sh -c 'tr "\0" " " < /proc/1/cmdline' 2>/dev/null)
  echo "  process: $ARGS"
  check "$net: profiles and heap reach the JVM" \
    $(case "$ARGS" in (*"$heap"*"-Dquarkus.profile=$profiles "*) echo 0;; (*) echo 1;; esac)
  deadline=$(( $(date +%s) + mins * 60 ))
  while :; do
    e=$(epoch); [ "$e" -ge "$target" ] 2>/dev/null && break
    [ "$(date +%s)" -ge "$deadline" ] && break
    sleep 20
  done
  echo "  epoch $e, tip $(api $HP node/tip | cut -c1-90)"
  check "$net: reached epoch $target within $mins min" $([ "$e" -ge "$target" ] 2>/dev/null; echo $?)
  CALC=$(api $HP node/epoch-calc-status)
  echo "  epoch-calc-status: $(echo "$CALC" | jq -c '{status, previous: .lastBoundary.previousEpoch, new: .lastBoundary.newEpoch, success: .lastBoundary.success}')"
  check "$net: epoch boundaries succeed" $(echo "$CALC" | jq -e '.status == "OK" and .lastBoundary.success == true' >/dev/null; echo $?)
  case ",$profiles," in *,wallet,*)
    local addr=$ADDR_TESTNET; [ "$net" = mainnet ] && addr=$ADDR_MAINNET
    FS=$(api $HP "addresses/$addr/first-seen"); echo "  first-seen: $(echo "$FS" | cut -c1-140)"
    check "$net: wallet first-seen index covers from origin" \
      $(echo "$FS" | jq -e '.coverage.enabled == true and .coverage.completeFromOrigin == true' >/dev/null; echo $?) ;;
  esac
  if [ -n "$restart" ]; then
    local b h; b=$(api $HP node/tip | jq -r .blockNumber); h=$(api $HP "blocks/$b" | jq -r .hash)
    (cd "$dir" && JAVA_OPTS=$heap ./yano.sh restart:$profiles) >> "$RUN/$net.log" 2>&1
    wait_http_ready $HP 180; sleep 10
    check "$net: restart resumes from chainstate (block $b unchanged, tip moved on)" \
      $([ "$(api $HP "blocks/$b" | jq -r .hash)" = "$h" ] && [ "$(api $HP node/tip | jq -r .blockNumber)" -ge "$b" ]; echo $?)
  fi
  echo "  ERROR lines: $(grep -c ' ERROR ' "$dir"/logs/yano.log 2>/dev/null || echo 0)"
  (cd "$dir" && ./yano.sh stop) >> "$RUN/$net.log" 2>&1
}

run_network preprod,wallet,small -Xmx384m 10 20 restart
run_network preview,small -Xmx384m 10 20
run_network mainnet,wallet,medium -Xmx1536m 3 20

[ ${#FAILS[@]} -eq 0 ] && echo "VERDICT: PASS" || echo "VERDICT: FAIL (${FAILS[*]})"
