# ADR-023: Docker Images and Compose Release Distribution

**Status:** Proposed  
**Date:** 2026-05-25  
**Authors:** Codex (GPT-5.5)  
**Related:** Existing `app:yanoDistZip`, `app:yanoNativeDistZip`, `.github/workflows/release.yml`, Yaci Store Docker/Compose release pattern

## Context

Yano currently produces two useful application artifacts:

- JVM distribution zip from `:app:yanoDistZip`, containing `yano.jar`, `bin/yano.sh`, `config/`, and `plugins/`.
- Native distribution zip from `:app:yanoNativeDistZip`, containing the native
  `yano` binary, `bin/yano.sh`, and `config/`. Native plugins are fixed at
  build time; the artifact does not advertise a runtime plugin directory.

There is also an existing `app/Dockerfile`, but it builds by cloning `bloxbean/yano` from `main` inside the Docker build. That is not suitable for release packaging because the image content is not tied directly to the checked-out source, tested artifacts, or release tag that triggered the workflow.

The existing `app/Dockerfile` is used by another project and must remain untouched for now. The new release Dockerfiles will live under `docker/` and will not change the legacy `app/Dockerfile` behavior.

Yano needs a standard release path for:

- JVM Docker image.
- Native Docker image.
- `linux/amd64` and `linux/arm64` images for both variants.
- A Docker Compose zip distribution similar to Yaci Store.
- DockerHub publishing under the `bloxbean` account.
- Release ordering where Docker images are published only after the existing Maven release succeeds.

## Decision

Adopt an artifact-first Docker release flow.

Gradle and CI build the Yano JVM/native artifacts first. Dockerfiles then copy those artifacts into runtime images. Docker builds must not clone the repository or build a different source revision inside the Dockerfile.

Publish Docker images to DockerHub under:

- `bloxbean/yano:<version>-jvm`
- `bloxbean/yano:<version>-native`
- `bloxbean/yano:latest-jvm`
- `bloxbean/yano:latest-native`

Do not publish bare image aliases in the initial Docker release:

- do not publish `bloxbean/yano:<version>`
- do not publish `bloxbean/yano:latest`

Users must choose `-jvm` or `-native` explicitly. This avoids making `latest` a permanent compatibility contract before the project has enough Docker adoption data.

## Release Tag Rules

Docker publishing is allowed for released Maven versions only. The workflow must reject a Docker release if the version still contains `-SNAPSHOT`.

Versioned flavor tags are published for both final and pre-release versions:

- `bloxbean/yano:<version>-jvm`
- `bloxbean/yano:<version>-native`

Floating tags are published only for final stable versions:

- `bloxbean/yano:latest-jvm`
- `bloxbean/yano:latest-native`

A final stable version is a strict semantic version with no suffix:

```text
^[0-9]+\.[0-9]+\.[0-9]+$
```

The workflow must not update `latest-jvm` or `latest-native` for versions containing suffixes such as:

- `-SNAPSHOT`
- `-preN`
- `-rcN`
- `-alphaN`
- `-betaN`

The workflow should compute:

```bash
IS_FINAL_RELEASE=false
if [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  IS_FINAL_RELEASE=true
fi
```

and use `IS_FINAL_RELEASE` to decide whether to push the `latest-*` tags.

## Release Workflow Dependency

Keep the Maven staging job as the release gate.

The existing `.github/workflows/release.yml` is triggered by:

```yaml
on:
  push:
    tags:
      - "v*"
```

Docker release should be run from this workflow after the Maven staging job succeeds:

```yaml
jobs:
  publish:
    # existing Maven Central staging job

  docker-release:
    needs: publish
    uses: ./.github/workflows/release-docker.yml
    with:
      version: ${{ needs.publish.outputs.version }}
      git_tag: ${{ github.ref_name }}
    secrets: inherit
```

