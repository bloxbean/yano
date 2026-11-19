---
title: "Building & testing"
description: "Run the build tiers and packaging checks."
sidebar:
  order: 2
---

Yano uses layered verification so the normal development build keeps complete
L1 coverage without also running every multi-node, packaged-runtime, or
native-image acceptance test. Application ledgers (app chains) and their
suites are built downstream in [Yano X](https://github.com/bloxbean/yano-x),
which depends on Yano; Yano never depends on Yano X.

## Requirements

- JDK 25
- The repository Gradle wrapper (`./gradlew`)
- `bash`, `curl`, `jq`, and `unzip` for packaged distribution acceptance

## Verification tiers

| Tier | Source location | Purpose | Included in `build` |
|---|---|---|---|
| Core | `src/test` | L1, host and plugin-boundary tests | Yes |
| Extended | `src/integrationTest` | Multi-process, external-service and benchmark suites | No |
| Distribution | packaged process contracts | Tests the lean JVM/native archives and plugin-directory boundary | No |

Tests are classified by behavior, not by class name. A test that starts several
nodes or external services belongs in `src/integrationTest`.

## Normal development build

Run the default build for normal changes:

```bash
./gradlew build -PskipSigning=true
```

This compiles every module and runs the core tests, including
`verifyNoYanoXDependency` (see below). It intentionally does not run extended
multi-node or distribution suites.

For a faster module-level iteration:

```bash
./gradlew :runtime:test
./gradlew :ledger-state:test
```

Run one ordinary test or method with Gradle's standard filter:

```bash
./gradlew :runtime:test \
  --tests 'org.yanoproject.runtime.HeaderSyncManagerTest'

./gradlew :runtime:test \
  --tests 'org.yanoproject.runtime.HeaderSyncManagerTest.epochBoundaryHoldPausesAndResumesHeaderSync'
```

## Dependency direction

`./gradlew verifyNoYanoXDependency` (part of `check`) fails when Yano requests,
declares, names or ships Yano X: any `org.yanoproject.x` group or `yano-x-*`
module in a plugin request or in any configuration of any project (buildscript
included), in generated POMs, module metadata and the BOM, as a token in
tracked source and build files, or inside existing build outputs
(`app/build/yano.jar`, the distribution ZIPs, the Docker build contexts, the
generated plugin index and native-image configuration). It finishes with a
negative probe that builds `gradle/no-yano-x-probe` and requires the guard to
reject it. The full violation list is written to
`build/reports/no-yano-x/violations.txt`; stale outputs from an older build are
reported too, so rebuild or clean them.

To prove that a clean checkout builds without any Yano X artifact, use a fresh
Gradle user home, so nothing comes from a cache, and keep Maven Local disabled
(the default; never pass `-PuseMavenLocal=true` here):

```bash
export GRADLE_USER_HOME="$(mktemp -d)"
./gradlew clean build :app:quarkusBuild verifyNoYanoXDependency -PskipSigning=true
```

## Extended tests

Run every retained integration suite, or select a module/test:

```bash
./gradlew extendedTest
./gradlew :archive-modules:archive-api:integrationTest

./gradlew :archive-modules:archive-api:integrationTest \
  --tests 'org.yanoproject.archive.benchmark.DuckLakeSnapshotConcurrencyTest'
```

## Distribution verification

Run the lean JVM distribution and directory-plugin boundary gate:

```bash
./gradlew distributionCheck -PskipSigning=true
```

It builds the ordinary Yano JVM ZIP and verifies:

- the core distribution manifest;
- packaged catalog integrity and directory-loaded conformance plugins;
- packaged launchers, config, plugin tools, and executable bits.

The gate uses temporary devnet data. It does not connect to Preview, Preprod,
or Mainnet and does not publish an artifact.

Individual distribution checks remain available:

```bash
./gradlew :app:verifyCoreJvmDistribution -PskipSigning=true
./gradlew :app:packagedJvmPluginCatalogSmoke -PskipSigning=true
```

Create archives without running the acceptance gate:

```bash
./gradlew :app:yanoDistZip -PskipSigning=true
./gradlew :app:yanoNativeDistZip -PskipSigning=true
```

## Full optional build

Run all retained repository core, integration, and JVM distribution tiers with one
command:

```bash
./gradlew fullBuild -PskipSigning=true
```

For a from-scratch release-style check:

```bash
./gradlew clean fullBuild -PskipSigning=true
```

`fullBuild` is intentionally not the normal inner-loop command. Network-specific
Haskell synchronization, native-image, and public-network acceptance workflows
retain their dedicated commands and opt-in requirements. Yano X owns the
app-chain, connector, product, showcase, and eUTxO/ZK gates.

## Continuous integration

Pull-request verification runs the retained core build, integration tests,
distribution checks, and GraalVM gates independently; the distribution and
native jobs also run `verifyNoYanoXDependency` over their outputs. Yano X has
its own JVM-only workflow that builds against published or staged Yano
artifacts (`.github/COORDINATED_INPUTS.md`). Release workflows use the corresponding
repository-owned gates before publishing.

## Performance and diagnostics

Gradle build caching is enabled in `gradle.properties`. Use the standard
repository-wide build command so dependency-alignment gates retain the Gradle
project locks they require:

```bash
./gradlew build -PskipSigning=true
```

Do not add Gradle's `--parallel` flag to a repository-wide build. The existing
Module alignment gates deliberately inspect several projects' dependency
graphs and Gradle 9 rejects that cross-project resolution under project
parallelism. CI obtains safe concurrency by running the core, extended, distribution,
and native-core tiers as independent jobs.

Test output defaults to failures and skips. Enable the former per-test and
standard-stream logging when diagnosing a failure:

```bash
./gradlew :runtime:test -PverboseTests=true
```

Use profiling when a tier becomes unexpectedly slow:

```bash
./gradlew build --profile --console=plain -PskipSigning=true
```

The HTML report is written under `build/reports/profile/`.
