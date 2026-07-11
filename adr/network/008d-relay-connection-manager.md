# ADR-NET-008D: Relay Connection Manager

## Status

Accepted. Core implementation and live preprod/Haskell verification completed
on 2026-06-30 for the scoped phases 0-6.

## Date

2026-06-30

## Context

ADR-NET-008 introduced pluggable upstream behavior. ADR-NET-008B added the
transaction diffusion boundary. ADR-NET-008C added relay-style peer sharing and
a small operator-facing relay discovery configuration.

Those pieces make Yano capable of connecting to upstream peers, serving inbound
N2N peers, accepting transaction diffusion, and answering peer-sharing requests.
However, connection ownership is still split across subsystems:

- `SyncSubsystem` and `PeerSession` own outbound upstream sessions.
- `ServeSubsystem` owns inbound N2N server sessions.
- `TxDiffusion` tracks peer tx state using peer ids passed by protocol handlers.
- Peer sharing currently reads a snapshot of known upstream peers.

This is enough for indexer and MVP relay behavior, but it is not enough for a
relay-class node. A relay needs one authoritative view of active N2N
connections so that it can:

- enforce inbound and per-IP connection limits;
- avoid duplicate outbound dials;
- handle inbound/outbound connection collisions deterministically;
- know which peers negotiated which mini-protocol capabilities;
- decide whether an inbound connection is usable for duplex relay work;
- provide consistent status and metrics;
- apply backoff and failure logging without noisy stack traces.

The connection manager should be introduced before the richer peer governor.
The peer governor will decide which peers should be cold, warm, or hot. The
connection manager should only own connection lifecycle, identity, limits,
capabilities, and status.

## Decision

Introduce a `RelayConnectionManager` runtime subsystem for Node-to-Node
connections.

The manager becomes the authoritative owner of N2N connection records, but it
does not initially replace `PeerSession`. The first implementation should wrap
and observe existing inbound/outbound connection creation, then gradually move
dial/listen decisions behind the manager once behavior is verified.

### Responsibilities

The connection manager is responsible for:

- connection identity and normalization;
- inbound admission control;
- outbound dial de-duplication;
- connection state transitions;
- negotiated capability recording;
- connection collision handling;
- failure counters and reconnect backoff hints;
- one-line expected network failure logging;
- status and metrics snapshots;
- lifecycle notifications to `TxDiffusion`, peer sharing, and future peer
  governor code.

The connection manager is not responsible for:

- choosing the best chain;
- selecting cold/warm/hot peers;
- deciding peer trust;
- validating blocks or transactions;
- implementing NAT traversal;
- storing non-canonical candidate chain data.

### Core Model

Add small value types in the runtime networking layer.

Use `ConnectionKey` for host/port identity. Do not introduce another
`PeerEndpoint` type here because the runtime already has a peer endpoint model
that includes protocol magic. Protocol magic belongs to network configuration,
not to the TCP connection key.

```java
record ConnectionKey(String host, int port) {}

enum ConnectionDirection {
    INBOUND,
    OUTBOUND
}

enum ConnectionState {
    CONNECTING,
    HANDSHAKING,
    ESTABLISHED,
    FAILED,
    CLOSED
}

record ProtocolCapabilities(
        Long negotiatedVersion,
        boolean chainSync,
        boolean blockFetch,
        boolean txSubmission,
        boolean peerSharing,
        boolean keepAlive,
        boolean query) {}
```

The concrete implementation can add fields as needed, but the status model
should expose at least:

- connection id;
- remote endpoint;
- normalized endpoint;
- direction;
- state;
- established time;
- last activity time;
- last failure reason;
- negotiated capabilities;
- whether the connection is usable for duplex relay work.

### Identity

Connection identity must be stable and normalized:

- hostnames are normalized case-insensitively;
- IP literals are normalized to canonical host address text;
- endpoint identity includes port;
- unresolved hostnames may be tracked by normalized hostname until a concrete
  remote address is known;
- inbound remote socket address and handshake peer data are both recorded when
  available.

The manager should keep both:

- a connection id unique to the local process;
- a connection key suitable for de-duplication and status.

IPv6 must be handled explicitly:

- bracketed endpoints such as `[::1]:3001` must parse correctly;
- unbracketed IPv6 literals must not be split with a naive last-colon rule;
- IP literals should be normalized to their canonical address form;
- per-IP accounting should key by normalized IP address, not by textual spelling.

### Inbound Admission

Before an inbound N2N server session is accepted into protocol handling,
`ServeSubsystem` asks the connection manager to reserve an inbound slot.

