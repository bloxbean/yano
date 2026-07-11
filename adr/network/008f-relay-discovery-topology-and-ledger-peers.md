# ADR-NET-008F: Relay Discovery, Topology, And Ledger Peers

## Status

Accepted. Initial relay discovery implementation and live preprod/Haskell
verification completed on 2026-06-30.

The implemented pass extends the existing `YaciPeerDiscoveryService`, keeps
`UpstreamDiscoveryConfig` as the config surface, routes discovered peers through
the governor admission path, adds source-aware address policy, parses Cardano
topology roots, loads peer snapshot files/URLs with a byte cap, and keeps
ledger peers disabled until rollback-safe pool-relay persistence exists.

Peer-sharing over already-established hot/warm sessions and ledger-peer
discovery remain production-hardening follow-up work because they require
additional Yaci session plumbing and ledger relay-endpoint persistence.

## Date

2026-06-30

## Context

ADR-NET-008E defines the relay peer governor. The governor needs peers from
multiple sources, but it should not parse topology files, download peer
snapshots, query peer sharing, or inspect ledger state directly.

Yano already has useful discovery pieces:

- configured upstream peers;
- compatibility with `yano.remote.*`;
- `UpstreamDiscoveryConfig` under `yano.upstream.discovery.*`;
- `YaciPeerDiscoveryService`, which loads peer snapshots and performs
  seed-based peer-sharing discovery;
- peer snapshot URL/file support for the official big-ledger peer snapshot
  shape used by the public network environment files;
- relay peer-sharing responses for inbound peers.

The current behavior is enough for MVP relay testing, but discovery is still
spread across upstream configuration, sync recovery, peer-store writes, and
peer-sharing support. A relay-class node needs a dedicated discovery boundary
that can feed normalized peer records into the peer governor from static config,
topology files, peer snapshots, peer sharing, and eventually ledger state.

This is a brownfield evolution, not a greenfield subsystem:

- `YaciPeerDiscoveryService` should be absorbed, extended, or renamed into the
  relay discovery implementation. Yano should not run a second discovery path
  beside it.
- `UpstreamDiscoveryConfig` remains the primary config contract. A future
  `yano.relay.discovery.*` namespace may be added only as a documented alias or
  migration path.
- Discovery should feed the 008E governor admission path. It should not write
  directly to an unbounded raw `PeerStore`.
- Discovery and the governor should share one incoming peer descriptor type.
  Avoid separate `DiscoveredPeer` and `PeerDescriptor` models with the same
  fields.

This ADR defines that discovery boundary.

## Decision

Evolve the existing discovery code into a source-oriented discovery service that
feeds incoming peer descriptors into the peer governor.

The service does not decide which peers become hot. It does not open canonical
sync sockets. It does not select chains. It only finds peer endpoints, attaches
provenance and source metadata, applies address policy, and updates the peer
governor.

### Existing Components To Evolve

| Existing component | 008F role |
| --- | --- |
| `YaciPeerDiscoveryService` | Extend into the relay discovery implementation. It already handles snapshot loading and seed-based peer sharing. |
| `UpstreamDiscoveryConfig` | Keep as the primary config model for discovery. Add new fields only when implemented. |
| `PeerAddressPolicy` | Refactor into a source-aware policy and fix IPv6 endpoint parsing and unresolved-host classification. |
| `PeerStore` | Becomes persistence behind the governor's bounded known-peer set, not direct discovery output. |
| `PeerGovernor` | Owns admission, dedupe, known-peer bounds, expiry, and source-aware peer state. |
| `RelayPeerSharingProvider` | Uses governor `sharablePeers(limit)` for responses after 008E integration. |
| `LedgerStateProvider` / account state store | Needs a rollback-safe pool relay endpoint index before ledger peer discovery can be implemented. |

### Responsibilities

The discovery service is responsible for:

- normalizing configured upstream peers;
- loading Cardano P2P topology files;
- loading configured peer snapshot files and URLs;
- refreshing peer snapshots with caching and failure handling;
- asking eligible peers for peer-sharing results;
- adding peer-sharing results as gossip peers;
- adding ledger peers only after Yano can provide active stake pool relay
  metadata reliably;
- applying routability/private-address policy by source;
- preserving source metadata such as trustable, advertise, hot valency, and
  warm valency;
- exposing discovery status.

The discovery service is not responsible for:

- peer state transitions;
- connection lifecycle;
- inbound admission limits;
- chain selection;
- block validation;
- transaction validation;
- serving peer-sharing responses directly.

### Incoming Peer Descriptor

Discovery and governor admission should use one incoming peer descriptor type:

```java
record PeerDescriptor(
        PeerEndpoint endpoint,
        PeerSource source,
        String sourceId,
        boolean trustable,
        boolean advertise,
        boolean sharable,
        int hotValency,
        int warmValency,
        long firstSeenMillis,
        long lastSeenMillis,
        Long expiresAtMillis) {}
```

`sourceId` groups records that came from the same local-root group, topology
section, snapshot, peer-sharing peer, or ledger refresh.

Use thin DTOs only at true boundaries:

- config input;
- topology/snapshot parsing;
- persistence;
- status output.

Do not add another long-lived `DiscoveredPeer` model beside the governor input
descriptor.

### Address Policy

Address policy is source-aware:

- `LOCAL_ROOT` and `STATIC_UPSTREAM` may use private addresses when explicitly
  configured.
- `PUBLIC_ROOT`, `BOOTSTRAP`, `GOSSIP`, and `LEDGER` reject loopback,
  link-local, multicast, unspecified, and private IP literals unless
  `allow-private-addresses=true`.
- IPv6 endpoints must support bracket form such as `[::1]:3001` and must not be
  split with a naive `lastIndexOf(':')`.
- DNS names may be admitted for public sources without resolving on every
  discovery pass, but a failed DNS lookup must not be classified as a public IP.
  Dial-time DNS failure should become connection-manager/governor backoff.
- Duplicate host/port pairs are collapsed after normalization.

`PeerAddressPolicy` is the single place for allowlist, denylist, private-address
handling, IPv4/IPv6 classification, and source-specific routing rules.

### Configuration

Use the existing `yano.upstream.discovery.*` namespace for the first
implementation:

```yaml
yano:
  upstream:
    discovery:
      enabled: true
      peer-sharing: true
      seeds: []
      peer-snapshot-files: []
      peer-snapshot-urls: []
      peer-snapshot-limit: 128
      allow-private-addresses: false
      allowlist: []
      denylist: []
      topology-file: ""
      ledger-peers: false
      use-ledger-after-slot: -1
```

Existing fields keep their current behavior. New fields are added only when the
corresponding implementation lands:

- `topology-file`;
- `ledger-peers`;
- `use-ledger-after-slot`.

Implementation constants may be used for snapshot refresh interval, peer-sharing
interval, gossip TTL, and maximum snapshot bytes until operators need those as
real tuning knobs. Do not ship config keys that do nothing.

Compatibility:

- Existing `yano.upstream.discovery.*` settings continue to work.
- `yano.relay.auto-discovery=true` continues enabling peer sharing and discovery
  defaults for relay profiles.
- If `yano.relay.discovery.*` is introduced later, it is an alias/migration
  path, not a second config surface.

### Topology File Support

Support the Cardano P2P topology shape:

```yaml
localRoots:
  - accessPoints:
      - address: 127.0.0.1
        port: 3001
    advertise: false
    trustable: true
    hotValency: 1
    warmValency: 1
    behindFirewall: false
    diffusionMode: InitiatorAndResponder

publicRoots:
  - accessPoints:
      - address: relay.example.com
        port: 3001
    advertise: false
    hotValency: 1
    warmValency: 1

bootstrapPeers:
  - address: bootstrap.example.com
    port: 3001

useLedgerAfterSlot: 0
peerSnapshotFile: big-ledger-peer-snapshot.json
```

