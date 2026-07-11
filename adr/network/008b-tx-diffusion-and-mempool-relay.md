# ADR-NET-008B: Transaction Diffusion and Mempool Relay

## Status

Accepted / Implementation Phases 0-4 Complete

## Implementation Status

- 2026-06-30: Phase 0 implemented.
- 2026-06-30: Phase 1 implemented. Added the `TxDiffusion` boundary,
  `PeerTxState`, bounded per-peer announcement/request state, mempool read
  methods, and `MemPoolTransactionReceivedEvent` observation.
- 2026-06-30: Phase 2 implemented. Routed local submit upstream forwarding
  through `TxDiffusion` when enabled while preserving legacy `active-selected`
  and `all-hot-trusted` behavior when only `yano.upstream.tx.forwarding` is
  configured.
- 2026-06-30: Phase 3 implemented. Routed N2N TxSubmission tx-id and tx-body
  handling through `TxDiffusion` when enabled, preserved the disabled-mode
  legacy `txsubmission` admission path, preserved the single
  `TransactionAdmission` path, and added peer-aware `tx-diffusion:<peer>`
  origins.
- 2026-06-30: Phase 4 implemented for the Yano diffusion boundary and the
  Yaci `PeerClient`/`TxSubmissionAgent` queue-backed upstream serving path.
  Yaci's inbound `TxSubmissionServerAgent` is the receiving/requesting role, so
  serving Yano's mempool to peers that only connect inbound would require
  wiring the TxSubmission client role for those sessions or opening a
  corresponding outbound peer connection; Yano now enforces policy/accounting
  at the boundary and documents that remaining server-session role wiring as
  later hardening.
- Status fields now include mempool pressure, diffusion mode, peer count,
  inbound/outbound counters, served tx/body bytes, and in-flight pressure.
- 2026-06-30: Runtime smoke-tested the app module on preprod with
  `yano.tx.diffusion.mode=local-submit-only`. The status endpoint exposed the
  new diffusion and mempool fields while the node followed preprod through
  p2p-relay mode.

## Date

2026-06-30

## Context

ADR-NET-008 introduced pluggable upstream modes and the first multi-peer relay
surface. Phase 6 added upstream transaction forwarding policies:

- `active-selected`
- `all-hot-trusted`
- `disabled`

That is useful for local REST submissions, but it is not full relay-style
transaction diffusion.

Current runtime shape:

- `TxSubsystem` owns transaction admission, the in-memory mempool,
  `TransactionValidateEvent`, `MemPoolTransactionReceivedEvent`, and eviction.
- `YaciTxSubmissionHandler` can admit transactions received over the
  TxSubmission mini-protocol into `TransactionAdmission`.
- `SyncSubsystem` forwards locally accepted transactions to upstream peers
  through the configured `yano.upstream.tx.forwarding` policy.
- The mempool evicts confirmed transactions, expired transactions, and excess
  transactions by count.

Existing building blocks should be reused:

| Need | Existing code to build on |
|---|---|
| Mempool transaction entry | `MemPoolTransaction` already carries `seqId`, `txHash`, `txBytes`, `txBodyType`, and `insertedAt`. |
| TTL/count/confirmed eviction | `DefaultMempoolEvictionPolicy` already evicts expired, excess, and block-confirmed transactions. |
| Single admission path | `TxSubsystem.admitTransaction()` already publishes `TransactionValidateEvent`, mutates the mempool, and publishes `MemPoolTransactionReceivedEvent`. |
| Partial tx-id tracking | `YaciTxSubmissionHandler.knownTxIds` already tracks seen tx ids for the current blocking TxSubmission path. |
| Local-submit forwarding | `SyncSubsystem.submitTxBytes()` already implements `disabled`, `active-selected`, and `all-hot-trusted`. |

Gaps:

- There is no per-peer transaction diffusion state.
- There is no shared tx read view that can answer "which tx ids should this
  peer learn about next?"
- The current upstream forwarding path eagerly sends full transaction bytes.
- Yano does not yet request transaction bodies from upstream peers using a
  governed pull model.
- Yano does not yet serve local mempool transaction bodies to downstream peers
  with per-peer rate and in-flight limits.
