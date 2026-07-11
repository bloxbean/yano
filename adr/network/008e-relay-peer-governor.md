# ADR-NET-008E: Relay Peer Governor

## Status

Accepted. Initial relay-governor implementation and live preprod/Haskell
verification completed on 2026-06-30.

The implemented pass absorbs the existing `PeerGovernor`, bounds the known-peer
set, adds source/state/snapshot models, applies simple scoring and backoff,
feeds discovery through governor admission, exposes compact status, and points
peer sharing at `sharablePeers()`.

Strict local-root group valency, governor-issued outbound dial scheduling, and
capability-aware tx/body-fetch selection remain production-hardening follow-up
work. Canonical active-peer recovery intentionally remains with the existing
sync supervisor.

The live pass also added conservative selected-upstream promotion hysteresis:
near-tip observers do not trigger canonical peer switching unless the candidate
has a meaningful lead or the active peer has failed, and only after the
generation-fenced recovery path can apply the pending switch.

## Date

2026-06-30

## Context

ADR-NET-008D introduces a relay connection manager as the runtime authority for
Node-to-Node connection records. That gives Yano a place to track inbound and
outbound connections, admission limits, connection failures, and negotiated
protocol capabilities.

The next layer is peer governance. Today, Yano can run in upstream modes such
as trusted single, static multi, rooted relay, and p2p relay. It can also keep
observer sessions and collect candidate headers. However, peer selection is
still mostly driven by configured upstream order, a small `PeerGovernor`
selection helper, and recovery callbacks in `SyncSubsystem`.

This is a brownfield evolution, not a greenfield subsystem:

- `PeerGovernor` already exists in the runtime and selects hot peers from a
  `PeerStore`.
- `UpstreamGovernorConfig` already exists under `yano.upstream.governor.*` with
  `enabled`, `targetCold`, `targetWarm`, `targetHot`, and
  `maxConcurrentDials`.
- `PeerStore`, `FileBackedPeerStore`, and `InMemoryPeerStore` already persist or
  hold known peers.
- `PeerAddressPolicy` already acts as the address-hygiene choke point for
  configured and discovered peers.
- `PeerRecoveryFailureTracker`, active-upstream recovery maps, and the peer
  session supervisor already own canonical active-peer recovery.
- `RelayPeerSharingProvider` already reads sharable peers from an injected
  supplier and can be pointed at governor output later.

A relay-class node needs a peer governor that answers these questions:

- Which known peers should remain cold?
- Which peers should be connected and warm?
- Which peers should be hot and actively used for sync, peer sharing, and tx
  diffusion?
- Which failed peers should wait in backoff?
- Which inbound peers are useful, and which should remain passive?
- Which peers are safe to share with others?

This ADR defines the peer-governor boundary and policy model. It intentionally
does not define discovery source mechanics in detail. ADR-NET-008F will define
topology, peer snapshot, peer-sharing, and ledger-peer discovery feeds.

## Decision

Evolve the existing `PeerGovernor`, `UpstreamGovernorConfig`, and `PeerStore`
model into the relay peer governor. Do not introduce a second governor,
parallel peer table, or separate config namespace for the same concept.

The governor owns peer governance state and desired peer activity. It does not
own sockets. It asks the `RelayConnectionManager` to connect, disconnect, or
report connection facts. It does not perform chain selection. It tells the sync,
tx, and peer-sharing layers which peers are eligible hot peers; chain-selection
code still decides which chain, if any, should be adopted.

### Existing Components To Evolve

