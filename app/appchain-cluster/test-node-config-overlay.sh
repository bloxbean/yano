#!/usr/bin/env bash
# Focused, process-free regression for private per-node config overlays.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CLUSTER="$SCRIPT_DIR/cluster.sh"
WORK="$(mktemp -d /tmp/yano-node-config-test.XXXXXX)"

die_test() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT INT TERM

# Load launcher functions without executing its command parser.
# shellcheck disable=SC1090
source /dev/stdin <<< "$(sed '/^# --- Arg parsing/,$d' "$CLUSTER")"
unset JAVA_OPTS YANO_EXTRA_ARGS

# The unset feature must be a true no-op.
NODE_CONFIG_DIR=""
validate_node_config_overlays 3
[ -z "$NODE_CONFIG_DIR_CANON" ] || die_test "unset overlay retained a directory"
[ "${#NODE_CONFIG_URIS[@]}" -eq 0 ] || die_test "unset overlay retained node URIs"
[ -z "$(node_config_location 0)" ] || die_test "unset overlay emitted a config location"

VALID="$WORK/private config"
mkdir -m 700 "$VALID"
for i in 0 1; do
  printf '%s\n' \
    'config_ordinal=275' \
    'quarkus.http.port=1' \
    'yano.storage.path=/must-not-win' \
    "connector.password=do-not-expose-$i" > "$VALID/node$i.properties"
  chmod 600 "$VALID/node$i.properties"
done

NODE_CONFIG_DIR="$VALID"
validate_node_config_overlays 2 > "$WORK/valid.log" 2>&1
[ ! -s "$WORK/valid.log" ] || die_test "successful validation printed config information"
EXPECTED_URI="$(python3 - "$VALID/node0.properties" <<'PY'
import pathlib
import sys

print(pathlib.Path(sys.argv[1]).resolve().as_uri())
PY
)"
[ "$(node_config_location 0)" = "$EXPECTED_URI" ] \
  || die_test "node 0 did not receive exactly one canonical config location"
[[ "$(node_config_location 0)" != *do-not-expose* ]] \
  || die_test "property content leaked into the config location"

# Exercise both launch paths with capture-only stand-ins (no Yano process,
# sockets, or cluster state). Each process must receive exactly the URI arg.
FAKE_BIN="$WORK/fake-bin"
mkdir -m 700 "$FAKE_BIN"
printf '%s\n' '#!/bin/sh' \
  ': > "$CAPTURE_FILE"' \
  'printf "%s" "${QUARKUS_CONFIG_LOCATIONS-}" > "$CAPTURE_LOCATION_FILE"' \
  'for arg in "$@"; do printf "%s\\n" "$arg" >> "$CAPTURE_FILE"; done' \
  'sleep 1' \
  > "$FAKE_BIN/capture"
chmod 700 "$FAKE_BIN/capture"
cp "$FAKE_BIN/capture" "$FAKE_BIN/java"
install -m 600 /dev/null "$WORK/yano.jar"

CLUSTER_DIR="$WORK/launch"
YANO_HOME="$WORK"
PROFILE="overlay-test"
NETWORK="preprod"
TRANSPORT=""
HTTP_BASE=19070
SERVER_BASE=19337
chain_props() { printf '%s\n' '-Doverlay-test=true'; }

assert_captured_overlay() {
  local capture="$1" location_capture="$2" expected="$3"
  [ "$(cat "$location_capture")" = "$expected" ] \
    || die_test "launch did not receive the exact config-location environment value"
  ! grep -q -- '^-Dquarkus.config.locations=' "$capture" \
    || die_test "launch selected the overlay at unsafe system-property precedence"
  ! grep -q 'do-not-expose' "$capture" \
    || die_test "launch arguments exposed property contents"
}

assert_launcher_props_present() {
  local capture="$1"
  grep -Fqx -- '-Dquarkus.http.port=19070' "$capture" \
    || die_test "capture omitted the launcher HTTP property"
  grep -Fqx -- "-Dyano.storage.path=$CLUSTER_DIR/node0/chainstate" "$capture" \
    || die_test "capture omitted the launcher storage property"
}

CAPTURE_FILE="$WORK/native.args"; CAPTURE_LOCATION_FILE="$WORK/native.location"
export CAPTURE_FILE CAPTURE_LOCATION_FILE
RUNTIME="native"; NATIVE="$FAKE_BIN/capture"
launch_node 2 0
wait "$(cat "$(pid_file 0)")" || die_test "capture-only native launch failed"
stop_managed_node_confirmed 0 \
  || die_test "capture-only native launch records were not safely retired"