This requires a Yaci server hook. With the current server shape, Yano only sees
inbound peers after protocol handling has started, which is too late for cheap
admission control. Phase 1 therefore includes adding a Yaci
`ServerConnectionListener` or accept-veto hook that is invoked from the N2N
server accept path with the remote socket address before the session enters
handshake/protocol handling.

An interim implementation may register and close over-limit inbound peers after
handshake, but that is only a stopgap. It spends resources on peers that will be
rejected and cannot provide a reliable anti-flood limit.

The default limits are:

```yaml
yano:
  relay:
    connection:
      max-inbound-connections: 100
      max-connections-per-ip: 5
```

The limits apply only to inbound N2N relay server connections. Outbound dials
initiated by Yano do not count against `max-connections-per-ip`. Node-to-client
server connections should remain governed by their existing server
configuration.

`max-connections-per-ip` is an anti-abuse control, not a consensus rule. NAT and
CGNAT can put multiple legitimate peers behind one IP address, so the default
must be configurable.

If an inbound connection is rejected because of capacity, the log should be a
single expected-network-failure line with the remote address and reason. It
should not print a stack trace.

### Outbound Dials

Before `SyncSubsystem` or a future peer governor starts an outbound peer
session, it asks the connection manager to reserve an outbound dial.

The manager must prevent duplicate concurrent dials to the same normalized
endpoint. If a dial is already in progress or an equivalent established
connection exists, the caller receives a decision instead of starting a new
socket:

- `DIAL_RESERVED`
- `ALREADY_CONNECTING`
- `ALREADY_CONNECTED`
- `BACKOFF_ACTIVE`
- `REJECTED_BY_LIMIT`

The actual socket creation may still live in `PeerSession`/Yaci during the
first implementation. The important first step is that all attempts are
registered through one authority.

The initial outbound integration point is `PeerClientFactory`. `PeerSession`
already receives peer clients through this factory, so the connection manager
can reserve, observe, and release outbound attempts by wrapping the factory
rather than rewriting `PeerSession` first.

### Deferred Source-Port Binding

Outbound source-port binding is deliberately out of scope for this ADR. It is a
useful relay-identity optimization, but it is platform-sensitive and requires
transport-level Yaci support for local bind configuration. It should be handled
by a later spike/ADR that covers Java socket behavior, `TIME_WAIT`,
`SO_REUSEADDR`/`SO_REUSEPORT`, and collision behavior explicitly.

Follow-up status: implemented as a default-enabled hardening item in
ADR-NET-008G on 2026-06-30 through
`yano.relay.connection.source-port-reuse`.

### Collision Handling

The connection manager must make duplicate and collision behavior explicit:

- Do not start a new outbound dial if a usable established connection to the
  same endpoint already exists.
- The connection manager must not independently close the active canonical sync
  connection. Canonical sync shutdown/switching remains owned by the sync
  supervisor through the generation-fenced recovery/switch path.
- If an inbound connection arrives while an outbound dial to the same endpoint
  is connecting, record both states and let the first usable established
  connection win for relay purposes.
- If two same-direction duplicate connections exist, keep the established
  connection with the most useful negotiated capabilities and close/reject the
  duplicate gracefully.
- If one connection negotiated peer sharing or tx submission and the other did
  not, capability-aware users may prefer the richer connection.

The first implementation can be conservative: it may record collisions and
avoid duplicate outbound dials without aggressively closing established
connections. Aggressive replacement can be added after status and tests prove
the model.

### Capability Recording

After handshake negotiation, the connection manager records the negotiated N2N
version and mini-protocol capabilities.

This should reuse existing protocol facts rather than inventing a parallel
capability detector. Post-handshake, Yaci already has the negotiated protocol
version and version data available through `Agent.getProtocolVersion()`,
`AcceptVersion`, and `N2NVersionData`. The Yano work is to add
listener/plumbing so those facts reach the connection manager for both outbound
clients and inbound server sessions.

At minimum this should include:

- ChainSync available;
- BlockFetch available;
- TxSubmission available;
- PeerSharing available;
- KeepAlive available;
- whether the connection is inbound, outbound, or usable as duplex.

`TxDiffusion` and peer sharing should eventually consume capability data from
the connection manager rather than each subsystem independently inferring peer
state.

### Listener SPI

Add an explicit listener boundary so peer governance, tx diffusion, and peer
sharing do not reach into subsystem-local session state:

```java
interface RelayConnectionListener {
    void onConnectionEvent(RelayConnectionEvent event);
}
```

Listener rules:

