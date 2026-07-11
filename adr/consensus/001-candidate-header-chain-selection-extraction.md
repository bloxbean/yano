# ADR-CONSENSUS-001: Candidate Header And Chain Selection Extraction

## Status

Accepted. Phases 0 through 4 were implemented on 2026-07-01.

## Date

2026-07-01

## Context

ADR-NET-008H extracted reusable relay peer-management behavior into `:p2p`.
During that work, candidate-header fan-in and chain-selection policy were left
in `runtime` deliberately.

Those classes are network-adjacent, but they are not peer-management policy.
They decide whether observed headers from multiple peers can affect the
canonical chain. That makes them consensus-facing code, even before Yano
implements full block/header validation and full Ouroboros chain-selection
parity.

At the time of decision, these classes were runtime-owned:

```text
runtime/sync/multipeer/CandidateHeader
runtime/sync/multipeer/CandidateHeaderStore
runtime/sync/multipeer/InMemoryCandidateHeaderStore
runtime/sync/multipeer/HeaderFanIn
runtime/sync/multipeer/ChainSelectionStrategy
runtime/sync/multipeer/ChainSelectionContext
runtime/sync/multipeer/ChainSelectionDecision
runtime/sync/multipeer/TrustedOrQuorumCandidateWithinRollbackWindow
runtime/sync/multipeer/BodyFetchScheduler
runtime/sync/multipeer/CanonicalApplier
runtime/sync/multipeer/CandidateHeaderListener
```

The pure state and policy classes are already close to extractable. The listener
and canonical adoption path are not:

- `CandidateHeaderListener` depends on Yaci `BlockChainDataListener` and runtime
  header validation;
- `SyncSubsystem` owns candidate evaluation scheduling, selected-upstream
  switching, generation-fenced peer recovery, and active canonical sync;
- canonical apply and rollback must remain under runtime supervision until
  there is a small, explicit port.

The first extraction set is intentionally small and currently JDK-only. A
`runtime.consensus` package would be a valid short-term alternative, but Yano's
relay roadmap includes stronger chain-selection policy, deterministic
tie-breaks, validation-aware selection, and eventual block-production adjacent
consensus work. Creating the module while the current classes are still pure is
cheaper than extracting it after runtime dependencies grow around them.

## Decision

Create a future `:consensus` module for reusable chain-selection and
candidate-header policy. Do not put these classes in `:p2p`.

The module name is broader than the initial payload. The first contents are
candidate-header and chain-selection policy only. Header/body validation, leader
schedule, block production, and fuller Ouroboros consensus rules remain out of
scope for this ADR, but may move into `:consensus` under later ADRs once their
runtime side effects have explicit ports.

The initial dependency direction is:

```text
runtime -> consensus
runtime -> p2p
```

`consensus` starts with no production dependency beyond the JDK. A later
`consensus -> core-api` dependency is allowed only when shared Yano API models or
ports justify it.

`p2p` and `consensus` must not depend on each other. Runtime is the composition
layer that wires peer observations from `p2p` sessions into consensus policy and
then applies selected changes through runtime-owned sync and apply machinery.
This rule must be enforced symmetrically: `consensus` cannot import `p2p`, and
`p2p` cannot import `consensus`.

## Implementation Status

| Phase | Status | Notes |
| --- | --- | --- |
| 0 | Implemented | Added `:consensus`, a consensus architecture guard, and a symmetric `:p2p` guard that forbids p2p-to-consensus imports. |
| 1 | Implemented | Moved candidate-header state and `HeaderFanIn` to `com.bloxbean.cardano.yano.consensus.selection`; moved focused store/fan-in tests to `:consensus`. |
| 2 | Implemented | Moved chain-selection context, decision, strategy SPI, and conservative trusted-or-quorum strategy to `:consensus`; moved strategy tests to `:consensus`. |
| 3 | Implemented | Runtime adapters now depend on consensus candidate models while keeping Yaci listener adapters and header validation in runtime. Runtime tests cover accepted and rejected observer headers. |
| 4 | Evaluated / Deferred | No `ChainSelectionController` was introduced in this iteration. Evaluation coalescing, selected-upstream switching, and generation-fenced recovery remain in `SyncSubsystem` until a later ADR adds explicit execution ports. |

## Module Boundary

### `consensus` Owns

The proposed `:consensus` module should own protocol-neutral chain-selection
state and policy:

- candidate header model;
- candidate header store interface;
- bounded in-memory candidate store;
- header fan-in over candidate observations;
- chain-selection context and decision;
- chain-selection strategy SPI;
- conservative trusted-or-quorum strategy;
- future deterministic tie-break policy;
- future validation-aware chain-selection inputs.

### `runtime` Owns

Runtime continues to own execution and side effects:

- Yaci listener adapters;
- header validation invocation;
- observer session lifecycle;
- selected upstream switching;
- `PeerSession` and `PeerSessionSupervisor`;
- generation-fenced canonical sync recovery;
- body fetch scheduling;
- canonical apply and rollback;
- storage integration and maintenance gates.

## Initial Extraction Set

Move first:

```text
CandidateHeader
CandidateHeaderStore
InMemoryCandidateHeaderStore
HeaderFanIn
ChainSelectionStrategy
ChainSelectionContext
ChainSelectionDecision
TrustedOrQuorumCandidateWithinRollbackWindow
```

These classes should remain JDK-only after the move. If an extraction requires
Yaci, RocksDB, Quarkus, `runtime`, or `p2p`, it is outside this initial move set.

Keep in runtime for the first extraction:

```text
CandidateHeaderListener
ObserverPeerSession
BodyFetchScheduler
CanonicalApplier
SyncSubsystem
PeerSession
LedgerApplyProcessor
HeaderValidator and validation pipeline
```

