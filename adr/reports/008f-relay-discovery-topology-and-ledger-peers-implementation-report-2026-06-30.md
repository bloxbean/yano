# ADR-NET-008F Implementation Report: Relay Discovery, Topology, And Ledger Peers

## Summary

Implemented the discovery foundation by extending the existing
`YaciPeerDiscoveryService` and feeding discovered peers through the governor
admission path.

The implementation supports source-aware address policy, Cardano-style topology
roots, peer snapshot files/URLs, and discovery-first bootstrap with a small
configuration surface.

## Implemented

- Kept `yano.upstream.discovery.*` as the discovery config namespace.
- Added discovery config fields:
  - `topology-file`
  - `ledger-peers`
  - `use-ledger-after-slot`
- Refactored `PeerAddressPolicy` to accept `PeerSource`.
- Fixed endpoint parsing for bracketed IPv6 such as `[::1]:3001`.
- Kept private-address admission source-aware:
  - local/static roots may use private addresses;
  - public/gossip/bootstrap/ledger peers reject private IP literals unless
    `allow-private-addresses=true`.
- Added topology parsing for:
  - `localRoots`
  - `publicRoots`
  - `bootstrapPeers`
  - topology-relative `peerSnapshotFile`
- Added peer snapshot loading from files and HTTPS URLs.
- Enforced a 2 MiB snapshot byte cap before JSON parsing.
- Validated `NetworkMagic` when present.
- Added snapshot peers as bootstrap candidates.
- Preserved seed-based Yaci peer-sharing discovery and routed results as
  `GOSSIP` peers through the governor.
- Added preprod peer snapshot URL to `app/config/application.yml`.

## Deliberate Deferrals

- Ledger peers remain disabled by default. Yano must first persist pool relay
  endpoint metadata in a rollback-safe account-state index.
- Peer sharing over already-established hot/warm N2N sessions is deferred until
  Yaci exposes a client hook on existing sessions. The seed-based bootstrap path
  remains active.
- Snapshot refresh caching and last-good fallback are deferred; the current
  implementation loads snapshots once per service start.

## Verification

- `./gradlew :runtime:test --tests com.bloxbean.cardano.yano.runtime.sync.multipeer.MultiPeerScaffoldingTest`
- `./gradlew :runtime:test`
- `./gradlew :app:test`
- `./gradlew :app:quarkusBuild`
- Final focused regression/build pass:
  `./gradlew :core-api:test --tests com.bloxbean.cardano.yano.api.model.NodeStatusTest :runtime:test --tests com.bloxbean.cardano.yano.runtime.sync.multipeer.MultiPeerScaffoldingTest --tests com.bloxbean.cardano.yano.runtime.connection.DefaultRelayConnectionManagerTest :app:quarkusBuild`

## Live Preprod Verification

Date: 2026-06-30.

Command:

```bash
cd /Users/satya/work/bloxbean/yano/app
java -jar build/yano.jar
```

Observed behavior:

- The official preprod snapshot URL
  `https://book.play.dev.cardano.org/environments/preprod/peer-snapshot.json`
  was loaded and accepted with 32 relay entries.
- Known peers reached 41 after configured peers, persisted peers, and snapshot
  peers were admitted through the governor.
- An invalid static peer did not block discovery-first recovery; Yano selected
  reachable peer `132.226.203.38:6001`.
- Yano reached `inSync=true` and applied Conway blocks through slot
  `127136177`.
- Local Haskell preprod connected inbound from `127.0.0.1:32000`.
- Ledger peers remained disabled as designed because rollback-safe pool relay
  endpoint persistence is not implemented yet.

## Notes

- Discovery does not open canonical sync sockets directly and does not select
  canonical chains.
- Discovery failures are logged as warnings and do not crash the node.
- Ledger peer configuration validates but does not activate a provider yet.
