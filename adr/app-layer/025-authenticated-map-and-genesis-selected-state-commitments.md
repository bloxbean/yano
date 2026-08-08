# ADR-025: Authenticated Map and Genesis-Selected State Commitments

## Status

Accepted for phased implementation. Phases 0, 1, 2, 3, and 5 were implemented
against the pre-amendment development baseline, and ADR-025.1 value validation
is implemented. [ADR-025.2](025.2-governed-authenticated-map-authorization-and-console.md)
is accepted for implementation and reopens the unreleased v1 authorization,
command, genesis, composite-state, proof, and console contracts. ADR-025.2
Phases A-D have frozen the final contract and delivered composite assembly,
governed-role authorization, and approval-gated execution. The existing
runtime phase records remain useful baselines, not final v1 qualification,
until ADR-025.2 Phases E-G are complete.

Phase 4 is deferred until the required ZeroJ library is released, and optional
Phase 6 is deferred. CCL `0.8.0-pre5-dev1` remains a development baseline, so
this status is not production qualification.

**Preview-baseline amendment:** ADR-031 supersedes this ADR's vendor-prefixed
commitment-format and proof-encoding identifiers for the first supported
app-chain baseline. References below to `ccl-*` and `zeroj-*` descriptors record
the implemented preview state; supported consensus/wire identifiers describe
the algorithm and format, while CCL/ZeroJ compatibility is conformance metadata.
No preview identifier migration or alias is required.

If accepted and implemented, this ADR supersedes the state-commitment choice in
[ADR-005 §D3](005-yano-app-chain-framework.md) for the supported app-chain
baseline. Its earlier assumption that preview chains might retain their MPF
history is replaced by ADR-031's fresh-start rule.

## Date

2026-08-03

## Related decisions and implementations

- [ADR-005](005-yano-app-chain-framework.md) defines the app-chain block,
  threshold finality, current MPF state commitment, proof model, catch-up, and
  L1 anchoring.
- [ADR-010](010-deterministic-effect-system.md) defines framework-owned effect
  state under the app-chain state root.
- [ADR-013.2](013.2-deterministic-composite-state-machine.md) defines
  deterministic logical namespaces and one atomic state root across composed
  components.
- [ADR-015](015-governed-composite-profile-evolution.md) defines explicit
  governed activation of consensus-affecting application profiles.
- [ADR-016](016-authenticated-appchain-consensus-profile-and-typed-runtime-limits.md)
  authenticates normalized consensus-affecting runtime settings in state.
- [ADR-019](019-reusable-domain-actor-registry-and-role-aware-approvals.md)
  defines reusable actor and role authorization that an authenticated map may
  consume.
- [ADR-025.1](025.1-authenticated-map-value-validation.md) adds optional,
  genesis-bound canonical encoding, declarative schemas, and deterministic
  value-validator plugins to the final v1 contract.
- [ADR-025.2](025.2-governed-authenticated-map-authorization-and-console.md)
  makes `governed-role` and approval-gated mutation part of final v1 through
  one committed composite product and adds its capability-gated console.
- [ADR-025.2 Phase A](025.2-phase-a-contract-and-work-bounds.md) records the
  final v1 CBOR, replay namespaces, work bounds, and crypto-cap measurement.
- [ADR-025.2 Phase B](025.2-phase-b-composite-assembly.md) records the committed
  composite assembly and governed capability gates.
- [ADR-025.2 Phase C](025.2-phase-c-governed-role.md) records actor-governed
  administration and direct-role authorization.
- [ADR-025.2 Phase D](025.2-phase-d-approval-gated-execution.md) records
  approval-gated execution, bounded reclamation, and operational results.
- [ADR-024](024-appchain-full-support-review-readiness-and-roadmap.md) identifies
  the remaining need to authenticate all consensus-affecting machine settings
  and to keep block, state, recovery, and proof identities aligned.
- Phase qualification records:
  [Phase 0](025-phase-0-contract-measurement-and-dependency-gates.md),
  [Phase 1](025-phase-1-authenticated-map-over-mpf.md),
  [Phase 2](025-phase-2-backend-neutral-prepared-commitment-runtime.md),
  [Phase 3](025-phase-3-classic-jmt-runtime.md), and
  [Phase 5](025-phase-5-proof-bundles-and-developer-experience.md).
