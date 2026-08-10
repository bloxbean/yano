# ADR app-layer/036: Cardano History proof UX and extensible protocol-parameter projections

**Status:** Proposed
**Date:** 2026-08-10
**Scope:** Cardano History query/proof UX, stake-address normalization, imported-proof verification,
named protocol-parameter projections, and reference off-chain/on-chain claim packages
**Depends on:** ADR app-layer/025.x, 028, 031, and 035
**Supersedes before release:** the positional protocol-parameter value and positional-field validator
described by ADR-028/035

## 1. Context and current behavior

Cardano History already authenticates protocol-parameter documents in primary app-chain MPF state and
large epoch stake and DRep distributions in per-epoch authenticated snapshots. The Cardano History
domain API exposes logical query coordinates while the generic proof APIs generate and verify the
physical proofs.

The preview implementation exposed several usability and evolution problems:

1. Stake queries required a credential discriminator and 28-byte credential hash even though most
   users know a Bech32 `stake`/`stake_test` reward address.
2. A separate Proofs tab implicitly reused the last query from another tab. It was unclear which
   fact, epoch, root, and completeness leaf were being proved.
3. The page used the latest protocol-parameter epoch for every dataset. Completed stake and DRep
   snapshots can legitimately lag it.
4. Generated proofs could be displayed, but local node verification, caller-pinned verification,
   and independent verification were not clearly distinguished.
5. `ProtocolParamsCanonicalCodec` freezes the complete document as a 56-element positional CBOR
   array. `CardanoHistoryParametersValidator` understands only a few unsigned integer positions.
   Adding a Cardano protocol parameter therefore changes a fixed codec and potentially the on-chain
   positional parser.
6. There is no production adapter from the portable Cardano History proof response to the normalized
   reference-validator redeemer. Stake claim intent such as minimum coin, exact coin, pool
   delegation, their combinations, and complete-snapshot absence is not yet a versioned wire
   contract.

The existing state roots and proofs remain cryptographically valid for the preview profile, but the
format is not a suitable long-lived product contract. App chains have not been released, so this ADR
chooses a clean replacement rather than migration or compatibility code.

## 2. Decision summary

Cardano History will use a **query-owned proof flow**. Parameters, Stake, and Governance views own
their query coordinates, result, proof generation, proof export, and immediate off-chain
verification. The separate Verify Proof view accepts an externally supplied proof and explicit
trust context; it never derives coordinates from hidden page state.

The server accepts a Bech32 stake address as the default stake-query identifier and uses Cardano
Client Lib (CCL) to derive the key/script discriminator and credential hash. Advanced callers retain
the raw discriminator/hash route. Exactly one input mode is active.

Protocol parameters will be stored as a canonical, self-describing named document and as selected
typed field leaves in primary authenticated state. The generic epoch-parameter state machine applies
a versioned projection manifest; it does not contain hard-fork-specific field positions. A Cardano
hard fork extends the L1 adapter/registry and its golden vectors, not the state-machine algorithm.

The full cost-model document is authenticated, along with one digest per Plutus language. Individual
cost coefficients are deliberately not projected.

## 3. UX and API contract

### 3.1 Dataset-specific epochs

The page obtains epochs independently:

| Subject | Epoch source | Meaning |
|---|---|---|
| Protocol parameters | Cardano History parameter catalog | Effective parameter epoch |
| Stake | `l1-epoch-stake-v1.distribution` snapshot catalog | Completed end-of-epoch stake snapshot |
| DRep distribution | governance snapshot catalog | Completed start-of-epoch DRep snapshot |
| Proposal | Domain query coordinate | Epoch at which proposal state is requested |

No dataset silently substitutes another dataset's latest epoch. A disabled component is shown as
unavailable, not as an empty result.

### 3.2 Stake identifiers

The default input is a Cardano reward address. The server parses it with the shared CCL-backed
`CardanoBech32Ids` utility and returns the normalized address, credential type, and credential hash.
Only a 29-byte key-hash or script-hash reward address is accepted. Base, enterprise, pointer,
malformed, or unsupported addresses fail with a bounded invalid-request response.

The advanced input accepts:

* credential type `0` (key hash) or `1` (script hash); and
* exactly 28 bytes of lowercase-normalized hexadecimal credential hash.

The two modes share one semantic query and proof path. Network ID is presentation metadata and is
not part of the canonical stake snapshot key.

