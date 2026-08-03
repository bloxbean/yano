# Authenticated-map value validation

The `authenticated-map` state machine is a proof-oriented, multi-collection
registry. Each collection independently chooses its authorization policy, key
and value bounds, value encoding, and optional validator. These choices are
compiled into canonical genesis and are identical on every member.

The safe default is intentionally simple:

| Setting | Default | Effect |
|---|---|---|
| `valueEncoding` | `opaque` | Accept any byte string within the value bound |
| `validator` | absent | Perform no schema or plugin validation |
| `authorization` | `open` | Apply the authenticated-map open-write policy |
| `restoreAllowed` | `false` | Do not restore revoked entries |

Validation is optional. An opaque collection without a validator preserves the
original authenticated-map behavior. Opting into a schema or plugin changes
consensus validity and therefore changes `genesisId`.

## Create a project

Authenticated-map genesis commits the initial membership. Supply every public
member key while initializing; placeholder keys are rejected because they would
produce a different chain identity.

```bash
./yano.sh appchain init --non-interactive \
  --recipe authenticated-map --network preprod --members 3 \
  --member-key <member-0-64-hex> \
  --member-key <member-1-64-hex> \
  --member-key <member-2-64-hex> \
  --name product-registry --chain-id product-registry \
  --output product-registry
```

The generated project starts with one open, opaque `records` collection. Edit
`spec.chains[0].authenticatedMap` in `appchain.yaml`, then render again. This
example adds an opaque collection, a canonical-CBOR collection, and a
schema-bound collection:

```yaml
authenticatedMap:
  profile: mpf-blake2b256-v1
  # All-zero is the initializer's explicit development/no-policy placeholder.
  # Replace it with the reviewed 32-byte commitment before an anchored chain
  # generation is created.
  anchorPolicyCommitment: "0000000000000000000000000000000000000000000000000000000000000000"
  maxBatchItems: 128
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
      maxKeyBytes: 64
      maxValueBytes: 16384
      valueEncoding: canonical-cbor
    - id: products
      authorization: owner
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

Run the renderer and doctor after every edit:

```bash
./yano.sh appchain render product-registry
./yano.sh appchain doctor product-registry --distribution /path/to/yano-release.zip
```

Doctor reports `authenticated-map-schema-encoding` explicitly. A collection
that references a schema must use `canonical-cbor`; `opaque` plus a schema is
rejected before genesis is emitted.

The generated outputs include:

- `config/shared-consensus.yaml`, containing the exact genesis and state
  identity settings used by nodes;
- `config/authenticated-map-genesis.hex`, a portable canonical genesis input
  for offline tooling; and
- `docs/VALUE_VALIDATION.md`, project-specific validation commands.

## Encoding and schema options

`opaque` performs only the declared byte-length check. Use it for already
canonical application formats, encrypted payloads, large attachments, or
record shapes expected to change during the lifetime of this chain.

`canonical-cbor` accepts exactly one bounded deterministic CBOR item. It rejects
indefinite-length items, non-minimal integers, duplicate or incorrectly ordered
map keys, trailing bytes, invalid UTF-8, and unsupported tags or simple values.
It is useful even without a schema because equal logical values then have equal
bytes and equal value hashes.

A declarative schema is authored in `cddl-yano-subset-v1`. Devtools resolve its
named, non-recursive rules and compile it to canonical
`yano-cbor-schema-ir-v1`. Consensus nodes evaluate the IR; they never parse the
source CDDL. Supported constructs include exact text-keyed maps, bounded arrays,
integer ranges, bounded text/byte strings, literals, choices, and optional
fields. External references, recursion, unbounded repetition, regular
expressions, and host-dependent extensions are rejected.

Schema validation is exact: undeclared map fields are rejected. Keep generous,
intentional bounds for fields expected to evolve, and remember that a schema is
immutable for this chain generation.

The available commitment profiles are:

- `mpf-blake2b256-v1`; and
- `jmt-blake2b256-v1`.

The Poseidon JMT profile remains unavailable until ADR-025 Phase 4 and its
release-pinned ZeroJ dependency are completed. Blueprints fail closed if it is
selected.

## Offline CLI preflight and inspection

Inspect the exact collection and validator set committed by genesis:

```bash
./yano.sh appchain state validators \
  --genesis-file product-registry/config/authenticated-map-genesis.hex
```

The result includes profile, format fingerprint, `genesisId`, collection
encodings and bounds, validator kind and contract version, schema definition
SHA-256, parameter SHA-256, and a plugin artifact-closure SHA-256 when present.

Validate a candidate from canonical lowercase hex or a file:

```bash
./yano.sh appchain state validate \
  --genesis-file product-registry/config/authenticated-map-genesis.hex \
  --collection products --key 736b752d31 --value-file product.cbor

./yano.sh appchain state validate \
  --genesis-file product-registry/config/authenticated-map-genesis.hex \
  --collection products --key 736b752d31 \
  --value-hex a363736b7565736b752d316673746174757366616374697665687175616e7469747905
