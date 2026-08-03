# App-chain standard-library contracts

This no-SPI artifact contains the bounded, versioned command, state-key, and
state-value codecs for Yano's stock `kv-registry`, `approvals`, `balances`,
`doc-trail`, and `authenticated-map` state machines. Applications may depend
on it without pulling in a node runtime or state-machine implementation.

The contracts accept only canonical definite-length CBOR and enforce a 1 MiB
wire bound. The artifact contains no plugin manifest, `ServiceLoader` entry,
network client, storage code, or runtime SPI implementation.

`AuthenticatedMapContract` additionally freezes the ADR-025 v1 logical-key,
collection, command, entry, genesis, logical-value-hash, and batch-commitment
contracts. Checked-in MPF/classic-JMT roots and proof vectors live at
`META-INF/yano/contracts/authenticated-map/v1/golden-vectors.properties`.
Run `./gradlew :appchain-stdlib-contracts:check` to verify them through both
CCL and the independent Python verifier.

Authenticated-map genesis codec version 2 adds a per-collection value-encoding
catalog. `VALUE_ENCODING_OPAQUE` (the default, including the source-compatible
five-argument collection constructor) preserves byte-transparent values.
`VALUE_ENCODING_CANONICAL_CBOR` accepts exactly one RFC 8949 deterministic-CBOR
item and rejects non-preferred encodings, indefinite items, tags, unsupported
simple values, duplicate or incorrectly ordered map keys, invalid UTF-8,
trailing bytes, and values outside the frozen nesting/item limits. Encoding is
consensus-bound in genesis; it is not a node-local setting.