### 3.3 Query-owned proof actions

Each subject view provides:

1. query;
2. generate proof for the visible result and committed root;
3. verify the generated proof off-chain;
4. export the portable proof package; and
5. for supported claims, export a normalized on-chain redeemer package.

Stake and DRep snapshot facts require both the subject proof and the authenticated completeness/meta
proof where absence or snapshot completeness matters. Parameters and proposals use their primary
state proof. Proof generation is disabled when the visible result no longer matches the selected
chain/epoch/identifier.

### 3.4 Verification trust levels

The UI and CLI use explicit labels:

* **proof-valid / bundled-root** — the proof matches a root carried in the same bundle; this proves
  internal consistency only;
* **proof-valid / local-anchor** — the node verifies the root against its locally observed accepted
  L1 anchor;
* **proof-valid / caller-pinned-anchor** — the caller supplies the complete expected chain,
  application/profile, height, block, transaction, slot, generation ID, digest, and root identity;
* **independently verified** — an external verifier obtains and validates the Cardano anchor rather
  than trusting the queried node.

A local verification endpoint is useful diagnostics but is never described as independent. Failure
responses preserve whether cryptography, semantic binding, completeness, or anchor identity failed.

The Verify Proof view is only for imported proof JSON. Caller-pinned mode requires every anchor
identity field; partial context fails closed.

## 4. Extensible protocol-parameter state

### 4.1 Logical keys

For epoch `E`, the parameter component owns:

```text
params/E/document
params/E/meta
params/E/fields/<field-id>
```

The full document remains the lossless source for exact-document claims and future off-chain
interpretation. Field leaves make common semantic predicates compact and stable on-chain.

Initial projected field IDs include:

```text
min-fee-a                 min-fee-b
max-block-size            max-tx-size
max-block-header-size     key-deposit
pool-deposit              max-epoch
desired-pool-count        pool-influence
monetary-expansion        treasury-expansion
protocol-version          min-pool-cost
execution-unit-prices     max-tx-ex-units
max-block-ex-units        max-value-size
collateral-percent        max-collateral-inputs
coins-per-utxo-byte       governance-action-deposit
drep-deposit              drep-activity
cost-models               cost-model-hash/plutus-v1
cost-model-hash/plutus-v2 cost-model-hash/plutus-v3
```

The registry may add fields. Existing IDs never change meaning or type. Aliases are API-only and are
not authenticated keys.

### 4.2 Canonical document

The replacement document is deterministic CBOR:

```text
[format-version, epoch, [[field-id, type-tag, canonical-value], ...]]]
```

Rules:

* field entries are unique and sorted by unsigned UTF-8 field-ID bytes;
* IDs are lowercase ASCII kebab-case with bounded length;
* integers use canonical CBOR integers; ledger coin values are non-negative integers;
* rationals are reduced `[numerator, denominator]` pairs with positive denominator;
* byte strings remain byte strings and hashes have field-specific fixed lengths;
* arrays/maps have deterministic ordering and bounded depth/count/size;
* floats, indefinite-length items, duplicate keys, non-minimal integers, and unknown type tags fail;
* `null` means the Cardano field exists but has no value; absence means the field is not present in
  that ledger era;
* re-encoding the parsed value must reproduce the original bytes exactly; and
* the epoch in the document and every projected leaf must equal the state key epoch.

The format size limit is 6 MiB at L1 observation admission, while an individual on-chain proof
package remains subject to its much smaller transaction/redeemer budget. Large documents are not
copied into field proofs.

### 4.3 Typed field leaves

Each field leaf is independently bound to its identity:

```text
[field-format-version, epoch, field-id, type-tag, canonical-value]
```

Closed type tags initially cover unsigned integer, signed integer, lovelace, rational, bytes/hash,
protocol version, structured document, and explicit null. On-chain validators support only the
types and predicates they declare. Unknown types can still be authenticated and verified off-chain
without being interpreted by a validator.

The component writes the document, metadata, and all configured projections atomically in one state
transition. Duplicate/missing required projections, a registry digest mismatch, or any canonical
encoding failure rejects the observation. Rollback uses the existing app-chain transition and
authenticated-state mechanisms; no mutable side index becomes authoritative.

### 4.4 Hard-fork evolution

The state-machine algorithm accepts a canonical document plus projection manifest. Cardano-specific
L1 adaptation owns extraction from `ProtocolParamsSnapshot`, stable field naming, and type mapping.
When a hard fork adds parameters:

