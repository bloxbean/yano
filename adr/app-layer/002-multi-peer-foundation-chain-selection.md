# ADR-002: Multi-Peer Foundation with Pluggable Chain Selection

## Status
Historical draft — superseded by Yano's P2P/consensus architecture

## Date
2026-03-11

> **Current-status note (2026-07-17):** The multi-peer foundation was delivered
> and subsequently evolved outside this draft before the current app-chain
> architecture was finalized. Treat the proposal below as design history, not
> as the active runtime contract. ADR-005 and the current implementation are
> authoritative; remaining work is indexed in [open_item.md](open_item.md).

## Context

Yaci currently connects to a single upstream Cardano node. This is a blocker for:
- **L1 resilience** — single point of failure for block sync
- **App layer** (ADR-001) — requires Yaci-to-Yaci mesh for app data gossip
- **Performance** — can't distribute block fetching across multiple peers

Additionally, with multiple upstream peers, each may present a different chain tip. A **chain selection rule** is needed to decide which chain to follow. This must be pluggable to support both standard Cardano relay behavior and future app-layer policies.

## Decision

### Multi-Peer Architecture

Replace single upstream with `PeerPool` managing multiple connections. Each peer tagged with type (`cardano` or `yaci`). Existing `N2NPeerFetcher` remains unchanged as a single-peer worker — the pool manages multiple instances.

Backward-compatible: when no `upstreams` list is configured, the existing `remote.host/port` is used as a single peer (identical to current behavior).

### Chain Selection

**Two-event + Strategy pattern:**

1. **`ChainCandidateEvent`** (VetoableEvent) — "Should we consider this chain?" Published when a peer presents a tip that differs from current best. Plugins can reject candidates (e.g., fork deeper than k, blacklisted peer).

2. **`ChainSelectionStrategy`** interface — "Which candidate wins?" Pluggable comparison logic.

**Default strategy (Amaru-style Praos):**
- Primary: highest block number (longest chain)
- Tie-break: smallest tip slot (Amaru approach — no Java VRF library available)
- Rollback limit: k from Shelley genesis `securityParam` (already parsed in `ShelleyGenesisData.securityParam()`, e.g., 2160 for mainnet/preprod)

Future: swap to VRF tie-breaking when a Java VRF library becomes available. Strategy is pluggable — zero application code changes needed.

### Peer Discovery

Reuse existing `PeerSharingAgent` (protocol 10) for automatic peer discovery when enabled.

### Test Topology

- Public preprod relay: `preprod-node.world.dev.cardano.org:30000`
- Local cardano-node: `localhost:32000`

## Architecture

```
YaciNodeConfig
  upstreams:
    - { host, port, type: cardano }   ← public preprod relay
    - { host, port, type: cardano }   ← local node on 32000

         ┌──────────────────────────────────┐
         │           PeerPool               │
         │  ┌────────────┐ ┌──────────────┐│
         │  │ PeerConn 1 │ │ PeerConn 2   ││
         │  │ preprod     │ │ localhost    ││
         │  │ :30000      │ │ :32000       ││
         │  └──────┬─────┘ └──────┬───────┘│
         └─────────┼──────────────┼────────┘
                   │              │
         ┌─────────▼──────────────▼──┐
         │       HeaderFanIn         │
         │  - dedup by block hash    │
         │  - ChainCandidateEvent    │
         │  - ChainSelectionStrategy │
         │  - best chain tracking    │
         └────────────┬──────────────┘
                      │
         ┌────────────▼──────────────┐
         │    BodyFetchScheduler     │
         │  - fetch from best peer   │
         │  - failover to next       │
         └────────────┬──────────────┘
                      │
         ┌────────────▼──────────────┐
         │       ChainState          │
         └───────────────────────────┘
```

## Key Components

### ChainSelectionStrategy (node-api)
```java
public interface ChainSelectionStrategy {
    int compare(ChainCandidate a, ChainCandidate b);
    long maxRollbackDepth();
}
```

### ChainCandidate (node-api)
```java
public record ChainCandidate(
    String peerId, long blockNumber, long slot,
    byte[] blockHash, Point intersectionPoint, long forkDepth
) {}
```

### ChainCandidateEvent (node-api, VetoableEvent)
Published before a candidate chain is considered. Plugins can reject.

### PraosChainSelection (node-runtime)
- `compare()`: block number first, smallest slot tie-break
- `maxRollbackDepth()`: returns k from Shelley genesis `securityParam`

### PeerPool (node-runtime)
Manages N `PeerConnection` wrappers around `N2NPeerFetcher`. Tracks per-peer tip, health, latency.

### HeaderFanIn (node-runtime)
Receives headers from multiple peers, deduplicates, evaluates candidates, tracks best chain.

### BodyFetchScheduler (node-runtime)
Fetches blocks from best peer with failover to next best.

## Configuration

```yaml
yaci:
  node:
    # Existing (backward compatible)
    remote:
      host: preprod-node.world.dev.cardano.org
      port: 30000
      protocol-magic: 1

    # New: multi-peer upstream
    upstreams:
      - host: "preprod-node.world.dev.cardano.org"
        port: 30000
        type: cardano
      - host: "localhost"
        port: 32000
        type: cardano

    # New: peer discovery
    peer-discovery:
      enabled: false
      interval-seconds: 60
      max-peers: 20

    # k parameter from shelley genesis securityParam (no config needed)
```

## Security Parameter (k)

The rollback limit k is read from the Shelley genesis file (`securityParam` field), already parsed by `ShelleyGenesisParser` into `ShelleyGenesisData.securityParam()`. Values: mainnet=2160, preprod=2160, preview=432, devnet=100. No separate configuration needed.

## Implementation Phases

1. Config + data types + interfaces
2. PeerPool + PeerConnection
3. HeaderFanIn + chain selection
4. BodyFetchScheduler
5. YaciNode integration
6. Peer discovery service
7. Integration testing (public preprod + localhost:32000)

## Risks

- **Multi-peer complexity**: Mitigated by keeping N2NPeerFetcher unchanged; pool is orchestration only
- **Single-upstream regression**: Backward-compatible config; feature-flagged
- **Chain selection edge cases**: Start with Amaru's proven approach; VRF upgrade later