- The mempool tracks count but not byte pressure, per-tx TTL, origin, validation
  status, or per-peer diffusion metadata.

Full tx diffusion should be a separate runtime boundary. It must not be modeled
as "broadcast every full transaction to every connected peer".

## Decision

Introduce a `TxDiffusion` boundary around the existing `TxSubsystem` mempool
and transaction admission flow.

The current `yano.upstream.tx.forwarding` behavior remains as a compatibility
layer for local submissions. Full diffusion is introduced behind new tx
diffusion configuration and can be enabled gradually.

The diffusion model is pull-oriented:

1. Accepted transactions enter a local mempool once.
2. Peers exchange transaction ids and sizes.
3. Unknown transaction bodies are requested only when local policy and budgets
   allow it.
4. Transaction bodies are served from the mempool only when requested and only
   within per-peer limits.
5. Each peer has explicit diffusion state so Yano avoids repeatedly announcing,
   requesting, or serving the same transaction.

Full transaction bodies should not be pushed eagerly to all peers as the primary
relay behavior.

## Goals

- Preserve existing local transaction submission behavior.
- Preserve existing `yano.upstream.tx.forwarding` compatibility.
- Admit local and network transactions through one transaction admission path.
- Extend the existing mempool transaction metadata needed for safe relay
  behavior: size, origin, expiry, validation status, and byte pressure.
- Track per-peer tx diffusion state for upstream and downstream peers.
- Bound memory, bandwidth, in-flight transactions, and peer abuse.
- Make tx diffusion observable in status, logs, events, and later metrics.
- Keep untrusted full diffusion disabled until validation and abuse controls are
  strong enough.

## Non-Goals

- No immediate full parity with Haskell `cardano-node` mempool behavior.
- No consensus-level transaction validation in this ADR.
- No durable mempool journaling in the first implementation.
- No guarantee that every transaction accepted by Yano will be accepted by the
  public network.
- No change to canonical chain selection or block body apply behavior.

## Current Compatibility Mode

`yano.upstream.tx.forwarding` remains supported:

| Value | Current behavior |
|---|---|
| `disabled` | Do not forward locally submitted transactions upstream. |
| `active-selected` | Forward locally submitted transactions to the selected active upstream peer. |
| `all-hot-trusted` | Forward locally submitted transactions to the selected peer and trusted observer peers. |

This path is local-submit forwarding, not full diffusion. It should remain
available because it is simple and useful for indexer/API deployments that only
need submitted transactions to reach the network.

## Proposed Configuration

New configuration should live under `yano.tx.diffusion` because tx diffusion is
not only an upstream concern. It spans local submissions, upstream peers,
downstream peers, and the runtime mempool.

```yaml
yano:
  tx:
    mempool:
      max-txs: 10000
      max-bytes: 134217728
      ttl-seconds: 10800

    diffusion:
      mode: disabled # disabled | local-submit-only | trusted-hot | all-hot
      limits:
        max-in-flight-txs-per-peer: 100
        max-in-flight-bytes-per-peer: 1048576
        peer-cooldown-ms: 60000
```

`mode` is the master switch. Directional behavior is derived from it:

| Mode | Inbound network txs | Outbound txs |
|---|---|---|
| `disabled` | Disabled | Disabled |
| `local-submit-only` | Disabled | Current local-submit forwarding semantics only |
| `trusted-hot` | Trusted hot peers only | Trusted hot peers only |
| `all-hot` | All hot peers, experimental | All hot peers, experimental |

Keep other policy values as internal defaults first: maximum tx size from
protocol parameters when available, tx-id announcement batch size, tx-body
request batch size, and rejection threshold before cooldown. Promote a value to
configuration only after operational testing shows it needs tuning.

Compatibility mapping:

- `yano.upstream.tx.forwarding=disabled` maps to diffusion mode `disabled`.
- `active-selected` maps to mode `local-submit-only` with target
  `active-selected`.
- `all-hot-trusted` maps to mode `local-submit-only` with target
  `trusted-hot`.

If both old and new config are present, the new `yano.tx.diffusion.*` settings
should be authoritative and the old forwarding policy should be treated as a
compatibility default.

## Runtime Architecture

