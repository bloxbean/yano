#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEMO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TOOL="$DEMO_DIR/tools/lifecycle.py"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/yano-lifecycle-test.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
assert_file() { [ -f "$1" ] || fail "expected file: $1"; }
assert_dir() { [ -d "$1" ] || fail "expected directory: $1"; }
assert_absent() { [ ! -e "$1" ] && [ ! -L "$1" ] || fail "expected absent path: $1"; }
digest() {
  python3 -c 'import hashlib, sys; print(hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())' "$1"
}
repeat_hex_byte() {
  python3 -c 'import sys; print(sys.argv[1] * 32)' "$1"
}
expect_failure() {
  label="$1"
  shift
  if "$@" >"$TMP/failure.out" 2>"$TMP/failure.err"; then
    fail "$label unexpectedly succeeded"
  fi
}
make_retiring_records() {
  network="$1"
  instance="$2"
  deployment="$3"
  chain="$4"
  replacement="$5"
  replacement_chain="$6"
  identity_digest="$7"
  python3 - "$network" "$instance" "$deployment" "$chain" "$replacement" \
    "$replacement_chain" "$identity_digest" <<'PY'
import json
from pathlib import Path
import sys

network, instance, deployment, chain, replacement, replacement_chain, digest = sys.argv[1:]
root = Path(network)
retirement = {
    "schemaVersion": 1,
    "kind": "yano.demo.retired-instance",
    "networkName": "devnet",
    "instanceId": instance,
    "deployment": deployment,
    "chainId": chain,
    "appchainIdentitySha256": digest,
    "replacementInstanceId": replacement,
    "replacementChainId": replacement_chain,
    "status": "retiring",
    "updatedAtMillis": 1,
}
reservation = {
    "schemaVersion": 1,
    "kind": "yano.demo.reset-reservation",
    "networkName": "devnet",
    "retiredInstanceId": instance,
    "deployment": deployment,
    "oldChainId": chain,
    "newInstanceId": replacement,
    "newChainId": replacement_chain,
}
for path, document in (
    (root / "retired" / deployment / f"{instance}.json", retirement),
    (root / "reservations" / deployment / f"{replacement}.json", reservation),
):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n")
    path.chmod(0o600)
PY
}
make_atomic_network() {
  base="$1"
  network="$base/networks/devnet"
  identity="$base/network-input.json"
  mkdir -p "$base"
  printf '%s\n' \
    '{"kind":"yano.demo.network-identity","networkName":"devnet","protocolMagic":42,"schemaVersion":1}' \
    > "$identity"
  chmod 600 "$identity"
  python3 "$TOOL" ensure-network --allowed-root "$base" --directory "$network" \
    --identity-file "$identity" >/dev/null
  printf '%s' "$network"
}
make_app_identity() {
  output="$1"
  network_root="$2"
  instance="$3"
  deployment="$4"
  chain="$5"
  python3 - "$output" "$network_root" "$instance" "$deployment" "$chain" <<'PY'
import hashlib
import json
from pathlib import Path
import sys

output, network_root, instance, deployment, chain = sys.argv[1:]
network_digest = hashlib.sha256(
    (Path(network_root) / "network-identity.json").read_bytes()
).hexdigest()
document = {
    "schemaVersion": 1,
    "kind": "yano.demo.appchain-identity",
    "layoutVersion": 1,
    "networkName": "devnet",
    "networkIdentitySha256": network_digest,
    "instanceId": instance,
    "deployment": deployment,
    "composeProject": f"yano-{instance}" if deployment == "compose" else None,
    "chainIds": [chain],
    "stateMachine": {
        "provider": "evidence-registry",
        "profileVersion": 1,
        "effectEmissionVersion": 1,
    },
    "membership": {
        "members": ["member-a", "member-b", "member-c"],
        "threshold": 2,
        "proposer": "member-a",
        "resultSigners": ["member-a"],
    },
    "effects": {"storageGate": "app-final", "requireAnchor": False},
    "anchor": {
        "enabled": False,
        "mode": "none",
        "everyBlocks": None,
        "maxIntervalMinutes": None,
        "signerFingerprint": None,
    },
    "connectors": {
        "s3": {"targetId": "s3-local", "locator": "http://s3", "profile": "v1"},
        "ipfs": {"targetId": "ipfs-local", "locator": "http://ipfs", "profile": "v1"},
        "kafka": {"targetId": "kafka-local", "locator": "kafka:9092", "profile": "v1"},
    },
}
Path(output).write_text(
    json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n",
    encoding="utf-8",
)
PY
  chmod 600 "$output"
}
make_lease_identity() {
  output="$1"
  app_identity="$2"
  python3 - "$output" "$app_identity" <<'PY'
import hashlib
import json
from pathlib import Path
import sys

output, identity_path = map(Path, sys.argv[1:])
raw = identity_path.read_bytes()
identity = json.loads(raw)
document = {
    "schemaVersion": 1,
    "kind": "yano.demo.l1-lease",
    "networkName": identity["networkName"],
    "instanceId": identity["instanceId"],
    "deployment": identity["deployment"],
    "chainIds": identity["chainIds"],
    "project": identity["composeProject"] or f"yano-{identity['instanceId']}-host",
    "networkIdentitySha256": identity["networkIdentitySha256"],
    "appchainIdentitySha256": hashlib.sha256(raw).hexdigest(),
}
output.write_text(
    json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n",
    encoding="utf-8",
)
PY
  chmod 600 "$output"
}
atomic_acquire() {
  network="$1"
  instance="$2"
  deployment="$3"
  identity="$4"
  lease="$5"
  python3 "$TOOL" deployment-acquire --network-root "$network" \
    --data-root "$network/instances/$instance/$deployment" \
    --l1-root "$network/l1/$deployment" --identity-file "$identity" \
    --lease-identity-file "$lease"
}
atomic_release() {
  network="$1"
  deployment="$2"
  lease="$3"
  python3 "$TOOL" lease-release --allowed-root "$network" \
    --directory "$network/l1/$deployment" --identity-file "$lease" >/dev/null
}
make_cleanup_plan() {
  output="$1"
  network="$2"
  runtime_parent="$3"
  instance="$4"
  deployment="$5"
  chain="$6"
  identity_file="$7"
  scope="$8"
  replacement_instance="${9:-}"
  replacement_chain="${10:-}"
  python3 - "$output" "$network" "$runtime_parent" "$instance" "$deployment" \
    "$chain" "$identity_file" "$scope" "$replacement_instance" "$replacement_chain" <<'PY'
import hashlib
import json
from pathlib import Path
import sys

(
    output, network, runtime_parent, instance, deployment, chain, identity_file,
    scope, replacement_instance, replacement_chain,
) = sys.argv[1:]
network = str(Path(network).resolve(strict=False))
runtime_parent = str(Path(runtime_parent).resolve(strict=False))
digest = None if scope == "l1" else hashlib.sha256(Path(identity_file).read_bytes()).hexdigest()
document = {
    "schemaVersion": 1,
    "kind": "yano.demo.cleanup-plan",
    "networkName": "devnet",
    "instanceId": instance,
    "deployment": deployment,
    "chainId": chain,
    "appchainIdentitySha256": digest,
    "scope": scope,
    "dataRoot": str(Path(network) / "instances" / instance / deployment),
    "l1Root": str(Path(network) / "l1" / deployment),
    "runtimeRoot": str(Path(runtime_parent) / deployment),
    "replacementInstanceId": replacement_instance or None,
    "replacementChainId": replacement_chain or None,
}
Path(output).write_text(
    json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n",
    encoding="utf-8",
)
PY
  chmod 600 "$output"
}
cleanup_execute() {
  network="$1"
  runtime_parent="$2"
  plan="$3"
  python3 "$TOOL" cleanup-execute --network-root "$network" \
    --runtime-allowed-root "$runtime_parent" --plan-file "$plan" --yes
}

command -v python3 >/dev/null 2>&1 || fail "python3 is required"
[ -f "$TOOL" ] || fail "lifecycle helper is missing"

ROOT="$TMP/data"
NETWORK="$ROOT/devnet"
NETWORK_ID="$TMP/network.json"
NETWORK_OTHER="$TMP/network-other.json"

printf '%s\n' \
  '{"schemaVersion":1,"kind":"yano.demo.network-identity","networkName":"devnet","protocolMagic":42}' \
  > "$NETWORK_ID"
