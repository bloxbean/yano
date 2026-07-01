# ADR-CONSENSUS-003 Implementation Summary

Date: 2026-07-01

## Completed Phases

- Phase 1: op-cert counter read provider API, versioned CBOR value, RocksDB-backed read skeleton.
- Phase 2: canonical apply integration and runtime ledger-view read wiring.
- Phase 3: rollback, restart, and reconcile replay tests.
- Phase 4: `none`, `compat`, and `strict` op-cert counter validation modes.
- Phase 5: old-database backfill/migration removed/deferred for preview simplicity.

## Runtime Behavior

- `praos-ledger` now reads persisted op-cert counter evidence when available.
- Default `yano.upstream.validation.opcert-counter-mode` is `none`, so the op-cert counter stage is opt-in while the new counter state matures.
- `compat` checks persisted counters when available and skips missing counter evidence for trusted/indexer, checkpointed, or old database deployments.
- `strict` mode treats a registered issuer with no stored counter as counter `0`.
- Canonical block apply updates counters; observer/candidate headers remain read-only.
- Startup reconcile populates op-cert counters only when account state is actually behind and replays canonical blocks normally. It does not scan already-synced databases solely to backfill counters.

## Verification

```text
./gradlew :ledger-state:test --tests '*OpCertCounter*' \
  :runtime:test \
    --tests com.bloxbean.cardano.yano.runtime.sync.validation.ShelleyHeaderValidatorTest \
    --tests com.bloxbean.cardano.yano.runtime.sync.validation.LedgerStateHeaderValidationLedgerViewProviderTest \
    --tests com.bloxbean.cardano.yano.runtime.config.UpstreamConfigTest
```

Result: passed.

```text
./gradlew :app:quarkusBuild
```

Result: passed.

```text
./gradlew :app:haskellSyncTest \
  --tests com.bloxbean.cardano.yano.app.e2e.haskellsync.RegularBPSyncTest \
  -Dyano.uber.jar=/Users/satya/work/bloxbean/yano/app/build/yano.jar
```

Result: passed. Haskell cardano-node reached slot 1200; Yano slot 1200; difference 0.

## Remaining Operational Note

The distribution default remains `opcert-counter-mode: none`. `strict` should be enabled only for databases synced with counter tracking from the validation start point. Existing or checkpointed databases should use `none` or `compat`.
