# ADR-NET-008G: Relay Production Hardening Risks

## Status

Pending, with Phase 1 source-port reuse implemented on 2026-06-30.

This ADR tracks the remaining relay-networking risks after ADR-NET-008D,
ADR-NET-008E, and ADR-NET-008F. Most items remain pending and should be
implemented as small hardening follow-ups.

## Date

2026-06-30

## Context

ADR-NET-008D introduced the relay connection manager. ADR-NET-008E introduced
the bounded relay peer governor. ADR-NET-008F introduced relay discovery,
topology, peer snapshot, and discovery-first bootstrap behavior.

Those ADRs moved Yano from a single-upstream/indexer-oriented node toward a
relay-capable node. The implemented scope has been verified on preprod with:

- multiple outbound upstream connections;
- a local Haskell node connected inbound;
- official preprod peer snapshot ingestion;
- discovery-first recovery after bad configured peers;
- bounded peer-governor status;
- ChainSync and TxSubmission server interop with the Haskell downstream.

However, Yano should not yet claim full production public-relay parity with a
Haskell node or other mature Cardano relay implementations. The remaining work
is mostly hardening and long-running public-network behavior.

This ADR exists so the deferred risks are explicit and can be implemented in
smaller future PRs without expanding the already completed 008D/008E/008F
scope.

## Decision

Track the remaining relay-production hardening work in this pending ADR.

The implementation should continue to follow the existing subsystem boundaries:

- the connection manager owns socket identity, admission, connection lifecycle,
  and negotiated capabilities;
- the peer governor owns peer policy, state, backoff, churn, valency, and
  sharable-peer selection;
- discovery owns topology, snapshots, peer sharing, and ledger peer sources;
- sync and chain-selection code own canonical chain adoption;
- transaction diffusion owns per-peer transaction relay protocol state.

Do not add broad configuration knobs until the corresponding behavior exists
and there is an operator need. Prefer conservative defaults and compact status
first.

## Remaining Risks

### 1. Outbound Source-Port Reuse

Status: implemented as a default-enabled, availability-safe feature on
2026-06-30.

Before this follow-up, Yano listened on its configured relay port but outbound
N2N dials used OS-assigned ephemeral source ports.

Desired relay behavior:

```text
listen endpoint:      yano.example:13338
outbound dial source: yano.example:13338 -> peer.example:3001
```

Previous behavior:

```text
listen endpoint:      yano.example:13338
outbound dial source: yano.example:57933 -> peer.example:3001
```

Risk addressed:

- public peers may observe an outbound ephemeral endpoint that is not the
  advertised relay endpoint;
- peer sharing is less useful because observed connection identity and
  advertised relay identity can diverge;
- inbound/outbound duplex detection is weaker.

Implementation notes:

- implemented with Yaci transport support for optional local bind host/port;
- enabled in Yano by default with
  `yano.relay.connection.source-port-reuse`;
- Yano derives a concrete local interface address from the route to an
  upstream peer before binding outbound dials, avoiding wildcard-bind conflicts
  with the relay listener;
- Yano opts into Yaci's ephemeral fallback for local-bind failures so `TIME_WAIT`
  or platform socket rules do not stop sync;
- falls back to OS-assigned source ports for individual dials when Java socket
  behavior, `TIME_WAIT`, `SO_REUSEADDR`, or platform differences prevent
  source-port reuse;
- remains configurable because some deployments cannot bind outbound dials to
  the listen port.

### 2. Full Inbound/Outbound Collision Handling

ADR-NET-008D implemented conservative duplicate suppression. It does not yet
perform the full relay-grade inbound/outbound replacement policy.

Risk:

- simultaneous inbound and outbound connections to the same peer may not
  converge to the best duplex connection;
- capability-rich connections may not always replace weaker duplicates;
- long-running public nodes may carry unnecessary duplicate sessions.

Implementation notes:

- keep the invariant that the connection manager must not close the active
  canonical sync connection directly;
- replacement decisions should be advisory to the owning subsystem unless the
  connection is clearly non-canonical;
- status should expose collision counts before aggressive replacement is
  enabled by default.

### 3. Strict Local-Root Valency

ADR-NET-008E preserves local-root metadata but does not yet enforce strict
per-group hot and warm valency.

Risk:

- configured local-root groups may not maintain the operator-requested number
  of warm/hot peers;
- failures may be replaced from the global pool instead of the same root group;
- topology behavior is not yet fully aligned with relay operators'
  expectations.

Implementation notes:

- enforce `hotValency` and `warmValency` per local-root group;
- preserve existing static upstream compatibility;
- expose per-source/per-group counts through a debug/admin endpoint before
  adding more default status fields.

### 4. Peer Churn And Richer Scoring

Yano currently has bounded peer state, hot/warm/backoff state, and simple
scoring. It does not yet have production-grade churn and scoring policy.

Risk:

- the node may stay connected to a narrow peer set for too long;
- slow or stale peers may remain hot longer than ideal;
- gossip peers may not rotate enough to resist stale topology or partial
  eclipse conditions.

Implementation notes:

- add controlled churn for gossip/public-root peers;
- score peers by connection stability, recent failures, tip freshness, header
  arrival, block-fetch latency, and negotiated capabilities;
- keep scores transient by default and relearn after restart;
- avoid churn of the active canonical peer unless chain-selection/sync
  supervisor explicitly requests it.

### 5. Governor-Issued Dial Scheduling

The current implementation still uses existing sync/observer paths to request
hot peers. The governor does not yet run a full independent dial scheduler.

Risk:

- max concurrent dials and warm/hot target maintenance are partly enforced by
  existing subsystem paths instead of one governor loop;
- recovery and peer rotation are less predictable under heavy churn;
- future tx/body-fetch peer selection may duplicate dial decisions.

Implementation notes:

- add explicit dial intents from the governor to the connection manager;
- keep canonical active-peer recovery with the sync supervisor;
- enforce `maxConcurrentDials` in one place;
- make dial scheduling deterministic and testable with fake clocks.

### 6. Established-Session Peer Sharing Client

Yano can serve peer-sharing requests and still has seed-based peer-sharing
discovery. It does not yet ask already-established hot/warm peers for peers over
the existing multiplexed N2N session.

Risk:

- peer-sharing discovery is less relay-like;
- extra seed-dialing behavior is needed for gossip;
- Yano cannot fully use existing hot peer capabilities for discovery.

Implementation notes:

- requires Yaci client mini-protocol plumbing on established N2N sessions;
- peer-sharing results should enter discovery as `GOSSIP` peers with TTL;
- empty peer-sharing responses should not be treated as protocol failures;
- protocol errors should feed governor backoff/scoring.

### 7. Ledger Peer Discovery

Yano does not yet derive relay peers from on-chain stake pool relay metadata.
ADR-NET-008F keeps `ledger-peers` disabled until relay endpoint persistence is
rollback-safe.

Risk:

- public-network peer discovery depends more on static roots, snapshots, and
  peer sharing;
- the node cannot naturally transition to stake-weighted ledger peers;
- peer snapshots can become stale without a live ledger source.

Implementation notes:

- persist pool relay endpoints from pool registration certificates;
- make the relay endpoint index rollback-safe in account-state storage;
- combine relay endpoints with active stake ranking;
- honor `useLedgerAfterSlot`;
- keep ledger peers disabled by default until this provider is reliable.

### 8. Snapshot Refresh And Last-Good Cache

The current snapshot implementation loads configured snapshots during service
start. Periodic refresh and last-good fallback are deferred.

Risk:

- long-running nodes may keep stale snapshot peers until restart;
- temporary snapshot URL failures cannot fall back to a known-good refreshed
  copy;
- operators have less visibility into discovery freshness.

Implementation notes:

- add bounded periodic refresh;
- keep last-good snapshot records with source timestamps;
- expose last refresh time, accepted count, and last error in status or debug
  diagnostics;
- enforce the existing byte cap before parsing every refresh.

### 9. Capability-Aware Tx And Body-Fetch Peer Selection

Tx diffusion still uses tx-submission protocol peer state. Body fetch still
uses the current sync/body-fetch path rather than a richer multi-peer scheduler.

Risk:

- peer capability facts are not yet fully shared across connection manager,
  governor, tx diffusion, and body fetch;
- body fetch does not yet exploit multiple hot peers;
- tx peer selection can drift from governor connection identity.

Implementation notes:

- use connection-manager capabilities and governor `PeerUse` decisions for
  `TX_DIFFUSION` and `BODY_FETCH`;
