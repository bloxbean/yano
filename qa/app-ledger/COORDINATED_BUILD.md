# Reproducing the coordinated draft without an intermediate release

Build each repository independently and exchange Maven artifacts. None of the
Gradle builds invokes another checkout. This recipe covers the implemented
milestones; it does not replace the remaining adapter/distribution work or final
release qualification.

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

The current development evidence used Yaci `0.5.0-pre15-app-only-245`, node API
`0.1.0-245.3`, Host API `1.0.0-245.3`, and framework `0.1.0-245.4` in isolated local
repositories. They are unpublished candidates, not remotely resolvable releases.
The final coordinated release must pin available upstream artifacts and freeze
its supported host baseline. No release tag or public publication is needed to
perform these incremental checks.