```

The command returns `ACCEPTED`, `REJECTED`, or `UNAVAILABLE`. `UNAVAILABLE`
means the collection uses a custom plugin but the offline CLI has no exact
application adapter for it; it never means the value was accepted. All three
results are advisory. Authoritative validation occurs when every node applies
the command.

Explain any authenticated-map result or receipt code:

```bash
./yano.sh appchain state explain --code 11
```

| Code | Name | Meaning |
|---:|---|---|
| 0 | `NONE` | Applied without an authenticated-map error |
| 1 | `UNKNOWN_COLLECTION` | Collection is absent from genesis |
| 2 | `COLLECTION_BOUNDS` | Key or value exceeds collection bounds |
| 3 | `UNAUTHORIZED` | Sender does not satisfy collection authorization |
| 4 | `ALREADY_EXISTS` | Operation required an absent entry |
| 5 | `ABSENT` | Operation required an existing entry |
| 6 | `REVOKED` | Operation required an active entry |
| 7 | `ACTIVE` | Operation required a revoked entry |
| 8 | `PRECONDITION` | Revision or value hash did not match |
| 9 | `RESTORE_FORBIDDEN` | Collection does not permit restoration |
| 10 | `VALUE_ENCODING` | Encoding constraint rejected the value |
| 11 | `VALUE_SCHEMA` | Declarative schema rejected the value |
| 12 | `VALUE_VALIDATOR` | Custom validator rejected the value |

Only a rejection reached during authoritative application has an authenticated
receipt. A CLI or client-side rejection has no receipt and makes no finality
claim.

## Java client preflight

The lightweight client module can decode genesis and reuse the encoding and
schema evaluator without depending on the runtime or plugin SPI:

```java
byte[] genesisBytes = HexFormat.of().parseHex(Files.readString(
        Path.of("config/authenticated-map-genesis.hex")).strip());
var preflight = AuthenticatedMapPreflight.fromEncodedGenesis(genesisBytes);

var mutation = AuthenticatedMapContract.Mutation.put(
        "products", skuBytes, canonicalProductCbor);
var result = preflight.validate(mutation);

if (result.accepted()) {
    stock.authenticatedMapMutate(mutation, preflight);
}
```

The overload accepting `preflight` checks the complete command immediately
before HTTP submission and throws `AuthenticatedMapPreflight.PreflightException`
on the first rejected or unavailable mutation. Existing submission methods are
unchanged.

Custom plugin preflight requires an application-supplied adapter selected from
the exact genesis descriptor:

```java
var preflight = AuthenticatedMapPreflight.fromEncodedGenesis(genesisBytes, descriptor -> {
    if (!descriptor.providerId().equals("gs1-gtin-v1")) {
        return Optional.empty();
    }
    verifyPinnedArtifactClosure(descriptor.definition());
    return Optional.of((collection, key, value) -> applicationGs1Check(key, value));
});
```

Returning `Optional.empty()` produces `UNAVAILABLE`, not acceptance. The client
adapter is a convenience only; a malicious client can omit it, so the same
genesis-pinned validator always runs in authoritative apply.

## Custom validator plugins

Schemas cover record shape. Use a custom validator only for a deterministic,
self-contained rule over `(collectionId, applicationKey, value)` that cannot be
expressed in the CDDL subset. Plugin validators cannot read state, sender,
height, membership, clock, randomness, environment, filesystem, or network
through the SPI.

Java bytecode is not sandboxed. A plugin can still call JDK APIs directly, so
validators are trusted consensus code and must be reviewed, explicitly
allow-listed, conformance-tested, and pinned by exact `ARTIFACT_CLOSURE` digest.
Do not load hostile or merely user-uploaded code.

The descriptor remains in the no-SPI contracts artifact, while the factory SPI
lives in `core-api`. Programmatic genesis construction uses:

```java
var validator = AuthenticatedMapContract.ValidatorDescriptor.plugin(
        "gtin-v1",
        "gs1-gtin-v1",
        exactArtifactClosureSha256,
        canonicalCborParameterMap);

var collection = new AuthenticatedMapContract.CollectionDescriptor(
        "products",
        AuthenticatedMapContract.AUTH_OWNER,
        false,
        64,
        4096,
        AuthenticatedMapContract.VALUE_ENCODING_CANONICAL_CBOR,
        validator.id());

var genesis = AuthenticatedMapGenesisFactory.mpf(
        config,
        anchorPolicyCommitment,
        128,
        65536,
        List.of(collection),
        List.of(validator),
        List.of());
```

The first-party `appchain-authenticated-map-validators` module provides
`gs1-gtin-v1` as a worked example for GTIN-8, GTIN-12, GTIN-13, and GTIN-14.
Production resolution additionally requires the provider bundle to be in the
runtime allow-list and its catalog digest mode to be exactly
`ARTIFACT_CLOSURE`.

Blueprint-native custom plugin descriptors are not exposed in v1alpha1. This
prevents a generated project from implying that an arbitrary Java bundle is
safe or installed. Build and review plugin genesis programmatically, or stay
with canonical encoding/declarative schemas in the standard blueprint path.

## Choosing the right boundary

Value validation sees one key and one value. If a rule must read another key,
check a relationship between collections, inspect membership, or emit effects,
it belongs in a composite component or custom state machine. There is no
node-local `AdmissionPolicy` in this feature.

Validation proves that committed bytes satisfy the declared encoding and rule.
It does not prove that the record is true, make a payload confidential, or
replace authorization.

In v1, encodings, schemas, plugins, and plugin parameters are immutable for the
chain generation. Tightening or replacing one requires a new chain generation
and an explicit state import/linking plan. For a record shape likely to evolve,
prefer `canonical-cbor` without a validator until governed validator evolution
is available.