Mapping:

- `localRoots` become `LOCAL_ROOT` peers.
- `publicRoots` become `PUBLIC_ROOT` peers.
- `bootstrapPeers` become `BOOTSTRAP` peers.
- `trustable`, `advertise`, `hotValency`, `warmValency`, `behindFirewall`, and
  `diffusionMode` are preserved where Yano has a matching behavior.
- Deprecated `valency` is accepted as an alias for `hotValency`.
- `peerSnapshotFile` is loaded relative to the topology file directory unless
  absolute.
- `useLedgerAfterSlot` controls when ledger peers may be used.

Yano should keep its existing YAML configuration. Topology file support is an
additional interoperability path, not a replacement for current config.

### Peer Snapshot Support

Peer snapshot support should accept:

- local file paths;
- HTTPS URLs;
- topology `peerSnapshotFile`;
- embedded defaults in the future, if useful.

The supported interoperable snapshot format is the Cardano big-ledger peer
snapshot shape generated by `cardano-cli query ledger-peer-snapshot` and used by
the public environment files:

- `NetworkMagic`;
- point metadata when present;
- `bigLedgerPools[]`;
- `relativeStake` or equivalent stake-weight fields;
- `relays[]` containing address and port.

The current parser already understands the `NetworkMagic` / `bigLedgerPools` /
`relays` shape. The implementation must add tests using real environment
snapshots and cardano-cli generated snapshots before claiming topology
`peerSnapshotFile` interoperability. Any additional custom snapshot shape must
be documented as legacy or Yano-specific.

The service should:

- enforce a maximum snapshot byte size before parsing;
- validate network magic when present;
- cache the last successful snapshot;
- keep using the previous snapshot when refresh fails;
- mark snapshot peers as `BOOTSTRAP` or `PUBLIC_ROOT` depending on context;
- avoid blocking node startup indefinitely on snapshot download.

If no configured peer is reachable but a snapshot is available, the governor
should still have snapshot peers to try. This fixes the bootstrap case where a
bad static root prevents progress even though discovery has usable peers.

### Peer Sharing As Gossip

Peer sharing should be modeled as a discovery source:

1. The peer governor selects peers eligible for `PEER_SHARING`.
2. The discovery service asks those peers for peer-sharing results.
3. Results are normalized, filtered, and added as `GOSSIP` peers with TTL.
4. The peer governor decides whether any gossip peers become warm or hot.

Current Yano/Yaci behavior uses `PeerDiscovery`, which opens a separate
seed-dialing N2N connection and runs the peer-sharing mini-protocol there. That
is useful for bootstrap, but it is not the same as relay-style peer sharing over
already established hot/warm N2N sessions.

The relay target is peer-sharing over existing multiplexed N2N connections. If
Yaci does not expose the peer-sharing client agent on an established session,
this phase requires a small Yaci session hook. The seed-based path may remain as
a bootstrap fallback.

Peer-sharing failures should affect the source peer's score only mildly unless
they are protocol errors. A peer returning an empty list is not a failure.

### Ledger Peers

Ledger peer discovery is larger than a query because Yano does not currently
persist relay endpoints from on-chain pool registrations. Current account-state
pool registration data stores deposit, margin, cost, pledge, reward account, and
owners, but not pool relay endpoints. Genesis pools can carry relays, but that
does not provide a current on-chain relay set.

Ledger peer discovery therefore has a required precursor:

- extend pool registration persistence to include relay endpoint metadata;
- expose relay endpoint data through a ledger-state read interface;
- make the new relay index delta-tracked and rollback-safe in
  `DefaultAccountStateStore`;
- maintain epoch-active semantics for pool parameter changes;
- reuse existing active stake distribution/reward-snapshot ranking to select big
  ledger pools.

After that precursor, the discovery service can add the provider interface:

```java
interface LedgerPeerProvider {
    List<LedgerPeerRelay> activeRelays();
    long observedAtSlot();
}
```

Rules:

