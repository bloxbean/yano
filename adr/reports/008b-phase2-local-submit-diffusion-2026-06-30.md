# ADR-NET-008B Phase 2 Implementation Report

Date: 2026-06-30

## Scope Completed

- Routed local submit upstream forwarding through `TxDiffusion` when diffusion
  is enabled.
- Preserved legacy `active-selected` and `all-hot-trusted` forwarding when only
  `yano.upstream.tx.forwarding` is configured.
- Added per-peer duplicate suppression for enabled diffusion modes.
- Wired `TxSubsystem::txDiffusion` into `SyncSubsystem`.

## Review Notes

- Disabled diffusion remains a pass-through for direct legacy sync subsystem
  tests and direct runtime embedding.
- The app-level config adapter still maps `yano.tx.diffusion.mode` into the
  effective upstream forwarding policy for distribution config.

## Verification

- `./gradlew :runtime:test --tests com.bloxbean.cardano.yano.runtime.sync.SyncSubsystemTest.allHotTrustedTxForwardingTargetsActiveAndTrustedObserverPeers --tests com.bloxbean.cardano.yano.runtime.sync.SyncSubsystemTest.txDiffusionSuppressesRepeatedLocalSubmitForwardToSamePeer`

