# ADR-NET-008H Implementation Report: Phase 5

## Date

2026-07-01

## Scope

Implemented ADR-NET-008H Phase 5: transaction diffusion boundary extraction.

The protocol-neutral transaction diffusion state now lives in `:p2p`, while
runtime remains the owner of transaction admission, validation listeners,
mempool mutation, and event publication.

## Module Shape

New package:

```text
com.bloxbean.cardano.yano.p2p.tx.diffusion
```

Moved from runtime to p2p:

- `DefaultTxDiffusion`;
- `DisabledTxDiffusion`;
- `PeerClass`;
- `PeerTxState`;
- `TxBodyIngressResult`;
- `TxBodyServeResult`;
- `TxDiffusion`;
- `TxDiffusionMode`;
- `TxDiffusionStats`;
- `TxIdAndSize`;
- `TxRequestPlan`.

Added p2p ports:

- `TxCatalog`: read-only mempool/catalogue access;
- `TxHashProvider`: transaction hash calculation;
- `TxAdmissionPort`: transaction admission callback.

## Runtime Integration

`TxSubsystem` now constructs `DefaultTxDiffusion` with:

- a runtime-backed `TxCatalog` adapter over the existing `MemPool`;
- `TransactionUtil::getTxHash` as the hash provider;
- existing tx-diffusion limits and mode configuration.

`YaciTxSubmissionHandler` now adapts runtime `TransactionAdmission` to the
`TxAdmissionPort` used by p2p. Diffused transaction bodies still enter the same
runtime admission path as REST/local submission:

```text
TxDiffusion -> TxAdmissionPort -> TransactionAdmission -> TransactionValidateEvent -> MemPoolTransactionReceivedEvent
```

Validation policy remains runtime-owned and plugin/listener driven.

## Test Changes

Moved `DefaultTxDiffusionTest` to `:p2p` and removed its dependency on runtime
`DefaultMemPool`. The test now uses a small fake `TxCatalog`, which keeps
`p2p` free of runtime imports and lets the ArchUnit boundary guard enforce the
module rule.

Updated runtime tests that instantiate `DefaultTxDiffusion` to provide the new
ports explicitly.

## Verification

Phase-level verification:

```text
./gradlew :p2p:test
./gradlew :runtime:compileJava :runtime:compileTestJava
./gradlew :runtime:test --tests 'com.bloxbean.cardano.yano.runtime.handlers.YaciTxSubmissionHandlerTest' --tests 'com.bloxbean.cardano.yano.runtime.sync.SyncSubsystemTest' --tests 'com.bloxbean.cardano.yano.runtime.tx.TxSubsystemTest'
```

Final verification:

```text
./gradlew :p2p:test :runtime:test
./gradlew :app:quarkusBuild
```

All commands completed successfully.

## Live Preprod Smoke

Started the built app from `app/` with `app/config/application.yml`:

```text
JAVA_TOOL_OPTIONS='--add-opens=java.base/java.lang=ALL-UNNAMED' ./start.sh
```

The smoke used the existing preprod chain state
`app/chainstate-preprod-static-multi` and the local Haskell
`cardano-node` setup in `/Users/satya/work/cardano-node/preprod`.
The Haskell topology was already configured with a local root pointing at
Yano:

```text
127.0.0.1:13338
```

Observed Yano status from `GET /api/v1/node/status`:

- `inSync=true`;
- `statusMessage="Node is running (phase: STEADY_STATE) [gap: 0 blocks] ..."`;
- `upstreamMode="p2p-relay"`;
- `upstreamActivePeer="relay.preprod.staging.wingriders.com:3001"`;
- `relayInboundConnectionCount=1`;
- `relayOutboundConnectionCount=3`;
- `relayEstablishedConnectionCount=4`;
- `relayRejectedInboundConnections=0`;
- `txDiffusionMode="all-hot"`;
- `txDiffusionEnabled=true`;
- `mempoolSize=0`.

The smoke also showed Yano serving the local Haskell node over inbound N2N:

- Yaci `NodeServer` accepted a client from `127.0.0.1:32000`;
- the Haskell node requested historical block ranges through Yano's
  `BlockFetchServerAgent`;
- after Yano reached tip, `ChainSyncServerAgent` notified the Haskell node of
  new blocks and the Haskell node fetched those blocks from Yano.

No transaction was submitted during this smoke window, so tx-diffusion counters
remained at zero. This verifies that the p2p-extracted server-side connection,
peer-sharing/block-serving wiring, and runtime tx-diffusion status wiring start
and operate correctly with a real preprod Haskell downstream. It does not add a
new live transaction-body diffusion assertion beyond the existing automated
tests and earlier manual tx-submission verification.

## Review Notes

- `:p2p` still has no production dependency on `runtime`; the ArchUnit guard
  passed after tx diffusion moved.
- Runtime configuration keys and runtime tx behavior did not change.
- Direct downstream tx serving remains implemented by the existing Yaci
  TxSubmission integration; this phase only moved Yano's diffusion state and
  policy out of runtime.
- Chain-selection and candidate-header fan-in remain in runtime pending a
  separate consensus ADR.
