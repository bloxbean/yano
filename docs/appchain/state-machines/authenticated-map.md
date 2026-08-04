# `authenticated-map` State Machine

`authenticated-map` is Yano's built-in, proof-oriented registry for multiple
named collections. Each collection has its own authorization policy, key/value
bounds, value encoding, and optional value validator. Every active entry or
revocation tombstone is threshold-finalized and individually provable against
the app-chain state root.

It is a general authenticated data structure, not a relational database or a
document store. It does not provide secondary indexes, joins, range queries,
confidentiality, or cross-entry business rules.

## When to use it

Use `authenticated-map` when an application needs one or more independently
configured key/value namespaces with verifiable current state:

- product, asset, credential, or configuration registries;
- owner-controlled records with controller transfer and revocation;
- member-maintained canonical event or reference data;
- opaque hashes, attachments, encrypted values, or application-native bytes;
- atomic updates to several distinct keys; and
- records that need deterministic schema or application-specific validation.

Use `kv-registry` when one owner-guarded key/value namespace is enough. Use
`ordered-log` when append order matters more than current keyed state. Write a
composite or custom state machine when a transition must inspect several
existing entries, maintain secondary indexes, enforce relationships between
collections, emit effects, or implement a larger workflow.

## Collection and entry model

Genesis defines up to 64 collections. The important per-collection options
are:

| Option | Meaning |
|---|---|
| `id` | Stable lowercase collection identifier |
| `authorization` | `open`, `owner`, or `member` mutation policy |
| `restoreAllowed` | Whether a revoked tombstone may become active again |
| `maxKeyBytes` | Maximum application-key size |
| `maxValueBytes` | Maximum value size |
| `valueEncoding` | `opaque` or `canonical-cbor` |
| `validator` | Optional genesis-bound schema or plugin descriptor id |

`open` permits any authenticated sender. `owner` records the first successful
creator as the 32-byte controller and permits later mutations only from that
controller. `member` requires the sender to be an active app-chain member at
the finalized height. Only an `owner` collection supports controller transfer.

An entry contains status (`ACTIVE` or `REVOKED`), revision, optional
controller, value, logical value hash, creation height, and last-mutation
height. Revocation retains a tombstone and the last logical value hash, but
removes the value bytes. Absence and revocation are therefore distinct and
provable states.

The mutation operations are:

| Operation | Behavior |
|---|---|
| `PUT` | Create an absent entry or replace an active value |
| `PUT_IF_ABSENT` | Create only when no entry or tombstone exists |
| `COMPARE_AND_SET` | Replace an active value after revision and/or value-hash checks |
| `TRANSFER_CONTROLLER` | Change an active owner collection's controller |
| `REVOKE` | Replace an active entry with a tombstone |
| `RESTORE` | Restore a tombstone when the collection permits it |

A command contains either one mutation or a bounded batch. A batch cannot
touch the same collection/key twice and is applied atomically: if any mutation
fails, none of its entry changes are written. For a command that reaches state
application, a retained receipt records the applied results or one
deterministic rejection code. Candidate-validation failures are filtered
before finalization and therefore have no receipt.

## Configuration

The recommended path is the authenticated-map project recipe. Bootstrap member
keys are required because membership, consensus settings, collection rules,
validators, and the state-commitment profile are all committed by genesis:

```bash
./yano.sh appchain init --non-interactive \
  --recipe authenticated-map --network preprod --members 3 \
  --member-key <member-0-64-hex> \
  --member-key <member-1-64-hex> \
  --member-key <member-2-64-hex> \
  --name product-registry --chain-id product-registry \
  --output product-registry
```

Configure `spec.chains[0].authenticatedMap` in the generated
`appchain.yaml`. This example combines an opaque collection, canonical CBOR,
and a declarative product schema:

```yaml
authenticatedMap:
  profile: mpf-blake2b256-v1
  anchorPolicyCommitment: "0000000000000000000000000000000000000000000000000000000000000000"
  maxBatchItems: 32
  maxBatchBytes: 65536
  collections:
    - id: attachments
      authorization: owner
      restoreAllowed: false
      maxKeyBytes: 64
      maxValueBytes: 1048576
      valueEncoding: opaque
    - id: canonical-events
      authorization: member
      restoreAllowed: false
      maxKeyBytes: 64
      maxValueBytes: 16384
      valueEncoding: canonical-cbor
    - id: products
      authorization: owner
      restoreAllowed: false
      maxKeyBytes: 64
      maxValueBytes: 4096
      valueEncoding: canonical-cbor
      validator: product-v1
  schemas:
    - id: product-v1
      root: product
      source: |
        product = {
          sku: tstr .size (1..32),
          quantity: uint .le 1000000,
          status: "active" / "held" / "retired",
          ? note: tstr .size (0..256)
        }
```