`needs: publish` ensures Docker images are not pushed if Maven staging fails. The Docker workflow must not wait for artifacts to become visible on `repo1.maven.org`, because this project stages Maven Central artifacts first and performs the final Maven release manually later.

The Docker implementation should live in a separate reusable workflow:

```yaml
name: Release Docker Images

on:
  workflow_call:
    inputs:
      version:
        required: true
        type: string
      git_tag:
        required: true
        type: string
  workflow_dispatch:
    inputs:
      version:
        required: true
        type: string
      git_tag:
        required: true
        type: string
```

This keeps Docker release logic isolated while preserving strict release ordering. The `workflow_dispatch` path exists for dry-run validation and manual retry/recovery from an existing release tag.

Manual `workflow_dispatch` must default to `dry_run=true`. Dry run builds and validates artifacts without DockerHub login, DockerHub push, manifest promotion, or GitHub release upload.

For real manual publishing (`dry_run=false`), add a preflight check before any Docker login or build:

1. reject versions containing `-SNAPSHOT`
2. verify `git_tag == v${VERSION}`
3. verify the Gradle version at that tag matches `VERSION`
4. verify the GitHub release exists before uploading the compose zip
5. require an explicit `maven_staging_confirmed=true` input

Do not make `release-docker.yml` independently trigger on `push.tags: v*` because separate tag-triggered workflows cannot directly depend on each other with `needs`.

## DockerHub Credentials

Use the same secret convention as Yaci Store:

- `DOCKERHUB_USERNAME`
- `DOCKERHUB_TOKEN`

Docker login:

```yaml
- name: Login to Docker Hub
  uses: docker/login-action@v3
  with:
    username: ${{ secrets.DOCKERHUB_USERNAME }}
    password: ${{ secrets.DOCKERHUB_TOKEN }}
```

DockerHub is the authoritative registry for the initial implementation. A GHCR mirror can be added later as a follow-up if the project wants a secondary public registry for CI rate-limit resilience.

## Repository Layout

Add:

```text
docker/
  yano.sh
  yano.bat
  jvm/
    Dockerfile
  native/
    Dockerfile
  compose/
    .env
    yano.yml
    yano-devnet.yml
    README.md
  config/
    env
```

Do not remove, replace, or deprecate the existing `app/Dockerfile` as part of this work. It remains a legacy compatibility Dockerfile for external consumers. New release Docker assets live under `docker/`.

## JVM Image

Build input:

```bash
./gradlew :app:yanoDistZip -PskipSigning=true
```

Image build flow:

1. Unpack `app/build/distributions/yano-<version>.zip` into a Docker build context.
2. Copy the distribution into `/app`.
3. Run as a non-root user.
4. Expose:
   - `7070` for HTTP/API.
   - `13337` for Cardano N2N server.
5. Install `curl` for a uniform HTTP health check.
6. Use a glibc-based Java runtime image, not Alpine, because RocksDB JNI requires glibc-compatible native libraries.

Base image must be configurable and validated at workflow time:

```text
ARG JVM_BASE_IMAGE=eclipse-temurin:25-jre
```

Do not hardcode an OS-specific Temurin tag unless the workflow verifies it exists before release. Add a preflight step:

```bash
docker buildx imagetools inspect "${JVM_BASE_IMAGE}" >/dev/null
```

If `eclipse-temurin:25-jre` is unavailable when the workflow is implemented, choose an available glibc-based Temurin 25 runtime tag. If no Temurin 25 runtime tag is available, use a documented temporary fallback and keep the Java runtime version aligned with Yano's compiled bytecode requirements.

Entrypoint:

```bash
java ${JAVA_OPTS:-} \
  -Dquarkus.profile="${YANO_PROFILE:-preprod}" \
  -jar /app/yano.jar \
  ${YANO_EXTRA_ARGS:-}
```

## Native Image

Build input:

```bash
./gradlew :app:yanoNativeDistZip -PskipSigning=true
```

