#!/usr/bin/env bash
set -euo pipefail
scripts_dir="$(cd "$(dirname "$0")/.." && pwd)"
root="$(mktemp -d "${TMPDIR:-/tmp}/framework-input-tests.XXXXXX")"
commit=1111111111111111111111111111111111111111
version=0.1.0-111111111
node_version=0.1.0-222222222
host_version=1.0.0-222222222
fixture="$root/fixture"
mkdir -p "$root/bin" "$fixture/maven"
for coordinate in "org/yanoproject/x:yano-x-appchain-cardano:$version" \
  "org/yanoproject/x:yano-x-appchain-host-adapter:$version" \
  "org/yanoproject:yano-node-api:$node_version" "org/yanoproject:yano-cardano-api:$node_version" \
  "org/yanoproject:yano-host-api:$host_version"; do
  IFS=: read -r group artifact artifact_version <<< "$coordinate"
  path="$fixture/maven/$group/$artifact/$artifact_version"
  mkdir -p "$path"
  for extension in pom jar; do printf 'fixture\n' > "$path/$artifact-$artifact_version.$extension"; done
done
jq -n --arg commit "$commit" --arg version "$version" --arg node "$node_version" --arg host "$host_version" \
  '{schemaVersion:1,repository:"bloxbean/yano-x",scope:"framework",commit:$commit,version:$version,
    nodeApiVersion:$node,hostApiVersion:$host,mavenDirectory:"maven",
    foundation:{repository:"bloxbean/yano",commit:"2222222222222222222222222222222222222222",runId:"456"}}' \
  > "$fixture/framework-inputs.json"
(cd "$fixture"; find maven -type f -print0 | sort -z | xargs -0 sha256sum; sha256sum framework-inputs.json) \
  > "$fixture/SHA256SUMS"
cat > "$root/bin/gh" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$1" == api ]]; then
  jq -n --arg commit "${TEST_COMMIT:-$YANO_X_INPUTS_COMMIT}" \
    --arg repository "${TEST_REPOSITORY:-bloxbean/yano-x}" --arg result "${TEST_RESULT:-success}" \
    '{head_sha:$commit,repository:{full_name:$repository},status:"completed",conclusion:$result,
      event:"workflow_dispatch",path:".github/workflows/build.yml"}'
else
  while [[ "$1" != --dir ]]; do shift; done
  directory="$2"
  cp -R "$TEST_FIXTURE/." "$directory/"
  if [[ -n "${TEST_INVALID_VERSION_FIELD:-}" ]]; then
    jq --arg field "$TEST_INVALID_VERSION_FIELD" '.[$field]="1 -Dunexpected=value"' \
      "$directory/framework-inputs.json" > "$directory/manifest.tmp"
    mv "$directory/manifest.tmp" "$directory/framework-inputs.json"
    grep -v '  framework-inputs.json$' "$directory/SHA256SUMS" > "$directory/sums.tmp"
    (cd "$directory" && sha256sum framework-inputs.json) >> "$directory/sums.tmp"
    mv "$directory/sums.tmp" "$directory/SHA256SUMS"
  fi
  if [[ "${TEST_TAMPER:-0}" == 1 ]]; then printf tampered >> "$directory/framework-inputs.json"; fi
  if [[ "${TEST_UNSAFE:-0}" == 1 ]]; then printf '%064d  ../outside\n' 0 >> "$directory/SHA256SUMS"; fi
  if [[ -n "${TEST_DROP:-}" ]]; then
    find "$directory/maven" -path "*/$TEST_DROP/*" -type f -delete
    grep -v "/$TEST_DROP/" "$directory/SHA256SUMS" > "$directory/sums.tmp"
    mv "$directory/sums.tmp" "$directory/SHA256SUMS"
  fi
  if [[ "${TEST_UNLISTED:-0}" == 1 ]]; then
    grep -v '/yano-x-appchain-cardano/' "$directory/SHA256SUMS" > "$directory/sums.tmp"
    mv "$directory/sums.tmp" "$directory/SHA256SUMS"
  fi
