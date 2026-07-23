# Optional EUTxO validity and ZK-rollup modules

These sibling modules implement Phase C of ADR-UTXO-001. They are optional:
the base EUTxO ledger has no ZeroJ or Julc dependency and behaves exactly as
before unless `machines.eutxo.validity.enabled=true`.

The current implementation is pre-production. Development Groth16 setup keys
are test-only; production release remains blocked until the ADR's ceremony,
audit, data-availability and operational gates are satisfied.