printf '%s\n' \
  '{"schemaVersion":1,"kind":"yano.demo.network-identity","networkName":"preview","protocolMagic":2}' \
  > "$NETWORK_OTHER"

# Creation writes sorted, compact, owner-only canonical JSON; recheck is idempotent.
python3 "$TOOL" ensure-network \
  --allowed-root "$ROOT" --directory "$NETWORK" --identity-file "$NETWORK_ID" \
  > "$TMP/create.out"
assert_file "$NETWORK/network-identity.json"
grep -Fq 'CREATED yano.demo.network-identity' "$TMP/create.out" \
  || fail "network creation was not reported"
EXPECTED='{"kind":"yano.demo.network-identity","networkName":"devnet","protocolMagic":42,"schemaVersion":1}'
[ "$(tr -d '\n' < "$NETWORK/network-identity.json")" = "$EXPECTED" ] \
  || fail "network marker is not canonical JSON"
MODE="$(python3 -c \
  'import os, sys; print(oct(os.stat(sys.argv[1]).st_mode & 0o777)[2:])' \
  "$NETWORK/network-identity.json")"
[ "$MODE" = "600" ] || fail "network marker mode is $MODE, expected 600"
FIRST_DIGEST="$(digest "$NETWORK/network-identity.json")"
python3 "$TOOL" ensure-network \
  --allowed-root "$ROOT" --directory "$NETWORK" --identity-file "$NETWORK_ID" \
  > "$TMP/recheck.out"
grep -Fq 'VALID yano.demo.network-identity' "$TMP/recheck.out" \
  || fail "network recheck was not reported"
[ "$FIRST_DIGEST" = "$(digest "$NETWORK/network-identity.json")" ] \
  || fail "idempotent recheck changed the marker"
expect_failure "network identity mismatch" python3 "$TOOL" ensure-network \
  --allowed-root "$ROOT" --directory "$NETWORK" --identity-file "$NETWORK_OTHER"
grep -Fq 'identity marker mismatch' "$TMP/failure.err" \
  || fail "network identity mismatch diagnostic is missing"
[ "$FIRST_DIGEST" = "$(digest "$NETWORK/network-identity.json")" ] \
  || fail "network identity mismatch changed the durable marker"

# The supported network bootstrap path rejects aliased or permissive final
# markers and fail-closes around lifecycle-lock tampering.
NETWORK_TAMPER_ROOT="$TMP/network-tamper"
NETWORK_TAMPER="$NETWORK_TAMPER_ROOT/devnet"
mkdir -p "$NETWORK_TAMPER_ROOT"
python3 "$TOOL" ensure-network --allowed-root "$NETWORK_TAMPER_ROOT" \
  --directory "$NETWORK_TAMPER" --identity-file "$NETWORK_ID" >/dev/null
NETWORK_TAMPER_MARKER="$NETWORK_TAMPER/network-identity.json"
chmod 0660 "$NETWORK_TAMPER_MARKER"
expect_failure "permissive network marker" python3 "$TOOL" ensure-network \
  --allowed-root "$NETWORK_TAMPER_ROOT" --directory "$NETWORK_TAMPER" \
  --identity-file "$NETWORK_ID"
grep -Fq 'writable by another user' "$TMP/failure.err" \
  || fail "permissive network-marker diagnostic is missing"
chmod 0600 "$NETWORK_TAMPER_MARKER"
ln "$NETWORK_TAMPER_MARKER" "$TMP/network-marker-hardlink"
expect_failure "hardlinked network marker" python3 "$TOOL" ensure-network \
  --allowed-root "$NETWORK_TAMPER_ROOT" --directory "$NETWORK_TAMPER" \
  --identity-file "$NETWORK_ID"
grep -Fq 'must not have hard links' "$TMP/failure.err" \
  || fail "hardlinked network-marker diagnostic is missing"
rm "$TMP/network-marker-hardlink"

NETWORK_SYMLINK_ROOT="$TMP/network-marker-symlink"
mkdir -p "$NETWORK_SYMLINK_ROOT/devnet"
ln -s "$NETWORK_ID" "$NETWORK_SYMLINK_ROOT/devnet/network-identity.json"
expect_failure "symlinked network marker" python3 "$TOOL" ensure-network \
  --allowed-root "$NETWORK_SYMLINK_ROOT" --directory "$NETWORK_SYMLINK_ROOT/devnet" \
  --identity-file "$NETWORK_ID"
grep -Fq 'must not be a symlink' "$TMP/failure.err" \
  || fail "symlinked network-marker diagnostic is missing"

LOCK_SYMLINK_ROOT="$TMP/lifecycle-lock-symlink"
mkdir -p "$LOCK_SYMLINK_ROOT"
ln -s "$NETWORK_ID" "$LOCK_SYMLINK_ROOT/.yano-lifecycle.lock"
expect_failure "symlinked lifecycle lock" python3 "$TOOL" ensure-network \
  --allowed-root "$LOCK_SYMLINK_ROOT" --directory "$LOCK_SYMLINK_ROOT/devnet" \
  --identity-file "$NETWORK_ID"
grep -Fq 'lifecycle lock must not be a symlink' "$TMP/failure.err" \
  || fail "symlinked lifecycle-lock diagnostic is missing"

LOCK_HARDLINK_ROOT="$TMP/lifecycle-lock-hardlink"
mkdir -p "$LOCK_HARDLINK_ROOT"
python3 "$TOOL" ensure-network --allowed-root "$LOCK_HARDLINK_ROOT" \
  --directory "$LOCK_HARDLINK_ROOT/devnet" --identity-file "$NETWORK_ID" >/dev/null
ln "$LOCK_HARDLINK_ROOT/.yano-lifecycle.lock" "$TMP/lifecycle-lock-hardlink-alias"
expect_failure "hardlinked lifecycle lock" python3 "$TOOL" ensure-network \
  --allowed-root "$LOCK_HARDLINK_ROOT" --directory "$LOCK_HARDLINK_ROOT/devnet" \
  --identity-file "$NETWORK_ID"
grep -Fq 'lifecycle lock must not have hard links' "$TMP/failure.err" \
  || fail "hardlinked lifecycle-lock diagnostic is missing"
rm "$TMP/lifecycle-lock-hardlink-alias"

# Recognized create-only temporaries are recovered exactly. Unknown or unsafe
# temporary shapes remain visible and block adoption instead of being guessed.
TEMP_RECOVERY_ROOT="$TMP/network-temp-recovery"
TEMP_RECOVERY="$TEMP_RECOVERY_ROOT/devnet"
mkdir -p "$TEMP_RECOVERY"
RECOVERABLE_TEMP="$TEMP_RECOVERY/.network-identity.json.tmp.9999.0123456789abcdef"
printf 'stale\n' > "$RECOVERABLE_TEMP"
chmod 0600 "$RECOVERABLE_TEMP"
python3 "$TOOL" ensure-network --allowed-root "$TEMP_RECOVERY_ROOT" \
  --directory "$TEMP_RECOVERY" --identity-file "$NETWORK_ID" >/dev/null
assert_absent "$RECOVERABLE_TEMP"
assert_file "$TEMP_RECOVERY/network-identity.json"
LINKED_TEMP="$TEMP_RECOVERY/.network-identity.json.tmp.9999.fedcba9876543210"
ln "$TEMP_RECOVERY/network-identity.json" "$LINKED_TEMP"
python3 "$TOOL" ensure-network --allowed-root "$TEMP_RECOVERY_ROOT" \
  --directory "$TEMP_RECOVERY" --identity-file "$NETWORK_ID" >/dev/null
assert_absent "$LINKED_TEMP"

UNKNOWN_TEMP_ROOT="$TMP/network-temp-unknown"
mkdir -p "$UNKNOWN_TEMP_ROOT/devnet"
printf 'unknown\n' > "$UNKNOWN_TEMP_ROOT/devnet/.network-identity.json.tmp.unknown"
expect_failure "unknown marker temporary" python3 "$TOOL" ensure-network \
  --allowed-root "$UNKNOWN_TEMP_ROOT" --directory "$UNKNOWN_TEMP_ROOT/devnet" \
  --identity-file "$NETWORK_ID"
grep -Fq 'refusing to adopt nonempty state without marker' "$TMP/failure.err" \
  || fail "unknown marker-temporary diagnostic is missing"
assert_file "$UNKNOWN_TEMP_ROOT/devnet/.network-identity.json.tmp.unknown"

