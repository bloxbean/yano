#!/usr/bin/env bash
set -euo pipefail

: "${GITHUB_WORKSPACE:?GITHUB_WORKSPACE is required}"
: "${GITHUB_OUTPUT:?GITHUB_OUTPUT is required}"
cd "$GITHUB_WORKSPACE"
if [[ -n "$(git status --porcelain)" ]]; then
  echo 'Qualification inputs require a clean committed source checkout' >&2
  exit 1
fi
commit="$(git rev-parse HEAD)"
[[ "$commit" =~ ^[0-9a-f]{40}$ ]]
[[ "$commit" == "${GITHUB_SHA:?GITHUB_SHA is required}" ]]
base_version="$(sed -n 's/^version[[:space:]]*=[[:space:]]*//p' gradle.properties)"
[[ "$base_version" =~ ^[0-9A-Za-z.-]+-SNAPSHOT$ ]]
staged_version="${base_version%-SNAPSHOT}-${commit:0:9}"
staged_directory="$GITHUB_WORKSPACE/build/consumer-inputs/$commit"
if [[ -e "$staged_directory" ]]; then
  echo 'Refusing to overwrite an existing input staging directory' >&2
  exit 1
fi
mkdir -p "$staged_directory/maven"
./gradlew publishAllPublicationsToStagingRepository :app:yanoDistZip \
  :app:packagedJvmPluginCatalogSmoke \
  "-PstagingRepository=$staged_directory/maven" "-Pversion=$staged_version" \
  -PskipSigning=true --no-parallel
cp "app/build/distributions/yano-$staged_version.zip" "$staged_directory/"
jq -n --arg commit "$commit" --arg version "$staged_version" \
  '{schemaVersion:1,repository:"bloxbean/yano",commit:$commit,version:$version,
    distribution:("yano-"+$version+".zip"),mavenDirectory:"maven"}' \
  > "$staged_directory/yano-inputs.json"
(
  cd "$staged_directory"
  # Inventory only regular packaged outputs; no credentials or source checkout.
  find maven -type f -print0 | sort -z | xargs -0 sha256sum
  sha256sum "yano-$staged_version.zip" yano-inputs.json
) > "$staged_directory/SHA256SUMS"
printf 'directory=%s\nversion=%s\n' "$staged_directory" "$staged_version" >> "$GITHUB_OUTPUT"
