# ADR-NET-008C Implementation Report: Relay Discovery MVP and Simple Tx Diffusion

## Scope

Implemented the ADR-NET-008C MVP:

- simple `yano.tx.diffusion.enabled` switch;
- `all-hot` tx diffusion by default when no legacy mode is configured;
- `yano.relay.auto-discovery` config;
- server-side peer-sharing handshake and mini-protocol support;
- relay peer-sharing provider using Yano's advertised endpoint and upstream
  peer store;
- relay status fields on `/api/v1/node/status`.

## Code Changes

Yaci:

- `HandshakeAgent` now negotiates `peerSharing=min(requested,supported)`.
- Added `PeerSharingServerAgent`.
- Added handshake and peer-sharing server unit tests.

Yano:

- Added `YanoPropertyKeys.Relay` and `Tx.DIFFUSION_ENABLED`.
- `YanoProducer` maps `relay.auto-discovery=true` to upstream discovery and
  peer-sharing requests.
- `TxSubsystem` defaults to `all-hot` when tx diffusion is enabled and no
  legacy mode is configured.
- `ServeSubsystem` advertises peer sharing and installs
  `PeerSharingServerAgent` per inbound N2N session.
- `RelayPeerSharingProvider` returns bounded, duplicate-suppressed peers.
- `SyncSubsystem` exposes a read-only peer-store snapshot for server-side
  sharing.
- `NodeStatus` includes `relayAutoDiscovery`, `relayAdvertisedHost`, and
  `relayAdvertisedPort`.
- `app/config/application.yml` was updated for local preprod testing:
  `relay.auto-discovery=true`, `advertised-host=127.0.0.1`,
  `advertised-port=13338`, `allow-private-addresses=true`, and
  `tx.diffusion.enabled=true`.

## Verification

Build and unit tests:

- `/Users/satya/work/bloxbean/yaci`: `./gradlew :core:test --tests
  com.bloxbean.cardano.yaci.core.protocol.peersharing.PeerSharingServerAgentTest
  --tests
  com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeNegotiationTest`
  passed.
- `/Users/satya/work/bloxbean/yaci`: `./gradlew -PskipSigning
  publishToMavenLocal` passed.
- `/Users/satya/work/bloxbean/yano`: focused runtime/app tests passed,
  including `RelayPeerSharingProviderTest`, `TxSubsystemTest`,
  `YaciTxSubmissionHandlerTest`, `SyncSubsystemTest`, and
  `YanoProducerTest`.
- `/Users/satya/work/bloxbean/yano`: `./gradlew :app:quarkusBuild` passed.

Live preprod test:

- Started Yano from `app/` with `config/application.yml`.
- Restarted the Haskell preprod node in
  `/Users/satya/work/cardano-node/preprod`.
- Haskell connected to Yano as a local root:
  `127.0.0.1:32000 -> 127.0.0.1:13338`.
- Haskell log showed successful N2N v15 handshake with
  `peerSharing = PeerSharingEnabled`.
- Yano log showed an inbound session and installed
  `PeerSharingServerAgent (protocol 10)`.
- Submitted a 1 ADA self-payment to the local Haskell node:
  `18e68b539d8be59b4be030177d0729db5db68fd345f5afd386780ac43aaa526b`.
- Yano accepted the diffused transaction from the Haskell peer:
  `txDiffusionInboundAccepted=1`, `txDiffusionPeerCount=1`,
  `txDiffusionInboundRejected=0`.
- A live Yaci `PeerDiscovery` client connected to Yano, negotiated
  `peerSharing=1`, requested 5 peers, and received a bounded list including
  Yano's advertised `127.0.0.1:13338`.

## Notes

This local test proves relay peer-sharing support and Haskell-to-Yano tx
diffusion on one machine. It does not prove public network discoverability,
because the test advertises `127.0.0.1`. Public relay testing requires a
routable advertised host, open N2N port, and peers that can advertise Yano to
the wider Cardano peer-sharing graph.
