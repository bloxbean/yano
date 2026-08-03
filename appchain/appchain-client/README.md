# Yano App-Chain Client

Java client SDK for applications that talk to a Yano app chain over REST and
SSE.

This module is deliberately light. It does not depend on the Yano runtime or
Yaci networking stack. It provides:

- REST submit/read/status/block/proof operations, including bounded committed-state queries
- SSE subscription with reconnect and duplicate suppression
- typed submit/subscribe helpers using caller-provided encoders/decoders
- AES-GCM group-body encryption helper
- profile-aware MPF/classic-JMT and composed effect-proof verification
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
ProofVerifier.TrustedStateRoot trustedRoot = loadFromIndependentlyVerifiedAnchor(
        "orders-chain", proof.orElseThrow().committedHeight());
boolean verified = proof.isPresent()
        && ProofVerifier.verify(proof.orElseThrow(), trustedRoot);
```

State-proof responses are transport-bounded and bind the key, optional value,
proof wire, root, and `committedHeight` to one atomic committed snapshot.
State keys are limited to 256 bytes; values and proof wires are each limited
to 1 MiB before JSON hex expansion.
`finalizedAtHeight`, when present, is the legacy height at which a message-id
key was included; it is not the proof snapshot height. A missing `valueHex`
with a proof is an exclusion proof and can be checked with
`ProofVerifier.verifyExclusion(...)`.

The trusted object binds chain id, exact profile, genesis id, height, root, and
its acquisition source. Obtain those fields from a locally verified block
chain, a threshold certificate under independently pinned membership, or a
Cardano anchor transaction/datum verified independently of the proof-serving
node:

```java
var trusted = new ProofVerifier.TrustedStateRoot(
        "orders-chain", verifiedAnchor.profile(), verifiedAnchor.genesisIdHex(),
        verifiedAnchor.height(), verifiedAnchor.stateRootHex(),
        ProofVerifier.TrustedRootSource.CARDANO_ANCHOR);
boolean verified = ProofVerifier.verify(proof.orElseThrow(), trusted);
```

When an envelope carries its finalized block header and certificate,
`ProofVerifier.verifyCertified(...)` can authenticate the root directly under
a caller-pinned membership set and threshold. The verifier recomputes the
canonical block hash, verifies distinct Ed25519 signers, binds the exact
commitment identity, and then dispatches the native proof by profile.

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
submits canonical bounded commands and can resolve an independently trusted
root for every returned proof before decoding state:

```java
StdlibAppChainClient stock = new StdlibAppChainClient(client, proof ->
        trustedRootArchive.require(proof.chainId(), proof.profile(),
                proof.genesisIdHex(), proof.committedHeight()));

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
- `ProofVerifier.verifyInternalConsistency(proof)` (and the deprecated
  one-argument `verify`) checks only the proof against the root carried by that
  same envelope. It makes no chain-authenticity claim.
- The release-matched verifier accepts the exact `mpf-blake2b256-v1` and
  `jmt-blake2b256-v1` metadata/codec contracts. The declared Poseidon profile
  remains fail-closed until its Phase 4 dependency is released and pinned.
- CCL `0.8.0-pre5-dev1` is the current development baseline, not a production
  dependency approval.
