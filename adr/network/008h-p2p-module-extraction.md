# ADR-NET-008H: P2P Module Extraction

## Status

Accepted. Phases 0 through 5 were implemented on 2026-07-01.

This ADR supersedes the module-extraction deferral in ADR-NET-002 for the
relay peer-management layer. ADR-NET-002 remains valid for the original
single-peer recovery history and for the rule that runtime-specific sync
execution must not be moved until its dependency boundary is clean.

Implementation report:

- `adr/reports/008h-p2p-module-extraction-phases-0-4-2026-07-01.md`
- `adr/reports/008h-p2p-module-extraction-phase-5-2026-07-01.md`

## Date

2026-06-30

## Context

Yano's relay networking work has grown substantially through ADR-NET-008B,
ADR-NET-008C, ADR-NET-008D, ADR-NET-008E, and ADR-NET-008F.

The runtime module now contains several distinct responsibilities:

- node assembly and lifecycle orchestration;
- chain sync execution and body fetch;
- canonical chain application and rollback coordination;
- peer connection lifecycle;
- peer governance, discovery, peer sharing, and relay policy;
- transaction admission and transaction diffusion;
- N2N server wiring;
- storage, ledger-state, UTXO, producer, and devnet integration.

The original ADR-NET-002 decision was to keep peer management inside the
`runtime` module until the boundary was proven. That was the right choice at
the time because the first peer-management code directly owned
`PeerClient`, `PipelineDataListener`, `HeaderSyncManager`, `BodyFetchManager`,
rollback coordination, transaction submission, and runtime progress signals.

That situation has changed. The relay networking work introduced components
whose natural ownership is no longer the runtime pipeline:

- relay connection lifecycle and socket identity;
- inbound admission limits and outbound duplicate suppression;
- peer governor state, scoring, backoff, and bounded known-peer admission;
- topology, peer snapshot, peer-sharing, and discovery-first bootstrap;
- peer sharing response selection;
- transaction diffusion protocol state.

Keeping all of this in `runtime` makes the module harder to reason about and
makes Yano less useful as a library for applications that want to reuse the
P2P layer without taking the full runtime implementation surface.

## Decision

Create a new Gradle module named:

```text
:p2p
```

The published artifact should be named:

```text
yano-p2p
```

The base Java package should be:

```text
com.bloxbean.cardano.yano.p2p
```

The `p2p` module will own reusable Cardano N2N peer-management and relay policy
code. The `runtime` module will continue to own node assembly, sync execution,
canonical chain application, storage integration, and lifecycle orchestration.

The extraction must be staged. Do not move every network-looking class at once.
Move only classes whose dependencies can be kept out of `runtime`.

## Naming

Use `p2p` rather than `network`, `peer-manager`, or `governance`.

Reasons:

- `p2p` matches Cardano node terminology around peer sharing, root peers,
  hot/warm/cold peers, and peer governance.
- `network` is accurate but too broad; Yaci still owns the lower-level
  multiplexing and mini-protocol implementations.
- `peer-manager` is too narrow; the target module also includes connection
  lifecycle, discovery, peer sharing, and transaction diffusion boundaries.
- `governance` is misleading in a Cardano codebase because it can mean
  Conway/on-chain governance.

## Module Dependency Rules

The allowed direction is:

```text
runtime -> p2p -> core-api
```

The `p2p` module may depend on:

- `core-api`;
- Yaci N2N/client/server libraries;
- Jackson for peer-store and topology/snapshot parsing;
- SLF4J logging APIs;
- Netty as `compileOnly` where Yaci hooks expose Netty types.

Yaci dependencies that appear in the public `p2p` API must be declared as
`api`, not `implementation`. In particular, the current connection-manager
inbound admission hook returns Yaci's `ServerConnectionListener`, whose accept
path exposes Netty `Channel` through the Yaci type. That is acceptable for the
first extraction because Yaci already owns the transport and mini-protocol
layer, but it is a small public-surface wart. A later cleanup should wrap this
behind a Yano-owned, Netty-free accept hook if embedders need a cleaner API.

The `p2p` module must not depend on:

- `runtime`;
- `ledger-state`;
- `ledger-rules`;
- RocksDB;
- `tx-services`;
- application adapters;
- Quarkus;
- runtime storage implementations.

If a class needs runtime chain state, ledger apply, body fetch, rollback
coordination, maintenance gates, or transaction validation internals, it stays
in `runtime` until a small port interface exists.

