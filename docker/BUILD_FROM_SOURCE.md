# Build Docker Images From Source

This guide builds Yano Docker images from the checked-out source tree. The release Dockerfiles are artifact-first: Gradle creates a Yano distribution, then Docker copies that distribution into a runtime image.

For jar, native, and Docker compose zip distributions, see `docs/BUILD_DISTRIBUTIONS.md`.

The legacy `app/Dockerfile` is not used by these commands.

## Prerequisites

- Docker with the Compose plugin.
- JDK 25 for JVM builds.
- GraalVM 25 with native-image support for native builds, or Docker container native build enabled.

Run commands from the repository root.

## JVM Image

Build the JVM Docker context:

```bash
./gradlew :app:prepareYanoDockerJvmContext -PskipSigning=true
```

Build the local image:

```bash
docker build \
  --build-arg JVM_BASE_IMAGE=eclipse-temurin:25-jre \
  -t bloxbean/yano:local-jvm \
  app/build/docker/jvm/context
```

Smoke test:

```bash
docker run --rm \
  -p 7070:7070 \
  -e YANO_AUTO_SYNC_START=false \
  -e YANO_PROFILE=preprod \
  bloxbean/yano:local-jvm
```

In another terminal:

```bash
curl -fsS http://localhost:7070/q/health/ready
```

## Native Image

Native Docker images require a Linux native binary. A macOS native binary cannot run in a Linux container.

On Linux with GraalVM installed:

```bash
./gradlew :app:prepareYanoDockerNativeContext \
  -Dquarkus.native.enabled=true \
  -Dquarkus.package.jar.enabled=false \
  -PskipSigning=true
```

On macOS, use Quarkus container native build so the output is a Linux ELF binary.

For release-parity local testing with Oracle GraalVM and G1 GC:

```bash
./gradlew :app:prepareYanoDockerNativeContext \
  -Dquarkus.native.enabled=true \
  -Dquarkus.package.jar.enabled=false \
  -Dquarkus.native.container-build=true \
  -Dquarkus.native.builder-image=container-registry.oracle.com/graalvm/native-image:25 \
  -PskipSigning=true
```

The Gradle build adds `--gc=G1` and sets the container workdir to `/project` for this Oracle image. The workdir is required because Oracle's `native-image` container defaults to `/app`, while Quarkus mounts the native-image source tree at `/project`.

For a Mandrel build, use the Quarkus Mandrel builder image. This uses the builder image default GC, which is normally Serial GC:

```bash
./gradlew :app:prepareYanoDockerNativeContext \
  -Dquarkus.native.enabled=true \
  -Dquarkus.package.jar.enabled=false \
  -Dquarkus.native.container-build=true \
  -Dquarkus.native.builder-image=quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 \
  -PskipSigning=true
```

Build the local native image:

```bash
docker build \
  -t bloxbean/yano:local-native \
  app/build/docker/native/context
```

Smoke test:

```bash
docker run --rm --entrypoint ldd bloxbean/yano:local-native /app/yano
```

```bash
docker run --rm \
  -p 7070:7070 \
  -e YANO_AUTO_SYNC_START=false \
  -e YANO_PROFILE=preprod \
  bloxbean/yano:local-native
```

In another terminal:

```bash
curl -fsS http://localhost:7070/q/health/ready
```

## Multi-Architecture Builds

The JVM image can be built as a multi-platform image from one JVM context:

```bash
./gradlew :app:prepareYanoDockerJvmContext -PskipSigning=true

docker buildx build \
  --platform linux/amd64,linux/arm64 \
  --build-arg JVM_BASE_IMAGE=eclipse-temurin:25-jre \
  -t bloxbean/yano:local-jvm \
  app/build/docker/jvm/context
```

Native images are architecture-specific. Build the native binary on the matching Linux architecture, then build/push that image for the same platform. The release workflow builds `linux/amd64` and `linux/arm64` native images on matching Linux runners and then creates the multi-arch manifest.

## Compose Distribution

Build a Docker Compose zip locally:

```bash
./gradlew :app:yanoDockerDistZip \
  -PyanoDockerReleaseVersion=0.1.0-pre4 \
  -PyanoDockerImageTag=0.1.0-pre4 \
  -PskipSigning=true
```

The zip is created under:

```text
app/build/distributions/yano-docker-0.1.0-pre4.zip
```

The generated `compose/.env` inside the zip pins:

```text
YANO_IMAGE_TAG=0.1.0-pre4
```

After extracting the zip:

```bash
./yano.sh config
./yano.sh start
```

The generated compose zip is release-oriented. If `YANO_IMAGE_TAG=0.1.0-pre4`, compose resolves the default JVM image as:

```text
bloxbean/yano:0.1.0-pre4-jvm
```

That image must already exist on DockerHub. For local source builds, edit `compose/.env` to use the local image tags you built above.

For the local JVM image `bloxbean/yano:local-jvm`:

```text
YANO_IMAGE=bloxbean/yano
YANO_IMAGE_TAG=local
YANO_IMAGE_FLAVOR=jvm
```

For the local native image `bloxbean/yano:local-native`:

```text
YANO_IMAGE=bloxbean/yano
YANO_IMAGE_TAG=local
YANO_IMAGE_FLAVOR=native
```

Check the resolved compose image before starting:

```bash
./yano.sh config | grep 'image:'
```

For another network, use the matching command:

```bash
./yano.sh config:mainnet | grep 'image:'
./yano.sh config:devnet | grep 'image:'
```

Each network uses its own chainstate directory by default, such as `chainstate-preprod`, `chainstate-mainnet`, and `chainstate-devnet`. The launcher creates the selected directory on `start` or `restart`. Set `YANO_CHAINSTATE_PATH` in `compose/.env` when you want a custom host path.

Switch to the native image by editing `compose/.env`:

```text
YANO_IMAGE_FLAVOR=native
```

Then restart:

```bash
./yano.sh restart
```

`JAVA_OPTS` applies only to the JVM image. For native-image runtime flags, use `YANO_EXTRA_ARGS`.

## Useful Cleanup

```bash
docker rm -f yano-default 2>/dev/null || true
docker image rm bloxbean/yano:local-jvm bloxbean/yano:local-native 2>/dev/null || true
```