assert_captured_overlay "$CAPTURE_FILE" "$CAPTURE_LOCATION_FILE" "$EXPECTED_URI"
assert_launcher_props_present "$CAPTURE_FILE"
[ ! -s "$(log_file 0)" ] || die_test "native launch log exposed config information"

CAPTURE_FILE="$WORK/jar.args"; CAPTURE_LOCATION_FILE="$WORK/jar.location"
export CAPTURE_FILE CAPTURE_LOCATION_FILE
RUNTIME="jar"; JAR="$WORK/yano.jar"; PATH="$FAKE_BIN:$PATH"
launch_node 2 0
wait "$(cat "$(pid_file 0)")" || die_test "capture-only jar launch failed"
stop_managed_node_confirmed 0 \
  || die_test "capture-only jar launch records were not safely retired"
assert_captured_overlay "$CAPTURE_FILE" "$CAPTURE_LOCATION_FILE" "$EXPECTED_URI"
assert_launcher_props_present "$CAPTURE_FILE"
[ ! -s "$(log_file 0)" ] || die_test "jar launch log exposed config information"

CAPTURE_FILE="$WORK/unset.args"; CAPTURE_LOCATION_FILE="$WORK/unset.location"
export CAPTURE_FILE CAPTURE_LOCATION_FILE
RUNTIME="native"; NODE_CONFIG_DIR_CANON=""
launch_node 2 1
wait "$(cat "$(pid_file 1)")" || die_test "capture-only unset launch failed"
stop_managed_node_confirmed 1 \
  || die_test "capture-only unset launch records were not safely retired"
! grep -q -- '^-Dquarkus.config.locations=' "$CAPTURE_FILE" \
  || die_test "unset overlay changed launch arguments"
[ ! -s "$CAPTURE_LOCATION_FILE" ] \
  || die_test "unset overlay changed the child config-location environment"
EXPECTED_UNSET="$WORK/unset.expected"
printf '%s\n' \
  '-Dquarkus.profile=overlay-test' \
  '-Dquarkus.http.host=127.0.0.1' \
  '-Dquarkus.http.port=19071' \
  '-Dyano.server.port=19338' \
  "-Dyano.storage.path=$CLUSTER_DIR/node1/chainstate" \
  '-Dyano.relay.connection.source-port-reuse=false' \
  '-Dyano.relay.connection.max-connections-per-ip=500' \
  '-Doverlay-test=true' > "$EXPECTED_UNSET"
cmp -s "$EXPECTED_UNSET" "$CAPTURE_FILE" \
  || { diff -u "$EXPECTED_UNSET" "$CAPTURE_FILE" >&2; die_test "unset launch argv changed"; }

# Restore the valid overlay state for the rejection cases below.
NODE_CONFIG_DIR="$VALID"
validate_node_config_overlays 2

# Preflight state is not trusted indefinitely. A selected file is revalidated
# immediately before launch, before any node directory or process is created.
cp -p "$VALID/node0.properties" "$WORK/node0.saved"
printf 'config_ordinal=500\n' > "$VALID/node0.properties"
if ( CLUSTER_DIR="$WORK/revalidate-content"; launch_node 2 0 ) \
    > "$WORK/revalidate-content.log" 2>&1; then
  die_test "launch accepted node config contents changed after preflight"
fi
grep -q 'reserved configuration-control key' "$WORK/revalidate-content.log" \
  || die_test "launch-time content revalidation produced the wrong diagnostic"
[ ! -e "$WORK/revalidate-content" ] \
  || die_test "launch-time content validation occurred after state creation"
cp -p "$WORK/node0.saved" "$VALID/node0.properties"

chmod 640 "$VALID/node0.properties"
if ( CLUSTER_DIR="$WORK/revalidate-mode"; launch_node 2 0 ) \
    > "$WORK/revalidate-mode.log" 2>&1; then
  die_test "launch accepted node config permissions changed after preflight"
fi
grep -q 'must not grant group or world permissions' "$WORK/revalidate-mode.log" \
  || die_test "launch-time mode revalidation produced the wrong diagnostic"
[ ! -e "$WORK/revalidate-mode" ] \
  || die_test "launch-time mode validation occurred after state creation"
chmod 600 "$VALID/node0.properties"

