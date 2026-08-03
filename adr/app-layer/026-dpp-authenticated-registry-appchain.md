# ADR-026: DPP Authenticated Registry Appchain

## Status

Proposed — implementation-deferred follow-up to
[ADR-025](025-authenticated-map-and-genesis-selected-state-commitments.md).

No DPP implementation begins until ADR-025 is accepted, implemented, and has
passed its compatibility, determinism, recovery, proof, and multi-member
qualification gates. This ADR changes no runtime behavior, configuration, wire
format, module, or published capability. It must not be cited as evidence that
Yano currently implements or conforms to a Digital Product Passport standard.

## Date

2026-08-03

## Related decisions and research

- [ADR-005](005-yano-app-chain-framework.md) owns app blocks, threshold
  finality, state, proofs, and L1 anchoring.
- [ADR-010](010-deterministic-effect-system.md) owns finality-gated effects,
  receipts, retries, and deterministic result incorporation.
- [ADR-013.2](013.2-deterministic-composite-state-machine.md) owns component
  isolation, declared workflows, effect ownership, and the one-state-machine-
  per-chain boundary.
- [ADR-015](015-governed-composite-profile-evolution.md) owns governed
  activation of already packaged composite profiles.
- [ADR-019](019-reusable-domain-actor-registry-and-role-aware-approvals.md) owns
  reusable domain-actor and role-approval concepts.
- [ADR-021](021-generic-on-approved-effect.md) owns generic approval-gated
  effects where that behavior is reused.
- [ADR-025](025-authenticated-map-and-genesis-selected-state-commitments.md)
  proposes the authenticated-map contract and chain-wide MPF, classic JMT, and
  ZeroJ Poseidon JMT commitment profiles on which this design depends.
- [DPP Possible Design and Yano Capability Assessment](dpp-possible-design.md)
  is the earlier exploratory research note. This ADR formalizes and refines its
  product, composite, and state-commitment decisions.
