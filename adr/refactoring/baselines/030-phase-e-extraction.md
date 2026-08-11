# ADR-030 Phase E extraction evidence

**Status:** Implemented on `feat/030-phase-e-extraction` in both repositories; commit and merge
require maintainer approval.

**Integration branch:** `feat/030_yano-x_reafactoring`

**Yano X workspace:** `/Users/satya/work/bloxbean/yano-x`

## Source and history provenance

The source boundary was frozen before either working repository was cut over:

| Identity | Value |
|---|---|
| Agreed source commit | `162558945b3529619e6cda19cb6a10da9c029637` |
| Pre-extraction tag | `adr-030-pre-extraction-20260811` |
| Recovery branch | `recovery/adr-030-pre-extraction` |
| Filtered source commit | `1008faded33b743cee94a895ed88c354d954a1a3` |
| Imported Yano X history ref | `history/adr030-yano-source` |
| Extraction tool | `git-filter-repo` in a temporary clone |

Yano X checks the same mapping from `config/source-extraction-v1.json`; extraction did not rewrite
the active Yano repository. The filtered history contains the paths selected by the checked-in
allocation manifest and remains reachable independently of the Phase E merge commit.

## Outcome

- All 52 downstream modules now live in their final hierarchical Yano X folders. Their Java package
  domain remains `com.bloxbean.cardano.yano.appchain.*`, while publications use the final
  `yano-x-*` artifact ids.
- The 18 runtime extensions remain dependency-complete plugin bundles. Yano X's plugin pack and JVM
  distribution resolve them by published Maven coordinates; no sibling-project task or source path
  is used.
- Yano retains 24 configured projects, 18 runtime projects, and 1,841 tracked allocation paths. Its
  lean host has no downstream project edge and retains OrderedLog as its only built-in application
  state machine.
- The moved source, product deployment scripts, product documentation, product ADRs, examples, and
  tools were removed from Yano. Rebuildable ignored outputs left below moved paths were also removed,
  so the old source folders do not survive as misleading empty or generated trees.
- Yano X owns its Gradle settings, JVM distribution, extension CI, release metadata, product docs,
  and copied boundary ADRs. Yano owns its core/native CI and core/operator documentation.
- Yano X CI uses a build-scoped Maven repository for its own publications and accepts released or
  staged Yano coordinates and JVM ZIP input. Maven local remains an explicit local-development mode,
  not an implicit CI fallback.
- The source launcher and help text no longer discover moved monorepo scripts. They identify the
  separately installed Yano X distribution when an extension command is requested.

## Regression and contract results

Development version:

`0.1.0-pre12-appchain-1625589-SNAPSHOT`

| Gate | Result | Evidence |
|---|---|---|
| Clean Yano build, tests, allocation/dependency audits, JVM ZIP, Maven-local publication | PASS | 24 projects; 286 tasks; 1m17s |
| Yano GraalVM native ZIP, core distribution verifier, native plugin-catalog process smoke | PASS | Oracle GraalVM 25.0.2; 68 tasks; 2m37s |
| Post-cutover Yano app tests, allocation/dependency audits, JVM ZIP | PASS | 52s after dead build-logic cleanup |
| Yano X source-provenance and JVM-only metadata/task checks | PASS | 52 artifacts; 18 runtime bundles; no native task or native extension claim |
| Yano X isolated build-scoped publication repository | PASS | All publications expose the `internal` repository task graph |
| Full Yano X `check` and JVM distribution preparation | PASS | 50s after extraction-boundary cleanup |
| Yano X evidence release contracts | PASS | ADR-013 packaged release-contract suite |
| Final Yano X ZIP same-input rebuild | PASS | Plugin-pack and JVM ZIP SHA-256 values remained byte-identical |

| Artifact | SHA-256 |
|---|---|
| `yano-0.1.0-pre12-appchain-1625589.zip` | `2f6f0ae0c1e7274f3563c9d98bfac6786a72dfd20db3041cb70de5ad0c943afc` |
| `yano-native-0.1.0-pre12-appchain-1625589-macos-arm64.zip` | `3e181736be51d290a0ce812be05caa849a0a5b05c33a12b49a4493aa0e902f3d` |
| `yano-x-plugin-pack-0.1.0-pre12-appchain-1625589.zip` | `65b790573c520b7f006b77fda98d2a4cac4961ccbfc1c9b154724fa12a2b25cd` |
| `yano-x-jvm-0.1.0-pre12-appchain-1625589.zip` | `c42f5bc566611c3825184c4ff574026a9d176bee87b8ca09c147532248b6643f` |

## Prerelease debt removed

Phase E did not preserve unreleased compatibility or extraction-only seams:

- Removed obsolete native-support claims, native connector construction seams, native build
  commands, and duplicate native extension metadata from the JVM-only repository.
- Removed hidden parent-classpath assumptions from distribution evidence and made its representative
  Kafka, IPFS, S3, composite, stdlib, role-workflow, evidence-registry, and evidence-profile bundle
  closure explicit.
- Replaced legacy monorepo paths, task references, artifact names, and first-party trust-owner names
  with repository-owned Yano X identities.
- Removed dead nullable connector/native parameters and test-only connector branches from Yano's
  retained plugin-conformance assembly.
- Corrected the evidence client's stale pre-split Maven exclusion and made its published-POM boundary
  check distinguish direct dependencies from exclusions.
- Removed stale product/native CI coupling and added separate JVM-only commit, distribution,
  connector-fault, effect-failover, and release-acceptance jobs.

No Phase E technical-debt deferral or prerelease compatibility alias was introduced. A clean
staged-Yano, fresh-checkout rehearsal remains Phase F by design.
