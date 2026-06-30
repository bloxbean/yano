# Blinklabs Dingo Source Review And Yano Gap Analysis

## Status

Review report

## Date

2026-06-30

## Reviewed Source

- Repository: `https://github.com/blinklabs-io/dingo.git`
- Local checkout: `/tmp/yano-dingo-review`
- Commit reviewed: `03d2aa23ca5312b7ae31ae6e2c2af32333aa1473`
- Method: source review only. Dingo was not built or run as part of this report.

## Executive Summary

Dingo is already much closer to a full Cardano node than Yano. It is not only a
relay shell around the mini-protocols. It has a connection manager, peer
governor, topology and peer-snapshot loading, peer sharing, ledger peer
discovery, multi-client ChainSync state, Praos and Genesis chain selection,
persistent chain/fork handling, ledger state, optional historical validation,
Mithril bootstrap, a validated mempool, tx-submission diffusion, block forging,
and experimental Leios/Dijkstra support.

Yano has made substantial progress toward relay behavior through ADR-NET-008,
008B, and 008C: pluggable upstream modes, peer snapshot ingestion, multi-peer
header observation, tx diffusion MVP, peer-sharing server support, and optional
header/body validation hooks. But Yano is still a relay/indexer hybrid. The
largest gaps are:

- No Dingo-class connection manager with inbound/outbound identity, source-port
  reuse, connection collision handling, per-IP limits, and duplex tracking.
- No full cold/warm/hot peer governor with topology source classes, valency,
  churn, scoring, bootstrap exit/recovery, and ledger peer discovery.
- Chain selection is still simplified. Dingo implements Genesis and Praos
  comparison; Yano currently relies on trusted/quorum-style candidate selection.
- Validation is only header-focused in Yano. Dingo has era-aware ledger state,
  protocol params, nonce handling, checkpoints, UTxO validation, mempool overlay,
  and rollback-aware state.
- Yano has no block production path.
- Yano has no Leios/Dijkstra support.

The most important next step is networking parity, not Leios or block
production. Yano should first add a real connection manager and peer governor
around Yaci, then upgrade chain selection and ledger validation.

## Dingo Architecture Map

The important Dingo source areas are:

- Node assembly: `internal/node/node.go`, root `node.go`
- Connection manager: `connmanager/*`
- Peer governor: `peergov/*`
- Topology and peer snapshots: `topology/topology.go`, `config/cardano/*/peer-snapshot.json`
- ChainSync state and strategies: `chainsync/*`, `ledger/chainsync.go`
- Chain selection: `chainselection/*`, `consensus/praos/*`
- Persistent chain/forks: `chain/manager.go`, `chain/chain.go`
- Ledger state and validation: `ledger/state.go`, `ledger/validation.go`,
  `ledger/verify_header.go`, `ledger/eras/*`
- Mempool and tx diffusion: `mempool/*`, `ouroboros/txsubmission.go`,
  `ouroboros/localtxsubmission.go`
- Block production: `ledger/leader/*`, `ledger/forging/*`,
  `ledger/validate_forged.go`
- Leios/Dijkstra: `ledger/leios/*`, `ledger/eras/dijkstra.go`
- Mithril bootstrap: `cmd/dingo/mithril.go`, `mithril/*`, ledger trust-boundary
  handling in `ledger/state.go` and `ledger/replay_recovery.go`

## Networking

### What Dingo Implements

Dingo starts both node-to-node and node-to-client listeners from
`internal/node/node.go`. The relay listener uses the configured relay port and
the connection manager receives the same value through
`WithOutboundSourcePort(cfg.RelayPort)`.

That source-port detail matters. `connmanager/outbound.go` binds outbound
dials to the relay port when configured. The code comment says source-port
reuse is required for peer sharing to be useful. `connmanager/listener.go` and
`connmanager/connection_manager.go` then track inbound and outbound connections
by peer address, handle connection-id collisions, preserve an existing outbound
over a duplicate inbound, replace inbound with outbound where appropriate, and
track inbound peer addresses only when source-port reuse is enabled.

