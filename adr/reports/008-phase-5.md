# ADR-NET-008 Phase 5 Implementation Report

## Scope

- Added `ChainSelectionStrategy`.
- Added `TrustedOrQuorumCandidateWithinRollbackWindow`.
- Added `ChainSelectionContext` and `ChainSelectionDecision`.
- Added `CanonicalApplier` boundary.
- Wired multi-peer observer candidate fan-in to asynchronous chain-selection
  evaluation.
- Added selected-upstream switching through the existing peer-session recovery
  path when a trusted or quorum-backed candidate wins.

## Review

- A single untrusted longer candidate is observe-only.
- Trusted candidates can be selected.
- Untrusted candidates require quorum before adoption.
- An untrusted non-quorum longer candidate does not block a shorter trusted
  candidate from being selected.
- Canonical writes still flow through one active `PeerSession` and one
  `LedgerApplyProcessor` generation.
- Full untrusted relay validation remains future work; structural-only
  untrusted candidates cannot force adoption.

## Verification

- `MultiPeerScaffoldingTest.singleUntrustedLongerCandidateIsObserveOnly`
- `MultiPeerScaffoldingTest.trustedLongerCandidateCanBeAdopted`
- `MultiPeerScaffoldingTest.untrustedQuorumCanBeAdopted`
- `MultiPeerScaffoldingTest.untrustedLongerCandidateDoesNotBlockTrustedCandidate`
- `./gradlew :runtime:test --tests "com.bloxbean.cardano.yano.runtime.sync.multipeer.MultiPeerScaffoldingTest" --tests "com.bloxbean.cardano.yano.runtime.sync.SyncSubsystemTest" --tests "com.bloxbean.cardano.yano.runtime.config.UpstreamConfigTest" --console=plain`
