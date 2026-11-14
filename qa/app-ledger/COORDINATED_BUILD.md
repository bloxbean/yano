# Reproducing the coordinated draft without an intermediate release

Build each repository independently and exchange Maven artifacts. None of the
Gradle builds invokes another checkout. This recipe covers the implemented
framework extraction; it does not replace final release qualification.

Use Java 25, the three linked PR branches and **new empty staging directories**.
Choose exact candidate versions for Yaci, node API, Host API and X; do not reuse a
released coordinate or overwrite a previous candidate's bytes. The placeholders
below are explicit inputs, not environment-dependent automatic discovery.

1. In the Yaci PR #245 checkout (base `next`, where protocols 100/103 already
   exist), stage its core artifact:

   ```sh
   ./gradlew :core:publishAllPublicationsToStagingRepository \
     -Pversion=YACI_VERSION -PskipSigning=true \
     -PstagingRepository=/absolute/yaci-stage --no-parallel
   ```

2. In the Yano PR #246 checkout (base `main`), stage only the foundational APIs.
   These tasks do not need X artifacts:

   ```sh
   ./gradlew :host-api:publishAllPublicationsToStagingRepository \
     :node-api:publishAllPublicationsToStagingRepository \
     :cardano-api:publishAllPublicationsToStagingRepository \
     -Pversion=NODE_API_VERSION -PhostApiVersion=HOST_API_VERSION \
     -PskipSigning=true -PstagingRepository=/absolute/host-stage \
     -I qa/app-ledger/artifact-inputs.gradle \
     -PappLedgerYaciRepository=file:///absolute/yaci-stage \
     -PappLedgerYaciVersion=YACI_VERSION --no-parallel
   ```

3. In the X PR #102 checkout (base `main`), test and stage the framework against
   those external API artifacts. Host API and X versions need not match:

   ```sh
   ./gradlew frameworkCheck :sdk:proof-contracts:test \
     :sdk:proof-contracts:publishAllPublicationsToInternalRepository \
     :framework:api:publishAllPublicationsToInternalRepository \
     :framework:cardano-api:publishAllPublicationsToInternalRepository \
     :framework:config:publishAllPublicationsToInternalRepository \
     :framework:core:publishAllPublicationsToInternalRepository \
     :framework:cardano:publishAllPublicationsToInternalRepository \
     :framework:cardano-onchain:publishAllPublicationsToInternalRepository \
     :framework:host-adapter:publishAllPublicationsToInternalRepository \
     :framework:standalone:publishAllPublicationsToInternalRepository \
     -Pversion=FRAMEWORK_VERSION -PnodeApiVersion=NODE_API_VERSION \
     -PhostApiVersion=HOST_API_VERSION \
     -PyanoRepository=file:///absolute/host-stage \
     -PinternalRepository=/absolute/framework-stage -PskipSigning=true \
     -I qa/framework/artifact-inputs.gradle \
     -PappLedgerYaciRepository=file:///absolute/yaci-stage \
     -PappLedgerYaciVersion=YACI_VERSION --no-parallel
   ```

4. Back in Yano, use the [harness command](README.md#exact-coordinated-inputs)
   with these exact Yaci/framework repositories and versions. Build
   `:app:quarkusBuild` with the same `-I`/`-P` arguments and run the packaged
   three-process gate. Retain the source revision, Maven inventories/hashes,
   JUnit/ABI reports and packaged JAR digest together.

The pre19 compatibility fixture is a separate frozen input, not the just-built
Host API. Its checksum is mandatory; see the harness README for the explicit
baseline-file option. Do not substitute a freshly compiled API as the old side
of the ABI check.

Exact development candidate versions and artifact hashes are recorded in the
[Phase 2 validation record](PHASE2_VALIDATION.md) and
[review follow-up record](REVIEW_FOLLOWUP.md), and
[concrete API ownership validation](CORE_API_REMOVAL_VALIDATION.md). They are unpublished candidates in
isolated local repositories, not remotely resolvable releases.
The final coordinated release must pin available upstream artifacts and freeze
its supported host baseline. No release tag or public publication is needed to
perform these incremental checks.

For the independent host matrix, stage the thin host modules after framework
publication: `appchain-config`, `consensus`, `p2p`, `ledger-rules`,
`ledger-state`, `plugin-catalog` and `runtime`. Use the same exact framework
input arguments as the harness, a new `-Pversion=HOST_VERSION` and the existing
`-PhostApiVersion=HOST_API_VERSION`. Publish `node-api` and `cardano-api` for that
host version as well. Do not republish an unchanged Host API coordinate.
The current host declares its concrete node, Cardano and Host API dependencies;
there is no current `yano-core-api` aggregate to stage. Keep the historical pre19
aggregate only as the frozen ABI/compiled-client baseline. The artifact-input
gate rejects obsolete aggregate dependencies in current runtime artifacts and
generated publication POMs.

Run X's `qa/framework/host-matrix/run.py` using its documented `prepare` and
`run` steps, with both `standalone` and `cardano-construction` profiles. Freeze
only the first supported host's fixture; later framework releases use `run`
without recompilation. The frozen pre19 client and the first Host API host are
different baselines with different purposes.

After both PRs are integrated and public dependency versions are pinned, perform
one final `bash qa/release-qa.sh --build` cycle on that coordinated Yano release
candidate. Use the existing release-QA preflight and sequential orchestration;
retain its L1 JVM/native, app-chain, endpoint, SDK compatibility and Docker
reports alongside the standalone package and oldest/current host matrix reports.
No release is made between implementation phases.
