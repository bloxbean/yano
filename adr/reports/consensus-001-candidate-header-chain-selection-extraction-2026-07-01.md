# ADR-CONSENSUS-001 Implementation Report

## Date

2026-07-01

## Scope

Implemented ADR-CONSENSUS-001 Phases 0 through 4.

The pure candidate-header and chain-selection policy classes now live in the
new `:consensus` module under:

```text
com.bloxbean.cardano.yano.consensus.selection
```

Runtime remains the owner of Yaci listener adapters, header validation,
observer session lifecycle, selected-upstream switching, peer recovery, body
fetch, and canonical apply.

## Moved To `:consensus`

- `CandidateHeader`
- `CandidateHeaderStore`
- `InMemoryCandidateHeaderStore`
- `HeaderFanIn`
- `ChainSelectionStrategy`
- `ChainSelectionContext`
- `ChainSelectionDecision`
- `TrustedOrQuorumCandidateWithinRollbackWindow`

## Runtime Boundary

Kept in runtime:

- `CandidateHeaderListener`
- `ObserverPeerSession`
- `SyncSubsystem`
- `PeerSession`
- `LedgerApplyProcessor`
- `BodyFetchScheduler`
- `CanonicalApplier`
- header validation pipeline

`CandidateHeaderListener` now depends on the consensus candidate model and
`HeaderFanIn`, while runtime validation remains runtime-owned.

## Test Changes

Added consensus tests:

- `CandidateHeaderStateTest`
- `ChainSelectionStrategyTest`
- `ConsensusArchitectureTest`

Extended p2p tests:

- `P2pArchitectureTest` now also rejects p2p imports of
  `com.bloxbean.cardano.yano.consensus..`.

Reduced runtime `MultiPeerScaffoldingTest` to runtime-owned adapter behavior:

- accepted observer headers enter candidate fan-in;
- rejected observer headers do not enter candidate fan-in.

## Phase 4 Evaluation

No `ChainSelectionController` was added.

The current extraction makes candidate state and selection policy independently
testable without moving runtime side effects. A controller should wait until
the next ADR defines explicit execution ports for canonical chain view,
selection clock, selected-chain actions, and generation-fenced promotion.

## Verification

Phase-level verification:

```text
./gradlew :consensus:test :p2p:test :runtime:compileJava
./gradlew :consensus:test :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.sync.multipeer.MultiPeerScaffoldingTest'
./gradlew :consensus:test :p2p:test :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.sync.multipeer.MultiPeerScaffoldingTest'
```

Final verification:

```text
./gradlew :consensus:test :p2p:test :runtime:test
./gradlew :app:quarkusBuild
```

Both commands completed successfully.
