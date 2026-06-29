# ADR-NET-008 Phase 8 Implementation Report

## Scope

- Added discovery configuration and runtime surface:
  - enabled flag;
  - peer-sharing flag;
  - seeds;
  - peer snapshot URLs;
  - peer snapshot files;
  - peer snapshot limit;
  - allow/deny lists;
  - private-address policy.
- Added Yaci peer-sharing discovery service.
- Added official Cardano Operations Book peer-snapshot support.
- Added default snapshot URL derivation for `p2p-relay` on `mainnet`,
  `preprod`, and `preview` when discovery is enabled and no explicit snapshot
  URL is configured.
- Added snapshot network-magic validation and relay scoring from
  `relativeStake`.

## Review

- Discovery is opt-in and only starts in multi-peer modes.
- Peer snapshot entries are treated as untrusted discovered peers.
- Snapshot entries are batched before observer refresh to avoid startup churn.
- Live peer-sharing entries can trigger observer refresh after address policy
  checks.
- Candidate headers from observer sessions remain non-canonical unless chain
  selection permits adoption.

## Verification

- `MultiPeerScaffoldingTest.peerSnapshotFileSeedsPeerStoreEntries`
- `MultiPeerScaffoldingTest.peerSnapshotWithWrongNetworkMagicIsIgnored`
- `MultiPeerScaffoldingTest.peerAddressPolicyRejectsPrivateAddressesUnlessAllowed`
- `./gradlew :runtime:test --tests "com.bloxbean.cardano.yano.runtime.sync.multipeer.MultiPeerScaffoldingTest" --tests "com.bloxbean.cardano.yano.runtime.sync.SyncSubsystemTest" --tests "com.bloxbean.cardano.yano.runtime.config.UpstreamConfigTest" --console=plain`
- Live preprod run in `p2p-relay` mode against
  `preprod-node.play.dev.cardano.org:3001`, local HTTP `7098`, local N2N
  `7099`.
- Live status reported:
  - `upstreamMode=p2p-relay`;
  - `upstreamHotPeerCount=2`;
  - `upstreamObserverPeerCount=1`;
  - `upstreamKnownPeerCount=71`;
  - `upstreamCandidateHeaderCount=217`;
  - `upstreamDiscoveryRunning=true`;
  - `blocksProcessed=85881`;
  - `lastProcessedSlot=1803783`;
  - active peer `preprod-node.play.dev.cardano.org:3001/RUNNING`.
- File-backed peer-store persistence was enabled for the live run and persisted
  the 71 discovered/snapshot peers.
- Final validation passed with `./gradlew :app:haskellSyncTest --console=plain`.
  The latest run used Yano n2n ports `58191` and `58259`, not local port
  `3001`.