- `ledger-peers` defaults to false until the relay endpoint provider is
  implemented.
- Enabling ledger peers without a provider must produce clear status/error and
  must not crash.
- If `use-ledger-after-slot < 0`, ledger peer discovery is disabled.
- If `use-ledger-after-slot >= 0`, ledger peers may be used only after local
  chain state has reached that slot.
- Ledger peer records use source `LEDGER`.
- Ledger peer refresh failures keep the last successful ledger peer set for a
  bounded time.

Ledger peer snapshot query serving is a later step. It should be enabled only
when Yano can answer from a consistent ledger view.

### Bootstrap Exit And Recovery

The discovery layer marks bootstrap peers but does not remove them permanently.

The peer governor may reduce bootstrap usage after:

- enough non-bootstrap peers are known;
- enough non-bootstrap peers are warm/hot;
- local sync has reached the configured ledger-peer slot; or
- peer-sharing/gossip has provided replacement peers.

Bootstrap peers should remain available for recovery if the known peer set
collapses.

### Peer Sharing Responses

Relay peer-sharing responses should eventually use the governor's
`sharablePeers(limit)` result. Discovery contributes the metadata needed for
that decision:

- source;
- advertise flag;
- sharable flag;
- routability;
- last seen;
- expiry.

This prevents Yano from sharing every known peer just because it exists in a raw
upstream store.

### Runtime Status

Expose compact discovery fields:

- `relayDiscoveryRunning`
- `relayDiscoveryKnownPeerCount`
- `relayDiscoveryLocalRootCount`
- `relayDiscoveryPublicRootCount`
- `relayDiscoveryBootstrapCount`
- `relayDiscoveryGossipCount`
- `relayDiscoveryLedgerPeerCount`
- `relayDiscoveryLastPeerSharingAtMillis`
- `relayDiscoveryLastSnapshotRefreshAtMillis`
- `relayDiscoveryLastLedgerRefreshAtMillis`
- `relayDiscoveryLastError`

Detailed peer provenance should be a debug/admin endpoint, not the default
status payload.

## Implementation Plan

### Phase 0: Absorb Existing Discovery Path

- Extend or rename `YaciPeerDiscoveryService`; do not add a second live
  discovery service beside it. **Implemented by extending the existing
  service.**
- Keep `UpstreamDiscoveryConfig` as the config contract. **Implemented.**
- Replace direct peer-store discovery writes with governor admission when the
  governor is enabled. **Implemented in `SyncSubsystem.onDiscoveredPeer`.**
- Share one incoming `PeerDescriptor` type with the governor. **Implemented in
  the governor admission path; `PeerStoreEntry` remains the discovery callback
  DTO for compatibility.**
- Preserve current upstream behavior when governor is disabled. **Implemented
  through existing peer-store compatibility.**

Acceptance:

- Existing profiles still start.
- Configured peers appear in discovery and governor status.
- Duplicate configured peers collapse to one normalized endpoint.
- No second discovery config namespace is introduced.

### Phase 1: Source-Aware Address Policy And Topology Parser

- Refactor `PeerAddressPolicy` to accept peer source. **Implemented.**
- Fix IPv6 endpoint parsing. **Implemented.**
- Fix unresolved-host handling so DNS failure does not classify a host as a
  public IP. **Implemented for IP literals; DNS names are admitted and dial-time
  failures flow to connection-manager/governor backoff.**
- Parse `localRoots`, `publicRoots`, `bootstrapPeers`, `useLedgerAfterSlot`, and
  `peerSnapshotFile`. **Implemented for roots, bootstrap peers, and
  `peerSnapshotFile`; `useLedgerAfterSlot` is parsed in config and remains
  inactive while ledger peers are disabled.**
- Preserve `trustable`, `advertise`, `hotValency`, `warmValency`,
  `behindFirewall`, and `diffusionMode` where applicable. **Trust/source is
  applied; full valency/diffusion-mode behavior is deferred to governor
  hardening.**
- Validate ports and required addresses. **Implemented.**