UNSAFE_TEMP_ROOT="$TMP/network-temp-unsafe"
mkdir -p "$UNSAFE_TEMP_ROOT/devnet"
ln -s "$NETWORK_ID" \
  "$UNSAFE_TEMP_ROOT/devnet/.network-identity.json.tmp.9999.1111111111111111"
expect_failure "unsafe marker temporary" python3 "$TOOL" ensure-network \
  --allowed-root "$UNSAFE_TEMP_ROOT" --directory "$UNSAFE_TEMP_ROOT/devnet" \
  --identity-file "$NETWORK_ID"
grep -Fq 'unsafe atomic marker temporary requires inspection' "$TMP/failure.err" \
  || fail "unsafe marker-temporary diagnostic is missing"

# The integrated acquisition API takes one NETWORK_ROOT lock for the global
# identity snapshot, marker publication, and exclusive non-reentrant lease.
ACQUIRE_NETWORK="$(make_atomic_network "$TMP/acquire-basic")"
ACQUIRE_APP="$TMP/acquire-basic-app.json"
ACQUIRE_LEASE="$TMP/acquire-basic-lease.json"
make_app_identity "$ACQUIRE_APP" "$ACQUIRE_NETWORK" alpha compose chain-alpha
make_lease_identity "$ACQUIRE_LEASE" "$ACQUIRE_APP"
atomic_acquire "$ACQUIRE_NETWORK" alpha compose "$ACQUIRE_APP" "$ACQUIRE_LEASE" \
  > "$TMP/acquire-basic.out"
assert_file "$ACQUIRE_NETWORK/instances/alpha/compose/appchain-identity.json"
assert_file "$ACQUIRE_NETWORK/l1/compose/demo-owner.json"
expect_failure "non-reentrant integrated lease" atomic_acquire \
  "$ACQUIRE_NETWORK" alpha compose "$ACQUIRE_APP" "$ACQUIRE_LEASE"
grep -Fq 'acquisition is non-reentrant' "$TMP/failure.err" \
  || fail "non-reentrant acquisition diagnostic is missing"

# Lease validation and the atomic acquisition snapshot share the same strict
# owner/mode/link checks as bootstrap markers.
ACQUIRE_LEASE_MARKER="$ACQUIRE_NETWORK/l1/compose/demo-owner.json"
chmod 0660 "$ACQUIRE_LEASE_MARKER"
expect_failure "permissive lease marker" python3 "$TOOL" lease-validate \
  --allowed-root "$ACQUIRE_NETWORK" --directory "$ACQUIRE_NETWORK/l1/compose" \
  --identity-file "$ACQUIRE_LEASE"
grep -Fq 'writable by another user' "$TMP/failure.err" \
  || fail "permissive lease-marker diagnostic is missing"
chmod 0600 "$ACQUIRE_LEASE_MARKER"
ln "$ACQUIRE_LEASE_MARKER" "$TMP/lease-marker-hardlink"
expect_failure "hardlinked lease marker" python3 "$TOOL" lease-validate \
  --allowed-root "$ACQUIRE_NETWORK" --directory "$ACQUIRE_NETWORK/l1/compose" \
  --identity-file "$ACQUIRE_LEASE"
grep -Fq 'must not have hard links' "$TMP/failure.err" \
  || fail "hardlinked lease-marker diagnostic is missing"
rm "$TMP/lease-marker-hardlink"
atomic_release "$ACQUIRE_NETWORK" compose "$ACQUIRE_LEASE"

ACQUIRE_APP_MARKER="$ACQUIRE_NETWORK/instances/alpha/compose/appchain-identity.json"
chmod 0660 "$ACQUIRE_APP_MARKER"
expect_failure "permissive app marker" atomic_acquire \
  "$ACQUIRE_NETWORK" alpha compose "$ACQUIRE_APP" "$ACQUIRE_LEASE"
grep -Fq 'writable by another user' "$TMP/failure.err" \
  || fail "permissive app-marker diagnostic is missing"
chmod 0600 "$ACQUIRE_APP_MARKER"
ln "$ACQUIRE_APP_MARKER" "$TMP/app-marker-hardlink"
expect_failure "hardlinked app marker" atomic_acquire \
  "$ACQUIRE_NETWORK" alpha compose "$ACQUIRE_APP" "$ACQUIRE_LEASE"
grep -Fq 'must not have hard links' "$TMP/failure.err" \
  || fail "hardlinked app-marker diagnostic is missing"
rm "$TMP/app-marker-hardlink"
atomic_acquire "$ACQUIRE_NETWORK" alpha compose "$ACQUIRE_APP" "$ACQUIRE_LEASE" >/dev/null
atomic_release "$ACQUIRE_NETWORK" compose "$ACQUIRE_LEASE"

# Different instances with different chains race only for the L1 lease. The
# loser must not leave even an empty instance/deployment control directory.
LEASE_RACE_NETWORK="$(make_atomic_network "$TMP/acquire-lease-race")"
make_app_identity "$TMP/lease-race-a-app.json" "$LEASE_RACE_NETWORK" racea compose chain-racea
make_lease_identity "$TMP/lease-race-a-lease.json" "$TMP/lease-race-a-app.json"
make_app_identity "$TMP/lease-race-b-app.json" "$LEASE_RACE_NETWORK" raceb compose chain-raceb
make_lease_identity "$TMP/lease-race-b-lease.json" "$TMP/lease-race-b-app.json"
(
  set +e
  atomic_acquire "$LEASE_RACE_NETWORK" racea compose \
    "$TMP/lease-race-a-app.json" "$TMP/lease-race-a-lease.json" \
    >"$TMP/lease-race-a.out" 2>"$TMP/lease-race-a.err"
  printf '%s\n' "$?" > "$TMP/lease-race-a.code"
) & pid_a=$!
(
  set +e
  atomic_acquire "$LEASE_RACE_NETWORK" raceb compose \
    "$TMP/lease-race-b-app.json" "$TMP/lease-race-b-lease.json" \
    >"$TMP/lease-race-b.out" 2>"$TMP/lease-race-b.err"
  printf '%s\n' "$?" > "$TMP/lease-race-b.code"
) & pid_b=$!
wait "$pid_a"; wait "$pid_b"
[ "$(grep -h '^0$' "$TMP"/lease-race-?.code | wc -l | tr -d ' ')" = 1 ] \
  || fail "different-chain lease race did not have exactly one winner"
if [ "$(cat "$TMP/lease-race-a.code")" = 0 ]; then
  atomic_release "$LEASE_RACE_NETWORK" compose "$TMP/lease-race-a-lease.json"
  assert_absent "$LEASE_RACE_NETWORK/instances/raceb/compose"
else
  atomic_release "$LEASE_RACE_NETWORK" compose "$TMP/lease-race-b-lease.json"
  assert_absent "$LEASE_RACE_NETWORK/instances/racea/compose"
fi

# A chain id is a permanent network-wide claim, including across deployment
# modes. Concurrent same-chain acquisition has one winner and no loser marker.
CHAIN_RACE_NETWORK="$(make_atomic_network "$TMP/acquire-chain-race")"
make_app_identity "$TMP/chain-race-a-app.json" "$CHAIN_RACE_NETWORK" chaina compose shared-chain
make_lease_identity "$TMP/chain-race-a-lease.json" "$TMP/chain-race-a-app.json"
make_app_identity "$TMP/chain-race-b-app.json" "$CHAIN_RACE_NETWORK" chainb compose shared-chain
make_lease_identity "$TMP/chain-race-b-lease.json" "$TMP/chain-race-b-app.json"
(
  set +e
  atomic_acquire "$CHAIN_RACE_NETWORK" chaina compose \
    "$TMP/chain-race-a-app.json" "$TMP/chain-race-a-lease.json" \
    >"$TMP/chain-race-a.out" 2>"$TMP/chain-race-a.err"
  printf '%s\n' "$?" > "$TMP/chain-race-a.code"
) & pid_a=$!
(
  set +e
  atomic_acquire "$CHAIN_RACE_NETWORK" chainb compose \
    "$TMP/chain-race-b-app.json" "$TMP/chain-race-b-lease.json" \
    >"$TMP/chain-race-b.out" 2>"$TMP/chain-race-b.err"
  printf '%s\n' "$?" > "$TMP/chain-race-b.code"
) & pid_b=$!
wait "$pid_a"; wait "$pid_b"
[ "$(grep -h '^0$' "$TMP"/chain-race-?.code | wc -l | tr -d ' ')" = 1 ] \
  || fail "same-chain race did not have exactly one winner"
