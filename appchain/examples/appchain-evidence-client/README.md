# App-chain evidence client

This non-plugin companion wraps `appchain-client` and the public, no-SPI
`appchain-evidence-contracts` codecs. It provides:

- bounded submission of canonical evidence commands;
- an idempotent notification helper; and
- a proof-aware `evidence/get` lookup which binds both returned state leaves
  to MPF inclusion proofs from the same committed app-chain snapshot.

The client fails closed. It checks the expected chain and state-machine id,
the requested evidence id/version, exact proof keys and values, proof root and
height, and both cryptographic inclusion proofs. If a block commits between
the query and proof reads, it retries the complete query/proof sequence a
small configured number of times. It never retries a malformed or invalid
proof as though it were a normal snapshot race.

```java
AppChainClient transport = AppChainClient.builder("http://localhost:8080/api/v1")
        .chainId("evidence-chain")
        .build();

EvidenceClient evidence = new EvidenceClient(transport, "evidence-chain");
Optional<VerifiedEvidence> result = evidence.queryVerified("batch-2026-07", 0);
```

Version `0` means the latest version. A not-found committed query returns
`Optional.empty()` only after verifying either an MPF exclusion proof for the
deterministic head key, or (for an explicit future version) a head inclusion
proof that commits a lower latest version. Transport, contract, identity, and
proof failures throw
`EvidenceClientException` with a stable error code and sanitized message.

The proof establishes that the evidence leaves are part of the returned state
root. For trust independent of the serving node, compare that root and height
with an authenticated finality certificate or Cardano L1 anchor; the demo
runner performs that additional check.

Credentials remain an `AppChainClient` concern. This module never records,
logs, reflects, or stores them.

Unlike the drop-in registry plugin, this typed SDK depends only on
`appchain-client` and `appchain-evidence-contracts`. The contracts module
then exposes the frozen connector types and evidence-local terminal outcomes
used by `EvidenceRecordV1`; it does not depend on Yano `core-api`. Neither
the client nor its complete runtime dependency closure contains registry
ServiceLoader entries, plugin manifests, core-api, or the Yaci/Netty/Reactor
stack, so adding the SDK to a Yano host cannot activate the evidence state
machine or pull in the node runtime.

## Build

```bash
./gradlew :appchain-evidence-client:check
```