Dingo also has operational limits:

- Default max inbound node-to-node connections: 100.
- Default max connections per IP: 5.
- Per-IP rate limiting for inbound accepts.
- Duplicate outbound dial suppression.
- Deny/backoff handling for failed peers.
- Expected dial failures logged at lower severity than unexpected failures.
- Best-effort duplex detection for inbound connections.

### Yano Current State

Yano now advertises peer sharing and can answer peer-sharing requests through
`runtime/.../server/RelayPeerSharingProvider.java`. With the latest change,
blank or `auto` `yano.relay.advertised-host` resolves local interface addresses
and applies the existing private-address policy. This is useful for local and
LAN tests and aligns better with Haskell/Dingo local behavior.

Yano does not yet have a Dingo-style connection manager. It can advertise an
endpoint, but it does not bind outbound node-to-node dials to the relay listen
port, and it does not yet have Dingo's inbound/outbound connection identity and
collision model.

### Gap

Yano needs a connection manager abstraction around Yaci:

- Track all inbound and outbound N2N connections by canonical peer identity.
- Support local outbound bind port/source-port reuse if Java/Yaci can expose it.
- Handle inbound/outbound connection collisions deterministically.
- Enforce max inbound connections and max connections per IP.
- Track duplex capability and peer-sharing capability per connection.
- Make connect failures normal one-line warnings/info where appropriate.

Without this, Yano can participate in local peer sharing, but it is not yet a
full relay-class participant in the P2P graph.

## Peer Discovery And Peer Governor

### What Dingo Implements

Dingo's peer governor is in `peergov/*`. It has explicit peer sources:

- topology local root
- topology public root
- topology bootstrap peer
- ledger peer
- gossip/peer-sharing peer
- inbound connection peer

It has explicit states:

- cold
- warm
- hot

The peer model tracks address normalization, sharability, topology group
metadata, local/public root valency, warm valency, trustability, reconnect
backoff, reconnect count, blockfetch latency, success rate, connection
stability, header arrival rate, tip delta, inbound tenure, duplex status, and
topology match.

The governor reconciles periodically and promotes/demotes peers to satisfy
targets. Defaults include:

- target known peers: 150
- target established/warm peers: 50
- target active/hot peers: 20
- min hot peers: 10
- ledger peer target: 20
- ledger refresh interval: 1 hour
- gossip churn: 20 percent every 5 minutes
- public-root churn: 20 percent every 30 minutes
- inbound warm/hot budgets and tenure/cooldown rules

Dingo loads Cardano topology files through `topology/topology.go`, including:

- local roots
- public roots
- bootstrap peers
- `useLedgerAfterSlot`
- `peerSnapshotFile`
- local-root `trustable`, `advertise`, `valency`, and `warmValency`

Peer snapshots are embedded for known networks and can also be loaded from the
topology file. In Genesis selection mode, Dingo can drop bootstrap roots after
loading a usable peer snapshot.

Dingo also has ledger peer discovery. `ledger/peer_provider.go` reads active
stake pool relays from ledger/database state. `peergov/ledger_discovery.go`
uses those relays after `useLedgerAfterSlot`, dedupes them, and filters
non-routable IP literals.

Peer sharing is active: `node.go` wires
`PeerRequestFunc: n.ouroboros.RequestPeersFromPeer`, and the peer governor asks
eligible hot peers for peers, adds the returned addresses as gossip peers, and
marks gossip peers as sharable.

### Yano Current State

Yano has upstream modes and a lighter peer store/governor in
`runtime/.../sync/multipeer`. It supports configured peers, discovery, peer
snapshot URLs, observer peers, and an active selected upstream. It now exposes a
read-only upstream peer snapshot to the relay peer-sharing provider. This is a
useful MVP, but it is much smaller than Dingo's governor.

