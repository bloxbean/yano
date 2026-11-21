#!/usr/bin/env bash
set -euo pipefail
scripts="$(cd "$(dirname "$0")/.." && pwd)"
root="$(mktemp -d "${TMPDIR:-/tmp}/consumer-stage-tests.XXXXXX")"
mkdir -p "$root/bin" "$root/work"
commit=3333333333333333333333333333333333333333
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
    -PstagingRepository=*) repository="${argument#*=}" ;;
  esac
done
for artifact in yano-node-api yano-host-api yano-plugin-catalog yano-app-core; do
  path="$repository/org/yanoproject/$artifact/$version"
  mkdir -p "$path"
  printf 'jar\n' > "$path/$artifact-$version.jar"
  printf '<project/>\n' > "$path/$artifact-$version.pom"
done
mkdir -p app/build/distributions
printf 'zip\n' > "app/build/distributions/yano-$version.zip"
MOCK
chmod +x "$root/bin/git" "$root/work/gradlew"
printf 'version = 0.1.0-next-SNAPSHOT\n' > "$root/work/gradle.properties"
run_stage() {
  env PATH="$root/bin:$PATH" GITHUB_WORKSPACE="$root/work" GITHUB_SHA="$commit" \
    GITHUB_OUTPUT="$root/output" \
    bash "$scripts/stage-consumer-inputs.sh"
}
run_stage > "$root/log" 2>&1 || { echo "FAIL: staging (see $root/log)" >&2; exit 1; }
staged="$root/work/build/consumer-inputs/$commit"
jq -e '.scope == null and .distribution == "yano-0.1.0-next-333333333.zip"
  and .hostApiVersion == null' "$staged/yano-inputs.json" >/dev/null
(cd "$staged" && sha256sum --check --strict SHA256SUMS >/dev/null)
for task in publishAllPublicationsToStagingRepository :app:yanoDistZip :app:packagedJvmPluginCatalogSmoke \
  :app:packagedJvmAppLedgerSmoke; do
  grep -Fx "$task" "$root/work/arguments.txt" >/dev/null
done
for artifact in yano-plugin-catalog yano-app-core; do
  grep -q "maven/org/yanoproject/$artifact/" "$staged/SHA256SUMS"
done
if run_stage > "$root/repeat.log" 2>&1; then
  echo 'Existing staged artifacts must not be overwritten' >&2; exit 1
fi
echo "PASS: every publication and the JVM ZIP, inventory, overwrite refusal"