| Existing component | 008E role |
| --- | --- |
| `PeerGovernor` | Extend into the relay peer-governor implementation. It may be renamed later, but there must not be a second live governor with overlapping authority. |
| `UpstreamGovernorConfig` | Keep as the primary config contract. Existing fields are wired before adding new knobs. |
| `PeerStore` | Provides stable seed/cache entries loaded by the governor and flushed from bounded governor output. The governor does not use it as a live runtime-state journal. |
| `FileBackedPeerStore` | Can load and write stable endpoint entries. Runtime scores, hot/warm/backoff state, and connection events are not persisted. |
| `PeerAddressPolicy` | Remains the admission and hygiene gate for configured, discovered, and sharable peer addresses. It should be fixed to parse IPv6 endpoints correctly before being used for strict relay admission. |
| `RelayConnectionManager` | Owns connection lifecycle, connection limits, connection failures, and negotiated capabilities. The governor consumes immutable snapshots and `RelayConnectionListener` events. |
| `PeerRecoveryFailureTracker` and sync supervisor | Keep canonical active-peer recovery. The governor does not independently close or replace the canonical apply peer. |
| `RelayPeerSharingProvider` | Switches from raw `PeerStore` output to `governor.sharablePeers()` when the governor is enabled. |

If a future release introduces `yano.relay.governor.*`, it must be a documented
alias or migration path for `yano.upstream.governor.*`, not a second source of
truth.

### Responsibilities

The peer governor is responsible for:

- maintaining a bounded known-peer table;
- classifying peers by source;
- moving non-canonical peers between cold, warm, hot, backoff, and quarantined
  states;
- enforcing target peer counts;
- enforcing local-root valency and warm-valency when local-root groups are
  added;
- applying failure backoff and cooldown for dial-candidate selection;
- scoring peers from observed connection and sync behavior;
- choosing candidate outbound dials;
- selecting hot peers for ChainSync observation, body fetch, tx diffusion, and
  peer sharing;
- identifying sharable peers for peer-sharing responses;
- preserving bootstrap peers for recovery;
- exposing peer-governance status.

The peer governor is not responsible for:

- socket lifecycle details;
- inbound connection capacity limits;
- mini-protocol implementation;
- chain selection;
- block validation;
- transaction validation;
- ledger peer discovery queries;
- topology or peer snapshot parsing;
- canonical active-peer recovery.

### Peer Sources

Use explicit source classes:

```java
enum PeerSource {
    STATIC_UPSTREAM,
    LOCAL_ROOT,
    PUBLIC_ROOT,
    BOOTSTRAP,
    GOSSIP,
    LEDGER,
    INBOUND
}
```

Source semantics:

- `STATIC_UPSTREAM`: compatibility source for existing `yano.remote.*` and
  `yano.upstream.peers` configuration.
- `LOCAL_ROOT`: operator-controlled root peer, usually high trust and optionally
  advertised.
- `PUBLIC_ROOT`: configured public root peer, not automatically trusted.
- `BOOTSTRAP`: peer used to start network discovery and initial sync.
- `GOSSIP`: peer learned from peer sharing.
- `LEDGER`: peer learned from stake-pool relay metadata.
- `INBOUND`: peer that connected to Yano before Yano explicitly selected it.

### Peer States

Use explicit lifecycle states:

```java
enum PeerState {
    COLD,
    WARM,
    HOT,
    BACKOFF,
    QUARANTINED
}
```

State semantics:

- `COLD`: known but not currently connected or selected for connection.
- `WARM`: connected or desired-connected, but not actively used for canonical
  sync. Warm peers may be used for peer sharing and health checks.
- `HOT`: actively eligible for ChainSync observation, body fetch, tx diffusion,
  or peer sharing.
- `BACKOFF`: temporarily suppressed after connection or protocol failures.
- `QUARANTINED`: suppressed until operator action or expiry after behavior that
  should not be retried automatically.

`HOT` does not mean canonical. A hot peer may observe headers without mutating
canonical state. Canonical adoption remains controlled by the chain-selection
and apply boundaries.

### Governance Record

The mutable internal record should contain only facts owned by the governor:

- normalized endpoint;
- original configured endpoint, if any;
- source;
- state;
- trustable flag;
- sharable flag;
- advertise flag;
- local-root group id, if any;
- local-root valency;
- local-root warm valency;
- first seen time;
- last seen time;
- last hot time;
- backoff-until time;
- quarantine-until time, if any;
- score.

