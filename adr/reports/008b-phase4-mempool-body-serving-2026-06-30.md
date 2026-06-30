# ADR-NET-008B Phase 4 Implementation Report

Date: 2026-06-30

## Scope Completed

- Added mode-aware mempool tx-body serving through `TxDiffusion`.
- Enforced per-peer served transaction count and byte limits.
- Served existing mempool transaction byte arrays without duplicating tx bodies
  in memory.
- Added served transaction and served byte counters to diffusion status.
- Wired request observation into `YaciTxSubmissionHandler`.

## Review Notes

- Upstream body serving uses the existing Yaci `PeerClient.submitTxBytes`
  queue / `TxSubmissionAgent`, which advertises tx ids and serves bodies when
  the peer requests them.
- Inbound Yano server sessions use Yaci `TxSubmissionServerAgent`, whose role
  is to request and receive transactions from the connecting peer. Serving
  Yano's mempool to peers that only connect inbound requires wiring the
  TxSubmission client role for those sessions or opening a corresponding
  outbound peer connection. This is documented as later hardening.

## Verification

- `./gradlew :runtime:test --tests com.bloxbean.cardano.yano.runtime.tx.diffusion.DefaultTxDiffusionTest`
- `./gradlew :runtime:test`
- `./gradlew :app:test :app:quarkusBuild`
