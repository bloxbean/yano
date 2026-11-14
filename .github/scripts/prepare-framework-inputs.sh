#!/usr/bin/env bash
set -euo pipefail
: "${GITHUB_WORKSPACE:?GITHUB_WORKSPACE is required}"
: "${GITHUB_ENV:?GITHUB_ENV is required}"
fail() { printf '%s\n' "$1" >&2; exit 1; }
pins="$GITHUB_WORKSPACE/.github/ci-input-pins.json"
if [[ -z "${YANO_X_INPUTS_RUN_ID:-}${YANO_X_INPUTS_COMMIT:-}" && -f "$pins" ]]; then
  YANO_X_INPUTS_RUN_ID="$(jq -r '.framework.runId // empty' "$pins")"
  YANO_X_INPUTS_COMMIT="$(jq -r '.framework.commit // empty' "$pins")"
fi
# Released builds keep ordinary Maven resolution when no coordinated input is selected.
[[ -n "${YANO_X_INPUTS_RUN_ID:-}${YANO_X_INPUTS_COMMIT:-}" ]] || exit 0
[[ "${YANO_X_INPUTS_RUN_ID:-}" =~ ^[0-9]+$ ]] || fail 'Invalid framework staging run ID'
[[ "${YANO_X_INPUTS_COMMIT:-}" =~ ^[0-9a-f]{40}$ ]] || fail 'A full framework commit pin is required'
[[ -z "${ORG_GRADLE_PROJECT_appLedgerFrameworkRepository:-}" ]] || fail 'Do not mix pinned and independent repositories'
metadata="$(gh api "repos/bloxbean/yano-x/actions/runs/$YANO_X_INPUTS_RUN_ID")"
jq -e --arg commit "$YANO_X_INPUTS_COMMIT" '
  .head_sha == $commit and .status == "completed" and .conclusion == "success"
  and .event == "workflow_dispatch" and .path == ".github/workflows/build.yml"
  and .repository.full_name == "bloxbean/yano-x"
' <<< "$metadata" >/dev/null || fail 'Framework run provenance does not match the pin'
mkdir -p "$GITHUB_WORKSPACE/build/ci-inputs"
directory="$(mktemp -d "$GITHUB_WORKSPACE/build/ci-inputs/framework.XXXXXX")"
gh run download "$YANO_X_INPUTS_RUN_ID" --repo bloxbean/yano-x \
  --name "yano-x-framework-inputs-$YANO_X_INPUTS_COMMIT" --dir "$directory"
jq -e --arg commit "$YANO_X_INPUTS_COMMIT" '
  .schemaVersion == 1 and .repository == "bloxbean/yano-x" and .scope == "framework"
  and .commit == $commit and .mavenDirectory == "maven"
  and .foundation.repository == "bloxbean/yano"
  and (.foundation.commit | test("^[0-9a-f]{40}$"))
  and (.foundation.runId | test("^[0-9]+$"))
' "$directory/framework-inputs.json" >/dev/null || fail 'Invalid framework input manifest'
version="$(jq -r .version "$directory/framework-inputs.json")"
node_api_version="$(jq -r .nodeApiVersion "$directory/framework-inputs.json")"
host_api_version="$(jq -r .hostApiVersion "$directory/framework-inputs.json")"
for value in "$version" "$node_api_version" "$host_api_version"; do
  [[ "$value" =~ ^[0-9A-Za-z.-]+$ && "$value" != null && "$value" != *-SNAPSHOT ]] || fail 'Invalid immutable version'
done
[[ "$version" == *-"${YANO_X_INPUTS_COMMIT:0:9}" ]] || fail 'Framework version does not match its commit'
for pair in "appLedgerFrameworkVersion:$version" "hostApiVersion:$host_api_version"; do
  name="ORG_GRADLE_PROJECT_${pair%%:*}"; expected="${pair#*:}"
  [[ -z "${!name:-}" || "${!name}" == "$expected" ]] || fail "Conflicting $name override"
done
while IFS= read -r checksum; do
  [[ "$checksum" =~ ^[0-9a-f]{64}[[:space:]][[:space:]][A-Za-z0-9_./+-]+$ ]] || fail 'Invalid checksum inventory'
  path="${checksum:66}"
  [[ "$path" != /* && "/$path/" != *"/../"* ]] || fail 'Unsafe checksum path'
done < "$directory/SHA256SUMS"
(cd "$directory" && sha256sum --check --strict SHA256SUMS) || fail 'Framework input checksum mismatch'
require_file() {
  [[ -f "$directory/$1" ]] || fail "Missing framework input: $1"
  awk -v expected="$1" 'substr($0, 67) == expected { found=1 } END { exit !found }' \
    "$directory/SHA256SUMS" || fail "Input is absent from checksum inventory: $1"
}
require_file framework-inputs.json
for artifact in yano-x-appchain-cardano yano-x-appchain-host-adapter; do
  for extension in pom jar; do
    require_file "maven/org/yanoproject/x/$artifact/$version/$artifact-$version.$extension"
  done
done
for artifact in yano-node-api yano-cardano-api yano-host-api; do
  api_version="$node_api_version"
  [[ "$artifact" != yano-host-api ]] || api_version="$host_api_version"
  for extension in pom jar; do
    require_file "maven/org/yanoproject/$artifact/$api_version/$artifact-$api_version.$extension"
  done
done
repository_uri="$(python3 -c 'from pathlib import Path; import sys; print(Path(sys.argv[1]).resolve().as_uri())' \
  "$directory/maven")"
{
  printf 'ORG_GRADLE_PROJECT_appLedgerFrameworkVersion=%s\n' "$version"
  printf 'ORG_GRADLE_PROJECT_appLedgerFrameworkRepository=%s\n' "$repository_uri"
  printf 'ORG_GRADLE_PROJECT_hostApiVersion=%s\n' "$host_api_version"
  printf 'YANO_X_VERIFIED_INPUTS=%s\n' "$directory"
} >> "$GITHUB_ENV"
