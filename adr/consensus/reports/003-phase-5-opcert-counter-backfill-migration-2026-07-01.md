# ADR-CONSENSUS-003 Phase 5 Report: Backfill Deferred

Date: 2026-07-01

## Scope

- Removed the special op-cert-only backfill path for old databases already at chain tip.
- Removed the backfill marker metadata and rollback-marker maintenance.
- Kept canonical apply tracking and normal account-state reconcile replay.
- Kept `none`, `compat`, and `strict` validation modes.

## Review Notes

- The packaged default is `opcert-counter-mode: none`.
- `strict` should be enabled only for databases synced with counter tracking from the validation start point.
- Existing or checkpointed databases should use `none` or `compat`; Yano preview does not attempt automatic counter-state migration.
- Removing backfill also removes the mainnet-scale transient delta-bloat risk identified during review.

## Tests

```text
./gradlew :ledger-state:test --tests com.bloxbean.cardano.yano.ledgerstate.DefaultAccountStateStoreOpCertCounterTest
```

Result: passed.