if [ "$(cat "$TMP/chain-race-a.code")" = 0 ]; then
  atomic_release "$CHAIN_RACE_NETWORK" compose "$TMP/chain-race-a-lease.json"
  assert_absent "$CHAIN_RACE_NETWORK/instances/chainb/compose"
  expect_failure "permanent same-chain claim" atomic_acquire "$CHAIN_RACE_NETWORK" \
    chainb compose "$TMP/chain-race-b-app.json" "$TMP/chain-race-b-lease.json"
else
  atomic_release "$CHAIN_RACE_NETWORK" compose "$TMP/chain-race-b-lease.json"
  assert_absent "$CHAIN_RACE_NETWORK/instances/chaina/compose"
  expect_failure "permanent same-chain claim" atomic_acquire "$CHAIN_RACE_NETWORK" \
    chaina compose "$TMP/chain-race-a-app.json" "$TMP/chain-race-a-lease.json"
fi
grep -Fq 'permanently claimed' "$TMP/failure.err" \
  || fail "permanent chain claim diagnostic is missing"
make_app_identity "$TMP/chain-cross-mode-app.json" "$CHAIN_RACE_NETWORK" \
  crossmode host shared-chain
make_lease_identity "$TMP/chain-cross-mode-lease.json" "$TMP/chain-cross-mode-app.json"
expect_failure "network-wide chain claim across deployments" atomic_acquire \
  "$CHAIN_RACE_NETWORK" crossmode host "$TMP/chain-cross-mode-app.json" \
  "$TMP/chain-cross-mode-lease.json"
grep -Fq 'permanently claimed' "$TMP/failure.err" \
  || fail "cross-deployment chain claim diagnostic is missing"

# The same (deployment, instance) cannot be assigned a different chain even
# after its lease is released.
INSTANCE_CLAIM_NETWORK="$(make_atomic_network "$TMP/acquire-instance-claim")"
make_app_identity "$TMP/instance-claim-a-app.json" "$INSTANCE_CLAIM_NETWORK" same compose chain-one
make_lease_identity "$TMP/instance-claim-a-lease.json" "$TMP/instance-claim-a-app.json"
make_app_identity "$TMP/instance-claim-b-app.json" "$INSTANCE_CLAIM_NETWORK" same compose chain-two
make_lease_identity "$TMP/instance-claim-b-lease.json" "$TMP/instance-claim-b-app.json"
(
  set +e
  atomic_acquire "$INSTANCE_CLAIM_NETWORK" same compose \
    "$TMP/instance-claim-a-app.json" "$TMP/instance-claim-a-lease.json" \
    >"$TMP/instance-claim-a.out" 2>"$TMP/instance-claim-a.err"
  printf '%s\n' "$?" > "$TMP/instance-claim-a.code"
) & pid_a=$!
(
  set +e
  atomic_acquire "$INSTANCE_CLAIM_NETWORK" same compose \
    "$TMP/instance-claim-b-app.json" "$TMP/instance-claim-b-lease.json" \
    >"$TMP/instance-claim-b.out" 2>"$TMP/instance-claim-b.err"
  printf '%s\n' "$?" > "$TMP/instance-claim-b.code"
) & pid_b=$!
wait "$pid_a"; wait "$pid_b"
[ "$(grep -h '^0$' "$TMP"/instance-claim-?.code | wc -l | tr -d ' ')" = 1 ] \
  || fail "same-instance different-chain race did not have exactly one winner"
if [ "$(cat "$TMP/instance-claim-a.code")" = 0 ]; then
  atomic_release "$INSTANCE_CLAIM_NETWORK" compose "$TMP/instance-claim-a-lease.json"
  LOSING_INSTANCE_APP="$TMP/instance-claim-b-app.json"
  LOSING_INSTANCE_LEASE="$TMP/instance-claim-b-lease.json"
else
  atomic_release "$INSTANCE_CLAIM_NETWORK" compose "$TMP/instance-claim-b-lease.json"
  LOSING_INSTANCE_APP="$TMP/instance-claim-a-app.json"
  LOSING_INSTANCE_LEASE="$TMP/instance-claim-a-lease.json"
fi
expect_failure "same instance different chain" atomic_acquire "$INSTANCE_CLAIM_NETWORK" \
  same compose "$LOSING_INSTANCE_APP" "$LOSING_INSTANCE_LEASE"
grep -Fq 'conflicts with its immutable active marker' "$TMP/failure.err" \
  || fail "same-instance immutable-claim diagnostic is missing"

# One canonical but partial global marker poisons the entire snapshot rather
# than becoming a weak claim that bypasses full immutable-profile validation.
MALFORMED_NETWORK="$(make_atomic_network "$TMP/acquire-malformed")"
mkdir -p "$MALFORMED_NETWORK/instances/partial/host"
printf '%s\n' \
  '{"chainIds":["partial-chain"],"deployment":"host","instanceId":"partial","kind":"yano.demo.appchain-identity","networkName":"devnet","schemaVersion":1}' \
  > "$MALFORMED_NETWORK/instances/partial/host/appchain-identity.json"
chmod 600 "$MALFORMED_NETWORK/instances/partial/host/appchain-identity.json"
make_app_identity "$TMP/malformed-good-app.json" "$MALFORMED_NETWORK" good compose chain-good
make_lease_identity "$TMP/malformed-good-lease.json" "$TMP/malformed-good-app.json"
expect_failure "partial canonical global marker" atomic_acquire "$MALFORMED_NETWORK" \
  good compose "$TMP/malformed-good-app.json" "$TMP/malformed-good-lease.json"
grep -Fq 'invalid field set' "$TMP/failure.err" \
  || fail "partial global marker diagnostic is missing"
assert_absent "$MALFORMED_NETWORK/instances/good/compose"

UNSAFE_CLAIM_NETWORK="$(make_atomic_network "$TMP/acquire-unsafe-records")"
make_app_identity "$TMP/unsafe-record-good-app.json" "$UNSAFE_CLAIM_NETWORK" \
  good compose chain-safe-good
make_lease_identity "$TMP/unsafe-record-good-lease.json" "$TMP/unsafe-record-good-app.json"
mkdir -p "$UNSAFE_CLAIM_NETWORK/instances/broken/host"
expect_failure "markerless global deployment record" atomic_acquire "$UNSAFE_CLAIM_NETWORK" \
  good compose "$TMP/unsafe-record-good-app.json" "$TMP/unsafe-record-good-lease.json"
grep -Fq 'missing its identity marker' "$TMP/failure.err" \
  || fail "markerless global record diagnostic is missing"
rm -rf "$UNSAFE_CLAIM_NETWORK/instances/broken"
make_app_identity "$TMP/symlink-record-target.json" "$UNSAFE_CLAIM_NETWORK" \
  linked host chain-linked
mkdir -p "$UNSAFE_CLAIM_NETWORK/instances/linked/host"
ln -s "$TMP/symlink-record-target.json" \
  "$UNSAFE_CLAIM_NETWORK/instances/linked/host/appchain-identity.json"
expect_failure "symlinked global deployment record" atomic_acquire "$UNSAFE_CLAIM_NETWORK" \
  good compose "$TMP/unsafe-record-good-app.json" "$TMP/unsafe-record-good-lease.json"
grep -Fq 'must not be a symlink' "$TMP/failure.err" \
  || fail "symlinked global record diagnostic is missing"
assert_absent "$UNSAFE_CLAIM_NETWORK/instances/good/compose"

# Two lineage records cannot branch into the same replacement identity. A
# malformed global collision blocks every new acquisition.
COLLISION_NETWORK="$(make_atomic_network "$TMP/acquire-lineage-collision")"
mkdir -p "$COLLISION_NETWORK/retired/compose" "$COLLISION_NETWORK/reservations/compose"
python3 - "$COLLISION_NETWORK" <<'PY'
import json
from pathlib import Path
import sys

