#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
DEMO_DIR="$(cd "$SCRIPT_DIR/.." && pwd -P)"
TOOL="$DEMO_DIR/tools/ed25519_keys.py"
TEST_ROOT="$(mktemp -d "$DEMO_DIR/.key-material-test.XXXXXX")"

cleanup() {
  rm -rf "$TEST_ROOT"
}
trap cleanup EXIT HUP INT TERM

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

expect_failure() {
  label="$1"
  shift
  if "$@" >"$TEST_ROOT/failure.out" 2>"$TEST_ROOT/failure.err"; then
    fail "$label unexpectedly succeeded"
  fi
  [ ! -s "$TEST_ROOT/failure.out" ] || fail "$label wrote key material to stdout on failure"
}

run_keys() {
  directory="$1"
  mode="$2"
  python3 "$TOOL" --directory "$directory" --mode "$mode" --count 3
}

run_existing_keys() {
  directory="$1"
  mode="$2"
  python3 "$TOOL" --directory "$directory" --mode "$mode" --count 3 --existing-only
}

expected_devnet='8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c,8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394,ed4928c628d1c2c6eae90338905995612959273a5c63f93636c14614ac8737d1'

# RFC 8032, section 7.1, test vector 1: empty-message Ed25519 key derivation.
python3 - "$TOOL" <<'PY'
import importlib.util
import sys

spec = importlib.util.spec_from_file_location("ed25519_keys", sys.argv[1])
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
seed = bytes.fromhex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
expected = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
if module.private_to_public(seed).hex() != expected:
    raise SystemExit("RFC 8032 Ed25519 public-key derivation mismatch")
PY

deterministic_dir="$TEST_ROOT/deterministic"
deterministic_output="$(run_keys "$deterministic_dir" deterministic)"
[ "$deterministic_output" = "$expected_devnet" ] || fail "deterministic profile changed"
[ "$(run_keys "$deterministic_dir" deterministic)" = "$deterministic_output" ] \
  || fail "deterministic keys did not persist"

python3 - "$deterministic_dir" <<'PY'
import os
import stat
import sys

directory = sys.argv[1]
if stat.S_IMODE(os.stat(directory, follow_symlinks=False).st_mode) != 0o700:
    raise SystemExit("key directory is not mode 0700")
expected = {
    ".member-keys.lock",
    ".member-keys.mode",
    "node0.seed", "node0.public",
    "node1.seed", "node1.public",
    "node2.seed", "node2.public",
}
if set(os.listdir(directory)) != expected:
    raise SystemExit("key directory inventory differs from the closed schema")
for name in expected:
    path = os.path.join(directory, name)
    info = os.stat(path, follow_symlinks=False)
    if not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600:
        raise SystemExit("key material is not an owner-only regular file")
    if info.st_uid != os.getuid() or info.st_nlink != 1:
        raise SystemExit("key material has unsafe ownership or hard links")
PY

generated_dir="$TEST_ROOT/generated"
generated_first="$(run_keys "$generated_dir" generated)"
generated_second="$(run_keys "$generated_dir" generated)"
[ "$generated_first" = "$generated_second" ] || fail "generated keys were not retained"
[ "$generated_first" != "$expected_devnet" ] || fail "generated profile reused deterministic identities"
[ "$(run_existing_keys "$generated_dir" generated)" = "$generated_first" ] \
  || fail "existing-only key reload changed the persisted identity"

missing_existing_dir="$TEST_ROOT/missing-existing"
expect_failure "existing-only missing key set" run_existing_keys "$missing_existing_dir" generated
[ ! -e "$missing_existing_dir" ] \
  || fail "existing-only key reload created a missing key directory"
empty_existing_dir="$TEST_ROOT/empty-existing"
mkdir -m 0700 "$empty_existing_dir"
expect_failure "existing-only empty key directory" run_existing_keys "$empty_existing_dir" generated
[ -z "$(find "$empty_existing_dir" -mindepth 1 -maxdepth 1 -print -quit)" ] \
  || fail "existing-only key reload mutated an empty key directory"

expect_failure "generated-to-deterministic mode switch" run_keys "$generated_dir" deterministic
expect_failure "deterministic-to-generated mode switch" run_keys "$deterministic_dir" generated
expect_failure "unsupported member count" \
  python3 "$TOOL" --directory "$TEST_ROOT/wrong-count" --mode generated --count 2

incomplete_dir="$TEST_ROOT/incomplete"
run_keys "$incomplete_dir" generated >/dev/null
rm "$incomplete_dir/node2.public"
expect_failure "incomplete key set" run_keys "$incomplete_dir" generated

mismatch_dir="$TEST_ROOT/mismatch"
run_keys "$mismatch_dir" deterministic >/dev/null
python3 - "$mismatch_dir/node0.public" <<'PY'
import os
import sys

descriptor = os.open(sys.argv[1], os.O_WRONLY | os.O_TRUNC)
try:
    os.write(descriptor, ("00" * 32 + "\n").encode("ascii"))