### Gap

Yano needs to graduate from "select hot peers" to a real governor:

- Peer sources: local root, public root, bootstrap, ledger, gossip, inbound.
- Peer states: cold, warm, hot.
- Topology valency and warm-valency enforcement.
- Backoff, churn, peer score, and deny/cooldown tracking.
- Bootstrap exit and bootstrap recovery logic.
- Ledger peer discovery from on-chain stake pool relays.
- A sharable-peer model so Yano does not blindly share every known upstream.
- Inbound governance so public relays can accept unknown peers without turning
  all of them into unlimited hot peers.

## Peer Sharing Semantics

### What Dingo Implements

Dingo distinguishes where a peer came from and whether it is sharable. It asks
hot peers for peers, adds returned peers as gossip peers, and serves peers
according to the peer governor state. It also filters non-routable peer
addresses for non-topology peer sources.

Dingo can answer ledger peer snapshot queries through
`ledger/peer_snapshot_query.go`, assembling active stake pool relays with stake
weights and the big-peer quota behavior used by Cardano ledger peer selection.

### Yano Current State

Yano's peer-sharing MVP serves:

- its own advertised or auto-resolved endpoint, if allowed;
- known upstream peer-store entries.

Private and loopback addresses are filtered unless
`yano.relay.allow-private-addresses=true`.

### Gap

Yano should separate "known peer" from "sharable peer". The next step should be:

- Mark peers sharable only when learned from appropriate sources or configured
  with `advertise=true`.
- Avoid sharing non-routable addresses by default even if they are known.
- Support Cardano topology `advertise` semantics for local roots.
- Add ledger peer snapshot query support only after Yano has enough ledger state
  to answer it honestly.

## ChainSync, Header Fan-In, And Block Fetch

### What Dingo Implements

Dingo's `chainsync.State` tracks multiple outbound clients, the active
ChainSync client, observation-only clients, seen headers, observed per-connection
headers, and blockfetch latency EWMA. It supports three header ingress
strategies:

- `primary`: one active peer drives ledger ingress; eligible peers can still
  observe headers.
- `parallel`: every eligible peer can drive ledger ingress, with dedupe.
- `round-robin`: rotates the ingress driver.

Defaults include:

- max ChainSync clients: 3
- stall timeout: 2 minutes
- seen-header retention: roughly 50,000 slots
- observed header history per connection: 256

`ledger/chainsync.go` serializes ChainSync event handling, ignores events from
closed connections, tracks peer header history, detects rollback loops, buffers
headers when another peer owns the pipeline, and starts blockfetch based on the
header queue. It also has shadow blockfetch near tip when a primary peer is
slow and another peer has the same block.

### Yano Current State

Yano has multi-peer header observation and an in-memory candidate store. It
keeps non-canonical candidate observations out of canonical RocksDB, which is
the right boundary. It can keep observer connections open near tip and choose
an active upstream for body fetch.

### Gap

Yano needs:

- Bounded per-peer observed header fragments, not only aggregate candidate
  observations.
- Header dedupe and per-peer history tied to connection identity.
- Header ingress strategies equivalent to Dingo's primary/parallel/round-robin.
- Stall detection and client recycling per ChainSync client.
- Blockfetch latency metrics and selection.
- Shadow blockfetch or alternate body fetch when the active peer is slow.
- A clearer separation between initial bulk sync and near-tip multi-peer
  observation.

## Chain Selection

### What Dingo Implements

Dingo implements two chain selection modes in `chainselection/*`:

- Praos selection.
- Genesis selection.

Genesis selection uses a rolling density window. The default window is derived
from consensus parameters as `ceil(3 * k / f)`. It exits Genesis mode when the
local tip plus the genesis window reaches the best known peer slot.

Praos comparison is implemented in `consensus/praos/comparison.go`:

1. Higher block number wins.
2. At the same block number, if issuer and slot are the same, higher operational
   certificate issue number wins.
