# Optional EUTxO validity and ZK-rollup modules

These sibling modules implement Phase C of ADR-UTXO-001. They are optional:
the base EUTxO ledger has no ZeroJ or Julc dependency and behaves exactly as
before unless `machines.eutxo.validity.enabled=true`.

The current implementation is pre-production. Development Groth16 setup keys
are test-only; production release remains blocked until the ADR's ceremony,
audit, data-availability and operational gates are satisfied.

Start with [GETTING_STARTED.md](GETTING_STARTED.md). It distinguishes the
developer proof/validator workflow that is executable today from the planned
live Cardano L1-to-appchain-to-L1 preview workflow.

Milestone notes:

- [Z0 feasibility](Z0_FEASIBILITY.md)
- [Z1 bounded circuit](Z1_BATCH_CIRCUIT.md)
- [Z2 prover pipeline](Z2_PROVER_PIPELINE.md)
- [Z3 validity root](Z3_VALIDITY_ROOT.md)
- [Z4 withdrawals](Z4_PROOF_WITHDRAWALS.md)
- [Z5 data availability and recovery](Z5_DATA_AVAILABILITY.md)
- [Z6 production hardening and release gates](Z6_PRODUCTION_HARDENING.md)
- [D3 fixed batch profiles and measurements](D3_BATCH_PROFILES.md)
- [D4 packaged testnet lifecycle](D4_LIFECYCLE.md)
- [D5 network acceptance and evidence status](D5_ACCEPTANCE.md)
