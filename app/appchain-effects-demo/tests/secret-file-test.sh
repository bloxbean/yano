#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
DEMO_DIR="$(cd "$SCRIPT_DIR/.." && pwd -P)"
TOOL="$DEMO_DIR/tools/secret_file.py"
TEST_ROOT="$(mktemp -d "$DEMO_DIR/.secret-file-test.XXXXXX")"

cleanup() {
  rm -rf "$TEST_ROOT"
}
trap cleanup EXIT HUP INT TERM

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

mode() {
  if stat -c '%a' "$1" >/dev/null 2>&1; then stat -c '%a' "$1"; else stat -f '%Lp' "$1"; fi
}

links() {
  if stat -c '%h' "$1" >/dev/null 2>&1; then stat -c '%h' "$1"; else stat -f '%l' "$1"; fi
}

temporary_name() {
  python3 - "$TOOL" "$1" <<'PY'
import importlib.util
import sys

spec = importlib.util.spec_from_file_location("secret_file", sys.argv[1])
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
print(module.temporary_name(sys.argv[2]))
PY
}

expect_failure() {
  local label="$1"
  shift
  if "$@" >"$TEST_ROOT/failure.out" 2>"$TEST_ROOT/failure.err"; then
    fail "$label unexpectedly succeeded"
  fi
  [ ! -s "$TEST_ROOT/failure.out" ] || fail "$label exposed data on stdout"
}

install_value() {
  local path="$1" value="$2"
  printf '%s\n' "$value" | python3 "$TOOL" --path "$path"
}

SECRETS="$TEST_ROOT/secrets"
mkdir -m 0700 "$SECRETS"

# Concurrent first-use installers must converge on one complete winner. Every
# caller succeeds, but no caller can truncate or replace the published file.
pids=()
i=0
while [ "$i" -lt 8 ]; do
  (
    install_value "$SECRETS/concurrent" "candidate-$i" \
      >"$TEST_ROOT/concurrent-$i.out" 2>"$TEST_ROOT/concurrent-$i.err"
  ) &
  pids+=("$!")
  i=$((i + 1))
done
for pid in "${pids[@]}"; do
  wait "$pid" || fail "concurrent secret installer failed"
done
winner="$(tr -d '\r\n' < "$SECRETS/concurrent")"
[[ "$winner" =~ ^candidate-[0-7]$ ]] || fail "concurrent winner is incomplete or unexpected"
[ "$(mode "$SECRETS/concurrent")" = 600 ] || fail "published secret is not 0600"
[ "$(links "$SECRETS/concurrent")" = 1 ] || fail "published secret retained a hard link"
i=0
while [ "$i" -lt 8 ]; do
  [ ! -s "$TEST_ROOT/concurrent-$i.out" ] || fail "installer exposed a secret on stdout"
  [ ! -s "$TEST_ROOT/concurrent-$i.err" ] || fail "concurrent installer emitted an error"
  i=$((i + 1))
done
install_value "$SECRETS/concurrent" replacement
[ "$(tr -d '\r\n' < "$SECRETS/concurrent")" = "$winner" ] \
  || fail "an existing secret was replaced"
[ -z "$(find "$SECRETS" -maxdepth 1 -name '.yano-secret-tmp-*' -print -quit)" ] \
  || fail "normal publication left a temporary artifact"

# A crash after link(2), but before unlink(2), is recoverable only when the
# exact deterministic temporary name is the same inode as the target.
printf 'linked-recovery\n' > "$SECRETS/linked"
chmod 600 "$SECRETS/linked"
linked_temporary="$SECRETS/$(temporary_name linked)"
ln "$SECRETS/linked" "$linked_temporary"
[ "$(links "$SECRETS/linked")" = 2 ] || fail "linked recovery fixture is invalid"
install_value "$SECRETS/linked" ignored-candidate
[ "$(tr -d '\r\n' < "$SECRETS/linked")" = linked-recovery ] \
  || fail "linked temporary recovery changed the winner"
[ "$(links "$SECRETS/linked")" = 1 ] || fail "linked recovery retained a hard link"
[ ! -e "$linked_temporary" ] || fail "linked recovery retained its exact temporary"

# A completed pre-publication temporary left by a killed installer is also
# recovered under the directory lock, preserving first-creator-wins semantics.
unpublished_temporary="$SECRETS/$(temporary_name unpublished)"
printf 'unpublished-recovery\n' > "$unpublished_temporary"
chmod 600 "$unpublished_temporary"
install_value "$SECRETS/unpublished" later-candidate
[ "$(tr -d '\r\n' < "$SECRETS/unpublished")" = unpublished-recovery ] \
  || fail "completed unpublished temporary was not recovered"
