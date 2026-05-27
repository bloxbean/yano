# Build Yano Distributions

This guide builds Yano release-style distributions from the checked-out source tree.

Run commands from the repository root.

## Prerequisites

- JDK 25.
- Docker with the Compose plugin for Docker distributions and container native builds.
- GraalVM 25 with `native-image` support for host native builds.

Use `-PskipSigning=true` for local builds that do not publish artifacts.

## JVM Zip Distribution

Build the JVM zip:

```bash
./gradlew :app:yanoDistZip -PskipSigning=true
```

Output:

```text
app/build/distributions/yano-<version>.zip
```

The zip contains `yano.jar`, `yano.sh`, config files, network genesis files, and plugin directory scaffolding.

## Native Zip Distribution

Build a native zip for the current host platform:

```bash
./gradlew :app:yanoNativeDistZip \
  -Dquarkus.native.enabled=true \
  -Dquarkus.package.jar.enabled=false \
  -PskipSigning=true
```

Output:

```text
app/build/distributions/yano-native-<version>-<platform>.zip
```

Examples:

```text
yano-native-0.1.0-pre4-macos-arm64.zip
yano-native-0.1.0-pre4-linux-x64.zip
yano-native-0.1.0-pre4-linux-arm64.zip
```

The zip contains the native `yano` executable, `yano.sh`, config files, network genesis files, and plugin directory scaffolding.

## Linux Native Zip From macOS

For a Linux native binary from macOS, use Quarkus container native build. This is useful when preparing a Linux Docker native context locally:

```bash
./gradlew :app:yanoNativeDistZip \
  -Dquarkus.native.enabled=true \
  -Dquarkus.package.jar.enabled=false \
  -Dquarkus.native.container-build=true \
  -Dquarkus.native.builder-image=container-registry.oracle.com/graalvm/native-image:25 \
  -PskipSigning=true
```

When Oracle's `native-image` builder is selected, Gradle adds `--gc=G1` and sets the container workdir to `/project`.

## Docker Compose Zip Distribution

Build the Docker compose zip:

```bash
./gradlew :app:yanoDockerDistZip \
  -PyanoDockerReleaseVersion=0.1.0-pre4 \
  -PyanoDockerImageTag=0.1.0-pre4 \
  -PskipSigning=true
```

Output:

```text
app/build/distributions/yano-docker-0.1.0-pre4.zip
```

For local Docker image testing, use a local image tag:

```bash
./gradlew :app:yanoDockerDistZip \
  -PyanoDockerReleaseVersion=0.1.0-pre4 \
  -PyanoDockerImageTag=local \
  -PskipSigning=true
```

The compose zip contains `yano.sh`, `yano.bat`, compose files, editable `config/application.yml`, editable `config/network`, network-specific chainstate directories, `logs`, and `plugins`.

## Docker Images

Docker images are built from Gradle-prepared artifact contexts:

```bash
./gradlew :app:prepareYanoDockerJvmContext -PskipSigning=true
```

```bash
./gradlew :app:prepareYanoDockerNativeContext \
  -Dquarkus.native.enabled=true \
  -Dquarkus.package.jar.enabled=false \
  -Dquarkus.native.container-build=true \
  -Dquarkus.native.builder-image=container-registry.oracle.com/graalvm/native-image:25 \
  -PskipSigning=true
```

See `docker/BUILD_FROM_SOURCE.md` for full Docker image build and smoke-test commands.

## Smoke Tests

Check zip contents:

```bash
unzip -l app/build/distributions/yano-*.zip | head
unzip -l app/build/distributions/yano-native-*.zip | head
unzip -l app/build/distributions/yano-docker-*.zip | head
```

Run JVM distribution:

```bash
unzip app/build/distributions/yano-<version>.zip -d /tmp/yano-jvm
cd /tmp/yano-jvm/yano-<version>
YANO_AUTO_SYNC_START=false ./yano.sh start
```

Run native distribution:

```bash
unzip app/build/distributions/yano-native-<version>-<platform>.zip -d /tmp/yano-native
cd /tmp/yano-native/yano-native-<version>-<platform>
YANO_AUTO_SYNC_START=false ./yano.sh start
```

Run Docker compose distribution with local images:

```bash
unzip app/build/distributions/yano-docker-<version>.zip -d /tmp/yano-docker
cd /tmp/yano-docker/yano-docker-<version>
./yano.sh config
./yano.sh start
curl -fsS http://localhost:7070/q/health/ready
./yano.sh stop
```