## Ownership Boundary

### `p2p` Owns

The target module owns reusable peer-network behavior:

- connection identity and lifecycle snapshots;
- connection-manager events and listener SPI;
- inbound admission accounting;
- outbound dial reservation and duplicate suppression;
- source-port reuse configuration and local-bind resolution;
- peer endpoint normalization;
- peer failure classification helpers;
- peer governor records, state transitions, scoring, backoff, and peer
  selection;
- peer-store interfaces and file/in-memory implementations;
- peer address policy, including source-aware private-address rules;
- topology and peer-snapshot ingestion;
- peer-sharing discovery feed;
- relay peer-sharing response selection;
- protocol capability snapshots;
- transaction diffusion protocol state through runtime-owned catalogue,
  transaction-hash, and admission ports.

### `runtime` Owns

The runtime module remains the node composition and execution layer:

- `RuntimeNode`;
- `SyncSubsystem`;
- `PeerSession` until it no longer directly creates runtime sync/apply
  managers;
- `PeerSessionSupervisor` while it directly supervises `PeerSession`;
- `ServeSubsystem` while it wires runtime transaction admission, chain state,
  and Yaci server lifecycle;
- `YaciTxSubmissionHandler` while it depends on runtime transaction admission;
- header sync, body fetch, pipeline data listener, and ledger apply;
- canonical chain selection and canonical writer boundaries;
- storage, rollback, maintenance, UTXO, ledger-state, producer, and devnet
  subsystems.

The runtime should consume `p2p` through stable interfaces and immutable
snapshots. Runtime code should not reach into mutable governor or connection
manager internals.

## Initial Move Set

The first implementation phase should move only the low-coupling peer and
network policy classes.

Move from `runtime` to `p2p`:

```text
runtime/connection/*

runtime/peer/PeerEndpoint
runtime/peer/PeerClientFactory
runtime/peer/DefaultPeerClientFactory
runtime/peer/LocalBindAddressResolver
runtime/peer/PeerFailureMessage
runtime/peer/PeerHealth
runtime/peer/PeerRecoveryFailureTracker
runtime/peer/PeerRecoveryReason
runtime/peer/PeerSessionState
runtime/peer/PeerSessionStatus

runtime/sync/multipeer/PeerGovernor
runtime/sync/multipeer/PeerDescriptor
runtime/sync/multipeer/PeerGovernorSnapshot
runtime/sync/multipeer/PeerSource
runtime/sync/multipeer/PeerState
runtime/sync/multipeer/PeerUse
runtime/sync/multipeer/PeerStore
runtime/sync/multipeer/PeerStoreEntry
runtime/sync/multipeer/InMemoryPeerStore
runtime/sync/multipeer/FileBackedPeerStore
runtime/sync/multipeer/PeerAddressPolicy
runtime/sync/multipeer/YaciPeerDiscoveryService
```

Moving `PeerRecoveryReason` is part of the cycle break. Runtime apply code may
reference the recovery reason enum, but `p2p` must not reference runtime apply
or sync execution. Placing the enum in `p2p` keeps the edge in the allowed
direction:

```text
runtime.apply -> p2p
```

Move `RelayPeerSharingProvider` after the peer-store and connection snapshots
are already in `p2p`.

Do not move in the first phase:

```text
RuntimeNode
SyncSubsystem
PeerSession
PeerSessionSupervisor
ObserverPeerSession
ServeSubsystem
YaciTxSubmissionHandler
HeaderSyncManager
BodyFetchManager
PipelineDataListener
LedgerApplyProcessor
DefaultTxDiffusion
```

These classes either orchestrate the node or still depend on runtime-specific
pipeline, validation, transaction, or storage concepts.

The `runtime/sync/validation` package is also intentionally left in `runtime`
for this extraction. Some validation classes are relatively self-contained, but
their primary consumer is `PeerSession`, and `PeerSession` remains runtime-owned
until its sync/apply dependencies are inverted. If `PeerSession` moves later,
Praos/Shelley header validation is a natural companion move.

## Package Migration

The target package should not keep the `.runtime` prefix.

For example:

```text
com.bloxbean.cardano.yano.runtime.connection.ConnectionKey
```

becomes:

```text
com.bloxbean.cardano.yano.p2p.connection.ConnectionKey
```

