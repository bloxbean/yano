# Yano App-Chain Integration Contracts

Provider-neutral, SDK-free wire contracts shared by Yano's first-party effect
connectors and deterministic state machines.

The module contains the frozen v1 canonical-CBOR payload and receipt models
for:

- `kafka.publish`
- `object.put`
- `ipfs.pin`

It also defines portable target aliases, bounded normalized failure codes,
target fingerprints, and domain-separated connector detail documents. It does
not contain Kafka, S3, IPFS, network-client, runtime, or credential code. Its
optional Java-filesystem detail archive is content-addressed and accepts hashes,
never caller paths.

The module explicitly declares only the CBOR codec and Bouncy Castle's
Blake2b implementation; the repository's shared Java convention also adds the
SLF4J API. In particular, this module does not pull the Cardano Client Library
(CCL) graph into connector plugins.

All decoders are fail-closed: they require one exact, definite-length,
preferred CBOR value, reject trailing data and unknown fields, and apply the
same semantic limits used by encoders. An allocation-free raw preflight
validates declared lengths and limits values to 512 items/eight array levels
before the CBOR decoder runs.

## V1 wire profiles

| Action | Command type | Confirmed reference |
|---|---|---|
| `kafka.publish` | `KafkaPublishCommandV1` | `KafkaPublishReceiptV1` |
| `object.put` | `ObjectPutCommandV1` | `ObjectPutReceiptV1` |
| `ipfs.pin` | `IpfsPinCommandV1` | `IpfsPinReceiptV1` |

### Frozen semantic grammars

All textual fields are US-ASCII. The portable v1 grammars are:

| Field | Exact rule |
|---|---|
| payload alias and configuration `target-id` | `[a-z][a-z0-9-]{0,62}` |
| content type | `[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+`, 1..127 bytes; no parameters |
| Kafka application-header name | `[a-z0-9][a-z0-9._-]{0,31}`, excluding the reserved `yano-` prefix |
| object-key segment | `[A-Za-z0-9][A-Za-z0-9._~!$'()+,;=@-]{0,127}` |
| stable provider text | printable ASCII U+0020..U+007E with the field-specific bound |

An object key is 1..512 bytes and 1..32 slash-separated object-key segments.
It cannot contain empty, `.` or `..` segments, a leading/trailing slash, a
backslash, percent sign, or non-ASCII character. A destination prefix used in
an object fingerprint is either empty or the same normalized object-key form;
it never ends in `/`. A configured object bucket matches
`[A-Za-z0-9][A-Za-z0-9._-]{0,254}`.

A Kafka command has at most 16 unique headers, sorted lexically by name. Each
value is at most 256 bytes and the sum of all ASCII name bytes and value bytes
is at most 2,048. The command topic is a portable alias; the resolved physical
topic committed by the receipt matches `[A-Za-z0-9._-]{1,249}` and is neither
`.` nor `..`.

The printable provider fields are `provider-version-id` (1..1,024 bytes),
optional `etag` (1..256 bytes), and optional IPFS `provider-reference`
(1..256 bytes). Kafka header values and the IPFS submitted request id are
opaque byte strings, not text.

`CanonicalCid.fromText(...)` is a client-boundary helper. It normalizes legacy
CIDv0 dag-pb text to CIDv1; effect payloads always carry canonical CIDv1 bytes.
V1 accepts exactly 36-byte CIDv1 values using raw (`0x55`) or dag-pb (`0x70`)
with a 32-byte sha2-256 multihash.

### Fingerprint preimages

Destination fingerprints use
`blake2b-256(US_ASCII(domain) || canonical_cbor(descriptor))`:

| Fingerprint | Domain | Descriptor |
|---|---|---|
| Kafka destination | `yano-kafka-destination-v1` | `[target-id, physical-topic]` |
| object destination | `yano-object-destination-v1` | six-item object descriptor (below) |
| IPFS target | `yano-ipfs-target-v1` | `[target-id]` |

The object version fingerprint is
`blake2b-256("yano-object-version-v1" || US_ASCII(provider-version-id))`.
The IPFS CID fingerprint is
`blake2b-256("yano-ipfs-cid-v1" || canonical-cid-v1-bytes)`.
Domains are concatenated without a NUL or length prefix. Descriptors are
definite-length, preferred-serialization CBOR arrays. The published golden
vectors include every input field, output fingerprint, and a CIDv0-to-CIDv1
normalization example.

The six-item object descriptor is `[target-id, bucket, normalized-prefix,
relative-key, encryption-policy-id, retention-policy-id]`, in that order.

CDDL is under `src/main/cddl/connectors`. The published JAR carries those
schemas and executable literal golden vectors under
`META-INF/yano/contracts/connectors/v1`. Use `connectors-v1.cddl` as the
self-contained schema; the named fragments are also present for reference.
`check` verifies that the aggregate is the deterministic ordered composition
of the published fragments and that all artifact entries are non-empty.
An independent RFC 8610 check is also available without making the normal
build depend on Rust or Docker:

```bash
CDDL_BIN=/path/to/cddl-0.10.5 ./scripts/verify-cddl.sh
```

It compiles the aggregate and validates every published command, receipt,
submitted-reference, and detail vector against its declared root rule.

The aggregate is the self-contained structural schema, but RFC 8610 cannot
portably express every semantic invariant. The adjacent CDDL comments and the
frozen Java codecs remain normative for Kafka header lexical ordering,
uniqueness, aggregate byte size and reserved-prefix exclusion, and for the
special `.`/`..` physical-topic exclusion. These constraints are repeated in
the semantic-grammar section above and covered by executable negative tests;
consumers must not treat CDDL validation alone as full contract validation.

Thin/native-catalog deployments pin one copy of this library. A self-contained
drop-in connector bundle must relocate its copy into a connector-specific
internal package and must expose only bytes—not contract classes—through host
SPI signatures.

## Failure handling

Decode errors throw `ConnectorContractException` carrying only a stable
`ConnectorErrorCode`. An executor returns the code's `wireCode()` as its
failure reason. It must never return the parser exception, payload, alias,
endpoint, credential, or provider error text.

## Detail documents

`ConnectorDetailDocumentV1` permits only connector-specific stable fields and
is committed with `ConnectorDetailHash`. A `detailHash` is returned only after
`ConnectorDetailArchive.archive(...)` succeeds. `FileConnectorDetailArchive`
provides the durable create-if-absent reference implementation; retrieval must
still sit behind a privileged operator API or CLI. The reference archive
requires a private POSIX filesystem supporting owner-only permissions, hard
links, and file/directory fsync, and probes those capabilities at startup.
Operators must place it on a monitored, quota-bounded filesystem.
The runtime UID and private archive tree are trusted against concurrent path
replacement; do not grant another same-UID process write access. After an
unclean stop, `.detail-*.tmp` files may be removed only while all archive
writers are stopped.

## Test

```bash
./gradlew :appchain-integration-contracts:test
```

See [ADR-013](../../adr/app-layer/013-first-party-integration-connectors-and-effect-demo.md).