Then render and verify the exact release:

```bash
./yano.sh appchain render product-registry
./yano.sh appchain doctor product-registry --distribution /path/to/yano-release.zip
```

The renderer emits the canonical genesis plus the three matching runtime
settings: `state.commitment-profile`, `state.format-fingerprint`, and
`state.genesis-id`. Do not hand-edit those values. Every member must use the
same generated chain configuration and validator artifacts.

`anchorPolicyCommitment` is consensus identity. The all-zero value is an
explicit development/no-policy placeholder; replace it with the reviewed
32-byte commitment before creating an anchored production chain generation.

The available commitment profiles are `mpf-blake2b256-v1` and
`jmt-blake2b256-v1`. The Poseidon JMT profile is reserved until its pinned
implementation is released.

## Value encoding and validation

Validation is optional and the default is `opaque` with no validator.

- `opaque` accepts any bounded byte sequence. Use it for encrypted values,
  hashes, attachments, or an application format already made deterministic.
- `canonical-cbor` requires exactly one bounded deterministic CBOR item. It
  accepts integers, byte/text strings, definite-length arrays, and
  definite-length maps, subject to canonical ordering and bounds. It rejects
  indefinite-length values, trailing bytes, non-minimal encodings, duplicate
  or incorrectly ordered map keys, invalid UTF-8, and unsupported tags or
  simple values.
- A declarative schema adds exact record-shape and field constraints using the
  supported `cddl-yano-subset-v1`; nodes execute its canonical compiled IR.
- A validator plugin adds a deterministic application-specific predicate over
  `(collectionId, applicationKey, value)`.

These rules are consensus-bound. Changing an encoding, schema, plugin,
parameters, or pinned plugin artifact creates a different genesis identity and
requires a new chain generation with an explicit migration plan.

For detailed schema syntax, offline preflight, validator descriptors, error
codes, and the plugin trust model, see
[Authenticated-map value validation](authenticated-map-validation.md).

## Run the showcase

The light showcase includes `authenticated-map-chain` with four collections:

| Collection | Authorization | Encoding and validation |
|---|---|---|
| `attachments` | owner | opaque bytes |
| `canonical-events` | member | canonical-CBOR array |
| `products` | owner | canonical-CBOR map plus `product-v1` schema |
| `gtins` | owner | canonical-CBOR text plus first-party `gs1-gtin-v1` plugin |

Start it and run the dedicated scenario:

```bash
./showcase.sh quickstart --profile light --nodes 3 --instance authmap-demo
./demos/submit-authenticated-map.sh authmap-demo
```

The scenario writes one value to every collection, demonstrates pre-finality
rejection by the product schema and GTIN plugin, performs a root-attested point
query, and retrieves the native MPF proof. The launcher generates the
authenticated-map genesis from the actual bootstrap member keys and the
validator bundle digest in that exact Yano release.

## Submit through REST

The topic is `authenticated-map.command.v1`. Use the packaged showcase codec
to create canonical command and value bytes; curl still sends the normal public
HTTP request:

```bash
BASE=http://127.0.0.1:7070
CODEC=./tools/showcase_codec.py

VALUE_HEX="$(python3 "$CODEC" authmap-value product sku-42 5 active \
  'demo product')"
BODY_HEX="$(python3 "$CODEC" authmap put products sku-42 "$VALUE_HEX")"

RESPONSE="$(curl -fsS -X POST \
  "$BASE/api/v1/app-chain/chains/authenticated-map-chain/messages" \
  -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg body "$BODY_HEX" \
    '{topic:"authenticated-map.command.v1",bodyHex:$body}')")"
MESSAGE_ID="$(printf '%s' "$RESPONSE" | jq -r .messageId)"
```

HTTP `202` proves only ingress/pool acceptance, not state-machine acceptance or
a successful finalized mutation. Encoding, schema, and plugin validation runs
again when forming the candidate block; an invalid message can therefore
receive `202`, then be filtered before finalization without changing state or
creating an authenticated receipt. Use local preflight for immediate advisory
feedback, and always wait for finality before treating a mutation as applied.
The generic query endpoint accepts the canonical point-query bytes:

```bash
until curl -fsS \
  "$BASE/api/v1/app-chain/chains/authenticated-map-chain/messages/$MESSAGE_ID" \
  >/dev/null 2>&1; do sleep 1; done

RECEIPT_HEX="$(python3 "$CODEC" authmap-receipt "$MESSAGE_ID")"
curl -fsS -X POST \
  "$BASE/api/v1/app-chain/chains/authenticated-map-chain/query/authenticated-map/receipt-v1" \
  -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg params "$RECEIPT_HEX" '{paramsHex:$params}')" | jq .

QUERY_HEX="$(python3 "$CODEC" authmap query products sku-42)"
curl -fsS -X POST \
  "$BASE/api/v1/app-chain/chains/authenticated-map-chain/query/authenticated-map/entry-v1" \
  -H 'Content-Type: application/json' \
  -d "$(jq -nc --arg params "$QUERY_HEX" '{paramsHex:$params}')" | jq .
```

For a native inclusion proof, derive the physical state key:

```bash
STATE_KEY_HEX="$(python3 "$CODEC" authmap state-key products sku-42)"
curl -fsS \
  "$BASE/api/v1/app-chain/chains/authenticated-map-chain/proof/$STATE_KEY_HEX" \
  | jq .
```

Verify the proof locally and bind its root to an independently trusted finality
certificate or Cardano anchor at an audit boundary.

## Submit from Java

Use `appchain-stdlib-contracts` for the portable wire contract and
`appchain-client` for submission, queries, and proof verification:

```java
AppChainClient raw = AppChainClient.builder("http://127.0.0.1:7070/api/v1")
        .chainId("product-registry")
        .apiKey(System.getenv("YANO_API_KEY"))
        .build();
StdlibAppChainClient map = new StdlibAppChainClient(raw);

byte[] key = "sku-42".getBytes(StandardCharsets.UTF_8);
byte[] value = productCbor(); // one canonical CBOR value matching product-v1

var mutation = AuthenticatedMapContract.Mutation.put("products", key, value);
var submitted = map.authenticatedMapMutate(mutation);

var point = map.authenticatedMapEntry("products", key);
var proof = map.authenticatedMapProof("products", key);
var receipt = map.authenticatedMapReceipt(
        HexFormat.of().parseHex(submitted.messageId()));
```

For race-safe writes, use `compareAndSet` with the revision and/or logical value
hash from the last trusted entry. Use `authenticatedMapBatch` for an atomic
list of distinct collection/key mutations. Application-side preflight can
reuse the exact genesis encoding/schema rules, but authoritative validation
still occurs independently on every node.

## Attach a custom validator format

An application can enforce its own deterministic value format without adding
a new `valueEncoding` name:

- use `opaque` plus a plugin when the plugin owns all format parsing; or
- use `canonical-cbor` plus a plugin when canonical CBOR should be enforced
  before the application rule.

Implement `AuthenticatedMapValueValidatorFactory` from `core-api`, register it
through `ServiceLoader` and a Yano plugin manifest, and return only `ACCEPT` or
`REJECT` from a total, deterministic validator. The plugin descriptor in
genesis pins the provider id, SPI contract version, canonical parameters, and
the exact `ARTIFACT_CLOSURE` SHA-256. The bundle must also be present in the
runtime catalog and explicitly allow-listed on every node.

Validator plugins are trusted in-process consensus code, not sandboxed uploads.
They must not depend on filesystem, network, clock, randomness, locale,
environment, mutable global state, or node-local configuration. If a rule must
read app-chain state or relate several entries, it belongs in a custom or
composite state machine instead of a value validator.

The light showcase's `gs1-gtin-v1` validator is a working reference for this
extension path. Attaching a different plugin to an existing chain is not a hot
configuration change; it creates a new chain generation.

## Operational boundaries

Collection definitions, validation rules, maximum batch limits, consensus
profile, bootstrap membership digest, and anchor-policy commitment are all
chain-generation identity. Review and archive the rendered genesis before
launch, keep plugin artifacts byte-identical across nodes, and fail deployment
when a digest or provider is unavailable.

Authenticated state proves which bytes finalized under those rules. It does
not prove that a real-world assertion is true, make public values confidential,
or preserve old command bodies forever. Archive application evidence
separately when historical availability is required.