mv "$VALID/node0.properties" "$WORK/node0.real"
ln -s "$WORK/node0.real" "$VALID/node0.properties"
if ( CLUSTER_DIR="$WORK/revalidate-identity"; launch_node 2 0 ) \
    > "$WORK/revalidate-identity.log" 2>&1; then
  die_test "launch accepted node config identity changed after preflight"
fi
grep -q 'must not be a symbolic link' "$WORK/revalidate-identity.log" \
  || die_test "launch-time identity revalidation produced the wrong diagnostic"
[ ! -e "$WORK/revalidate-identity" ] \
  || die_test "launch-time identity validation occurred after state creation"
rm "$VALID/node0.properties"
mv "$WORK/node0.real" "$VALID/node0.properties"

# A required file must exist.
MISSING="$WORK/missing"
mkdir -m 700 "$MISSING"
printf 'config_ordinal=275\noverlay.test=true\n' > "$MISSING/node0.properties"
chmod 600 "$MISSING/node0.properties"
if ( NODE_CONFIG_DIR="$MISSING"; validate_node_config_overlays 2 ) \
    > "$WORK/missing.log" 2>&1; then
  die_test "missing node config was accepted"
fi
grep -q 'node 1 config is missing or not a regular file' "$WORK/missing.log" \
  || die_test "missing node config produced the wrong diagnostic"
! grep -qF "$MISSING" "$WORK/missing.log" \
  || die_test "missing-file diagnostic exposed the configured path"

# Symlinks cannot escape the canonical directory.
SYMLINK="$WORK/symlink"
mkdir -m 700 "$SYMLINK"
install -m 600 /dev/null "$WORK/outside.properties"
ln -s "$WORK/outside.properties" "$SYMLINK/node0.properties"
if ( NODE_CONFIG_DIR="$SYMLINK"; validate_node_config_overlays 1 ) \
    > "$WORK/symlink.log" 2>&1; then
  die_test "symlink node config was accepted"
fi
grep -q 'node 0 config must not be a symbolic link' "$WORK/symlink.log" \
  || die_test "symlink config produced the wrong diagnostic"
! grep -qF "$SYMLINK" "$WORK/symlink.log" \
  || die_test "symlink diagnostic exposed the configured path"

# Group/world access is forbidden because overlays may contain credentials.
INSECURE="$WORK/insecure"
mkdir -m 700 "$INSECURE"
install -m 640 /dev/null "$INSECURE/node0.properties"
if ( NODE_CONFIG_DIR="$INSECURE"; validate_node_config_overlays 1 ) \
    > "$WORK/insecure.log" 2>&1; then
  die_test "group-readable node config was accepted"
fi
grep -q 'must not grant group or world permissions' "$WORK/insecure.log" \
  || die_test "insecure mode produced the wrong diagnostic"

# A selected file has one fixed source ordinal. Missing, duplicate, or changed
# ordinals and nested config locations could beat launcher-owned values.
RESERVED="$WORK/reserved"
mkdir -m 700 "$RESERVED"
printf 'config_ordinal=500\n' > "$RESERVED/node0.properties"
chmod 600 "$RESERVED/node0.properties"
if ( NODE_CONFIG_DIR="$RESERVED"; validate_node_config_overlays 1 ) \
    > "$WORK/reserved.log" 2>&1; then
  die_test "config_ordinal was accepted"
fi
grep -q 'reserved configuration-control key' "$WORK/reserved.log" \
  || die_test "config_ordinal produced the wrong diagnostic"

printf 'CONFIG_ORDINAL=275\n' > "$RESERVED/node0.properties"
if ( NODE_CONFIG_DIR="$RESERVED"; validate_node_config_overlays 1 ) \
    > "$WORK/case-ordinal.log" 2>&1; then
  die_test "non-literal config_ordinal spelling was accepted"
fi
grep -q 'reserved configuration-control key' "$WORK/case-ordinal.log" \
  || die_test "non-literal config_ordinal produced the wrong diagnostic"

printf 'config_ordinal=275\n%%devnet.config_ordinal=500\n' \
  > "$RESERVED/node0.properties"
if ( NODE_CONFIG_DIR="$RESERVED"; validate_node_config_overlays 1 ) \
    > "$WORK/profiled-ordinal.log" 2>&1; then
  die_test "profiled config_ordinal was accepted"
fi
grep -q 'bounded UTF-8 key=value lines' "$WORK/profiled-ordinal.log" \
  || die_test "profiled config_ordinal produced the wrong diagnostic"