The governor must not copy connection-manager or sync facts into long-lived peer
records. During reconcile, it reads immutable snapshots from other owners:

- connection state, last connection failure, reconnect hints, and negotiated
  capabilities from the connection manager;
- header progress, keepalive freshness, and body-fetch progress from sync
  observation snapshots;
- tx diffusion activity from tx-diffusion observations.

This keeps a single source of truth for sockets, capabilities, and canonical
sync behavior while still allowing the governor to make policy decisions.

Use a separate immutable `PeerDescriptor` or `PeerGovernorPeer` snapshot for
public output. Do not use the mutable internal record as an API object.

### Targets And Configuration

Use the existing `yano.upstream.governor.*` namespace for the first
implementation:

```yaml
yano:
  upstream:
    governor:
      enabled: true
      targets:
        cold: 150
        warm: 8
        hot: 3
      max-concurrent-dials: 4
```

Config mapping:

- `targetCold` is treated as the known-peer target. The name is legacy; the
  implementation should document that known peers include cold, warm, hot,
  backoff, and quarantined peers.
- `targetWarm` is the desired connected non-hot pool.
- `targetHot` is the desired hot peer count.
- `maxConcurrentDials` limits governor-issued outbound dial attempts.

Additional knobs should be introduced only when implemented and needed by
operators. Deferred knobs include:

- `minHotPeers`;
- `reconcileInterval`;
- `churnInterval`;
- `failureBackoffMin`;
- `failureBackoffMax`;
- `inboundHotQuota`.

Until those knobs are added, the implementation should use conservative
constants and expose behavior through status. Do not ship a config key that has
no effect.

Compatibility:

- `trusted-single` remains single-peer and does not require the relay governor.
- `trusted-failover` may use the governor for failure ordering but should keep
  trusted semantics.
- `static-multi`, `rooted-relay`, and `p2p-relay` use the governor when enabled.
- Existing config maps into `STATIC_UPSTREAM` or root peers without breaking
  current profiles.

### Discovery Admission And Known-Peer Bounds

Discovery must feed peers through the governor, not directly into an unbounded
raw store. The governor is the admission point for:

- static upstream peers;
- bootstrap peers;
- peer snapshot entries;
- peer-sharing gossip;
- future ledger peer results;
- inbound peer observations.

Admission flow:

1. Normalize and validate the endpoint through `PeerAddressPolicy`.
2. Add or update the governed peer by normalized endpoint.
3. Preserve configured and bootstrap peers ahead of gossip during eviction.
4. Enforce the known-peer target before exposing peers to sync or sharing.
5. Flush the stable known-endpoint cache on a debounced cadence and on clean
   shutdown.
6. Keep runtime score, hot/warm/backoff state, and connection observations
   in memory only.

The persisted store, when used, should contain only stable endpoint facts. It
must not persist volatile score, backoff, or quarantine state unless an
explicit future requirement needs it. Scores should be relearned after restart
to avoid stale or poisoned persisted preference.

### Reconcile Loop

The governor runs a periodic reconcile loop:

1. Read immutable connection and sync snapshots.
2. Apply pending connection events from the `RelayConnectionListener` path.
3. Expire stale gossip peers when they exceed TTL and are not connected.
4. Move peers out of `BACKOFF` when `backoffUntil` has passed.
5. Ensure local-root warm and hot valency targets when local roots are enabled.
6. Select public, bootstrap, gossip, and ledger peers to satisfy warm target.
7. Promote eligible warm peers to hot until `targetHot` is reached.
8. Demote unhealthy or low-score hot peers.
9. Issue outbound dial intents through the connection manager, respecting
   `maxConcurrentDials`.
10. Publish an immutable status snapshot.

The reconcile loop must not hold governor locks while starting or stopping
network operations.