3. Otherwise compare VRF output when the tiebreaker is armed. In Conway, VRF
   tiebreaking is restricted to tips within 5 slots.
4. If no consensus rule applies, the tips compare equal and the incumbent is
   kept.

The selector also filters closed, ineligible, and stale peers; supports
connection priority from the peer governor; and uses blockfetch latency as a
transport tiebreaker only when consensus comparison says the selected block is
the same. It has an anti-flap incumbent pin for tiny head forks with escape
conditions for longer chains and stalled progress.

### Yano Current State

Yano has `ChainSelectionStrategy` and
`TrustedOrQuorumCandidateWithinRollbackWindow`. It can avoid adopting arbitrary
single untrusted observations when quorum is required. This is useful as a
transitional safety layer, but it is not Cardano chain selection.

### Gap

Yano needs Cardano chain selection:

- Implement Praos select-view extraction from Shelley-family headers:
  issuer, slot, operational certificate issue, and VRF output.
- Implement the Praos comparison rules, including Conway's restricted VRF
  tiebreaking.
- Implement Genesis density mode for bootstrap from untrusted peers.
- Track peer tips and observed fragments with bounded memory.
- Filter stale, closed, ineligible, and low-priority peers.
- Treat quorum-lite as a temporary unvalidated safety policy, not the target
  consensus algorithm.

## Persistent Chain And Rollback Handling

### What Dingo Implements

Dingo's `chain.Manager` maintains a persistent primary chain and ephemeral
non-primary candidate/fork chains. Blocks on the primary chain are persisted in
the blob database. Removed primary blocks are moved into a cache and rollback
events are recorded. Persistent rollbacks deeper than security parameter `k`
are rejected.

`chain.Chain` has a queued-header capacity based on the security parameter with
a safety floor. It enforces header continuity, persists blocks transactionally,
and publishes events only after commit. Iterators observe rollback markers so
downstream ChainSync serving can cross reorgs correctly.

### Yano Current State

Yano persists canonical chain/indexer state and has rollback handling designed
around the indexer path. Candidate headers are intentionally in memory and not
canonical. That is correct for the current architecture, but it is not yet a
general persistent chain/fork manager.

### Gap

Yano needs a chain manager boundary if it wants Dingo-level node behavior:

- Primary chain storage independent from indexer-specific projections.
- Ephemeral fork/candidate chains with bounded cache.
- Header continuity and rollback-depth enforcement at the chain boundary.
- Transactional event publication after block commit.
- Iterator/query behavior that can serve downstream peers correctly across
  rollbacks.

## Ledger State And Validation

### What Dingo Implements

Dingo has a substantial ledger state implementation in `ledger/state.go`.
It tracks eras, protocol params, hard-fork transitions, epoch nonce cache,
epoch state, opcert sequence, stake snapshots, governance state, rollback
state, chain checkpoints, Mithril trust boundary, mempool integration, and
worker pools.

Header crypto validation is implemented in `ledger/verify_header.go`. It
validates Shelley-family VRF and KES signatures using epoch nonce and KES period
context. Byron headers are skipped.

When historical validation is enabled, Dingo validates transactions with
era-specific UTxO rules through `ledger/eras/*`. Conway validation includes
phase-1 UTxO rules, fee validation, required redeemer checks, and Plutus
validation paths, with explicit handling for phase-2 failed transactions that
are consensus-valid.

Dingo also enforces configured chain checkpoints and has replay recovery rules
around Mithril trust boundaries.

### Yano Current State

Yano has optional header validation hooks and a body validation framework with
default no-op body validation. This is intentionally extensible and useful for
library users, but current validation is not a full ledger transition system.

### Gap

To reach Dingo level, Yano needs:

- Era table and hard-fork boundary state.
- Protocol parameter tracking by era and epoch.
- Epoch nonce computation and cache.
- KES period and opcert sequence tracking.
- Full UTxO ledger validation for Shelley through Conway.
- Plutus/script validation integration or a clearly bounded native/JVM bridge.
- Governance state, pool state, stake snapshots, reward snapshot rotation, and
  pool relay state.
- Checkpoint enforcement.
- Rollback-safe ledger state persistence.
- Mithril snapshot import and trust-boundary handling.

The existing Yano header/body validator plugin model should remain, but it
should sit on top of a real ledger validation service, not replace it.

## Mempool And Transaction Diffusion

### What Dingo Implements

Dingo's mempool in `mempool/mempool.go` is validated and byte-bounded. Defaults
include:

- TTL: 5 minutes
- cleanup interval: 1 minute
- eviction watermark: 90 percent
- rejection watermark: 95 percent

It validates incoming transactions against ledger state and a mempool UTxO
overlay. The overlay prevents double-spend acceptance across pending
transactions and prunes descendants when parents are removed.

Tx diffusion is implemented through the Ouroboros TxSubmission mini-protocol in
`ouroboros/txsubmission.go`. Dingo requests tx ids from peers, rate-limits
inbound tx bodies per peer, admits them through the mempool, and serves local
mempool tx ids/bodies to peers through per-connection mempool consumers. Full
duplex inbound connections also start TxSubmission handling.

### Yano Current State

Yano now has a `TxDiffusion` boundary, per-peer state, mempool byte accounting,
N2N inbound tx body ingestion, local-submit compatibility, body serving from
the local mempool, status counters, and the simplified 008C config:

```yaml
yano:
  tx:
    diffusion:
      enabled: true
```

Accepted transactions go through the existing `TransactionAdmission` path and
the `TransactionValidateEvent` extension point.

### Gap

Yano's tx diffusion MVP works, but Dingo-level relay behavior still needs:

- Full ledger-backed mempool validation, not only plugin/event validation.
- Mempool UTxO overlay.
- Per-peer rate limiting and cooldowns.
- Per-peer tx consumers for outbound serving on every usable hot/duplex peer.
- Clear interaction between block application, mempool eviction, and descendant
  pruning.
- More complete status/metrics per peer and aggregate mempool pressure.

## Block Production

### What Dingo Implements

Dingo has block production support:

- `ledger/leader/*` computes leader schedules from active stake, epoch nonce,
  slots per epoch, active slot coefficient, and consensus mode.
- `ledger/forging/keys.go` loads VRF keys, KES keys, and operational
  certificates, validates KES key/opcert compatibility, and evolves KES keys.
- `ledger/forging/forger.go` runs a slot-aligned forging loop, skips forging
  when still syncing, checks leadership, builds a block, optionally
  self-validates it, adopts it locally, and broadcasts it.
- `ledger/forging/builder.go` builds era-appropriate blocks from the mempool,
  checking block size, transaction size, execution units, and intra-block
  double spends.
- `ledger/validate_forged.go` validates forged blocks before diffusion.

### Yano Current State

Yano does not have block production.

### Gap

To match Dingo as a block-producing node, Yano needs:

- Pool credential loading for cold, VRF, KES, and operational certificate files.
- KES evolution and period checks.
- Leader election and schedule cache.
- Slot clock integration.
- Block builder for current and previous compatible eras.
- Mempool selection with full ledger validation.
- Self-validation before local adoption.
- Block diffusion to downstream/upstream peers.
- Slot-battle detection/recording.

This should come after networking, chain selection, and ledger validation. It
depends on all three.

## Storage, APIs, And Mithril

### What Dingo Implements

Dingo's database is split between metadata and blob storage. SQLite and Badger
are always available; Postgres/MySQL and S3/GCS are optional plugin paths.
Storage modes separate core node state from API indexing. API mode can start
UTxO RPC, Blockfrost-compatible API, Mesh API, Bark archive server, and Midnight
services.