- listeners receive immutable event objects;
- listeners are invoked outside registry locks;
- listener failures are logged and do not block registry state transitions;
- listener delivery is best-effort and should be idempotent for consumers.

### Failure Handling

Expected network failures are normal in public peer networks. The connection
manager should reuse the existing one-line failure summarization path instead
of creating a second classifier. The failure taxonomy should map onto
`PeerFailureMessage.summarize()` behavior for:

- DNS resolution failure;
- connection refused;
- connection timeout;
- remote reset;
- handshake refusal;
- protocol version mismatch;
- inbound rejected by local limit.

Unexpected internal errors may still log stack traces.

The manager should track:

- last failure reason;
- failure count;
- last failure time;
- suggested reconnect-after time.

Reconnect policy remains owned by the existing supervisor or future peer
governor. The connection manager only provides the facts and backoff hints.

### Concurrency

The connection registry is shared by inbound server threads, outbound sync
workers, tx-submission handlers, peer-sharing handlers, and status endpoints.

Rules:

- Do not hold the registry lock while performing DNS resolution, socket dials,
  handshake negotiation, or protocol shutdown.
- State transitions must be atomic per connection id.
- Endpoint indexes must be updated consistently with connection state changes.
- Lifecycle listeners must be invoked outside registry locks.
- Status snapshots must be immutable copies.
- It is acceptable for a close/reconnect callback to observe that the
  connection has already been replaced; it should no-op in that case.

### Configuration

Initial configuration:

```yaml
yano:
  relay:
    connection:
      max-inbound-connections: 100
      max-connections-per-ip: 5
```

Compatibility:

- Existing non-relay profiles keep current behavior.
- If `yano.relay.auto-discovery=false` and `yano.server.enabled=false`, the
  connection manager may still be created for outbound status, but inbound
  limits do not apply.

### Runtime Status

Extend `NodeStatus` with aggregate fields:

- `relayInboundConnectionCount`
- `relayOutboundConnectionCount`
- `relayEstablishedConnectionCount`
- `relayConnectingConnectionCount`
- `relayRejectedInboundConnections`
- `relayFailedOutboundConnections`
- `relayConnectionsPerIpMax`

A detailed connection listing can be added later as an admin endpoint. The
default status endpoint should remain compact.

## Implementation Plan

### Phase 0: Model And Passive Registry

- Add connection model types. **Implemented.**
- Add `RelayConnectionManager` interface and default implementation. **Implemented.**
- Register outbound attempts by wrapping the existing `PeerClientFactory`
  integration point.
- Register inbound sessions through the Yaci accept hook added in phase 1.
  **Implemented.**
- Add status counters. **Implemented.**

Acceptance:

- Existing trusted-single and p2p-relay profiles still start.
- Status shows inbound/outbound counts.
- Unit tests cover state transitions and immutable snapshots.

### Phase 1: Yaci Inbound Hook And Admission Limits

- Add a Yaci N2N server accept listener or accept-veto hook. **Implemented.**
- Pass remote socket address and provisional connection id to Yano before
  protocol handling starts. **Implemented.**
- Enforce `max-inbound-connections`. **Implemented.**
- Enforce `max-connections-per-ip`. **Implemented.**
- Release reservations on handshake failure and disconnect. **Implemented.**
- Log rejected inbound connections as one-line expected failures. **Implemented.**
- Avoid using Yaci's internal session map as the authoritative Yano connection
  count. **Implemented.**

Acceptance:

- Tests can reserve up to the limit and reject the next connection.
- Per-IP limits are enforced.
- Releasing a connection frees capacity.
- Over-limit inbound peers are rejected before full protocol handling.

### Phase 2: Outbound Dial De-Duplication And Failure Facts

- Route outbound peer-session starts through `PeerClientFactory` wrapping.
  **Implemented.**
- Suppress duplicate concurrent dials. **Implemented.**
- Record dial failures. **Implemented.**
- Reconnect-after hints remain owned by the existing supervisor/future governor.
- Convert expected dial failures to one-line logs.

Acceptance:

- Duplicate outbound starts for the same endpoint produce one dial.
- DNS/refused/timeout failures do not print noisy stack traces.
- Existing peer recovery still works.

### Phase 3: Capability Recording And Listener SPI

- Record negotiated protocol capabilities after handshake. **Implemented.**
- Add `RelayConnectionListener`. **Implemented.**
- Publish immutable connection lifecycle events outside registry locks.
  **Implemented.**
- Reuse existing Yaci negotiated-version/version-data facts. **Implemented.**

Acceptance:

- TxSubmission and PeerSharing can determine whether a connection supports the
  needed protocol.
