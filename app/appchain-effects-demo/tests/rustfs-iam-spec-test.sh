#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
TOOL="$SCRIPT_DIR/../tools/rustfs_iam_spec.py"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/yano-rustfs-iam-test.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
mode() { stat -c '%a' "$1" 2>/dev/null || stat -f '%Lp' "$1"; }
secret() { (umask 077; printf '%s\n' "$2" > "$1"); }

mkdir -m 0700 "$TMP/secrets"
secret "$TMP/secrets/bootstrap-access" yano-bootstrap-access
secret "$TMP/secrets/bootstrap-secret" yano-bootstrap-secret
secret "$TMP/secrets/runner-access" yano-runner-access
secret "$TMP/secrets/runner-secret" yano-runner-secret
secret "$TMP/secrets/executor-access" yano-executor-access
secret "$TMP/secrets/executor-secret" yano-executor-secret

generate() {
  python3 "$TOOL" --output "$TMP/secrets/rustfs-iam.json" \
    --root-access "$TMP/secrets/bootstrap-access" \
    --runner-access "$TMP/secrets/runner-access" \
    --executor-access "$TMP/secrets/executor-access"
}

digest="$(generate)"
[[ "$digest" =~ ^[0-9a-f]{64}$ ]] || fail "generator did not return one digest"
[ "$(mode "$TMP/secrets/rustfs-iam.json")" = 600 ] || fail "generated specification is not 0600"
[ "$digest" = "$(shasum -a 256 "$TMP/secrets/rustfs-iam.json" | awk '{print $1}')" ] \
  || fail "reported digest does not bind exact specification bytes"
[ "$digest" = "$(generate)" ] || fail "idempotent generation changed the specification"

jq -e '
  .schemaVersion == 1
  and .provider == "rustfs"
  and [.roles[].name] == ["bootstrap", "runner", "executor"]
  and [.roles[].principalType] == ["built-in-root", "managed-user", "managed-user"]
  and (.roles[0] | has("policyName") | not)
  and [.roles[1].policyName, .roles[2].policyName]
      == ["YanoS3RunnerV1", "YanoS3ExecutorV1"]
  and [.policies[].name]
      == ["YanoS3RunnerV1", "YanoS3ExecutorV1"]
  and all(.policies[]; (.content | fromjson).Version == "2012-10-17")
  and all(.roles[]; (has("secretKey") | not))
' "$TMP/secrets/rustfs-iam.json" >/dev/null || fail "RustFS role/policy schema is malformed"

for value in yano-bootstrap-secret yano-runner-secret yano-executor-secret; do
  ! grep -Fq "$value" "$TMP/secrets/rustfs-iam.json" \
    || fail "IAM specification contains a secret key"
done

cp "$TMP/secrets/rustfs-iam.json" "$TMP/original.json"
printf 'tampered\n' > "$TMP/secrets/rustfs-iam.json"
chmod 600 "$TMP/secrets/rustfs-iam.json"
if generate >"$TMP/tamper.out" 2>"$TMP/tamper.err"; then
  fail "generator accepted an existing mismatched specification"
fi
for value in yano-bootstrap-secret yano-runner-secret yano-executor-secret; do
  ! grep -R -Fq "$value" "$TMP/tamper.out" "$TMP/tamper.err" \
    || fail "failure output exposed a secret"
done

mv "$TMP/original.json" "$TMP/secrets/rustfs-iam.json"
chmod 600 "$TMP/secrets/rustfs-iam.json"
secret "$TMP/secrets/executor-access" yano-runner-access
if generate >/dev/null 2>&1; then
  fail "generator accepted duplicate access keys"
fi

printf 'PASS: private immutable RustFS IAM role/policy specification\n'
