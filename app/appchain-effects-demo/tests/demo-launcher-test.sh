#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEMO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_DIR="$(cd "$DEMO_DIR/../.." && pwd)"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/yano-demo-launcher.XXXXXX")"
TMP="$(cd "$TMP" && pwd -P)"
trap 'rm -rf "$TMP"' EXIT

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
mode() {
  if stat -c '%a' "$1" >/dev/null 2>&1; then stat -c '%a' "$1"; else stat -f '%Lp' "$1"; fi
}
env_value() { sed -n "s/^$1=//p" "$2"; }
assert_exists() { [ -e "$1" ] || fail "expected path is missing: $1"; }
assert_absent() { [ ! -e "$1" ] || fail "unexpected path exists: $1"; }

command -v docker >/dev/null 2>&1 || fail "docker is required for Compose config validation"
docker compose version >/dev/null 2>&1 || fail "docker compose is required"
command -v jq >/dev/null 2>&1 || fail "jq is required"

export DEMO_SKIP_BUILD=true
export DEMO_DATA_ROOT="$TMP/data"
export DEMO_SECRET_ROOT="$TMP/secrets"
export DEMO_RUNTIME_ROOT="$TMP/runtime"

for profile in devnet preview preprod mainnet; do
  profile_file="$DEMO_DIR/config/networks/$profile.env"
  [ "$(sed -n 's/^anchor_max_interval_minutes=//p' "$profile_file")" = 60 ] \
    || fail "$profile profile does not pin the 60-minute anchor safety interval"
done

bash -n "$DEMO_DIR/demo.sh"
grep -Fq 'tools/render_template.py' "$DEMO_DIR/demo.sh" \
  || fail "launcher does not use the stdin-based template renderer"
grep -Fq 'tools/managed_process.py' "$DEMO_DIR/demo.sh" \
  || fail "host UI lifecycle does not use the exact managed-process helper"
if grep -Fq 'host-ui.pid' "$DEMO_DIR/demo.sh"; then
  fail "host UI lifecycle still relies on a bare PID file"
fi
if grep -Fq 'sed "s|@$key@|' "$DEMO_DIR/demo.sh"; then
  fail "template values can leak through an external sed command argument"
fi

# Invalid identity and numeric inputs must fail before creating any managed root.
INVALID_ROOT="$TMP/invalid"
if DEMO_DATA_ROOT="$INVALID_ROOT/data" DEMO_SECRET_ROOT="$INVALID_ROOT/secrets" \
    DEMO_RUNTIME_ROOT="$INVALID_ROOT/runtime" \
    "$DEMO_DIR/demo.sh" config --network not-a-network \
    >"$TMP/invalid-network.out" 2>&1; then
  fail "invalid network unexpectedly succeeded"
fi
grep -Fq -- '--network must be exactly' "$TMP/invalid-network.out" \
  || fail "invalid network rejection is unclear"
assert_absent "$INVALID_ROOT"

if DEMO_DATA_ROOT="$INVALID_ROOT/data" DEMO_SECRET_ROOT="$INVALID_ROOT/secrets" \
    DEMO_RUNTIME_ROOT="$INVALID_ROOT/runtime" DEMO_HTTP_BASE=not-a-number \
    "$DEMO_DIR/demo.sh" config --instance invalid-port \
    >"$TMP/invalid-number.out" 2>&1; then
  fail "invalid numeric input unexpectedly succeeded"
fi
grep -Fq 'DEMO_HTTP_BASE must be a decimal integer' "$TMP/invalid-number.out" \
  || fail "invalid numeric rejection is unclear"
assert_absent "$INVALID_ROOT"

OVERLAP_ROOT="$TMP/overlap"
if DEMO_DATA_ROOT="$OVERLAP_ROOT/data" DEMO_SECRET_ROOT="$OVERLAP_ROOT/data/secrets" \
    DEMO_RUNTIME_ROOT="$OVERLAP_ROOT/runtime" \
    "$DEMO_DIR/demo.sh" config --instance overlapping \
    >"$TMP/overlap.out" 2>&1; then
  fail "overlapping managed roots unexpectedly succeeded"
fi
grep -Fq 'must be pairwise disjoint' "$TMP/overlap.out" \
  || fail "overlapping managed-root rejection is unclear"
assert_absent "$OVERLAP_ROOT"

SECRET_IN_DATA="$TMP/secret-in-data"
mkdir -p "$SECRET_IN_DATA/data"
printf '%064x\n' 12 > "$SECRET_IN_DATA/data/anchor.seed"
chmod 600 "$SECRET_IN_DATA/data/anchor.seed"
if DEMO_DATA_ROOT="$SECRET_IN_DATA/data" DEMO_SECRET_ROOT="$SECRET_IN_DATA/secrets" \
    DEMO_RUNTIME_ROOT="$SECRET_IN_DATA/runtime" \
    "$DEMO_DIR/demo.sh" config --network preview --instance unsafe-secret \
    --anchor-key-file "$SECRET_IN_DATA/data/anchor.seed" \
    --confirm-public-anchor preview >"$TMP/secret-in-data.out" 2>&1; then
  fail "cleanup-managed anchor secret unexpectedly succeeded"
fi
grep -Fq 'secret material must not be stored below' "$TMP/secret-in-data.out" \
  || fail "cleanup-managed secret rejection is unclear"
assert_absent "$SECRET_IN_DATA/data/networks"

BAD_LOCATOR_ROOT="$TMP/bad-locator"
if DEMO_DATA_ROOT="$BAD_LOCATOR_ROOT/data" DEMO_SECRET_ROOT="$BAD_LOCATOR_ROOT/secrets" \
    DEMO_RUNTIME_ROOT="$BAD_LOCATOR_ROOT/runtime" \
    DEMO_HOST_S3_ENDPOINT='http://user:password@127.0.0.1:9000/?token=secret' \
    DEMO_HOST_S3_TARGET_ID=bad-locator \
    "$DEMO_DIR/demo.sh" prepare --deployment host --instance bad-locator \
    >"$TMP/bad-locator.out" 2>&1; then
  fail "credential-bearing host connector endpoint unexpectedly succeeded"
fi
grep -Fq 'host connector locators must be bounded credential-free' "$TMP/bad-locator.out" \
  || fail "credential-bearing connector rejection is unclear"
assert_absent "$BAD_LOCATOR_ROOT"

# Read-only commands for an absent instance must not create data, secrets, or runtime files.
MISSING_ROOT="$TMP/missing"
DEMO_DATA_ROOT="$MISSING_ROOT/data" DEMO_SECRET_ROOT="$MISSING_ROOT/secrets" \
  DEMO_RUNTIME_ROOT="$MISSING_ROOT/runtime" \
  "$DEMO_DIR/demo.sh" status --instance absent >"$TMP/status-missing.out"
DEMO_DATA_ROOT="$MISSING_ROOT/data" DEMO_SECRET_ROOT="$MISSING_ROOT/secrets" \
  DEMO_RUNTIME_ROOT="$MISSING_ROOT/runtime" \
  "$DEMO_DIR/demo.sh" stop --instance absent >"$TMP/stop-missing.out"
grep -Fq "Instance 'absent' is not prepared" "$TMP/status-missing.out" \
  || fail "missing-instance status is unclear"
grep -Fq "nothing to stop" "$TMP/stop-missing.out" \
  || fail "missing-instance stop is unclear"
assert_absent "$MISSING_ROOT"

# A chain ID is a network-wide permanent claim, not an instance-local name.
# Concurrent first use by different instances must publish exactly one active
# marker, and losing launchers must not leave a partial deployment directory.
CONTENTION_ROOT="$TMP/chain-contention"
CONTENTION_NETWORK="$CONTENTION_ROOT/data/networks/devnet"
CONTENTION_CHAIN="evidence-chain-contention"
CONTENTION_COUNT=16
mkdir -p "$CONTENTION_ROOT"
contention_pids=()
i=1
while [ "$i" -le "$CONTENTION_COUNT" ]; do
  instance="contender-$i"
  (
    if DEMO_DATA_ROOT="$CONTENTION_ROOT/data" \
        DEMO_SECRET_ROOT="$CONTENTION_ROOT/secrets" \
        DEMO_RUNTIME_ROOT="$CONTENTION_ROOT/runtime" \
        "$DEMO_DIR/demo.sh" config --instance "$instance" \
        --chain-id "$CONTENTION_CHAIN" \
        >"$CONTENTION_ROOT/$instance.out" 2>&1; then
      printf '0\n' > "$CONTENTION_ROOT/$instance.status"
    else
      printf '%s\n' "$?" > "$CONTENTION_ROOT/$instance.status"
    fi
  ) &
  contention_pids+=("$!")
  i=$((i + 1))
