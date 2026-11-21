# Yano P2P

Peer-to-peer networking and relay policy for Yano: upstream peer sessions and their health, the relay
connection manager, the peer governor, peer-sharing discovery and transaction diffusion. Sockets and
mini-protocols come from Yaci; this module decides which peers to use and how. Canonical sync stays in
`runtime`.

## Artifact

`org.yanoproject:yano-p2p`

## Key contents

| Package | What it holds |
|---|---|
| `org.yanoproject.p2p.peer` | Upstream peer sessions: `DefaultPeerClientFactory`, `PeerHealth`, `PeerSessionStatus`, `PeerRecoveryFailureTracker`, `LocalBindAddressResolver`. |
| `org.yanoproject.p2p.connection` | `RelayConnectionManager` and `DefaultRelayConnectionManager`, connection keys, states and snapshots. |
| `org.yanoproject.p2p.governor` | `PeerGovernor` (bounded admission and cold/warm/hot/backoff state), `PeerStore` with `InMemoryPeerStore` and `FileBackedPeerStore`, `PeerAddressPolicy`. |
| `org.yanoproject.p2p.discovery` | `YaciPeerDiscoveryService`, which feeds Yaci peer-sharing results into the peer store. |
| `org.yanoproject.p2p.peersharing` | `RelayPeerSharingProvider`, which answers peer-sharing requests. |
| `org.yanoproject.p2p.tx.diffusion` | `TxDiffusion` / `DefaultTxDiffusion`, with the runtime-owned `TxAdmissionPort` and `TxCatalog` boundaries. |

`PeerClientFactory` and `PeerEndpoint`, in the same `org.yanoproject.p2p.peer` package, are contracts
published by [`node-api`](../node-api/README.md).

## Dependencies and boundaries

- Depends on [`cardano-api`](../cardano-api/README.md) and Yaci `core`/`helper`; Netty is `compileOnly`.
- Must not depend on `runtime` or `consensus`; `P2pArchitectureTest` (ArchUnit) enforces this.
- `runtime` depends on it (`api`).

Design: [ADR-NET-008H P2P module extraction](../adr/network/008h-p2p-module-extraction.md) and the other
`adr/network/008*` records.

## Build and test

```bash
./gradlew :p2p:build -PskipSigning=true
./gradlew :p2p:test
```