1. the adapter/field registry adds named entries and golden vectors;
2. the admitted capability/profile digest changes if consensus-visible projections change;
3. the generic parameter component remains unchanged; and
4. mixed nodes with different manifests cannot form the same application identity or accept
   different state.

Unknown but canonical fields remain in the document and may be stored as opaque canonical CBOR. A
later release can promote one to a typed projection without reinterpretation. Because the product is
preview-only, the first implementation starts a fresh Cardano History chain with the new profile;
there is no positional-format reader, dual writer, or migration path.

### 4.5 Cost models and nested parameters

`cost-models` authenticates the complete canonical language-to-positional-coefficient map. Each
known language also gets a domain-separated digest leaf:

```text
H("yano:cardano-cost-model:v1" || language-id || canonical-language-model)
```

This permits a compact equality claim about the active Plutus language model. Coefficients are not
separate leaves: coefficient-level state would greatly expand keys and invites brittle semantics as
ledger language versions evolve.

Other structured parameters, such as protocol version, execution-unit prices/limits, voting
threshold groups, and reference-script pricing, receive one typed leaf at the meaningful ledger
boundary. A field is decomposed further only when a concrete on-chain predicate needs it and the
registry can give the subfield a stable cross-era meaning.

## 5. Claim and proof packages

### 5.1 Parameter claims

The initial predicates are:

* exact canonical field value;
* unsigned integer/lovelace equal, minimum, maximum, or inclusive range;
* exact rational after reduction;
* exact hash/byte string;
* field absent for the era, using authenticated document/meta semantics; and
* exact full parameter document.

For example, `key-deposit == 2_000_000` at epoch 305 proves the
`params/305/fields/key-deposit` leaf and binds its typed envelope, epoch, field ID, application
identity, commitment profile, anchored height, and MPF root.

### 5.2 Stake claims

The versioned stake claim contract supports:

* coin minimum;
* coin exact;
* delegated pool hash exact;
* coin minimum plus pool hash;
* coin exact plus pool hash; and
* credential absent from a complete epoch snapshot.

Coin and pool hash remain separate semantic fields inside the authenticated stake value so a minimum
coin claim does not require knowing the pool. Absence is accepted only when the completeness/meta
leaf and subject non-inclusion proof bind to the same immutable snapshot root and epoch.

### 5.3 Portable and on-chain packages

The portable package carries:

* versioned subject coordinates and claim;
* canonical value or absence marker;
* primary proof or secondary subject plus completeness proofs;
* secondary descriptor/root binding where applicable;
* committed height and application/commitment profile identity; and
* optional caller-pinned L1 anchor identity.

One production converter validates that package and emits the generic MPF reference-validator
redeemer shape. It never accepts an arbitrary UI claim that is not exactly reflected in authenticated
bytes. JMT packages remain off-chain-only and the UI disables on-chain export for them.

## 6. Security and operational requirements

1. Address parsing, field IDs, epochs, hashes, proof nodes, document sizes, nesting, and imported JSON
   are strictly bounded before expensive work.
2. Every proof binds chain generation, application profile digest, commitment profile, key, value,
   anchored height, and root. A valid proof for another chain or snapshot is rejected.
3. Snapshot completeness and fact proofs must use one descriptor/root; independently valid proofs
   from different snapshots cannot be combined.
4. UI claim controls are descriptive until the production converter and validator vector pass. The
   page must say that no on-chain redeemer is available rather than export an incomplete object.
5. Sensitive API keys and imported proof material are not persisted in browser local storage or
   written to logs.
6. Canonicalization has cross-language Java and validator golden vectors, including malformed and
   resource-exhaustion cases.

## 7. Implementation plan

### P0 — Characterize and freeze current behavior

* Record positional codec, current routes, proof envelopes, snapshot completeness semantics, and
  on-chain limitations.
* Add regression fixtures for a real Conway parameter snapshot and completed stake snapshot.
* Mark positional parameter profile preview-only and forbid new consumers.

**Exit:** fixtures reproduce current roots/proofs and every intentional replacement is listed.

### P1 — Query-owned console proof UX

* Add dataset-specific epoch selection.
* Add CCL-backed stake-address route and mutually exclusive advanced raw inputs.
* Move generation/export/local verification into Parameters, Stake, and Governance views.
* Convert the standalone Verify Proof view to imported-proof verification with optional complete
  caller-pinned context and explicit trust labels.