```text
REST submit / N2C submit / N2N TxSubmission
              |
              v
       TransactionAdmission
              |
              v
          TxSubsystem
   validation events + mempool
              |
              v
        TxDiffusion
   tx catalogue + peer states
       /                  \
      v                    v
upstream hot peers    downstream peers
TxSubmission client   TxSubmission server
```

Invariant: every transaction that is accepted into Yano's local mempool must go
through `TransactionAdmission`. `TxDiffusion` must not mutate the mempool
directly. Successful admission publishes `MemPoolTransactionReceivedEvent`,
regardless of whether the transaction originated from REST, node-to-client
submission, node-to-node submission, or future tx diffusion.

### `MemPoolTransaction`

Do not introduce a parallel `MempoolEntry` type in the first implementation.
Extend the existing `MemPoolTransaction` model, because it is already the
runtime's in-memory transaction entry:

```java
record MemPoolTransaction(
        long seqId,
        byte[] txHash,
        byte[] txBytes,
        TxBodyType txBodyType,
        long insertedAt,
        int size,
        TxOrigin origin,
        long expiresAtMillis,
        ValidationStatus validationStatus,
        String rejectionReason
) {}
```

If the exact public record shape should remain stable, the implementation can
keep additional relay metadata in an internal side table keyed by tx hash. The
important rule is that there is one authoritative mempool entry per transaction
body, not a second diffusion-owned tx store.

The mempool must enforce:

- max transaction count;
- max total bytes;
- max single transaction size;
- TTL;
- removal when the transaction appears in an applied block.

The transaction bytes should be stored once. Per-peer state should store ids and
small metadata, not duplicate transaction bodies.

### `TxOrigin`

Origins should be explicit and observable:

- `rest-api`
- `n2c-submit`
- `n2n:<peerId>`
- `tx-diffusion:<peerId>`
- `block-producer`
- `unknown`

Origin is used for event metadata, status, debugging, and policy. It must not
bypass validation by itself. The same `MemPoolTransactionReceivedEvent` should
be published for all origins; consumers should inspect event metadata when they
need source-specific behavior.

### `MempoolCatalog`

`MempoolCatalog` is optional for the first implementation. A few read methods
on `TxSubsystem` are enough until there is a second consumer such as REST
mempool inspection.

If introduced, the catalogue must be a peer-agnostic read view over admitted
transactions:

```java
interface MempoolCatalog {
    Optional<TxHandle> get(String txHash);

    List<TxIdAndSize> candidatesForAnnouncement(int maxCount, int maxBytes);

    boolean contains(String txHash);

    MempoolSnapshot snapshot();
}
```

`TxSubsystem` should remain the owner of admission and eviction. The catalogue
is a controlled view for diffusion and APIs. Peer-specific filtering, such as
"already announced to this peer", belongs in `TxDiffusion` and `PeerTxState`.

### `TxDiffusion`

The diffusion boundary coordinates local mempool events and peer protocol
events:

```java
interface TxDiffusion {
    void onTransactionAccepted(MemPoolTransaction entry);

    TxRequestPlan onPeerTxIds(String peerId, List<TxIdAndSize> txIds);

    void onPeerTxBodies(String peerId, List<byte[]> txBodies);

    List<byte[]> onPeerRequestedTxs(String peerId, List<String> txHashes);

    void onPeerConnected(String peerId, PeerClass peerClass);

    void onPeerDisconnected(String peerId);
}
```

This interface should be protocol-neutral. Yaci TxSubmission-specific code
should adapt mini-protocol messages into these calls.

### `PeerTxState`

Each hot peer gets bounded transaction state:

```java
final class PeerTxState {
    String peerId;
    PeerClass peerClass;
    BoundedSet<String> announcedToPeer;
    BoundedMap<String, InFlightTx> requestedFromPeer;
    long inFlightBytesFromPeer;
    long rejectedCount;
    long cooldownUntilMillis;
    long lastActivityMillis;
}
```

The concrete implementation should use bounded LRU-style sets instead of
unbounded `HashSet`s. Use a plain class rather than a record because this is
mutable operational state. Do not mirror every relationship with separate
"by peer" sets unless a later protocol path needs it; most v1 behavior can be
derived from the mempool plus `announcedToPeer` and `requestedFromPeer`.

