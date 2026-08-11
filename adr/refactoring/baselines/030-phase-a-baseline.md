# ADR-030 Phase A boundary and regression baseline

**Status:** Passed on `feat/030-phase-a-boundary-baseline`; commit and merge require maintainer approval.
**Captured:** 2026-08-11
**Source commit:** `6d36400063fed2ac32f28f58728a7c550fabb329`
**Integration branch:** `feat/030_yano-x_reafactoring`

## Environment and comparison rules

- macOS 26.0.1 (`arm64`), OpenJDK 25.0.2 BellSoft, Gradle 9.4.1.
- Native gate: Oracle GraalVM 25.0.2 with `native-image` 25.0.2.
- Times are wall-clock `real` seconds from `/usr/bin/time -p` and are single observations.
- The machine had owner-managed Yano devnet/preprod processes running. They were not stopped or
  modified. The measurements characterize this Phase A workspace but are not controlled benchmark
  results.
- The cache-independent comparison point is `clean build --no-build-cache`. A future improvement
  claim must use the same command, Java/Gradle line, machine, and comparable background load. It
  should use the median of at least three runs in both repositories.
- Cache-warm timings may be compared only to cache-warm timings and must report executed, cached,
  and up-to-date task counts.
- E2Es use isolated temporary directories, ports, Compose project names, and cleanup paths. They do
  not reuse the retained operator clusters.

## Build and package baseline

| Gate | Result | Wall time | Evidence |
|---|---:|---:|---|
| `./gradlew clean build --no-build-cache -PskipSigning=true` | PASS | 690.61s | 496 actionable tasks: 493 executed, 3 up-to-date |
| `./gradlew clean build -PskipSigning=true` (warm cache) | PASS | 74.99s | 496 actionable tasks: 280 executed, 213 from cache, 3 up-to-date |
| `./gradlew build -PskipSigning=true` (final warm tree) | PASS | 69.95s | 424 actionable tasks |
| `./gradlew cleanTest test -PskipSigning=true` (warm cache) | PASS | 24.92s | All repository unit tasks |
| JVM ZIP, packaged JVM smoke, and showcase ZIP regeneration | PASS | 53.80s | 176 tasks; process-level plugin catalog smoke passed |
| `./gradlew build distributionCheck -PskipSigning=true` | PASS | 162.67s | 434 tasks; runtime, stock outcome, and self-contained showcase acceptance passed |

The two ZIPs below are build-instance identifiers, not reproducible-build promises; ZIP entry
timestamps can change their hashes between equivalent builds.

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| `yano-0.1.0-pre12-appchain-6d36400.zip` | 339,673,875 | `e8f89cb7f7323be77c83fb47838ca775df198ec71eaab3e229f49c8ae66e8716` |
| `yano-showcase-0.1.0-pre12-appchain-6d36400-SNAPSHOT.zip` | 799,908,024 | `9708751011d9579158cffa2a5d9a8d02cd4f847da0abac6acefcdd5799aa07a0` |

## Heavy app-chain regression inventory

| Suite | Result | Wall time / detail |
|---|---:|---|
| `appChainExtendedTest` | PASS | 143.71s; service-independent extended matrix. Real connector services are covered by the dedicated connector gate below. |
| `appChainCryptoTest` | PASS | 578.11s |
| `distributionCheck` | PASS | 102.92s isolated observation; also rerun with final `build distributionCheck` gate above |
| Native plugin conformance | PASS | 140.12s after P22/P23 fixes; native build plus governed composite and catalog process smokes |
| Connector fault matrix | PASS | 65.84s |
| Kafka TLS/SASL integration | PASS | 12.30s |
| Effect failover contracts | PASS | 4.59s |
| Effect failover live E2E | PASS | 241.18s |
| Deployment parity live E2E | PASS | 415.74s |
| Role workflow live E2E | PASS | 213.81s; includes authorization, proofs, recovery, and one-member restart/catch-up |

The machine-readable commands and the behavior protected by each gate are in
`config/repository-split/allocation-v1.json`. The root `check` lifecycle validates that inventory,
all included projects, path ownership, artifact ids, dispositions, and debt ownership.

## Phase A implementation and lessons

Phase A established these enforceable seams without moving source into Yano X:

- classified all 71 included Gradle projects, target module paths, artifact ids, publication types,
  plugin bundle ids, cross-cutting path groups, and owners;
- wired allocation validation into root `check` and CI;
- initialized `/Users/satya/work/bloxbean/yano-x` as an empty Git workspace without creating a
  source or task dependency on that absolute path;
- recorded nine regression suites and package fingerprints; and
- removed P14-P23 instead of preserving unreleased app-chain behavior through aliases or shims.

The test-first iterations exposed four general lessons for the next phases:

1. A manifest or test name is not evidence that a path is exercised. Connector scripts had to run
   the actual `integrationTest` source set and require clean JUnit evidence.
2. Generated deployments must pin the entire deterministic state identity. A profile name without
   fingerprint and genesis id is not a runnable multi-node fixture.
3. Read-only verification must preserve cluster state; it must not be made to look healthy by
   requiring an unrelated consensus-tip advance.
4. Native HTTP boundaries must parse and render stable wire views explicitly. JVM-only reflection
   hid both optional query binding and raw capability-manifest serialization defects.

## Remaining work at the Phase B boundary

P1-P13 remain intentionally assigned to later named phases in ADR-030. They are not compatibility
shims accepted as permanent debt. Phase B owns the host/product seam, real isolated plugin bundles,
client/testkit split, runtime-to-stdlib test edge, compatibility matrix, and canonical
`yano.plugins.*` configuration. P9 finishes in Phase C and the temporary UI/path classification P12
finishes at extraction in Phase E.

No source has been copied to Yano X in Phase A. Phase B may start only after this feature branch is
reviewed, committed, and merged into the integration branch.
