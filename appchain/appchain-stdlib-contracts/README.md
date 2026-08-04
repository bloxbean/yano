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

Authenticated-map genesis codec version 4 is the final unreleased v1 layout.
It incorporates the ADR-025.1 value-encoding/validator catalogs and the
ADR-025.2 five-mode authorization catalog, stable policy IDs, and optional
canonical governed genesis closure. `VALUE_ENCODING_OPAQUE` (the default,
including the source-compatible five-argument collection constructor)
preserves byte-transparent values.
`VALUE_ENCODING_CANONICAL_CBOR` accepts exactly one RFC 8949 deterministic-CBOR
item and rejects non-preferred encodings, indefinite items, tags, unsupported
simple values, duplicate or incorrectly ordered map keys, invalid UTF-8,
trailing bytes, and values outside the frozen nesting/item limits. Encoding is
consensus-bound in genesis; it is not a node-local setting.

Declarative schemas use the closed `cddl-yano-subset-v1` authoring language.
Devtools compile source into canonical `yano-cbor-schema-ir-v1` bytes before
genesis; nodes evaluate only those inlined, genesis-bound bytes. The frozen
grammar and limits are published beside `schema-vectors.properties` under the
authenticated-map v1 resource directory. `check` runs both the Java corpus and
an independent Python compiler/evaluator against those vectors.

Custom validators use `VALIDATOR_KIND_PLUGIN`. Their descriptor pins the
factory provider id, `authenticated-map-validator-v1` SPI contract, canonical
parameter map, and raw 32-byte SHA-256 artifact-closure digest. A normal plugin
rejection produces `ERROR_VALUE_VALIDATOR = 12`; an unexpected plugin failure
is a node failure and must not become a receipt. The public validator SPI lives
in `core-api`, while first-party implementations live in the separate
`appchain-authenticated-map-validators` bundle.

`AuthenticatedMapAuthorizationContract` freezes the separate action/evidence
layers, complete-action Blake2b-256 commitment, claimed-key Ed25519 statement,
approval reference, direct/approval consumption records, and their exact key
namespaces. The checked-in `authorization-vectors.properties` file is verified
by Java and dependency-free Python. The companion CDDL publishes every array
discriminant and bound.

The logical framework key namespaces are:

| Fact | Logical key before composite wrapping |
|---|---|
| Genesis marker | `yano-authenticated-map-internal-v1 / genesis` |
| Receipt/result | `yano-authenticated-map-receipts-v1 / messageId` |
| Direct consumption | `yano-authenticated-map-direct-consumption-v1 / (v1, actorId, authorizationId)` |
| Approval consumption | `yano-authenticated-map-approval-consumption-v1 / proposalId` |

There is deliberately no collection or action-scope suffix on the approval
key. Physical proofs add the committed composite component namespace; domain
APIs return that exact physical key rather than asking callers to recreate it.
