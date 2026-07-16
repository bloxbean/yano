#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEMO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_DIR="$(cd "$DEMO_DIR/../.." && pwd)"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/yano-demo-launcher.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
mode() {
  if stat -c '%a' "$1" >/dev/null 2>&1; then stat -c '%a' "$1"; else stat -f '%Lp' "$1"; fi
}

export DEMO_SKIP_BUILD=true
export DEMO_DATA_ROOT="$TMP/data"
export DEMO_SECRET_ROOT="$TMP/secrets"
export DEMO_RUNTIME_ROOT="$TMP/runtime"

bash -n "$DEMO_DIR/demo.sh"
grep -Fq 'tools/render_template.py' "$DEMO_DIR/demo.sh" \
  || fail "launcher does not use the stdin-based template renderer"
if grep -Fq 'sed "s|@$key@|' "$DEMO_DIR/demo.sh"; then
  fail "template values can leak through an external sed command argument"
fi
OUT1="$TMP/config-1.yml"
"$DEMO_DIR/demo.sh" config --instance launcher > "$OUT1"

SECRET_DIR="$TMP/secrets/devnet/launcher"
RUNTIME_DIR="$TMP/runtime/devnet/launcher"
NODE_DIR="$SECRET_DIR/nodes-compose"
[ "$(mode "$SECRET_DIR")" = 700 ] || fail "secret directory is not 0700"
for file in "$SECRET_DIR"/* "$NODE_DIR"/*.properties; do
  [ -d "$file" ] && continue
  [ "$(mode "$file")" = 600 ] || fail "private file is not 0600: $file"
done

for file in "$SECRET_DIR"/yano-api-key "$SECRET_DIR"/minio-*-key \
  "$SECRET_DIR"/minio-*-password; do
  [ -f "$file" ] || continue
  value="$(tr -d '\r\n' < "$file")"
  ! grep -Fq "$value" "$OUT1" || fail "launcher rendered a secret value"
  if grep -R -Fq -- "$value" "$RUNTIME_DIR"; then
    fail "raw secret persisted outside the private secret tree"
  fi
done

[ "$(grep -hF 'effects.executor.enabled=true' "$NODE_DIR"/*.properties | wc -l | tr -d ' ')" -eq 1 ] \
  || fail "executor ownership is not unique"
[ "$(grep -hF 'effects.executor.enabled=false' "$NODE_DIR"/*.properties | wc -l | tr -d ' ')" -eq 2 ] \
  || fail "followers are not explicitly non-executors"
grep -Fq 'anchor.signing-key=3030303030303030303030303030303030303030303030303030303030303030' \
  "$NODE_DIR/node0.properties" || fail "anchor seed was rendered incorrectly"
grep -Fq 'anchor.every-blocks=1' "$NODE_DIR/node0.properties" \
  || fail "demo does not anchor the terminal READY block deterministically"
grep -Fq 'signing-key=0101010101010101010101010101010101010101010101010101010101010101' \
  "$NODE_DIR/node0.properties" || fail "node 0 seed does not match its member public key"
grep -Fq 'signing-key=0202020202020202020202020202020202020202020202020202020202020202' \
  "$NODE_DIR/node1.properties" || fail "node 1 seed does not match its member public key"
grep -Fq 'signing-key=0303030303030303030303030303030303030303030303030303030303030303' \
  "$NODE_DIR/node2.properties" || fail "node 2 seed does not match its member public key"
expected_members='yano.app-chain.chains[0].members=8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c,8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394,ed4928c628d1c2c6eae90338905995612959273a5c63f93636c14614ac8737d1'
[ "$(grep -hFx "$expected_members" "$NODE_DIR"/*.properties | wc -l | tr -d ' ')" -eq 3 ] \
  || fail "generated membership does not exactly match the three frozen public keys"
grep -Fq 'chains[3].anchor' "$NODE_DIR/node0.properties" \
  && fail "anchor key changed the chain index"

genesis="$TMP/data/devnet/launcher/l1/shared/shelley-genesis.json"
timestamp="$TMP/data/devnet/launcher/l1/shared/genesis-timestamp"
[ "$(jq -r .systemStart "$genesis")" != null ] || fail "shared genesis has no systemStart"
[ "$(jq -r .epochLength "$genesis")" = 500 ] || fail "shared genesis epoch length is not bounded"
configured="$(grep -h '^yano.block-producer.genesis-timestamp=' "$NODE_DIR"/*.properties \
  | cut -d= -f2 | sort -u)"
[ "$configured" = "$(cat "$timestamp")" ] || fail "nodes do not share the explicit genesis timestamp"
expected_start="$(python3 - "$timestamp" <<'PY'
from datetime import datetime, timezone
from pathlib import Path
import sys
millis = int(Path(sys.argv[1]).read_text().strip())
print(datetime.fromtimestamp(millis / 1000, timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'))
PY
)"
[ "$(jq -r .systemStart "$genesis")" = "$expected_start" ] \
  || fail "Shelley systemStart and explicit timestamp were not derived from one instant"

before="$(shasum -a 256 "$SECRET_DIR/yano-api-key" "$genesis")"
OUT2="$TMP/config-2.yml"
"$DEMO_DIR/demo.sh" config --instance launcher > "$OUT2"
after="$(shasum -a 256 "$SECRET_DIR/yano-api-key" "$genesis")"
[ "$before" = "$after" ] || fail "re-render rotated credentials or devnet identity"

RUNNER_CONFIG="$TMP/runtime/devnet/launcher/runner-compose.properties"
expected_runner_members='demo.yano.member-keys=8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c,8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394,ed4928c628d1c2c6eae90338905995612959273a5c63f93636c14614ac8737d1'
grep -Fxq "$expected_runner_members" "$RUNNER_CONFIG" \
  || fail "compose runner does not pin the exact devnet member keys"
grep -Fxq 'demo.yano.threshold=2' "$RUNNER_CONFIG" \
  || fail "compose runner does not pin the 2-of-3 finality threshold"
VALIDATION_JAR="${DEMO_RUNNER_VALIDATION_JAR:-}"
if [ -z "$VALIDATION_JAR" ]; then
  "$REPO_DIR/gradlew" -p "$REPO_DIR" --no-daemon \
    :appchain-evidence-demo-runner:shadowJar >/dev/null
  VALIDATION_JAR="$(find \
    "$REPO_DIR/appchain/examples/appchain-evidence-demo-runner/build/libs" \
    -maxdepth 1 -type f -name '*-all.jar' -print | sort | tail -n 1)"
fi
[ -f "$VALIDATION_JAR" ] || fail "evidence demo runner validation jar is missing"
PARSE_CONFIG="$TMP/runner-compose-host-parse.properties"
sed \
  -e "s|/run/secrets/minio-runner-access-key|$SECRET_DIR/minio-runner-access-key|g" \
  -e "s|/run/secrets/minio-runner-secret-key|$SECRET_DIR/minio-runner-secret-key|g" \
  "$RUNNER_CONFIG" > "$PARSE_CONFIG"
java --add-modules=jdk.httpserver -jar "$VALIDATION_JAR" validate-config \
  --config "$PARSE_CONFIG" > "$TMP/runner-config-validation.out"
grep -Fxq 'PASS command=validate-config' "$TMP/runner-config-validation.out" \
  || fail "generated compose runner configuration did not parse"
for key in s3 ipfs kafka; do
  first_id="$(sed -n "s/^${key}.target-id=//p" "$RUNNER_CONFIG")"
  [[ "$first_id" =~ ^[a-z][a-z0-9-]{0,62}$ ]] \
    || fail "$key target id is not a bounded portable identifier"
done
"$DEMO_DIR/demo.sh" config --instance launcher-peer > "$TMP/config-peer.yml"
PEER_RUNNER="$TMP/runtime/devnet/launcher-peer/runner-compose.properties"
for key in s3 ipfs kafka; do
  first_id="$(sed -n "s/^${key}.target-id=//p" "$RUNNER_CONFIG")"
  peer_id="$(sed -n "s/^${key}.target-id=//p" "$PEER_RUNNER")"
  [ "$first_id" != "$peer_id" ] || fail "$key target id was reused across instances"
  [ "$peer_id" = "${key}-compose-devnet-launcher-peer" ] \
    || fail "$key target id did not use the normalized instance identity"
done

if "$DEMO_DIR/demo.sh" config --instance launcher_peer \
    >"$TMP/invalid-instance.out" 2>&1; then
  fail "underscore-bearing instance unexpectedly succeeded"
fi
grep -Fq '[a-z0-9][a-z0-9-]{0,31}' "$TMP/invalid-instance.out" \
  || fail "instance rejection does not publish the canonical grammar"

if DEMO_CHAIN_ID='Evidence_Chain' "$DEMO_DIR/demo.sh" config \
    --instance invalid-chain >"$TMP/invalid-chain.out" 2>&1; then
  fail "unsafe chain id unexpectedly succeeded"
fi
grep -Fq '[a-z][a-z0-9-]{0,62}' "$TMP/invalid-chain.out" \
  || fail "chain-id rejection does not publish the canonical grammar"

"$DEMO_DIR/demo.sh" config --instance placeholder-value \
  >"$TMP/placeholder-initial.out"
PLACEHOLDER_KEY="$TMP/secrets/devnet/placeholder-value/yano-api-key"
printf '%s\n' '@CHAIN_ID@' > "$PLACEHOLDER_KEY"
chmod 600 "$PLACEHOLDER_KEY"
if "$DEMO_DIR/demo.sh" config --instance placeholder-value \
    >"$TMP/placeholder-rejected.out" 2>&1; then
  fail "placeholder-shaped template value unexpectedly succeeded"
fi
grep -Fq 'template value API_KEY must not contain placeholder syntax' \
  "$TMP/placeholder-rejected.out" \
  || fail "placeholder-shaped template value rejection is unclear"

if "$DEMO_DIR/demo.sh" clean --instance launcher >"$TMP/clean.out" 2>&1; then
  fail "clean unexpectedly succeeded in Phase 1.5"
fi
grep -Fq 'deferred to Phase 1.6' "$TMP/clean.out" \
  || fail "clean rejection does not explain lifecycle ownership"
if "$DEMO_DIR/demo.sh" config --network preview >"$TMP/public.out" 2>&1; then
  fail "public-network config unexpectedly succeeded"
fi
grep -Fq 'Phase 1.6' "$TMP/public.out" || fail "public-network guard is unclear"

# Exercise host rendering without building: create only the files verified by
# the prepare handoff. No executable is started.
HOST_RUNTIME="$TMP/runtime/devnet/hosttest"
mkdir -p "$HOST_RUNTIME/plugins"
for name in appchain-kafka appchain-ipfs appchain-objectstore-s3 appchain-evidence-registry; do
  : > "$HOST_RUNTIME/plugins/$name-bundle.jar"
done
: > "$HOST_RUNTIME/runner.jar"
: > "$HOST_RUNTIME/yano.jar"
mkdir -p "$TMP/bin"
printf '#!/usr/bin/env sh\nexit 88\n' > "$TMP/bin/docker"
chmod 755 "$TMP/bin/docker"
PATH="$TMP/bin:$PATH" "$DEMO_DIR/demo.sh" prepare --deployment host \
  --instance hosttest > "$TMP/host.out"
HOST_CONFIG="$HOST_RUNTIME/runner-host.properties"
HOST_NODES="$TMP/secrets/devnet/hosttest/nodes-host"
[ -f "$HOST_CONFIG" ] && [ -f "$HOST_NODES/node0.properties" ] \
  || fail "host mode did not render the shared runner and node overlays"
if grep -Eq '^ui\.' "$HOST_CONFIG"; then
  fail "host scenario runner configuration contains UI-only keys"
fi
grep -Fq 'demo.yano.api-key-file' "$HOST_CONFIG" \
  && fail "host scenario runner contains the full Yano admin key path"
grep -Fq 'demo.yano.urls=http://127.0.0.1:7070/api/v1' "$HOST_CONFIG" \
  || fail "host runner does not use the same Yano URL contract"
grep -Fxq "$expected_runner_members" "$HOST_CONFIG" \
  || fail "host runner does not pin the exact devnet member keys"
grep -Fxq 'demo.yano.threshold=2' "$HOST_CONFIG" \
  || fail "host runner does not pin the 2-of-3 finality threshold"
java --add-modules=jdk.httpserver -jar "$VALIDATION_JAR" validate-config \
  --config "$HOST_CONFIG" > "$TMP/host-runner-config-validation.out"
grep -Fxq 'PASS command=validate-config' "$TMP/host-runner-config-validation.out" \
  || fail "generated host runner configuration did not parse"
grep -Fq 'effects.executor.enabled=true' "$HOST_NODES/node0.properties" \
  || fail "host node 0 is not the executor"
grep -Fq 'effects.executor.enabled=false' "$HOST_NODES/node1.properties" \
  || fail "host follower can execute effects"

HOST_CHAIN_RUNTIME="$TMP/runtime/devnet/hostchain"
mkdir -p "$HOST_CHAIN_RUNTIME/plugins"
for name in appchain-kafka appchain-ipfs appchain-objectstore-s3 appchain-evidence-registry; do
  : > "$HOST_CHAIN_RUNTIME/plugins/$name-bundle.jar"
done
: > "$HOST_CHAIN_RUNTIME/runner.jar"
: > "$HOST_CHAIN_RUNTIME/yano.jar"
DEMO_CHAIN_ID=custom-evidence-chain PATH="$TMP/bin:$PATH" \
  "$DEMO_DIR/demo.sh" prepare --deployment host --instance hostchain \
  > "$TMP/host-chain.out"
HOST_CHAIN_RUNNER="$HOST_CHAIN_RUNTIME/runner-host.properties"
HOST_CHAIN_APP="$HOST_CHAIN_RUNTIME/host-home/config/application-appchain.yml"
grep -Fxq 'demo.chain-id=custom-evidence-chain' "$HOST_CHAIN_RUNNER" \
  || fail "custom host chain id did not reach the runner"
grep -Fxq '      chain-id: "custom-evidence-chain"' "$HOST_CHAIN_APP" \
  || fail "custom host chain id did not reach cluster application config"
parsed_host_chain="$(grep -vE '^[[:space:]]*#' "$HOST_CHAIN_APP" \
  | grep -oE 'chain-id:[[:space:]]*"[^"]+"' \
  | sed -E 's/.*"([^"]+)".*/\1/')"
[ "$parsed_host_chain" = custom-evidence-chain ] \
  || fail "cluster launcher cannot discover the rendered custom chain id"
grep -Fq 'host_cluster anchor-bootstrap "$DEMO_CHAIN_ID"' "$DEMO_DIR/demo.sh" \
  || fail "host anchor bootstrap is not bound to the selected chain id"

if DEMO_HOST_S3_ENDPOINT=http://127.0.0.1:19000 \
    PATH="$TMP/bin:$PATH" "$DEMO_DIR/demo.sh" prepare --deployment host \
    --instance hostoverride >"$TMP/host-override.out" 2>&1; then
  fail "host endpoint override succeeded without an explicit immutable target id"
fi
grep -Fq 'DEMO_HOST_S3_TARGET_ID is required' "$TMP/host-override.out" \
  || fail "host endpoint override rejection does not identify its target-id requirement"

printf 'PASS: launcher rendering and lifecycle guards\n'