printf 'overlay.test=true\n' > "$RESERVED/node0.properties"
if ( NODE_CONFIG_DIR="$RESERVED"; validate_node_config_overlays 1 ) \
    > "$WORK/missing-ordinal.log" 2>&1; then
  die_test "missing fixed config_ordinal was accepted"
fi
grep -q 'exactly one literal config_ordinal=275' "$WORK/missing-ordinal.log" \
  || die_test "missing config_ordinal produced the wrong diagnostic"

printf 'config_ordinal=275\nconfig_ordinal=275\n' > "$RESERVED/node0.properties"
if ( NODE_CONFIG_DIR="$RESERVED"; validate_node_config_overlays 1 ) \
    > "$WORK/duplicate-ordinal.log" 2>&1; then
  die_test "duplicate fixed config_ordinal was accepted"
fi
grep -q 'exactly one literal config_ordinal=275' "$WORK/duplicate-ordinal.log" \
  || die_test "duplicate config_ordinal produced the wrong diagnostic"

printf 'config_ordinal=275\nquarkus.config.locations=file:///tmp/escape.properties\n' \
  > "$RESERVED/node0.properties"
if ( NODE_CONFIG_DIR="$RESERVED"; validate_node_config_overlays 1 ) \
    > "$WORK/location-selector.log" 2>&1; then
  die_test "nested config-location selector was accepted"
fi
grep -q 'reserved configuration-control key' "$WORK/location-selector.log" \
  || die_test "nested location selector produced the wrong diagnostic"

# Escaped/continued keys are outside the intentionally simple grammar, so a
# reserved key cannot be smuggled through java.util.Properties decoding.
printf 'config_ordinal=275\nconfig\\u005fordinal=500\n' > "$RESERVED/node0.properties"
if ( NODE_CONFIG_DIR="$RESERVED"; validate_node_config_overlays 1 ) \
    > "$WORK/escaped-key.log" 2>&1; then
  die_test "escaped properties key was accepted"
fi
grep -q 'bounded UTF-8 key=value lines' "$WORK/escaped-key.log" \
  || die_test "escaped key produced the wrong diagnostic"

WRITABLE_DIR="$WORK/writable-dir"
mkdir -m 770 "$WRITABLE_DIR"
install -m 600 /dev/null "$WRITABLE_DIR/node0.properties"
if ( NODE_CONFIG_DIR="$WRITABLE_DIR"; validate_node_config_overlays 1 ) \
    > "$WORK/writable-dir.log" 2>&1; then
  die_test "group-writable config directory was accepted"
fi
grep -q 'directory must not be group or world writable' "$WORK/writable-dir.log" \
  || die_test "writable config directory produced the wrong diagnostic"

# A private leaf is still replaceable when a canonical parent is writable.
UNSAFE_PARENT="$WORK/unsafe-ancestor"
mkdir -m 770 "$UNSAFE_PARENT"
mkdir -m 700 "$UNSAFE_PARENT/private"
printf 'config_ordinal=275\n' > "$UNSAFE_PARENT/private/node0.properties"
chmod 600 "$UNSAFE_PARENT/private/node0.properties"
if ( NODE_CONFIG_DIR="$UNSAFE_PARENT/private"; validate_node_config_overlays 1 ) \
    > "$WORK/unsafe-ancestor.log" 2>&1; then
  die_test "non-sticky writable config ancestor was accepted"
fi
grep -q 'unsafe or untrusted canonical ancestor' "$WORK/unsafe-ancestor.log" \
  || die_test "unsafe config ancestor produced the wrong diagnostic"
! grep -qF "$UNSAFE_PARENT" "$WORK/unsafe-ancestor.log" \
  || die_test "unsafe-ancestor diagnostic exposed the configured path"

# Sticky temporary roots retain ownership of entries, so /tmp-style layouts
# remain supported when the private directory and file are launcher-owned.
STICKY_PARENT="$WORK/sticky-ancestor"
mkdir -m 1777 "$STICKY_PARENT"
mkdir -m 700 "$STICKY_PARENT/private"
printf 'config_ordinal=275\n' > "$STICKY_PARENT/private/node0.properties"
chmod 600 "$STICKY_PARENT/private/node0.properties"
( NODE_CONFIG_DIR="$STICKY_PARENT/private"; validate_node_config_overlays 1 ) \
  > "$WORK/sticky-ancestor.log" 2>&1 \
  || die_test "sticky writable config ancestor was rejected"
