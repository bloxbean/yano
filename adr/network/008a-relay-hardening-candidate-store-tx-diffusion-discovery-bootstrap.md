# ADR-NET-008A: Relay Hardening for Candidate Headers, Tx Diffusion, and Discovery Bootstrap

## Status

Accepted

## Date

2026-06-29

## Context

ADR-NET-008 introduced configurable upstream modes and the first multi-peer
relay path. Live preprod testing surfaced three follow-up issues:

1. Candidate headers were stored by `blockHash` only. If two observer peers
   reported the same block hash, the later observation replaced the earlier
   one. This broke the intended quorum rule because the store could not
   distinguish "one peer reported this hash" from "multiple peers reported
   this hash."
2. Current transaction forwarding is a submit-forward policy from locally
   admitted transactions to upstream peers. It is not full relay-style mempool
   diffusion.
3. `p2p-relay` still needs at least one configured or legacy remote upstream
   peer before discovery can contribute peers. If that bootstrap peer is down
   or absent, sync cannot start purely from peer snapshots or peer sharing.

Cardano node-to-node networking uses separate mini-protocols for chain sync,
block fetch, and tx submission. The public Cardano networking documentation
describes node-to-node tx submission as pull-based, where the initiator asks
for transaction IDs and transaction bodies rather than peers blindly pushing
full transactions to every connection:

- https://docs.cardano.org/about-cardano/explore-more/cardano-network/networking-protocol

Yano should move in that direction without losing the simpler indexer/default
operational modes.

## Decision

### 1. Candidate Header Store

Candidate headers are stored as peer observations, not unique block hashes.

The in-memory candidate store uses `(peerId, blockHash)` as its identity:

- the same peer reporting the same block hash updates its own observation;
- different peers reporting the same block hash produce distinct observations;
- chain selection can count distinct peer observations for quorum;
- `get(blockHash)` returns the latest observation for compatibility;
- `all()` returns observations, not unique hashes.

The store remains ephemeral and non-canonical. It must not write to canonical
RocksDB chain state.

The store is bounded in two ways:

- rollback-window pruning removes observations older than
  `yano.upstream.selection.rollback-window-slots`; when that value is omitted
  or `0`, runtime derives the window from Shelley genesis as
  `ceil(securityParam / activeSlotsCoeff)` slots;
- a fixed in-memory observation cap evicts the oldest observations when the cap
  is exceeded.

The current default cap is `16_384` observations. Expected retained
observations are approximately:

```text
observer_peer_count * securityParam
```

because `ceil(k / activeSlotsCoeff)` slots contain about `k` active blocks on
average. With `targetHot=3`, one selected canonical peer and two observers on
mainnet/preprod (`k=2160`, `activeSlotsCoeff=0.05`) retain about
`2 * 2160 = 4,320` observations. With seven observers, the estimate is
`15,120`, still below the cap. A rough HotSpot heap estimate for the current
string-heavy candidate record and concurrent-map entry is `500-900` bytes per
observation, so the common three-hot-peer case is about `2-4 MiB`; the hard cap
is about `8-15 MiB`. A future config key can expose this cap if live relay
testing shows a need.

### 2. Transaction Diffusion

Keep the current forwarding policy as a compatibility layer:

- `active-selected`
- `all-hot-trusted`
- `disabled`

Do not treat this as full mempool diffusion.

Full relay-style tx diffusion should be implemented as a separate
`TxDiffusion`/`MempoolDiffusion` boundary rather than as a loop that pushes
full transactions to every connected peer. The target design is:

- accepted local and network transactions enter a shared mempool;
- mempool entries track tx hash, size, origin, validation result, TTL, and
  per-peer diffusion state;
- each hot peer has tx-submission state for announced, requested, in-flight,
  served, and rejected transactions;
- full tx bodies are downloaded or served once per peer need, with dedupe;
- resource limits bound in-flight bytes, request counts, and per-peer failures;
- peers that repeatedly request invalid or unavailable txs are scored down;
- local submission remains a separate API path that admits txs and then makes
  them available for diffusion;
- the initial production mode may diffuse only to trusted hot peers, with an
  explicit opt-in for all hot peers after abuse controls exist.

This should align with Cardano's pull-based node-to-node tx-submission model
instead of making Yano broadcast full transactions eagerly.

### 3. Discovery-First Bootstrap

`p2p-relay` should be able to start without explicit `upstream.peers` or
legacy `yano.remote.*` when discovery can provide bootstrap peers.

The intended startup order for discovery-capable modes is:

1. Build the peer store.
2. Seed configured peers, if any.
3. If no active peer is available and discovery is enabled, load peer-snapshot
   files and URLs synchronously before starting canonical sync.
