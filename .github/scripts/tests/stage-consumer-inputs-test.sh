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
  if [[ "$artifact" == yano-host-api && " $* " == *' -x :host-api:publishMavenJavaPublicationToStagingRepository '* ]]; then
    mkdir -p host-api/build/libs
    printf 'jar\n' > "host-api/build/libs/yano-host-api-$host.jar"
    [[ "${TEST_CHANGED_HOST_API:-0}" != 1 ]] || printf 'changed\n' >> "host-api/build/libs/yano-host-api-$host.jar"
    continue
  fi
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
if grep -Eq '^:app:|^publishAll|appLedgerFramework' "$root/work/arguments.txt"; then
  echo 'Foundation bootstrap must not resolve the full host/framework' >&2; exit 1
fi
if env PATH="$root/bin:$PATH" GITHUB_WORKSPACE="$root/work" GITHUB_SHA="$commit" \
  GITHUB_OUTPUT="$root/output" YANO_INPUTS_SCOPE=foundation \
  bash "$scripts/stage-consumer-inputs.sh" > "$root/repeat.log" 2>&1; then
  echo 'Existing staged artifacts must not be overwritten' >&2; exit 1
fi
echo "PASS: foundation-only task selection, independent API version, inventory, overwrite refusal ($root)"

for name in host changed-host; do
  mkdir -p "$root/$name"
  cp "$root/work/gradlew" "$root/work/gradle.properties" "$root/$name/"
  changed=0; [[ "$name" != changed-host ]] || changed=1
  result=0
  env PATH="$root/bin:$PATH" GITHUB_WORKSPACE="$root/$name" \
    GITHUB_SHA=3333333333333333333333333333333333333333 GITHUB_OUTPUT="$root/$name/output" \
    YANO_INPUTS_SCOPE=host YANO_X_VERIFIED_INPUTS="$staged" \
    ORG_GRADLE_PROJECT_hostApiVersion=1.0.0-111111111 TEST_CHANGED_HOST_API="$changed" \
    bash "$scripts/stage-consumer-inputs.sh" > "$root/$name/log" 2>&1 || result=$?
  if [[ "$name" == host && "$result" != 0 || "$name" == changed-host && "$result" == 0 ]]; then
    echo "FAIL: $name (see $root/$name/log)" >&2; exit 1
  fi
  echo "PASS: $name"
done
grep -Fx ':host-api:publishMavenJavaPublicationToStagingRepository' "$root/host/arguments.txt" >/dev/null
host_path=maven/org/yanoproject/yano-host-api/1.0.0-111111111/yano-host-api-1.0.0-111111111.pom
cmp "$staged/$host_path" "$root/host/build/consumer-inputs/3333333333333333333333333333333333333333/$host_path"