Native Docker images need Linux native binaries:

- `linux/amd64` binary for `linux/amd64` image.
- `linux/arm64` binary for `linux/arm64` image.

Do not use the current macOS ARM native zip for Docker ARM64 images. A macOS ARM64 binary cannot run in a Linux ARM64 container.

Preferred CI strategy:

- Use a Linux AMD64 runner for `linux/amd64`.
- Use GitHub-hosted Linux ARM64 runners for `linux/arm64`, with the `ubuntu-24.04-arm` label.

Fallback strategy:

- Use QEMU/buildx for image assembly only if a native Linux ARM64 runner is temporarily unavailable.
- Avoid GraalVM native-image compilation under QEMU as the normal release path because it is slow and more fragile.

Runtime base should remain glibc-based until native dependency checks prove a smaller base is safe. Use Ubuntu 24.04 for the initial native image so the runtime glibc is aligned with GitHub's `ubuntu-24.04` native build runners:

```text
ubuntu:24.04
```

Install only runtime dependencies needed by the native executable, for example:

- `ca-certificates`
- `curl`
- `libstdc++6`
- `zlib1g` if required

Install `curl` in both JVM and native images so Compose can use the same HTTP health check for both image flavors. CI must run `ldd /app/yano` or equivalent in the native image build/smoke test to validate dependencies.

Entrypoint:

```bash
/app/yano \
  -Dquarkus.profile="${YANO_PROFILE:-preprod}" \
  ${YANO_EXTRA_ARGS:-}
```

## Multi-Architecture Publishing

JVM image can use Docker Buildx multi-platform build directly:

```yaml
platforms: linux/amd64,linux/arm64
tags: |
  bloxbean/yano:${{ inputs.version }}-jvm
  # Only when IS_FINAL_RELEASE=true:
  bloxbean/yano:latest-jvm
```

Native image should publish per-platform images from matching native artifacts, then assemble/push the multi-arch manifest:

- `bloxbean/yano:<version>-native-amd64`
- `bloxbean/yano:<version>-native-arm64`
- manifest: `bloxbean/yano:<version>-native`
- manifest, only when `IS_FINAL_RELEASE=true`: `bloxbean/yano:latest-native`

The per-arch tags may be retained for debugging or deleted later. Retaining them is useful for diagnosing platform-specific native failures.

## Compose Distribution

Create a Docker Compose zip similar to Yaci Store:

```text
yano-docker-<version>.zip
```

Contents:

```text
yano-docker-<version>/
  yano.sh
  yano.bat
  compose/
    .env
    yano.yml
    yano-devnet.yml
    README.md
  config/
    env
    application.yml
  chainstate/
  logs/
  plugins/
```

The image itself contains the immutable network genesis/config files under `/app/config/network`. The Compose distribution should not bind-mount `../config` over `/app/config`, because that would hide the image's bundled network files. User override files are mounted individually.

The shared Compose layout retains `plugins/` for the JVM flavor. The native
flavor cannot load those JARs and reports any mounted JARs as ignored; native
plugin selection is a build-time operation.

If a user needs custom genesis files, document an explicit additional mount for that network subdirectory, for example:

```yaml
- ../config/network/custom:/app/config/network/custom:ro
```

Add a Gradle task:

```text
:app:yanoDockerDistZip
```

Do not use lowercase `tag` in Compose env files. Use `YANO_IMAGE_TAG`.

The repository default `.env` should be easy to edit and should not be rewritten in place by release workflows:

```text
YANO_IMAGE=bloxbean/yano
YANO_IMAGE_TAG=latest
YANO_IMAGE_FLAVOR=jvm
YANO_HTTP_PORT=7070
YANO_N2N_PORT=13337
YANO_PROFILE=preprod
INSTANCE_NAME=default
```

The release zip README must state the exact released image tag. Users who want reproducible deployments should set `YANO_IMAGE_TAG=<version>`.