Peer state may be lost on restart. The mempool is authoritative for tx bodies;
per-peer diffusion state is operational cache.

### `PeerClass`

Policy should distinguish peer classes:

- `ACTIVE_SELECTED`
- `TRUSTED_HOT`
- `UNTRUSTED_HOT`
- `DOWNSTREAM`

Initial full diffusion should be limited to `TRUSTED_HOT` and
`ACTIVE_SELECTED`. `UNTRUSTED_HOT` should require stronger abuse controls and
validation before it can participate.

## Concurrency Model

The implementation must avoid global peer-state mutation from many protocol
threads.

- `TxSubsystem` and the mempool are the shared, thread-safe transaction store.
  Writes go through `TransactionAdmission`, which already serializes admission
  with the admission gate. `DefaultMemPool` operations are synchronized today;
  byte accounting must preserve that single-store consistency.
- `PeerTxState` is confined to its owning peer session or peer I/O executor.
  One peer thread mutates one peer's tx state. A peer thread must not mutate
  another peer's tx state.
- Cross-peer work is expressed as immutable notifications or queued commands.
  For example, a `MemPoolTransactionReceivedEvent` can cause "new tx available"
  notifications to be queued for eligible peers, but the event thread must not
  directly mutate each peer's `PeerTxState`.
- Tx-id announcement selection reads from the shared mempool and then updates
  only the owning peer's state.
- Eviction does not call back into all peer states. If a tx is evicted by TTL,
  capacity, or block confirmation while a peer has requested it, the serve path
  returns "not available" or omits it. In-flight cleanup is lazy on timeout or
  next peer-state touch.
- Disconnect cleanup is local to the disconnected peer's state. It must not
  scan or lock all peers.

This confinement keeps tx diffusion from repeating the multi-peer observer race
class already seen in upstream sync work.

## Protocol Behavior

### Local Transaction Accepted

1. `TxSubsystem.submitTransaction()` admits and validates the transaction.
2. `MemPoolTransactionReceivedEvent` is emitted.
3. `TxDiffusion` observes the accepted mempool entry.
4. Compatibility mode may still call current upstream forwarding.
5. Full diffusion mode marks the tx as available for announcement to eligible
   peers.

### Peer Announces Tx IDs

1. TxSubmission adapter passes tx ids and sizes to `TxDiffusion`.
2. Already-known txs are acknowledged/ignored.
3. Unknown txs are filtered by size, peer policy, in-flight limits, and mempool
   capacity.
4. A request plan is returned for accepted unknown ids.
5. Repeated invalid sizes or unavailable txs penalize the peer.

### Peer Sends Tx Bodies

1. Tx bodies are checked against request state and size limits.
2. Requested body bytes go through `TransactionAdmission`.
3. Accepted txs enter the local mempool once and publish
   `MemPoolTransactionReceivedEvent` with source-specific origin metadata such
   as `tx-diffusion:<peerId>` or `n2n:<peerId>`.
4. Invalid txs are rejected and counted against the peer.
5. Accepted network-origin txs become eligible for announcement to other peers,
   excluding peers that already announced or received them.

### Peer Requests Tx Bodies

1. TxSubmission adapter asks `TxDiffusion` for requested tx hashes.
2. `TxDiffusion` serves only txs present in the local mempool.
3. Response is capped by per-peer batch and byte limits.
4. Served counts and bytes are recorded for rate limiting. Persistent
   "served-to-peer" sets are not required in v1 unless tests show repeated
   serving loops.

## Validation Policy

All admitted tx bodies must use `TransactionAdmission`.

Diffusion validation uses the existing `TransactionValidateEvent` chain. Do not
introduce a separate diffusion-only validation hook in the first implementation.
Plugins and embedders can already register listeners in the existing validation
event ordering model, and those listeners will govern REST, node-to-client,
node-to-node, and future diffusion-origin transactions because all accepted
transactions pass through `TxSubsystem.admitTransaction()`.

For network-origin transactions:

- If transaction validation is available and required, invalid txs are rejected.
- If validation is required but unavailable, Yano should not request or admit
  network tx bodies.
- If validation is explicitly best-effort for trusted peers, accepted txs must
  be marked with a weaker validation status.
- Untrusted full diffusion should remain disabled until validation and abuse
  controls are enabled.

