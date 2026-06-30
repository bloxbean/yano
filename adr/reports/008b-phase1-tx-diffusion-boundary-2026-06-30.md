# ADR-NET-008B Phase 1 Implementation Report

Date: 2026-06-30

## Scope Completed

- Added the `TxDiffusion` boundary and default implementation.
- Added bounded `PeerTxState` for per-peer announced/requested transaction
  state.
- Added mempool read methods for lookup and bounded snapshots.
- Wired `MemPoolTransactionReceivedEvent` into diffusion stats from
  `TxSubsystem`.
- Added `TxDiffusionStats` for status/reporting.

## Review Notes

- Phase 1 does not change wire behavior.
- Peer state is in-memory and drops on disconnect or process restart.
- Mempool remains the authoritative transaction-body store.

## Verification

- `./gradlew :runtime:test --tests com.bloxbean.cardano.yano.runtime.tx.diffusion.DefaultTxDiffusionTest --tests com.bloxbean.cardano.yano.runtime.tx.TxSubsystemTest`

