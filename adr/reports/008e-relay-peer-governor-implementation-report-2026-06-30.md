# ADR-NET-008E Implementation Report: Relay Peer Governor

## Summary

Implemented the first relay peer-governor pass by evolving the existing
`PeerGovernor` instead of adding a second governor or config namespace.

The implementation keeps the operator surface small and uses the existing
`yano.upstream.governor.*` settings for known/warm/hot targets.

## Implemented

- Added explicit peer model types:
  - `PeerSource`
  - `PeerState`
  - `PeerUse`
  - `PeerDescriptor`
  - `PeerGovernorSnapshot`
- Extended `PeerGovernor` into the bounded peer-governance authority.
- Routed configured peers, discovered peers, and inbound connection observations
  through governor admission.
- Preserved configured/local/bootstrap peers ahead of gossip during known-peer
  eviction.
- Added simple scoring and fixed-delay backoff for non-canonical connection
  failures.
- Kept canonical active-peer recovery with the existing sync supervisor.
- Switched peer identity to canonical endpoint keys so discovery records and
  connection-manager events update the same governed peer.
- Kept runtime score, hot/warm/backoff state, connection observations, and
  inbound ephemeral peers in memory only.
- Restored stable endpoint cache persistence with debounced admission flushes
  and clean-shutdown flush.
- Exposed compact governor counts in `NodeStatus`.
- Pointed relay peer-sharing at `governor.sharablePeers(...)`.
- Registered the governor as a `RelayConnectionListener`.
- Added selected-upstream promotion hysteresis so near-tip observer candidates
  do not churn the canonical peer unless the active peer has failed or the
  candidate has a meaningful block lead.

## Deliberate Deferrals

- Strict local-root group valency is not yet enforced. Local/root source
  metadata is preserved and global warm/hot targets are enforced.
- Governor-issued outbound dial scheduling is not yet a separate loop.
  Existing sync/observer paths continue to request hot peers and the connection
  manager suppresses duplicate outbound dials.
- Tx diffusion still uses tx-submission protocol peer state. Capability-aware
  tx peer selection can be added once the protocol identity and connection
  identity are unified.

## Verification

- `./gradlew :core-api:compileJava`
- `./gradlew :runtime:compileJava`
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

Observed status while running:

```json
{
  "inSync": true,
  "relayKnownPeerCount": 41,
  "relayHotPeerCount": 3,
  "relayWarmPeerCount": 8,
  "relayBackoffPeerCount": 4,
  "relayInboundPeerCount": 1,
  "relaySharablePeerCount": 36,
  "upstreamHotPeerCount": 3,
  "upstreamObserverPeerCount": 2,
  "upstreamCandidateHeaderCount": 30
}
```

Observed behavior:

- A deliberately invalid configured peer and several refused/unresolvable peers
  moved through retry/backoff behavior without noisy stack traces.
- The governor kept three hot upstream peers and two observer sessions while
  Yano stayed at tip.
- No selected-upstream switch churn was observed during the near-tip run.
- The local Haskell node was recorded as an inbound relay peer.

## Notes

- Peer governance state is not chain state. Hot peer selection remains separate
  from canonical chain adoption.
- The governor does not close or replace the canonical sync connection.
- Scores and backoff are intentionally not persisted; they are relearned after
  restart.
- The file-backed peer store is a stable endpoint seed/cache format, not a
  runtime state journal; stable endpoint entries are flushed without persisting
  runtime scoring/backoff.
