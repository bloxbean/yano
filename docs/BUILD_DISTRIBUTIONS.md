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

Verify the final uber-JAR, its merged catalog/manifests, and JVM directory
loading with the build-only conformance bundle:

```bash
./gradlew :app:packagedJvmPluginCatalogSmoke -PskipSigning=true
```

This task intentionally uses the default
`includeNativePluginConformanceFixture=false`: the fixture must be absent from
the application index so startup can prove it was selected from the external
plugin directory. The task starts an isolated one-member app chain and asserts
all six configured fixture products through chain-scoped status; the fixture's
adversarial TCCL handoff also proves those callbacks crossed catalog facades.

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

The zip contains the native `yano` executable, `yano.sh`, config files, and
network genesis files. It deliberately has no plugin directory: native images
cannot load JARs dynamically. Include first-party T3 providers before native
catalog/reflection generation with `-PincludeFirstPartyPluginBundles=true`, or
add another plugin project to the app build-time runtime classpath **and** its
bundle-id/project entry to `buildTimePluginBundles` in `app/build.gradle`.
Index generation is intentionally strict: it derives that bundle's actual
declared root-plus-dependencies closure from the app resolution graph and fails
an unmapped thin bundle instead of fingerprinting only its provider JAR. A
plugin must declare every executable dependency; the shared JVM loader cannot
discover undeclared reflective use of an unrelated app dependency.

After the native zip task finishes, verify the final executable that it copied
into the distribution:

```bash
./gradlew :app:nativePluginCatalogSmoke -PskipSigning=true
```

The smoke task regenerates the current packaged-JVM index and compares both its
byte SHA-256 and selected-catalog fingerprint with the native executable's
startup provenance record. This makes an executable built with different
plugin catalog inputs fail even if it starts and reports healthy; it is not a
digest of unrelated application code. Pass the same catalog properties to both
commands; for example, a binary built with
`-PincludeFirstPartyPluginBundles=true` must be smoked with that property too.
Use `-PyanoNativeBinary=<path>` only to verify another executable built from
the same catalog inputs. Release workflows always package first, smoke the
resulting `app/build/yano` (or `yano.exe`), and only then upload the zip; this
prevents a distribution-triggered native rebuild from replacing an executable
that was already tested.

Maintainers can additionally exercise native reachability for every typed
app-chain plugin SPI with the non-published conformance fixture:

```bash
./gradlew :app:quarkusBuild \
  -PincludeFirstPartyPluginBundles=true \
  -PincludeNativePluginConformanceFixture=true \
  -Dquarkus.native.enabled=true \
  -Dquarkus.package.jar.enabled=false \
  -PskipSigning=true

./gradlew :app:nativePluginCatalogSmoke \
  -PincludeFirstPartyPluginBundles=true \
  -PincludeNativePluginConformanceFixture=true \
  -PskipSigning=true
```

This property is a verification-only build input. Do not use the resulting
binary as a release artifact; the dedicated CI job neither publishes nor
packages it. The smoke starts an isolated one-member, no-peer app chain and
asserts the configured signer, state machine, sequencer, observer, finalized
sink, and effect executor through its structured status. It also retains the
catalog-provenance and ignored-directory-JAR checks.

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

The compose zip contains `yano.sh`, `yano.bat`, compose files, editable `config/application.yml`, editable `config/network`, `logs`, and `plugins`. Network-specific chainstate directories are created by the launcher on `start` or `restart`.

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