The default out-of-box setting should keep full diffusion disabled.

## Resource Limits

The first production-capable implementation must enforce all of these limits,
but only a small subset should be operator-configurable initially.

Configurable in v1:

- max mempool tx count;
- max mempool bytes;
- tx TTL;
- max in-flight tx count per peer;
- max in-flight bytes per peer;
- peer cooldown duration.

Internal defaults in v1:

- max single tx size, from protocol parameters when available;
- max tx ids per announcement batch;
- max tx body request count per peer;
- max served tx bytes per peer per cycle;
- peer cooldown after repeated invalid txs, unavailable txs, or protocol
  misuse.

Limits should fail closed. When the node is at capacity, it should stop
requesting new tx bodies before it accepts unbounded memory pressure.

## Observability

Phase 0 status should expose the safe, already-owned runtime state:

- `txDiffusionEnabled`
- `txDiffusionMode`
- `mempoolSize`
- `mempoolBytes`
- `mempoolMaxTxs`
- `mempoolMaxBytes`
- `mempoolTtlSeconds`
- `mempoolAccepting`
- `mempoolValidationAvailable`
- `mempoolEvaluationAvailable`

Later protocol phases should add diffusion counters once tx ids, request
planning, and tx body serving exist:

- `txDiffusionPeerCount`
- `txDiffusionAcceptedMempoolEvents`
- `txDiffusionInboundTxIdsRequested`
- `txDiffusionInboundTxIdsRejected`
- `txDiffusionInboundTxIdsIgnored`
- `txDiffusionInboundTxBodiesAccepted`
- `txDiffusionInboundTxBodiesRejected`
- `txDiffusionInboundTxBodiesIgnored`
- `txDiffusionOutboundForwarded`
- `txDiffusionOutboundSuppressed`
- `txDiffusionServedTxs`
- `txDiffusionServedBytes`
- `txDiffusionInFlightTxs`
- `txDiffusionInFlightBytes`
- per-peer optional debug view:
  - announced count;
  - requested count;
  - in-flight bytes;
  - rejected count;
  - cooldown state.

REST endpoints can follow later:

- `GET /api/v1/node/mempool`
- `GET /api/v1/node/mempool/tx/{txHash}`
- `GET /api/v1/node/tx-diffusion/peers`

Metrics can mirror the same counters once the metrics surface is finalized.

## Consequences

Positive:

- Local and network txs use one admission path.
- Full diffusion can be enabled without changing canonical sync.
- Memory and bandwidth pressure are explicitly bounded.
- Per-peer state makes tx relay debuggable and testable.
- Current simple forwarding remains available.

Negative:

- More runtime state and more protocol edge cases.
- Requires careful testing with peer disconnects and duplicate tx ids.
- Requires byte-based mempool accounting, not only count-based eviction.
- Full untrusted relay behavior remains unsafe until validation and abuse
  controls are mature.

## Rollout Plan

### Phase 0: Mempool Limits And Config Guardrails

Scope:

- Accept this ADR.
- Keep `yano.upstream.tx.forwarding` behavior unchanged.
- Add config model for `yano.tx.mempool` and `yano.tx.diffusion.mode` without
  enabling full diffusion by default.
- Extend existing `MemPoolTransaction` metadata, or add an internal tx-hash
  keyed metadata side table if the public record must stay stable.
- Make `DefaultMempoolEvictionPolicy` configurable.
- Add byte accounting and byte-based eviction to the existing mempool.
- Add status fields that show diffusion is disabled unless explicitly enabled.
  Include current mempool size, byte pressure, configured limits, admission
  state, validation availability, and evaluation availability.

Acceptance:

- Existing local tx forwarding tests still pass.
- Default config remains compatible with current deployments.
- Mempool count and byte caps are enforced.
- Confirmed tx eviction still works.
- Duplicate txs do not duplicate stored bytes or events unexpectedly.

### Phase 1: TxDiffusion Boundary And Peer State

Scope:

- Add `TxDiffusion` and `PeerTxState`.
- Add minimal read methods on `TxSubsystem`; add `MempoolCatalog` only if the
  read surface starts to leak into multiple consumers.
