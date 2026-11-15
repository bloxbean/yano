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
scope="${YANO_INPUTS_SCOPE:-host}"
[[ "$scope" == host || "$scope" == foundation ]] || { echo 'Invalid input scope' >&2; exit 1; }
host_api_version="${ORG_GRADLE_PROJECT_hostApiVersion:-1.0.0-${commit:0:9}}"
[[ "$host_api_version" =~ ^[0-9A-Za-z.-]+$ && "$host_api_version" != *-SNAPSHOT ]]
mkdir -p "$staged_directory/maven"
if [[ "$scope" == foundation ]]; then
  tasks=(:node-api:publishAllPublicationsToStagingRepository
    :cardano-api:publishAllPublicationsToStagingRepository :host-api:publishAllPublicationsToStagingRepository)
else
  tasks=(publishAllPublicationsToStagingRepository :app:yanoDistZip :app:packagedJvmPluginCatalogSmoke)
  if [[ -n "${YANO_X_VERIFIED_INPUTS:-}" ]]; then
    cp -R "$YANO_X_VERIFIED_INPUTS/maven/." "$staged_directory/maven/"
    # Preserve the exact independently versioned foundation publication, including
    # its dependency metadata. Rebuilding the host must not rewrite that POM.
    tasks+=(-x :host-api:publishMavenJavaPublicationToStagingRepository :host-api:jar)
  fi
fi
./gradlew "${tasks[@]}" \
  "-PstagingRepository=$staged_directory/maven" "-Pversion=$staged_version" \
  "-PhostApiVersion=$host_api_version" -PskipSigning=true --no-parallel
if [[ "$scope" == host ]]; then
  cp "app/build/distributions/yano-$staged_version.zip" "$staged_directory/"
  if [[ -n "${YANO_X_VERIFIED_INPUTS:-}" ]]; then
    host_api_path="maven/org/yanoproject/yano-host-api/$host_api_version/yano-host-api-$host_api_version.jar"
    cmp "$YANO_X_VERIFIED_INPUTS/$host_api_path" "host-api/build/libs/yano-host-api-$host_api_version.jar" || {
      echo 'Host API bytes changed: stage a new foundation version before rebuilding the framework' >&2; exit 1;
    }
  fi
fi
jq -n --arg commit "$commit" --arg version "$staged_version" \
  --arg hostApiVersion "$host_api_version" --arg scope "$scope" \
  '{schemaVersion:1,repository:"bloxbean/yano",commit:$commit,version:$version,
    hostApiVersion:$hostApiVersion,scope:$scope,mavenDirectory:"maven"}
    + (if $scope == "host" then {distribution:("yano-"+$version+".zip")} else {} end)' \
  > "$staged_directory/yano-inputs.json"
(
  cd "$staged_directory"
  if [[ -z "$(find maven -type f -print -quit)" ]]; then
    echo 'Staging produced no Maven artifacts; refusing an empty inventory' >&2
    exit 1
  fi
  find maven -type f -print0 | sort -z | xargs -0 sha256sum
  [[ "$scope" != host ]] || sha256sum "yano-$staged_version.zip"
  sha256sum yano-inputs.json
) > "$staged_directory/SHA256SUMS"
printf 'directory=%s\nversion=%s\n' "$staged_directory" "$staged_version" >> "$GITHUB_OUTPUT"