Acceptance:

- A Cardano-style topology file can feed local/public/bootstrap peers into the
  governor.
- Invalid entries are reported without crashing the node.
- IPv4, bracketed IPv6, DNS names, allowlist, and denylist behavior are covered
  by tests.

### Phase 2: Peer Snapshot Loading

- Load peer snapshot files. **Implemented.**
- Download configured peer snapshot URLs with timeout and size limit.
  **Implemented.**
- Parse Cardano big-ledger peer snapshots and keep compatibility with the
  current supported shape. **Implemented for `NetworkMagic` /
  `bigLedgerPools` / `relays`.**
- Add parser tests using real environment snapshots and a cardano-cli generated
  snapshot fixture. **Unit fixture added; real environment URL is used in app
  config and live verification.**
- Cache the last successful result. **Deferred; first implementation loads once
  per service start.**
- Keep previous snapshot records when refresh fails. **Deferred to refresh
  implementation.**
- Use snapshot peers for bootstrap if static peers fail. **Implemented through
  governor admission and discovery-first fallback.**

Acceptance:

- Yano can start with no static peers when a valid snapshot URL/file is
  configured.
- A bad static peer does not block trying snapshot peers.
- Snapshot refresh failures are visible in status but do not stop sync.
- Oversized snapshots are rejected before full parse.

### Phase 3: Discovery-First Bootstrap

- Ensure discovery runs before the first required outbound sync attempt in relay
  profiles. **Implemented by starting discovery before peer selection.**
- Let the governor choose from static, topology, snapshot, and bootstrap peers.
  **Implemented.**
- Preserve bootstrap peers for recovery.
  **Implemented by governor preservation rules.**

Acceptance:

- If the first configured peer is invalid but discovery has healthy peers, Yano
  can still find a peer and sync.
- Recovery can return to bootstrap peers when gossip/ledger peers disappear.

### Phase 4: Peer Sharing Discovery

- Keep the existing seed-based `PeerDiscovery` path as bootstrap fallback.
  **Implemented.**
- Ask eligible hot/warm peers for peer-sharing results over existing N2N
  sessions when Yaci exposes the required hook. **Deferred; Yaci hook not yet
  exposed.**
- Add results as TTL-bound `GOSSIP` peers.
  **Implemented for seed-based peer-sharing results.**
- Feed gossip peers into the governor.
  **Implemented.**
- Score protocol failures, not empty responses.
  **Deferred to richer peer-sharing observations.**

Acceptance:

- New gossip peers appear after peer sharing.
- Expired gossip peers are removed when not connected.
- Peer sharing does not mutate canonical chain state.
- If the existing-session Yaci hook is missing, the implementation status states
  that only seed-based peer sharing is active.

### Phase 5: Rollback-Safe Pool Relay Persistence

- Extend pool registration persistence to include relay endpoints.
- Add a rollback-safe pool relay index to `DefaultAccountStateStore`.
- Delta-track all new relay-index writes and deletes.
- Preserve epoch-active pool parameter semantics.
- Expose relay endpoints through a read interface.

Acceptance:

- Pool relay endpoints survive restart.
- Rollback restores previous relay endpoint state.
- Epoch-boundary tests verify active relay changes.
- Existing pool-parameter and reward tests continue to pass.

Status: deferred. This is required before production ledger-peer discovery can
be enabled.

### Phase 6: Ledger Peer Provider And Discovery

- Add `LedgerPeerProvider` with a disabled default provider.
- Combine existing active stake ranking with the new relay endpoint provider.
- Apply `useLedgerAfterSlot`.
- Add relays as `LEDGER` peers.
- Refresh on interval and after relevant ledger-state changes.
- Add config and status for ledger peer discovery only when the provider exists.

Acceptance:

- Feature is disabled by default.
- Enabling without a provider gives a clear status/error and does not crash.
- After the configured slot, ledger peers are added to the governor.
- Ledger refresh failure keeps the last good set for a bounded time.

