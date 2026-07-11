# ADR-NET-008G Phase 1 Implementation Report: Source-Port Reuse

## Summary

Implemented default-enabled outbound source-port reuse for Yano relay upstream
dials. Supervised outbound N2N connections try to bind their local source port
to the configured Yano relay server port before dialing. If a specific dial
cannot bind because of `TIME_WAIT` or platform socket rules, Yano falls back to
an OS-assigned source port for that dial so sync can continue.

## Implemented

- Added optional Yaci local-bind host/port fields to `NodeClientConfig`.
- Wired Yaci `Session` to call Netty `connect(remote, local)` when a local
  bind port is configured.
- Enabled `SO_REUSEADDR` on the Yaci TCP client when local binding is active.
- Added Yano `DefaultPeerClientFactory.supervisedWithLocalBind(...)`.
- Added route-based local bind-host resolution in Yano so outbound dials bind
  to the concrete local interface address, not wildcard.
- Added Yano config key:
  `yano.relay.connection.source-port-reuse`.
- Enabled Yaci local-bind fallback only for Yano's supervised relay upstream
  dials.
- Enabled source-port reuse by default in the bundled Yano config and runtime
  fallback.
- Kept the app-folder preprod config enabled for manual verification.

## Verification

- Yaci targeted tests:
  `./gradlew :core:test --tests com.bloxbean.cardano.yaci.core.network.NodeClientConfigTest --tests com.bloxbean.cardano.yaci.core.network.SessionTest --tests com.bloxbean.cardano.yaci.core.network.TCPNodeClientTest`
- Yaci local publish:
  `./gradlew publishToMavenLocal -PskipSigning`
- Yano targeted regression/build:
  `./gradlew :runtime:test --tests com.bloxbean.cardano.yano.runtime.peer.DefaultPeerClientFactoryTest --tests com.bloxbean.cardano.yano.runtime.connection.DefaultRelayConnectionManagerTest --tests com.bloxbean.cardano.yano.runtime.sync.multipeer.MultiPeerScaffoldingTest :app:quarkusBuild`

## Live Preprod Verification

Date: 2026-06-30.

Command:

```bash
cd /Users/satya/work/bloxbean/yano/app
java -jar build/yano.jar
```

Observed:

- `yano.relay.connection.source-port-reuse=true`
- Yano resolved the local bind address as `192.168.68.100:13338`.
- Status reached `inSync=true` with three established outbound upstream
  connections.
- `lsof` showed two established outbound peers using the relay source port:
  `192.168.68.100:13338->3.12.62.57:3001` and
  `192.168.68.100:13338->3.127.163.30:3001`.
- One active upstream used an OS-assigned source port after local-bind
  contention, which is the intended availability fallback.
- Yano auto-transitioned to steady state with distance to tip `0` slots.

## Operational Notes

- Source-port reuse is enabled by default in Yano, but it remains
  availability-safe rather than strict: individual dials can fall back to
  OS-assigned source ports when the platform rejects local binding.
- The implementation derives a concrete local interface address from the route
  to an upstream peer and binds that address with the configured Yano relay
  server port.
- If the platform rejects a specific local bind, that dial falls back to an
  OS-assigned source port and normal upstream recovery/failover logic continues.