Phases that need capability-aware hot selection require the connection manager
to expose per-connection immutable facts, not only aggregate counts. That should
be added to the 008D connection snapshot before those phases are implemented.

### Scoring

The first score model should be simple and explainable:

- start from a neutral score;
- add score for successful connection establishment;
- add score for recent header progress;
- add score for stable keepalive responses;
- add score for successful tx diffusion activity;
- subtract score for connection failures;
- subtract score for protocol handshake failures;
- subtract score for stale hot peers;
- heavily penalize peers rejected by validation or protocol correctness checks.

The score is a selection hint, not a consensus rule. It must never override
chain-selection validation.

### Backoff And Quarantine

Expected connection failures put a peer in `BACKOFF`.

Backoff ownership is split deliberately:

- The governor owns backoff for non-canonical dial-candidate selection.
- The canonical active peer remains governed by the existing sync supervisor,
  `PeerRecoveryFailureTracker`, and generation-fenced recovery path.
- Existing ad-hoc observer and active-upstream retry maps in `SyncSubsystem`
  should be migrated into governor-managed policy over time, not duplicated
  forever.

Backoff uses exponential delay with jitter when the implementation reaches that
phase. Until configurable backoff is added, fixed conservative delays are
acceptable if they are visible in status.

Quarantine is reserved for behavior that should not be retried quickly:

- repeated protocol violations;
- repeated invalid data after validation is enabled;
- operator-denied peer;
- peer exceeds configured abuse thresholds.

Initial implementation may expose quarantine as an internal state with no
automatic operator UI beyond status.

### Inbound Governance

Inbound peers are not automatically hot.

When a peer connects inbound:

- the connection manager records the connection;
- it emits a `RelayConnectionEvent`;
- the governor implements `RelayConnectionListener` and adds or updates an
  `INBOUND` peer record;
- the peer starts as warm only if it negotiated useful capabilities;
- it can become hot only if inbound hot policy allows it and the peer has useful
  recent behavior.

This prevents unknown inbound peers from consuming all hot peer capacity while
still allowing useful duplex relay behavior.

### Sharable Peers

The governor owns the sharable-peer decision.

A peer can be shared if:

- it has a routable endpoint or private sharing is explicitly enabled;
- its source permits sharing;
- it is not quarantined;
- it is not in active backoff;
- it has not recently failed repeatedly;
- local-root `advertise` semantics allow it.

`RelayPeerSharingProvider` already has a supplier seam. Switching that supplier
to `governor.sharablePeers()` is the intended integration path.

### Interfaces

Initial interface shape:

```java
interface PeerGovernorService extends AutoCloseable, RelayConnectionListener {
    void addOrUpdatePeer(PeerDescriptor peer);
    void recordSyncObservation(PeerSyncObservation observation);
    void recordTxObservation(PeerTxObservation observation);
    List<PeerDescriptor> hotPeers(PeerUse use);
    List<PeerDescriptor> sharablePeers(int limit);
    PeerGovernorSnapshot snapshot();
}
```

The concrete type can continue to be `PeerGovernor` or can be renamed later. The
important rule is that there is one governor authority.

`PeerUse` should distinguish:

- `CHAIN_SYNC`
- `BODY_FETCH`
- `TX_DIFFUSION`
- `PEER_SHARING`

This allows later phases to make capability-aware choices without duplicating
peer state in every subsystem.

### Runtime Status

Extend `NodeStatus` or the relay status object with compact aggregate fields:

- `relayKnownPeerCount`
- `relayColdPeerCount`
- `relayWarmPeerCount`
- `relayHotPeerCount`
- `relayBackoffPeerCount`
- `relayQuarantinedPeerCount`
- `relaySharablePeerCount`
- `relayInboundPeerCount`
- `relayGossipPeerCount`
- `relayLedgerPeerCount`
- `relayBootstrapPeerCount`
- `relayGovernorTargetHotPeers`
- `relayGovernorTargetWarmPeers`
- `relayGovernorLastReconcileAtMillis`