4. Select an initial active peer from the peer store using the peer governor.
5. Start canonical sync from that peer.
6. Start observer peers and optional peer-sharing discovery.
7. If no peer can be selected, keep the sync subsystem in a retrying state
   rather than failing the process immediately.
8. If a configured active peer fails before startup completes, start discovery
   fallback and promote an eligible discovered peer before retrying recovery.

Peer-sharing seeds can be configured explicitly. Peer-snapshot peers may later
be used as peer-sharing seeds, but that should be rate-limited and scored so a
large snapshot does not trigger aggressive dial storms.

## Consequences

- The current quorum policy can now count distinct peers for the same block
  hash.
- Candidate memory usage is bounded.
- `upstreamCandidateHeaderCount` now means candidate observations, not unique
  block hashes.
- Full tx diffusion remains future work and should not be represented as
  complete relay behavior yet.
- Discovery-first bootstrap requires a SyncSubsystem startup refactor because
  the current constructor and startup path assume an active peer exists before
  discovery runs.

## Implementation Status

Implemented now:

- Candidate store identity changed from `blockHash` to `(peerId, blockHash)`.
- Same-peer duplicate observations are deduplicated.
- Same-hash observations from different peers are retained for quorum.
- The in-memory store is capped at `16_384` observations.
- Rollback-window pruning remains active and now derives its default from
  Shelley genesis as `ceil(k / activeSlotsCoeff)` slots.
- Discovery-first bootstrap is implemented for multi-peer upstream modes when
  `yano.upstream.discovery.enabled=true`.
- `p2p-relay` can start without explicit `yano.upstream.peers` and without
  legacy `yano.remote.*` when peer snapshots or peer sharing provide a peer.
- `p2p-relay` with configured peers can fall back to an eligible discovered
  peer when the configured active peer fails during startup.
- Recently failed active upstream peers are placed on the failover cooldown
  before selecting the next active peer, so a bad trusted static peer does not
  immediately outrank usable discovered peers.
- Active failover selection prefers unfailed eligible peers before retrying
  peers that already failed in the current failover cycle. This prevents a
  trusted but broken static root from winning again before discovered snapshot
  peers have been attempted.
- Startup/dial failures after an active-peer switch immediately retry the newly
  selected peer through the serialized recovery executor, instead of waiting for
  the supervisor's normal recovery cooldown and polling interval.
- In discovery-first mode, legacy `yano.remote.*` is not silently converted
  into the initial upstream peer. Operators that want a static fallback should
  list it under `yano.upstream.peers`.
- If discovery does not produce a usable peer at startup, the sync subsystem
  remains alive and retries instead of failing the process immediately.
- Peer-session status collection is defensive around incomplete startup state
  so recovery logging cannot block failover dispatch.
- The peer-session supervisor treats a stopped active session as recoverable
  after cooldown, which prevents relay failover from stalling after a failed
  recovery attempt switches the active pointer to another peer.

Planned:

- `TxDiffusion` / mempool diffusion boundary.
- Configurable tx diffusion modes beyond the current forwarding policy.
- Optional config for candidate observation cap if operational testing needs
  it.

## Validation

Candidate-store behavior is covered by:

- `candidateStoreKeepsSameHashObservationsPerPeerForQuorum`
- `candidateStoreDeduplicatesSamePeerSameHash`
- `candidateStoreEvictsOldestObservationsWhenBounded`
- `discoveryBootstrapSatisfiesClientRemoteRequirement`
- `singleActiveModeStillRequiresRemoteOrPeer`
- `p2pRelayCanBootstrapActivePeerFromPeerSnapshotWithoutConfiguredPeers`
- `p2pRelayFallsBackToDiscoveredPeerWhenConfiguredPeerFails`
- `p2pRelaySkipsRecentlyFailedConfiguredPeerWhenDiscoveredPeerAlsoFails`
- `p2pRelayTriesUnfailedDiscoveredPeersBeforeRetryingFailedTrustedPeer`
- `stoppedSessionTriggersRecoveryRetryAfterCooldown`

Run:

```bash
./gradlew :runtime:test --tests "com.bloxbean.cardano.yano.runtime.sync.multipeer.MultiPeerScaffoldingTest" --console=plain
./gradlew :runtime:test --tests "com.bloxbean.cardano.yano.runtime.config.UpstreamConfigTest" --tests "com.bloxbean.cardano.yano.runtime.sync.SyncSubsystemTest.p2pRelayCanBootstrapActivePeerFromPeerSnapshotWithoutConfiguredPeers" --tests "com.bloxbean.cardano.yano.runtime.sync.SyncSubsystemTest.p2pRelayFallsBackToDiscoveredPeerWhenConfiguredPeerFails" --tests "com.bloxbean.cardano.yano.runtime.sync.SyncSubsystemTest.p2pRelaySkipsRecentlyFailedConfiguredPeerWhenDiscoveredPeerAlsoFails" --console=plain
```
