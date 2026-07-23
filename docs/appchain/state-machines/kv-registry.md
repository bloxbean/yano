# `kv-registry` State Machine

`kv-registry` is Yano's built-in mutable key/value registry with deterministic
first-writer ownership. The member that first creates a key becomes its owner;
only that same member can update or delete it. Every current entry is
replicated, threshold-finalized, and individually provable against the
app-chain state root.

The configured state-machine id is exactly `kv-registry`. A chain id such as
`registry-chain` identifies one independent ledger using that implementation.

## When to use it

Use `kv-registry` when applications need a provable current value and the
authorization rule “first writer owns this key” is sufficient:

- consortium allow/deny lists;
- product, asset, token, or credential metadata;
- DID documents or current document pointers;
- shared configuration owned per record;
- service, issuer, or schema registries; and
- mutable status records where one member is the authority.

Choose a custom or role-aware state machine when ownership must transfer
atomically or under governance, multiple parties must approve updates, values
have domain-specific transition rules, one organization may own many member
keys, or authorization must use a business actor distinct from the app-chain
member.

## Data and ownership model

The machine accepts canonical CBOR commands:

```text
[0, keyBytes, valueBytes]  PUT
[1, keyBytes, emptyBytes]  DELETE
```

The rules are:

- A `PUT` for an absent key creates `[sender, value]`; the authenticated
  envelope sender owns the entry while it exists.
- A `PUT` by the owner replaces the current value.
- A `PUT` by another member is a deterministic no-op.
- A `DELETE` by the owner removes the entry.
- A delete of an absent key or a delete by a non-owner is a no-op.
- After deletion, the next member to PUT that key becomes the new owner.
- Keys and PUT values must be non-empty.

HTTP acceptance and even block finalization do not prove that a command
changed the registry. Read and verify the resulting state entry when the
application needs the deterministic outcome.

## Start the out-of-the-box demo

The default local cluster hosts `registry-chain` with UTF-8 values:

```bash
./yano.sh appchain cluster start 3
```

The launcher provides convenient UTF-8 commands. Create a key through node 1:

```bash
./yano.sh appchain cluster kv registry-chain set supplier-42 active --node 1
```

Node 1 now owns `supplier-42`. This update through node 2 finalizes as a
message but cannot change the entry:

```bash
./yano.sh appchain cluster kv registry-chain set supplier-42 suspended --node 2
```

The owner can update or delete it:

```bash
./yano.sh appchain cluster kv registry-chain set supplier-42 suspended --node 1
./yano.sh appchain cluster kv registry-chain del supplier-42 --node 1
```

## Configuration

Configure a standalone registry with:

```yaml
yano:
  app-chain:
    enabled: true
    chain-id: registry-chain
    state-machine: kv-registry
    machines:
      kv-registry:
        value-format: utf8
```

In multi-chain form:

```yaml
yano:
  app-chain:
    chains[0]:
      chain-id: registry-chain
      state-machine: kv-registry
      membership:
        mode: governed
      machines:
        kv-registry:
          value-format: utf8
```

`value-format` is a deterministic structural constraint:

| Value | Behavior |
|---|---|
| `raw` | Any non-empty byte string; the default |
| `utf8` | Value must be valid UTF-8 |
| `cbor` | Value must contain one bounded, well-formed CBOR item |

A non-conforming PUT is rejected at admission by an honest node and remains a
deterministic no-op during block execution. All members must use the same
format. Changing it for an existing chain changes consensus semantics and
requires a governed profile activation or a new chain.

## Submit through REST

REST accepts the canonical command bytes through `bodyHex`. From the source
checkout's `app/` directory, encode a PUT and DELETE with the tutorial helper:

```bash
TOOL=../docs/appchain/tutorials/tools/stdlib_command.py

PUT_HEX=$(python3 "$TOOL" kv-registry put supplier-42 \
  --value-text active)
DELETE_HEX=$(python3 "$TOOL" kv-registry delete supplier-42)
```

Submit the PUT through node 1 so node 1 becomes the owner:

```bash
curl -sS -X POST \
  http://127.0.0.1:7071/api/v1/app-chain/chains/registry-chain/messages \
  -H 'Content-Type: application/json' \
  -d "{\"topic\":\"registry\",\"bodyHex\":\"$PUT_HEX\"}" | jq .
```

For a raw binary value, use `--value-hex` instead of `--value-text`:

```bash
PUT_HEX=$(python3 "$TOOL" kv-registry put binary-key \
  --value-hex 010203ff)
```

Submit the delete through the same owner member:

```bash
curl -sS -X POST \
  http://127.0.0.1:7071/api/v1/app-chain/chains/registry-chain/messages \
  -H 'Content-Type: application/json' \
  -d "{\"topic\":\"registry\",\"bodyHex\":\"$DELETE_HEX\"}" | jq .
```

The topic is a routing/filtering label and does not create a namespace. The
key bytes themselves identify the registry entry. If two applications need
logical namespaces, use explicit keys such as `suppliers/acme` and
`schemas/order/v2`, or separate chains when membership and operations should
also be isolated.

## Submit from Java

Use the client artifact with the node version:

```groovy
implementation "com.bloxbean.cardano:yano-appchain-client:${yanoVersion}"
```

The client includes the portable no-SPI stock contracts and a typed facade:

```java
import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.client.StdlibAppChainClient;

import java.nio.charset.StandardCharsets;

var ownerClient = AppChainClient.builder("http://127.0.0.1:7071/api/v1")
        .chainId("registry-chain")
        // .apiKey("secret")
        .build();
var registry = new StdlibAppChainClient(ownerClient);

byte[] key = "supplier-42".getBytes(StandardCharsets.UTF_8);
byte[] value = "active".getBytes(StandardCharsets.UTF_8);

var submitted = registry.kvPut(key, value);
System.out.println(submitted.messageId());

// Submit later through the same member identity.
registry.kvDelete(key);
```

The server, not the HTTP caller object, signs the normal REST submission.
Pointing another client at port 7072 therefore uses node 2's member identity
and cannot update a key owned by node 1.

## Read and prove an entry

The physical state key is exactly the registry key bytes. For a UTF-8 key:

```bash
KEY_HEX=$(python3 -c 'print("supplier-42".encode().hex())')

curl -sS \
  "http://127.0.0.1:7070/api/v1/app-chain/chains/registry-chain/proof/$KEY_HEX" \
  | jq .
```

An included value is canonical CBOR:

```text
[ownerPublicKeyBytes, valueBytes]
```

After deletion, the proof endpoint can return an exclusion proof with no
`valueHex`. Keep application keys at most 256 bytes when they must be queried
through the standard proof endpoint.

Verify and decode the proof in Java:

```java
import java.util.HexFormat;

var reader = AppChainClient.builder("http://127.0.0.1:7070/api/v1")
        .chainId("registry-chain")
        .build();
var entry = new StdlibAppChainClient(reader).kvEntry(key).orElseThrow().value();
byte[] owner = entry.owner();
byte[] currentValue = entry.value();

System.out.println("owner=" + HexFormat.of().formatHex(owner));
System.out.println("value=" + new String(currentValue, StandardCharsets.UTF_8));
```

`ProofVerifier.verify` verifies the returned MPF proof against the root in the
same response. For independent verification, obtain the expected root from a
pinned chain profile plus verified finality/anchor evidence rather than
trusting the serving node to nominate the root.

## Application design choices

### Stable keys provide idempotency

Repeated owner PUTs replace one current entry, so a stable domain key such as
`supplier-42` provides natural current-state idempotency. History is still in
the finalized app blocks; the state proof represents only the latest value.

### Ownership is intentionally simple

There is no atomic transfer-owner command, expiry, multi-signature update, or
administrator override. Delete-and-recreate releases the key and lets the next
writer own it, so it is not a safe governed transfer protocol. If richer
ownership is required, define the transition and its authorization in a
custom/composite state machine instead of relying on an off-chain convention.

### Values remain application data

`utf8` and `cbor` validate structure, not a business schema. Version values
explicitly when consumers need stable decoding, for example:

```json
{"schemaVersion":1,"status":"active","country":"SG"}
```

Every member receives the command bytes and may retain history. Encrypt values
before submission when confidentiality is required, and keep encryption-key
management outside deterministic consensus.

## Related documentation

- [Registry and proofs tutorial](../tutorials/02-registry-and-proofs.md)
- [Stock state-machine cookbook](../tutorials/03-stock-state-machines.md)
- [Complete app-chain user guide](../../APP_CHAIN_USER_GUIDE.md)
- [Consensus and state-machine internals](../../APP_CHAIN_CONSENSUS_GUIDE.md)
- [Java app-chain client](../../../appchain/appchain-client/README.md)