This is noisier than keeping the old package names, but it prevents a misleading
module where code physically lives in `:p2p` while still presenting itself as
runtime implementation code.

Package movement should be done in small commits so import-only churn is easy
to review.

## Implementation Plan

### Phase 0: ADR And Dependency Guard

Status: completed on 2026-07-01.

- Add this ADR.
- Add the `:p2p` Gradle module.
- Give `:p2p` only the dependencies allowed by this ADR.
- Make `:runtime` depend on `:p2p`.
- Add an ArchUnit test in `:p2p` that fails if any `p2p` class imports
  `com.bloxbean.cardano.yano.runtime..`.

Acceptance:

- `./gradlew :p2p:compileJava` succeeds with no `runtime` dependency.
- The root build still compiles before moving behavior.
- The ArchUnit dependency test runs in CI and documents the no-runtime-import
  rule.

### Phase 1: Extract Connection And Peer Primitives

Status: completed on 2026-07-01.

- Move connection-manager model classes and the default connection manager.
- Move endpoint, peer-client factory, local-bind, failure-message, and peer
  health primitives that do not require runtime managers.
- Update imports in runtime.
- Move the connection-manager tests into `:p2p`.

Acceptance:

- Existing connection-manager tests pass in `:p2p`.
- Runtime still starts with the moved connection manager.
- No runtime dependency is introduced into `:p2p`.

### Phase 2: Extract Peer Governor And Peer Store

Status: completed on 2026-07-01.

- Move the peer governor and its data model.
- Move peer-store interfaces and file/in-memory stores.
- Move peer address policy.
- Move governor tests into `:p2p`.
- Keep runtime-owned sync and observer creation as callers of the governor.

Acceptance:

- Existing governor tests pass in `:p2p`.
- Runtime status still exposes upstream peer counts.
- Discovery, observer selection, and peer-sharing code use the moved governor
  APIs without reaching into runtime internals.

### Phase 3: Extract Discovery

Status: completed on 2026-07-01.

- Move `YaciPeerDiscoveryService` after the governor and address policy are in
  `p2p`.
- Keep discovery callbacks as small interfaces or lambdas so discovery feeds the
  runtime/governor without depending on runtime.
- Preserve snapshot size limits, topology parsing, source-aware address policy,
  and discovery-first bootstrap behavior.

Acceptance:

- Snapshot and topology tests pass in `:p2p`.
- Preprod peer-snapshot loading still works.
- A bad configured static peer does not prevent discovery-first bootstrap from
  selecting usable discovered peers.

### Phase 4: Extract Peer Sharing Provider

Status: completed on 2026-07-01.

- Move relay peer-sharing response selection to `p2p`.
- Change `RelayPeerSharingProvider` from package-private runtime implementation
  detail to a public `p2p` type or a public `p2p` factory-backed provider.
- Keep `ServeSubsystem` in runtime; it wires the Yaci server and runtime
  transaction/chain dependencies.
- Runtime injects a `p2p` peer-sharing provider into the server layer.

Acceptance:

- A connected Haskell node can still request peer-sharing results from Yano.
- Returned peers come from the governor-managed sharable peer set.

### Phase 5: Transaction Diffusion Boundary

Status: completed on 2026-07-01.

Do not move `DefaultTxDiffusion` until runtime dependencies have been inverted.

First define small ports for:

- mempool catalogue reads and transaction body lookup;
- transaction hash calculation;
- transaction admission.

After those ports exist, move protocol-neutral transaction diffusion state into
`p2p`. Runtime should continue to own the actual transaction admission path and
validation event chain.

Implemented ports:

- `TxCatalog`: read-only catalogue for `contains(txHash)` and
  `getTransaction(txHash)`;
- `TxHashProvider`: transaction hash calculation, supplied by runtime using
  existing Cardano transaction utilities;
- `TxAdmissionPort`: admission callback used by diffusion to route inbound
  transaction bodies into the runtime admission and validation event chain.

Moved to `p2p.tx.diffusion`:

- `DefaultTxDiffusion`;
- `DisabledTxDiffusion`;
- `PeerTxState`;
- transaction diffusion request/result/stat records and enums.

Acceptance:

- Local tx submission behavior remains unchanged.
- Inbound tx diffusion from connected peers still reaches the existing runtime
  transaction admission path.
- `p2p` does not depend on runtime transaction classes.

### Phase 6: Optional Chain-Selection Policy Extraction