done
for pid in "${contention_pids[@]}"; do
  wait "$pid"
done

contention_successes=0
contention_winner=""
i=1
while [ "$i" -le "$CONTENTION_COUNT" ]; do
  instance="contender-$i"
  status="$(tr -d '[:space:]' < "$CONTENTION_ROOT/$instance.status")"
  marker="$CONTENTION_NETWORK/instances/$instance/compose/appchain-identity.json"
  if [ "$status" = 0 ]; then
    contention_successes=$((contention_successes + 1))
    contention_winner="$instance"
    assert_exists "$marker"
  else
    assert_absent "$CONTENTION_NETWORK/instances/$instance/compose"
    assert_absent "$marker"
  fi
  i=$((i + 1))
done
[ "$contention_successes" -eq 1 ] \
  || fail "same-chain contention produced $contention_successes successful launchers instead of one"
[ -n "$contention_winner" ] || fail "same-chain contention did not identify its winner"
[ "$(find "$CONTENTION_NETWORK/instances" -type f -name appchain-identity.json \
    -print | wc -l | tr -d ' ')" -eq 1 ] \
  || fail "same-chain contention published more than one active marker"
jq -e --arg instance "$contention_winner" --arg chain "$CONTENTION_CHAIN" '
  .instanceId == $instance and .chainIds == [$chain]
' "$CONTENTION_NETWORK/instances/$contention_winner/compose/appchain-identity.json" \
  >/dev/null || fail "same-chain contention winner published the wrong identity"

OUT1="$TMP/config-1.yml"
"$DEMO_DIR/demo.sh" config --instance launcher > "$OUT1"

NETWORK_ROOT="$TMP/data/networks/devnet"
INSTANCE_ROOT="$NETWORK_ROOT/instances/launcher/compose"
L1_ROOT="$NETWORK_ROOT/l1/compose"
SHARED_GENESIS_ROOT="$NETWORK_ROOT/l1/shared"
SECRET_DIR="$TMP/secrets/networks/devnet/launcher/compose"
RUNTIME_DIR="$TMP/runtime/networks/devnet/launcher/compose"
NODE_DIR="$SECRET_DIR/nodes-compose"
MEMBER_DIR="$SECRET_DIR/member-keys"
ENV_FILE="$RUNTIME_DIR/compose.env"
NETWORK_MARKER="$NETWORK_ROOT/network-identity.json"
INSTANCE_MARKER="$INSTANCE_ROOT/appchain-identity.json"

for path in "$NETWORK_MARKER" "$INSTANCE_MARKER" "$L1_ROOT" "$SHARED_GENESIS_ROOT" \
  "$SECRET_DIR" "$RUNTIME_DIR"; do assert_exists "$path"; done
