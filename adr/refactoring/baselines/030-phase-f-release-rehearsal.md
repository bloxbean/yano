# ADR-030 Phase F independent release rehearsal evidence

**Status:** Completed and merged. Yano feature commit `67a30238` was merged at `f7433138`; Yano X
feature commit `387dc2c9` was merged to `main` at `7d77721e`.

**Yano integration branch:** `feat/030_yano-x_reafactoring`

**Yano X integration branch:** `main`

**Rehearsal version:** `0.1.0-pre12-appchain-b6d0b82-SNAPSHOT`

## Isolated release inputs

- Yano used its ordinary Maven publication and `:app:yanoDistZip` tasks. No new base-ZIP mechanism
  or release was introduced.
- The rehearsal published 22 Yano coordinates to an isolated file-backed staging repository.
- Yano X published 69 coordinates to a separate isolated repository: 50 thin/library artifacts,
  18 dependency-complete runtime bundles, and the Yano X BOM.
- Maven Local was disabled for release gates. The final Yano X build received the staged Yano
  repository, exact Yano version, and exact JVM ZIP as explicit inputs.
- A source-export checkout with no `.git`, no sibling `yano` directory, no IDE state, and a fresh
  Gradle cache completed `clean check distributionCheck`. It then rebuilt the final distributions
  against the refreshed Yano staging input.

## Distribution outcome

The JVM distribution selection is explicit in `config/jvm-distribution-v1.json`:

- 17 conflict-free bundles are installed under `plugins/` by default;
- the alternative eUTxO ZK runtime bundle is shipped under `optional-plugins/`; and
- all 18 bundles remain published and catalog-visible.

The default-set gate rejects missing bundle dependencies and duplicate contribution identities.
The packaged manifest, directory-loaded runtime catalog, bundle digests, and contribution identities
must agree. The acceptance suite also constructs the supported alternative eUTxO ZK selection after
removing the standard eUTxO bundle and its dependent bridge.

Both Yano distributions include `LICENSE` and `sbom/yano.cdx.json`. The Yano X plugin pack includes
`LICENSE` and `sbom/yano-x.cdx.json`; the combined JVM ZIP preserves the base license as
`LICENSE.yano` and includes both SBOMs. Snapshot bundle filenames inside archives are canonical and
do not expose timestamped Maven repository filenames.

## Regression and release-gate results

| Gate | Result | Evidence |
|---|---|---|
| Clean Yano build, all tests, allocation audit, lean-host dependency audit | PASS | 24 projects; 153 tasks; 8m07s |
| Yano staging publication and JVM distribution verification | PASS | 22 staged coordinates; 232 tasks; 57s |
| Final Yano GraalVM native build, native ZIP verification, native catalog process smoke | PASS | Oracle GraalVM 25.0.2; 95 tasks; 2m05s |
| Native devnet epoch crossing | PASS | Isolated ports 7170/14337; epochs 0 -> 1 -> 2; no runtime errors |
| Yano X clean no-sibling/no-Maven-Local build | PASS | 409 tasks; 354 executed from scratch; 6m57s |
| Final Yano X release-candidate acceptance with refreshed staged dependencies | PASS | 349 tasks; 126 executed; 8m08s |
| Packaged vs directory-loaded catalog identity | PASS | 17 default bundles; 1 optional bundle; representative stdlib, Kafka, evidence, and eUTxO identities |
| Final runtime and stock outcomes | PASS | activation, drift, proof, restart, secret, audit, registry, approval, and effect gates |
| Final source-export distribution rebuild | PASS | 137 tasks; final archives byte-identical |
| Jackson and SLF4J convergence, docs and artifact boundaries | PASS | Release-candidate static check |

## SBOM and license evidence

- Yano CycloneDX 1.6 SBOM: 460 components, zero external components without license metadata.
- Yano X CycloneDX 1.6 SBOM: 287 components, zero external components without license metadata.
- Serial numbers and build timestamps are removed from release SBOMs.
- First-party license and VCS fields are recreated in deterministic order with the authoritative
  repository URL, so Git checkouts and source exports produce identical SBOM bytes.
- Yano has one exact external license override for
  `javax.annotation:javax.annotation-api:1.3.2`; unused or shadowed overrides fail the build.

## Final artifact hashes

| Artifact | SHA-256 |
|---|---|
| `yano-0.1.0-pre12-appchain-b6d0b82.zip` | `33c7438af608f461fc27a4a8d2d3331de6aba29e1f7a40807ef795610bcb5e1d` |
| `yano-native-0.1.0-pre12-appchain-b6d0b82-macos-arm64.zip` | `65919e1bf057d253e100231c7475b2b20bc14d15f2dd7d711ae8bea946bebae1` |
| `yano-x-plugin-pack-0.1.0-pre12-appchain-b6d0b82.zip` | `3d2b055caa7e869eecc4d288fc481d714214d617b5162bbbc8745b94f6d530ef` |
| `yano-x-jvm-0.1.0-pre12-appchain-b6d0b82.zip` | `23ba4e4fc1a05377b3cfa39bf3667f7795d24c01ff17f844d343d208146a4905` |

The two Yano X hashes were reproduced byte-for-byte in the Git working tree and the independent
source-export build using the same staged inputs.

## Defects and prerelease debt removed during rehearsal

- A public Julc prerelease mismatch hidden by mutated Maven-local artifacts was removed by updating
  to public `0.1.0-pre16`, pinning the ZeroJ/on-chain runtime dependencies, and selecting PV11
  explicitly in the DropList conformance test.
- Standard eUTxO and eUTxO ZK both contributed `app-state-machine/eutxo-ledger`. The ZK runtime is
  now an explicit optional alternative rather than an invalid second active default.
- The combined ZIP placed the cluster launcher under a stale `examples/` path while the retained
  Yano launcher expects root `appchain-cluster/`. The archive layout and verifier now enforce the
  executable root path.
- The eUTxO demo emitter still wrote generated validator scripts to the removed
  `examples/appchain-showcase` source path. It now targets `examples/showcase`, and the duplicate
  generated tree is gone.
- Yano declared MIT in publication metadata but had no repository/distribution license file. The
  MIT license is now present and verified in JVM and native archives.
- Release SBOMs previously inherited checkout-dependent VCS fields and map ordering. Normalization
  now makes Git and source-export archives reproducible while retaining license and VCS provenance.
- Timestamped Maven snapshot filenames are no longer leaked into the plugin pack or combined ZIP.
- The unreleased `yaci.plugins.*` compatibility namespace and its conflict-resolution path were
  removed. Runtime configuration now accepts only the canonical `yano.plugins.*` keys.

No Phase F technical-debt deferral or prerelease compatibility alias was introduced.