finally:
    os.close(descriptor)
PY
expect_failure "seed/public mismatch" run_keys "$mismatch_dir" deterministic

mode_dir="$TEST_ROOT/unsafe-mode"
run_keys "$mode_dir" generated >/dev/null
chmod 0644 "$mode_dir/node0.seed"
expect_failure "group-readable seed" run_keys "$mode_dir" generated

unsafe_directory="$TEST_ROOT/unsafe-directory"
mkdir -m 0755 "$unsafe_directory"
expect_failure "pre-existing permissive directory" run_keys "$unsafe_directory" generated
python3 - "$unsafe_directory" <<'PY'
import os
import stat
import sys

if stat.S_IMODE(os.stat(sys.argv[1], follow_symlinks=False).st_mode) != 0o755:
    raise SystemExit("helper silently repaired an insecure existing directory")
PY

symlink_target="$TEST_ROOT/symlink-target"
mkdir -m 0700 "$symlink_target"
ln -s "$symlink_target" "$TEST_ROOT/symlink-directory"
expect_failure "symlink final directory" run_keys "$TEST_ROOT/symlink-directory" generated

ancestor_target="$TEST_ROOT/ancestor-target"
mkdir -m 0700 "$ancestor_target"
ln -s "$ancestor_target" "$TEST_ROOT/symlink-ancestor"
expect_failure "symlink directory ancestor" run_keys "$TEST_ROOT/symlink-ancestor/keys" generated

symlink_file_dir="$TEST_ROOT/symlink-file"
run_keys "$symlink_file_dir" deterministic >/dev/null
cp "$symlink_file_dir/node0.seed" "$TEST_ROOT/external-seed"
chmod 0600 "$TEST_ROOT/external-seed"
rm "$symlink_file_dir/node0.seed"
ln -s "$TEST_ROOT/external-seed" "$symlink_file_dir/node0.seed"
expect_failure "symlink seed" run_keys "$symlink_file_dir" deterministic

hardlink_dir="$TEST_ROOT/hardlink"
run_keys "$hardlink_dir" generated >/dev/null
ln "$hardlink_dir/node0.seed" "$TEST_ROOT/second-seed-link"
expect_failure "hard-linked seed" run_keys "$hardlink_dir" generated

extra_dir="$TEST_ROOT/extra"
run_keys "$extra_dir" generated >/dev/null
touch "$extra_dir/unexpected"
chmod 0600 "$extra_dir/unexpected"
expect_failure "unexpected directory entry" run_keys "$extra_dir" generated

lock_mode_dir="$TEST_ROOT/lock-mode"
run_keys "$lock_mode_dir" generated >/dev/null
chmod 0644 "$lock_mode_dir/.member-keys.lock"
expect_failure "permissive lock file" run_keys "$lock_mode_dir" generated

lock_link_dir="$TEST_ROOT/lock-hardlink"
run_keys "$lock_link_dir" generated >/dev/null
ln "$lock_link_dir/.member-keys.lock" "$TEST_ROOT/second-lock-link"
expect_failure "hard-linked lock file" run_keys "$lock_link_dir" generated

oversize_dir="$TEST_ROOT/oversize"
run_keys "$oversize_dir" generated >/dev/null
python3 - "$oversize_dir/node1.seed" <<'PY'
import os
import sys

descriptor = os.open(sys.argv[1], os.O_WRONLY | os.O_APPEND)
try:
    os.write(descriptor, b"x")
finally:
    os.close(descriptor)
PY
expect_failure "oversized key file" run_keys "$oversize_dir" generated

if [ "$(id -u)" -eq 0 ]; then
  owner_dir="$TEST_ROOT/wrong-owner"
  run_keys "$owner_dir" generated >/dev/null
  chown 1 "$owner_dir/node0.seed"
  expect_failure "foreign-owned seed" run_keys "$owner_dir" generated
fi

concurrent_dir="$TEST_ROOT/concurrent"
pids=""
index=0
while [ "$index" -lt 8 ]; do
  python3 "$TOOL" --directory "$concurrent_dir" --mode generated --count 3 \
    >"$TEST_ROOT/concurrent-$index.out" 2>"$TEST_ROOT/concurrent-$index.err" &
  pids="$pids $!"
  index=$((index + 1))
done
for pid in $pids; do
  wait "$pid" || fail "concurrent key creator failed"
done
index=1
while [ "$index" -lt 8 ]; do
  cmp -s "$TEST_ROOT/concurrent-0.out" "$TEST_ROOT/concurrent-$index.out" \
    || fail "concurrent key creators observed different identities"
  [ ! -s "$TEST_ROOT/concurrent-$index.err" ] || fail "concurrent key creator emitted an error"
  index=$((index + 1))
done
[ "$(run_keys "$concurrent_dir" generated)" = "$(cat "$TEST_ROOT/concurrent-0.out")" ] \
  || fail "concurrently generated keys did not persist"

echo "key-material-test: PASS"