Mithril support is first-class. `cmd/dingo/mithril.go` can list, show, and sync
Mithril artifacts. The sync path downloads a snapshot, imports ledger state,
copies immutable blocks, backfills gaps, rebuilds indexes, and records
Prometheus metrics for each phase. Ledger startup reads `mithril_ledger_slot`
as a trust boundary and rejects rollback/replay behavior that would cross it
incorrectly.

### Yano Current State

Yano is built around RocksDB-backed chain/indexer state and application status
endpoints. It does not have Dingo's dual storage model, API indexing modes, or
Mithril bootstrap pipeline.

### Gap

Yano needs:

- A clearer separation between core node storage and optional index/API
  projections.
- Rollback-safe metadata transactions across all node state.
- Mithril bootstrap/import if fast trust-based startup is a target.
- API/index modes only after the core node state model is stable.

## Leios And Dijkstra

### What Dingo Implements

Dingo contains experimental Dijkstra and Leios support:

- `ledger/eras/dijkstra.go` adds the Dijkstra era when enabled.
- `ledger/leios/manager.go` implements vote storage, vote validation, stake
  tallying, quorum/certificate assembly, and bounded in-memory vote state.
- `ledger/leios/pipeline.go` implements provisional CIP-0164 linear Leios
  endorser-block pipeline stages: produce, diffuse, vote, certify, eligible,
  and expired.
- `ledger/chainsync.go` and the forging path include endorser-block handling.
- Node assembly gates Leios networking and Dijkstra support behind explicit
  experimental/testnet configuration.

The implementation is real source code, but the source itself treats this area
as experimental. Some prototype paths intentionally trust or skip behavior that
is still changing.

### Yano Current State

Yano has no Leios or Dijkstra support.

### Gap

Leios should not be the first parity target. Yano should first close:

1. networking;
2. chain selection;
3. ledger validation;
4. mempool relay;
5. block production.

After those are stable, Leios can be added as an experimental subsystem with
feature gates, bounded in-memory state, and explicit testnet-only defaults.

## Yano Strengths To Preserve

Yano should not copy Dingo blindly. Current Yano design has useful properties:

- `PeerSession` is a clean single-peer worker.
- Canonical state is only mutated by the selected canonical path.
- Candidate headers are kept out of canonical RocksDB.
- `LedgerApplyProcessor` generation fencing gives a useful single-writer apply
  boundary.
- Header/body validation has a plugin-friendly API for library users.
- Tx admission already has a single path and vetoable validation events.
- The 008C operator config is intentionally small for the MVP.

The main work is to replace the simplified policies behind these boundaries
with Cardano-node/Dingo-grade implementations.

## Roadmap To Reach Dingo Level

### Phase 1: Connection Manager Parity

Build a Yano connection manager around Yaci:

- inbound/outbound connection registry;
- canonical peer identity;
- source-port reuse or an explicit Java limitation document;
- inbound and per-IP limits;
- connection collision rules;
- duplex and protocol capability tracking;
- expected network failure logging;
- status counters per connection state.

Acceptance: Yano can run with inbound and outbound N2N peers, expose correct
connection identity/status, and avoid duplicate/colliding sessions.

### Phase 2: Peer Governor Parity

Replace the lightweight governor with cold/warm/hot peer governance:

- source classes: local root, public root, bootstrap, gossip, ledger, inbound;
- topology valency and warm valency;
- advertise/trustable semantics;
- backoff, churn, scoring, and cooldown;
- bootstrap exit/recovery;
- shareable-peer filtering.

Acceptance: Yano can sustain a healthy peer set from topology plus peer sharing
without a manually pinned active peer.

### Phase 3: Discovery Parity

Add full topology and ledger peer discovery behavior:

- Cardano topology file compatibility;
- embedded or configured peer snapshot support;
- `useLedgerAfterSlot`;
- ledger peer provider from active stake pool relay state;
- ledger peer snapshot query support once ledger state is available.