[ "$(links "$SECRETS/unpublished")" = 1 ] \
  || fail "unpublished recovery retained a hard link"
[ ! -e "$unpublished_temporary" ] || fail "unpublished recovery retained its temporary"

# A kill in the middle of a write leaves an invalid deterministic temporary.
# It is preserved and fails closed for operator diagnosis; it is never promoted
# or silently deleted as though it were a completed secret.
partial_temporary="$SECRETS/$(temporary_name partial)"
printf 'partial-write' > "$partial_temporary"
chmod 600 "$partial_temporary"
expect_failure "partial unpublished temporary" install_value "$SECRETS/partial" later-candidate
[ ! -e "$SECRETS/partial" ] || fail "partial temporary was published"
[ "$(cat "$partial_temporary")" = partial-write ] \
  || fail "partial temporary was not preserved for diagnosis"

# A hard link under any other name is not the helper's artifact and must fail
# closed without removing either path.
printf 'unknown-link\n' > "$SECRETS/unknown"
chmod 600 "$SECRETS/unknown"
ln "$SECRETS/unknown" "$SECRETS/not-the-helper-temporary"
expect_failure "unrecognized hard link" install_value "$SECRETS/unknown" replacement
[ -f "$SECRETS/unknown" ] && [ -f "$SECRETS/not-the-helper-temporary" ] \
  || fail "unrecognized hard-link rejection removed a path"

# Even the reserved name cannot be removed unless it is the target's exact
# inode. This prevents cleanup from deleting an unrelated file.
printf 'reserved-target\n' > "$SECRETS/reserved"
chmod 600 "$SECRETS/reserved"
reserved_temporary="$SECRETS/$(temporary_name reserved)"
printf 'unrelated\n' > "$reserved_temporary"
chmod 600 "$reserved_temporary"
expect_failure "unrelated reserved temporary" install_value "$SECRETS/reserved" replacement
[ "$(tr -d '\r\n' < "$reserved_temporary")" = unrelated ] \
  || fail "unrelated reserved temporary was modified"

printf 'unsafe-mode\n' > "$SECRETS/unsafe-mode"
chmod 0644 "$SECRETS/unsafe-mode"
expect_failure "group-readable secret" install_value "$SECRETS/unsafe-mode" replacement

printf 'hard-linked\n' > "$SECRETS/hard-linked"
chmod 0600 "$SECRETS/hard-linked"
ln "$SECRETS/hard-linked" "$TEST_ROOT/hard-linked-copy"
expect_failure "hard-linked secret" install_value "$SECRETS/hard-linked" replacement

printf 'symlink-target\n' > "$TEST_ROOT/symlink-target"
chmod 0600 "$TEST_ROOT/symlink-target"
ln -s "$TEST_ROOT/symlink-target" "$SECRETS/symlink-secret"
expect_failure "symlink secret" install_value "$SECRETS/symlink-secret" replacement

PERMISSIVE="$TEST_ROOT/permissive"
mkdir -m 0755 "$PERMISSIVE"
expect_failure "permissive parent" install_value "$PERMISSIVE/secret" candidate
[ ! -e "$PERMISSIVE/secret" ] || fail "permissive directory received a secret"

REAL_PARENT="$TEST_ROOT/real-parent"
mkdir -m 0700 "$REAL_PARENT"
ln -s "$REAL_PARENT" "$TEST_ROOT/symlink-parent"
expect_failure "symlink parent" install_value "$TEST_ROOT/symlink-parent/secret" candidate
[ ! -e "$REAL_PARENT/secret" ] || fail "symlink parent received a secret"

expect_failure "empty candidate" bash -c "printf '' | python3 '$TOOL' --path '$SECRETS/empty'"
expect_failure "multiline candidate" bash -c \
  "printf 'first\\nsecond\\n' | python3 '$TOOL' --path '$SECRETS/multiline'"
expect_failure "space-bearing candidate" bash -c \
  "printf 'not safe\\n' | python3 '$TOOL' --path '$SECRETS/spaces'"

if [ "$(id -u)" -eq 0 ]; then
  printf 'wrong-owner\n' > "$SECRETS/wrong-owner"
  chmod 0600 "$SECRETS/wrong-owner"
  chown 1 "$SECRETS/wrong-owner"
  expect_failure "foreign-owned secret" install_value "$SECRETS/wrong-owner" replacement
fi

echo "secret-file-test: PASS"