- Future peer governor code can subscribe without inspecting session internals.

### Phase 4: Collision Handling

- Detect same-endpoint outbound duplicates. **Implemented.**
- Prefer an already established usable outbound connection over duplicate
  dials. **Implemented conservatively by rejecting the duplicate dial.**
- Never close the active canonical sync connection directly from the connection
  manager. **Implemented by design; the manager records/closes only its own
  connection records and does not call sync-supervisor switching APIs.**
- Inbound/outbound same-endpoint replacement remains conservative and
  observable only.
- Expose capability-aware lifecycle events. **Implemented.**

Acceptance:

- Inbound connection plus outbound dial to same endpoint does not create
  uncontrolled duplicate sessions.
- Canonical sync remains under the sync supervisor's generation-fenced recovery
  path.

### Phase 5: Subsystem Integration

- `TxDiffusion` currently receives N2N tx-submission peer connect/disconnect
  notifications from `YaciTxSubmissionHandler`; migrating that source to the
  connection manager remains follow-up work to avoid duplicate peer ids.
- Peer-sharing provider can use connection-manager facts for active/sharable
  peers when available. **Implemented.**
- Future peer governor can consume the registry instead of inspecting
  subsystem-local sessions. **Implemented through `RelayConnectionListener` and
  immutable connection snapshot details.**

Acceptance:

- Existing tx diffusion tests still pass.
- Peer sharing still returns bounded peers.
- Connection-manager status can be compared with tx diffusion peer counts during
  live tests.

### Phase 6: Live Verification

Run live preprod tests with:

- one configured upstream;
- multiple discovered upstreams;
- one local Haskell node connecting inbound;
- tx diffusion enabled;
- peer sharing enabled;
- inbound limit set low for rejection testing.

Acceptance:

- Yano syncs to tip.
- Haskell node can connect inbound.
- A submitted tx can diffuse over the inbound connection.
- Expected failed peers produce one-line logs.
- Status counters match observed connections.
- Restart/shutdown releases all connection records.

Status: completed for this ADR pass on 2026-06-30.

Live verification used the rebuilt app jar from `app/` with the preprod config,
one intentionally invalid static upstream, discovered snapshot peers, and a
local Haskell preprod node connected inbound from `127.0.0.1:32000`.

Observed status during the run:

- `inSync=true`;
- active/canonical peer `132.226.203.38:6001`;
- `relayOutboundConnectionCount=3`;
- `relayInboundConnectionCount=1`;
- `relayEstablishedConnectionCount=4`;
- `relayKnownPeerCount=41`;
- Haskell downstream opened ChainSync and TxSubmission against Yano's N2N
  server on `127.0.0.1:13338`;
- expected invalid/refused peers logged as one-line warnings without stack
  traces;
- no duplicate-outbound warning and no connection-manager initiated canonical
  sync replacement.

Inbound limit rejection was covered by unit tests rather than the live preprod
run.

## Invariants

- The active canonical sync path remains generation-fenced by the existing
  runtime apply boundary.
- Connection-manager state does not imply canonical chain selection.
- Candidate headers remain outside canonical chain storage.
- Tx admission still goes through `TransactionAdmission`.
- Inbound rejection must not crash the process.
- The connection manager must not independently close or replace the active
  canonical sync connection.

## Non-Goals

- Full cold/warm/hot peer governor.
- Ledger peer discovery.
- Genesis or Praos chain-selection changes.
- Full block validation.
- Outbound source-port binding was excluded from the original ADR-NET-008D
  scope and implemented later as ADR-NET-008G Phase 1.
- NAT traversal or automatic router configuration.
- Public reachability guarantees.
- Replacement of Yaci protocol implementations.

## Risks

- Pre-handshake inbound admission requires a Yaci server hook. Without that
  hook, Yano can only observe inbound peers after resources have already been
  spent.
- Closing duplicate connections too aggressively could disrupt active sync.
  The first implementation should prefer observing and suppressing new
  duplicates before replacing established sessions.
- Connection identity is harder for unresolved DNS names than IP literals.
  The manager should keep both configured endpoint and resolved remote address.
- Status endpoint growth can become noisy. Keep aggregate status compact and
  add detailed diagnostics separately.

## Consequences

Yano gets a single N2N connection authority that later relay work can build on.
The immediate benefit is cleaner logs, safer inbound limits, better tx diffusion
peer accounting, and fewer duplicate outbound sessions. The longer-term benefit
is that peer governance, chain selection, peer sharing, and tx relay can all use
the same connection facts instead of maintaining separate partial views.