Acceptance: Yano can bootstrap from public roots/snapshots and later transition
to ledger/gossip peers.

### Phase 4: Chain Selection Parity

Implement Genesis and Praos chain selection:

- observed peer tip store;
- bounded peer fragments;
- Genesis density window;
- Praos select-view extraction and comparison;
- Conway VRF tiebreak restriction;
- stale/closed/ineligible filtering;
- anti-flap behavior with escape conditions.

Acceptance: Yano converges with Haskell/Dingo on public network forks without
using quorum-lite as the primary rule.

### Phase 5: Chain And Blockfetch Parity

Add Dingo-style header and body handling:

- header ingress strategies: primary, parallel, round-robin;
- header dedupe;
- per-peer observed history;
- blockfetch latency EWMA;
- shadow/alternate blockfetch near tip;
- persistent primary chain plus bounded fork cache.

Acceptance: Yano can switch peers, fetch bodies efficiently, and serve a
rollback-aware chain to downstream peers.

### Phase 6: Ledger Validation And State

Implement real ledger state:

- era and hard-fork state;
- protocol params;
- epoch nonce;
- KES/opcert sequence;
- UTxO and governance state;
- full Shelley through Conway validation;
- checkpoint enforcement;
- rollback-safe persistence;
- Mithril trust boundary if Mithril bootstrap is adopted.

Acceptance: Yano can validate received blocks rather than treating upstreams as
trusted or quorum-trusted.

### Phase 7: Mempool And Tx Relay Parity

Upgrade tx diffusion on top of ledger validation:

- validated mempool with UTxO overlay;
- descendant pruning;
- per-peer tx consumers;
- per-peer rate limits;
- full bidirectional tx-submission on all usable hot/duplex peers;
- richer mempool and tx diffusion status.

Acceptance: Yano relays transactions safely with unknown public peers.

### Phase 8: Block Production

Add block producer mode:

- credential loading;
- leader schedule;
- KES evolution;
- block building;
- self-validation;
- local adoption and diffusion;
- slot battle tracking.

Acceptance: Yano can produce blocks on a devnet/testnet using the same core
ledger and networking paths as relay mode.

### Phase 9: APIs, Mithril, And Operational Maturity

Add optional node modes and operational depth:

- core vs API/index mode;
- UTxO RPC or equivalent Java API surface;
- Mithril bootstrap/import;
- richer Prometheus metrics;
- pprof/JFR-style debug guidance;
- conformance and interop tests with Haskell node and Dingo.

Acceptance: Yano is operable as a long-running node, not only a library/runtime
component.

### Phase 10: Leios/Dijkstra Experimental Track

After core parity, add Leios behind explicit feature flags:

- Dijkstra era gate;
- Leios mini-protocol support;
- endorser block pipeline;
- vote validation/tallying;
- bounded vote/certificate state;
- testnet-only defaults.

Acceptance: Yano can join Leios prototype networks without weakening normal
relay/node behavior.

## Immediate Recommendations

1. Start with the connection manager. Peer sharing and relay discovery are only
   partially useful until Yano can identify, govern, and reuse inbound/outbound
   peer connections like a relay.
2. Treat outbound source-port reuse as a design spike. Dingo considers it
   important enough to wire through node startup. If Java/Yaci cannot support it
   cleanly, document the limitation and expected public-discovery impact.
3. Keep 008C's simple tx config. Do not expose Dingo-level policy knobs until
   the peer governor exists.
4. Replace quorum-lite chain selection with Praos/Genesis before calling Yano a
   public relay node.
5. Keep Leios separate and experimental. It should not block relay parity.

## Bottom Line

Dingo's current source shows the level Yano needs to reach: a real peer
governor, full connection management, ledger-backed chain selection and
validation, a validated mempool, and block production. Yano's current
subsystem boundaries are reasonable, but the implementations behind networking,
chain selection, and validation need to become consensus-grade before Yano can
be considered Dingo-level.
