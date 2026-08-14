# ADR-030 Phase D distribution evidence

**Status:** Implemented and accepted on `feat/030-phase-d-distributions` in both
repositories; commit and merge require maintainer approval.

**Integration branch:** `feat/030_yano-x_reafactoring`

**Yano source commit:** `c9d09eb5`

**Yano X workspace:** `/Users/satya/work/bloxbean/yano-x`

## Outcome

Phase D separates the supported deployment products without adding another Yano
base-packaging mechanism:

- Yano's existing `:app:yanoDistZip` and `:app:yanoNativeDistZip` tasks now
  include a machine-readable `yano-distribution-v1.json` contract.
- The JVM contract identifies `core-jvm`, OrderedLog as the only built-in
  state machine, and plugin-directory support. The native contract identifies
  `core-native`, the same OrderedLog boundary, and no plugin-directory support.
- Yano's ordinary `publishToMavenLocal` task publishes the 52 artifacts
  allocated to Yano X under their final `yano-x-*` coordinates during the
  refactoring window.
- Each of the 18 runtime extensions has a thin library publication and a
  distinct dependency-complete `*-bundle` publication. The thin publication
  no longer carries a legacy `bundle` classifier.
- Yano X resolves those 18 bundle coordinates, validates their single plugin
  manifest and exact version, and produces a checksummed plugin-pack manifest.
- Yano X produces a reproducible plugin-pack ZIP and a batteries-included JVM
  ZIP by overlaying the validated bundles on the ordinary Yano JVM ZIP.
- Yano X has no native-image build or release tasks. Its distribution manifest
  records `nativeImageSupported: false`.
- `yano-x-bom` imports `yano-bom` and constrains 52 thin artifacts plus 18
  bundle artifacts (71 dependency-management entries in total).

## Development input exercised

Yano/Yano X version:

`0.1.0-pre12-appchain-c9d09eb-SNAPSHOT`

The inputs were published locally because no prerelease repository is available
during refactoring. The same existing publication and JVM ZIP mechanisms are
used for a staged/released version.

| Artifact | SHA-256 |
|---|---|
| `yano-0.1.0-pre12-appchain-c9d09eb.zip` | `83dde3b365a89722180a2aba1e174cff0448f3f86ef9c9695b8e51349c2714eb` |
| `yano-native-0.1.0-pre12-appchain-c9d09eb-macos-arm64.zip` | `a7ff9c1f358b62c9b85a4e9f4776b23df694a1a969a1a35f59cd4df9157f2306` |
| `yano-x-plugin-pack-0.1.0-pre12-appchain-c9d09eb.zip` | `7516ad55458d4c4ecbd4e5ca3995760177330f7c243d92dceb81e70e3e231f04` |
| `yano-x-jvm-0.1.0-pre12-appchain-c9d09eb.zip` | `0bbea20389b305baeadc00e40238e719c57c72850b1f6aa14364ad634cb0ab4d` |

Both Yano X SHA-256 values were unchanged after rebuilding all manifest and ZIP
tasks with `--rerun-tasks`.

## Regression and contract results

| Gate | Result | Evidence |
|---|---|---|
| Full Yano Maven-local publication and ordinary JVM ZIP | PASS | 75 projects; 691 tasks; 1m30s |
| Full Yano unit suite, allocation audit, and core JVM ZIP | PASS | 75 projects; 339 tasks; 1m48s with a temporary 2 GiB Gradle heap |
| Lean Yano unit suite, allocation audit, and core JVM ZIP | PASS | 23 projects; 116 tasks; no Yano X project configured |
| Yano allocation ownership | PASS | 75 projects; 3,264 tracked paths; 9 suites |
| Oracle GraalVM 25.0.2 native image and core-native ZIP | PASS | 58 tasks; 2m04s; native verifier passed |
| Native and packaged-JVM core catalog process smokes | PASS | native health/catalog fingerprint and packaged JVM catalog both passed |
| Yano X artifact inventory | PASS | 52 artifacts; 18 runtime bundles |
| Yano X published-input and JVM distribution check | PASS | 11 aligned Yano components; 18 checked bundles |
| Yano X plugin-pack ZIP content and embedded manifest | PASS | one root; exact 18-file set; packaged manifest equals generated input |
| Yano X JVM-only build contract | PASS | no native image, compile, or distribution tasks |
| Yano X BOM | PASS | 71 managed dependencies: 1 Yano BOM + 52 thin + 18 bundles |
| Older JVM ZIP with current Maven version | EXPECTED FAIL | rejected the `df6cfa9` root against `c9d09eb` |
| Offline Yano X build without `useMavenLocal=true` | EXPECTED FAIL | all unreleased bundle coordinates were unavailable |

The first full test attempt exhausted the repository's configured 512 MiB Gradle
daemon heap while entering the EUTXO end-to-end tests. No assertion failed. The
same complete command passed with a command-line-only 2 GiB heap; the user's
`gradle.properties` was not changed.

## Prerelease debt removed

Phase D did not preserve compatibility-only app-chain packaging debt:

- Removed the legacy bundle-classifier publication path and replaced it with
  separately addressable `*-bundle` artifacts.
- Removed the evidence demo runner's duplicate shaded-artifact declaration,
  retaining the component-provided `all` variant.
- Fixed stale Javadoc links/snippet syntax that prevented complete publication.
- Corrected the allocation of the pure EUTXO indexer core library and the two
  actual JDBC/ZK indexer runtime plugins.
- Made the showcase runtime plugin publishable under its allocated Yano X thin
  and bundle coordinates.
- Added exact distribution-manifest, plugin-set, root-layout, and JVM/native
  boundary checks so these choices cannot silently regress.

No Phase D technical-debt deferral or compatibility alias was introduced.
History-preserving source extraction remains Phase E; clean staged-repository
release rehearsal remains Phase F.