* Expose stake claim-intent controls but keep on-chain export disabled until P5.

**Exit:** frontend checks pass; stale/hidden query state cannot select a proof; malformed addresses
and partial pinned contexts fail closed.

### P2 — Named parameter contract and registry

* Define field IDs, type tags, canonical document/leaf codecs, registry digest, limits, and golden
  vectors in the stdlib contract owner.
* Replace the fixed 56-position codec and state keys without a compatibility reader/writer.
* Add deterministic ordering tests using shuffled source maps and repeated nodes.
* Add null/absent, rational, structured-value, unknown-field, and cost-model digest vectors.

**Exit:** identical logical parameter sets produce identical roots independent of input iteration
order; malformed or manifest-inconsistent input is rejected.

### P3 — Generic epoch-parameter component

* Make the component apply a versioned projection manifest and atomically write document/meta/field
  leaves.
* Keep Cardano hard-fork extraction in the observer adapter/registry, not component logic.
* Publish field/schema discovery through capability/domain APIs so clients do not hard-code the
  currently active era.
* Audit transition rollback and replay identity across three nodes.

**Exit:** a synthetic future parameter passes through the generic component without changing its
state-transition code, and three-node roots agree after replay/rollback.

### P4 — Parameter query, client, and proof APIs

* Add field catalog and typed field query/proof helpers while preserving generic physical proof APIs.
* Add CLI commands for document and named-field query/proof/verify.
* Update the Parameters UI with field selection, typed predicates, proof export, and off-chain
  verification.
* Keep proof endpoints bounded and root-fixed to the selected result.

**Exit:** `key-deposit == 2 ADA` and representative integer, rational, hash, null/absent, and
cost-model digest claims round-trip through API, client, CLI, and UI.

### P5 — Production claim/redeemer adapters

* Version stake and parameter claim DTOs and canonical encodings.
* Implement portable-proof-to-MPF-redeemer conversion with semantic validation.
* Replace positional parameter parsing in the reference validator with named typed-leaf validation.
* Implement exact-only and combination stake predicates plus complete-snapshot absence.
* Publish cross-language positive/negative validator vectors and transaction-size/ex-unit budgets.

**Exit:** exported MPF redeemers validate on Cardano for all supported predicates; wrong field,
epoch, profile, root, completeness leaf, value, and JMT profile fail.

### P6 — Qualification and documentation

* Run unit, property, malformed corpus, multi-node replay/rollback, snapshot, and on-chain tests.
* Test UI and CLI against devnet and retained preprod data.
* Document how to obtain an independent L1 anchor and distinguish every verification trust level.
* Update ADR-028/035 references and recreate showcase clusters because the state profile changes.

**Exit:** clean full build passes; devnet/preprod runbooks demonstrate parameter and stake proofs;
no positional profile remains in distributed artifacts.

## 8. Testing matrix

| Area | Required tests |
|---|---|
| Address UX | key/script reward addresses, mainnet/testnet, raw hex, wrong address types, malformed/oversized input |
| Canonical params | shuffled fields, duplicate IDs, non-canonical CBOR, rational reduction, null vs absent, future field, size/depth limits |
| Cost models | stable per-language digest, language addition, coefficient mutation, ordering, no coefficient leaves |
| Consensus | three-node same root, restart, rollback/replay, mixed registry rejection, fresh-chain identity |
| Proof semantics | inclusion/non-inclusion, field/type/epoch mismatch, snapshot pair mismatch, stale root, wrong profile/application |
| UI/API | dataset epoch lag, disabled capability, query invalidation, imported proof, local/pinned labels, bounded errors |
| On-chain | every predicate positive/negative, anchor mismatch, exact-only stake, absence completeness, MPF accepted/JMT rejected |

## 9. Consequences

The UX becomes understandable without exposing physical state keys, and a Bech32 stake address is
enough for the common path. The Verify Proof view has a clear independent-input purpose.

Named leaves make common protocol-parameter claims compact and keep validators stable when Cardano
adds unrelated parameters. The cost is additional authenticated writes per epoch and a registry that
must be governed as a consensus-visible contract. Storing both the full document and projections is
intentional: the document preserves lossless history while projections provide usable on-chain
semantics.

Removing the positional format changes Cardano History roots and application profile identity. This
is accepted before release and requires fresh showcase chains, but avoids permanent dual-format
technical debt.

