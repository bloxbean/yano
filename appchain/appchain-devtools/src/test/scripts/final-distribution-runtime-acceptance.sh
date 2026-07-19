#!/usr/bin/env bash
set -euo pipefail

archive="${1:?Usage: final-distribution-runtime-acceptance.sh YANO_ZIP}"
http_base="${YANO_ACCEPTANCE_HTTP_BASE:-18080}"
server_base="${YANO_ACCEPTANCE_SERVER_BASE:-23337}"
work="$(mktemp -d "${TMPDIR:-/tmp}/yano-appchain-dx.XXXXXX")"
project="$work/project"

cleanup() {
  if [ -x "$project/scripts/stop" ]; then
    "$project/scripts/stop" >/dev/null 2>&1 || true
  fi
  rm -rf "$work"
}

failure() {
  code=$?
  trap - EXIT
  for log in "$project"/logs/*.log; do
    [ -f "$log" ] && { echo "--- $log" >&2; tail -100 "$log" >&2; }
  done
  cleanup
  exit "$code"
}
trap failure EXIT

unzip -q "$archive" -d "$work/distribution"
yano_home="$(find "$work/distribution" -mindepth 1 -maxdepth 1 -type d | head -1)"
export YANO_HOME="$yano_home"
export YANO_ACCEPTANCE_API_KEY='packaged-acceptance-operator'

"$yano_home/yano.sh" appchain init --non-interactive \
  --recipe audit-log --network devnet --members 2 \
  --member-key 8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c \
  --member-key 8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394 \
  --name packaged-acceptance --chain-id acceptance-chain \
  --http-port-base "$http_base" --server-port-base "$server_base" \
  --output "$project" --format json >/dev/null

printf '%s\n' \
  'YANO_APPCHAIN_SIGNING_KEY=0101010101010101010101010101010101010101010101010101010101010101' \
  "YANO_APPCHAIN_API_KEYS=$YANO_ACCEPTANCE_API_KEY" \
  >"$project/secrets/node0.env"
printf '%s\n' \
  'YANO_APPCHAIN_SIGNING_KEY=0202020202020202020202020202020202020202020202020202020202020202' \
  "YANO_APPCHAIN_API_KEYS=$YANO_ACCEPTANCE_API_KEY" \
  >"$project/secrets/node1.env"
chmod 600 "$project"/secrets/node*.env

"$project/scripts/start" >/dev/null
api0="http://127.0.0.1:${http_base}/api/v1/app-chain/chains/acceptance-chain"
api1="http://127.0.0.1:$((http_base + 1))/api/v1/app-chain/chains/acceptance-chain"
curl -fsS -H "X-API-Key: $YANO_ACCEPTANCE_API_KEY" "$api0/identity" \
  | jq -e '.schemaVersion == "v1" and .identityCoverage == "PROJECT_BOUND"
      and (.consensusProfileDigest | length) == 64
      and (.pluginCatalogFingerprint | startswith("sha256:"))
      and (.resolvedConfigDigest | length) == 64
      and (.releaseCatalogDigest | length) == 64' >/dev/null
"$yano_home/yano.sh" appchain drift "$project" \
  --peer "http://127.0.0.1:${http_base}/api/v1/" \
  --peer "http://127.0.0.1:$((http_base + 1))/api/v1/" \
  --api-key-env YANO_ACCEPTANCE_API_KEY --format json \
  | jq -e '.status == "DRIFT_OK" and .peerCount == 2' >/dev/null
submitted="$(curl -fsS -H 'content-type: application/json' \
  -d '{"topic":"acceptance","body":"packaged-runtime"}' "$api0/messages")"
message_id="$(jq -er '.messageId' <<<"$submitted")"

for _ in $(seq 1 60); do
  tip0="$(curl -fsS "$api0/tip")"
  tip1="$(curl -fsS "$api1/tip")"
  height0="$(jq -r '.height' <<<"$tip0")"
  height1="$(jq -r '.height' <<<"$tip1")"
  root0="$(jq -r '.stateRoot' <<<"$tip0")"
  root1="$(jq -r '.stateRoot' <<<"$tip1")"
  [ "$height0" -gt 0 ] && [ "$height0" = "$height1" ] \
    && [ "$root0" = "$root1" ] && break
  sleep 1
done
[ "$height0" -gt 0 ] && [ "$height0" = "$height1" ] && [ "$root0" = "$root1" ]
curl -fsS "$api0/blocks/$height0" \
  | jq -e --arg id "$message_id" --arg root "$root0" \
      '.stateRoot == $root and .certSignatures == 2 and .messages[0].messageId == $id' >/dev/null
curl -fsS "$api0/messages/$message_id" \
  | jq -e --arg id "$message_id" '.messageId == $id and .height > 0' >/dev/null
curl -fsS "$api0/proof/$message_id" \
  | jq -e --arg id "$message_id" --arg root "$root0" \
      '.key == $id and .stateRoot == $root and .proofWireHex != ""' >/dev/null
curl -fsS "$api0/evidence/$message_id" \
  | jq -e --arg id "$message_id" \
      '.messageId == $id and (.blocksCbor | length) > 0 and .threshold == 2' >/dev/null

old_pid="$(cat "$project/run/node1.pid")"
kill "$old_pid"
for _ in $(seq 1 20); do
  kill -0 "$old_pid" 2>/dev/null || break
  sleep 1
done
rm -f "$project/run/node1.pid"
"$project/scripts/start-node" 1 >"$project/logs/node1-restart.log" 2>&1 &
new_pid=$!
printf '%s\n' "$new_pid" >"$project/run/node1.pid"
for _ in $(seq 1 90); do
  curl -fsS "http://127.0.0.1:$((http_base + 1))/q/health/ready" >/dev/null 2>&1 && break
  sleep 1
done
for _ in $(seq 1 60); do
  restarted="$(curl -fsS "$api1/tip")"
  [ "$(jq -r '.height' <<<"$restarted")" = "$height0" ] \
    && [ "$(jq -r '.stateRoot' <<<"$restarted")" = "$root0" ] && break
  sleep 1
done
[ "$(jq -r '.height' <<<"$restarted")" = "$height0" ]
[ "$(jq -r '.stateRoot' <<<"$restarted")" = "$root0" ]

! grep -ER \
  '0101010101010101010101010101010101010101010101010101010101010101|0202020202020202020202020202020202020202020202020202020202020202|packaged-acceptance-operator' \
  "$project/logs"
for record in "$project"/run/node*.pid; do
  pid="$(cat "$record")"
  ! ps -p "$pid" -o command= | grep -E '010101010101|020202020202|packaged-acceptance-operator'
done

"$project/scripts/stop"
[ ! -e "$project/run/node0.pid" ] && [ ! -e "$project/run/node1.pid" ]
trap - EXIT
cleanup
echo "PASS: final distribution app-chain runtime, identities, drift, proof, restart, and secret gates"
