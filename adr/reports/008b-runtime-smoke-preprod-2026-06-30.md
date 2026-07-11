# ADR-NET-008B Runtime Smoke: Preprod Local-Submit Diffusion

Date: 2026-06-30

## Scope

Started the app module from `app/` with the built jar and
`app/config/application.yml` after implementing phases 1-4.

Command:

```sh
java --add-opens java.base/java.lang=ALL-UNNAMED -jar build/yano.jar
```

## Configuration

- Network: preprod.
- Upstream mode: `p2p-relay`.
- Tx diffusion mode: `local-submit-only`.
- Mempool byte/count limits and tx diffusion limits were loaded from
  `app/config/application.yml`.

## Observed Status

The status endpoint was reachable on port `7072` and exposed the new mempool
and tx diffusion fields:

```json
{
  "running": true,
  "syncing": true,
  "initialSyncComplete": false,
  "localTipSlot": 127103617,
  "remoteTipSlot": 127103557,
  "upstreamMode": "p2p-relay",
  "upstreamHotPeerCount": 3,
  "upstreamObserverPeerCount": 2,
  "upstreamActivePeer": "132.226.203.38:6001",
  "txDiffusionMode": "local-submit-only",
  "txDiffusionEnabled": true,
  "txDiffusionPeerCount": 0,
  "txDiffusionAcceptedMempoolEvents": 0,
  "txDiffusionInboundAccepted": 0,
  "txDiffusionInboundRejected": 0,
  "txDiffusionInboundIgnored": 0,
  "txDiffusionOutboundForwarded": 0,
  "txDiffusionOutboundSuppressed": 0,
  "txDiffusionServedTxs": 0,
  "txDiffusionServedBytes": 0,
  "txDiffusionInFlightTxs": 0,
  "txDiffusionInFlightBytes": 0,
  "mempoolSize": 0,
  "mempoolBytes": 0
}
```

## Result

The app started successfully, discovered/fell over to a reachable preprod peer,
and continued following the chain. No tx diffusion activity occurred during the
smoke window because the local mempool was empty and `local-submit-only` only
diffuses transactions admitted locally.

The app process was stopped after the smoke check and port `7072` was clear.