- The requirements source is the
  [Cardano Foundation Digital Product Passport on Cardano — Solution Blueprint
  v0.1](https://github.com/cardano-foundation/cardano-dpp-standards/blob/main/DPP-Blueprint-Cardano-v0.1.md).
  It is treated as a draft pattern library, not a frozen compliance standard.

## 0. In plain words

Yano should eventually offer a DPP authenticated registry appchain for product
identity, passport versions, claims, issuer keys, lifecycle events,
certificates, recovery claims, and revocation state.

The Cardano Foundation blueprint is a catalogue of scenarios and requirements.
Yano does not have to copy its proposed implementation of one NFT, tree,
partition root, or Cardano transaction per product or batch. The default Yano
design instead uses:

1. one governed appchain for one DPP trust and failure-isolation domain;
2. one ordered finalized ledger for accepted command history;
3. one authenticated state map for current state and immutable receipts;
4. one chain-wide commitment profile selected at genesis;
5. one threshold-finalized state root per app block;
6. periodic Cardano script anchoring of qualified roots; and
7. external storage, projection, search, cache, and portal services for large
   documents and high-volume reads.

A basic prototype can be configuration over the generic `authenticated-map`
state machine. It demonstrates authenticated records, revisions, revocation,
compare-and-set, batches, proofs, and anchors, but it does not enforce complete
DPP domain truth.

The complete product is a custom manifested DPP state-machine bundle built on
the composite programming model. It exports its own DPP machine identity; it is
not an arbitrary YAML composition under the stock `composite` identity.

All DPP component state is committed under exactly one selected backend:

```text
yano-mpf-blake2b256-v1
or
classic-radix16-blake2b256-v1
or
zeroj-poseidon-jmt-v1
```

Components do not select separate trees, create sidecar trees, or nest JMT in
MPF. The first production-oriented DPP release is MPF-first. Classic JMT and
Poseidon JMT become selectable only after the complete DPP composite and proof
clients pass the corresponding ADR-025 qualification gates.

## 1. Problem

### 1.1 Four scenarios share one underlying state problem

The external blueprint describes a static passport anchor, a privacy-preserving
anchored proof, an append-only lifecycle event log, and a high-throughput scan
path. Most of their durable requirements overlap:

- stable product and issuer identifiers;
- versioned product and claim state;
- authenticated partner keys and roles;
- authorized, replay-protected mutations;
- current, replaced, revoked, retired, or inactive status;
- append-only evidence of accepted lifecycle operations;
- certificate and recovery-claim uniqueness;
- historical auditability;
- point proofs against a trusted checkpoint;
- confidential data kept outside public state;
- high-volume ingestion with amortized L1 publication; and
- cached responses tied to a precise finalized and, when claimed, L1-anchored
  root.

Four unrelated stacks would duplicate identity, canonicalization,
authorization, versioning, proofs, anchoring, and operations. A common DPP
authenticated registry can satisfy the shared requirements while retaining
optional direct-L1 artifacts when their semantics are actually needed.

### 1.2 A tree is necessary but is not a DPP product

ADR-025 supplies a deterministic map and commitment backends. A root proves
which canonical bytes existed at a finalized height. It does not define:

- what a GTIN, passport, claim, event, or certificate means;
- which actor may perform a transition;
- which lifecycle transitions are valid;
- whether a recovery claim consumes a unique entitlement;
- whether an issuer is accredited;
- whether a physical or environmental assertion is true;
- how JSON-LD, EPCIS, units, decimals, and timestamps are canonicalized;
- how confidential data is encrypted and disclosed; or
- how consumers discover and render a passport.

A generic map client can follow such rules voluntarily. A production DPP
network must enforce the consensus-relevant subset in deterministic application
code.

### 1.3 The DPP state machine and commitment backend are different layers

The DPP state machine answers:

```text
Is this command valid, who authorized it, and what logical DPP state follows?
```

The ADR-025 profile answers:

```text
How is the resulting complete appchain state committed and proved?
```

The same canonical command sequence should produce equivalent logical DPP
records under every qualified profile. MPF, classic JMT, and Poseidon JMT roots
and native proofs remain deliberately different.

### 1.4 Direct-L1 and appchain trust must not be confused

A per-product Cardano asset or datum is independently visible in Cardano's
ledger. A Yano checkpoint is a root finalized by the configured appchain quorum
and later notarized or protected by a Cardano anchor policy. Cardano does not
re-execute every DPP transition merely because it stores that root.

The API and UI must distinguish whether a fact is:

- signed by a named domain actor;
- accepted under DPP application rules;
- threshold-finalized by the appchain;
- included in a particular authenticated state root;
- anchored on Cardano through a particular height;
- verified by a direct MPF proof or ZeroJ application proof; and
- corroborated by an independent real-world authority.

One ambiguous `verified` badge is insufficient.

## 2. Decision summary

When work begins after ADR-025 completion, Yano will follow these decisions:

1. Create a first-party DPP authenticated registry product at the application
   layer, without changing Cardano or Yano consensus.
2. Treat the external blueprint as requirements input rather than a mandatory
   implementation architecture.
3. Permit a clearly labelled configuration-only prototype on
   `authenticated-map`; do not present it as the complete DPP product.
4. Implement the complete product as a custom manifested provider built on the
   deterministic composite library and exporting its own DPP identity.
5. Keep tightly coupled product, actor-key, event, certificate, and recovery
   checks in a coherent DPP core component.
6. Reuse independent approval and publication components only through exact
   declared routes and named workflows.
7. Select one chain-wide MPF, classic JMT, or Poseidon JMT profile at genesis.
8. Do not create a tree per product, claim class, tenant, topic, or component.
9. Use the finalized appchain ledger for order and the authenticated map for
   current records, immutable receipts, deduplication markers, and product
   heads. Searchable timelines are projections.
10. Keep large and confidential content outside consensus state; commit hashes,
    hiding commitments, bounded references, status, and authorization metadata.
11. Require inner domain-signed commands. Transport or member identity does not
    establish manufacturer, auditor, shipper, recycler, or device identity.
12. Use stable product keys and application revisions. Use historical roots and
    retained versions for time travel, with immutable version records only when
    their permanent addressability is required.
13. Anchor aggregate qualified roots by default. Per-product Cardano assets or
    CIP-68 references are optional publication profiles.
14. Separate appchain finality from L1 anchoring in every API and UI.
15. Serve consumer scans, GS1 resolution, search, and timelines through a
    proof-aware projection tier rather than voting nodes as a public database.
16. Release MPF first, classic JMT after full DPP qualification and benchmarks,
    and Poseidon JMT only for an explicit reviewed ZeroJ statement.
17. Do not claim confidentiality from Poseidon alone; replicated plaintext is
    visible to appchain members.
18. Freeze no DPP module, machine ID, command ID, or wire codec until ADR-025 is
    complete and the DPP contract phase begins.

## 3. Blueprint scenarios and the alternate Yano design

### 3.1 Scenario summary

| Blueprint scenario | Default Yano realization | When the blueprint/direct-L1 pattern remains useful |
|---|---|---|
| Static Passport Anchor | Stable product record plus content commitment in one DPP state root, periodically anchored | A few genuinely immutable public passports, or a wallet-visible asset is required |
| Anchored Proof | Claim/value commitments as authenticated keys with profile-tagged point or ZeroJ proofs | A separate per-passport claim tree is required by an external format or verifier |
| Event Log | Ordered appchain blocks plus immutable event entries and a materialized product head | An independent compact append-only-history proof specifically requires MMR/hash-chain semantics |
| High Throughput | Many updates per app block, one global root, periodic L1 checkpoints, proof-aware cache/CDN | Separate chains are justified by different governance or failure isolation, not arbitrary batch partitioning |

### 3.2 Static passport anchor

For a large or mutable registry, Yano will key `products` by the canonical
product identifier; store the current manifest commitment, issuer, schema,
revision, status, and storage reference; retain the full document externally;
and anchor the aggregate root periodically.

This avoids using burn-and-mint, mint-only version assets, or repeated CIP-68
datum updates merely to implement database versioning. An optional immutable
locator asset may reference `(chainId, productKey)` for wallet/Cardano
discoverability without becoming the mutable DPP state.

### 3.3 Anchored proof and selective disclosure

Claims can be independent canonically keyed entries instead of an unrelated
tree for every passport:

```text
collection = claims
key        = canonical(productId, claimType, claimId)
value      = public claim | salted commitment | encrypted-reference commitment
```

A proof response binds the disclosed value or commitment, product, issuer,
status, revision, height, block, commitment profile, root, native proof,
finality, and optional L1 anchor.

Poseidon JMT may later support explicit DPP-specific statements such as recycled
content above a threshold, carbon intensity below a limit, active certification,
or recovery eligibility without revealing every underlying field. These require
application circuits binding all relevant records, policy inputs, roots,
recipients, nullifiers, and transaction context; they do not follow
automatically from choosing Poseidon.

### 3.4 Event log and lifecycle history

The default does not replace Yano's ordered block history with an MMR:

- the finalized ledger preserves accepted command order;
- an immutable `events` entry provides an exact event point proof;
- `product-heads` stores the next sequence, last event digest, current lifecycle
  state, and revision;
- one atomic transition inserts the event and advances the head;
- expected revision and unique event ID prevent stale updates and replay; and
- a projection builds product, actor, time, location, and event-type timelines.

The model distinguishes signed business `observedAt`, deterministic finalized
height/L1 reference, and later Cardano anchor observation. It never reads the
local wall clock during deterministic execution.

### 3.5 High-throughput registry and scan path

The default high-throughput flow is:

```text
bounded DPP commands
  -> sequenced app blocks
  -> one threshold-finalized state root per block
  -> periodic Cardano checkpoint
  -> finalized sink and idempotent projector
  -> query database and cache/CDN
  -> GS1 resolver/portal with root-pinned proof evidence
```

Operational partitions may use region, brand, tenant, or time windows. They do
not create separate consensus roots unless different membership, threshold,
retention, anchoring, or failure isolation justifies a separate appchain.

No product-count, throughput, or scan-latency claim is accepted without a
reproducible workload benchmark. Tree selection does not eliminate signature,
execution, consensus, projection, proof, network, and cache costs.

### 3.6 Blueprint open points

| Open point | Yano decision |
|---|---|
| Partner signing keys and trust | Authenticated actor/key registry with governed accreditation, rotation, validity, and revocation history |
| Inactive or replaced passport | Explicit lifecycle status, revision, optional successor, and deterministic transitions |
| Cache claims verification too early | Root-pinned proof plus separate appchain-finalized and L1-anchored-through heights |
| Shared serialization and fingerprints | Genesis-bound codecs, domain separation, schema IDs, and cross-language golden vectors |

## 4. Product and deployment boundary

### 4.1 One chain per trust and isolation domain

One DPP chain is appropriate when its products and tenants share member
governance, threshold, sequencing/finality, DPP schema, commitment profile,
anchor policy, retention, and acceptable failure domain. Logical collections
separate records inside that domain. Incompatible governance, proof profiles,
or failure isolation requires a separate appchain.

### 4.2 Conceptual service layout

```text
ERP / PIM / PLM / MES / EPCIS / devices
                    |
                    v
              DPP gateway
       canonicalize, authorize, sign,
       stage content, submit command
                    |
                    v
      DPP authenticated registry appchain
      +----------------------------------+
      | custom DPP composite provider    |
      | dpp-core                         |
      | approvals / governance           |
      | publication workflow             |
      | one chain-wide MPF/JMT root      |
      +----------------------------------+
          |                    |
          | finalized sink     | finality-gated effects
          v                    v
  projector / query DB    object/IPFS/Kafka/Cardano
          |
          v
   cache / CDN / GS1 resolver / portal / verifier SDK
```

Gateway, projector, and portal databases are rebuildable projections. They do
not own hidden consensus state.

### 4.3 No DPP-driven consensus change

The product does not require a new transport, consensus algorithm, sequencer,
finality scheme, Cardano ledger rule, or per-product appchain. Any shared gap
beyond ADR-025 receives its own narrow ADR instead of being hidden in the DPP
bundle.

## 5. State-machine and composite decision

### 5.1 Configuration-only prototype

After ADR-025, a prototype may select `authenticated-map` and declare
DPP-shaped collections. It demonstrates infrastructure but does not prove that
clients obeyed DPP lifecycle, issuer, recovery, or standards rules.

### 5.2 Full product provider

The complete product exports a distinct manifested identity, conceptually:

```text
state-machine.id     = dpp-registry
composite-profile.id = dpp-registry-v1
```

Exact wire IDs are frozen during implementation. The custom provider depends on
`appchain-composite` and packages a reviewed, bounded component profile.
Operators cannot discover unrelated component JARs and choose arbitrary order
in YAML. Component identity, version, route, namespace, workflow, quota,
activation interval, and compatibility identity are consensus state as required
by ADR-013.2.

### 5.3 Initial component shape

| Component | Responsibility |
|---|---|
| `dpp-core-v1` | Actor/key state, products, claims, versions, lifecycle events and heads, certificates, recovery claims, policies, and deterministic DPP rules |
| `role-approvals` | Reusable governed proposals and role-aware approvals for configured actions |
| `dpp-publication-v1` | Publication intents, effect ownership, terminal receipts, reconciliation state, and optional notification/publication workflows |

Tightly coupled actor, product, claim, event, and recovery checks stay inside
`dpp-core-v1` initially. Splitting them across isolated components would force
cross-component workflows for most commands without creating a useful reusable
boundary.

Candidate named workflows include actor accreditation, passport publication,
revoke/replace, recovery decisions, and forced milestone publication. A
workflow never calls an ERP, object store, IPFS, Kafka, Cardano service, clock,
filesystem, or network; it only stages state and effect intents.

### 5.4 One root, not component roots

The selected backend produces one post-state root for framework, composite, and
DPP state. Component namespaces prevent collision but are not separately
certified roots. This design rejects mixed MPF/JMT components, per-component
trees, a JMT root stored as an MPF value, and sidecar state mutated before
finality.

If two domains need incompatible proof profiles, they use separate chains or a
future explicitly specified multi-commitment protocol. This ADR defines no such
protocol.

## 6. Commitment-profile choices

### 6.1 Profile matrix

| ADR-025 profile | DPP primary fit | Cardano verification path | Release position |
|---|---|---|---|
| `yano-mpf-blake2b256-v1` | Current product/status/certificate proofs, existing Yano compatibility, and applications needing the qualified Cardano/Aiken-oriented MPF path | Direct qualified MPF proof against the authoritative anchor root, plus DPP policy and replay binding | Required first DPP profile |
| `classic-radix16-blake2b256-v1` | Large update-heavy registries, native JMT versions, retained historical point proofs, and off-chain verification without a ZK requirement | Root is anchored; native point proof remains off-chain until a separate Cardano verifier is specified and qualified | Optional after full composite qualification and benchmarks |
| `zeroj-poseidon-jmt-v1` | Private eligibility, membership, non-membership, or DPP-specific property/transition statements proved through ZeroJ | Groth16 proof verified by a Plutus V3 application validator against the authoritative Poseidon JMT root | Optional after circuits, ceremony, audit, budgets, and public-network tests |

### 6.2 Selection and qualification rules

- The commitment profile is selected in chain genesis and is part of identity.
- It cannot be changed by editing one member's configuration.
- It cannot be selected per collection, tenant, component, command, or proof.
- Every proof envelope and anchor identifies the exact canonical profile.
- A 32-byte root is never decoded by guessing its profile.
- A classic JMT proof is not a Poseidon witness and cannot use the ZeroJ
  verification key.
- The DPP logical-state conformance suite runs against every advertised profile.
- A profile is not DPP-qualified merely because its underlying tree passes unit
  tests. The composite, workflows, snapshots, catch-up, restart, proofs,
  anchors, effects, and projectors must be qualified with it.

### 6.3 MPF-first release

The first complete DPP profile uses `yano-mpf-blake2b256-v1` because Yano's
existing integration is MPF-based, the direct Cardano/Aiken-oriented path has
the clearest initial product semantics, and one baseline is needed for DPP
commands, state, queries, effects, portal behavior, and acceptance tests before
adding backend variability.

This is release ordering, not a claim that MPF is always faster or smaller.
Representative DPP workloads must benchmark all qualified profiles.

### 6.4 Poseidon privacy boundary

Poseidon JMT is selected only when a concrete proof statement justifies its
operational and cryptographic cost. The first target is an on-demand fact or
property proof against a threshold-finalized, L1-anchored root—not a claim that
every DPP block is a validity-proof rollup.

A DPP-specific circuit may need to bind:

- a product record;
- a private claim commitment;
- an issuer/accreditation record;
- a certificate-status record;
- an active policy record;
- validity or expiry inputs;
- a public or pseudonymous product identifier;
- a recipient, action, or claim nullifier; and
- the exact root, circuit fingerprint, verification key, and `ScriptContext`.

That multi-key statement is a separate reviewed deliverable. ADR-025's
operation-specific JMT circuits do not automatically establish it.

## 7. Canonical DPP state model

### 7.1 Logical collections

The physical namespace follows the selected top-level machine and
ADR-025/composite codecs. These names express logical ownership only:

| Logical collection | Key shape | Value purpose |
|---|---|---|
| `actors` | actor ID | Actor kind, roles, accreditation, current key references, validity, status, and revision |
| `actor-keys` | canonical actor ID and key ID | Algorithm, public key, validity interval, rotation/revocation linkage, and status |
| `products` | canonical product ID such as a profile-qualified GTIN | Current passport version, issuer/controller, manifest commitment, schema, lifecycle status, revision, and optional replacement |
| `product-versions` | canonical product ID and application version | Immutable version metadata and content/claim commitments when permanent addressing is required |
| `claims` | product ID, claim type, and claim ID | Public value, salted/hiding commitment, encrypted-reference commitment, issuer, validity, visibility, and status |
| `events` | deterministic event ID | Product, event type, actor, signature/evidence commitments, sequence, observed-time data, and status |
| `product-heads` | product ID | Last accepted sequence and event digest, current lifecycle state, product revision, and deduplication context |
| `certificates` | certificate ID | Type, subject, issuer, evidence commitment, validity, and active/revoked/consumed status |
| `recovery-claims` | claim ID or nullifier | Subject/material batch, canonical quantity or commitment, certificate, issuer, and issued/redeemed/revoked status |
| `policies` | policy/profile ID and version | Canonical schema, authorization, lifecycle, units, disclosure, and threshold-policy commitments |
| `publication` | intent or subject ID | Target profile, requested artifact, effect/result status, receipt commitment, reconciliation state, and revision |

Collection labels do not create physical key locality. MPF and JMT hash the
complete canonical key, so range, prefix, and search queries require a derived
index.

### 7.2 Common entry envelope

Every mutable DPP record uses a canonical envelope binding at least:

```text
entry schema version
record type
logical status
revision
controller or authorizing actor, when applicable
canonical payload or payload commitment
domain profile/schema identity
creation and last-transition app heights
```

Application revision starts at one and advances exactly once per successful
logical mutation. Compare-and-set may require the expected revision, expected
payload commitment, or both.

### 7.3 Product identity and status

The product key remains stable across normal passport versions. The initial
conceptual status set is:

```text
DRAFT
ACTIVE
INACTIVE
REPLACED
REVOKED
RETIRED
```

The implementation contract freezes allowed transitions and authorization.
`REPLACED` may bind a successor product or passport version. `REVOKED` and
`RETIRED` are not synonyms. A status transition never physically erases
historical proof material.

### 7.4 Versioning

The normal version model is:

```text
stable product key
+ application revision/current passport version
+ proof at finalized app height H
```

JMT version remains appchain height as required by ADR-025; it is not the same
as a manufacturer-declared passport version.

`product-versions` is used when every old application version must remain
current-addressable even if historical tree versions are pruned. Otherwise,
retained historical proofs and archived evidence bundles are preferred over
unbounded duplicate state.

### 7.5 External content

Consensus state stores commitments and bounded references, not arbitrary
passport documents, photographs, PDFs, telemetry streams, or proprietary ERP
records. A content record may bind:

- canonical digest and hash profile;
- byte length and media/schema type;
- immutable object/CID reference or a commitment to a confidential locator;
- non-secret encryption/profile metadata;
- issuer signature or signature commitment;
- availability/replication receipt commitment; and
- lifecycle status.

A state proof authenticates those bytes. It does not prove that an external
object remains available.

## 8. Commands and deterministic workflows

### 8.1 Candidate command catalogue

These names describe v1 scope but are not frozen wire topics yet:

| Command | Deterministic outcome |
|---|---|
| `actor.register` | Register a domain actor and initial key/roles under bootstrap or governed policy |
| `actor.rotate-key` | Activate a new actor key and link or schedule retirement of the prior key |
| `actor.revoke-key` | Revoke an actor key at a deterministic activation point |
| `actor.change-roles` | Apply an accepted accreditation or role change |
| `product.register` | Create a stable product record and initial passport revision |
| `passport.publish-version` | Advance the expected product/passport revision and commit new manifest/claim metadata |
| `passport.replace` | Mark the record replaced and bind its successor according to policy |
| `passport.revoke` | Revoke a passport under an authorized reason and policy |
| `passport.retire` | Mark end-of-life retirement under lifecycle policy |
| `claim.put` | Create or update an authorized public value or private commitment |
| `claim.revoke` | Replace an active claim with an explicit revocation record |
| `event.append` | Insert a unique event and atomically advance the expected product head |
| `certificate.issue` | Create a unique certificate under issuer/type policy |
| `certificate.revoke` | Revoke an active certificate under policy |
| `recovery.issue` | Create a unique recovery entitlement or certificate state |
| `recovery.redeem` | Atomically consume an active recovery claim and apply a bounded aggregate transition |
| `recovery.revoke` | Revoke an unconsumed recovery record under policy |
| `publication.request` | Create an idempotent, finality-gated publication intent |
| `publication.reconcile` | Incorporate a qualified result or governed reconciliation decision |

### 8.2 Atomic product-event transition

`event.append` demonstrates why generic CRUD does not define the full product.
In one candidate-state transaction it must:

1. decode a bounded canonical domain envelope;
2. verify the actor signature under the key valid for the deterministic policy
   reference;
3. verify actor role and product authorization;
4. verify event ID uniqueness and replay policy;
5. load the product and product head;
6. require the expected product revision and next event sequence;
7. validate the lifecycle transition and canonical units/fields;
8. write the immutable event record;
9. update the product head and permitted current product status;
10. optionally create a bounded publication intent; and
11. either stage every change or make no logical change.

### 8.3 Recovery anti-double-counting

A recovery flow prevents the same canonical claim from being accepted twice by
using a deterministic ID/nullifier, put-if-absent issuance, expected-status
compare-and-set on redemption, certificate and actor validation, canonical
quantities/units, and atomic claim/certificate/aggregate changes.

This proves non-duplication inside the defined DPP identity domain. It does not
prove physical material exists, was measured correctly, or was not registered
under another external identity. Accreditation, inspection, device, oracle, and
dispute rules remain necessary.

## 9. Domain actors, keys, and authorization

### 9.1 Outer submitter versus domain author

The appchain sender, REST credential, sequencer, and voting member are not
automatically the manufacturer, shipper, auditor, repairer, recycler, or device
named by an event.

Every governed DPP mutation carries an inner signed envelope containing at
least:

```text
envelope/profile version
chain and DPP domain identity
command/topic identity
canonical payload commitment
actor ID and key ID
deterministic command/event ID or nonce
expected revision/sequence where applicable
claimed observed time and interpretation, when applicable
signature algorithm and signature
```

The state machine re-verifies the signature during deterministic execution
against authenticated actor/key state. Admission may reject malformed
structure, but execution remains authoritative for current key, role,
revocation, replay, and state-dependent checks.

### 9.2 Deterministic validity

Key and policy validity is evaluated against a deterministic chain reference,
such as app height or the block's qualified L1 reference. It never uses local
wall-clock time. Claimed physical observation time is signed business data;
consensus enforces bounds only when an exact deterministic rule belongs to the
DPP profile.

### 9.3 Governance and approvals

The profile declares which actions require direct controller authority, an
accredited role, an active certificate, appchain governance, a role-approval
threshold, or a named workflow consuming an accepted proposal.

Examples include issuer accreditation, actor revocation, disputed-status
override, recovery-policy change, and activation of a packaged future DPP
profile. An effect executor never decides the DPP transition; it acts on a
finalized intent and returns a result.

## 10. Canonicalization and interoperability

### 10.1 Canonical bytes

Raw JSON text, default Java serialization, ambient Jackson configuration, map
iteration order, locale-sensitive formatting, and platform timestamps are
forbidden as consensus-critical encodings.

Each supported DPP profile freezes:

- command and state CBOR/CDDL;
- JSON/JSON-LD normalization when source formats use them;
- schema identifiers and schema digest rules;
- Unicode normalization;
- integer, decimal, rational, unit, and rounding rules;
- timestamp representation and time-zone rules;
- absent, null, empty, unknown, and redacted semantics;
- ordered versus set-valued arrays;
- GTIN, company, serial, batch, certificate, and actor identifier rules;
- event ID and replay-key derivation;
- content, claim, event, certificate, and policy hash domains;
- salts/commitments for low-entropy private values; and
- source-signature preimages.

### 10.2 Standards posture and golden vectors

The first profile may map to the external blueprint, GS1 Digital Link, and a
selected EPCIS version, but it must not claim compliance until applicable
specifications, conformance criteria, and tests are satisfied. A later external
profile is never silently reinterpreted under the same DPP identity.

Portable golden vectors cover every command and state type, invalid/boundary
encodings, identifier normalization, signatures, commitments, revisions,
lifecycle transitions, event/head updates, recovery flows, and logical-state
equivalence across commitment profiles. At least one independent client
implementation is required before an interoperability claim.

## 11. Privacy and selective disclosure

### 11.1 Replication boundary

Voting members replicate consensus state. Plaintext stored in DPP state is
visible to those operators even when a later Poseidon proof hides its witness
from a Cardano validator.

Confidential data therefore uses one of:

- a salted/hiding commitment in state and encrypted content outside state;
- bounded ciphertext in state only under an explicit key and retention model;
  or
- a commitment to a private record controlled by an external disclosure
  service.

Secrets, decryption keys, object-store credentials, API tokens, and KMS/HSM
handles are never consensus state or genesis configuration.

### 11.2 Baseline selective disclosure

The baseline non-ZK flow is:

1. canonicalize a claim;
2. add sufficient random salt or use an approved hiding commitment;
3. commit the claim record into authenticated state;
4. disclose the claim and opening only to an authorized verifier;
5. return the native state proof and qualified root evidence; and
6. verify the opening, state proof, finality, and optional L1 anchor.

This reveals the selected claim to that verifier, but a point proof does not
reveal sibling claim values.

### 11.3 Poseidon/ZeroJ flow

The advanced flow keeps private inputs in a prover witness and exposes only the
application-defined outputs. The prover service is outside consensus and may be
operated by a holder, issuer, regulated service, or another approved party.

Release requires exact Poseidon encodings, a bounded DPP statement, real
JMT-path witness generation, circuit and verification-key fingerprints,
production ceremony and review, Cardano budget measurements, replay/nullifier
and `ScriptContext` binding, and failure behavior that never falls back to an
unproved claim.

### 11.4 Privacy non-goals

This ADR does not define anonymous membership, private consensus execution,
automatic GDPR compliance, deletion of finalized commitments, a universal
JSON-LD circuit, or proof that private content remains available. Personal data
should be excluded from immutable public state wherever possible. Even linked
hashes and commitments can require legal review.

## 12. Query, proof, projection, and portal model

### 12.1 Node query surface

The node exposes bounded committed queries for exact product, version, actor,
key, role, claim, certificate, event, product head, recovery claim, publication,
and DPP profile records. It does not become a full-text, range, analytics,
geospatial, or timeline database.

### 12.2 Root-pinned proof response

A proof-aware response binds one snapshot:

```text
chainId and genesisId
DPP machine/profile and schema version
app height and block hash
state-commitment profile and root
logical collection/key/status/revision
value or commitment permitted for the caller
native proof type and encoding
finality certificate or trusted reference
L1 anchor reference and anchored-through height, when requested
```

Fetching data at one root and a proof at another is rejected. Asking one
untrusted server for both its claimed root and proof establishes only internal
consistency unless the client independently verifies finality or an L1 anchor.

### 12.3 Projection and cache

The normal public path is:

```text
finalized block sink
  -> idempotent DPP projector
  -> query/search database
  -> cache/CDN
  -> GS1 resolver and portal
```

Projector idempotency binds at least `(chainId, height, blockHash)`. Rebuild and
reconciliation verify the finalized block chain and state-root checkpoints.

A cache entry identifies the exact product revision, finalized height, proof
root/profile, L1-anchored-through height, source cursor, and revalidation policy.

### 12.4 Verification labels

Portal and status surfaces distinguish, at minimum:

- `DOMAIN_SIGNATURE_VALID`;
- `DPP_RULES_ACCEPTED`;
- `APPCHAIN_FINALIZED`;
- `STATE_PROOF_VERIFIED`;
- `L1_ANCHORED_THROUGH_HEIGHT`;
- `ZK_STATEMENT_VERIFIED`, when applicable;
- `EXTERNAL_CERTIFICATION_REPORTED`, when applicable; and
- current DPP lifecycle status.

A record newer than the latest anchor is never labelled Cardano-anchored.

## 13. L1 anchoring and optional Cardano artifacts

### 13.1 Default aggregate anchor

The default uses the framework script anchor and includes or transitively binds:

```text
chainId
genesisId
DPP state-machine/profile identity
state-commitment profile
fromHeight / toHeight
tip block hash
tip state root
membership/finality epoch
anchor schema/version
```

One anchor covers every DPP record and finalized command bound by that tip. The
anchor wallet and script are deployment concerns; an individual DPP record does
not receive its own wallet merely because it belongs to the registry.

### 13.2 Cadence and milestones

The deployment defines a bounded periodic cadence. A high-value workflow may
request or wait for a milestone anchor through existing finality-gated
mechanisms, but deterministic application execution never submits a Cardano
transaction directly.

Consumers may accept threshold-finalized data immediately, accept only records
at or below the latest L1 checkpoint, require a maximum anchor lag, or require a
named milestone checkpoint.

### 13.3 Profile-specific verification paths

- **MPF:** an application validator may verify the qualified point proof against
  the authoritative root plus DPP policy and replay conditions.
- **Classic JMT:** Cardano stores the qualified root while point proofs remain
  off-chain unless a separate on-chain verifier is specified and qualified.
- **Poseidon JMT:** a Plutus V3 application validator verifies the exact ZeroJ
  DPP statement and checks its public root against the authoritative anchor.

### 13.4 Optional product artifacts

Later publication profiles may support an immutable locator asset, CIP-68 or a
future DPP reference datum/token, product status publication, a milestone
certificate asset, or a recovery entitlement representation.

They require separate deterministic on-chain formats, wallet safety, fee/change
policy, confirmation and rollback handling, idempotency, reconciliation, and
public tests. They do not replace canonical appchain state unless a later ADR
explicitly changes the trust model.

## 14. Effects and external integrations

### 14.1 Effect rule

No deterministic DPP component performs external I/O. It stages bounded state
and an `EffectIntent`; the framework releases the intent after its finality
gate. The executor returns a durable result for deterministic incorporation.

Potential actions include:

- publish or promote an already staged immutable object;
- pin or verify an existing IPFS CID;
- publish a business integration record to an allowed Kafka target;
- notify a configured ERP/webhook alias;
- submit an optional DPP Cardano publication; and
- reconcile external publication state.

Callers select configured aliases, not arbitrary endpoints or credentials.
Every action has a stable idempotency key, bounded payload/reference, digest and
size constraints, retry policy, terminal status, and receipt schema.

### 14.2 Data availability

An L1 anchor and state proof authenticate an object commitment, not object
availability. A profile advertising durable evidence defines storage
replication or WORM policy, digest verification, availability health,
republication/recovery, retention, and representation of missing content
without rewriting historical truth.

### 14.3 Publication status

The effect/publication lifecycle may include:

```text
NOT_REQUESTED
PENDING_FINALITY
READY
RUNNING
SUCCEEDED
RETRYABLE_FAILURE
PARKED
TERMINAL_FAILURE
RECONCILED
```

Domain and publication statuses remain distinct. A valid passport can exist
while an optional notification retries; another profile may require successful
immutable storage publication before activating the passport. That policy is
deterministic and profile-specific.

## 15. Trust, truth, and security boundaries

### 15.1 What the system can prove

Subject to retained data and correct verification, it can prove that:

- a particular domain-signed command was accepted under a named DPP profile;
- the configured quorum finalized an ordered block containing it;
- a canonical record had a stated value/status at a finalized height;
- the record belongs to the selected MPF/JMT root;
- an L1 anchor committed the appchain tip through a stated height; and
- a qualified ZeroJ proof satisfied its exact public statement, when used.

### 15.2 What it cannot prove alone

It cannot cryptographically prove that a physical product matches its tag, a
manufacturer's statement is truthful, a sensor was calibrated and attached to
the product, material was actually recovered, a certification authority
followed its procedures, external content remains available, or a regulator
accepts the deployment as compliant.

Those claims require accreditation, inspection, device attestation, multiple
sources, oracles, legal governance, audits, disputes, and availability controls.

### 15.3 Principal threats

The threat model and tests cover forged/replayed actor envelopes, stale keys or
roles, malicious ordering within the existing consensus model, stale revisions,
duplicate recovery claims, canonicalization ambiguity, low-entropy commitment
guessing, unauthorized disclosure, cache/root/proof mix-and-match,
anchor-profile confusion, duplicate effects, compromised storage references,
unbounded work/state/circuits, and silent loss of advertised proof history.

## 16. Genesis, identity, and evolution

### 16.1 Genesis-bound identity

The canonical identity binds at least:

- state-machine and composite-profile IDs;
- executable component catalog/profile digest;
- DPP domain/schema/canonicalization profile;
- state-commitment profile and format fingerprint;
- state-layout and collection identities;
- command and signature profiles;
- actor bootstrap/governance policy;
- product/lifecycle/status policy;
- recovery/certificate policy when enabled;
- relevant runtime bounds and effect quotas;
- initial membership, threshold, sequencing/finality; and
- L1 anchor-policy identity.

Endpoints, credentials, buckets, brokers, private keys, and secrets are not
genesis state. Consensus may bind non-secret target aliases or policy IDs whose
concrete endpoints are resolved locally.

### 16.2 Evolution

Compatible future behavior activates only through an exact packaged profile and
the governance mode selected at genesis. An admin endpoint or single-node YAML
edit cannot redefine DPP semantics.

Changes that reinterpret old commands, reuse an incompatible namespace, alter
canonical keys, or switch commitment profile require an explicit migration or
new chain. A commitment-profile switch is never an in-place composite upgrade.

### 16.3 Historical retention

DPP retention may be much longer than rollback retention. The product profile
distinguishes block/replay retention, current state, historical MPF/JMT nodes,
immutable version/event records, archived proof bundles, and external object
retention. A pruned proof returns unavailable; it is never recreated against a
newer root and labelled historical.

## 17. Proposed module and artifact boundary

No module is created by this ADR. The post-ADR-025 target is conceptually:

```text
appchain-dpp-contracts
  commands, state/query DTOs, codecs, signatures, profile descriptors,
  proof envelopes, verifier contracts, and golden vectors

appchain-dpp
  manifested custom composite provider, dpp-core, workflows, queries,
  publication component, conformance fixtures, health, and metrics

appchain-dpp-client
  submission helpers, domain signing, committed queries, proof and anchor
  verification, and projection support

appchain-dpp-showcase
  packaged three-node example, sample identities/products/events/recovery,
  scripts, curl examples, load/soak flow, and complete demo/operator docs

appchain-effects-cardano-dpp          (optional later)
  hardened product/certificate publication and reconciliation

appchain-dpp-onchain                  (optional later)
  Aiken validators/policies, codecs, and golden vectors

external or separately packaged services
  gateway, object-store integration, finalized projector, query DB,
  GS1 resolver, cache/CDN, portal, and optional ZeroJ prover
```

Contracts and verifier artifacts do not depend on node runtime or credentials.
A JVM plugin follows the existing dynamic bundle rules. Native support requires
inclusion and testing at native-image build time.

## 18. Implementation prerequisites and delivery plan

### 18.1 Hard prerequisite: ADR-025 completion

DPP implementation does not begin until ADR-025 has delivered, at minimum:

- accepted authenticated-map commands, entries, collections, and key codecs;
- MPF compatibility and logical-state conformance;
- backend-neutral prepare, discard, and atomic final commit behavior;
- genesis/profile identity and retained-state mismatch checks;
- profile-tagged current and historical proof envelopes;
- restart, catch-up, snapshot, restore, integrity, and pruning behavior;
- multi-member matching-root tests; and
- the qualification status required for each profile the DPP phase intends to
  advertise.

This ADR may be refined during ADR-025 work, but no DPP wire ID or production
claim is frozen ahead of that substrate.

### 18.2 Phase D0 — current standards and contract freeze

1. Re-evaluate current DPP, GS1, EPCIS, Cardano, and applicable regulatory
   specifications; do not assume blueprint v0.1 remains final.
2. Select the first bounded DPP profile and honest conformance posture.
3. Freeze identifiers, CBOR/CDDL, signatures, lifecycle rules, statuses, units,
   commitments, queries, proof envelopes, and golden vectors.
4. Define threat, privacy, retention, availability, and accreditation models.
5. Benchmark representative logical workloads before selecting non-MPF release
   targets.

### 18.3 Phase D1 — MPF DPP core and contracts

1. Implement `appchain-dpp-contracts` and independent vector tests.
2. Implement deterministic actor/key, product, claim, event/head, certificate,
   recovery, and publication state in `dpp-core-v1`.
3. Implement signatures, authorization, replay protection, revisions,
   lifecycle transitions, and bounded atomic mutations.
4. Export a distinct DPP provider with an MPF-only first profile.
5. Run state-machine, composite, replay, restart, rollback/reapply, snapshot,
   catch-up, and hostile-input tests.

### 18.4 Phase D2 — proofs, gateway, projection, and reference portal

1. Implement the DPP client/verifier and root-pinned point-proof flow.
2. Build a gateway for canonicalization, domain signing, content staging, and
   submission.
3. Build an idempotent finalized-block projector and current/timeline query
   model.
4. Build a GS1/QR reference portal with explicit finality/anchor labels.
5. Demonstrate replacement, revocation, key rotation, certificate invalidation,
   event history, cache invalidation, and proof verification.

### 18.5 Phase D3 — effects and aggregate L1 demonstration

1. Integrate approved object/IPFS/Kafka effect capabilities through target
   aliases and durable receipts.
2. Exercise crash, retry, reconciliation, and unavailable-content behavior.
3. Anchor the qualified MPF DPP root on Cardano and verify the complete proof
   chain.
4. Package the showcase, scripts, documentation, and load/soak scenarios.

### 18.6 Phase D4 — classic JMT profile

1. Run the complete DPP logical conformance suite on
   `classic-radix16-blake2b256-v1`.
2. Verify height/version mapping, history, pruning, snapshot/restore, restart,
   catch-up, and failed-candidate discard through the composite.
3. Benchmark writes, reads, proofs, database size, pruning, and recovery against
   MPF.
4. Advertise it only when its measured benefit and off-chain proof path justify
   the operational profile.

### 18.7 Phase D5 — Poseidon JMT and DPP-specific ZK

1. Select one bounded, valuable DPP statement rather than a universal circuit.
2. Freeze its public inputs and application-policy binding.
3. Implement real JMT witness generation and cross-project golden vectors.
4. Complete ceremony, review, negative tests, Cardano budget measurements, and
   public-network execution.
5. Add prover/portal flows and confirm the state model does not leak intended
   private plaintext to appchain members.
6. Advertise the profile only after the complete release boundary passes.

### 18.8 Phase D6 — optional Cardano product artifacts

1. Establish a product need for a locator, CIP-68 reference, certificate,
   recovery asset, or other Cardano representation.
2. Specify it in a separate Cardano-effect/on-chain ADR.
3. Implement wallet safety, policy, idempotency, confirmation, rollback,
   reconciliation, and verifier tooling.
4. Retain root-only operation as a supported profile.

## 19. Verification and acceptance criteria

### 19.1 Determinism and consensus

- Independent codec implementations reproduce golden vectors.
- Every member derives identical logical writes and profile-specific roots.
- Malformed finalized messages remain deterministic and bounded.
- No DPP apply/workflow path reads time, randomness, environment, filesystem,
  sidecar databases, DNS, or network.
- Failed proposals leave no DPP tree, effect, index, or publication residue.
- Final block, DPP state, commitment nodes/root, effects, indexes, and tip commit
  atomically under ADR-025.

### 19.2 Authorization and lifecycle

- Actor signatures, rotation, revocation, validity, roles, and replay protection
  have positive, negative, historical, restart, and catch-up coverage.
- Product registration, update, replacement, revocation, inactivity, and
  retirement transitions are enumerated and tested.
- Event/head updates reject stale revision, wrong sequence, duplicate ID,
  invalid actor, and invalid lifecycle transition atomically.
- Recovery/certificate tests reject duplicate issuance or redemption,
  inconsistent units, stale state, invalid role, and revoked authority.
- Approval-gated commands cannot bypass the accepted workflow through a direct
  alternate topic.

### 19.3 Proofs and history

- Product, actor, claim, event, certificate, recovery, status, and supported
  non-membership/revocation proofs follow each profile's exact semantics.
- Proofs bind chain, genesis, DPP profile, commitment profile, height, block,
  root, key, status, and finality.
- Cross-profile proof decoding and root substitution fail closed.
- Historical proofs resolve to the stated retained height; pruned proofs return
  unavailable.
- Cache/projector data cannot mix value, proof, finality, and anchor evidence
  from different heights undetected.

### 19.4 Effects, L1, and ZeroJ

- Effects release only after their finality gate.
- Crash-before-send, send-before-ack, duplicate retry, timeout, parking,
  reconciliation, and terminal failure are tested.
- External actions use configured aliases and idempotency keys.
- MPF DPP proofs verify against the exact authoritative Cardano anchor root in
  the supported direct-proof example.
- Classic JMT makes no direct-on-chain point-proof claim without a qualified
  verifier.
- Poseidon verifies the exact circuit, public root, key, replay/nullifier, and
  application transaction context.
- Threshold anchoring is never labelled a whole-block ZK validity proof.

### 19.5 Scale, operations, and product honesty

- Every published product-count, update-rate, proof-latency, anchor-lag,
  scan-latency, catch-up, restart, and storage claim has a reproducible workload
  and report.
- A three-node default cluster and larger supported member counts reproduce
  roots and recover from restart/catch-up.
- Status exposes DPP profile, commitment profile, root, height, finality, anchor
  lag, projector lag, proof-history floor, effect health, and integrity.
- The showcase includes setup, submission, approval, event, recovery, proof,
  anchoring, restart, stop, load, soak, and cleanup documentation.
- Documentation separates blueprint mapping from standards compliance,
  cryptographic inclusion from physical truth, member visibility from private
  disclosure, and optional Cardano assets from canonical appchain state.

## 20. Non-goals and poor fits

The DPP authenticated registry is not:

- a replacement for ERP, PIM, PLM, MES, EPCIS, object storage, relational
  queries, search, analytics, or a CDN;
- proof that a real-world statement is truthful;
- a permissionless public network by default;
- automatic regulatory, GS1, or EPCIS compliance;
- an automatic Cardano asset per product;
- a tree or appchain per product, tenant, event type, or topic;
- confidential merely because it uses Poseidon;
- an authenticated range-query engine;
- an MMR replacement for every log use case;
- a whole-block validity rollup;
- an in-place MPF/JMT migration;
- a way to erase finalized public history; or
- a reason to perform external I/O during state execution.

This architecture is a poor fit for a tiny issuer with a handful of immutable
public products and no consortium, update, privacy, or scale requirement. A
direct Cardano anchor may be simpler.

## 21. Alternatives considered

### 21.1 Implement four blueprint stacks literally

Not selected as Yano's default. It duplicates identity, state, versioning,
authorization, proofs, and anchors and may require many L1 transactions.
Direct-L1 variants remain optional when their trust or ecosystem semantics are
specifically required.

### 21.2 Use only generic `authenticated-map`

Selected for a prototype, rejected as the complete product. Generic operations
enforce revisions, uniqueness, ownership, revocation, and batches but do not
define DPP signatures, lifecycle, certificates, units, recovery truth, or
standards semantics.

### 21.3 Build one standalone monolithic DPP machine

Viable but not preferred for product packaging. A coherent `dpp-core` remains
intentionally broad, while the composite safely reuses independent approvals
and publication/result workflows with explicit routes, namespaces, quotas, and
evolution identities.

### 21.4 Dynamically compose through YAML

Rejected. Component order, routes, state ownership, workflows, quotas, and
compatibility are consensus-critical. The custom bundle packages a reviewed
catalog; configuration selects only allowed profiles and local integrations.

### 21.5 Give components or privacy classes different trees

Rejected. Multiple root trust domains complicate atomic workflows, proofs,
anchors, snapshots, and recovery and conflict with ADR-025's chain-wide profile.
Use separate chains when trust domains genuinely differ.

### 21.6 Use an MMR as complete DPP state

Rejected. DPP needs mutable current status, issuer keys, revocation, corrections,
product heads, and certificates. Appchain blocks provide order; the map provides
point-addressed state. A specialized MMR may remain a derived artifact.

### 21.7 Put all passport data on Cardano

Rejected as default because of confidentiality, size, update, cost, and data
governance. Cardano receives qualified roots and optional bounded artifacts;
large content remains in appropriate external storage.

## 22. Consequences

### 22.1 Benefits

- One architecture covers static, selective-proof, lifecycle, recovery, and
  high-throughput scenarios.
- L1 cost is amortized across many products and updates.
- Stable identity and app revisions avoid asset churn for database versioning.
- Actor keys, roles, status, events, certificates, and policies share one
  authenticated snapshot.
- The proof backend can target direct Cardano, versioned off-chain, or ZeroJ use
  without changing DPP command semantics.
- Ordered history and materialized state provide complementary guarantees.
- Composite workflows reuse approvals and effects without moving DPP truth into
  consensus core.
- Projections and portals can scale independently while remaining verifiable.

### 22.2 Costs and risks

- An appchain adds governance, availability, operations, and quorum trust versus
  one direct Cardano transaction.
- DPP schema, canonicalization, actor, lifecycle, privacy, and interoperability
  work is substantial.
- Long proof and content retention may be expensive.
- High-throughput claims require real benchmarks.
- Poseidon adds circuit, prover, ceremony, audit, and Cardano-budget complexity.
- Optional product assets add wallet, fee, policy, rollback, and reconciliation
  work.
- External standards and regulations may change before implementation begins.

## 23. Explicitly deferred decisions

The implementation contract phase decides, from then-current requirements:

- exact machine, component, topic, query, and profile identifiers;
- the first DPP/GS1/EPCIS schema profile and honest conformance claim;
- lifecycle and reason-code taxonomies;
- actor accreditation and bootstrap governance;
- which operations require role approval;
- public/private claim categories and commitment schemes;
- historical retention and archival proof policy;
- representative scale workload and service targets;
- the first DPP-specific ZeroJ statement, if justified;
- whether a locator/CIP-68/certificate/recovery asset is built;
- default anchor cadence and milestone policies; and
- deployment boundaries among gateway, projector, portal, prover, and operator
  services.

The stable decision is the architecture and dependency: after ADR-025, build
DPP as a separately versioned application product using a custom composite, one
chain-wide authenticated commitment, ordered appchain history, periodic
qualified Cardano anchors, and explicit external-service and real-world-truth
boundaries.