A detailed peer table can be exposed later through an admin/debug endpoint.

## Implementation Plan

### Phase 0: Absorb Existing Governor And Store

- Extend the existing `PeerGovernor` instead of adding a parallel
  `RelayPeerGovernor`. **Implemented.**
- Keep `UpstreamGovernorConfig` as the config contract. **Implemented.**
- Add peer-source, peer-state, peer-descriptor, and snapshot model types.
  **Implemented.**
- Make the governor the bounded admission path for configured, snapshot, gossip,
  and inbound peers. **Implemented.**
- Use `PeerStore` as the stable seed/cache backend for known endpoints.
  **Implemented with debounced/shutdown flush; the governor does not persist
  runtime connection state.**
- Do not change outbound selection yet. **Preserved for compatibility; existing
  relay modes still ask the governor for hot peers.**

Acceptance:

- Existing upstream modes still behave as before.
- Governor status reflects known configured peers.
- Known peer count is bounded by the configured target.
- Unit tests cover add/update/dedupe/eviction/snapshot behavior.

### Phase 1: Backoff And Scoring

- Record connection failures and success through `RelayConnectionListener`.
  **Implemented.**
- Implement backoff timers for non-canonical dial candidates. **Implemented
  with a conservative fixed delay.**
- Implement simple score updates. **Implemented.**
- Expose backoff and score in internal snapshots. **Backoff counts are exposed;
  detailed scores remain internal/debug-only.**
- Keep canonical active-peer recovery with the sync supervisor. **Implemented
  by boundary.**

Acceptance:

- Repeated failed non-canonical peers enter backoff.
- Successful peers reset governor-owned failure state.
- Backoff peers are not selected as dial candidates.
- Canonical active-peer recovery still follows the existing generation-fenced
  path.

### Phase 2: Warm/Hot Target Selection

- Implement reconcile loop. **Implemented.**
- Add detailed immutable connection facts to the 008D connection snapshot if
  needed for capability-aware decisions. **Implemented in 008D.**
- Select warm peers from known cold peers. **Implemented.**
- Promote eligible warm peers to hot. **Implemented.**
- Demote unhealthy hot peers. **Implemented for backoff/quarantine paths.**
- Respect target counts and max concurrent dials. **Target counts implemented;
  `maxConcurrentDials` remains enforced by existing sync/connection-manager
  paths rather than governor-issued dial scheduling.**

Acceptance:

- With more known peers than target counts, selected peers stay bounded.
- With failed hot peers, governor chooses replacements.
- No duplicate outbound dials are issued.

### Phase 3: Local Root Valency

- Add local-root group metadata. **Partially implemented through source and
  descriptor fields.**
- Enforce valency and warm-valency per local-root group. **Deferred; current
  implementation preserves operator roots and applies global warm/hot targets.**
- Preserve existing static upstream compatibility. **Implemented.**

Acceptance:

- Local roots meet configured valency when enough peers are available.
- Local root failures trigger replacement within the same group when possible.

### Phase 4: Inbound Governance

- Add inbound peer records from connection-manager inbound events.
  **Implemented.**
- Apply inbound hot policy. **Implemented conservatively: inbound peers are
  observed and do not become sharable by default.**
- Promote useful inbound peers only when they negotiate required capabilities.
  **Deferred to capability-aware selection hardening.**

Acceptance:

- Unknown inbound peers do not consume all hot peer capacity.
- A capable inbound peer can become eligible for tx diffusion and peer sharing.

### Phase 5: Sharable Peer Selection

- Implement `sharablePeers(limit)`. **Implemented.**
- Honor source, advertise, routability, backoff, and quarantine. **Implemented
  for source/backoff/quarantine; stricter advertise/routability enforcement will
  be refined with local-root valency work.**