- keep tx-submission protocol peer state as the protocol authority for in-flight
  tx ids/bodies;
- avoid changing canonical chain adoption while improving body/tx peer choice.

### 10. Public Reachability Diagnostics

Yano can advertise a host/port and can accept inbound connections, but it does
not yet provide strong diagnostics for whether the advertised endpoint is
publicly reachable.

Risk:

- operators may think the node is discoverable when NAT/firewall rules prevent
  inbound public connections;
- peer sharing can advertise an endpoint that only works locally;
- relay health is harder to assess in production.

Implementation notes:

- add diagnostics that distinguish configured advertised endpoint, bound local
  endpoint, observed inbound peers, and self-advertised peer-sharing endpoint;
- do not implement automatic router/NAT traversal in this ADR;
- document that public reachability is an operator/network responsibility.

## Implementation Plan

### Phase 0: Diagnostics And Status

- Add debug/admin diagnostics for connection collisions, peer source counts,
  discovery freshness, and advertised endpoint state.
- Keep the default status endpoint compact.

### Phase 1: Source-Port Binding Spike

Status: implemented on 2026-06-30.

- Added optional Yaci local-bind support for outbound N2N dials.
- Added Yano configuration through
  `yano.relay.connection.source-port-reuse`.
- Added local route-based bind-host resolution so the feature binds to the
  concrete local interface address instead of wildcard.
- Added local-bind fallback to OS-assigned source ports for individual dials
  that fail due to bind contention.
- Made source-port reuse enabled by default in the bundled application config
  and runtime fallback.
- Kept enabled in `app/config/application.yml` for the local preprod relay
  profile used during manual verification.
- Linux verification is still recommended before relying on strict source-port
  reuse rates in packaged relay profiles.

### Phase 2: Collision Policy Hardening

- Track collision events explicitly.
- Implement safe non-canonical duplicate replacement.
- Keep canonical sync replacement under the sync supervisor.

### Phase 3: Local-Root Valency

- Enforce per-group hot and warm valency.
- Add tests for local root replacement after failure.
- Preserve static upstream compatibility.

### Phase 4: Governor Dial Scheduler And Churn

- Add governor-issued dial intents.
- Add conservative churn for public/gossip peers.
- Expand scoring while keeping the config surface small.

### Phase 5: Established-Session Peer Sharing

- Add or expose the required Yaci client hook.
- Query eligible hot/warm peers for peer-sharing results.
- Admit results as TTL-bound gossip peers through the governor.

### Phase 6: Snapshot Refresh

- Add periodic snapshot refresh.
- Persist or retain last-good snapshot records.
- Expose refresh status and last error.

### Phase 7: Ledger Peer Discovery

- Add rollback-safe pool relay endpoint persistence.
- Add ledger peer provider.
- Enable `ledger-peers` only after tests prove rollback and restart behavior.

### Phase 8: Capability-Aware Tx/Body Peer Selection

- Feed connection capabilities and governor `PeerUse` results into tx diffusion
  and body-fetch scheduling.
- Keep tx protocol state and canonical chain selection boundaries intact.

## Acceptance Criteria

Yano should not be described as production public-relay ready until:

- outbound source-port reuse or an explicitly documented alternative is
  available; implemented as default-enabled best-effort reuse on 2026-06-30;
- inbound/outbound duplicate and collision behavior is deterministic;
- local-root valency is enforced;
- peer churn and scoring keep long-running peer sets healthy;
- established-session peer sharing works;
- ledger peer discovery works from rollback-safe relay endpoint state;
- snapshot refresh has last-good fallback;
- tx/body peer selection uses shared capability/governor facts;
- diagnostics make public reachability and advertised endpoint state clear.

## Non-Goals

- Praos/Genesis chain selection.
- Full ledger validation.
- Validated mempool and full transaction rules.
- Block production.
- Leios or Dijkstra support.
- Automatic NAT traversal or router configuration.

Those are important for full node parity but belong in separate consensus,
ledger, mempool, and block-production ADRs.

## Consequences

This ADR keeps the relay-networking hardening backlog explicit without
reopening the completed 008D/008E/008F implementation scope. It gives future
work a clear order and prevents Yano from prematurely claiming production
public-relay parity before the remaining long-running-network risks are closed.