For pre-release Docker zips, the generated zip `.env` must set `YANO_IMAGE_TAG=<version>` because pre-release workflows do not publish `latest-*` tags.

Compose image reference:

```yaml
image: ${YANO_IMAGE:-bloxbean/yano}:${YANO_IMAGE_TAG:-latest}-${YANO_IMAGE_FLAVOR:-jvm}
```

This lets users switch to native with:

```text
YANO_IMAGE_FLAVOR=native
```

The compose distribution should also include small launch wrappers, modeled after Yaci Store's Docker distribution:

- `yano.sh` for Unix-like systems.
- `yano.bat` for Windows.

The wrappers should call `docker compose` with the correct compose and env files from the extracted distribution root, so users do not need to remember the full compose command. Supported initial commands:

```text
start
start:devnet
stop
restart
restart:devnet
logs
logs:yano
status
config
pull
```

## Compose Environment Contract

Use environment variable names that map cleanly to Yano and Quarkus properties.

Compose `.env`:

```text
INSTANCE_NAME=default

YANO_IMAGE=bloxbean/yano
YANO_IMAGE_TAG=latest
YANO_IMAGE_FLAVOR=jvm

YANO_PROFILE=preprod
YANO_HTTP_PORT=7070
YANO_N2N_PORT=13337

# JVM image only. Uncomment and tune when explicit JVM heap settings are needed.
# JAVA_OPTS=-Xms1g -Xmx4g
YANO_EXTRA_ARGS=
```

Container env file `docker/config/env`:

```text
YANO_AUTO_SYNC_START=true
YANO_STORAGE_PATH=/app/chainstate
YACI_PLUGINS_DIRECTORY=/app/plugins
QUARKUS_HTTP_HOST=0.0.0.0
```

Remote-node overrides should be supported through environment variables:

```text
YANO_REMOTE_HOST=preprod-node.world.dev.cardano.org
YANO_REMOTE_PORT=30000
YANO_REMOTE_PROTOCOL_MAGIC=1
```

For devnet:

```text
YANO_PROFILE=devnet
YANO_NETWORK=devnet
YANO_IMAGE_FLAVOR=jvm
```

Native devnet can be supported, but it should be validated separately because block production, transaction evaluation, and script evaluation depend on native-image compatibility.

## Runtime Defaults

Docker images must inherit Yano's normal application defaults. They must not add block-producer or transaction-evaluation flags in the Dockerfile.

In particular:

- `yano.block-producer.enabled` remains `false` unless the selected profile or environment explicitly enables it.
- `YANO_PROFILE=preprod` is the default runtime profile.
- Devnet/block-producer mode requires explicit opt-in through `YANO_PROFILE=devnet` or equivalent profile/env settings.

The new release Docker images expose and map `7070`, matching Yano's configured HTTP port. This does not change the legacy `app/Dockerfile`, which currently exposes `8080`.

## Compose Services

Base compose service:

```yaml
services:
  yano:
    image: ${YANO_IMAGE:-bloxbean/yano}:${YANO_IMAGE_TAG:-latest}-${YANO_IMAGE_FLAVOR:-jvm}
    container_name: yano-${INSTANCE_NAME:-default}
    env_file:
      - ../config/env
    environment:
      YANO_PROFILE: ${YANO_PROFILE:-preprod}
      JAVA_OPTS: ${JAVA_OPTS:-}
      YANO_EXTRA_ARGS: ${YANO_EXTRA_ARGS:-}
    ports:
      - "${YANO_HTTP_PORT:-7070}:7070"
      - "${YANO_N2N_PORT:-13337}:13337"
    volumes:
      - ../chainstate:/app/chainstate
      - ../logs:/app/logs
      - ../config/application.yml:/app/config/application.yml:ro
      - ../plugins:/app/plugins
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://localhost:7070/q/health/ready || exit 1"]
      interval: 20s
      timeout: 5s
      retries: 12
    restart: unless-stopped
    logging:
      driver: json-file
      options:
        max-size: "1m"
        max-file: "20"
```

