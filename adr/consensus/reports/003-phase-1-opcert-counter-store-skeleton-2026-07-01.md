# ADR-CONSENSUS-003 Phase 1 Report: Op-Cert Counter Store Skeleton

Date: 2026-07-01

## Scope

- Added `OpCertCounterProvider` and `OpCertCounterState` in `core-api`.
- Added a versioned CBOR value for op-cert counter state in `ledger-state`.
- Added `OpCertCounterTracker` as the RocksDB-backed key/value helper.
- Exposed op-cert counter reads through `DefaultAccountStateStore`.

## Review Notes

- The durable state remains under `DefaultAccountStateStore` and uses the existing account-state column family.
- The tracker is intentionally small: it owns key derivation, value encoding/decoding, and later canonical block extraction.
- A separate write-side `OpCertCounterStore` SPI is intentionally not exposed; canonical apply remains the only writer through `DefaultAccountStateStore`.
- No runtime validation policy changed in this phase.

## Tests

```text
./gradlew :ledger-state:test --tests com.bloxbean.cardano.yano.ledgerstate.OpCertCounterTrackerTest
```

Result: passed.