fi
MOCK
chmod +x "$root/bin/gh"
run_case() {
  local name="$1" expected="$2" result=0
  shift 2
  local workspace="$root/$name"
  mkdir -p "$workspace"
  if [[ "$name" == committed-* || "$name" == explicit-default-override ]]; then
    mkdir -p "$workspace/.github"
    printf 'appLedgerFrameworkVersion = %s\n' "$version" > "$workspace/gradle.properties"
    if [[ "$name" == committed-drift || "$name" == explicit-default-override ]]; then
      printf 'appLedgerFrameworkVersion = 0.1.0-different\n' > "$workspace/gradle.properties"
    fi
    jq -n --arg commit "$commit" '{framework:{runId:"123",commit:$commit}}' > "$workspace/.github/ci-input-pins.json"
  fi
  if env PATH="$root/bin:$PATH" TEST_FIXTURE="$fixture" GITHUB_WORKSPACE="$workspace" \
    GITHUB_ENV="$workspace/env" YANO_X_INPUTS_RUN_ID=123 YANO_X_INPUTS_COMMIT="$commit" \
    ORG_GRADLE_PROJECT_appLedgerFrameworkRepository='' ORG_GRADLE_PROJECT_appLedgerFrameworkVersion='' \
    ORG_GRADLE_PROJECT_hostApiVersion='' "$@" bash "$scripts_dir/prepare-framework-inputs.sh" \
    > "$workspace/output.log" 2>&1; then result=0; else result=$?; fi
  if [[ "$expected" == pass && "$result" != 0 || "$expected" == fail && "$result" == 0 ]]; then
    echo "FAIL: $name (see $workspace/output.log)" >&2; exit 1
  fi
  echo "PASS: $name"
}
run_case valid pass
run_case 'space path' pass
run_case committed-pin pass YANO_X_INPUTS_RUN_ID='' YANO_X_INPUTS_COMMIT=''
run_case committed-drift fail YANO_X_INPUTS_RUN_ID='' YANO_X_INPUTS_COMMIT=''
grep -F 'Committed framework pin differs' "$root/committed-drift/output.log" >/dev/null
[[ ! -e "$root/committed-drift/env" ]]
run_case explicit-default-override pass
run_case ordinary-default pass YANO_X_INPUTS_RUN_ID='' YANO_X_INPUTS_COMMIT=''
[[ ! -e "$root/ordinary-default/env" ]]
for field in version nodeApiVersion hostApiVersion; do
  run_case "invalid-$field" fail TEST_INVALID_VERSION_FIELD="$field"
  grep -F 'Invalid immutable version' "$root/invalid-$field/output.log" >/dev/null
  [[ ! -e "$root/invalid-$field/env" ]]
done
run_case missing-pin fail YANO_X_INPUTS_COMMIT=''
run_case wrong-commit fail TEST_COMMIT=2222222222222222222222222222222222222222
run_case wrong-repository fail TEST_REPOSITORY=someone/else
run_case failed-run fail TEST_RESULT=failure
run_case wrong-framework-version fail ORG_GRADLE_PROJECT_appLedgerFrameworkVersion=0.1.0-other
run_case wrong-host-version fail ORG_GRADLE_PROJECT_hostApiVersion=1.0.0-other
run_case mixed-repository fail ORG_GRADLE_PROJECT_appLedgerFrameworkRepository=https://example.invalid
run_case tampered fail TEST_TAMPER=1
run_case unsafe-checksum fail TEST_UNSAFE=1
run_case unlisted-publication fail TEST_UNLISTED=1
for artifact in yano-x-appchain-cardano yano-x-appchain-host-adapter yano-node-api yano-cardano-api yano-host-api; do
  run_case "missing-$artifact" fail TEST_DROP="$artifact"
done
grep -Fx "ORG_GRADLE_PROJECT_appLedgerFrameworkVersion=$version" "$root/valid/env" >/dev/null
grep -Fx "ORG_GRADLE_PROJECT_hostApiVersion=$host_version" "$root/valid/env" >/dev/null
grep -F '%20' "$root/space path/env" >/dev/null
echo "Framework input fixtures retained at $root"

# Exercise real Gradle precedence without configuring this repository or resolving dependencies.
precedence="$root/gradle-precedence"
mkdir -p "$precedence"
printf "rootProject.name = 'input-precedence'\n" > "$precedence/settings.gradle"
keys=(appLedgerFrameworkVersion hostApiVersion)
for key in "${keys[@]}"; do printf '%s=checked-in-default\n' "$key"; done > "$precedence/gradle.properties"
cat > "$precedence/build.gradle" <<'GRADLE'
tasks.register('verifyInputs') {
    doLast {
        ['appLedgerFrameworkVersion', 'hostApiVersion'].each { key ->
            assert providers.gradleProperty(key).get() == providers.gradleProperty("expected_${key}").get()
        }
    }
}
GRADLE
(
  # GITHUB_ENV is data, not shell code (paths may contain spaces).
  while IFS='=' read -r key value; do export "$key=$value"; done < "$root/valid/env"
  prepared_env="$root/valid/env"
  unset GRADLE_OPTS
  for mode in defaults prepared command-line; do
    arguments=()
    for key in "${keys[@]}"; do
      variable="ORG_GRADLE_PROJECT_$key"
      expected="$(sed -n "s/^$variable=//p" "$prepared_env")"
      if [[ "$mode" == defaults ]]; then
        expected=checked-in-default
        unset "$variable"
      else
        export "$variable=$expected"
      fi
      if [[ "$mode" == command-line ]]; then
        expected=explicit-override
        arguments+=("-P$key=$expected")
      fi
      arguments+=("-Pexpected_$key=$expected")
    done
    if ! "$scripts_dir/../../gradlew" -p "$precedence" --offline --no-daemon --no-watch-fs --no-parallel \
        --console=plain verifyInputs "${arguments[@]}" > "$precedence/$mode.log" 2>&1; then
      echo "FAIL: Gradle $mode precedence (see $precedence/$mode.log)" >&2
      exit 1
    fi
    echo "PASS: Gradle $mode precedence"
  done
)
