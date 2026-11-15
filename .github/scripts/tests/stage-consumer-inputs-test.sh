#!/usr/bin/env bash
set -euo pipefail
scripts="$(cd "$(dirname "$0")/.." && pwd)"
root="$(mktemp -d "${TMPDIR:-/tmp}/foundation-stage-tests.XXXXXX")"
mkdir -p "$root/bin" "$root/work"
commit=1111111111111111111111111111111111111111
cat > "$root/bin/git" <<'MOCK'
#!/usr/bin/env bash
if [[ "$1" == status ]]; then
  [[ "${TEST_DIRTY:-0}" != 1 ]] || echo ' M tracked-file'
else
  echo "$GITHUB_SHA"
fi
MOCK
cat > "$root/work/gradlew" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$@" > arguments.txt
for argument in "$@"; do
  case "$argument" in
    -Pversion=*) version="${argument#*=}" ;;
    -PhostApiVersion=*) host="${argument#*=}" ;;
    -PstagingRepository=*) repository="${argument#*=}" ;;
  esac
done
for artifact in yano-node-api yano-cardano-api yano-host-api; do
  v="$version"; [[ "$artifact" != yano-host-api ]] || v="$host"
  path="$repository/org/yanoproject/$artifact/$v"
  mkdir -p "$path"
  printf 'jar\n' > "$path/$artifact-$v.jar"
  printf '<project/>\n' > "$path/$artifact-$v.pom"
done
if [[ " $* " == *' :app:yanoDistZip '* ]]; then
  mkdir -p app/build/distributions
  printf 'zip\n' > "app/build/distributions/yano-$version.zip"
fi
MOCK
chmod +x "$root/bin/git" "$root/work/gradlew"
printf 'version = 0.1.0-next-SNAPSHOT\n' > "$root/work/gradle.properties"
env PATH="$root/bin:$PATH" GITHUB_WORKSPACE="$root/work" GITHUB_SHA="$commit" \
  GITHUB_OUTPUT="$root/output" YANO_INPUTS_SCOPE=foundation ORG_GRADLE_PROJECT_hostApiVersion='' \
  bash "$scripts/stage-consumer-inputs.sh"
staged="$root/work/build/consumer-inputs/$commit"
jq -e '.scope == "foundation" and .distribution == null and .hostApiVersion == "1.0.0-111111111"' \
  "$staged/yano-inputs.json" >/dev/null
(cd "$staged" && sha256sum --check --strict SHA256SUMS >/dev/null)
grep -Fx ':node-api:publishAllPublicationsToStagingRepository' "$root/work/arguments.txt" >/dev/null
grep -Fx ':cardano-api:publishAllPublicationsToStagingRepository' "$root/work/arguments.txt" >/dev/null
grep -Fx ':host-api:publishAllPublicationsToStagingRepository' "$root/work/arguments.txt" >/dev/null
if grep -Eq '^:app:|^publishAll' "$root/work/arguments.txt"; then
  echo 'Foundation staging must not build the full host' >&2; exit 1
fi
if env PATH="$root/bin:$PATH" GITHUB_WORKSPACE="$root/work" GITHUB_SHA="$commit" \
  GITHUB_OUTPUT="$root/output" YANO_INPUTS_SCOPE=foundation \
  bash "$scripts/stage-consumer-inputs.sh" > "$root/repeat.log" 2>&1; then
  echo 'Existing staged artifacts must not be overwritten' >&2; exit 1
fi
echo "PASS: foundation-only task selection, independent API version, inventory, overwrite refusal ($root)"

mkdir -p "$root/host"
cp "$root/work/gradlew" "$root/work/gradle.properties" "$root/host/"
host_commit=3333333333333333333333333333333333333333
env PATH="$root/bin:$PATH" GITHUB_WORKSPACE="$root/host" GITHUB_SHA="$host_commit" \
  GITHUB_OUTPUT="$root/host/output" YANO_INPUTS_SCOPE=host ORG_GRADLE_PROJECT_hostApiVersion='' \
  bash "$scripts/stage-consumer-inputs.sh" > "$root/host/log" 2>&1 \
  || { echo "FAIL: host (see $root/host/log)" >&2; exit 1; }
host_staged="$root/host/build/consumer-inputs/$host_commit"
jq -e '.scope == "host" and .distribution == "yano-0.1.0-next-333333333.zip"
  and .hostApiVersion == "1.0.0-333333333"' "$host_staged/yano-inputs.json" >/dev/null
(cd "$host_staged" && sha256sum --check --strict SHA256SUMS >/dev/null)
grep -Fx 'publishAllPublicationsToStagingRepository' "$root/host/arguments.txt" >/dev/null
grep -Fx ':app:yanoDistZip' "$root/host/arguments.txt" >/dev/null
echo "PASS: host task selection, distribution, inventory"
