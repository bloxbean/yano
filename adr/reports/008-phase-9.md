# ADR-NET-008 Phase 9 Implementation Report

## Scope

- Added an optional upstream `HeaderValidator` seam for Shelley and later
  headers.
- Added `validation.level=header-signature`; changed built-in header validation
  defaults to disabled (`none`).
- Added the body-validation scaffold with `body-level=none` as the only
  supported built-in body preset.
- Added `BodyValidator`, `BodyValidationContext`, `BodyValidationResult`,
  `BodyValidationPipeline`, `BodyValidatorFactory`, and
  `BodyValidationException`.
- Added `YanoAssembly.bodyValidation(...)` so library embedders can install a
  custom body validator before runtime construction.
- Added `yano.upstream.validation.level` and
  `yano.upstream.validation.body-level` to application YAML defaults and the
  sample config. Both are disabled by default.
- Implemented Shelley+ structural checks:
  - header CBOR envelope/header-body shape;
  - decoded field consistency with original header bytes;
  - block/header hash verification;
  - required issuer key, VRF material, body hash, KES signature, and
    operational-certificate material sizes.
- Implemented Shelley+ header signature checks:
  - KES signature over serialized header body;
  - KES period relative to op-cert period and max KES evolutions;
  - operational-certificate cold signature over
    `kesVkey || counter || kesPeriod`.
- Wired validation into canonical header sync and observer fan-in.
- Exposed validation level, accepted count, rejected count, latest rejection
  stage, and latest rejection reason through upstream status.
- Wired body validation into the Shelley+ `Block` body apply path before
  consensus events and canonical body storage. The built-in `none` validator
  preserves current behavior.
- Fixed the Quarkus compatibility adapter so explicit `yano.upstream.*`
  overrides, including `yano.upstream.validation.level`, are honored when peers
  still come from legacy `yano.remote.*` settings.

## Review

- Byron validation is intentionally unchanged in this phase.
- Body validation has no out-of-box checks yet. `body-level` supports only
  `none`, and unsupported values fail fast.
- Byron body validation is intentionally unchanged; the new body hook currently
  applies to Shelley and later `Block` bodies.
- `header-signature` does not enable `trust-policy=validated`; untrusted
  single-peer canonical adoption still needs quorum, consensus-lite, or full
  validation.
- Canonical peer rejection raises a header validation failure and triggers the
  existing peer-recovery path. Observer rejection drops the header before
  candidate fan-in.
- Canonical body rejection raises `BodyValidationException` before storage.
  The existing apply path remains fail-closed, but intentional body validation
  rejection no longer logs as a storage error stack.
- The validator accepts both wrapped chain-sync headers and raw Shelley header
  arrays so it can be reused by live sync, tests, and later body validation.
- Legacy remote compatibility remains intact: without explicit
  `yano.upstream.*` properties, the adapter still returns `null` and
  `YanoConfig.effectiveUpstream()` synthesizes the trusted-single peer exactly
  as before.

## Verification

- `ShelleyHeaderValidatorTest.structuralValidationAcceptsValidShelleyHeader`
- `ShelleyHeaderValidatorTest.headerSignatureValidationAcceptsValidKesAndOpCertSignatures`
- `ShelleyHeaderValidatorTest.structuralValidationRejectsHeaderHashMismatch`
- `ShelleyHeaderValidatorTest.headerSignatureValidationRejectsBadKesSignature`
- `ShelleyHeaderValidatorTest.headerSignatureValidationRejectsBadOperationalCertificateSignature`
- `UpstreamConfigTest.headerSignatureValidationLevelIsSupported`
- `UpstreamConfigTest.headerSignatureValidatedTrustPolicyStillFailsFast`
- `UpstreamConfigTest.validationDefaultsAreDisabled`
- `UpstreamConfigTest.unsupportedBodyValidationLevelFailsFast`
- `BodyFetchManagerSimpleTest.customBodyValidatorCanRejectBeforeStorage`
- `BodyValidationPipelineTest.pipelineRunsInOrderAndStopsOnFirstRejection`
- `BodyValidationPipelineTest.pipelineRejectsWhenValidatorThrows`
- `BodyValidationPipelineTest.contextDefensivelyCopiesBlockBytes`
- `YanoAssemblyTest.bodyValidationBuilderInstallsCustomValidator`
- `YanoAssemblyTest.unsupportedBodyValidationDefaultPresetFailsFast`
- `YanoProducerTest.upstreamValidationOverrideIsHonoredWithLegacyRemoteConfig`
- `./gradlew :runtime:compileJava :runtime:test --tests "com.bloxbean.cardano.yano.runtime.BodyFetchManagerSimpleTest.customBodyValidatorCanRejectBeforeStorage" --console=plain`
- `./gradlew :runtime:test --tests "com.bloxbean.cardano.yano.runtime.sync.validation.BodyValidationPipelineTest" --tests "com.bloxbean.cardano.yano.runtime.BodyFetchManagerSimpleTest.customBodyValidatorCanRejectBeforeStorage" --tests "com.bloxbean.cardano.yano.runtime.assembly.YanoAssemblyTest" --tests "com.bloxbean.cardano.yano.runtime.config.UpstreamConfigTest" :app:test --tests "com.bloxbean.cardano.yano.app.YanoProducerTest" --console=plain`
- `./gradlew :runtime:test --tests "com.bloxbean.cardano.yano.runtime.sync.validation.ShelleyHeaderValidatorTest" --tests "com.bloxbean.cardano.yano.runtime.config.UpstreamConfigTest" --console=plain`
- `./gradlew :app:test --tests "com.bloxbean.cardano.yano.app.YanoProducerTest" :runtime:test --tests "com.bloxbean.cardano.yano.runtime.sync.validation.ShelleyHeaderValidatorTest" --tests "com.bloxbean.cardano.yano.runtime.config.UpstreamConfigTest" --console=plain`
- `./gradlew :app:quarkusBuild --console=plain`
- `./gradlew :core-api:test :runtime:test :app:test :testkit:test --console=plain`
- `./gradlew :app:haskellSyncTest --console=plain`
- `git diff --check`

Live preprod smoke:

- Command shape: `java ... -Dyano.network=preprod
  -Dyano.remote.host=preprod-node.play.dev.cardano.org
  -Dyano.remote.port=3001
  -Dyano.upstream.validation.level=header-signature -jar app/build/yano.jar`.
- Local ports: HTTP `7118`, N2N `7119`.
- Sampled status: `upstreamValidationLevel=header-signature`,
  `upstreamValidationAcceptedHeaders=5455`,
  `upstreamValidationRejectedHeaders=0`,
  active peer `preprod-node.play.dev.cardano.org:3001`.
- Process was stopped cleanly after the status check.

Haskell sync:

- Final rerun after the body-validation scaffold and pipeline fix passed with
  dynamically allocated Yano n2n ports `62916` and `62984`, avoiding local port
  `3001`.
- Past-time-travel sync ended with exact Yano/cardano-node tip match at slot
  `4895`.
- Regular block-producer sync crossed the epoch boundary and ended with exact
  Yano/cardano-node tip match at slot `1200`.