- Cardano Client Lib (CCL) development release
  [`v0.8.0-pre5-dev1`](https://github.com/bloxbean/cardano-client-lib/releases/tag/v0.8.0-pre5-dev1)
  is the temporary implementation baseline for current MPF and Blake2b-256 JMT
  work. It is not the eventual production dependency; replace it with a proper
  CCL release before production qualification.
- ZeroJ supplies the host implementation for Yano's
  `jmt-poseidon-bls12381-v1` commitment profile, operation-specific JMT
  circuits, and the Cardano Plutus V3 Groth16 verification path. Its current
  dependency-specific host descriptor is `zeroj-poseidon-jmt-v1`.

The number is local to the `adr/app-layer` series.

## 0. In plain words

Yano already has a useful `kv-registry`, but it is deliberately small: one
keyspace, first-writer ownership, put/delete, and values authenticated by the
framework's MPF root.

This ADR proposes a richer, backend-neutral **authenticated map** product with:

- one app-chain-wide authenticated state tree;
- multiple fixed logical collections inside that tree;
- versioned canonical entries;
- a closed five-mode collection authorization catalog: `open`, `owner`,
  `member`, `governed-role`, and `approval`;
- optional genesis-bound value encoding, schema, and plugin validation;
- put-if-absent, compare-and-set, transfer, revoke, and atomic batch semantics;
- current and historical point proofs;
- finality and L1-anchor proof bundles; and
- a commitment profile selected once in app-chain genesis.

The three initial commitment choices are:

1. Cardano-compatible Blake2b-256 MPF;
2. classic radix-16 Blake2b-256 JMT; and
3. ZeroJ Poseidon JMT for compact ZeroJ proofs verifiable by Cardano Plutus V3.

The application protocol is independent of that choice. Applications submit
commands such as “compare-and-set collection C, key K”; they do not submit MPF
or JMT operations. The selected commitment engine determines how the resulting
logical state is committed and proved.

The finality protocol does not change. In a three-member chain with threshold
two, the sequencer proposes a block containing the selected backend's state
root, every member independently re-executes the block and recomputes that
root, and two matching member signatures finalize it. When JMT is selected, a
JMT root replaces the MPF root in the backend-neutral `stateRoot` field; it
does not replace or weaken threshold consensus.

```text
ordered app messages
        |
        v
deterministic authenticated-map transition
        |
        v
selected commitment profile computes stateRoot
        |
        v
members independently reproduce stateRoot
        |
        v
configured n-of-m finality certificate
        |
        v
L1 anchor of chain/genesis/height/block/state identity
```

## 1. Problem

### 1.1 The current KV registry is useful but intentionally narrow

The current `kv-registry` provides a good demo and lightweight registry:

- one byte-keyed namespace;
- first writer becomes owner;
- only the owner can update or delete;
- optional raw/CBOR/UTF-8 value validation; and
- generic MPF inclusion/exclusion through the app-chain proof API.

Production registry use cases commonly need more:

- several collision-free logical collections;
- fixed collection schemas and size limits;
- different collection authorization policies;
- optimistic concurrency through revisions or compare-and-set;
- atomic changes spanning several keys or collections;
- explicit revocation history rather than destructive disappearance;
- portable proof responses that identify the commitment profile;
- a proof bundle tied to threshold finality and an independently obtained L1
  checkpoint; and
- a storage profile appropriate to either direct Cardano proofs, long-lived
  versioned off-chain state, or ZK-enabled Cardano consumption.

Changing `kv-registry` in place would change deterministic replay semantics for
already deployed chains. The richer contract therefore needs a new state
machine identity.

### 1.2 MPF, classic JMT, and Poseidon JMT serve different needs

The current MPF path is well integrated with Yano's candidate-state staging,
atomic block commit, historical root lookup, client proof verification, and
Cardano/Aiken-oriented proof tooling.

JMT adds a native versioned storage model, structural sharing, stale-node
tracking, and historical point proofs. CCL provides a stable classic
Blake2b-256 profile and permits custom named profiles. ZeroJ uses that profile
extension to define a circuit-friendly Poseidon JMT with inclusion,
non-inclusion, update, insertion, and tombstone circuits.

These roots are not interchangeable. The same logical entries produce
different roots under MPF, classic JMT, and Poseidon JMT. A 32-byte root is
meaningful only together with its exact commitment profile.

### 1.3 A state machine plugin cannot safely persist its own sidecar JMT

Yano executes a proposed block against staged state before members provide a
finality certificate. A failed or superseded proposal must be discarded
without changing durable state.

An ordinary plugin that calls a JMT API which commits immediately would mutate
state before finality. Storing only the JMT root in MPF while persisting JMT
nodes in a side database also creates a split atomicity boundary: a crash can
commit the root without the nodes, or the nodes without the finalized block.

Embedding JMT nodes as ordinary values inside MPF would create JMT-inside-MPF:
double commitment, additional write amplification, nested proofs, and no clear
benefit for the common case.

State commitment selection must therefore be a framework/runtime concern. The
authenticated-map state machine supplies deterministic logical mutations; the
runtime-selected commitment backend stages, proves, commits, snapshots, and
recovers them.

### 1.4 A map root alone is not a trusted application fact

A valid Merkle proof establishes a statement relative to a supplied root. It
does not establish that the root belongs to the intended chain, height,
application profile, or finality epoch.

Every proof flow must bind:

- chain identity;
- genesis identity;
- app-chain height and block hash;
- commitment-profile identity;
- state root;
- threshold finality certificate; and
- when claimed, the exact Cardano L1 anchor.

For ZeroJ, the application circuit and Cardano validator must additionally bind
the business statement: key/value or policy result, recipient, amount,
nullifier/replay domain, expiry, and relevant transaction context. A root-only
inclusion circuit otherwise proves only that the prover knows some included
entry.

## 2. Decision summary

1. Add a new first-party state machine named `authenticated-map`; keep
   `kv-registry` unchanged for compatibility and lightweight use.
2. Define authenticated-map commands and logical state independently of MPF or
   JMT.
3. Use one authenticated state tree per app chain. Logical collections are
   namespaces in that tree, not separate trees.
4. Select exactly one state-commitment profile in app-chain genesis. The first
   profile set is:

   ```text
   mpf-blake2b256-v1
   jmt-blake2b256-v1
   jmt-poseidon-bls12381-v1
   ```

5. Make the selection immutable for the lifetime of the chain generation. A
   local configuration edit, governed map command, or restart cannot change
   it.
6. Include the normalized commitment-profile identity in the canonical genesis
   identity, consensus profile, status surface, snapshots, proof bundles, and
   L1 anchor payload/datum.
7. Keep the app-block `stateRoot` concept backend-neutral. It contains the MPF
   or JMT post-state root selected by genesis.
8. Keep finality backend-neutral. Followers independently reproduce the
   selected root before voting; configured threshold rules remain unchanged.
9. Bind JMT storage version to finalized app-chain height. The app ledger
   remains the authoritative height-to-root mapping, including heights with no
   logical state change.
10. Require a staged/prepared commitment API. Candidate execution has no
    durable side effects; the finalized block, indexes, selected tree nodes,
    version root, and tip advance share one crash-consistent commit boundary.
11. Use logical tombstones as the backend-neutral deletion/revocation baseline.
    Do not claim a JMT tombstone is a cryptographic non-inclusion proof.
12. Expose profile-tagged proof envelopes and reject cross-profile decoding or
    verification.
13. Keep search, range queries, analytics, and indexes outside the consensus
    tree as rebuildable projections.
14. Qualify profiles incrementally: MPF first, classic JMT second, ZeroJ
    Poseidon JMT third. A profile is not production-approved merely because it
    computes roots or passes a local ZK proof.

## 3. Product boundary: `authenticated-map`

### 3.1 Identity and compatibility

The new state machine has a distinct, versioned identity, conceptually:

```text
state-machine.id      = authenticated-map
state-machine.version = 1
```

It is not a silent upgrade or alias for `kv-registry`. Existing
`kv-registry` blocks, command bodies, state values, and roots keep their current
meaning.

The initial implementation may reuse validated codecs and ownership logic from
`kv-registry`, but replay compatibility is defined by the new contract and
golden vectors rather than implementation reuse.

### 3.2 One tree, several logical collections

An authenticated-map genesis declares a bounded, canonically ordered set of
collections. Example use—not final configuration syntax:

```yaml
app-chain:
  chains:
    - id: product-registry
      genesis:
        state-machine:
          id: authenticated-map
          version: 1
        state:
          commitment-profile: jmt-poseidon-bls12381-v1
        authenticated-map:
          collections:
            - id: products
              authorization: owner
              value-format: cbor
              max-key-bytes: 128
              max-value-bytes: 16384
            - id: issuer-keys
              authorization: governed-role
              authorization-policy: issuer-write
              value-format: cbor
              max-key-bytes: 128
              max-value-bytes: 4096
            - id: revocations
              authorization: approval
              authorization-policy: revocation-release
              value-format: cbor
              max-key-bytes: 128
              max-value-bytes: 4096
```

Collection configuration is consensus-critical. Its canonical bytes or digest
must be part of genesis/profile identity. Node-local collection defaults are
not permitted.

Topics remain message-routing labels. A topic does not create a tree, storage
namespace, retention boundary, or independent consensus unit. Commands name a
collection explicitly.

### 3.3 Canonical namespaced keys

The logical key passed to the commitment backend uses a domain-separated,
length-delimited encoding. The exact codec will be frozen with golden vectors;
the design shape is:

```text
uint8  keyCodecVersion (=1)
uint8  namespaceKind  (framework=0, authenticated-map=1)
uint16 collectionIdLength
bytes  canonicalCollectionId
uint32 applicationKeyLength
bytes  applicationKey
```

Framework state uses its own namespace kind and preserves the existing
reserved `~yano/`, `~fx/`, governance, and L1-observation ownership rules at
the API layer. An application cannot construct a framework key by embedding a
reserved string in its application key.

The collection ID is canonical lowercase ASCII in v1, unique, non-empty, and
bounded. Application keys are opaque bytes subject to the collection bound.
Concatenation with an ambiguous delimiter such as `collection:key` is rejected.

MPF and both JMT profiles hash the complete canonical key according to their
profile. Logical collection prefixes therefore provide collision isolation,
not physical locality or authenticated prefix scans.

### 3.4 Canonical entry semantics

An active entry logically contains at least:

```text
entry schema version
status = ACTIVE | REVOKED
revision
controller/authorization subject, when required
canonical value bytes or value commitment
```

The final binary/CBOR codec, field sizes, and domain tags are implementation
deliverables and receive cross-profile golden vectors. Java serialization,
classpath-dependent codecs, maps with unspecified ordering, and ambient
Jackson module discovery are prohibited in deterministic application.

Revision starts at one and advances exactly once for each successful mutation
of the entry. Compare-and-set can bind the expected revision, expected logical
value hash, or both. The logical value hash is defined by the authenticated-map
contract and does not change with the selected tree hash.

### 3.5 Initial operation model

The proposed v1 operation catalog is:

| Operation | Deterministic meaning |
|---|---|
| `PUT` | Create or update when collection policy permits. |
| `PUT_IF_ABSENT` | Create only when no active or revoked entry exists, according to the collection's frozen recreation policy. |
| `COMPARE_AND_SET` | Update only when expected revision/value commitment matches. |
| `TRANSFER_CONTROLLER` | Change the controller under the current collection policy. |
| `REVOKE` | Replace an active entry with the canonical tombstone/revocation value. |
| `RESTORE` | Restore a revoked key only when the collection's genesis policy permits it. |
| `BATCH` | Apply a bounded, canonically ordered list atomically or make no change. |

Physical deletion is not part of the common v1 contract. MPF can physically
delete, but the current CCL JMT profiles do not. Making deletion depend on the
selected backend would leak storage mechanics into application semantics and
make cross-profile behavior unnecessarily different.

Collections may choose whether a revoked key can be restored or reused. A
revoked JMT key remains cryptographically included with a tombstone value; API
and UI responses must call it `REVOKED`, never `ABSENT`.

### 3.6 Authorization

The final unreleased v1 collection authorization catalog is:

- `open`: any admitted sender may mutate, subject to operation preconditions;
- `owner`: first creator becomes controller and controls later mutation;
- `member`: any active consensus member may mutate;
- `governed-role`: an actor must have a named role under ADR-019 state; and
- `approval`: a referenced finalized approval authorizes the mutation.

The collection kind and, for governed modes, its stable policy ID are fixed by
genesis or by an explicitly cataloged, height-activated governance profile.
Actor and policy contents evolve through authenticated immutable revisions and
governed current pointers. Authorization uses authenticated app-chain state,
the outer signed message sender, and bounded embedded actor/approval evidence,
never a node-local allowlist. Role, policy, approval, or membership lookup is
evaluated at the candidate block's deterministic state point.

[ADR-025.2](025.2-governed-authenticated-map-authorization-and-console.md)
owns the exact policy records, action/signature binding, one-time approval
consumption, internal composite assembly, query/proof surface, and console.

### 3.7 Atomic batches

A batch is application-atomic and has a bounded item count and total byte size.
The canonical contract defines:

- deterministic item order or a requirement that the submitted order is
  preserved;
- rejection of duplicate collection/key pairs unless a future explicitly
  sequential batch form permits them;
- all-or-nothing precondition evaluation;
- one revision transition per affected key;
- deterministic resource accounting; and
- a batch/result commitment suitable for receipts and future ZeroJ circuits.

Several messages in one app block remain sequential according to block order.
An atomic map batch is not the same as the entire app block's mutation batch.

### 3.8 Query model

The authoritative point-query surface should provide:

- current entry by collection/key;
- entry at finalized app height;
- current inclusion, revocation, or non-inclusion proof;
- historical proof at a retained height;
- entry revision and last mutation height;
- operation receipt/status when retained; and
- a full verification bundle.

Prefix scans, full-text search, aggregation, and analytical filters use a
rebuildable projection database. Such responses must identify themselves as
derived and must not be marketed as authenticated range proofs.

## 4. Genesis-selected commitment profiles

### 4.1 Canonical profile identifiers

The initial allowlist is closed:

| Yano profile ID | Backend and commitment | Primary fit |
|---|---|---|
| `mpf-blake2b256-v1` | Current CCL MPF with Blake2b-256 key hashing and Cardano-compatible MPF commitment | Default; direct Cardano/Aiken-oriented proofs and existing Yano compatibility |
| `jmt-blake2b256-v1` | CCL `JmtProfile.classicBlake2b256V1()` radix-16 JMT and its exact persistent format | Large versioned off-chain maps and historical point proofs without a ZK requirement |
| `jmt-poseidon-bls12381-v1` | CCL custom JMT profile supplied by ZeroJ using the reviewed BLS12-381 Poseidon parameter/encoding profile | ZK membership/property and state-transition proofs intended for Cardano Plutus V3 verification |

These are Yano's canonical, genesis-bound commitment-profile identifiers. A
dependency-specific persistence or host descriptor is separate metadata and is
bound by the profile's format fingerprint. For the current temporary dependency
baseline, the mappings are:

| Yano profile ID | Current dependency descriptor |
|---|---|
| `mpf-blake2b256-v1` | Existing CCL MPF format and proof contract in `0.8.0-pre5-dev1`; CCL does not yet expose a matching named MPF descriptor |
| `jmt-blake2b256-v1` | CCL `classic-radix16-blake2b256-v1` in development release `0.8.0-pre5-dev1` |
| `jmt-poseidon-bls12381-v1` | ZeroJ `zeroj-poseidon-jmt-v1` host descriptor |

The dependency descriptor strings are not alternate genesis profile IDs. A
member must resolve the selected Yano profile to the pinned descriptor and
format fingerprint and fail closed on any mismatch.

Aliases such as `mpf`, `jmt`, `poseidon`, or a Java class name are not canonical
genesis values. A configuration layer may offer friendly input aliases only if
it resolves and persists the exact canonical profile before chain creation.

Every profile descriptor fixes at least:

- key hashing and canonical byte-to-field behavior;
- leaf and internal-node commitments;
- empty root and empty child values;
- node/radix/path rules;
- root byte encoding;
- proof forms and proof-wire codec;
- persistence format identity;
- logical/physical deletion capability; and
- implementation/artifact compatibility range.

The root length is 32 bytes for all three initial profiles, but length equality
does not imply compatibility. A Poseidon root is also required to be a
canonical BLS12-381 scalar encoding.

### 4.2 MPF profile

`mpf-blake2b256-v1` preserves the current Yano root algorithm for new
authenticated-map chains choosing MPF.

Properties:

- current Yano staging and atomic RocksDB integration already exist;
- physical key deletion is available;
- current and historical proof lookup is available through app-block roots and
  retained immutable MPF nodes;
- CCL proof verification and Cardano/Aiken-compatible formats are the main
  direct-on-chain path; and
- it remains the default until another profile passes equivalent release gates.

Historical query support is an application-level height-to-root contract even
though MPF does not use JMT's native numeric version model.

### 4.3 Classic Blake2b-256 JMT profile

`jmt-blake2b256-v1` is Yano's canonical profile ID for CCL
`JmtProfile.classicBlake2b256V1()`. The temporary CCL development baseline
`0.8.0-pre5-dev1` persists the dependency descriptor
`classic-radix16-blake2b256-v1`. A later CCL release must reproduce the exact
root, proof, and persistence contract or require a new Yano profile ID and
format fingerprint.

Properties:

- radix-16 JMT with copy-on-write numeric versions;
- inclusion and both supported non-inclusion proof forms;
- structural sharing, stale-node tracking, and pruning support;
- RocksDB/RDBMS implementations qualified only within CCL's documented
  operational profile;
- one logical writer per namespace;
- no native physical deletion in the current contract; and
- no implicit compatibility with the ZeroJ Poseidon JMT circuits.

This profile is appropriate when applications value native versioned storage
and off-chain proof verification but do not require the selected root to be
proved by the current ZeroJ JMT circuit family.

### 4.4 ZeroJ Poseidon JMT profile

`jmt-poseidon-bls12381-v1` is Yano's canonical profile ID for the custom CCL JMT
commitment supplied by ZeroJ. The current ZeroJ host descriptor is
`zeroj-poseidon-jmt-v1`. The commitment is deliberately different from classic
CCL JMT and from ZeroJ's Poseidon MPF profile.

The currently implemented operation-specific circuit family covers:

- inclusion;
- non-inclusion at an authenticated empty terminal;
- non-inclusion against an authenticated different leaf;
- existing-value update (`oldRoot`, `newRoot`);
- insertion at an empty terminal;
- insertion beside a different leaf; and
- update to an explicitly bound tombstone.

The native JMT path is private prover input. A BLS12-381 Groth16 proof remains
constant-size under the current ZeroJ encoding and is verified by a Plutus V3
Groth16 verifier. This is SNARK verification of a statement about a JMT root,
not native JMT path evaluation inside Plutus.

Important boundaries:

- only roots produced by the exact `jmt-poseidon-bls12381-v1` commitment and
  its bound ZeroJ host descriptor can be used; classic Blake2b-256 JMT roots
  cannot be substituted;
- every bounded circuit shape and public-input schema has an exact circuit
  fingerprint and verification key;
- an inclusion template with only `root` public proves existential knowledge,
  not owner, recipient, amount, or authorization policy;
- current operation-specific transition circuits prove one mutation;
- a whole Yano block with several mutations needs several proofs, a fixed
  application circuit chaining intermediate roots, or a future canonical
  bounded batch-transition circuit; and
- production use remains gated by an exact-circuit production ceremony,
  external review, current Cardano protocol-budget measurement, Yaci/public
  network execution, and application-specific `ScriptContext` binding.

ZeroJ is not needed for app-chain consensus. Members calculate the Poseidon JMT
root directly through the host implementation. ZeroJ is used to prove a fact
or transition involving a finalized root to an off-chain or Cardano verifier.

### 4.5 Why Poseidon MPF is not a fourth v1 option

ZeroJ also has a Poseidon MPF profile, but this ADR intentionally starts with
the three profiles requested above. Adding another commitment universe expands
genesis identity, persistence, proof codecs, conformance matrices, client
negotiation, and release artifacts. It requires a follow-up ADR and measured
product need rather than an implicit alias of the current Blake2b-256 MPF.

### 4.6 The selected profile is chain-wide

The genesis choice applies to the app chain's complete authenticated state,
including framework markers, governance/effect state, and application
collections. It is not a private tree hidden inside `authenticated-map`.
Consequently the selected MPF or JMT root is the block's one `stateRoot` and is
what finality and anchoring certify.

Every active state-machine component, deterministic framework subsystem, and
effect-state transition must declare and pass conformance for the selected
backend semantics. In particular, code that calls `AppStateWriter.delete()`
must observe logical absence after the call even when a JMT backend represents
that absence with a canonical internal tombstone. Native proofs must still
distinguish physical absence from an included tombstone.

Initial JMT enablement may therefore be limited to an authenticated-map chain
profile and framework/effect combinations that have passed the JMT conformance
suite. A composite profile is allowed only when every component is qualified
for the same selected backend. Unsupported combinations fail genesis
validation; they do not fall back to MPF or create an MPF/JMT hybrid root.

## 5. Genesis identity and immutability

### 5.1 Canonical genesis commitment

For chains created under this ADR, canonical genesis bytes include at least:

- chain ID and genesis schema version;
- state-machine ID/version;
- exact state-commitment profile ID and format fingerprint;
- canonical collection descriptors and authorization policies;
- normalized message/block/resource limits;
- initial membership, roles, threshold, and sequencer profile or their canonical
  epoch-0 commitment;
- consensus-affecting effect settings;
- initial anchor identity/policy fields that affect verification; and
- any initial authenticated-map entries and their canonical ordering.

The design target is:

```text
genesisId = Blake2b-256(
    "yano-appchain-genesis-v1\0" || canonicalGenesisBytes
)
```

The final canonical codec is coordinated with the broader consensus-profile
work identified by ADR-024. Secrets, filesystem paths, URLs, API keys, wallet
seeds, and executor-local settings are excluded.

### 5.2 Where identity is bound

The genesis/profile identity must be available and checked in:

- peer app-chain initialization and catch-up compatibility checks;
- proposal/vote/finality signing identity;
- the authenticated framework profile marker;
- snapshots, manifests, and integrity checks;
- status/config inspection APIs and CLI output;
- proof envelopes; and
- metadata and script-anchor payloads/datums.

The current app-block `stateRoot` stays backend-neutral. A future block format
revision should bind `genesisId` directly in the signed block preimage rather
than expecting a verifier to infer the profile from root bytes. Until that
format is implemented, this ADR is not complete.

### 5.3 No in-place profile switch

The selected profile cannot change at runtime, on restart, or through an
ordinary governance message. It changes every state root and proof format.

Switching profiles requires one of:

1. a new chain generation/new chain identity importing a canonically exported
   snapshot and linking to the old chain's final checkpoint; or
2. a future explicit hard-fork ADR defining a dual-commit transition height,
   complete state migration, proof continuity, crash recovery, and client
   compatibility.

There is no implicit root conversion between MPF, classic JMT, and Poseidon
JMT. A local node configured with the wrong profile fails before consensus or
catch-up participation; it never adopts a retained store under a different
descriptor.

Existing non-empty Yano app chains are legacy MPF chains. Absence of a new
genesis-profile field in their historical format means MPF, not “use the local
default.” They are not migrated by this ADR.

## 6. Consensus behavior

### 6.1 The threshold protocol does not change

The tree is not the consensus algorithm. It is the deterministic state
commitment certified by the existing consensus protocol.

For a configured threshold `t` of `m` members:

1. the sequencer selects and orders messages;
2. the proposer applies them to a candidate view using the genesis-selected
   commitment backend;
3. the proposal contains the resulting backend-neutral `stateRoot`;
4. each follower validates the proposal and independently applies the same
   ordered messages from the same previous root/version;
5. a follower votes only when its full proposal/block identity, including the
   locally reproduced root, matches;
6. `t` valid member signatures produce the finality certificate; and
7. the finalized block, root, tree update, indexes, and tip are durably
   committed.

For three members with threshold two, MPF and JMT both use two-of-three
finality. The only difference is which commitment engine produced the root.

### 6.2 Members sign a qualified block identity, not a bare root

The signing preimage must transitively bind:

```text
chainId
genesisId / commitment profile
height and previous block hash
messages root and ordered block contents
post-state root
L1 reference
membership/finality epoch
```

A node that reproduces a different root refuses to vote and reports expected
versus observed profile/root identity without leaking application values.

### 6.3 JMT version mapping

The logical authenticated-state version equals finalized app-chain height:

```text
JMT version H <-> finalized AppBlock height H <-> AppBlock.stateRoot
```

If a block makes no state change, the app ledger still records its post-state
root. The backend may internally alias the preceding JMT root instead of
duplicating nodes, but queries and proof bundles resolve through the finalized
block height.

Version numbers by themselves are not authenticated application state. The
block/finality/genesis binding authenticates `(height, root)`.

## 7. Runtime commitment SPI and atomicity

### 7.1 Required abstraction

ADR-005 anticipated a `StateCommitment` SPI, but the current runtime is tightly
integrated with MPF. Implementation of this ADR requires a transactional,
candidate-aware contract conceptually equivalent to:

```java
interface AuthenticatedStateBackend {
    StateCommitmentProfile profile();
    CandidateState beginCandidate(long baseHeight, byte[] baseRoot, long targetHeight);
    StateSnapshot snapshot(long height);
    StateProof prove(long height, byte[] canonicalKey);
    void verifyIntegrity();
}

interface CandidateState extends AutoCloseable {
    Optional<byte[]> get(byte[] canonicalKey);
    void put(byte[] canonicalKey, byte[] canonicalValue);
    void deleteOrTombstone(byte[] canonicalKey);
    byte[] root();
    PreparedStateCommit prepare();
    void discard();
}
```

The final Java API may differ. The behavioral requirements do not.

### 7.2 Candidate execution

Candidate state must provide read-your-writes behavior across all messages and
framework callbacks in a proposed block. It must not modify durable roots,
nodes, values, stale indexes, version metadata, or pruning metadata before the
proposal has a valid finality certificate.

Closing or discarding an uncertified candidate releases all temporary state.
Retrying another proposal at the same height starts from the unchanged
finalized root.

### 7.3 Final commit boundary

The following become visible together or not at all:

- finalized app-block bytes and hash;
- finality certificate;
- message and query indexes;
- framework and application state mutations;
- selected MPF/JMT nodes and values;
- JMT version root and stale-node metadata when applicable;
- current state root; and
- ledger tip.

The preferred implementation integrates backend column families with the
runtime's RocksDB database and external `WriteBatch`. If a library API cannot
participate in that batch, it must gain a non-persisting prepared-update API or
Yano must define and test an equally strong recoverable commit protocol. Two
independently committed databases with “best effort” reconciliation do not
satisfy this ADR.

CCL's public JMT `put(version, mutations)` style currently commits through its
store. It cannot be called directly from `AppStateMachine.apply()` or from a
candidate path until the required preparation/atomicity boundary exists.

### 7.4 One logical writer

Each local chain generation owns one backend namespace and one serialized
commit path. Sequencer rotation does not create concurrent local tree writers:
every node still applies at most one finalized height at a time.

Background pruning, snapshots, proof reads, and integrity scans coordinate with
the writer according to the backend's qualified concurrency contract.

## 8. Proof and verification contract

### 8.1 Profile-tagged proof envelope

A proof response must carry an explicit, canonical envelope such as:

```text
proofSchemaVersion
chainId
genesisId
height
blockHash
stateCommitmentProfile
stateRoot
collectionId
canonicalApplicationKey
logicalStatus = ACTIVE | REVOKED | ABSENT
value/revision/controller fields as permitted
nativeProofType
nativeProofEncoding
nativeProofBytes
finalityCertificate or reference
L1 anchor evidence or reference, when requested
```

The verifier selects the proof decoder by the allowlisted profile and encoding,
never by probing bytes. MPF proof bytes, classic JMT proof bytes, and ZeroJ
witness/proof artifacts are distinct types.

### 8.2 Historical proofs and retention

The API can return a historical proof only while the required nodes/versions
are retained. A pruned proof response is `UNAVAILABLE`, not a fabricated proof
against a newer root.

Retention and pruning may be operational rather than consensus-affecting when
they do not change roots, but advertised proof-history capability, snapshot
compatibility, rollback horizon, and operator warnings must be explicit.

### 8.3 Root trust

Clients should obtain the trusted root from one of:

- a locally verified finalized app-block chain;
- a valid threshold certificate under a trusted membership history; or
- an independently verified Cardano anchor datum/checkpoint.

Asking the same untrusted REST server for both root and proof establishes only
internal consistency with that server's claimed state.

## 9. Cardano L1 and ZeroJ integration

### 9.1 Backend-neutral anchoring

Metadata and script anchors include or transitively bind:

```text
chainId
genesisId
stateCommitmentProfile
fromHeight / toHeight
tip block hash
tip state root
anchor schema/version
```

The block hash continues to bind history and messages. Carrying profile and
root explicitly avoids ambiguous indexing and enables proof consumers to fail
closed before decoding.

For on-chain consumers, the authoritative current root should be available in
an inline datum or reference input protected by the anchor thread token and
anchor update policy. Transaction metadata alone is useful notarization but is
not a convenient authoritative input to a later validator.

### 9.2 Three Cardano verification paths

#### MPF direct proof

An application validator can use the qualified Cardano/Aiken-compatible MPF
proof path against the root in the anchor datum. Business-policy and replay
bindings remain the application's responsibility.

#### Classic JMT proof

Classic JMT is verified off-chain unless a separately specified and qualified
Cardano verifier is introduced. It cannot be passed to the ZeroJ Poseidon JMT
circuit because the commitment profiles differ.

#### ZeroJ proof about a Poseidon JMT root

A prover converts a real CCL/ZeroJ JMT proof into a strict ZeroJ witness and
proves an application statement. A Cardano Plutus V3 validator verifies the
Groth16 proof and checks that its public root equals the authoritative anchor
root/reference datum.

The application circuit/validator must bind every policy-relevant output. For
a funds-release flow that normally includes:

- chain/genesis/profile or a root datum that already fixes them;
- beneficiary or credential subject;
- asset and amount or a committed policy result;
- nullifier, claim ID, or consumed UTxO identity;
- epoch/expiry when applicable;
- exact circuit release and verification key; and
- relevant `ScriptContext` inputs and outputs.

### 9.3 Threshold checkpoint versus validity proof

This ADR distinguishes three trust models:

1. **Threshold-finalized root:** the configured quorum attests that it executed
   the block and agrees on the root.
2. **Threshold root plus on-demand ZeroJ fact proof:** the quorum authenticates
   the root; users privately prove membership, non-membership, or a policy fact
   against it. This is the recommended first integration.
3. **ZK-proven whole-block transition:** Cardano verifies that all state
   mutations from the previous root to the new root satisfy a validity circuit.
   This is a later validity-rollup-style option.

The current operation-specific ZeroJ JMT transition circuits cover individual
mutations. A canonical bounded whole-block/batch transition requires a CCL
transition witness, canonical batch semantics, bounded circuits, independent
vectors, and measured Cardano budgets. Threshold anchoring must not be
marketed as a whole-block ZK validity proof before that work exists.

## 10. Real-world use cases

### 10.1 Credential, issuer-key, and revocation transparency

Collections can store issuer keys, credential commitments, validity windows,
and explicit revocation status. A verifier can prove the key/status at a
particular finalized height. Poseidon JMT additionally enables a holder to
prove a private eligibility property against the anchored registry root.

The ordered app ledger remains important: a map proves state at a checkpoint;
the ledger explains and audits how the map evolved.

### 10.2 Asset and digital-product registries

Examples include digital product passports, real-world asset metadata,
supply-chain assets, serial-number ownership, equipment certificates, and
tokenization registries. The map stores canonical metadata or content hashes,
owner/controller, issuer, and lifecycle status.

The root proves which bytes were committed. It does not prove that a physical
claim is true or that an external S3/IPFS document remains available.

### 10.3 Authorization, entitlement, and consortium membership

Collections can represent principal/resource roles, delegated authority,
approval limits, service entitlements, and allow/deny lists. Historical proofs
can establish which policy was active when an app-chain action finalized.

Compare-and-set and revisions prevent an update from silently succeeding
against stale authorization state.

### 10.4 Auditable policy, configuration, tariff, and price snapshots

An application can commit rulebooks, pricing schedules, tariffs, risk
parameters, workflow definitions, model/configuration hashes, and compliance
policies. A later dispute can resolve the exact version used for a decision.

### 10.5 Accounts, balances, quotas, and allowances

Large point-addressed maps can hold consortium balances, loyalty points,
carbon allowances, API quotas, resource allocations, or exposure limits.

For value-bearing Cardano release, the selected profile and proof path matter:
MPF permits the direct proof path; Poseidon JMT permits a ZeroJ proof when the
application statement and validator are fully bound and production-qualified.

### 10.6 Evidence and document metadata

The map can hold evidence ID to content hash, submitter, classification,
lifecycle status, and external storage reference. Large content stays outside
the consensus tree. The state proof authenticates metadata, not blob
availability.

### 10.7 Authenticated caches and light clients

An external service can cache records and serve profile-tagged proofs. A client
that independently knows the finalized/anchored root can verify point queries
without trusting that service's database.

### 10.8 Multi-tenant applications

Tenants sharing governance, retention, and finality can use collections or a
tenant component in the canonical key inside one tree. Separate JMTs are not
created for arbitrary tenants. Tenants requiring different membership,
threshold, anchoring, or failure isolation use separate app chains.

## 11. Non-goals and poor fits

The authenticated map is not:

- a full-text or prefix-search engine;
- an authenticated range-query database;
- an analytics/aggregation engine;
- an ordered log, queue, or time-series replacement;
- a confidentiality mechanism merely because ZeroJ can hide a proof witness;
- proof that external content is available;
- proof that a real-world claim is truthful;
- a permissionless consensus protocol;
- a dynamic tree-per-topic service; or
- an automatic whole-block ZK rollup.

MPF and JMT hash canonical keys, so logical namespace prefixes do not produce
physical key locality. Derived indexes may be PostgreSQL, search, cache, or
stream projections, but they remain rebuildable and outside consensus.

App-chain members normally replicate the underlying state. Private witness
presentation to Cardano does not make replicated map values confidential from
those members. Confidential storage needs encryption, access policy, and a
separate threat model.

## 12. Why one tree rather than several JMTs

One tree provides:

- one root per finalized block;
- atomic operations across collections;
- one proof/root/finality/anchor identity;
- one snapshot and recovery contract;
- one integrity and pruning lifecycle; and
- simpler catch-up and operations.

One JMT per dynamic topic or collection would create many column-family sets,
roots, version streams, pruning jobs, proof codecs, snapshots, metrics, and
cross-tree atomicity problems. Cross-collection batches would need a durable
coordinator and a root-of-roots proof.

Fixed multiple trees may become reasonable only for a demonstrated hard
boundary such as independent retention, storage placement, export/state sync,
administration, or bounded parallel execution. A follow-up ADR would need:

- a canonical ordered root registry/root-of-roots;
- two-layer proofs;
- per-height rules for unchanged subtrees;
- one atomic commit across every changed tree and the app block; and
- evidence that separate chains are not the clearer isolation unit.

This ADR rejects multiple trees for v1.

## 13. Security and operational requirements

### 13.1 Determinism and domain separation

- Collection/key/value codecs are versioned and have golden vectors.
- All map iteration entering hashing or batching is canonically ordered.
- Duplicate collection IDs, keys, operations, and aliases are rejected.
- Key, value, collection, batch, and block limits are bounded and authenticated
  as consensus settings.
- Framework and application namespaces are structurally separated.
- Profile, proof type, and proof encoding are explicit and allowlisted.
- Poseidon field encodings reject non-canonical aliases.

### 13.2 Root and statement binding

- Votes bind the block/genesis/profile identity, not a bare root.
- Proof verifiers obtain the root independently or verify its finality/anchor.
- ZeroJ validators bind application facts and replay protection.
- Verification keys and circuit/release identities are fixed by policy, never
  supplied freely by a proof submitter.

### 13.3 Persistence and recovery

- Candidate execution is side-effect free until finality.
- Block and authenticated state commit atomically.
- Retained format descriptors fail closed on mismatch.
- Restart verifies tip block, root, profile, backend metadata, and integrity.
- Kill-during-commit testing covers every durable boundary.
- Snapshots include profile and genesis identity and are restored only into an
  empty compatible namespace.
- Catch-up re-executes blocks and reproduces every retained state root.

### 13.4 Pruning and historical availability

- Pruning never changes a retained/latest root.
- The operator cannot prune below required rollback, snapshot, audit, or proof
  service horizons without an explicit checked action.
- Proof endpoints report pruned history honestly.
- Metrics expose latest height/version, oldest provable height, stale nodes,
  pruning state, integrity status, and backend/profile identity.

### 13.5 ZeroJ release boundary

Before value-bearing use, each exact application circuit requires:

- independent review of commitment and circuit soundness;
- a production trusted-setup ceremony and provenance for its exact R1CS/VK;
- current network protocol-budget measurement;
- positive and adversarial Yaci VM tests;
- public target-network execution;
- application `ScriptContext`, state-token, authorization, nullifier, and
  release binding; and
- a documented upgrade/retirement process for verification keys and scripts.

Benchmark/dev proving keys or known single-party toxic waste are never shipped
as production-approved artifacts.

## 14. Implementation plan

This plan intentionally separates product semantics from backend work.

### Phase 0 — Contract, measurements, and dependency gates

1. Freeze canonical profile IDs and map/collection/entry/command codecs.
2. Add cross-language/cross-module golden vectors for keys, entries, genesis,
   roots, and proofs.
3. Pin initial work to CCL development release `0.8.0-pre5-dev1` and an exact
   ZeroJ revision exposing the required persistent profiles and proof contracts.
4. Replace the CCL development pin with a proper CCL release and rerun descriptor,
   root, proof, persistence, and cross-module golden-vector checks before any
   production qualification.
5. Benchmark current MPF and both JMT profiles on identical map workloads.
6. Design the prepared-update/external-batch contract with CCL; do not build a
   sidecar commit workaround.
7. Freeze the five-mode authorization catalog, governed action/evidence
   contracts, and authoritative-versus-derived projection API defined by
   ADR-025.2.

Benchmark dimensions include:

- 100 thousand, 1 million, 5 million, and where practical 10 million keys;
- insert/update/revoke mixes;
- application batches of 1, 10, 100, and 1,000 within configured limits;
- candidate prepare, root calculation, discard, and finalized commit latency;
- p50/p95/p99 throughput and latency;
- current/historical proof latency and native proof size;
- disk growth, stale nodes, write amplification, and compaction;
- restart, integrity scan, snapshot, restore, replay, and catch-up;
- retained-history/pruning behavior; and
- a sustained soak with synced WAL/durability settings.

### Phase 1 — Authenticated-map semantics over current MPF

1. Add the versioned `authenticated-map` state machine without changing
   `kv-registry`.
2. Implement collections, canonical namespaces, active/revoked entries,
   revisions, the five authorization modes, compare-and-set, governed evidence
   consumption, and bounded atomic batch.
3. Add profile-neutral point/history/receipt query DTOs.
4. Use the current MPF runtime path and prove identical replay across several
   members, restarts, snapshots, and catch-up.
5. Add demo/client scripts only after the deterministic contract is frozen.

This phase validates product value before adding a new persistence backend.

### Phase 2 — Backend-neutral prepared commitment runtime

1. Introduce the candidate-aware commitment SPI in `core-api` or the smallest
   stable public boundary.
2. Refactor the current MPF integration behind it without changing legacy MPF
   roots or proof bytes.
3. Add genesis/profile identity, status, proof envelope, snapshot manifest, and
   peer compatibility checks.
4. Make finalized block, state backend, and indexes share one atomic commit.
5. Add fault injection at every prepare/commit/restart boundary.

Legacy MPF chains must reproduce their existing roots byte-for-byte after the
refactor.

### Phase 3 — Classic Blake2b-256 JMT

1. Integrate `jmt-blake2b256-v1` through the prepared backend and require its
   pinned CCL descriptor and format fingerprint.
2. Map app height to JMT version and implement retained historical proofs.
3. Implement tombstone-aware logical reads, proof classification, snapshots,
   integrity checks, and pruning.
4. Run multi-member deterministic root, finality, catch-up, crash, and soak
   tests.
5. Publish measured operating bounds instead of generic performance claims.

### Phase 4 — ZeroJ Poseidon JMT

1. Integrate the exact `jmt-poseidon-bls12381-v1` profile through the pinned
   ZeroJ host descriptor without reimplementing its hash/commitment rules in
   Yano.
2. Reuse ZeroJ strict witness conversion and circuit manifests through an
   optional integration module; avoid making ZeroJ a mandatory dependency for
   MPF/classic-JMT chains.
3. Add proof endpoints/artifacts for supported inclusion, non-inclusion,
   update, insertion, and tombstone statements.
4. Bind script-anchor datum/reference roots to chain/genesis/profile/height.
5. Build a non-value-bearing showcase flow on devnet/preprod.
6. Keep production and mainnet deployment fail-closed until all ZeroJ release
   gates in §13.5 pass.

### Phase 5 — Proof bundles and developer experience

1. Add Java client verification dispatch by profile.
2. Add CLI commands for current/historical entry, proof, verify, genesis/profile
   inspection, integrity, snapshot, and oldest provable height.
3. Expose backend/profile/version/root consistently in REST, status UI, metrics,
   evidence bundles, and anchor views.
4. Document trusted-root acquisition and avoid examples that verify a node's
   proof only against the same node's untrusted root.

### Phase 6 — Optional future validity and multiproof work

Only after a canonical CCL host contract exists:

1. benchmark multiproof versus several independent proofs;
2. implement bounded homogeneous JMT batch transitions before a universal
   mixed-operation circuit;
3. bind batch/message commitments and intermediate transition semantics;
4. evaluate recursive/aggregated proof designs separately; and
5. decide whether whole-block validity proofs are a Yano product profile.

This phase is not required for threshold-finalized roots plus on-demand ZeroJ
fact proofs.

### 14.1 Expected module boundaries

The implementation should preserve optionality:

| Area | Likely location/responsibility |
|---|---|
| Backend-neutral profile, candidate, proof, and snapshot contracts | `core-api` |
| MPF/JMT RocksDB staging, atomic commit, recovery, integrity | `runtime` |
| Authenticated-map deterministic state machine and codecs | `appchain-stdlib` or a focused first-party appchain module |
| Profile-aware client proof verification | `appchain-client` |
| ZeroJ witness/proof/manifest integration | optional extension module, not base runtime API |
| REST/status/config wiring | `app` and appchain devtools |
| Demonstration profile and scripts | `appchain-showcase` after core qualification |

The exact Gradle split is an implementation decision. The dependency rule is
not: MPF-only and classic-JMT deployments must not pull proving keys, dev setup
artifacts, native provers, or an unconditional ZeroJ runtime.

## 15. Verification and acceptance criteria

The ADR is implemented only when all applicable criteria pass.

### 15.1 Compatibility

- Existing MPF app-chain fixtures reproduce identical state roots and proofs.
- `kv-registry` command/state behavior is unchanged.
- A non-empty store rejects a different/missing profile descriptor.
- No local default silently changes a retained chain's profile.

### 15.2 Determinism and consensus

- At least three nodes independently reproduce every root for each supported
  profile.
- A three-member threshold-two test finalizes matching roots for MPF, classic
  JMT, and Poseidon JMT.
- A node with a wrong profile/genesis/root refuses to vote before durable
  mutation.
- Reordered commands, duplicate keys, malformed encodings, and limit violations
  have deterministic fail-closed outcomes.
- Full replay, restart at several heights, snapshot restore, and late-member
  catch-up reproduce the same roots.

### 15.3 Atomicity and recovery

- Discarded and timed-out proposals leave no durable tree/version/stale-node
  changes.
- Kill before, during, and after the commit boundary yields either the old
  complete tip or the new complete tip, never a mixed state.
- Block bytes, root, backend version, indexes, and state entries agree after
  every injected restart.
- Integrity checks detect missing/foreign nodes, wrong format, ahead/behind
  manifests, and root/version mismatch.

### 15.4 Proofs

- Inclusion, non-inclusion, and tombstone/revocation vectors pass for every
  applicable profile.
- Wrong root, key, value, profile, proof form, height, genesis, and malformed
  encoding are rejected.
- Historical proofs verify against the exact block root at the requested
  retained height.
- Client proof bundles reject invalid finality certificates and incorrect L1
  anchor bindings.
- Cross-profile proof substitution always fails.

### 15.5 ZeroJ/Cardano

- Real host Poseidon JMT proofs convert through the strict ZeroJ witness path.
- Positive proofs and mutated root/key/value/path/public-input cases run through
  the actual Plutus V3 VM integration.
- Representative validators bind authoritative root/version datum, state
  token/reference identity, release/VK, replay protection, and application
  outputs.
- Network budget and public testnet evidence are recorded for each released
  circuit shape.
- Production manifests remain unavailable until ceremony and external-review
  gates are evidenced.

### 15.6 Performance and operations

- Published benchmarks use production durability settings and identify hardware,
  dataset, key distribution, batch policy, profile, versions, and retention.
- The selected default remains MPF unless measured product requirements justify
  another default.
- Soak, compaction, snapshot, catch-up, and pruning remain within documented
  operator bounds.
- Status/UI/CLI report the exact canonical profile and never only “JMT.”

## 16. Consequences

### 16.1 Positive

- Applications get a real versioned authenticated-map product rather than a
  tree-specific demo.
- Logical collection and command semantics remain stable across commitment
  backends.
- MPF preserves the current Cardano-native path.
- Classic JMT serves large versioned off-chain maps.
- Poseidon JMT enables compact privacy-preserving facts and transitions to be
  verified on Cardano through ZeroJ.
- Genesis/profile binding prevents silent root-format disagreement.
- One tree preserves atomic cross-collection workflows and simple anchoring.
- Optional module boundaries avoid imposing ZK cost on ordinary deployments.

### 16.2 Negative

- The runtime commitment layer requires a real refactor; JMT cannot safely be
  added as a small state-machine plugin.
- Every profile multiplies root, proof, snapshot, recovery, conformance, and
  operational testing.
- JMT's current lack of physical deletion requires explicit tombstone semantics
  and careful UI/API wording.
- Rich query projections remain separate and non-authoritative.
- Profile migration is a new-chain or explicit hard-fork exercise.
- ZeroJ production use adds circuit review, setup ceremony, VK/script lifecycle,
  and Cardano budget responsibilities.
- Whole-block ZK validity is not provided by the initial single-operation
  circuit catalog.

## 17. Alternatives considered

### A. Extend `kv-registry` in place

Rejected. Changing command, authorization, deletion, or state encoding changes
replay and roots for existing chains. Keep it simple and stable; add a new
versioned product.

### B. Make JMT an ordinary state-machine sidecar

Rejected. Immediate persistence violates proposal discard, and a second DB
breaks the atomic block/state boundary.

### C. Store a JMT root as a value inside MPF

Rejected as the default architecture. It produces double commitment and
nested proofs and still requires an atomic sidecar. A deliberately aggregated
multi-commitment chain would need a separate ADR.

### D. One JMT per topic, collection, or tenant

Rejected for v1. It creates unbounded storage/proof/version operational state
and complicates cross-collection atomicity. Use logical namespaces or separate
app chains for genuine governance/finality isolation.

### E. Choose the backend at every restart

Rejected. The profile is consensus and persistence identity, not a local
performance setting.

### F. Make Poseidon JMT the immediate default

Rejected for now. It is strategically attractive for ZeroJ/Cardano flows but
still has external production gates and does not replace the current MPF
compatibility contract. Make it an explicit genesis choice until qualification
and adoption justify revisiting the default.

### G. Use classic JMT roots with ZeroJ Poseidon circuits

Rejected as cryptographically invalid. The commitment profiles differ; there
is no implicit conversion between their roots.

### H. Treat threshold anchoring as a ZK validity proof

Rejected. Threshold finality attests quorum agreement. A whole-block validity
proof must constrain the complete ordered transition from old root to new root.

## 18. Open implementation questions

These do not change the high-level decision but require resolution before the
corresponding phase:

1. What exact canonical genesis codec and block-format revision will bind
   `genesisId`?
2. Does the prepared JMT update land first in CCL, or can its store safely
   expose an external RocksDB batch without leaking RocksDB into public APIs?
3. Is `BATCH` submitted order authoritative, or are independent keys sorted
   canonically before application? The answer must preserve application intent
   and future circuit compatibility.
4. What are the default and maximum collection/key/value/batch limits?
5. What historical proof-retention tiers are supported and advertised?
6. Should the anchor script store the complete profile ID/genesis ID directly
   or only a versioned genesis commitment plus root?
7. Which ZeroJ application circuits are worth standardizing beyond the generic
   operation primitives?
8. What canonical host transition trace will support future bounded JMT batch
   circuits?
9. When, if ever, should fixed multi-tree/root-of-roots support be considered?

Each answer that changes consensus, root, proof, or migration semantics requires
this ADR to be amended before implementation or a focused follow-up ADR.

## 19. Market landscape and product positioning

### 19.1 Positioning boundary

The proposed scope combines:

- deterministic multi-party app-chain execution;
- configurable threshold finality;
- one logical authenticated-map contract with a genesis-selected MPF,
  Blake2b-256 JMT, or BLS12-381 Poseidon JMT commitment;
- profile-tagged current and historical point proofs;
- native Cardano checkpoint anchoring; and
- an optional Java-to-Groth16-to-Plutus V3 private proof path.

This is an architectural positioning statement, not a market-exclusivity
claim. Authenticated databases, consortium ledgers, enterprise DLT frameworks,
transparency systems, and general rollup or sovereign-chain frameworks each
cover parts of the problem and may be stronger in their established domains.
The proposed differentiation is the Cardano/Java integration and product
packaging, not invention of authenticated maps or replicated ledgers.

### 19.2 Proposed market position

Do not position this as another database or another general-purpose blockchain.
Both descriptions invite comparisons where mature products are stronger.

The clearer category statement is:

> **Cardano-anchored, multi-party verifiable application state for Java
> systems, with direct or privacy-preserving proofs selected at genesis.**

The product wedge should be a concrete solution such as:

- credential/key/revocation transparency;
- governed asset or digital-product registries;
- policy, entitlement, and approval state;
- evidence/document status registries; or
- privately provable balances, eligibility, quotas, or claims.

Tree names belong in expert configuration and proof metadata, not the headline.
Friendly deployment presets may describe outcomes while persisting the exact
canonical profile, for example:

| Product-facing preset | Canonical commitment profile | User outcome |
|---|---|---|
| `cardano-verifiable` | `mpf-blake2b256-v1` | Direct Cardano-oriented state proofs and current compatibility |
| `versioned-audit` | `jmt-blake2b256-v1` | Native versioned JMT storage and off-chain historical proofs |
| `zk-cardano-verifiable` | `jmt-poseidon-bls12381-v1` | Private facts/transitions proved with Groth16 and verified by Plutus V3 |

These names are illustrative UX, not additional genesis aliases unless a later
configuration contract freezes their resolution.

### 19.3 Potential advantages

#### Cardano-native vertical integration

Yano can combine Cardano L1 observation, appchain sequencing/finality,
authenticated state, anchoring, proof retrieval, and Cardano transaction
submission in one Java stack. Authenticated databases generally need an
independently built blockchain anchor service, while general rollup stacks may
target a different execution, data-availability, or settlement ecosystem.

#### One application contract, three verifiability profiles

An application can keep the same collection/authorization/update model while
choosing direct Cardano-oriented MPF, versioned classic JMT, or circuit-friendly
Poseidon JMT at genesis. This makes the trust/proof choice explicit without
forcing application messages to encode a tree implementation.

#### Threshold finality plus independently consumable state proofs

The proposed proof bundle combines application state, quorum finality, and an
L1 checkpoint. Verifiable databases can supply database-state verification and
consortium ledgers can supply transaction receipts; Yano can make Cardano the
independently visible checkpoint and consumption layer.

#### Poseidon/Plutus privacy path

Poseidon JMT can support a Cardano validator learning a policy result rather
than the private key/value/path. The host JMT, witness conversion, Java circuit,
Groth16 proof, and Plutus V3 verifier can live in one integrated Java/Cardano
stack. That end-to-end path is a meaningful differentiator if it becomes
independently reviewed and operationally packaged.

#### Focused and potentially lighter than a general chain

For a consortium that needs a governed registry and Cardano checkpoint rather
than EVM compatibility, gas economics, a new public token, or general smart
contracts, a packaged Yano appchain can be smaller in conceptual and operating
scope than a general sovereign-chain, enterprise DLT, or EVM-rollup framework.
This advantage must be demonstrated with deployment, upgrade, monitoring, and
recovery experience; it is not true merely because the codebase is smaller.

#### Java and existing BloxBean/Cardano ecosystem

Java APIs, CCL transaction construction, Yaci protocols, Yano node integration,
and the Poseidon/Groth16 proof path can be attractive to enterprise
Java/Cardano teams that would otherwise combine non-JVM chain infrastructure
with separate JVM services.

### 19.4 Disadvantages and competitive risks

#### Maturity and assurance gap

Established enterprise DLT, consortium-ledger, verifiable-database, and rollup
stacks have broader production history, communities, integrations, security
review, and operator knowledge. Yano's appchain and ZK paths are comparatively
young. Value-bearing claims must remain gated by audit, formal/adversarial
testing, public-network evidence, and durable operational experience.

#### Consensus liveness and fault-model gap

The current fixed/rotating sequencer plus threshold certificate is simpler than
a mature BFT protocol such as those used in established chain frameworks. It
can provide strong certification safety under its assumptions, but liveness,
view change, equivocation accountability, network partitions, and dynamic
membership require further qualification. Do not market “two-of-three” as
equivalent to every mature BFT implementation.

#### No replicated-state confidentiality by default

Authenticated state is not confidential state. Members can normally read the
replicated map. Some consortium frameworks provide TEE-protected state or code,
and some enterprise DLT frameworks provide selectively distributed private
collections. Yano needs a separate encryption/selective-replication design if
that buyer need is central; a ZK proof only hides its witness from the eventual
verifier.

#### Query and database ergonomics

Mature database products can offer SQL/KV access, common clients, rich document
queries, and secondary indexes. A hashed authenticated map offers point proofs,
not rich search. Yano must package a reliable projection/index story and clearly
label derived queries, or users will experience it as an inferior database.

#### ZK operational cost and specialization

Every exact ZeroJ circuit shape needs review, proving/setup artifacts, a
verification key and script lifecycle, prover capacity, failure handling, and
budget monitoring. General zkVM rollup frameworks may make arbitrary program
evolution easier, while operation-specific circuits can be smaller but more
specialized. Current JMT batch/whole-block validity circuits are also future
work.

#### Interoperability and ecosystem gap

Established sovereign-chain ecosystems may provide cross-chain protocols and
module catalogs; EVM rollup ecosystems may provide shared liquidity and managed
providers; enterprise and consortium platforms may provide mature identity,
integration, and hosted-ledger options. Yano currently lacks comparable managed
deployment, marketplace, interoperability, SLA, and broad client ecosystems.

#### Three profiles increase product and support complexity

Multiple commitment choices are valuable only when presented as clear outcome
profiles. They multiply conformance, migration, documentation, monitoring,
proof codecs, incident diagnosis, and customer decision burden. The default
must remain opinionated, and unsupported combinations must fail at genesis.

#### Anchoring does not provide data availability or truth

A Cardano root proves a checkpoint existed; it does not make appchain data
available, correct a false real-world assertion, or recover state from vanished
members. Snapshot distribution, ledger retention, independent auditors, and
application governance remain product requirements.

#### Generic ledger-database positioning has uncertain demand

The discontinuation of past managed ledger-database offerings does not prove
authenticated state lacks a market, but it cautions against leading with a
generic “immutable database” pitch. The product should solve a regulated
multi-party verification problem where quorum ownership, portable proofs,
Cardano settlement, or privacy proofs are essential—not merely add hashes to
ordinary CRUD.

### 19.5 Competitive conclusion

The defensible advantage is not JMT itself. JMT, MPF, IAVL, sparse Merkle maps,
and transparency logs are available elsewhere.

The potential advantage is this integrated chain of evidence:

```text
domain-specific governed update
    -> deterministic Java state transition
    -> genesis-selected authenticated root
    -> multi-member finality certificate
    -> Cardano checkpoint
    -> portable direct or ZeroJ privacy proof
    -> application-specific Cardano action
```

The largest disadvantage is that every arrow must be production-grade before
the combined promise is stronger than adopting a mature database, consortium
or enterprise DLT framework, or general rollup SDK. The first product release
should therefore choose one vertical use case, one opinionated profile, and one
complete verification flow while retaining the three-profile architecture for
later deployments.

## 20. References

- Foundational *Jellyfish Merkle Tree* paper: versioned sparse authenticated
  state optimized for LSM storage:
  <https://developers.diem.com/papers/jellyfish-merkle-tree/2021-01-14.pdf>
- CCL `v0.8.0-pre5-dev1` development release, used only as the temporary
  implementation baseline:
  <https://github.com/bloxbean/cardano-client-lib/releases/tag/v0.8.0-pre5-dev1>
- CCL Jellyfish Merkle Tree implementation and production-readiness material at
  that development tag:
  <https://github.com/bloxbean/cardano-client-lib/tree/v0.8.0-pre5-dev1/verified-structures/jellyfish-merkle>
- ZeroJ Poseidon authenticated-state modules and Cardano verification path:
  <https://github.com/bloxbean/zeroj>