- Implement in-memory bounded per-peer state.
- Wire `MemPoolTransactionReceivedEvent` into diffusion state.
- No network protocol behavior changes yet.

Acceptance:

- Unit tests cover announcement selection, dedupe, in-flight accounting, and
  peer disconnect cleanup.

### Phase 2: Local Submit Compatibility Through TxDiffusion

Scope:

- Move current upstream forwarding behind the diffusion boundary after the
  boundary is proven by tests.
- Preserve `active-selected` and `all-hot-trusted` semantics.
- Track per-peer announcement/forwarding state so local submits are not
  repeatedly sent to the same peer.

Acceptance:

- REST-submitted txs still reach configured target peers.
- Reconnect does not create unbounded repeated forwarding.

### Phase 3: Network Tx Ingress

Scope:

- Route TxSubmission-received tx ids and tx bodies through `TxDiffusion`.
- Request unknown tx bodies only within limits.
- Admit received bodies through `TransactionAdmission`.
- Preserve the `MemPoolTransactionReceivedEvent` invariant for accepted network
  txs, with peer-aware origin metadata.
- Reject network ingress when validation is required but unavailable.

Acceptance:

- Peer-submitted txs enter the local mempool once.
- Accepted peer-submitted txs publish `MemPoolTransactionReceivedEvent`.
- Invalid or oversized txs are rejected and counted.
- Repeated invalid peers enter cooldown.

Implementation note:

- Current Yaci `TxSubmissionServerAgent` schedules body requests internally.
  Yano therefore enforces request limits at the diffusion/admission boundary:
  unplanned, duplicate, disabled, or over-limit bodies are ignored or rejected
  and counted. Disabled diffusion mode preserves the legacy `txsubmission`
  admission path.

### Phase 4: Serve Mempool Tx Bodies To Peers

Scope:

- Let downstream and eligible upstream peers request tx bodies from the local
  mempool.
- Enforce per-peer served-byte and served-count limits.
- Avoid serving txs that are absent, expired, or already confirmed.

Acceptance:

- A connected peer can learn ids and request tx bodies from Yano.
- Serving respects limits and does not duplicate transaction bytes in memory.

Implementation note:

- The implemented serving path covers Yano's diffusion boundary and Yaci
  `PeerClient.submitTxBytes` / `TxSubmissionAgent` upstream queues. Inbound
  `NodeServer` sessions currently install Yaci's `TxSubmissionServerAgent`,
  whose role is to request and receive transactions from the connecting peer.
  Serving Yano's mempool to peers that only connect inbound requires wiring the
  TxSubmission client role for those sessions or opening a corresponding
  outbound peer connection.

### Later Hardening

After these implementation phases are stable:

- enable `trusted-hot` full pull-based diffusion in private/devnet relay
  clusters;
- add normal status counters and optional debug endpoints;
- add `all-hot` as an explicit experimental mode only after strict resource
  limits, validation posture, and soak tests are in place.

## Testing Strategy

Unit tests:

- mempool byte/count/TTL eviction;
- duplicate tx admission;
- event failure rollback;
- `MemPoolTransactionReceivedEvent` publication for REST, N2C, N2N, and
  diffusion-origin txs;
- announcement selection;
- request planning;
- per-peer in-flight accounting;
- peer cooldown after invalid txs;
- block-applied confirmed eviction.

Integration tests:

- local REST tx forwarded through compatibility mode;
- two Yano instances exchanging tx ids and tx bodies on a private devnet;
- downstream peer requests a tx body from Yano's mempool;
- validation-required mode rejects network txs when validator is unavailable;
- restart clears peer diffusion state but preserves safe mempool behavior.

Manual tests:

- private Haskell-node/devnet tx propagation;
- preprod local-submit-only forwarding;
- trusted-hot relay cluster with bounded mempool and rate limits.

## Open Questions

- Should the mempool eventually be journaled for restart recovery, or should it
  remain purely in memory?
- Should Yano expose mempool tx bodies through REST by default, or only hashes
  and metadata?
- What are safe default byte limits for mainnet/preprod when protocol params are
  not yet available at startup?
- Should `all-hot` require `validation.level=full` once full transaction
  validation is available?
- How much of per-peer tx state should be included in the normal status endpoint
  versus debug-only endpoints?
