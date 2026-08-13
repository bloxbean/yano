# ADR-034 Phase 6 Verification Report

Phase 6 defines worker-owned transaction and account-event facts and projections.
Every on-chain transaction is projected, including phase-2-invalid transactions
with `valid=false`; only the Phase 0 scalar allowlist (location, validity, fee)
is stored. Account events use the same asynchronous block contract and no
longer require a synchronous history write to serve the new archive path.

The block projection contract now receives the deterministic archive job before
derivation, ensuring every fact row carries correct commit provenance. A test
pins phase-2-invalid visibility and job identity.