- Update relay peer-sharing provider to use governor sharable peers when the
  governor is enabled. **Implemented through the `SyncSubsystem` supplier.**

Acceptance:

- Peer-sharing responses are bounded and exclude backoff/quarantined peers.
- Local roots are shared only when configured as advertised.

### Phase 6: Sync And Tx Integration

- Let `SyncSubsystem` request hot peers from the governor in relay modes.
  **Implemented.**
- Let `TxDiffusion` consume peer class/capability hints from the governor.
  **Deferred; tx diffusion still uses its per-peer protocol state.**
- Keep canonical adoption behind existing chain-selection/apply logic.
  **Implemented by boundary.**

Acceptance:

- p2p relay mode can maintain hot peers through the governor.
- Tx diffusion continues to work with connected hot/inbound peers.
- Candidate headers still do not mutate canonical state directly.

### Phase 7: Live Verification

Run live preprod tests with:

- one static trusted upstream;
- multiple static roots;
- peer sharing enabled;
- one local inbound Cardano node;
- low hot-peer targets for deterministic testing;
- failing peers mixed with healthy peers.

Acceptance:

- Yano reaches tip.
- Failed peers enter backoff without noisy stack traces.
- Healthy replacement peers become hot.
- Inbound tx diffusion still works.
- Peer-sharing responses come from sharable governor peers.

Status: completed for this ADR pass on 2026-06-30.

Live verification used the rebuilt app jar from `app/` with preprod
`p2p-relay`, a deliberately bad configured peer, peer snapshot discovery, and a
local Haskell node connected inbound from `127.0.0.1:32000`.

Observed status during the run:

- `inSync=true`;
- `relayKnownPeerCount=41`;
- `relayHotPeerCount=3`;
- `relayWarmPeerCount=8`;
- `relayBackoffPeerCount=4`;
- `relayInboundPeerCount=1`;
- `relaySharablePeerCount=36`;
- `upstreamHotPeerCount=3`;
- `upstreamObserverPeerCount=2`;
- expected failed roots entered retry/backoff behavior without noisy stack
  traces;
- no selected-upstream switch churn was observed while near tip.

The test confirmed the implemented governor bounds and replacement behavior.
Strict per-group local-root valency, independent governor dial scheduling, and
capability-aware tx/body-fetch selection remain deferred hardening items.

## Invariants

- Peer state is not chain state.
- Hot peer selection does not imply canonical chain adoption.
- Trustable peer metadata must be visible to chain-selection code but not hidden
  inside the governor.
- The governor must not duplicate connection-manager ownership of socket state,
  failures, or negotiated capabilities.
- The governor must not independently close the canonical sync connection.
- Backoff and quarantine must not crash the node.
- Candidate headers remain outside canonical RocksDB.
- Tx admission still goes through `TransactionAdmission`.

## Non-Goals

- A second governor implementation running beside `PeerGovernor`.
- A second config namespace for the same governor policy.
- Topology file parsing.
- Peer snapshot loading.
- Ledger peer discovery.
- Genesis or Praos chain selection.
- Full block validation.
- Block production.
- NAT traversal.
- Churn policy in the first implementation.

## Risks

- Too much policy in the first implementation can make behavior hard to debug.
  The first score model should stay simple.
- Inbound governance can accidentally starve useful inbound peers if quotas are
  too low. Keep status visible before exposing many knobs.
- Reconcile loops can race with connection shutdown. Use immutable snapshots and
  listener callbacks outside locks.
- Replacing hot peers too aggressively can cause sync churn. Use conservative
  demotion and explicit stale/failure thresholds.
- Persisted peer preference can become stale or poisoned. Persist known peers,
  but relearn scores and transient failure state.

## Consequences

Yano gains a relay peer-governance layer without forcing a rewrite of
`PeerSession` or chain-selection code. Later discovery work can feed peers into
the governor, and later chain-selection work can consume bounded,
capability-aware hot peer sets instead of managing peers directly.