Candidate header fan-in and chain-selection strategy may move later, but only
after the project decides whether this belongs in `p2p` or a future
`consensus` module.

For now, do not move canonical apply, ledger rollback, body fetch, or header
validation execution.

Acceptance:

- No chain-selection behavior changes as part of the module extraction.
- Any later extraction has its own ADR or ADR update.

## Testing Plan

For each phase:

- run the moved unit tests in their new module;
- verify moved tests do not depend on shared runtime test fixtures;
- run `:runtime:test` for integration regressions;
- run the root build when import churn is large;
- run a preprod smoke test after phases that affect discovery, peer sharing,
  source-port reuse, or transaction diffusion.

Tests that exercise only moved peer-management code should move with the code.
Runtime integration tests that happen to reference governor or connection
classes should stay in `runtime` and receive import updates. For example, a
sync-subsystem integration test should not move to `:p2p` just because it
mentions `PeerGovernor`.

Minimum final verification:

```text
./gradlew clean build
```

and a manual preprod smoke test proving:

- Yano starts from the app module;
- Yano connects to discovered or configured preprod peers;
- the status endpoint reports relay connection and governor state;
- a local Haskell preprod node can connect inbound;
- tx diffusion still admits a transaction through the existing runtime path.

## Compatibility

Runtime configuration keys should not change as part of this extraction.

Existing `yano.upstream.*`, `yano.relay.*`, and `yano.tx.*` behavior should
continue to work. Package names and module coordinates may change for internal
Java APIs, but operator configuration and app runtime behavior should remain
compatible.

If any moved class is part of a public Java API, keep a compatibility shim or
document the breaking change explicitly before release.

## Alternatives Considered

### Keep Everything In `runtime`

This avoids import churn, but it leaves `runtime` as the long-term home for
connection management, peer governance, discovery, transaction relay, sync
execution, storage, and node assembly. That makes the module harder to test and
harder to reuse.

Rejected.

### Create `network`

`network` is a reasonable name, but it is broader than the intended ownership.
Yaci remains the lower-level network and mini-protocol implementation. Yano's
new module is specifically the P2P peer-management and relay policy layer.

Rejected in favor of `p2p`.

### Create `peer-manager`

This name is too narrow. The module would also own connection manager,
discovery, peer sharing, address policy, source-port reuse, and eventually
transaction diffusion protocol state.

Rejected.

### Create `governance`

This conflicts conceptually with Cardano on-chain governance.

Rejected.

### Move `PeerSession` First

`PeerSession` currently constructs and coordinates runtime-specific sync and
apply components. Moving it first would either create a forbidden dependency
from `p2p` to `runtime` or force premature port abstractions around the sync
pipeline.

Rejected for the first extraction phase.

## Risks

- Import churn may obscure behavioral changes. Keep moves mechanical and
  reviewable.
- A hidden runtime dependency can accidentally pull the new module back into
  the old coupling. Enforce the dependency rule early.
- Moving packages may affect embedders using internal classes. Identify any
  exposed types before release.
- Transaction diffusion may be tempting to move too early. Keep runtime
  transaction admission as the owner until clean ports exist.
- Chain-selection policy may deserve a future `consensus` module rather than
  `p2p`; do not force that decision during this extraction.

## Consequences

Positive:

- Relay peer-management code becomes independently testable.
- Runtime becomes more focused on node assembly and sync execution.
- Future embedders can reuse the P2P layer without taking all runtime storage
  and ledger dependencies.
- The direction aligns with the connection manager, peer governor, discovery,
  and peer-sharing boundaries introduced by ADR-NET-008D/E/F.

Costs:

- The initial extraction creates package/import churn.
- Some classes must remain in runtime until additional ports exist.
- The project will temporarily have a mixed boundary while phases are in
  progress.

## Final Target Shape

```text
core-api
  Shared public contracts and configuration models.

p2p
  Peer endpoints, connection manager, peer governor, discovery, peer store,
  address policy, peer sharing, and eventually protocol-neutral tx diffusion.

runtime
  RuntimeNode, SyncSubsystem, PeerSession, ServeSubsystem, chain apply,
  storage integration, transaction admission, ledger, UTXO, producer, and
  devnet orchestration.

app
  Quarkus application adapter and distribution configuration.
```

The target shape is a decomposition, not a rewrite. Runtime remains the
composition root; `p2p` becomes the reusable relay networking layer.
