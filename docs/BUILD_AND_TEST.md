# Building and testing Yano

Yano uses layered verification so the normal development build keeps complete
L1 and retained app-chain host/OrderedLog coverage without also running every
multi-node, packaged-runtime, or native-image acceptance test. Optional state
machines, connectors, products, and their cryptographic suites are built in
[Yano X](https://github.com/bloxbean/yano-x).

## Requirements

- JDK 25
- The repository Gradle wrapper (`./gradlew`)
- `bash`, `curl`, `jq`, and `unzip` for packaged distribution acceptance

## Verification tiers

| Tier | Source location | Purpose | Included in `build` |
|---|---|---|---|
| Core | `src/test` | L1 tests plus retained app-chain host, OrderedLog, proof, and plugin-boundary tests | Yes |
| Extended | `src/integrationTest` | Retained multi-node sequencing, catch-up, membership, anchoring, and host integration | No |
| Distribution | packaged process contracts | Tests the lean JVM/native archives and plugin-directory boundary | No |

Tests are classified by behavior, not by class name. A test that starts several
app-chain nodes belongs in `src/integrationTest` even when it validates retained
host behavior.

## Normal development build

Run the default build for normal changes:

```bash
./gradlew build -PskipSigning=true
```

This compiles every retained module and runs ordinary L1 and app-chain core
tests. It intentionally does not run extended multi-node or distribution suites.

For a faster module-level iteration:

```bash
./gradlew :runtime:test
./gradlew :appchain-testkit:test
./gradlew :appchain-proof-verifier:test
```

Run one ordinary test or method with Gradle's standard filter:

```bash
./gradlew :runtime:test \
  --tests 'com.bloxbean.cardano.yano.runtime.appchain.AppChainTwoNodeSmokeTest'

./gradlew :runtime:test \
  --tests 'com.bloxbean.cardano.yano.runtime.appchain.AppChainTwoNodeSmokeTest.twoNodes_exchangeAuthenticatedMessages_bothDirections'
```

## Extended tests

Run every retained integration suite, or select a module/test:

```bash
./gradlew extendedTest
./gradlew :runtime:integrationTest

./gradlew :runtime:integrationTest \
  --tests 'com.bloxbean.cardano.yano.runtime.appchain.GovernedMembershipIntegrationTest'
```

## Distribution verification

Run the lean JVM distribution and directory-plugin boundary gate:

```bash
./gradlew distributionCheck -PskipSigning=true
```

It builds the ordinary Yano JVM ZIP and verifies:

- the core distribution manifest and OrderedLog-only built-in boundary;
- packaged catalog integrity and directory-loaded conformance plugins;
- absence of Yano X bundles and downstream source/task dependencies; and
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
retain their dedicated commands and opt-in requirements. Yano X owns connector,
product, showcase, and eUTxO/ZK gates.

## Continuous integration

Pull-request verification runs the retained core build, integration tests,
distribution checks, and GraalVM gates independently. Yano X has its own JVM-only
extension and release-acceptance workflow. Release workflows use the corresponding
repository-owned gates before publishing.

## Performance and diagnostics

Gradle build caching is enabled in `gradle.properties`. Use the standard
repository-wide build command so dependency-alignment gates retain the Gradle
project locks they require:

```bash
./gradlew build -PskipSigning=true
```

Do not add Gradle's `--parallel` flag to a repository-wide build. The existing
ADR-013 alignment gates deliberately inspect several projects' dependency
graphs and Gradle 9 rejects that cross-project resolution under project
parallelism. CI obtains safe concurrency by running the core, extended, crypto,
and distribution tiers as independent jobs.

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
