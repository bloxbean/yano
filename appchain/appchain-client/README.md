# Yano App-Chain Client

Java client SDK for applications that talk to a Yano app chain over REST and
SSE.

This module is deliberately light. It does not depend on the Yano runtime or
Yaci networking stack. It provides:

- REST submit/read/status/block/proof operations, including bounded committed-state queries
- SSE subscription with reconnect and duplicate suppression
- typed submit/subscribe helpers using caller-provided encoders/decoders
- AES-GCM group-body encryption helper
- client-side MPF and composed effect-proof verification
- typed stock-machine commands and verified state decoding through
  `StdlibAppChainClient`

See also:

- [App-chain user guide](../../docs/APP_CHAIN_USER_GUIDE.md)
- [App-chain tutorial](../../docs/APP_CHAIN_TUTORIAL.md)
- [ADR-005](../../adr/app-layer/005-yano-app-chain-framework.md)
- [ADR-006](../../adr/app-layer/006-appchain-enterprise-extensions-and-zk.md)

## Usage

```java
import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.client.EffectProofVerifier;
import com.bloxbean.cardano.yano.appchain.client.Hex;
import com.bloxbean.cardano.yano.appchain.client.ProofVerifier;

AppChainClient client = AppChainClient.builder("http://localhost:7070/api/v1")
        .chainId("orders-chain")       // optional for single-chain nodes
        .apiKey("secret")              // optional, when REST auth is enabled
        .build();

var submitted = client.submitText("orders", "order-1");
var tip = client.tip();

var proof = client.proof(Hex.decode(submitted.messageId()));
boolean verified = proof.isPresent() && ProofVerifier.verify(proof.get());
```

State-proof responses are transport-bounded and bind the key, optional value,
proof wire, root, and `committedHeight` to one atomic committed snapshot.
State keys are limited to 256 bytes; values and proof wires are each limited
to 1 MiB before JSON hex expansion.
`finalizedAtHeight`, when present, is the legacy height at which a message-id
key was included; it is not the proof snapshot height. A missing `valueHex`
with a proof is an exclusion proof and can be checked with
`ProofVerifier.verifyExclusion(...)`.

For stronger verification, compare the proof against a state root obtained from
an L1 anchor transaction rather than the same node that served the proof:

```java
boolean verified = ProofVerifier.verify(proof.get(), anchoredStateRootHex);
```

Effect emissions have a composed proof from canonical record bytes through
the block's ordered effects root into that block's historical state root:

```java
var lookup = client.effectProof(42, 0);
boolean verified = lookup.available() && EffectProofVerifier.verifyFor(
        lookup.proof(), independentlyTrustedStateRootAtHeight42,
        "orders-chain", 42, 0);
```

The lookup distinguishes `NOT_FOUND` from `PRUNED`; archive a proof before the
node's effect-record retention horizon when long-lived evidence is required.
An L1 anchor root can be compared directly only when it anchors height 42;
for a later anchor, authenticate block 42's certificate/hash-chain link to the
anchored descendant separately.

## Committed-state queries

Generic state-machine queries keep their request and result codec-neutral while
preserving the exact committed snapshot metadata returned by the node:

```java
record PassportRequest(String assetId) {}
record PassportView(String assetId, String status) {}

CborCodec<PassportRequest> requests = CborCodec.of(PassportRequest.class);
CborCodec<PassportView> views = CborCodec.of(PassportView.class);

var result = client.query("passport/read",
        requests.encode(new PassportRequest("asset-1")));
PassportView view = views.decode(result.payload());

System.out.println(result.committedHeight() + " " + Hex.encode(result.stateRoot()));
```

The path must use the canonical ADR-011.3 relative-path grammar. Parameters are
limited to 64 KiB and results to 1 MiB. `stateRoot()` and `payload()` return
defensive copies. The root identifies the snapshot used by the query; the
opaque payload is not itself a Merkle proof. Committed-state queries are always
chain-scoped, so the client builder must set `chainId`.

Submit, committed-query, and state-proof responses use strict bounded readers;
provider response bodies and API keys are never reflected in their errors.

## SSE

```java
AutoCloseable subscription = client.subscribe(-1, "orders", message -> {
    System.out.println(message.height() + ":" + message.index()
            + " " + message.messageId());
});

// later
subscription.close();
```

`fromHeight = -1` starts live-only. Any non-negative height replays from that
height and then follows new finalized blocks.

## Typed Payloads

The SDK stays independent of `core-api`, so typed methods accept functions
rather than framework codec types.

```java
record Order(String id, long amount) {}

CborCodec<Order> codec = CborCodec.of(Order.class);

client.submitTyped("orders", new Order("o-1", 100), codec::encode);
client.subscribeTyped(1, "orders", codec::decode, (order, envelope) -> {
    System.out.println(order.id());
});
```

## Stock state-machine contracts

`StdlibAppChainClient` uses the no-SPI `appchain-stdlib-contracts` artifact. It
submits canonical bounded commands and decodes only state values whose MPF
proof verifies locally:

```java
StdlibAppChainClient stock = new StdlibAppChainClient(client);

stock.kvPut("supplier-42".getBytes(UTF_8), "active".getBytes(UTF_8));
stock.propose("release-17", payload, 2, 0);
stock.mint("customer-42", BigInteger.valueOf(100));
stock.appendDocument("case-9", documentHash, "ipfs://bafy...");

var balance = stock.balance("customer-42");
```

The convenience topics are versioned (`*.command.v1`), but topics are routing
labels rather than state-machine identities. A successful submit proves
acceptance only; read the verified state after finalization for the outcome.

## Test

```bash
./gradlew :appchain-client:test
```

## Notes

- REST base URL must include the Yano API prefix, for example
  `http://localhost:7070/api/v1`.
- Use `chainId(...)` when a node hosts multiple app chains.
- `ProofVerifier.verify(proof)` only checks the proof against the state root in
  the response. For audit-grade verification, use an independently obtained
  root from the L1 anchor.