[ ! -s "$WORK/sticky-ancestor.log" ] \
  || die_test "successful sticky-ancestor validation printed information"

# Ownership-negative fixtures require privilege to create portably. Exercise
# them when the focused test itself runs as root; normal-user CI covers all
# positive ownership paths above.
if [ "$(id -u)" -eq 0 ]; then
  OTHER_UID=65534
  OWNER_DIR="$WORK/owner-dir"
  mkdir -m 700 "$OWNER_DIR"
  printf 'config_ordinal=275\n' > "$OWNER_DIR/node0.properties"
  chmod 600 "$OWNER_DIR/node0.properties"
  if chown "$OTHER_UID" "$OWNER_DIR" 2>/dev/null; then
    if ( NODE_CONFIG_DIR="$OWNER_DIR"; validate_node_config_overlays 1 ) \
        > "$WORK/owner-dir.log" 2>&1; then
      die_test "config directory owned by another uid was accepted"
    fi
    grep -q 'directory must be owned by the launcher user' "$WORK/owner-dir.log" \
      || die_test "directory-owner check produced the wrong diagnostic"
  fi

  OWNER_FILE="$WORK/owner-file"
  mkdir -m 700 "$OWNER_FILE"
  printf 'config_ordinal=275\n' > "$OWNER_FILE/node0.properties"
  chmod 600 "$OWNER_FILE/node0.properties"
  if chown "$OTHER_UID" "$OWNER_FILE/node0.properties" 2>/dev/null; then
    if ( NODE_CONFIG_DIR="$OWNER_FILE"; validate_node_config_overlays 1 ) \
        > "$WORK/owner-file.log" 2>&1; then
      die_test "node config owned by another uid was accepted"
    fi
    grep -q 'node 0 config must be owned by the launcher user' "$WORK/owner-file.log" \
      || die_test "file-owner check produced the wrong diagnostic"
  fi
fi

# The node<N> set is exact; stale and ambiguous names fail before launch.
install -m 600 /dev/null "$VALID/node2.properties"
if ( NODE_CONFIG_DIR="$VALID"; validate_node_config_overlays 2 ) \
    > "$WORK/extra.log" 2>&1; then
  die_test "extra node config was accepted"
fi
grep -q 'unexpected per-node config for node 2' "$WORK/extra.log" \
  || die_test "extra node config produced the wrong diagnostic"
rm "$VALID/node2.properties"

install -m 600 /dev/null "$VALID/node01.properties"
if ( NODE_CONFIG_DIR="$VALID"; validate_node_config_overlays 2 ) \
    > "$WORK/name.log" 2>&1; then
  die_test "ambiguous node config name was accepted"
fi
grep -q 'unexpected per-node config filename' "$WORK/name.log" \
  || die_test "ambiguous node config name produced the wrong diagnostic"

# cmd_start must reject overlays before it creates the cluster data directory.
PREFLIGHT_HOME="$WORK/preflight-home"
PREFLIGHT_DATA="$WORK/must-not-be-created"
mkdir -m 700 -p "$PREFLIGHT_HOME/config"
printf 'yano.app-chain.chains[0].chain-id: "preflight"\n' \
  > "$PREFLIGHT_HOME/config/application-appchain.yml"
if ( YANO_HOME="$PREFLIGHT_HOME"
     CONFIG_FILE="$PREFLIGHT_HOME/config/application-appchain.yml"
     YANO_JAR="$WORK/yano.jar"; export YANO_JAR
     RUNTIME="jar"
     NODE_CONFIG_DIR="$WORK/does-not-exist"
     CLUSTER_DIR="$PREFLIGHT_DATA"
     cmd_start 1 ) > "$WORK/preflight.log" 2>&1; then
  die_test "cmd_start accepted a missing overlay directory"
fi
grep -q 'per-node config directory does not exist or is not a directory' "$WORK/preflight.log" \
  || die_test "cmd_start preflight produced the wrong diagnostic"
[ ! -e "$PREFLIGHT_DATA" ] \
  || die_test "overlay validation occurred after cluster data creation"

for output in "$WORK"/*.log "$WORK"/*.args "$CLUSTER_DIR"/node*/node.log; do
  [ -f "$output" ] || continue
  ! grep -q 'do-not-expose' "$output" \
    || die_test "a validation diagnostic, log, or captured argv exposed a secret"
done

printf 'PASS: per-node config overlays are canonical, private, exact, and opt-in\n'
