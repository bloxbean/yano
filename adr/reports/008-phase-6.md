# ADR-NET-008 Phase 6 Implementation Report

## Scope

- Added `BodyFetchScheduler` seam.
- Added upstream tx forwarding config and status surface.
- Implemented live tx forwarding policies:
  - `active-selected`;
  - `all-hot-trusted`;
  - `disabled`.

## Review

- Live body fetch remains handled by current `BodyFetchManager`.
- This phase defines the selected-chain body-fetch extension point without changing current fetching semantics.
- `all-hot-trusted` forwards submitted transactions to the active trusted peer
  and running trusted observer peers.
- Untrusted observer peers are excluded from all-hot forwarding.
- The selected-chain body-fetch seam is intentionally present without changing
  the proven canonical body apply path.

## Verification

- `SyncSubsystemTest.allHotTrustedTxForwardingTargetsActiveAndTrustedObserverPeers`
- `./gradlew :runtime:test --tests "com.bloxbean.cardano.yano.runtime.sync.SyncSubsystemTest" --console=plain`