root = Path(sys.argv[1])
for old, old_chain, byte in (("olda", "chain-olda", "11"), ("oldb", "chain-oldb", "22")):
    document = {
        "schemaVersion": 1,
        "kind": "yano.demo.retired-instance",
        "networkName": "devnet",
        "instanceId": old,
        "deployment": "compose",
        "chainId": old_chain,
        "appchainIdentitySha256": byte * 32,
        "replacementInstanceId": "branched",
        "replacementChainId": "chain-branched",
        "status": "retired",
        "updatedAtMillis": 1,
    }
    path = root / "retired" / "compose" / f"{old}.json"
    path.write_text(json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n")
    path.chmod(0o600)
reservation = {
    "schemaVersion": 1,
    "kind": "yano.demo.reset-reservation",
    "networkName": "devnet",
    "retiredInstanceId": "olda",
    "deployment": "compose",
    "oldChainId": "chain-olda",
    "newInstanceId": "branched",
    "newChainId": "chain-branched",
}
path = root / "reservations" / "compose" / "branched.json"
path.write_text(json.dumps(reservation, sort_keys=True, separators=(",", ":")) + "\n")
path.chmod(0o600)
PY
make_app_identity "$TMP/collision-good-app.json" "$COLLISION_NETWORK" good host chain-collision-good
make_lease_identity "$TMP/collision-good-lease.json" "$TMP/collision-good-app.json"
expect_failure "branched replacement collision" atomic_acquire "$COLLISION_NETWORK" good host \
  "$TMP/collision-good-app.json" "$TMP/collision-good-lease.json"
grep -Fq 'multiple retirement records reserve' "$TMP/failure.err" \
  || fail "branched replacement collision diagnostic is missing"

# Journaled cleanup performs every preflight before publishing its global
# transaction. An active lease therefore leaves no cleanup fence behind.
CLEAN_NETWORK="$(make_atomic_network "$TMP/atomic-cleanup")"
CLEAN_RUNTIME="$TMP/atomic-cleanup-runtime"
mkdir -p "$CLEAN_RUNTIME/guarded/compose"
make_app_identity "$TMP/clean-guarded-app.json" "$CLEAN_NETWORK" guarded compose chain-guarded
make_lease_identity "$TMP/clean-guarded-lease.json" "$TMP/clean-guarded-app.json"
atomic_acquire "$CLEAN_NETWORK" guarded compose \
  "$TMP/clean-guarded-app.json" "$TMP/clean-guarded-lease.json" >/dev/null
mkdir -p "$CLEAN_NETWORK/instances/guarded/compose/observability"
printf 'guarded\n' > "$CLEAN_NETWORK/instances/guarded/compose/observability/sentinel"
make_cleanup_plan "$TMP/clean-guarded-plan.json" "$CLEAN_NETWORK" \
  "$CLEAN_RUNTIME/guarded" guarded compose chain-guarded "$TMP/clean-guarded-app.json" \
  observability
expect_failure "cleanup active lease preflight" cleanup_execute "$CLEAN_NETWORK" \
  "$CLEAN_RUNTIME/guarded" "$TMP/clean-guarded-plan.json"
grep -Fq 'actively leased' "$TMP/failure.err" \
  || fail "active cleanup lease diagnostic is missing"
assert_absent "$CLEAN_NETWORK/.yano-cleanup-transaction.json"
assert_file "$CLEAN_NETWORK/instances/guarded/compose/observability/sentinel"
atomic_release "$CLEAN_NETWORK" compose "$TMP/clean-guarded-lease.json"

# Crash after central publication leaves the original target intact. A linked
# atomic temp is recovered, a different plan is refused, and the exact plan
# resumes to completion.
expect_failure "cleanup stop after transaction" env \
  YANO_LIFECYCLE_TEST_STOP_AFTER=transaction python3 "$TOOL" cleanup-execute \
  --network-root "$CLEAN_NETWORK" --runtime-allowed-root "$CLEAN_RUNTIME/guarded" \
  --plan-file "$TMP/clean-guarded-plan.json" --yes
CENTRAL_TX="$CLEAN_NETWORK/.yano-cleanup-transaction.json"
assert_file "$CENTRAL_TX"
assert_file "$CLEAN_NETWORK/instances/guarded/compose/observability/sentinel"
mkdir -p "$CLEAN_NETWORK/instances/guarded/compose/reports"
printf 'report\n' > "$CLEAN_NETWORK/instances/guarded/compose/reports/sentinel"
make_cleanup_plan "$TMP/clean-different-plan.json" "$CLEAN_NETWORK" \
  "$CLEAN_RUNTIME/guarded" guarded compose chain-guarded "$TMP/clean-guarded-app.json" reports
expect_failure "different cleanup plan during recovery" cleanup_execute "$CLEAN_NETWORK" \
  "$CLEAN_RUNTIME/guarded" "$TMP/clean-different-plan.json"
grep -Fq 'another or malformed cleanup plan is already published' "$TMP/failure.err" \
  || fail "different cleanup-plan recovery diagnostic is missing"
make_app_identity "$TMP/cleanup-fenced-acquire-app.json" "$CLEAN_NETWORK" \
  fenced host chain-fenced
make_lease_identity "$TMP/cleanup-fenced-acquire-lease.json" \
  "$TMP/cleanup-fenced-acquire-app.json"
expect_failure "deployment acquisition during cleanup" atomic_acquire "$CLEAN_NETWORK" \
  fenced host "$TMP/cleanup-fenced-acquire-app.json" "$TMP/cleanup-fenced-acquire-lease.json"
grep -Fq 'cleanup transaction is active' "$TMP/failure.err" \
  || fail "active cleanup did not fence deployment acquisition"
assert_absent "$CLEAN_NETWORK/instances/fenced/host"
LINKED_CENTRAL_TEMP="$CLEAN_NETWORK/..yano-cleanup-transaction.json.tmp.9999.0123456789abcdef"
ln "$CENTRAL_TX" "$LINKED_CENTRAL_TEMP"
cleanup_execute "$CLEAN_NETWORK" "$CLEAN_RUNTIME/guarded" \
  "$TMP/clean-guarded-plan.json" > "$TMP/cleanup-target-recovery.out"
assert_absent "$LINKED_CENTRAL_TEMP"
assert_absent "$CENTRAL_TX"
assert_absent "$CLEAN_NETWORK/instances/guarded/compose/observability"

# Crash after rename exercises the quarantine-only state; recovery deletes the
# exact quarantine and transaction without touching sibling state.
make_cleanup_plan "$TMP/clean-reports-plan.json" "$CLEAN_NETWORK" \
  "$CLEAN_RUNTIME/guarded" guarded compose chain-guarded "$TMP/clean-guarded-app.json" reports
expect_failure "cleanup stop after quarantine" env \
  YANO_LIFECYCLE_TEST_STOP_AFTER=quarantine:reports python3 "$TOOL" cleanup-execute \
  --network-root "$CLEAN_NETWORK" --runtime-allowed-root "$CLEAN_RUNTIME/guarded" \
  --plan-file "$TMP/clean-reports-plan.json" --yes
assert_absent "$CLEAN_NETWORK/instances/guarded/compose/reports"
REPORT_QUARANTINE="$(find "$CLEAN_NETWORK/instances/guarded/compose" -maxdepth 1 \
  -name '.reports.yano-quarantine.*' -print)"
[ -n "$REPORT_QUARANTINE" ] || fail "cleanup quarantine-only state was not retained"
assert_file "$REPORT_QUARANTINE/sentinel"
cleanup_execute "$CLEAN_NETWORK" "$CLEAN_RUNTIME/guarded" \
  "$TMP/clean-reports-plan.json" >/dev/null
assert_absent "$REPORT_QUARANTINE"
assert_absent "$CENTRAL_TX"

# Crash after quarantine deletion exercises the journal+neither state.
mkdir -p "$CLEAN_RUNTIME/guarded/compose"
printf 'runtime\n' > "$CLEAN_RUNTIME/guarded/compose/sentinel"
make_cleanup_plan "$TMP/clean-runtime-plan.json" "$CLEAN_NETWORK" \
  "$CLEAN_RUNTIME/guarded" guarded compose chain-guarded "$TMP/clean-guarded-app.json" runtime
expect_failure "cleanup stop after target deletion" env \
  YANO_LIFECYCLE_TEST_STOP_AFTER=delete:runtime python3 "$TOOL" cleanup-execute \
  --network-root "$CLEAN_NETWORK" --runtime-allowed-root "$CLEAN_RUNTIME/guarded" \
  --plan-file "$TMP/clean-runtime-plan.json" --yes
assert_file "$CENTRAL_TX"
assert_absent "$CLEAN_RUNTIME/guarded/compose"
[ -z "$(find "$CLEAN_RUNTIME/guarded" -maxdepth 1 -name '.compose.yano-quarantine.*' -print)" ] \
  || fail "delete-phase stop retained a quarantine"
cleanup_execute "$CLEAN_NETWORK" "$CLEAN_RUNTIME/guarded" \
  "$TMP/clean-runtime-plan.json" >/dev/null
assert_absent "$CENTRAL_TX"

# Instance cleanup fences its successor before deleting anything, then can
# resume with the active marker living only in its exact quarantine. Retirement
# completes only after runtime and instance targets are both gone.
mkdir -p "$CLEAN_RUNTIME/guarded/compose"
printf 'runtime-again\n' > "$CLEAN_RUNTIME/guarded/compose/sentinel"
make_cleanup_plan "$TMP/clean-instance-plan.json" "$CLEAN_NETWORK" \
  "$CLEAN_RUNTIME/guarded" guarded compose chain-guarded "$TMP/clean-guarded-app.json" \
  instance successor chain-successor
expect_failure "cleanup stop after retirement record" env \
  YANO_LIFECYCLE_TEST_STOP_AFTER=retirement-record python3 "$TOOL" cleanup-execute \
  --network-root "$CLEAN_NETWORK" --runtime-allowed-root "$CLEAN_RUNTIME/guarded" \
  --plan-file "$TMP/clean-instance-plan.json" --yes
assert_file "$CENTRAL_TX"
assert_file "$CLEAN_NETWORK/retired/compose/guarded.json"
assert_absent "$CLEAN_NETWORK/reservations/compose/successor.json"
assert_file "$CLEAN_NETWORK/instances/guarded/compose/appchain-identity.json"
expect_failure "cleanup stop after complete retirement fence" env \
  YANO_LIFECYCLE_TEST_STOP_AFTER=retirement python3 "$TOOL" cleanup-execute \
  --network-root "$CLEAN_NETWORK" --runtime-allowed-root "$CLEAN_RUNTIME/guarded" \
  --plan-file "$TMP/clean-instance-plan.json" --yes
assert_file "$CENTRAL_TX"
assert_file "$CLEAN_NETWORK/reservations/compose/successor.json"
assert_file "$CLEAN_NETWORK/instances/guarded/compose/appchain-identity.json"
expect_failure "cleanup stop with quarantined attachment" env \
  YANO_LIFECYCLE_TEST_STOP_AFTER=quarantine:instance python3 "$TOOL" cleanup-execute \
  --network-root "$CLEAN_NETWORK" --runtime-allowed-root "$CLEAN_RUNTIME/guarded" \
  --plan-file "$TMP/clean-instance-plan.json" --yes
assert_file "$CENTRAL_TX"
INSTANCE_QUARANTINE="$(find "$CLEAN_NETWORK/instances/guarded" -maxdepth 1 \
  -name '.compose.yano-quarantine.*' -print)"
[ -n "$INSTANCE_QUARANTINE" ] || fail "quarantined app-chain attachment was not retained"
assert_file "$INSTANCE_QUARANTINE/appchain-identity.json"
expect_failure "cleanup stop after instance quarantine deletion" env \
  YANO_LIFECYCLE_TEST_STOP_AFTER=delete:instance python3 "$TOOL" cleanup-execute \
  --network-root "$CLEAN_NETWORK" --runtime-allowed-root "$CLEAN_RUNTIME/guarded" \
  --plan-file "$TMP/clean-instance-plan.json" --yes
assert_file "$CENTRAL_TX"
assert_absent "$INSTANCE_QUARANTINE"
assert_absent "$CLEAN_NETWORK/instances/guarded"
expect_failure "cleanup stop after retirement completion" env \
  YANO_LIFECYCLE_TEST_STOP_AFTER=retirement-complete python3 "$TOOL" cleanup-execute \
  --network-root "$CLEAN_NETWORK" --runtime-allowed-root "$CLEAN_RUNTIME/guarded" \
  --plan-file "$TMP/clean-instance-plan.json" --yes
assert_file "$CENTRAL_TX"
python3 - "$CLEAN_NETWORK/retired/compose/guarded.json" <<'PY'
import json
import sys
if json.load(open(sys.argv[1], encoding="utf-8")).get("status") != "retired":
    raise SystemExit("cleanup retirement did not durably complete before its injected stop")
PY
cleanup_execute "$CLEAN_NETWORK" "$CLEAN_RUNTIME/guarded" \
  "$TMP/clean-instance-plan.json" >/dev/null
assert_absent "$INSTANCE_QUARANTINE"
assert_absent "$CLEAN_NETWORK/instances/guarded"
assert_absent "$CENTRAL_TX"
python3 - "$CLEAN_NETWORK/retired/compose/guarded.json" <<'PY'
import json
import sys
if json.load(open(sys.argv[1], encoding="utf-8")).get("status") != "retired":
    raise SystemExit("cleanup retirement did not complete")
PY
assert_file "$CLEAN_NETWORK/reservations/compose/successor.json"
make_app_identity "$TMP/clean-successor-app.json" "$CLEAN_NETWORK" \
  successor compose chain-successor
make_lease_identity "$TMP/clean-successor-lease.json" "$TMP/clean-successor-app.json"
atomic_acquire "$CLEAN_NETWORK" successor compose \
  "$TMP/clean-successor-app.json" "$TMP/clean-successor-lease.json" >/dev/null
atomic_release "$CLEAN_NETWORK" compose "$TMP/clean-successor-lease.json"

# Host cleanup validates every known app-chain link before publishing or
# unlinking anything. A wrong sibling target leaves no transaction/retirement;
# the exact managed link is then removed as part of the successful reset.
HOST_CLEAN_NETWORK="$(make_atomic_network "$TMP/atomic-host-cleanup")"
make_app_identity "$TMP/host-clean-app.json" "$HOST_CLEAN_NETWORK" hostold host chain-hostold
make_lease_identity "$TMP/host-clean-lease.json" "$TMP/host-clean-app.json"
atomic_acquire "$HOST_CLEAN_NETWORK" hostold host \
  "$TMP/host-clean-app.json" "$TMP/host-clean-lease.json" >/dev/null
atomic_release "$HOST_CLEAN_NETWORK" host "$TMP/host-clean-lease.json"
HOST_DATA="$HOST_CLEAN_NETWORK/instances/hostold/host"
HOST_LINK="$HOST_CLEAN_NETWORK/l1/host/host-cluster/node0/chainstate/app-chain"
mkdir -p "$HOST_DATA/app-chain/node0" "$(dirname "$HOST_LINK")" "$TMP/wrong-host-target"
ln -s "$TMP/wrong-host-target" "$HOST_LINK"
HOST_RUNTIME_PARENT="$TMP/absent-host-runtime/hostold"
make_cleanup_plan "$TMP/host-clean-plan.json" "$HOST_CLEAN_NETWORK" \
  "$HOST_RUNTIME_PARENT" hostold host chain-hostold "$TMP/host-clean-app.json" \
  instance hostnext chain-hostnext
expect_failure "wrong managed host link" cleanup_execute "$HOST_CLEAN_NETWORK" \
  "$HOST_RUNTIME_PARENT" "$TMP/host-clean-plan.json"
grep -Fq 'points outside its exact app-chain target' "$TMP/failure.err" \
  || fail "wrong managed-host-link diagnostic is missing"
assert_absent "$HOST_CLEAN_NETWORK/.yano-cleanup-transaction.json"
assert_absent "$HOST_CLEAN_NETWORK/retired/host/hostold.json"
rm "$HOST_LINK"
ln -s "$HOST_DATA/app-chain/node0" "$HOST_LINK"
cleanup_execute "$HOST_CLEAN_NETWORK" "$HOST_RUNTIME_PARENT" \
  "$TMP/host-clean-plan.json" >/dev/null
assert_absent "$HOST_LINK"
assert_absent "$HOST_DATA"
assert_file "$HOST_CLEAN_NETWORK/retired/host/hostold.json"

# Host all-scope cleanup permits only its three exact, prevalidated managed
# app-chain links inside the L1 target. An extra nested link or a managed link
# with the wrong target fails before transaction publication or mutation.
HOST_ALL_NETWORK="$(make_atomic_network "$TMP/atomic-host-all-cleanup")"
make_app_identity "$TMP/host-all-app.json" "$HOST_ALL_NETWORK" hostall host chain-hostall
make_lease_identity "$TMP/host-all-lease.json" "$TMP/host-all-app.json"
atomic_acquire "$HOST_ALL_NETWORK" hostall host \
  "$TMP/host-all-app.json" "$TMP/host-all-lease.json" >/dev/null
atomic_release "$HOST_ALL_NETWORK" host "$TMP/host-all-lease.json"
HOST_ALL_DATA="$HOST_ALL_NETWORK/instances/hostall/host"
HOST_ALL_L1="$HOST_ALL_NETWORK/l1/host"
for index in 0 1 2; do
  mkdir -p "$HOST_ALL_DATA/app-chain/node$index" \
    "$HOST_ALL_L1/host-cluster/node$index/chainstate"
  ln -s "$HOST_ALL_DATA/app-chain/node$index" \
    "$HOST_ALL_L1/host-cluster/node$index/chainstate/app-chain"
done
printf 'l1\n' > "$HOST_ALL_L1/sentinel"
HOST_ALL_RUNTIME_PARENT="$TMP/absent-host-all-runtime/hostall"
make_cleanup_plan "$TMP/host-all-plan.json" "$HOST_ALL_NETWORK" \
  "$HOST_ALL_RUNTIME_PARENT" hostall host chain-hostall "$TMP/host-all-app.json" \
  all hostallnext chain-hostallnext

mkdir -p "$TMP/host-all-unmanaged-target"
ln -s "$TMP/host-all-unmanaged-target" \
  "$HOST_ALL_L1/host-cluster/node0/chainstate/unmanaged"
expect_failure "host all nested unmanaged link" cleanup_execute "$HOST_ALL_NETWORK" \
  "$HOST_ALL_RUNTIME_PARENT" "$TMP/host-all-plan.json"
grep -Fq 'cleanup target contains a symlink' "$TMP/failure.err" \
  || fail "host all unmanaged-link diagnostic is missing"
assert_absent "$HOST_ALL_NETWORK/.yano-cleanup-transaction.json"
assert_file "$HOST_ALL_DATA/appchain-identity.json"
assert_file "$HOST_ALL_L1/sentinel"
rm "$HOST_ALL_L1/host-cluster/node0/chainstate/unmanaged"

rm "$HOST_ALL_L1/host-cluster/node2/chainstate/app-chain"
ln -s "$HOST_ALL_DATA/app-chain/node1" \
  "$HOST_ALL_L1/host-cluster/node2/chainstate/app-chain"
expect_failure "host all wrong managed link" cleanup_execute "$HOST_ALL_NETWORK" \
  "$HOST_ALL_RUNTIME_PARENT" "$TMP/host-all-plan.json"
grep -Fq 'points outside its exact app-chain target' "$TMP/failure.err" \
  || fail "host all wrong-link diagnostic is missing"
assert_absent "$HOST_ALL_NETWORK/.yano-cleanup-transaction.json"
assert_file "$HOST_ALL_DATA/appchain-identity.json"
assert_file "$HOST_ALL_L1/sentinel"
rm "$HOST_ALL_L1/host-cluster/node2/chainstate/app-chain"
ln -s "$HOST_ALL_DATA/app-chain/node2" \
  "$HOST_ALL_L1/host-cluster/node2/chainstate/app-chain"

cleanup_execute "$HOST_ALL_NETWORK" "$HOST_ALL_RUNTIME_PARENT" \
  "$TMP/host-all-plan.json" >/dev/null
assert_absent "$HOST_ALL_DATA"
assert_absent "$HOST_ALL_L1"
assert_file "$HOST_ALL_NETWORK/retired/host/hostall.json"

# An unjournaled quarantine is never guessed or adopted by cleanup-execute.
ORPHAN_TX_NETWORK="$(make_atomic_network "$TMP/atomic-orphan-cleanup")"
mkdir -p "$ORPHAN_TX_NETWORK/l1/compose/node0"
ORPHAN_TX="$ORPHAN_TX_NETWORK/l1/compose/.node0.yano-quarantine.0123456789abcdef0123456789abcdef"
mkdir "$ORPHAN_TX"
make_app_identity "$TMP/orphan-placeholder-app.json" "$ORPHAN_TX_NETWORK" \
  unused compose chain-orphan-unused
make_cleanup_plan "$TMP/orphan-clean-plan.json" "$ORPHAN_TX_NETWORK" \
  "$TMP/orphan-absent-runtime" unused compose chain-orphan-unused \
  "$TMP/orphan-placeholder-app.json" l1
expect_failure "unknown central-cleanup quarantine" cleanup_execute "$ORPHAN_TX_NETWORK" \
  "$TMP/orphan-absent-runtime" "$TMP/orphan-clean-plan.json"
grep -Fq 'orphan cleanup journal or quarantine requires inspection' "$TMP/failure.err" \
  || fail "orphan cleanup-execute quarantine diagnostic is missing"
assert_dir "$ORPHAN_TX"
assert_absent "$ORPHAN_TX_NETWORK/.yano-cleanup-transaction.json"
make_app_identity "$TMP/orphan-fenced-app.json" "$ORPHAN_TX_NETWORK" \
  fenced host chain-orphan-fenced
make_lease_identity "$TMP/orphan-fenced-lease.json" "$TMP/orphan-fenced-app.json"
expect_failure "deployment acquisition with orphan quarantine" atomic_acquire \
  "$ORPHAN_TX_NETWORK" fenced host "$TMP/orphan-fenced-app.json" \
  "$TMP/orphan-fenced-lease.json"
grep -Fq 'cleanup journal or quarantine requires recovery' "$TMP/failure.err" \
  || fail "orphan quarantine did not fence deployment acquisition"

# l1-only cleanup needs no app identity and accepts an absent runtime parent;
# it still refuses retained attachments before publishing a transaction.
L1_NETWORK="$(make_atomic_network "$TMP/atomic-l1-cleanup")"
mkdir -p "$L1_NETWORK/l1/compose/node0"
printf 'l1\n' > "$L1_NETWORK/l1/compose/node0/sentinel"
make_app_identity "$TMP/l1-placeholder-app.json" "$L1_NETWORK" unused compose chain-unused
ABSENT_RUNTIME_PARENT="$TMP/does-not-exist/runtime-parent"
make_cleanup_plan "$TMP/l1-clean-plan.json" "$L1_NETWORK" "$ABSENT_RUNTIME_PARENT" \
  unused compose chain-unused "$TMP/l1-placeholder-app.json" l1
cleanup_execute "$L1_NETWORK" "$ABSENT_RUNTIME_PARENT" "$TMP/l1-clean-plan.json" >/dev/null
assert_absent "$L1_NETWORK/l1/compose"
assert_absent "$ABSENT_RUNTIME_PARENT"

L1_BLOCK_NETWORK="$(make_atomic_network "$TMP/atomic-l1-attachment")"
make_app_identity "$TMP/l1-block-app.json" "$L1_BLOCK_NETWORK" attached compose chain-attached
make_lease_identity "$TMP/l1-block-lease.json" "$TMP/l1-block-app.json"
atomic_acquire "$L1_BLOCK_NETWORK" attached compose \
  "$TMP/l1-block-app.json" "$TMP/l1-block-lease.json" >/dev/null
atomic_release "$L1_BLOCK_NETWORK" compose "$TMP/l1-block-lease.json"
make_cleanup_plan "$TMP/l1-block-plan.json" "$L1_BLOCK_NETWORK" \
  "$TMP/absent-l1-block-runtime" attached compose chain-attached \
  "$TMP/l1-block-app.json" l1
expect_failure "l1 retained attachment preflight" cleanup_execute "$L1_BLOCK_NETWORK" \
  "$TMP/absent-l1-block-runtime" "$TMP/l1-block-plan.json"
grep -Fq 'retained app-chain attachments' "$TMP/failure.err" \
  || fail "l1 attachment diagnostic is missing"
assert_absent "$L1_BLOCK_NETWORK/.yano-cleanup-transaction.json"

# A retirement that lost its active directory but has not reached `retired`
# remains an L1 attachment. Neither l1-only cleanup nor another instance's
# all-scope cleanup may cross that incomplete reset boundary.
L1_RETIRING_NETWORK="$(make_atomic_network "$TMP/atomic-l1-retiring-attachment")"
mkdir -p "$L1_RETIRING_NETWORK/l1/compose/node0"
printf 'retained-l1\n' > "$L1_RETIRING_NETWORK/l1/compose/node0/sentinel"
make_retiring_records "$L1_RETIRING_NETWORK" interrupted compose chain-interrupted \
  interrupted-next chain-interrupted-next "$(repeat_hex_byte 31)"
make_app_identity "$TMP/l1-retiring-placeholder.json" "$L1_RETIRING_NETWORK" \
  interrupted compose chain-interrupted
make_cleanup_plan "$TMP/l1-retiring-clean-plan.json" "$L1_RETIRING_NETWORK" \
  "$TMP/absent-l1-retiring-runtime" interrupted compose chain-interrupted \
  "$TMP/l1-retiring-placeholder.json" l1
expect_failure "l1 incomplete retirement attachment" cleanup_execute \
  "$L1_RETIRING_NETWORK" "$TMP/absent-l1-retiring-runtime" \
  "$TMP/l1-retiring-clean-plan.json"
grep -Fq 'retained app-chain attachments' "$TMP/failure.err" \
  || fail "incomplete-retirement L1 diagnostic is missing"
assert_file "$L1_RETIRING_NETWORK/l1/compose/node0/sentinel"
assert_absent "$L1_RETIRING_NETWORK/.yano-cleanup-transaction.json"

ALL_BLOCK_NETWORK="$(make_atomic_network "$TMP/atomic-all-retiring-sibling")"
make_app_identity "$TMP/all-selected-app.json" "$ALL_BLOCK_NETWORK" \
  selected compose chain-selected
make_lease_identity "$TMP/all-selected-lease.json" "$TMP/all-selected-app.json"
atomic_acquire "$ALL_BLOCK_NETWORK" selected compose \
  "$TMP/all-selected-app.json" "$TMP/all-selected-lease.json" >/dev/null
atomic_release "$ALL_BLOCK_NETWORK" compose "$TMP/all-selected-lease.json"
make_retiring_records "$ALL_BLOCK_NETWORK" sibling compose chain-sibling \
  sibling-next chain-sibling-next "$(repeat_hex_byte 32)"
make_cleanup_plan "$TMP/all-blocked-plan.json" "$ALL_BLOCK_NETWORK" \
  "$TMP/absent-all-block-runtime" selected compose chain-selected \
  "$TMP/all-selected-app.json" all selected-next chain-selected-next
expect_failure "all cleanup with incomplete sibling retirement" cleanup_execute \
  "$ALL_BLOCK_NETWORK" "$TMP/absent-all-block-runtime" "$TMP/all-blocked-plan.json"
grep -Fq 'retained app-chain attachments' "$TMP/failure.err" \
  || fail "all-scope incomplete-sibling diagnostic is missing"
assert_file "$ALL_BLOCK_NETWORK/instances/selected/compose/appchain-identity.json"
assert_absent "$ALL_BLOCK_NETWORK/.yano-cleanup-transaction.json"

# The explicit devnet factory reset removes every disposable data/runtime
# namespace. The old network identity authorizes cleanup and is then replaced
# by an exact reset marker so the next launch can safely create a current
# devnet system start. Its transaction resumes after quarantining a target.
RESET_BASE="$TMP/devnet-reset-data"
RESET_NETWORK="$(make_atomic_network "$RESET_BASE")"
RESET_RUNTIME="$TMP/devnet-reset-runtime"
make_app_identity "$TMP/reset-app.json" "$RESET_NETWORK" reset-a compose chain-reset-a
make_lease_identity "$TMP/reset-lease.json" "$TMP/reset-app.json"
atomic_acquire "$RESET_NETWORK" reset-a compose \
  "$TMP/reset-app.json" "$TMP/reset-lease.json" >/dev/null
mkdir -p "$RESET_NETWORK/retired/compose" \
  "$RESET_NETWORK/reservations/compose" \
  "$RESET_RUNTIME/networks/devnet/reset-a/compose"
printf 'retired\n' > "$RESET_NETWORK/retired/compose/sentinel"
printf 'reserved\n' > "$RESET_NETWORK/reservations/compose/sentinel"
printf 'runtime\n' > "$RESET_RUNTIME/networks/devnet/reset-a/compose/sentinel"

expect_failure "devnet reset without confirmation" python3 "$TOOL" reset-devnet \
  --data-base "$RESET_BASE" --runtime-base "$RESET_RUNTIME" --compose-stopped
grep -Fq 'reset-devnet requires explicit --yes' "$TMP/failure.err" \
  || fail "devnet reset confirmation rejection is missing"
assert_file "$RESET_NETWORK/instances/reset-a/compose/appchain-identity.json"

expect_failure "interrupted devnet reset" env \
  YANO_LIFECYCLE_TEST_STOP_AFTER=quarantine:devnet-instances \
  python3 "$TOOL" reset-devnet --data-base "$RESET_BASE" \
  --runtime-base "$RESET_RUNTIME" --compose-stopped --yes
grep -Fq 'test-requested stop after durable phase quarantine:devnet-instances' \
  "$TMP/failure.err" || fail "devnet reset did not reach its durable quarantine boundary"
assert_file "$RESET_NETWORK/.yano-devnet-reset-transaction.json"
assert_dir "$RESET_NETWORK/.instances.yano-devnet-reset-quarantine"

python3 "$TOOL" reset-devnet --data-base "$RESET_BASE" \
  --runtime-base "$RESET_RUNTIME" --compose-stopped --yes \
  > "$TMP/devnet-reset-resumed.out"
grep -Fq 'DEVNET RESET COMPLETE network=devnet secrets=preserved identity=regenerate' \
  "$TMP/devnet-reset-resumed.out" || fail "resumed devnet reset did not complete"
assert_file "$RESET_NETWORK/network-identity.json"
jq -e '.factoryResetPending == true and .networkName == "devnet"' \
  "$RESET_NETWORK/network-identity.json" >/dev/null \
  || fail "devnet reset did not publish its exact network reseed marker"
assert_file "$RESET_NETWORK/.yano-lifecycle.lock"
for removed in instances l1 retired reservations; do
  assert_absent "$RESET_NETWORK/$removed"
done
assert_absent "$RESET_RUNTIME/networks/devnet"
assert_absent "$RESET_NETWORK/.yano-devnet-reset-transaction.json"
assert_absent "$RESET_NETWORK/.instances.yano-devnet-reset-quarantine"

# Repeating the reset is harmless. Replacing the reset marker with a normal
# network identity then proves a previous disposable instance id is reusable.
python3 "$TOOL" reset-devnet --data-base "$RESET_BASE" \
  --runtime-base "$RESET_RUNTIME" --compose-stopped --yes >/dev/null
python3 "$TOOL" ensure-network --allowed-root "$RESET_BASE" \
  --directory "$RESET_NETWORK" --identity-file "$RESET_BASE/network-input.json" \
  --replace-factory-reset-pending >/dev/null
atomic_acquire "$RESET_NETWORK" reset-a compose \
  "$TMP/reset-app.json" "$TMP/reset-lease.json" >/dev/null
assert_file "$RESET_NETWORK/instances/reset-a/compose/appchain-identity.json"
atomic_release "$RESET_NETWORK" compose "$TMP/reset-lease.json"

# CLI help exposes only the supported atomic lifecycle surface. Superseded
# partial operations reject at argument parsing and cannot bypass invariants.
python3 "$TOOL" --help > "$TMP/help.out"
grep -Fq 'ensure-network' "$TMP/help.out" || fail "ensure-network help is missing"
grep -Fq 'deployment-acquire' "$TMP/help.out" || fail "deployment-acquire help is missing"
grep -Fq 'lease-validate' "$TMP/help.out" || fail "lease-validate help is missing"
grep -Fq 'lease-release' "$TMP/help.out" || fail "lease-release help is missing"
grep -Fq 'cleanup-execute' "$TMP/help.out" || fail "cleanup-execute help is missing"
grep -Fq 'reset-devnet' "$TMP/help.out" || fail "reset-devnet help is missing"
for removed in ensure-instance lease-acquire retirement-begin retirement-complete; do
  if grep -Fq "$removed" "$TMP/help.out"; then
    fail "removed lifecycle command remains in help: $removed"
  fi
  expect_failure "removed lifecycle command $removed" python3 "$TOOL" "$removed"
  grep -Fq "invalid choice: '$removed'" "$TMP/failure.err" \
    || fail "removed lifecycle command did not reject at parsing: $removed"
done
if grep -Eq '(^|[,{ ])clean([,} ]|$)' "$TMP/help.out"; then
  fail "generic clean command remains in lifecycle help"
fi
expect_failure "removed generic clean command" python3 "$TOOL" clean
grep -Fq "invalid choice: 'clean'" "$TMP/failure.err" \
  || fail "removed generic clean command did not reject at parsing"

printf 'PASS: lifecycle identity and cleanup safety contract\n'
