# Building and testing Yano

Yano uses layered verification so the normal development build keeps complete
L1 and app-chain core coverage without also paying for every multi-node,
Groth16, and packaged-runtime acceptance test.

## Requirements

- JDK 25
- The repository Gradle wrapper (`./gradlew`)
- `bash`, `curl`, `jq`, and `unzip` for packaged distribution acceptance

## Verification tiers

| Tier | Source location | Purpose | Included in `build` |
|---|---|---|---|
| Core | `src/test` | L1 tests, app-chain unit/core tests, a two-node smoke test, and a small real ZK proof | Yes |
| Extended | `src/integrationTest` | Multi-node sequencing, catch-up, governed membership, profile governance, and opt-in real connectors | No |
| Crypto | `src/cryptoTest` | Full Groth16 proving, B16, ceremony interoperability, recovery proofs, and on-chain proof validation | No |
| Distribution | `src/distributionTest` and packaged shell contracts | Tests copied/extracted release archives and packaged runtime behavior | No |

Tests are classified by behavior, not by class name. An in-memory composition
test may remain in `src/test` even if its class name contains `IntegrationTest`.
Conversely, a test that starts several app-chain nodes belongs in
`src/integrationTest`.

## Normal development build

Run the default build for normal changes:

```bash
./gradlew build -PskipSigning=true
```

This compiles every module, runs all ordinary L1 tests, and runs all app-chain
core tests. It intentionally does not create or extract the final showcase ZIP,
run the complete proof matrix, or run the extended multi-node suites.

For a faster module-level iteration:

```bash
./gradlew :runtime:test
./gradlew :appchain-stdlib:test
./gradlew :appchain-eutxo-zk-zeroj:test
```

Run one ordinary test or method with Gradle's standard filter:

```bash
./gradlew :runtime:test \
  --tests 'com.bloxbean.cardano.yano.runtime.appchain.AppChainTwoNodeSmokeTest'

./gradlew :runtime:test \
  --tests 'com.bloxbean.cardano.yano.runtime.appchain.AppChainTwoNodeSmokeTest.twoNodes_exchangeAuthenticatedMessages_bothDirections'
```

## Extended app-chain tests

Run every extended app-chain suite:

```bash
./gradlew appChainExtendedTest
```

Run a module or individual extended test:

```bash
./gradlew :runtime:integrationTest

./gradlew :runtime:integrationTest \
  --tests 'com.bloxbean.cardano.yano.runtime.appchain.GovernedMembershipIntegrationTest'
```

Real Kafka, S3/RustFS, Kubo, and evidence-runner tests live in this tier but
remain skipped unless their existing opt-in system properties and services are
provided. The aggregate still runs the self-contained runtime and composite
cluster tests without external infrastructure.

To run every repository integration suite, including non-app-chain suites and
the Quarkus tag-based integration tests:

```bash
./gradlew extendedTest
```

## Full cryptographic verification

Run the complete EUTxO ZeroJ/Groth16 matrix:

```bash
./gradlew appChainCryptoTest
```

Run one crypto module or test:

```bash
./gradlew :appchain-eutxo-zk-onchain:cryptoTest

./gradlew :appchain-eutxo-zk-zeroj:cryptoTest \
  --tests 'com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj.EutxoJubjubBatchProofTest'
```

Crypto test tasks use a shared Gradle lock and one fork so they remain
serialized even when the rest of the build uses `--parallel`. This avoids
several 4 GiB proving JVMs competing at once.

## Distribution verification

Run the complete JVM distribution gate:

```bash
./gradlew distributionCheck -PskipSigning=true
```

It builds the Yano, app-chain CLI, and showcase ZIPs and then verifies:

- packaged files, schemas, metadata, documentation, launchers, and executable bits;
- app-chain CLI init, validation, rendering, migration, capabilities, and GitOps export;
- every advertised recipe/runtime/deployment combination;
- packaged and directory-plugin discovery;
- showcase archive portability and absence of checkout-specific paths;
- a two-node packaged devnet flow with finality, proofs, evidence, and restart catch-up;
- packaged audit-log, registry, approval, and effect outcomes.

The gate uses temporary devnet data. It does not connect to Preview, Preprod,
or Mainnet and does not publish an artifact.

Individual distribution checks remain available:

```bash
./gradlew :appchain-devtools:distributionTest -PskipSigning=true
./gradlew :app:packagedJvmPluginCatalogSmoke -PskipSigning=true
./gradlew :appchain-showcase:showcaseDistributionContract -PskipSigning=true
```

Create archives without running the acceptance gate:

```bash
./gradlew :app:yanoDistZip -PskipSigning=true
./gradlew :appchain-showcase:distZip -PskipSigning=true
```

## Full optional build

Run all repository core, integration, crypto, and distribution tiers with one
command:

```bash
./gradlew fullBuild -PskipSigning=true
```

For a from-scratch release-style check:

```bash
./gradlew clean fullBuild -PskipSigning=true
```

`fullBuild` is intentionally not the normal inner-loop command. Network-specific
Haskell synchronization, native-image, externally provisioned connector, and
public-network acceptance workflows retain their dedicated commands and opt-in
requirements.

## Continuous integration

Pull-request verification runs the core build, extended app-chain tests, crypto
tests, and distribution checks as separate jobs. They remain independent release
gates but execute concurrently, so CI wall time is determined primarily by the
slowest tier instead of the sum of all tiers. Nightly, manual snapshot, and Maven
release workflows use `clean fullBuild` before publishing.

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
