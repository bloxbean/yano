# ADR-CONSENSUS-003 Phase 4 Report: Strict And Compatibility Modes

Date: 2026-07-01

## Scope

- Added `yano.upstream.validation.opcert-counter-mode`.
- Supported values are `compat` and `strict`; default is `compat`.
- Runtime passes the selected policy to `LedgerStateHeaderValidationLedgerViewProvider`.
- Strict mode treats registered issuers without explicit stored counter state as counter `0`.

## Review Notes

- The default remains backward compatible for old chain-state databases and checkpointed starts.
- Strict mode is opt-in until migration/backfill is available and tested for existing public-network databases.
- Unregistered issuers still rely on the existing `praos-ledger` leader/stake validation stages to fail closed.

## Tests

```text
./gradlew :runtime:test \
  --tests com.bloxbean.cardano.yano.runtime.sync.validation.LedgerStateHeaderValidationLedgerViewProviderTest \
  --tests com.bloxbean.cardano.yano.runtime.config.UpstreamConfigTest
```

Result: passed.
