# `doc-trail` State Machine

`doc-trail` is Yano's built-in append-only trail per business entity. Each
command appends an application-level document or event hash to one entity's
running chained head. The current count and head are replicated,
threshold-finalized, and individually provable against the app-chain state
root, while the documents themselves remain outside the chain.

The configured state-machine id is exactly `doc-trail`. An entity id can be a
product id, case id, shipment id, credential id, or another stable application
identifier.

## When to use it

Use `doc-trail` when several members need a tamper-evident ordered history for
each entity without replicating full documents:

- digital product passports and provenance;
- supply-chain or shipment histories;
- compliance and audit case files;
- document/evidence revision trails;
- certificate and credential histories; and
- a provable current trail head linked to off-chain content.

Choose `ordered-log` when only one global event order is needed. Choose
`kv-registry` when the main requirement is a mutable current value. Use a
custom or composite state machine when appends require ownership, roles,
approval gates, uniqueness, or domain-specific transition validation.

## Trail model

The machine accepts one canonical CBOR command:

```text
[entityId, entryHashBytes, optionalReference]
```

For each entity, it stores:

```text
UTF8("e/" + entityId) -> cbor([count, headHash])
```

The genesis head is 32 zero bytes. Each finalized append computes:

```text
head[n] = Blake2b-256(head[n-1] || entryHash[n] || senderPublicKey[n])
```

This commits the order, entry hash, and authenticated member author. Repeating
the same entry is another append and changes the count/head; the stock machine
does not deduplicate or impose business rules.

The optional reference is a locator such as an object id, URL, IPFS CID, or
document number. It is committed through the finalized message history, while
the compact per-entity chained head uses only the entry hash and author. Treat
`entryHash` as the content-integrity identity; do not rely on the reference
string alone to authenticate the document. Archive finalized command bodies
when node retention may remove them.

## Configuration

`doc-trail` has no machine-specific settings:

```yaml
yano:
  app-chain:
    enabled: true
    chain-id: document-trail-chain
    state-machine: doc-trail
```

In a multi-chain deployment:

```yaml
yano:
  app-chain:
    chains[0]:
      chain-id: document-trail-chain
      state-machine: doc-trail
      membership:
        mode: governed
      block:
        interval-ms: 1000
```

The default local demo does not include a standalone `doc-trail` chain. Add a
new chain to the shared configuration before starting a fresh cluster, or use
the app-chain configuration generator for a new project. Do not reinterpret
retained data from another state machine as `doc-trail` state.

## Hash and submit a document through REST

The application selects the document hash algorithm represented by
`entryHashBytes`. SHA-256 is a common application choice; the running trail
head itself always uses Blake2b-256.

From the source checkout's `app/` directory, create a sample document hash and
encode the command with the tutorial helper:

```bash
TOOL=../docs/appchain/tutorials/tools/stdlib_command.py
ENTITY=product-42

ENTRY_HASH=$(python3 -c \
  'import hashlib; print(hashlib.sha256(b"quality certificate v1").hexdigest())')

APPEND_HEX=$(python3 "$TOOL" doc-trail "$ENTITY" "$ENTRY_HASH" \
  --reference 's3://evidence/product-42/certificate-v1.pdf')
```

Submit the command through a member:

```bash
curl -sS -X POST \
  http://127.0.0.1:7071/api/v1/app-chain/chains/document-trail-chain/messages \
  -H 'Content-Type: application/json' \
  -d "{\"topic\":\"documents\",\"bodyHex\":\"$APPEND_HEX\"}" | jq .
```

A successful HTTP `202` accepts the message envelope. Wait for finalization
before treating the trail as advanced. The topic is only a routing/filtering
label; `entityId` selects the trail.

Append another revision by hashing its exact bytes and submitting a new
command with the same entity id:

```bash
ENTRY_HASH=$(python3 -c \
  'import hashlib; print(hashlib.sha256(b"quality certificate v2").hexdigest())')

APPEND_HEX=$(python3 "$TOOL" doc-trail "$ENTITY" "$ENTRY_HASH" \
  --reference 's3://evidence/product-42/certificate-v2.pdf')

curl -sS -X POST \
  http://127.0.0.1:7072/api/v1/app-chain/chains/document-trail-chain/messages \
  -H 'Content-Type: application/json' \
  -d "{\"topic\":\"documents\",\"bodyHex\":\"$APPEND_HEX\"}" | jq .
```

Because the two submissions use different ingress members, their author keys
also differ and are incorporated into the trail head.