[ "$(mode "$SECRET_DIR")" = 700 ] || fail "secret directory is not 0700"
[ "$(mode "$NETWORK_MARKER")" = 600 ] || fail "network marker is not private 0600"
[ "$(mode "$INSTANCE_MARKER")" = 600 ] || fail "instance marker is not private 0600"
for file in "$SECRET_DIR"/* "$NODE_DIR"/*.properties "$MEMBER_DIR"/*; do
  [ -d "$file" ] && continue
  [ "$(mode "$file")" = 600 ] || fail "private file is not 0600: $file"
done

jq -e '
  .schemaVersion == 1
  and .kind == "yano.demo.network-identity"
  and .networkName == "devnet"
  and .protocolMagic == 42
' "$NETWORK_MARKER" >/dev/null || fail "network marker identity is incomplete"
jq -e '
  .schemaVersion == 1
  and .kind == "yano.demo.appchain-identity"
  and .networkName == "devnet"
  and .instanceId == "launcher"
  and .deployment == "compose"
  and .chainIds == ["evidence-chain-launcher"]
  and .membership.threshold == 2
  and .anchor.enabled == true
  and .anchor.everyBlocks == 1
  and .anchor.maxIntervalMinutes == 60
  and .effects.storageGate == "l1-anchored"
' "$INSTANCE_MARKER" >/dev/null || fail "instance marker identity is incomplete"
anchor_fingerprint="$(python3 - "$SECRET_DIR/anchor.seed" <<'PY'
import hashlib
from pathlib import Path
import sys
seed = Path(sys.argv[1]).read_text(encoding="ascii").strip().encode("ascii")
print(hashlib.sha256(b"yano-demo-anchor-signer-v1\0" + seed).hexdigest())
PY
)"
[ "$(jq -r .anchor.signerFingerprint "$INSTANCE_MARKER")" = "$anchor_fingerprint" ] \
  || fail "anchor marker fingerprint does not derive from the persisted seed"

# Persisted member seeds must derive the recorded public identities and rendered membership.
python3 - "$DEMO_DIR/tools/ed25519_keys.py" "$MEMBER_DIR" <<'PY' \
  || fail "persisted member seeds do not derive their public identities"
import importlib.util
from pathlib import Path
import sys

spec = importlib.util.spec_from_file_location("ed25519_keys", sys.argv[1])
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
directory = Path(sys.argv[2])
for index in range(3):
    seed = bytes.fromhex((directory / f"node{index}.seed").read_text().strip())
    public = (directory / f"node{index}.public").read_text().strip()
    if module.private_to_public(seed).hex() != public:
        raise SystemExit(1)
PY
expected_members="$(for i in 0 1 2; do tr -d '\r\n' < "$MEMBER_DIR/node$i.public"; \
  [ "$i" -eq 2 ] || printf ','; done)"
[ "$(grep -hFx "yano.app-chain.chains[0].members=$expected_members" \
  "$NODE_DIR"/*.properties | wc -l | tr -d ' ')" -eq 3 ] \
  || fail "rendered nodes do not use the persisted member public keys"
grep -Fxq "demo.yano.member-keys=$expected_members" "$RUNTIME_DIR/runner-compose.properties" \
  || fail "runner membership differs from node membership"
[ "$(grep -hF 'effects.executor.enabled=true' "$NODE_DIR"/*.properties | wc -l | tr -d ' ')" -eq 1 ] \
  || fail "executor ownership is not unique"
[ "$(grep -hF 'effects.executor.enabled=false' "$NODE_DIR"/*.properties | wc -l | tr -d ' ')" -eq 2 ] \
  || fail "followers are not explicitly non-executors"
grep -Fq 'anchor.every-blocks=1' "$NODE_DIR/node0.properties" \
  || fail "devnet anchor cadence is not deterministic"
grep -Fxq 'yano.app-chain.chains[0].anchor.max-interval-minutes=60' \
  "$NODE_DIR/node0.properties" \
  || fail "devnet Compose anchor does not use the profile safety interval"

for file in "$SECRET_DIR"/yano-api-key "$SECRET_DIR"/anchor.seed \
  "$SECRET_DIR"/minio-*-key "$SECRET_DIR"/minio-*-password "$MEMBER_DIR"/*.seed; do
  [ -f "$file" ] || continue
  value="$(tr -d '\r\n' < "$file")"
  ! grep -Fq "$value" "$OUT1" || fail "launcher rendered a secret value"
  if grep -R -Fq -- "$value" "$RUNTIME_DIR"; then
    fail "raw secret persisted outside the private secret tree"
  fi
done

genesis="$SHARED_GENESIS_ROOT/shelley-genesis.json"
timestamp="$SHARED_GENESIS_ROOT/genesis-timestamp"
[ "$(jq -r .systemStart "$genesis")" != null ] || fail "shared genesis has no systemStart"
[ "$(jq -r .epochLength "$genesis")" = 500 ] || fail "shared genesis epoch is not bounded"
configured="$(grep -h '^yano.block-producer.genesis-timestamp=' "$NODE_DIR"/*.properties \
  | cut -d= -f2 | sort -u)"
[ "$configured" = "$(cat "$timestamp")" ] || fail "nodes do not share the genesis timestamp"
expected_start="$(python3 - "$timestamp" <<'PY'
from datetime import datetime, timezone
from pathlib import Path
import sys
millis = int(Path(sys.argv[1]).read_text().strip())
print(datetime.fromtimestamp(millis / 1000, timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'))
PY
)"
[ "$(jq -r .systemStart "$genesis")" = "$expected_start" ] \
  || fail "Shelley systemStart and timestamp differ"

# Project and connector target identities carry the same path-derived suffix.
project="$(env_value DEMO_PROJECT_NAME "$ENV_FILE")"
scope="${project##*-}"
[[ "$scope" =~ ^[0-9a-f]{8}$ ]] || fail "Compose project has no bounded dynamic suffix"
[ "$project" = "yano-effects-devnet-launcher-$scope" ] \
  || fail "Compose project is not scoped to network, instance and path"
RUNNER_CONFIG="$RUNTIME_DIR/runner-compose.properties"
for key in s3 ipfs kafka; do
  actual="$(sed -n "s/^${key}.target-id=//p" "$RUNNER_CONFIG")"
  [ "$actual" = "${key}-compose-devnet-launcher-$scope" ] \
    || fail "$key target identity does not share the project suffix"
done

# Re-rendering must preserve all immutable identity and secret material.
before="$(shasum -a 256 "$NETWORK_MARKER" "$INSTANCE_MARKER" \
  "$SECRET_DIR/yano-api-key" "$SECRET_DIR/anchor.seed" "$MEMBER_DIR"/*.seed "$genesis")"
"$DEMO_DIR/demo.sh" config --instance launcher > "$TMP/config-2.yml"
after="$(shasum -a 256 "$NETWORK_MARKER" "$INSTANCE_MARKER" \
  "$SECRET_DIR/yano-api-key" "$SECRET_DIR/anchor.seed" "$MEMBER_DIR"/*.seed "$genesis")"
[ "$before" = "$after" ] || fail "re-render changed immutable identity or credentials"

# Once anchored history exists, startup is bound to one canonical script
# identity. Missing or tampered bindings fail before any process can start.
ANCHOR_BINDING="$INSTANCE_ROOT/anchor-binding.json"
for i in 0 1 2; do
  mkdir -p "$INSTANCE_ROOT/app-chain/node$i/evidence-chain-launcher"
  printf 'test-current\n' > "$INSTANCE_ROOT/app-chain/node$i/evidence-chain-launcher/CURRENT"
done
python3 - "$ANCHOR_BINDING" <<'PY'
import json, os, sys
document = {
    "schemaVersion": 1,
    "kind": "yano.demo.anchor-binding",
    "networkName": "devnet",
    "instanceId": "launcher",
    "deployment": "compose",
    "chainId": "evidence-chain-launcher",
    "threadPolicyId": "11" * 28,
    "scriptHash": "22" * 28,
    "scriptAddress": "addr_test1" + "q" * 40,
    "lastAdoptedHeight": 7,
    "verifiedMembers": 3,
    "verifiedAtMillis": 1,
}
with open(sys.argv[1], "w") as out:
    json.dump(document, out, sort_keys=True, separators=(",", ":"))
    out.write("\n")
os.chmod(sys.argv[1], 0o600)
PY
"$DEMO_DIR/demo.sh" config --instance launcher > "$TMP/config-binding-valid.yml"
cp "$ANCHOR_BINDING" "$TMP/anchor-binding.saved"
python3 - "$ANCHOR_BINDING" <<'PY'
import json, os, sys
path = sys.argv[1]
document = json.load(open(path))
document["chainId"] = "tampered-chain"
with open(path, "w") as out:
    json.dump(document, out, sort_keys=True, separators=(",", ":"))
    out.write("\n")
os.chmod(path, 0o600)
PY
if "$DEMO_DIR/demo.sh" config --instance launcher >"$TMP/tampered-binding.out" 2>&1; then
  fail "tampered anchor binding unexpectedly succeeded"
fi
grep -Fq 'anchor binding identity is invalid' "$TMP/tampered-binding.out" \
  || fail "tampered anchor binding rejection is unclear"
cp "$TMP/anchor-binding.saved" "$ANCHOR_BINDING"
chmod 600 "$ANCHOR_BINDING"
rm "$ANCHOR_BINDING"
if "$DEMO_DIR/demo.sh" config --instance launcher >"$TMP/missing-binding.out" 2>&1; then
  fail "anchored history without its binding unexpectedly succeeded"
fi
grep -Fq 'retained anchored history has no immutable anchor binding' "$TMP/missing-binding.out" \
  || fail "missing anchor binding rejection is unclear"
cp "$TMP/anchor-binding.saved" "$ANCHOR_BINDING"
chmod 600 "$ANCHOR_BINDING"

cp "$INSTANCE_MARKER" "$TMP/instance-marker.saved"
python3 - "$INSTANCE_MARKER" <<'PY'
import json, os, sys
path = sys.argv[1]
document = json.load(open(path))
document["chainIds"] = ["tampered-chain"]
with open(path, "w") as out:
    json.dump(document, out, sort_keys=True, separators=(",", ":"))
    out.write("\n")
os.chmod(path, 0o600)
PY
if "$DEMO_DIR/demo.sh" config --instance launcher >"$TMP/tampered-instance.out" 2>&1; then
  fail "tampered instance marker unexpectedly succeeded"
fi
grep -Fq 'app-chain identity mismatch' "$TMP/tampered-instance.out" \
  || fail "tampered instance marker rejection is unclear"
cp "$TMP/instance-marker.saved" "$INSTANCE_MARKER"
chmod 600 "$INSTANCE_MARKER"

cp "$NETWORK_MARKER" "$TMP/network-marker.saved"
python3 - "$NETWORK_MARKER" <<'PY'
import json, os, sys
path = sys.argv[1]
document = json.load(open(path))
document["protocolMagic"] += 1
with open(path, "w") as out:
    json.dump(document, out, sort_keys=True, separators=(",", ":"))
    out.write("\n")
os.chmod(path, 0o600)
PY
if "$DEMO_DIR/demo.sh" config --instance launcher >"$TMP/tampered-network.out" 2>&1; then
  fail "tampered network marker unexpectedly succeeded"
fi
grep -Fq 'network identity mismatch' "$TMP/tampered-network.out" \
  || fail "tampered network marker rejection is unclear"
cp "$TMP/network-marker.saved" "$NETWORK_MARKER"
chmod 600 "$NETWORK_MARKER"

# A second instance has distinct member/anchor identities and target/project suffix,
# while sharing only the network's L1 and generated genesis trees.
"$DEMO_DIR/demo.sh" config --instance launcher-peer > "$TMP/config-peer.yml"
PEER_SECRET="$TMP/secrets/networks/devnet/launcher-peer/compose"
PEER_RUNTIME="$TMP/runtime/networks/devnet/launcher-peer/compose"
PEER_INSTANCE="$NETWORK_ROOT/instances/launcher-peer/compose"
PEER_ENV="$PEER_RUNTIME/compose.env"
peer_members="$(for i in 0 1 2; do tr -d '\r\n' < "$PEER_SECRET/member-keys/node$i.public"; \
  [ "$i" -eq 2 ] || printf ','; done)"
[ "$peer_members" != "$expected_members" ] || fail "member identities were reused across instances"
[ "$(cat "$PEER_SECRET/anchor.seed")" != "$(cat "$SECRET_DIR/anchor.seed")" ] \
  || fail "anchor identity was reused across instances"
[ "$(env_value DEMO_PROJECT_NAME "$PEER_ENV")" != "$project" ] \
  || fail "Compose project was reused across instances"
[ "$(env_value DEMO_YANO0_DATA_DIR "$PEER_ENV")" = "$(env_value DEMO_YANO0_DATA_DIR "$ENV_FILE")" ] \
  || fail "instances do not share the selected deployment's L1 state"
[ "$(env_value DEMO_SHELLEY_GENESIS_FILE "$PEER_ENV")" = "$genesis" ] \
  || fail "instances do not share the network genesis"
[ "$PEER_INSTANCE" != "$INSTANCE_ROOT" ] || fail "app-chain state is not instance-isolated"
for key in s3 ipfs kafka; do
  first_id="$(sed -n "s/^${key}.target-id=//p" "$RUNNER_CONFIG")"
  peer_id="$(sed -n "s/^${key}.target-id=//p" "$PEER_RUNTIME/runner-compose.properties")"
  [ "$first_id" != "$peer_id" ] || fail "$key target id was reused across instances"
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

# Public profiles default to relay/app-final with no anchor. Enabling a public
# anchor requires both an owner-only key and exact network acknowledgement.
"$DEMO_DIR/demo.sh" config --network preview --instance preview-safe \
  > "$TMP/preview-safe.yml"
PREVIEW_ROOT="$TMP/data/networks/preview"
PREVIEW_INSTANCE="$PREVIEW_ROOT/instances/preview-safe/compose"
PREVIEW_SECRET="$TMP/secrets/networks/preview/preview-safe/compose"
PREVIEW_RUNTIME="$TMP/runtime/networks/preview/preview-safe/compose"
PREVIEW_NODE="$PREVIEW_SECRET/nodes-compose/node0.properties"
PREVIEW_RUNNER="$PREVIEW_RUNTIME/runner-compose.properties"
grep -Fxq 'yano.app-chain.chains[0].machines.evidence-registry.storage-gate=app-final' \
  "$PREVIEW_NODE" || fail "preview does not default to APP_FINAL storage"
grep -Fxq 'scenario.require-anchor=false' "$PREVIEW_RUNNER" \
  || fail "preview scenario unexpectedly requires an anchor"
if grep -Eq 'anchor\.(enabled|signing-key|every-blocks|max-interval-minutes)=' "$PREVIEW_NODE"; then
  fail "preview default rendered an automatic anchor directive"
fi
jq -e '
  .anchor.enabled == false
  and .anchor.everyBlocks == null
  and .anchor.maxIntervalMinutes == null
  and .effects.requireAnchor == false
' \
  "$PREVIEW_INSTANCE/appchain-identity.json" >/dev/null \
  || fail "preview marker does not record its unanchored policy"

PUBLIC_KEY="$TMP/operator-funded-preview.seed"
PUBLIC_KEY_UPPER='ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789'
PUBLIC_KEY_LOWER='abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789'
printf '%s\n' "$PUBLIC_KEY_UPPER" > "$PUBLIC_KEY"
chmod 600 "$PUBLIC_KEY"
if "$DEMO_DIR/demo.sh" config --network preview --instance preview-anchor \
    --anchor-key-file "$PUBLIC_KEY" >"$TMP/preview-key-only.out" 2>&1; then
  fail "public anchor key alone unexpectedly authorized spending"
fi
grep -Fq 'add --confirm-public-anchor preview' "$TMP/preview-key-only.out" \
  || fail "public anchor consent rejection is unclear"
WRONG_CONFIRM_ROOT="$TMP/preview-wrong-confirm"
if DEMO_DATA_ROOT="$WRONG_CONFIRM_ROOT/data" DEMO_SECRET_ROOT="$WRONG_CONFIRM_ROOT/secrets" \
    DEMO_RUNTIME_ROOT="$WRONG_CONFIRM_ROOT/runtime" \
    "$DEMO_DIR/demo.sh" config --network preview --instance preview-anchor \
    --anchor-key-file "$PUBLIC_KEY" --confirm-public-anchor preprod \
    >"$TMP/preview-wrong-confirm.out" 2>&1; then
  fail "a public anchor acknowledgement for another network unexpectedly succeeded"
fi
grep -Fq -- '--confirm-public-anchor must exactly name the selected network' \
  "$TMP/preview-wrong-confirm.out" \
  || fail "cross-network public anchor acknowledgement rejection is unclear"
assert_absent "$WRONG_CONFIRM_ROOT"
"$DEMO_DIR/demo.sh" config --network preview --instance preview-anchor \
  --anchor-key-file "$PUBLIC_KEY" --confirm-public-anchor preview \
  > "$TMP/preview-anchor.yml"
PREVIEW_ANCHOR_SECRET="$TMP/secrets/networks/preview/preview-anchor/compose"
PREVIEW_ANCHOR_RUNTIME="$TMP/runtime/networks/preview/preview-anchor/compose"
PREVIEW_ANCHOR_NODE="$PREVIEW_ANCHOR_SECRET/nodes-compose/node0.properties"
grep -Fxq 'yano.app-chain.chains[0].anchor.every-blocks=30' "$PREVIEW_ANCHOR_NODE" \
  || fail "explicit preview anchor does not use the public 30-block cadence"
grep -Fxq 'yano.app-chain.chains[0].anchor.max-interval-minutes=60' \
  "$PREVIEW_ANCHOR_NODE" \
  || fail "explicit preview anchor does not use the profile safety interval"
grep -Fxq 'yano.app-chain.chains[0].machines.evidence-registry.storage-gate=l1-anchored' \
  "$PREVIEW_ANCHOR_NODE" || fail "explicit preview anchor does not gate storage on L1"
grep -Fxq 'scenario.require-anchor=true' \
  "$PREVIEW_ANCHOR_RUNTIME/runner-compose.properties" \
  || fail "explicit preview anchor is not required by the scenario"
grep -Fxq "yano.app-chain.chains[0].anchor.signing-key=$PUBLIC_KEY_LOWER" \
  "$PREVIEW_ANCHOR_NODE" \
  || fail "configured uppercase anchor seed was not normalized before rendering"
expected_preview_fingerprint="$(printf '%s' "$PUBLIC_KEY_LOWER" | python3 -c \
  'import hashlib,sys; print(hashlib.sha256(b"yano-demo-anchor-signer-v1\0"+sys.stdin.buffer.read()).hexdigest())')"
jq -e '
  .anchor.enabled == true
  and .anchor.everyBlocks == 30
  and .anchor.maxIntervalMinutes == 60
  and .effects.requireAnchor == true
' "$PREVIEW_ROOT/instances/preview-anchor/compose/appchain-identity.json" >/dev/null \
  || fail "explicit preview marker does not pin its anchor cadence"
jq -e --arg fingerprint "$expected_preview_fingerprint" \
  '.anchor.signerFingerprint == $fingerprint' \
  "$PREVIEW_ROOT/instances/preview-anchor/compose/appchain-identity.json" >/dev/null \
  || fail "preview anchor fingerprint did not use the normalized seed"
preview_marker_before="$(shasum -a 256 \
  "$PREVIEW_ROOT/instances/preview-anchor/compose/appchain-identity.json")"
"$DEMO_DIR/demo.sh" config --network preview --instance preview-anchor \
  --anchor-key-file "$PUBLIC_KEY" --confirm-public-anchor preview \
  > "$TMP/preview-anchor-rerender.yml"
[ "$preview_marker_before" = "$(shasum -a 256 \
    "$PREVIEW_ROOT/instances/preview-anchor/compose/appchain-identity.json")" ] \
  || fail "uppercase anchor key input produced an unstable normalized identity"

# Preprod has the same opt-in spending guard and reviewed safety cadence.
"$DEMO_DIR/demo.sh" config --network preprod --instance preprod-safe \
  > "$TMP/preprod-safe.yml"
PREPROD_ROOT="$TMP/data/networks/preprod"
PREPROD_SAFE_NODE="$TMP/secrets/networks/preprod/preprod-safe/compose/nodes-compose/node0.properties"
PREPROD_SAFE_RUNNER="$TMP/runtime/networks/preprod/preprod-safe/compose/runner-compose.properties"
grep -Fxq 'yano.app-chain.chains[0].machines.evidence-registry.storage-gate=app-final' \
  "$PREPROD_SAFE_NODE" || fail "preprod does not default to APP_FINAL storage"
grep -Fxq 'scenario.require-anchor=false' "$PREPROD_SAFE_RUNNER" \
  || fail "preprod scenario unexpectedly requires an anchor"
if grep -Eq 'anchor\.(enabled|signing-key|every-blocks|max-interval-minutes)=' \
    "$PREPROD_SAFE_NODE"; then
  fail "preprod default rendered an automatic anchor directive"
fi
jq -e '
  .anchor.enabled == false
  and .anchor.everyBlocks == null
  and .anchor.maxIntervalMinutes == null
  and .effects.requireAnchor == false
' "$PREPROD_ROOT/instances/preprod-safe/compose/appchain-identity.json" >/dev/null \
  || fail "preprod marker does not record its unanchored policy"

"$DEMO_DIR/demo.sh" config --network preprod --instance preprod-anchor \
  --anchor-key-file "$PUBLIC_KEY" --confirm-public-anchor preprod \
  > "$TMP/preprod-anchor.yml"
PREPROD_ANCHOR_NODE="$TMP/secrets/networks/preprod/preprod-anchor/compose/nodes-compose/node0.properties"
grep -Fxq 'yano.app-chain.chains[0].anchor.every-blocks=30' "$PREPROD_ANCHOR_NODE" \
  || fail "explicit preprod anchor does not use the public 30-block cadence"
grep -Fxq 'yano.app-chain.chains[0].anchor.max-interval-minutes=60' \
  "$PREPROD_ANCHOR_NODE" \
  || fail "explicit preprod anchor does not use the profile safety interval"
jq -e '
  .anchor.enabled == true
  and .anchor.everyBlocks == 30
  and .anchor.maxIntervalMinutes == 60
  and .effects.requireAnchor == true
' "$PREPROD_ROOT/instances/preprod-anchor/compose/appchain-identity.json" >/dev/null \
  || fail "explicit preprod marker does not pin its anchor cadence"

# Mainnet must be explicitly enabled and remains unanchored/no-value-movement.
MAINNET_ROOT="$TMP/mainnet"
if DEMO_DATA_ROOT="$MAINNET_ROOT/data" DEMO_SECRET_ROOT="$MAINNET_ROOT/secrets" \
    DEMO_RUNTIME_ROOT="$MAINNET_ROOT/runtime" \
    "$DEMO_DIR/demo.sh" config --network mainnet --instance guarded \
    >"$TMP/mainnet-unguarded.out" 2>&1; then
  fail "unguarded mainnet config unexpectedly succeeded"
fi
grep -Fq 'mainnet requires the explicit --enable-mainnet guard' "$TMP/mainnet-unguarded.out" \
  || fail "mainnet guard rejection is unclear"
assert_absent "$MAINNET_ROOT"
DEMO_DATA_ROOT="$MAINNET_ROOT/data" DEMO_SECRET_ROOT="$MAINNET_ROOT/secrets" \
  DEMO_RUNTIME_ROOT="$MAINNET_ROOT/runtime" \
  "$DEMO_DIR/demo.sh" config --network mainnet --instance guarded --enable-mainnet \
  > "$TMP/mainnet.yml"
MAINNET_NODE="$MAINNET_ROOT/secrets/networks/mainnet/guarded/compose/nodes-compose/node0.properties"
MAINNET_RUNNER="$MAINNET_ROOT/runtime/networks/mainnet/guarded/compose/runner-compose.properties"
MAINNET_ENV="$MAINNET_ROOT/runtime/networks/mainnet/guarded/compose/compose.env"
MAINNET_MARKER="$MAINNET_ROOT/data/networks/mainnet/instances/guarded/compose/appchain-identity.json"
grep -Fxq 'yano.app-chain.chains[0].machines.evidence-registry.storage-gate=app-final' \
  "$MAINNET_NODE" || fail "guarded mainnet is not APP_FINAL"
grep -Fxq 'scenario.require-anchor=false' "$MAINNET_RUNNER" \
  || fail "guarded mainnet unexpectedly requires an anchor"
if grep -Eq 'anchor\.(enabled|signing-key|every-blocks|max-interval-minutes)=|/devnet/fund' \
    "$MAINNET_NODE" "$MAINNET_RUNNER" "$MAINNET_ENV"; then
  fail "guarded mainnet rendered an automatic value/anchor operation"
fi
jq -e '
  .anchor.enabled == false
  and .anchor.everyBlocks == null
  and .anchor.maxIntervalMinutes == null
  and .effects.requireAnchor == false
' "$MAINNET_MARKER" >/dev/null || fail "mainnet marker does not record its unanchored policy"
[ "$(env_value DEMO_YANO_PROFILE "$MAINNET_ENV")" = mainnet ] \
  || fail "Compose mainnet profile was not propagated"
[ "$(env_value DEMO_LEADER_WARMUP_SECONDS "$MAINNET_ENV")" = 0 ] \
  || fail "public profile warmup was not propagated"
MAINNET_ANCHOR_ROOT="$TMP/mainnet-anchor-attempt"
if DEMO_DATA_ROOT="$MAINNET_ANCHOR_ROOT/data" \
    DEMO_SECRET_ROOT="$MAINNET_ANCHOR_ROOT/secrets" \
    DEMO_RUNTIME_ROOT="$MAINNET_ANCHOR_ROOT/runtime" \
    "$DEMO_DIR/demo.sh" config --network mainnet --instance forbidden-anchor \
    --enable-mainnet --anchor-key-file "$PUBLIC_KEY" --confirm-public-anchor mainnet \
    >"$TMP/mainnet-anchor.out" 2>&1; then
  fail "mainnet automatic anchoring unexpectedly succeeded"
fi
grep -Fq 'mainnet profile forbids automatic anchor/value operations' \
  "$TMP/mainnet-anchor.out" || fail "mainnet anchor prohibition is unclear"
assert_absent "$MAINNET_ANCHOR_ROOT"

# A zero exit from `docker compose down` is not sufficient evidence that a
# deployment stopped. Simulate a failed start whose rollback sees a stopped
# project-labelled orphan, then verify both rollback and explicit stop retain
# the exact shared-L1 lease.
ORPHAN_ROOT="$TMP/compose-orphan"
ORPHAN_INSTANCE="orphan-guard"
ORPHAN_RUNTIME="$ORPHAN_ROOT/runtime/networks/devnet/$ORPHAN_INSTANCE/compose"
ORPHAN_NETWORK="$ORPHAN_ROOT/data/networks/devnet"
ORPHAN_LEASE="$ORPHAN_NETWORK/l1/compose/demo-owner.json"
mkdir -p "$ORPHAN_RUNTIME/plugins"
for name in appchain-kafka appchain-ipfs appchain-objectstore-s3 appchain-evidence-registry; do
  : > "$ORPHAN_RUNTIME/plugins/$name-bundle.jar"
done
: > "$ORPHAN_RUNTIME/runner.jar"
: > "$ORPHAN_RUNTIME/yano.jar"
ORPHAN_BIN="$TMP/orphan-bin"
ORPHAN_DOCKER_LOG="$TMP/orphan-docker.log"
mkdir -p "$ORPHAN_BIN"
cat > "$ORPHAN_BIN/docker" <<'SH'
#!/bin/sh
printf '%s\n' "$*" >> "$FAKE_DOCKER_LOG"
if [ "${1:-}" = compose ]; then
  operation=
  shift
  for argument in "$@"; do
    case "$argument" in
      config|up|down) operation="$argument";;
    esac
  done
  case "$operation" in
    config) exit 0;;
    up) exit 42;;
    down) exit 0;;
  esac
fi
if [ "${1:-}" = ps ]; then
  printf 'stopped-orphan-container\n'
  exit 0
fi
printf 'unexpected fake docker invocation: %s\n' "$*" >&2
exit 97
SH
chmod 755 "$ORPHAN_BIN/docker"

if DEMO_DATA_ROOT="$ORPHAN_ROOT/data" DEMO_SECRET_ROOT="$ORPHAN_ROOT/secrets" \
    DEMO_RUNTIME_ROOT="$ORPHAN_ROOT/runtime" FAKE_DOCKER_LOG="$ORPHAN_DOCKER_LOG" \
    PATH="$ORPHAN_BIN:$PATH" "$DEMO_DIR/demo.sh" up \
    --instance "$ORPHAN_INSTANCE" >"$TMP/orphan-up.out" 2>&1; then
  fail "fake Compose startup unexpectedly succeeded"
fi
grep -Fq 'ROLLBACK_STARTUP incomplete' "$TMP/orphan-up.out" \
  || fail "uncertain Compose rollback was not reported as incomplete"
assert_exists "$ORPHAN_LEASE"
orphan_lease_before="$(shasum -a 256 "$ORPHAN_LEASE")"
if DEMO_DATA_ROOT="$ORPHAN_ROOT/data" DEMO_SECRET_ROOT="$ORPHAN_ROOT/secrets" \
    DEMO_RUNTIME_ROOT="$ORPHAN_ROOT/runtime" FAKE_DOCKER_LOG="$ORPHAN_DOCKER_LOG" \
    PATH="$ORPHAN_BIN:$PATH" "$DEMO_DIR/demo.sh" stop \
    --instance "$ORPHAN_INSTANCE" >"$TMP/orphan-stop.out" 2>&1; then
  fail "Compose stop unexpectedly trusted a zero down result while an orphan remained"
fi
grep -Fq 'Compose services could not be proven absent; L1 lease was preserved' \
  "$TMP/orphan-stop.out" || fail "uncertain Compose stop rejection is unclear"
assert_exists "$ORPHAN_LEASE"
[ "$orphan_lease_before" = "$(shasum -a 256 "$ORPHAN_LEASE")" ] \
  || fail "uncertain Compose shutdown changed its preserved L1 lease"
[ "$(grep -c ' down ' "$ORPHAN_DOCKER_LOG" | tr -d ' ')" -ge 2 ] \
  || fail "rollback and stop did not both request Compose teardown"
grep -Eq '^ps .* -a |^ps -a ' "$ORPHAN_DOCKER_LOG" \
  || fail "Compose teardown did not inspect stopped project containers"

# Cleanup requires an exact scope, explicit confirmation, and a stopped
# deployment. A tiny fake Docker only answers the clean-time `docker ps` check.
FAKE_BIN="$TMP/fake-bin"
mkdir -p "$FAKE_BIN"
cat > "$FAKE_BIN/docker" <<'SH'
#!/bin/sh
if [ "${1:-}" = ps ]; then exit 0; fi
printf 'unexpected fake docker invocation: %s\n' "$*" >&2
exit 97
SH
chmod 755 "$FAKE_BIN/docker"

if PATH="$FAKE_BIN:$PATH" "$DEMO_DIR/demo.sh" clean --instance launcher --yes \
    >"$TMP/clean-noscope.out" 2>&1; then
  fail "cleanup without a scope unexpectedly succeeded"
fi
grep -Fq 'clean requires one explicit --scope' "$TMP/clean-noscope.out" \
  || fail "missing cleanup scope rejection is unclear"
if PATH="$FAKE_BIN:$PATH" "$DEMO_DIR/demo.sh" clean --instance launcher \
    --scope observability >"$TMP/clean-noyes.out" 2>&1; then
  fail "cleanup without --yes unexpectedly succeeded"
fi
grep -Fq 'clean requires --yes' "$TMP/clean-noyes.out" \
  || fail "missing cleanup confirmation rejection is unclear"
if PATH="$FAKE_BIN:$PATH" "$DEMO_DIR/demo.sh" clean --instance launcher \
    --scope reports --new-instance unrelated --yes \
    >"$TMP/clean-unrelated-replacement.out" 2>&1; then
  fail "replacement arguments were accepted by an unrelated cleanup scope"
fi
grep -Fq 'only valid with --scope instance or --scope all' \
  "$TMP/clean-unrelated-replacement.out" \
  || fail "unrelated replacement-argument rejection is unclear"

touch "$INSTANCE_ROOT/app-chain/appchain.sentinel"
touch "$INSTANCE_ROOT/connectors/connector.sentinel"
touch "$INSTANCE_ROOT/observability/observability.sentinel"
touch "$INSTANCE_ROOT/reports/report.sentinel"
touch "$RUNTIME_DIR/runtime.sentinel"
touch "$L1_ROOT/l1.sentinel"
secret_before="$(shasum -a 256 "$SECRET_DIR/yano-api-key" "$SECRET_DIR/anchor.seed" \
  "$MEMBER_DIR"/*.seed)"

for unsafe_scope in appchain connectors; do
  if PATH="$FAKE_BIN:$PATH" "$DEMO_DIR/demo.sh" clean --instance launcher \
      --scope "$unsafe_scope" --yes >"$TMP/clean-$unsafe_scope.out" 2>&1; then
    fail "$unsafe_scope state was discarded independently"
  fi
  grep -Fq -- "--scope $unsafe_scope is unsafe in isolation" \
    "$TMP/clean-$unsafe_scope.out" || fail "$unsafe_scope boundary refusal is unclear"
done
assert_exists "$INSTANCE_ROOT/connectors/connector.sentinel"
assert_exists "$INSTANCE_ROOT/app-chain/appchain.sentinel"
assert_exists "$INSTANCE_ROOT/observability/observability.sentinel"
assert_exists "$INSTANCE_ROOT/reports/report.sentinel"
assert_exists "$RUNTIME_DIR/runtime.sentinel"
assert_exists "$L1_ROOT/l1.sentinel"

PATH="$FAKE_BIN:$PATH" "$DEMO_DIR/demo.sh" clean --instance launcher \
  --scope observability --yes > "$TMP/clean-observability.out"
PATH="$FAKE_BIN:$PATH" "$DEMO_DIR/demo.sh" clean --instance launcher \
  --scope reports --yes > "$TMP/clean-reports.out"
assert_absent "$INSTANCE_ROOT/observability"
assert_absent "$INSTANCE_ROOT/reports"
assert_exists "$INSTANCE_ROOT/app-chain/appchain.sentinel"

PATH="$FAKE_BIN:$PATH" "$DEMO_DIR/demo.sh" clean --instance launcher \
  --scope runtime --yes > "$TMP/clean-runtime.out"
assert_absent "$RUNTIME_DIR"
assert_exists "$INSTANCE_ROOT/app-chain/appchain.sentinel"
assert_exists "$L1_ROOT/l1.sentinel"

# Retiring an instance requires a new instance/chain identity, preserves shared
# L1 and all old secrets, and prevents the retired identity from being reused.
if PATH="$FAKE_BIN:$PATH" "$DEMO_DIR/demo.sh" clean --instance launcher \
    --scope instance --yes >"$TMP/clean-instance-no-replacement.out" 2>&1; then
  fail "instance retirement without a replacement unexpectedly succeeded"
fi
grep -Fq 'requires --new-instance' "$TMP/clean-instance-no-replacement.out" \
  || fail "replacement requirement is unclear"
PATH="$FAKE_BIN:$PATH" "$DEMO_DIR/demo.sh" clean --instance launcher \
  --scope instance --new-instance launcher-v2 --new-chain-id evidence-chain-launcher-v2 \
  --yes > "$TMP/clean-instance.out"
assert_absent "$INSTANCE_ROOT"
assert_exists "$L1_ROOT/l1.sentinel"
assert_exists "$SECRET_DIR/yano-api-key"
[ "$secret_before" = "$(shasum -a 256 "$SECRET_DIR/yano-api-key" "$SECRET_DIR/anchor.seed" \
  "$MEMBER_DIR"/*.seed)" ] || fail "instance retirement changed preserved secrets"
assert_exists "$NETWORK_ROOT/retired/compose/launcher.json"
assert_exists "$NETWORK_ROOT/reservations/compose/launcher-v2.json"
jq -e '.status == "retired" and .replacementInstanceId == "launcher-v2"' \
  "$NETWORK_ROOT/retired/compose/launcher.json" >/dev/null \
  || fail "retirement did not durably reach the retired state"
if "$DEMO_DIR/demo.sh" config --instance launcher >"$TMP/retired-reuse.out" 2>&1; then
  fail "retired instance identity was reusable"
fi
grep -Fq "is retired and cannot be reused" "$TMP/retired-reuse.out" \
  || fail "retired identity rejection is unclear"
"$DEMO_DIR/demo.sh" config --instance launcher-v2 --chain-id evidence-chain-launcher-v2 \
  > "$TMP/replacement.yml"
REPLACEMENT_MARKER="$NETWORK_ROOT/instances/launcher-v2/compose/appchain-identity.json"
jq -e '.instanceId == "launcher-v2" and .chainIds == ["evidence-chain-launcher-v2"]' \
  "$REPLACEMENT_MARKER" >/dev/null || fail "replacement identity was not installed"
assert_exists "$L1_ROOT/l1.sentinel"
assert_exists "$SECRET_DIR/yano-api-key"

# Unanchored history is the same effect-instance boundary: deleting its journal
# while keeping connector targets could replay terminal external effects.
touch "$PREVIEW_INSTANCE/app-chain/preview-app.sentinel"
touch "$PREVIEW_INSTANCE/logs/preview-log.sentinel"
touch "$PREVIEW_INSTANCE/connectors/preview-connector.sentinel"
preview_marker_before="$(shasum -a 256 "$PREVIEW_INSTANCE/appchain-identity.json")"
if PATH="$FAKE_BIN:$PATH" "$DEMO_DIR/demo.sh" clean --network preview \
    --instance preview-safe --scope appchain --yes >"$TMP/clean-preview-appchain.out" 2>&1; then
  fail "unanchored app-chain history was discarded independently"
fi
grep -Fq -- '--scope appchain is unsafe in isolation' "$TMP/clean-preview-appchain.out" \
  || fail "unanchored app-chain boundary refusal is unclear"
assert_exists "$PREVIEW_INSTANCE/app-chain"
assert_exists "$PREVIEW_INSTANCE/logs"
assert_exists "$PREVIEW_INSTANCE/connectors/preview-connector.sentinel"
[ "$preview_marker_before" = "$(shasum -a 256 "$PREVIEW_INSTANCE/appchain-identity.json")" ] \
  || fail "app-chain cleanup changed the immutable instance marker"

# Deleting public-network L1 state needs a second, exact acknowledgement.
PREVIEW_L1="$PREVIEW_ROOT/l1/compose"
touch "$PREVIEW_L1/public-l1.sentinel"
if PATH="$FAKE_BIN:$PATH" "$DEMO_DIR/demo.sh" clean --network preview \
    --instance preview-safe --scope l1 --yes >"$TMP/clean-public-l1-noack.out" 2>&1; then
  fail "public L1 cleanup without network acknowledgement unexpectedly succeeded"
fi
grep -Fq 'public L1 deletion requires --confirm-public-l1-delete preview' \
  "$TMP/clean-public-l1-noack.out" || fail "public L1 cleanup guard is unclear"
assert_exists "$PREVIEW_L1/public-l1.sentinel"
if PATH="$FAKE_BIN:$PATH" "$DEMO_DIR/demo.sh" clean --network preview \
    --instance preview-safe --scope l1 --confirm-public-l1-delete preview --yes \
    >"$TMP/clean-public-l1-attached.out" 2>&1; then
  fail "public L1 state was deleted while app-chain instances remained attached"
fi
grep -Fq 'shared L1 state is still attached' "$TMP/clean-public-l1-attached.out" \
  || fail "retained-instance L1 refusal is unclear"
assert_exists "$PREVIEW_L1"
assert_exists "$PREVIEW_INSTANCE/appchain-identity.json"
assert_exists "$PREVIEW_SECRET/yano-api-key"

# Cleanup policy is derived from canonical retained identity, not current CLI
# defaults. Tampering an attachment marker preserves every requested target.
PEER_MARKER="$PEER_INSTANCE/appchain-identity.json"
touch "$PEER_INSTANCE/reports/tamper-guard.sentinel"
cp "$PEER_MARKER" "$TMP/peer-marker.saved"
python3 - "$PEER_MARKER" <<'PY'
import json, os, sys
path = sys.argv[1]
document = json.load(open(path))
document["instanceId"] = "wrong-instance"
with open(path, "w") as out:
    json.dump(document, out, sort_keys=True, separators=(",", ":"))
    out.write("\n")
os.chmod(path, 0o600)
PY
if PATH="$FAKE_BIN:$PATH" "$DEMO_DIR/demo.sh" clean --instance launcher-peer \
    --scope reports --yes >"$TMP/clean-tampered-identity.out" 2>&1; then
  fail "cleanup accepted a tampered persisted identity"
fi
grep -Fq 'persisted lifecycle identity is invalid' "$TMP/clean-tampered-identity.out" \
  || fail "tampered cleanup identity rejection is unclear"
assert_exists "$PEER_INSTANCE/reports/tamper-guard.sentinel"
cp "$TMP/peer-marker.saved" "$PEER_MARKER"
chmod 600 "$PEER_MARKER"

# A public L1 can be removed only after its sole attachment is retired. The
# exact public-network deletion acknowledgement remains independently required.
PUBLIC_CLEAN="$TMP/public-clean"
PUBLIC_CLEAN_ENV=(DEMO_DATA_ROOT="$PUBLIC_CLEAN/data" \
  DEMO_SECRET_ROOT="$PUBLIC_CLEAN/secrets" DEMO_RUNTIME_ROOT="$PUBLIC_CLEAN/runtime")
env "${PUBLIC_CLEAN_ENV[@]}" "$DEMO_DIR/demo.sh" config --network preview \
  --instance public-old > "$TMP/public-clean-config.yml"
PUBLIC_CLEAN_L1="$PUBLIC_CLEAN/data/networks/preview/l1/compose"
touch "$PUBLIC_CLEAN_L1/public-clean.sentinel"
env "${PUBLIC_CLEAN_ENV[@]}" PATH="$FAKE_BIN:$PATH" "$DEMO_DIR/demo.sh" clean \
  --network preview --instance public-old --scope instance --new-instance public-new \
  --new-chain-id evidence-chain-public-new --yes > "$TMP/public-clean-retire.out"
env "${PUBLIC_CLEAN_ENV[@]}" PATH="$FAKE_BIN:$PATH" "$DEMO_DIR/demo.sh" clean \
  --network preview --instance public-old --scope l1 \
  --confirm-public-l1-delete preview --yes > "$TMP/public-clean-l1.out"
assert_absent "$PUBLIC_CLEAN_L1"
assert_exists "$PUBLIC_CLEAN/secrets/networks/preview/public-old/compose/yano-api-key"

# `all` is one guarded transaction boundary: with no sibling attachment it
# retires the instance and removes that deployment's shared L1, preserving keys.
ALL_ROOT="$TMP/all-clean"
ALL_ENV=(DEMO_DATA_ROOT="$ALL_ROOT/data" DEMO_SECRET_ROOT="$ALL_ROOT/secrets" \
  DEMO_RUNTIME_ROOT="$ALL_ROOT/runtime")
env "${ALL_ENV[@]}" "$DEMO_DIR/demo.sh" config --instance all-old > "$TMP/all-config.yml"
ALL_NETWORK="$ALL_ROOT/data/networks/devnet"
ALL_INSTANCE="$ALL_NETWORK/instances/all-old/compose"
ALL_L1="$ALL_NETWORK/l1/compose"
ALL_SECRET="$ALL_ROOT/secrets/networks/devnet/all-old/compose/yano-api-key"
touch "$ALL_INSTANCE/connectors/all-connector.sentinel" "$ALL_L1/all-l1.sentinel"
env "${ALL_ENV[@]}" PATH="$FAKE_BIN:$PATH" "$DEMO_DIR/demo.sh" clean \
  --instance all-old --scope all --new-instance all-new \
  --new-chain-id evidence-chain-all-new --yes > "$TMP/all-clean.out"
assert_absent "$ALL_INSTANCE"
assert_absent "$ALL_L1"
assert_exists "$ALL_SECRET"
jq -e '.status == "retired" and .replacementInstanceId == "all-new"' \
  "$ALL_NETWORK/retired/compose/all-old.json" >/dev/null \
  || fail "all-scope cleanup did not durably retire its instance"

# If the real launcher cleanup is interrupted after publishing its durable
# retirement fence and deleting instance data, the exact command resumes the
# central cleanup transaction and completes safely.
CRASH_ROOT="$TMP/crash-resume"
CRASH_ENV=(DEMO_DATA_ROOT="$CRASH_ROOT/data" DEMO_SECRET_ROOT="$CRASH_ROOT/secrets" \
  DEMO_RUNTIME_ROOT="$CRASH_ROOT/runtime")
env "${CRASH_ENV[@]}" "$DEMO_DIR/demo.sh" config --instance crash-old \
  > "$TMP/crash-config.yml"
CRASH_NETWORK="$CRASH_ROOT/data/networks/devnet"
CRASH_INSTANCE="$CRASH_NETWORK/instances/crash-old/compose"
if env "${CRASH_ENV[@]}" PATH="$FAKE_BIN:$PATH" \
    YANO_LIFECYCLE_TEST_STOP_AFTER=delete:instance "$DEMO_DIR/demo.sh" clean \
    --instance crash-old --scope instance --new-instance crash-new \
    --new-chain-id evidence-chain-crash-new --yes \
    > "$TMP/crash-interrupted.out" 2>&1; then
  fail "injected launcher cleanup interruption unexpectedly succeeded"
fi
grep -Fq 'test-requested stop after durable phase delete:instance' \
  "$TMP/crash-interrupted.out" \
  || fail "launcher cleanup did not reach the injected delete boundary"
assert_absent "$CRASH_INSTANCE"
assert_exists "$CRASH_NETWORK/.yano-cleanup-transaction.json"
jq -e '.status == "retiring" and .replacementInstanceId == "crash-new"' \
  "$CRASH_NETWORK/retired/compose/crash-old.json" >/dev/null \
  || fail "interrupted launcher cleanup did not retain its retiring fence"
env "${CRASH_ENV[@]}" PATH="$FAKE_BIN:$PATH" "$DEMO_DIR/demo.sh" clean \
  --instance crash-old --scope instance --new-instance crash-new \
  --new-chain-id evidence-chain-crash-new --yes > "$TMP/crash-resume.out"
assert_absent "$CRASH_NETWORK/.yano-cleanup-transaction.json"
jq -e '.status == "retired" and .replacementInstanceId == "crash-new"' \
  "$CRASH_NETWORK/retired/compose/crash-old.json" >/dev/null \
  || fail "interrupted retirement did not resume to completion"

# Exercise host rendering without building or starting a process.
HOST_RUNTIME="$TMP/runtime/networks/devnet/hosttest/host"
mkdir -p "$HOST_RUNTIME/plugins"
for name in appchain-kafka appchain-ipfs appchain-objectstore-s3 appchain-evidence-registry; do
  : > "$HOST_RUNTIME/plugins/$name-bundle.jar"
done
: > "$HOST_RUNTIME/runner.jar"
: > "$HOST_RUNTIME/yano.jar"
PATH="$FAKE_BIN:$PATH" "$DEMO_DIR/demo.sh" prepare --deployment host \
  --instance hosttest > "$TMP/host.out"
HOST_CONFIG="$HOST_RUNTIME/runner-host.properties"
HOST_NODES="$TMP/secrets/networks/devnet/hosttest/host/nodes-host"
[ -f "$HOST_CONFIG" ] && [ -f "$HOST_NODES/node0.properties" ] \
  || fail "host mode did not render runner and node overlays"
host_members="$(sed -n 's/^demo.yano.member-keys=//p' "$HOST_CONFIG")"
[ -n "$host_members" ] || fail "host runner has no explicit membership"
grep -Fxq 'demo.yano.threshold=2' "$HOST_CONFIG" \
  || fail "host runner does not pin the 2-of-3 threshold"
grep -Fq 'effects.executor.enabled=true' "$HOST_NODES/node0.properties" \
  || fail "host node 0 is not the executor"
grep -Fq 'effects.executor.enabled=false' "$HOST_NODES/node1.properties" \
  || fail "host follower can execute effects"
[ "$(grep -hFx 'yano.app-chain.chains[0].anchor.max-interval-minutes=60' \
  "$HOST_NODES"/*.properties | wc -l | tr -d ' ')" -eq 3 ] \
  || fail "host node overlays do not use the same profile safety interval"

# Host preparation intentionally releases its lease. Even then, malformed
# cluster lifecycle state is active/uncertain: cleanup preserves runtime and
# stop must run the strict cluster reconciliation path instead of returning
# early as "already stopped".
HOST_CLUSTER="$TMP/data/networks/devnet/l1/host/host-cluster"
HOST_MALFORMED_PID="$HOST_CLUSTER/node0.pid.tmp.bad"
mkdir -p "$HOST_CLUSTER"
printf 'incomplete\n' > "$HOST_MALFORMED_PID"
touch "$HOST_RUNTIME/runtime-clean-guard.sentinel"
if PATH="$FAKE_BIN:$PATH" "$DEMO_DIR/demo.sh" clean --deployment host \
    --instance hosttest --scope runtime --yes \
    >"$TMP/host-clean-malformed-pid.out" 2>&1; then
  fail "host cleanup accepted malformed no-lease PID lifecycle state"
fi
grep -Fq 'managed services or an L1 lease are active' \
  "$TMP/host-clean-malformed-pid.out" \
  || fail "host cleanup did not classify malformed PID state as uncertain"
assert_exists "$HOST_RUNTIME/runtime-clean-guard.sentinel"
if PATH="$FAKE_BIN:$PATH" "$DEMO_DIR/demo.sh" stop --deployment host \
    --instance hosttest >"$TMP/host-stop-malformed-pid.out" 2>&1; then
  fail "host no-lease stop ignored malformed PID lifecycle state"
fi
grep -Fq 'malformed PID lifecycle state' "$TMP/host-stop-malformed-pid.out" \
  || fail "host no-lease stop did not invoke strict cluster reconciliation"
assert_exists "$HOST_MALFORMED_PID"
rm "$HOST_MALFORMED_PID"

# Shared-L1 ownership is acquired before connector initialization. A later
# node-start failure stops the partial cluster and releases that lease.
FAKE_START_BIN="$TMP/fake-start-bin"
mkdir -p "$FAKE_START_BIN"
cat > "$FAKE_START_BIN/java" <<'SH'
#!/bin/sh
case " $* " in
  *' init-connectors '*) exit 0;;
  *) exit 42;;
esac
SH
chmod 755 "$FAKE_START_BIN/java"
if PATH="$FAKE_START_BIN:$PATH" "$DEMO_DIR/demo.sh" up --deployment host \
    --instance hosttest >"$TMP/host-start-failure.out" 2>&1; then
  fail "fake host runtime unexpectedly started"
fi
grep -Fq 'ROLLBACK_STARTUP complete' "$TMP/host-start-failure.out" \
  || fail "failed host startup did not report completed rollback"
assert_absent "$TMP/data/networks/devnet/l1/host/demo-owner.json"

# The public opt-in anchor renders the same reviewed interval in host mode.
HOST_PREVIEW_RUNTIME="$TMP/runtime/networks/preview/hostpreview/host"
mkdir -p "$HOST_PREVIEW_RUNTIME/plugins"
for name in appchain-kafka appchain-ipfs appchain-objectstore-s3 appchain-evidence-registry; do
  : > "$HOST_PREVIEW_RUNTIME/plugins/$name-bundle.jar"
done
: > "$HOST_PREVIEW_RUNTIME/runner.jar"
: > "$HOST_PREVIEW_RUNTIME/yano.jar"
PATH="$FAKE_BIN:$PATH" "$DEMO_DIR/demo.sh" prepare --deployment host \
  --network preview --instance hostpreview --anchor-key-file "$PUBLIC_KEY" \
  --confirm-public-anchor preview > "$TMP/host-preview.out"
HOST_PREVIEW_NODES="$TMP/secrets/networks/preview/hostpreview/host/nodes-host"
[ "$(grep -hFx 'yano.app-chain.chains[0].anchor.max-interval-minutes=60' \
  "$HOST_PREVIEW_NODES"/*.properties | wc -l | tr -d ' ')" -eq 3 ] \
  || fail "preview host overlays do not use the profile safety interval"
jq -e '
  .anchor.enabled == true
  and .anchor.everyBlocks == 30
  and .anchor.maxIntervalMinutes == 60
  and .effects.requireAnchor == true
' "$TMP/data/networks/preview/instances/hostpreview/host/appchain-identity.json" >/dev/null \
  || fail "preview host marker does not pin its anchor cadence"

VALIDATION_JAR="${DEMO_RUNNER_VALIDATION_JAR:-}"
if [ -z "$VALIDATION_JAR" ]; then
  "$REPO_DIR/gradlew" -p "$REPO_DIR" --no-daemon \
    :appchain-evidence-demo-runner:shadowJar >/dev/null
  VALIDATION_JAR="$(find \
    "$REPO_DIR/appchain/examples/appchain-evidence-demo-runner/build/libs" \
    -maxdepth 1 -type f -name '*-all.jar' -print | sort | tail -n 1)"
fi
[ -f "$VALIDATION_JAR" ] || fail "evidence demo runner validation jar is missing"
java --add-modules=jdk.httpserver -jar "$VALIDATION_JAR" validate-config \
  --config "$HOST_CONFIG" > "$TMP/host-runner-config-validation.out"
grep -Fxq 'PASS command=validate-config' "$TMP/host-runner-config-validation.out" \
  || fail "generated host runner configuration did not parse"

printf 'PASS: launcher profiles, immutable identity, lifecycle and cleanup contracts\n'
