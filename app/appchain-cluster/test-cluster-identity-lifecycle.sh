#!/usr/bin/env bash
# Focused regression for the independent L1/app-chain identity lifecycles.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CLUSTER="$SCRIPT_DIR/cluster.sh"
WORK="$(mktemp -d /tmp/yano-cluster-identity-test.XXXXXX)"

die_test() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT INT TERM

# Load launcher functions without executing its command parser.
# shellcheck disable=SC1090
source /dev/stdin <<< "$(sed '/^# --- Arg parsing/,$d' "$CLUSTER")"

NETWORK="preprod"
ENABLE_ANCHOR=1
ANCHOR_MODE="script"
ANCHOR_KEY=""
ANCHOR_KEY_FILE=""
THRESHOLD=""
DEVNET_GENESIS_FILE_DIGEST=""
SELECTED_CHAIN=""
chain_ids() { printf '%s\n' "$SELECTED_CHAIN"; }

create_member_profile() {
  local directory="$1" first="$2" second="$3"
  local first_seed second_seed
  mkdir -m 700 "$directory"
  first_seed="$(repeat_byte "$(printf '%02x' "$(( first + 1 ))")")"
  second_seed="$(repeat_byte "$(printf '%02x' "$(( second + 1 ))")")"
  printf '%s\n' "$first_seed" > "$directory/node0.seed"
  printf '%s\n' "${PUBKEYS[$first]}" > "$directory/node0.public"
  printf '%s\n' "$second_seed" > "$directory/node1.seed"
  printf '%s\n' "${PUBKEYS[$second]}" > "$directory/node1.public"
  chmod 600 "$directory"/*
}

select_member_profile() {
  MEMBER_KEY_DIR="$1"
  validate_member_key_inputs 2
}

create_anchor_profile() {
  local directory="$1" value="$2"
  mkdir -m 700 "$directory"
  printf '%s\n' "$(repeat_byte "$value")" > "$directory/anchor.seed"
  chmod 400 "$directory/anchor.seed"
}

select_anchor_profile() {
  ANCHOR_KEY_FILE="$1/anchor.seed"
  ANCHOR_KEY=""
  validate_anchor_key_input
}

write_external_marker() {
  local output="$1" instance="$2" members proposer anchor_fingerprint
  members="$(members_csv 2)"
  proposer="$(node_pub 0)"
  anchor_fingerprint="$(anchor_signing_seed 0 | python3 -c '
import hashlib, sys
print(hashlib.sha256(b"yano-demo-anchor-signer-v1\0" + sys.stdin.buffer.read()).hexdigest())
')" || die_test "cannot fingerprint test anchor signer"
  python3 - "$output" "$instance" "$SELECTED_CHAIN" "$members" "$proposer" \
      "$anchor_fingerprint" <<'PY'
import json
from pathlib import Path
import sys

output, instance, chain, members, proposer, anchor_fingerprint = sys.argv[1:]
document = {
    "schemaVersion": 1,
    "kind": "yano.demo.appchain-identity",
    "layoutVersion": 1,
    "networkName": "preprod",
    "networkIdentitySha256": "test-network-identity",
    "instanceId": instance,
    "deployment": "host",
    "chainIds": [chain],
    "stateMachine": {
        "provider": "evidence-registry",
        "profileVersion": 1,
        "effectEmissionVersion": 1,
    },
    "membership": {
        "members": members.split(","),
        "threshold": 2,
        "proposer": proposer,
    },
    "anchor": {
        "enabled": True,
        "mode": "script",
        "signerFingerprint": anchor_fingerprint,
    },
    "effects": {"storageGate": "l1-anchored", "requireAnchor": True},
}
Path(output).write_text(
    json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n",
    encoding="utf-8",
)
PY
  chmod 600 "$output"
}

attach_appchain_state() {
  local instance="$1" chain="$2" i root target link
  for i in 0 1; do
    root="$CLUSTER_DIR/node$i/chainstate"
    target="$WORK/instances/$instance/node$i"
    link="$root/app-chain"
    mkdir -p "$root" "$target/$chain"
    touch "$target/$chain/CURRENT"
    if [ -e "$link" ] && [ ! -L "$link" ]; then
      die_test "unexpected non-symlink app-chain state root"
    fi
    [ ! -L "$link" ] || rm "$link"
    ln -s "$target" "$link"
  done
}

no_retained_state() { return 1; }
has_retained_state() { return 0; }

write_identity_candidate() {
  local path="$1" content="$2"
  printf '%s\n' "$content" > "$path"
  chmod 600 "$path"
}

assert_single_link() {
  python3 - "$1" <<'PY' >/dev/null 2>&1
import os
import stat
import sys

info = os.lstat(sys.argv[1])
raise SystemExit(0 if stat.S_ISREG(info.st_mode) and info.st_nlink == 1 else 1)
PY
}

exercise_identity_publication_recovery() {
  local family="$1" marker_name="$2" prefix="$3" label="$4"
  local directory marker old current unrelated unknown digest
  local payload='{"kind":"identity-publication-fixture","schemaVersion":1}'

  # Crash before publication: an exact, single-link old candidate is safe to
  # discard before the newly generated candidate is published create-only.
  directory="$WORK/publication-$family-temp-only"
  mkdir -m 700 "$directory"
  marker="$directory/$marker_name"
  old="$directory/${prefix}1001"
  current="$directory/${prefix}1002"
  write_identity_candidate "$old" "$payload"
  write_identity_candidate "$current" "$payload"
  install_or_validate_identity "$current" "$marker" no_retained_state "$label"
  [ -f "$marker" ] || die_test "$family temp-only recovery did not publish its marker"
  [ ! -e "$old" ] && [ ! -e "$current" ] \
    || die_test "$family temp-only recovery left a candidate behind"
  assert_single_link "$marker" \
    || die_test "$family temp-only recovery did not leave one durable marker link"
  digest="$(file_digest "$marker")"

  # Crash after create-only publication but before candidate cleanup: the
  # marker and old temporary are the same inode. Recovery removes only that
  # exact link, fsyncs the directory, and leaves the retained marker unchanged.
  directory="$WORK/publication-$family-linked"
  mkdir -m 700 "$directory"
  marker="$directory/$marker_name"
  old="$directory/${prefix}2001"
  current="$directory/${prefix}2002"
  write_identity_candidate "$marker" "$payload"
  ln "$marker" "$old"
  write_identity_candidate "$current" "$payload"
  [ "$(file_identity "$marker")" = "$(file_identity "$old")" ] \
    || die_test "$family linked recovery fixture is not the same inode"
  install_or_validate_identity "$current" "$marker" no_retained_state "$label"
  [ ! -e "$old" ] && [ ! -e "$current" ] \
    || die_test "$family linked recovery left a candidate behind"
  assert_single_link "$marker" \
    || die_test "$family linked recovery did not restore a single marker link"
  [ "$(file_digest "$marker")" = "$digest" ] \
    || die_test "$family linked recovery changed the marker bytes"

  # Recovery is driven by the marker/temporary inode, not by the new profile.
  # A changed profile still completes safe crash cleanup and then reports the
  # ordinary retained-identity mismatch without replacing the marker.
  directory="$WORK/publication-$family-linked-profile-change"
  mkdir -m 700 "$directory"
  marker="$directory/$marker_name"
  old="$directory/${prefix}2501"
  current="$directory/${prefix}2502"
  write_identity_candidate "$marker" "$payload"
  ln "$marker" "$old"
  write_identity_candidate "$current" '{"kind":"different-profile"}'
  if ( install_or_validate_identity "$current" "$marker" no_retained_state "$label" ) \
      > "$directory/result.log" 2>&1; then
    die_test "$family linked recovery accepted a changed retained profile"
  fi
  grep -q 'differs from retained state' "$directory/result.log" \
    || die_test "$family linked profile change produced the wrong diagnostic"
  [ ! -e "$old" ] && [ ! -e "$current" ] && assert_single_link "$marker" \
    || die_test "$family linked profile-change recovery was not completed safely"
  [ "$(file_digest "$marker")" = "$digest" ] \
    || die_test "$family linked profile-change recovery changed the marker"

  # A hard-linked temporary that is not the marker's inode is not a publication
  # crash state, even when it uses the exact reserved filename. Fail closed and
  # preserve the retained marker and suspicious link for operator inspection.
  directory="$WORK/publication-$family-mismatched-link"
  mkdir -m 700 "$directory"
  marker="$directory/$marker_name"
  old="$directory/${prefix}3001"
  current="$directory/${prefix}3002"
  unrelated="$directory/unrelated"
  write_identity_candidate "$marker" "$payload"
  write_identity_candidate "$unrelated" '{"kind":"not-the-marker"}'
  ln "$unrelated" "$old"
  write_identity_candidate "$current" "$payload"
  if ( install_or_validate_identity "$current" "$marker" no_retained_state "$label" ) \
      > "$directory/result.log" 2>&1; then
    die_test "$family accepted a mismatched hard-linked temporary"
  fi
  grep -q 'unsafe or unrecognized' "$directory/result.log" \
    || die_test "$family mismatched hard link produced the wrong diagnostic"
  [ -f "$old" ] && [ "$(file_identity "$old")" = "$(file_identity "$unrelated")" ] \
    || die_test "$family mismatched hard link was modified during refusal"
  [ "$(file_digest "$marker")" = "$digest" ] \
    || die_test "$family mismatched hard link changed the retained marker"

  # Names in the reserved temporary namespace must have the exact numeric
  # suffix generated by the launcher. Unknown entries are never swept.
  directory="$WORK/publication-$family-unknown-temp"
  mkdir -m 700 "$directory"
  marker="$directory/$marker_name"
  current="$directory/${prefix}4002"
  unknown="$directory/${prefix}unknown"
  write_identity_candidate "$current" "$payload"
  write_identity_candidate "$unknown" "$payload"
  if ( install_or_validate_identity "$current" "$marker" no_retained_state "$label" ) \
      > "$directory/result.log" 2>&1; then
    die_test "$family accepted an unknown identity temporary"
  fi
  grep -q 'unsafe or unrecognized' "$directory/result.log" \
    || die_test "$family unknown temporary produced the wrong diagnostic"
  [ ! -e "$marker" ] && [ -f "$unknown" ] \
    || die_test "$family unknown temporary refusal mutated publication state"

  # Exact crash debris must never weaken the original retained-state rule: if
  # state exists without its marker, recovery may clean safe temporaries but it
  # must not manufacture a new identity for that state.
  directory="$WORK/publication-$family-retained-without-marker"
  mkdir -m 700 "$directory"
  marker="$directory/$marker_name"
  old="$directory/${prefix}5001"
  current="$directory/${prefix}5002"
  write_identity_candidate "$old" "$payload"
  write_identity_candidate "$current" "$payload"
  if ( install_or_validate_identity "$current" "$marker" has_retained_state "$label" ) \
      > "$directory/result.log" 2>&1; then
    die_test "$family manufactured an identity for retained markerless state"
  fi
  grep -q 'retained .* state has no identity marker' "$directory/result.log" \
    || die_test "$family retained markerless state produced the wrong diagnostic"
  [ ! -e "$marker" ] && [ ! -e "$old" ] && [ ! -e "$current" ] \
    || die_test "$family retained markerless refusal did not safely clean exact debris"
}

# Both marker families use the same crash-safe protocol but distinct reserved
# temporary namespaces. These fixtures also execute every parent-directory
# fsync path used by initial publication and linked/pre-publication recovery.
exercise_identity_publication_recovery \
  l1 cluster-identity.json .cluster-l1-identity.tmp. "L1 identity"
exercise_identity_publication_recovery \
  app cluster-appchain-identity.json .cluster-app-identity.tmp. "app-chain identity"

KEYS_A="$WORK/member-a"
KEYS_B="$WORK/member-b"
ANCHOR_A="$WORK/anchor-a"
ANCHOR_B="$WORK/anchor-b"
create_member_profile "$KEYS_A" 3 4
create_member_profile "$KEYS_B" 5 6
create_anchor_profile "$ANCHOR_A" a1
create_anchor_profile "$ANCHOR_B" b1

# An orchestrator owns app-chain identity/state outside the retained L1 tree.
# Attachment A installs only the L1 marker. Attachment B may later use a
# different chain and membership while preserving that exact L1 marker.
CLUSTER_DIR="$WORK/shared-l1"
mkdir -m 700 "$CLUSTER_DIR"
SELECTED_CHAIN="evidence-chain-a"
select_member_profile "$KEYS_A"
select_anchor_profile "$ANCHOR_A"
MARKER_A="$WORK/appchain-a.json"
write_external_marker "$MARKER_A" "a"
APPCHAIN_IDENTITY_MARKER="$MARKER_A"
attach_appchain_state "a" "$SELECTED_CHAIN"
ensure_cluster_identity 2

L1_MARKER="$(cluster_identity_file)"
[ -f "$L1_MARKER" ] || die_test "attachment A did not install the L1 identity marker"
[ ! -e "$(cluster_app_identity_file)" ] \
  || die_test "external attachment A created a standalone app-chain marker"
L1_IDENTITY="$(file_identity "$L1_MARKER")"
L1_DIGEST="$(file_digest "$L1_MARKER")"

# External marker parsing is exact: JSON numeric fields are not booleans, and
# a second hard link cannot be used to swap the validated identity out-of-band.
BAD_SCHEMA_MARKER="$WORK/appchain-bad-schema.json"
python3 - "$MARKER_A" "$BAD_SCHEMA_MARKER" <<'PY'
import json
from pathlib import Path
import sys

document = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
document["schemaVersion"] = True
Path(sys.argv[2]).write_text(
    json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n",
    encoding="utf-8",
)
PY
chmod 600 "$BAD_SCHEMA_MARKER"
if ( APPCHAIN_IDENTITY_MARKER="$BAD_SCHEMA_MARKER"; ensure_cluster_identity 2 ) \
    > "$WORK/external-schema.log" 2>&1; then
  die_test "external marker accepted a boolean schema version"
fi
grep -q 'does not match the selected cluster profile' "$WORK/external-schema.log" \
  || die_test "invalid external schema produced the wrong diagnostic"

ln "$MARKER_A" "$WORK/appchain-a-second-link.json"
if ( APPCHAIN_IDENTITY_MARKER="$MARKER_A"; ensure_cluster_identity 2 ) \
    > "$WORK/external-hardlink.log" 2>&1; then
  die_test "external marker with multiple hard links was accepted"
fi
grep -q 'bounded launcher-owned 0400/0600 regular file' "$WORK/external-hardlink.log" \
  || die_test "hard-linked external marker produced the wrong diagnostic"
rm "$WORK/appchain-a-second-link.json"

chmod 500 "$MARKER_A"
if ( APPCHAIN_IDENTITY_MARKER="$MARKER_A"; ensure_cluster_identity 2 ) \
    > "$WORK/external-mode.log" 2>&1; then
  die_test "executable external marker mode was accepted"
fi
grep -q 'bounded launcher-owned 0400/0600 regular file' "$WORK/external-mode.log" \
  || die_test "executable external marker produced the wrong diagnostic"
chmod 600 "$MARKER_A"

for i in 0 1; do touch "$CLUSTER_DIR/node$i/chainstate/CURRENT"; done

SELECTED_CHAIN="evidence-chain-b"
select_member_profile "$KEYS_B"
select_anchor_profile "$ANCHOR_B"
MARKER_B="$WORK/appchain-b.json"
write_external_marker "$MARKER_B" "b"
APPCHAIN_IDENTITY_MARKER="$MARKER_B"
attach_appchain_state "b" "$SELECTED_CHAIN"
ensure_cluster_identity 2

[ "$(file_identity "$L1_MARKER")" = "$L1_IDENTITY" ] \
  || die_test "attachment B replaced the retained L1 identity marker"
[ "$(file_digest "$L1_MARKER")" = "$L1_DIGEST" ] \
  || die_test "attachment B changed the retained L1 identity marker"
[ ! -e "$(cluster_app_identity_file)" ] \
  || die_test "external attachment B created a standalone app-chain marker"

# Standalone state owns a local app-chain marker. A retained restart with a
# different member-key profile must fail, and an external marker cannot be used
# later to bypass that standalone identity.
CLUSTER_DIR="$WORK/standalone"
mkdir -m 700 "$CLUSTER_DIR"
SELECTED_CHAIN="standalone-chain"
APPCHAIN_IDENTITY_MARKER=""
select_member_profile "$KEYS_A"
select_anchor_profile "$ANCHOR_A"
ensure_cluster_identity 2
STANDALONE_MARKER="$(cluster_app_identity_file)"
[ -f "$STANDALONE_MARKER" ] || die_test "standalone app-chain marker was not installed"
STANDALONE_DIGEST="$(file_digest "$STANDALONE_MARKER")"
mkdir -p "$CLUSTER_DIR/node0/chainstate/app-chain/$SELECTED_CHAIN"
touch "$CLUSTER_DIR/node0/chainstate/CURRENT"
touch "$CLUSTER_DIR/node0/chainstate/app-chain/$SELECTED_CHAIN/CURRENT"

select_member_profile "$KEYS_B"
select_anchor_profile "$ANCHOR_A"
if ( APPCHAIN_IDENTITY_MARKER=""; ensure_cluster_identity 2 ) \
    > "$WORK/standalone-key-change.log" 2>&1; then
  die_test "standalone retained state accepted a different member-key profile"
fi
grep -q 'app-chain identity differs from retained state' "$WORK/standalone-key-change.log" \
  || die_test "standalone key change produced the wrong diagnostic"
[ "$(file_digest "$STANDALONE_MARKER")" = "$STANDALONE_DIGEST" ] \
  || die_test "failed standalone restart changed its identity marker"

OVERRIDE_MARKER="$WORK/standalone-override.json"
write_external_marker "$OVERRIDE_MARKER" "override"
if ( APPCHAIN_IDENTITY_MARKER="$OVERRIDE_MARKER"; ensure_cluster_identity 2 ) \
    > "$WORK/standalone-external-override.log" 2>&1; then
  die_test "external marker bypassed a retained standalone identity"
fi
grep -q 'cannot replace a standalone cluster identity' \
  "$WORK/standalone-external-override.log" \
  || die_test "standalone external-marker override produced the wrong diagnostic"
[ "$(file_digest "$STANDALONE_MARKER")" = "$STANDALONE_DIGEST" ] \
  || die_test "external override attempt changed the standalone identity marker"

printf 'PASS: sequential external app-chain attachments preserve L1 identity; standalone changes fail closed\n'
