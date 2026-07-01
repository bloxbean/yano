# ADR-CONSENSUS-002 Phase 0-4 Implementation Report

Date: 2026-07-01

## Scope

This report covers implementation of ADR-CONSENSUS-002 phases 0 through 4:

- Phase 0: ADR and config safety gates
- Phase 1: validation evidence model
- Phase 2: fragment and intersection store
- Phase 3: deterministic Cardano-oriented comparator
- Phase 4: staged header validation pipeline

Phases 5 through 8 remain pending.

## Implementation Summary

Phase 0 added a safety gate so `trust-policy=validated` is only accepted when
the configured header validation level can produce validation evidence. Trusted
indexer configurations with `level: none` and `body-level: none` remain valid.

Phase 1 added `HeaderValidationEvidence` in `:consensus` and extended
candidate observations with era, validation evidence, optional VRF output, and
body availability/validation flags. Runtime header validation results now carry
accepted stages and rejection stage details, and observer fan-in attaches this
evidence to candidate headers.

Phase 2 added bounded per-peer candidate fragments, continuity checks, canonical
intersection checks, and pruning. This gives chain selection a path to reject
single disconnected longer headers and forks outside the rollback window.

Phase 3 added a deterministic comparator with explicit comparison reasons:
longer chain, density, validated VRF tie-break, and deterministic fallback. The
trusted/quorum strategy now uses the comparator and respects the configured
trust policy, including the `validated` policy.

Phase 4 refactored Shelley+ header validation into a staged
`HeaderValidationPipeline`. The existing `ShelleyHeaderValidator` remains as a
compatibility facade. The `header-signature` profile now maps to named
`structural`, `kes-signature`, and `opcert-signature` stages. Library users can
compose validation through builder APIs for profiles, defaults, custom
validators, disabled stages, and overrides.

## Review And Iteration

Two implementation gaps were found and fixed during review:

- `trust-policy=validated` needed to be enforced in chain-selection context, not
  only in config validation. `ChainSelectionContext` now carries the trust
  policy and the strategy gates untrusted candidate adoption accordingly.
- Post-Babbage protocol version is encoded as an array in the parsed header
  view. `ShelleyHeaderView` was updated to parse that shape correctly, and the
  validation pipeline tests now cover it.

## Verification

The following checks passed:

```bash
./gradlew :runtime:test --tests com.bloxbean.cardano.yano.runtime.config.UpstreamConfigTest
./gradlew :consensus:test :runtime:test --tests com.bloxbean.cardano.yano.runtime.sync.multipeer.MultiPeerScaffoldingTest
./gradlew :consensus:test
./gradlew :runtime:test --tests com.bloxbean.cardano.yano.runtime.sync.validation.ShelleyHeaderValidatorTest --tests com.bloxbean.cardano.yano.runtime.sync.validation.HeaderValidationPipelineTest --tests com.bloxbean.cardano.yano.runtime.sync.multipeer.MultiPeerScaffoldingTest --tests com.bloxbean.cardano.yano.runtime.config.UpstreamConfigTest
./gradlew :consensus:test :p2p:test :runtime:test
./gradlew :app:quarkusBuild
```

## Real Preprod Sync Smoke Test

Yano was started from the `app` module with:

```bash
java -Dquarkus.config.locations=config/application.yml -jar build/yano.jar
```

The configured preprod chainstate was already near tip and validation was
disabled:

```yaml
yano:
  upstream:
    validation:
      level: none
      body-level: none
```

Observed behavior:

- Quarkus started on the configured app HTTP port.
- Nonce state was restored at startup from the local body tip.
- The configured DNS peer failed to resolve, then discovery-provided peers were
  used for active upstream recovery.
- Yano established an active upstream connection, started observer sessions, and
  found an intersection at the local tip.
- Conway blocks were applied after startup and the status endpoint reported the
  runtime healthy, not degraded, and in sync.
- Log scan did not find header validation failures, missing local block body
  errors, nonce checkpoint errors, or unrepaired rollback warnings.

The run validates that the phase 0-4 changes preserve trusted/preprod sync when
validation is disabled. Validation-enabled live sync remains part of later
phase verification after VRF and ledger-view validation are implemented.

## Pending Work

- Phase 5: Praos VRF proof validation.
- Phase 6: ledger-view Praos validation.
- Phase 7: body integrity before adoption.
- Phase 8: runtime controller integration.