Status: deferred. `ledger-peers` defaults to false and enabling it currently
logs a clear warning without crashing.

### Phase 7: Sharable Peer Responses

- Update peer-sharing provider to prefer governor `sharablePeers(limit)`.
  **Implemented.**
- Use discovery metadata for `advertise` and source filtering. **Partially
  implemented through governor source/backoff/quarantine rules; stricter
  advertise behavior follows local-root valency hardening.**
- Keep self-advertisement behavior from ADR-NET-008C. **Implemented.**

Acceptance:

- Local roots with `advertise=false` are not shared.
- Gossip/ledger peers are shared only when routable and healthy.
- Response size stays bounded by request amount.

Status: implemented for the current peer-sharing provider.

### Phase 8: Live Verification

Run live preprod tests with:

- topology file only;
- peer snapshot URL only;
- invalid static peer plus valid snapshot;
- peer sharing enabled;
- private local root with `advertise=false`;
- local inbound Cardano node;
- ledger peers disabled.

Later, repeat with ledger peers enabled after relay endpoint persistence and
provider implementation.

Acceptance:

- Yano syncs with topology-only and snapshot-only configs.
- Invalid static peers do not block discovery-first bootstrap.
- Peer sharing adds gossip peers.
- Private non-advertised local roots are not shared.

Status: completed for this ADR pass on 2026-06-30 for snapshot/discovery-first
bootstrap with ledger peers disabled.

Live verification used the official preprod peer snapshot URL, an intentionally
invalid configured peer, several unreachable/refused snapshot peers, and a
local Haskell node connected inbound from `127.0.0.1:32000`.

Observed status during the run:

- the official preprod snapshot
  `https://book.play.dev.cardano.org/environments/preprod/peer-snapshot.json`
  was accepted with 32 relay entries;
- discovery/governor state reached `relayKnownPeerCount=41`;
- invalid static roots did not block recovery to reachable snapshot/bootstrap
  peers;
- Yano recovered to active peer `132.226.203.38:6001`;
- `inSync=true` at slots `127136147` and later `127136177`;
- Haskell downstream connected to the Yano N2N server and opened ChainSync and
  TxSubmission;
- ledger peers remained disabled as designed.

Topology-only and snapshot-only profile runs are still useful release-candidate
checks, but the implemented discovery-first behavior was verified against the
real preprod network.

## Invariants

- Discovery only discovers peers; it does not connect canonical sync sockets
  directly.
- Discovery does not select canonical chains.
- Discovery failures must not crash the process.
- Existing upstream config remains compatible.
- Peer-sharing results are untrusted until governed and validated by later
  layers.
- Ledger peer discovery must not be enabled by default before ledger state can
  provide consistent relay data.
- Discovery must not bypass the governor's known-peer bound.

## Non-Goals

- A second discovery subsystem beside `YaciPeerDiscoveryService`.
- A second discovery config namespace for the same policy.
- Peer governor state machine.
- Connection manager implementation.
- Genesis or Praos chain selection.
- Full block validation.
- Transaction validation.
- Block production.
- NAT traversal.
- Automatic public reachability guarantees.

## Risks

- Supporting both Yano config and topology files can create duplicate concepts.
  Normalize both into one governor input descriptor and keep one internal model.
- Snapshot URLs can be slow, unavailable, or oversized. Use timeouts, size
  limits, and last-good cache.
- Peer-sharing can produce many low-quality peers. Use TTL, routability filters,
  and governor scoring.
- Ledger peers depend on ledger correctness and rollback-safe relay persistence.
  Keep the source disabled until the ledger provider is reliable.
- Existing-session peer sharing may require a Yaci session hook. Keep the
  seed-based path as a fallback until that hook is available.

## Consequences

Yano gains a dedicated discovery boundary that feeds the peer governor from
static config, topology files, snapshots, peer sharing, and eventually ledger
state. Relay startup no longer has to depend on one good static upstream, and
peer-sharing responses can become source-aware and safer without duplicating
discovery logic in the sync and server subsystems.
