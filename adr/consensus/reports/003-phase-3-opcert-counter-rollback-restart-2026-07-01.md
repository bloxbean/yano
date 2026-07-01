# ADR-CONSENSUS-003 Phase 3 Report: Rollback And Restart

Date: 2026-07-01

## Scope

- Added rollback tests proving counter overwrite restores the previous value.
- Added rollback-before-first-counter coverage proving absent state is restored.
- Added restart coverage proving counters survive RocksDB close/reopen.
- Added reconcile replay coverage proving stored block bodies populate op-cert counter state on startup catch-up.

## Review Notes

- No separate op-cert rollback journal was introduced.
- Counter values are restored by the existing account-state per-block delta entries.
- Reconcile uses the normal `DefaultAccountStateStore.reconcile(...)` replay path, so missing local block bodies still fail closed.

## Tests

```text
./gradlew :ledger-state:test --tests com.bloxbean.cardano.yano.ledgerstate.DefaultAccountStateStoreOpCertCounterTest
```

Result: passed.