Both JVM and native images must include `curl`; the Compose health check must work unchanged for both image flavors.

## CI Validation

Docker release workflow must run smoke tests before pushing or before tagging latest:

JVM smoke:

1. Start container with `YANO_AUTO_SYNC_START=false`.
2. Wait for `http://localhost:7070/q/health/ready`.
3. Query a lightweight status/config endpoint.
4. Stop container cleanly.

Native smoke:

1. Run `ldd /app/yano` in the image.
2. Start container with `YANO_AUTO_SYNC_START=false`.
3. Wait for health endpoint.
4. Query a lightweight status/config endpoint.
5. Stop container cleanly.

Compose zip smoke:

1. Unzip `yano-docker-<version>.zip`.
2. Run `docker compose -f compose/yano.yml config`.
3. Optionally run a short `docker compose up` JVM smoke.

## Security and Provenance

Initial release:

- Publish DockerHub images with versioned tags.
- Use non-root container user.
- Avoid embedding secrets or local `application.yml.working`.
- Do not copy local chainstate, logs, or generated test data into images or compose zips.

Follow-up release hardening:

- Add SBOM generation.
- Add Docker provenance attestations.
- Add image signing with `cosign`.
- Add vulnerability scanning with Trivy or equivalent.

## Consequences

Positive:

- Docker images are tied to the same source and artifacts as the release.
- Docker publishing is gated by successful Maven staging.
- JVM and native images have clear tags and explicit compatibility expectations.
- Multi-arch support is first-class for both AMD64 and ARM64.
- The compose zip gives users a Yaci Store-like operational experience.
- DockerHub image naming is consistent with other Bloxbean projects.

Tradeoffs:

- Native Docker release requires Linux ARM64 build capacity.
- Release workflow becomes longer because native-image builds are expensive.
- The compose distribution adds another artifact to keep in sync with runtime config changes.
- Native image operational parity must be validated before making native the default image.

## Implementation Plan

1. Add artifact-based `docker/jvm/Dockerfile`.
2. Add artifact-based `docker/native/Dockerfile`.
3. Add shared entrypoint scripts if direct Dockerfile `ENTRYPOINT` cannot cleanly support env expansion.
4. Add compose files and env files under `docker/compose` and `docker/config`.
5. Add `yano.sh` and `yano.bat` compose launcher scripts.
6. Add `:app:yanoDockerDistZip`.
7. Add `.github/workflows/release-docker.yml` as a reusable workflow with manual dispatch fallback.
8. Update `.github/workflows/release.yml` to expose the Gradle version and call the Docker release workflow with `needs: publish`.
9. Add smoke tests to the Docker workflow.
10. Leave the current `app/Dockerfile` unchanged for compatibility.
11. Document local build, release, and compose usage in `docker/compose/README.md`.

## Open Questions

- Should compose provide separate files for relay mode and devnet mode, or one parameterized compose file plus examples?

## References

- Yaci Store release Docker workflow: `/Users/satya/work/bloxbean/yaci-store/.github/workflows/release.yml`
- Yaci Store compose zip workflow: `/Users/satya/work/bloxbean/yaci-store/.github/workflows/release-compose-zip.yml`
- Yaci Store compose env and services: `/Users/satya/work/bloxbean/yaci-store/docker/compose`
- Existing Yano JVM distribution task: `app/build.gradle`
- Existing Yano native distribution task: `app/build.gradle`
- Existing legacy Yano Dockerfile to leave unchanged: `app/Dockerfile`
- GitHub-hosted runner labels for Linux ARM64: `ubuntu-24.04-arm`, `ubuntu-22.04-arm` in GitHub Actions runner documentation: https://docs.github.com/en/actions/reference/runners/github-hosted-runners
