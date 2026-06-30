# ADR-NET-008D Implementation Report: Relay Connection Manager

## Summary

Implemented the core relay connection-manager foundation for ADR-NET-008D.
The implementation keeps operator config small: only inbound total and per-IP
limits are exposed.

Follow-up: outbound source-port reuse was implemented later on 2026-06-30 as
an ADR-NET-008G Phase 1 hardening item. It is enabled by default and remains
controlled by `yano.relay.connection.source-port-reuse`.

## Implemented

- Added Yaci `ServerConnectionListener` and `ServerConnectionDecision` hook.
- Wired Yaci `NodeServer` to call the hook before protocol session creation.
- Added server-side handshake completion notification so inbound capabilities
  can be recorded after `AcceptVersion` is sent.
- Exposed negotiated protocol version from Yaci `N2NPeerFetcher` and
  `PeerClient`.
- Added Yano `runtime.connection` package:
  - `ConnectionKey`
  - `ConnectionDirection`
  - `ConnectionState`
  - `ProtocolCapabilities`
  - `RelayConnectionEvent`
  - `RelayConnectionListener`
  - `RelayConnectionSnapshot`
  - `RelayConnectionManager`
  - `DefaultRelayConnectionManager`
- Added outbound tracking through `PeerClientFactory` wrapping without changing
  `PeerSession` canonical sync ownership.
- Enforced inbound limits:
  - `yano.relay.connection.max-inbound-connections`
  - `yano.relay.connection.max-connections-per-ip`
- Added compact relay connection status fields to `NodeStatus`.
- Added per-connection snapshot entries for internal consumers.
- Wired peer sharing to include active, established, peer-sharing-capable
  outbound connections from the connection snapshot.
- Wired the relay peer governor as a `RelayConnectionListener`.
- Added defaults to bundled and app-folder YAML config.

## Phase Status

- Phase 0: implemented.
- Phase 1: implemented.
- Phase 2: implemented for outbound reservation, duplicate suppression, and
  failure counters. Reconnect-after hints remain with the existing supervisor
  and future peer governor.
- Phase 3: implemented.
- Phase 4: implemented conservatively for outbound duplicate suppression and
  canonical-sync non-interference. Aggressive inbound/outbound replacement is
  deferred.
- Phase 5: implemented for peer-sharing and peer-governor consumers. Tx
  diffusion intentionally continues to use the tx-submission handler peer ids
  to avoid duplicate protocol identity.
- Phase 6: implemented and live verified for the scoped connection-manager
  behavior. Inbound rejection limits were verified by unit tests; the live run
  verified normal inbound admission from a local Haskell node.

## Verification

- Yaci compile: `./gradlew :core:compileJava :helper:compileJava`
- Yaci targeted test:
  `./gradlew :core:test --tests com.bloxbean.cardano.yaci.core.protocol.handshake.HandshakeNegotiationTest`
- Yaci local publish:
  `./gradlew publishToMavenLocal -PskipSigning`
- Yano compile:
  `./gradlew :runtime:compileJava :app:compileJava`
- Yano targeted manager test:
  `./gradlew :runtime:test --tests com.bloxbean.cardano.yano.runtime.connection.DefaultRelayConnectionManagerTest`
- Yano runtime suite: `./gradlew :runtime:test`
- Yano app producer config test:
  `./gradlew :app:test --tests com.bloxbean.cardano.yano.app.YanoProducerTest`
- Yano app build: `./gradlew :app:quarkusBuild`
- Additional post-governor validation:
  `./gradlew :runtime:test --tests com.bloxbean.cardano.yano.runtime.connection.DefaultRelayConnectionManagerTest`
- Final focused regression/build pass:
  `./gradlew :core-api:test --tests com.bloxbean.cardano.yano.api.model.NodeStatusTest :runtime:test --tests com.bloxbean.cardano.yano.runtime.sync.multipeer.MultiPeerScaffoldingTest --tests com.bloxbean.cardano.yano.runtime.connection.DefaultRelayConnectionManagerTest :app:quarkusBuild`

## Live Preprod Verification

Date: 2026-06-30.

Command:

```bash
cd /Users/satya/work/bloxbean/yano/app
java -jar build/yano.jar
```

Environment:

- Yano config: `app/config/application.yml`
- Network: preprod
- Storage: `app/chainstate-preprod-static-multi`
- HTTP port: `7072`
- N2N server port: `13338`
- Local Haskell preprod node: `127.0.0.1:32000`

Observed status while running:

```json
{
  "inSync": true,
  "peerName": "132.226.203.38:6001",
  "upstreamActivePeer": "132.226.203.38:6001",
  "relayInboundConnectionCount": 1,
  "relayOutboundConnectionCount": 3,
  "relayEstablishedConnectionCount": 4,
  "relayKnownPeerCount": 41
}
```

Observed behavior:

- Haskell downstream connected inbound from `127.0.0.1:32000`.
- TxSubmission server initialized and sent `RequestTxIds` to the downstream
  peer.
- Yano applied Conway blocks through slot `127136177` during the run.
- Connection-manager counts matched `lsof`: three outbound Yano upstream
  sockets plus one inbound Haskell socket.
- Invalid/refused preprod peers produced one-line warnings; no stack traces
  were emitted for expected network failures.
- No duplicate outbound dial warning was observed.

## Notes

- The connection manager does not close or replace the canonical sync
  connection directly. Sync recovery remains owned by the existing supervisor
  and generation-fenced apply boundary.
- `TxDiffusion` still receives inbound tx peer lifecycle events through
  `YaciTxSubmissionHandler`, which keeps peer ids aligned with tx-submission
  protocol state.
- A detailed connection-list admin endpoint was intentionally not added; the
  default status endpoint remains aggregate-only.