## Submit from Java

Use the client and standard-library artifacts with the node version:

```groovy
implementation "com.bloxbean.cardano:yano-appchain-client:${yanoVersion}"
implementation "com.bloxbean.cardano:yano-appchain-stdlib:${yanoVersion}"
```

Hash the exact application bytes, encode the stock command, and submit it:

```java
import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.stdlib.DocTrailStateMachine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

var client = AppChainClient.builder("http://127.0.0.1:7071/api/v1")
        .chainId("document-trail-chain")
        // .apiKey("secret")
        .build();

byte[] document = "quality certificate v1".getBytes(StandardCharsets.UTF_8);
byte[] entryHash = MessageDigest.getInstance("SHA-256").digest(document);

byte[] command = DocTrailStateMachine.append(
        "product-42",
        entryHash,
        "s3://evidence/product-42/certificate-v1.pdf");

var submitted = client.submit("documents", command);
System.out.println(submitted.messageId());
```

Store the exact hash algorithm/version with the off-chain record or in a
versioned application envelope. `doc-trail` treats `entryHash` as opaque
non-empty bytes and cannot infer whether it represents SHA-256, Blake2b, a
Merkle root, or another digest.

## Read and prove a trail head

The physical state key is UTF-8 `e/<entityId>`:

```bash
STATE_KEY_HEX=$(python3 -c 'print("e/product-42".encode().hex())')

curl -sS \
  "http://127.0.0.1:7070/api/v1/app-chain/chains/document-trail-chain/proof/$STATE_KEY_HEX" \
  | jq .
```

The response's `valueHex` is canonical CBOR `[count, headHash]`. It proves the
current trail summary, not the availability of the referenced documents.
Verify and decode it in Java:

```java
import com.bloxbean.cardano.yano.appchain.client.ProofVerifier;

import java.util.HexFormat;

var proof = client.proof(DocTrailStateMachine.entityKey("product-42"))
        .orElseThrow();
if (!ProofVerifier.verify(proof) || proof.valueHex() == null) {
    throw new IllegalStateException("trail state is absent or unverified");
}

var trail = DocTrailStateMachine.decodeEntry(
        HexFormat.of().parseHex(proof.valueHex()));

System.out.println("entries=" + trail.count());
System.out.println("head=" + HexFormat.of().formatHex(trail.headHash()));
```

For independent verification, reconstruct the ordered `(entryHash, author)`
sequence from finalized block history, recompute it with
`DocTrailStateMachine.computeHead(...)`, and compare it with the proven head.
Also verify the MPF proof against a state root obtained from independently
trusted finality or Cardano anchor evidence.

Useful history endpoints, relative to `/api/v1/app-chain`, include:

```text
GET /chains/{chainId}/blocks?from=1&limit=100
GET /chains/{chainId}/messages/by-topic/documents?fromHeight=0&limit=100
GET /chains/{chainId}/messages/{messageId}
GET /chains/{chainId}/status
```

The app-chain history proves which command bytes were finalized. The external
document must still be fetched, hashed with the declared application
algorithm, and matched to `entryHash`.

## Design and operational guidance

### Keep documents outside consensus

Submit hashes and bounded references rather than PDFs, images, or large JSON
documents. Every command is replicated to every member and may be retained or
exported. An object store, IPFS, or controlled document repository should own
the content lifecycle.

### Define canonical document bytes

Two semantically identical JSON documents can hash differently because of
whitespace, key order, or encoding. Specify canonicalization before hashing,
or hash immutable binary artifacts exactly as stored.

### Separate integrity from availability

The trail proves an ordered digest commitment. It does not guarantee that an
S3 object, URL, or IPFS object remains available. Operate retention,
replication, access control, and evidence export separately.

### Add policy through composition

The stock machine lets every admitted member append to every entity. If an
append must first be approved, combine approval and document-trail behavior in
a committed composite workflow. If only an entity owner or domain role may
append, implement that rule in a state machine; an off-chain check alone is
not consensus enforcement.

## Related documentation

- [Stock state-machine cookbook](../tutorials/03-stock-state-machines.md)
- [Evidence publication tutorial](../tutorials/04-evidence-publication.md)
- [Plugins and composites](../tutorials/08-plugins-and-composites.md)
- [Complete app-chain user guide](../../APP_CHAIN_USER_GUIDE.md)
- [Consensus and state-machine internals](../../APP_CHAIN_CONSENSUS_GUIDE.md)
- [Java app-chain client](../../../appchain/appchain-client/README.md)
