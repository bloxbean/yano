# ADR-CONSENSUS-003 Phase 2 Report: Canonical Apply Integration

Date: 2026-07-01

## Scope

- Wired op-cert counter updates into `DefaultAccountStateStore.applyBlock`.
- Kept updates inside the canonical block apply path and the existing account-state write batch.
- Wired `LedgerStateHeaderValidationLedgerViewProvider.opCertStateFor(...)` to read explicit persisted counter evidence.

## Review Notes

- Observer and candidate headers remain read-only; they do not update op-cert state.
- The validation pipeline keeps compatibility behavior when no counter evidence exists.
- Counter overwrite is journaled through `putStateWithDelta`, preparing rollback tests for Phase 3.

## Tests

```text
./gradlew :ledger-state:test --tests com.bloxbean.cardano.yano.ledgerstate.DefaultAccountStateStoreOpCertCounterTest \
  :runtime:test --tests com.bloxbean.cardano.yano.runtime.sync.validation.LedgerStateHeaderValidationLedgerViewProviderTest
```

Result: passed.
