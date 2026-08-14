# ADR-030 Phase C published-contract evidence

**Status:** Implemented on `feat/030-phase-c-published-contracts` in both repositories; commit and
merge require maintainer approval.

**Integration branch:** `feat/030_yano-x_reafactoring`

**Yano X workspace:** `/Users/satya/work/bloxbean/yano-x`

## Outcome

Phase C establishes a one-way, published boundary without inventing a second Yano packaging
mechanism:

- Yano's existing `publishToMavenLocal` and `:app:yanoDistZip` tasks provide local-development
  inputs. The same tasks remain the basis for staged and release publication.
- The retained `yano-bom` aligns the supported host/API/runtime/config/proof/testkit artifacts.
- Yano X has its own Gradle wrapper, group/version, `yano-x-bom`, and hierarchical
  `:distribution:jvm` assembly boundary.
- Yano X enables `mavenLocal()` only with `-PuseMavenLocal=true`. It receives the existing Yano JVM
  ZIP only through `-PyanoJvmDist`; there is no sibling checkout path, composite build, Yano task
  invocation, or copied application source.
- `verifyPublishedContracts` requires one exact Yano version across the resolved Maven graph, ZIP
  root, and the embedded `yano.jar` `Implementation-Version`. Its report also records the ZIP
  SHA-256 and resolved Yano components.
- Plain build discovery such as `./gradlew help` and `./gradlew projects` does not require Yano input
  properties. Contract generation and verification fail with explicit messages when an input is
  missing.

The full settings file intentionally uses readable `yanoModules` and `yanoXModules` maps. Normal
full builds include both maps; `-PleanYanoBuild=true` excludes the Yano X group. The allocation JSON
remains the independently verified ownership and extraction source of truth rather than executable
settings code.

## Published input exercised

Yano version:
`0.1.0-pre12-appchain-03100b3-SNAPSHOT`

JVM distribution:
`yano-0.1.0-pre12-appchain-03100b3.zip`

Distribution SHA-256:
`9edcaca91514d723a043a192f95329efc12f223832acf96d036d253428970963`

The generated Yano X verification report resolved 11 Yano components. Every component used the
requested version; the ZIP root was `yano-0.1.0-pre12-appchain-03100b3`, and the embedded runtime
manifest used the exact snapshot version above.

## Regression and contract results

| Gate | Result | Evidence |
|---|---|---|
| Lean Yano unit suite and allocation verification | PASS | 23 projects; 109 tasks; 23s |
| Lean host dependency boundary | PASS | 18 retained runtime projects; no Yano X project edge |
| Full monorepo unit suite and allocation verification | PASS | 75 projects; 334 tasks; 1m37s |
| Local Yano Maven publication plus existing JVM ZIP | PASS | 194 tasks; 41s; fixture publication skipped |
| Yano X project discovery without input properties | PASS | Hierarchy contains `:bom` and `:distribution:jvm` |
| Matching Yano Maven graph and JVM ZIP | PASS | 11 aligned components plus root and embedded-manifest identity |
| Older JVM ZIP with current Maven version | EXPECTED FAIL | Rejected `df6cfa9` root against expected `03100b3` |
| Missing `yanoJvmDist` | EXPECTED FAIL | Explicit `yanoJvmDist is required` failure |
| Offline build without `useMavenLocal=true` | EXPECTED FAIL | Locally published Yano artifacts were not visible |
| Yano and Yano X `diff --check` | PASS | No whitespace errors |

The local publication run exposed a latent Gradle task-validation problem in the non-published
native plugin-conformance fixture. Its generated version source was already an input to compilation
but not to `sourceJar`. `sourceJar` now depends on the generator; the fixture's Maven publish task
continues to be skipped, so it has not become a supported artifact accidentally.

## Remaining release gate

No internal/staging Maven endpoint is configured during the local refactoring campaign. Therefore
the clean repository-only staged-artifact run in Phase C step 7 remains conditional on that endpoint
becoming available and is mandatory before the first public Yano X release (also exercised by Phase
F). This does not introduce a sibling-build fallback: local development is explicitly Maven-local,
and release CI must use the staged repository with Maven-local disabled.

No source modules have moved in Phase C. Physical history-preserving extraction remains Phase E.