`BodyFetchScheduler` and `CanonicalApplier` are already small boundary names,
but they represent runtime side effects. They should move only if they become
pure ports declared by `consensus` and implemented by runtime, with no runtime
implementation leakage into `consensus`.

## Required Ports

Before moving execution-facing behavior, define small ports:

```text
CandidateObserver
ChainSelectionClock
CanonicalChainView
SelectedChainActions
```

Suggested responsibilities:

- `CandidateObserver`: accepts normalized candidate headers from any transport
  or listener implementation.
- `ChainSelectionClock`: supplies current time for deterministic tests and
  cooldowns.
- `CanonicalChainView`: read-only current canonical tip and rollback-window
  parameters.
- `SelectedChainActions`: runtime-implemented actions for selected-upstream
  promotion and eventual body scheduling.

The first implementation can avoid these ports by moving only the pure classes
listed above.

## Chain-Selection Policy Requirements

Future policy work should preserve these invariants:

- only selected/canonical headers may mutate canonical chain state;
- observer header fan-in must not write canonical state;
- untrusted single-peer longer chain cannot force canonical rollback/adoption;
- rollback-window checks must be derived from genesis/security parameter where
  practical;
- deterministic tie-break behavior must be explicit;
- validation level must be an input to chain-selection policy, not hidden in the
  candidate store;
- selected-upstream switching must remain generation-fenced by runtime until the
  apply pipeline itself is extracted.

## Implementation Plan

### Phase 0: Module Skeleton

- Add `:consensus`.
- Add an architecture test that prevents imports from
  `com.bloxbean.cardano.yano.runtime..` and
  `com.bloxbean.cardano.yano.p2p..`.
- Extend the existing `:p2p` architecture guard so `p2p` cannot import
  `com.bloxbean.cardano.yano.consensus..`.
- Keep `:consensus` production dependencies empty initially. Add the
  `runtime -> consensus` project dependency when the first classes move in
  Phase 1.

Acceptance:

- `./gradlew :consensus:test :p2p:test :runtime:compileJava` passes.
- `:consensus` has no runtime, p2p, core-api, RocksDB, Quarkus, or app
  dependency.
- `:p2p` tests fail if a p2p class imports `com.bloxbean.cardano.yano.consensus..`.

### Phase 1: Pure Candidate Header State

- Move `CandidateHeader`, `CandidateHeaderStore`,
  `InMemoryCandidateHeaderStore`, and `HeaderFanIn`.
- Move the candidate-store and header-fan-in unit tests from
  `MultiPeerScaffoldingTest` into `:consensus`.
- Remove the moved assertions from runtime `MultiPeerScaffoldingTest`; keep that
  file only for real runtime multi-peer integration coverage.
- Update runtime imports.

Acceptance:

- Candidate store tests pass in `:consensus`.
- Runtime multi-peer observer tests still pass.
- No duplicate candidate-store/header-fan-in tests remain in runtime.

### Phase 2: Pure Chain Selection Strategy

- Move `ChainSelectionStrategy`, `ChainSelectionContext`,
  `ChainSelectionDecision`, and
  `TrustedOrQuorumCandidateWithinRollbackWindow`.
- Move the strategy tests into `:consensus`.
- Remove the moved strategy assertions from runtime
  `MultiPeerScaffoldingTest`. Delete that test file if no runtime-specific
  integration coverage remains after the 008H p2p test cleanup.
- Keep runtime scheduling and selected-peer switching in `SyncSubsystem`.

Acceptance:

- Strategy tests pass in `:consensus`.
- Runtime `SyncSubsystem` still evaluates selection through the moved strategy.
- `MultiPeerScaffoldingTest` is either deleted or contains only runtime-owned
  integration behavior.

### Phase 3: Runtime Adapter Cleanup

- Keep `CandidateHeaderListener` in runtime, but make it depend only on the
  consensus candidate model and `HeaderFanIn`.
- Keep header validation runtime-owned.
- Keep observer session lifecycle runtime-owned.

Acceptance:

- Observer sessions still record candidate headers.
- Rejected observer headers do not enter fan-in.
- No canonical apply behavior changes.

### Phase 4: Future Consensus Controller Spike

Evaluate whether to introduce a `ChainSelectionController` in `:consensus` that
owns evaluation coalescing, pruning, and strategy execution while returning
decisions to runtime.

This phase must not move canonical apply or peer-session recovery. It should
return explicit decisions for runtime to execute.

Acceptance:

- Runtime remains the only code that switches selected upstream peers.
- Chain-selection evaluation is deterministic and unit-testable without Yaci or
  runtime storage.

## Testing Plan

For each phase:

- run moved tests in `:consensus`;
- run runtime multi-peer tests;
- run `:p2p:test` to verify no p2p-to-consensus coupling was introduced;
- run `:runtime:test` after import churn;
- run an app/preprod smoke test after any phase that changes observer
  candidate handling or selected-upstream switching.

Minimum verification:

```text
./gradlew :consensus:test :p2p:test :runtime:test
./gradlew :app:quarkusBuild
```

## Consequences

Positive:

- keeps `:p2p` focused on peer management and relay networking;
- gives chain-selection policy a reusable home;
- makes candidate-store and selection behavior independently testable;
- creates a path to stronger validation-aware and deterministic chain
  selection.

Tradeoffs:

- introduces another module;
- requires careful port boundaries to avoid moving runtime side effects too
  early;
- leaves canonical apply and selected-upstream switching in runtime until a
  later, more explicit extraction.

## Non-Goals

- Implement full Ouroboros chain selection in this ADR.
- Move `PeerSession`, `LedgerApplyProcessor`, or body fetch.
- Move header validation pipeline.
- Persist candidate headers.
- Change current upstream modes or operator configuration.
