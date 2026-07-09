# Yano App-Chain Client

Java client SDK for applications that talk to a Yano app chain over REST and
SSE.

This module is deliberately light. It does not depend on the Yano runtime or
Yaci networking stack. It provides:

- REST submit/read/status/block/proof operations
- SSE subscription with reconnect and duplicate suppression
- typed submit/subscribe helpers using caller-provided encoders/decoders
- AES-GCM group-body encryption helper
- client-side MPF inclusion proof verification

See also:

- [App-chain user guide](../../docs/APP_CHAIN_USER_GUIDE.md)
- [App-chain tutorial](../../docs/APP_CHAIN_TUTORIAL.md)
- [ADR-005](../../adr/app-layer/005-yano-app-chain-framework.md)
- [ADR-006](../../adr/app-layer/006-appchain-enterprise-extensions-and-zk.md)

## Usage

```java
import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
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

For stronger verification, compare the proof against a state root obtained from
an L1 anchor transaction rather than the same node that served the proof:

```java
boolean verified = ProofVerifier.verify(proof.get(), anchoredStateRootHex);
```

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
