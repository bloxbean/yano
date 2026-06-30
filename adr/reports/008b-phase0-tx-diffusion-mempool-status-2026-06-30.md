# ADR-008B Phase 0 Implementation Report

Date: 2026-06-30

## Scope

- Added `yano.tx.mempool.*` and `yano.tx.diffusion.*` configuration keys.
- Added byte accounting and byte-cap eviction to the existing in-memory mempool.
- Added mempool and tx diffusion status fields to `NodeStatus`.
- Wired Quarkus config into runtime globals.
- Mapped configured `yano.tx.diffusion.mode` to the effective legacy
  `yano.upstream.tx.forwarding` policy for current local-submit forwarding.
- Added explicit tx config to `app/src/main/resources/application.yml` and
  `app/config/application.yml`.

Full pull-based peer tx-id/body diffusion is not part of this phase.

## Verification

- `./gradlew :runtime:test --tests com.bloxbean.cardano.yano.runtime.tx.TxSubsystemTest`
- `./gradlew :app:test --tests com.bloxbean.cardano.yano.app.YanoProducerTest`
- `./gradlew :app:test`
- `./gradlew :app:quarkusBuild`
- `./gradlew test`
- `git diff --check`

All commands completed successfully.

## Manual Preprod Run

Started from `app/` with `app/config/application.yml` using:

```bash
java --add-opens java.base/java.lang=ALL-UNNAMED -jar build/yano.jar
```

The process is running under launchd as PID `43141`, listening on:

- REST: `7072`
- N2N server: `13338`

Observed status:

- `upstreamMode`: `p2p-relay`
- `upstreamHotPeerCount`: `3`
- `upstreamObserverPeerCount`: `2`
- `txDiffusionMode`: `local-submit-only`
- `upstreamTxForwarding`: `active-selected`
- `mempoolSize`: `0`
- `mempoolBytes`: `0`
- `mempoolMaxTxs`: `10000`
- `mempoolMaxBytes`: `134217728`
- `mempoolTtlSeconds`: `10800`
- `mempoolAccepting`: `true`
- `mempoolValidationAvailable`: `true`
- `mempoolEvaluationAvailable`: `true`

The node reached `STEADY_STATE` and remained connected to preprod peers.

## Notes

- No valid preprod transaction was submitted during this run, so the mempool
  remained empty. This verifies config, status, and runtime health, but not
  actual local transaction forwarding.
- Full relay-style transaction diffusion remains in ADR-008B Phases 1-4.
