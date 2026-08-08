# ADR app-layer/031: Composable state-machine foundations and portable proofs

**Status:** Accepted and in implementation — Phases 0 through 6 complete
**Date:** 2026-08-08
**Scope:** App-chain state machines, composition, authorization and approvals, authenticated state,
proofs, and L1 consumption
**Related:** ADR app-layer/005, 013.2, 019, 021, 025/025.2, 027, 028, and 030
**Preview reset policy:** App-chain is unreleased. The refactor establishes a new genesis/storage,
SPI, wire, proof, composition, and anchor baseline. Pre-refactor app-chain databases, blocks,
plugins, proofs, and anchors are not migrated or supported.

---

## 1. Decision summary

Yano will keep **one deployable application contract: `AppStateMachine`**. A composite application
will continue to be an `AppStateMachine`; composition is an implementation technique, not a second
kind of application.

`AppStateMachine` will be simplified to one framework execution entry point taking an
`AppBlockExecutionContext`, `AppStateWriter`, and `AppEffectEmitter`. The context exposes
runtime-validated, block-bound inputs such as decoded `~l1/*` observations while preserving their
original message positions. It never exposes a live L1 ledger-state store. This gives ADR-028
historical protocol-parameter/stake machines and custom applications a typed path to L1 facts
without making replay depend on retained L1 history.

The current public `CompositeComponent` compatibility boundary and synthetic-block adapter will be
removed rather than carried into the first supported release. Ordinary application assembly accepts
`AppStateMachine` instances directly. Composition may use one small internal routed execution port,
but it is not a second developer-facing application SPI. The primary unit of reuse is a small
deterministic transition capability used by both:

* a stock standalone state-machine adapter, such as ordered-log or document-trail; and
* a custom/composite state machine or cross-component workflow.

Authorization and approval will be extracted as typed deterministic capabilities above authenticated
storage. This work will build on the already implemented ADR-019 domain-actor and role-approval
components. It will not create a second identity, role, or approval system, and it will not make the
runtime aware of application policy.

Proofs will be separated into four claims that must not be conflated:

1. a block finalized a message identifier;
2. authenticated application state contained a key/value fact;
3. supplied message bytes produce that identifier; and
4. the message bytes remain available.

Yano will provide typed proof subjects and portable proof bundles for the first three. Availability
requires a separate retention or external data-availability promise and is not implied by a root.
Commitment-format and proof-encoding identifiers will describe algorithms and byte contracts, not
implementation projects. CCL/ZeroJ compatibility and tested versions remain descriptive
conformance metadata rather than consensus identity.

For L1 verification, MPF will gain a bounded, versioned on-chain proof ABI and reusable verifier.
The existing eUTxO MPF converter and on-chain implementation prove that this is feasible, but they
are product-local today. Classic JMT remains an off-chain-only profile for this ADR.

This ADR **refines and supersedes ADR-030 as the implementation direction**. ADR-030's separation of
state, authorization, approval, and effects is accepted. Its proposed parallel actor/policy/runtime
model is not adopted literally because current code already contains more mature reusable role and
approval machinery. Intended product semantics may be retained, but no current app-chain state key,
root, identifier, artifact boundary, or wire encoding is preserved merely for compatibility.

---

## 2. Current behavior — storage and commitment

This section records the implementation as it exists before proposing changes.

### 2.1 One block has one state transition and one state root

`AppChainEngine` opens an `AuthenticatedStateBackend` candidate at the committed tip, applies the
configured `AppStateMachine`, prepares the candidate, and puts its post-state root into the
`AppBlock`. A follower re-executes the same block and rejects it when the computed root differs.

`AppLedgerStore` commits the following in one RocksDB `WriteBatch`:

* the canonical CBOR-encoded `AppBlock` in `app_blocks`;
* the tip, block hash, state root, commitment identity, and other metadata in `app_meta`;
* message-id-to-height lookup entries in `app_msgs`;
* MPF nodes or JMT nodes/values/roots for the authenticated state candidate;
* query-index entries; and
* effect records and effect-runtime state.

The atomic batch is important: the block, root, indexes, and requested effects cannot represent
different committed heights after a successful write. The `app_msgs` and query indexes are
operational indexes, however. They are not themselves committed key/value facts under the
application state root.

Application state is an opaque canonical byte key/value space exposed through `AppStateReader` and
`AppStateWriter`. A state machine chooses its data model and encoding. The backend commits exactly
those bytes; it does not understand documents, log records, owners, roles, or approvals.

### 2.2 State commitment profiles

The selected `StateCommitmentIdentity` binds the closed profile, format fingerprint, and genesis id.
The root alone is therefore not enough to interpret a proof; a verifier must also pin this identity.

| Profile | Runtime status | Delete semantics | Historical proof model | Verification target |
|---|---|---|---|---|
| `mpf-blake2b256-v1` | Implemented and released | Physical delete | Immutable retained MPF nodes by root/height | Off-chain now; on-chain feasible |
| `jmt-blake2b256-v1` | Implemented and released | Reserved logical tombstone | Versioned roots/nodes with pruning watermark | Off-chain only in this ADR |
| Poseidon JMT profile | Declared, deliberately unreleased | Profile-defined | Not a supported runtime proof contract | None |

MPF native proofs use `ccl-mpf-proof-wire-v1`. Classic JMT native proofs use
`ccl-classic-jmt-proof-cbor-v1`. These are backend-specific proof formats even though they are
returned through the common `StateProof` contract.

### 2.3 Composite storage format

`CompositeStateMachine` is already an `AppStateMachine`. It does not create one root per component.
It presents each component with a namespaced view of the same global authenticated state.

`CompositeCommitmentV1.componentKey(componentId, localKey)` produces the physical committed key:

```text
"yano-composite-state-v1\0"
    || uint8(component-id-length)
    || component-id UTF-8
    || uint16(local-key-length)
    || local-key
```

The component sees its local key; the backend and proof API see the physical key. The composite
profile, component versions/configuration, routes, state compatibility, activation, query paths, and
effect quotas are themselves committed under the composite profile marker. This protects consensus
from two nodes silently assembling different applications.

There is only one global root. A component reader's `stateRoot()` consequently denotes that global
root, not a component subroot.

### 2.4 Current message commitments are different from application state

Every app block contains `messagesRoot`, a binary Blake2b-256 Merkle root over the ordered list of
message identifiers. A message identifier is content-derived from the canonical signed message
encoding. The implementation duplicates an odd final node while constructing the tree.

This creates several possible evidence paths:

| Mechanism | What is committed | Compact proof available today? | Anchored by `stateRoot`? |
|---|---|---:|---:|
| `app_msgs` RocksDB index | message id -> block height | No; server lookup only | No |
| `AppBlock.messagesRoot` | ordered message identifiers in that block | No public path generator/verifier | No; it is in the block header |
| `OrderedLog` application state | message id -> `[height,index,topic,sender]` | Yes, as a generic state proof | Yes |
| `EvidenceBundle` | full retained block/message-id list, certs, and hash chain | Recomputes whole roots; not compact | Can link blocks to a claimed anchor |

`OrderedLog.recordMessage` already provides the strongest generic state-root path: it writes the
message identifier as a state key and a canonical position record as its value. A proof for that key
therefore establishes that the state at the proved height recorded the message as finalized. Other
state machines do not automatically record this fact.

The ordered-log value does not store the original body. A claimant supplies the signed message
separately, and a verifier recomputes its content-derived identifier before accepting that it is the
message described by the state proof.

Retention can strip message bodies and authentication material while preserving identifiers,
headers, `messagesRoot`, and finality certificates. At that point evidence can still prove identifier
finalization, but cannot re-verify message content or make the body available. A commitment is not a
data-availability service.

### 2.5 L1 facts are currently sequenced as reserved messages

`L1Observer` is a deterministic block-scoped plugin. Every member observes its own L1 stream; the
scheduled proposer injects matching claims as signed `~l1/<observer-id>` messages, and followers
recompute and verify those claims before voting. `L1ObservationService` stability-gates and retains a
verification window. Historical catch-up below the local window relies on the finalized app-chain
certificate because the joining node may no longer possess that L1 history.

The application currently receives the original reserved messages only through `AppBlock.messages()`.
A machine such as eUTxO scans the block, recognizes configured observation topics, calls
`L1Observation.decode`, checks its product claim, and applies state. The runtime has already decoded
and checked the same envelope earlier, but does not pass that typed result to the state machine.

ADR-028 proposes `L1EpochObserver` and an epoch-pinned `L1EpochState` for the **observation phase**.
That handle is used while a member has the L1 boundary state and emits canonical observation bytes.
It cannot safely be exposed during app-block `apply()`: a fresh member must replay an old attestation
from the app block alone, even when its local protocol-parameter/stake history was pruned or never
existed.

---

## 3. Current behavior — proof generation and verification

### 3.1 State proof generation

`AuthenticatedStateBackend.prove(height, canonicalKey)` generates an inclusion, exclusion, or
logical-tombstone proof at a finalized snapshot. The REST proof endpoints accept a physical key and
return a `StateProofEnvelope` containing:

* proof schema version 1;
* chain id and finalized block hash;
* snapshot height, state root, state version, and commitment identity;
* canonical key, presence, and value;
* native proof encoding and bytes; and
* the block finality certificate.

Historical proofs are limited by the backend's retained history. JMT exposes an explicit pruning
watermark. MPF currently retains its historical immutable proof nodes unless an operator uses a
different retention implementation.

The proof endpoint is deliberately storage-shaped. For composite state it expects the namespaced
physical key. Some domain clients, including authenticated-map, expose helpers that derive that key,
but there is no common typed proof-subject registry across state machines.

### 3.2 Off-chain verification and its trust boundary

`appchain-client` `ProofVerifier` implements native MPF inclusion/exclusion verification and classic
JMT proof verification. It rejects mismatched profile ids, native encodings, fingerprints, and
unreleased profiles.

There are three materially different verification modes:

1. **Native proof math only.** This proves that the key/value is consistent with the root carried in
   the same response. It does not establish that the root belongs to the intended chain.
2. **Trusted-root verification.** The caller supplies an independently obtained
   `TrustedStateRoot`, and the proof must match it.
3. **Certified verification.** The caller pins chain id, genesis/commitment identity, member set,
   and threshold, then verifies the block hash, finality certificate, and native proof.

Only the latter two authenticate the root independently of the server that supplied the proof.
`AppAnchorCommitment` correctly treats server-reported L1 references as evidence to be checked, not
as proof that a Cardano transaction/output/datum exists.

### 3.3 L1 anchors today

Metadata anchors publish a tuple containing chain id, height range, block hash, and state root. They
are useful for observation and audit, but another validator cannot consume transaction metadata as
a stable reference input in the same way it can consume a script output.

Script anchors use a state-thread NFT and an inline v1 datum:

```text
[version, chain_id, height, block_hash, state_root, member_keys, threshold]
```

The Aiken validator enforces datum shape, chain/version continuity, monotonic height, the continuing
thread output, and signatures from the input datum's threshold. It authenticates advancement of a
root. It does **not** interpret an MPF/JMT proof or an application key/value.

The v1 datum does not carry the state commitment profile, format fingerprint, or genesis id.
Consumers must therefore pin those facts by validator configuration/deployment and by the identity
of the trusted anchor thread. A future generic anchor version should make this binding explicit; v1
must not be reinterpreted in place.

### 3.4 Can the current MPF proof be verified on-chain?

**The cryptographic answer is yes; the generic product answer is not yet.**

The eUTxO extension already contains:

* `EutxoMpfProofConverter`, which checks native proof math and turns a CCL MPF inclusion proof into
  a bounded root-fold (the independently trusted anchor authenticates the root later);
* a versioned `EutxoMpfProof` contract with key, value, and fold bounds; and
* on-chain MPF inclusion/exclusion logic used by settlement/nullifier validators.

This demonstrates that CCL MPF roots can be consumed by Cardano validators. It is not yet a generic
app-chain facility because the normalized proof ABI, converter, claim semantics, validators, and
cost limits live under the eUTxO product. A raw REST `ccl-mpf-proof-wire-v1` response cannot simply be
passed to the stock anchor validator.

A reusable on-chain path still needs:

* a stable, bounded Plutus-data proof ABI independent of CCL Java object layout;
* a generic wire-to-on-chain converter that fails closed;
* a reusable MPF verifier library/artifact and conformance vectors;
* explicit binding to the trusted anchor thread, chain, height, block hash, and MPF profile;
* typed key/value claim decoding so that proving arbitrary bytes has application meaning; and
* execution-budget limits tested against maximum accepted proofs.

Classic JMT already has sound off-chain verification. No on-chain JMT verifier is accepted by this
ADR, and applications requiring L1 consumption must select the released MPF profile.

### 3.5 Can someone prove that a message was available?

Not from an MPF root alone, and not indefinitely from any current root.

The current system can prove increasingly strong statements when the corresponding artifacts are
available:

```text
finality certificate + block header
    -> this committee finalized this block and its messagesRoot/stateRoot

messagesRoot + all message ids (EvidenceBundle today)
    -> this message id was in this finalized block

OrderedLog state key/value + MPF/JMT proof
    -> authenticated state recorded this message id and position at this height

original signed message + recomputed message id
    -> these exact supplied bytes are the committed message
```

None of these says that an independent party could retrieve the body at a later time. That requires
a retention SLA, an object-store/IPFS commitment with retrieval guarantees, or a separate
data-availability protocol. ADR-031 will use the terms **finalized**, **state-recorded**,
**content-verified**, and **available** separately in APIs and documentation.

### 3.6 Practical answer with the current code

For an ordered-log chain, an off-chain verifier can do this today:

1. independently read/verify the Cardano anchor and obtain its height, block hash, and state root;
2. request the state proof at that retained height using the message id as the ordered-log key;
3. verify the proof against the independently obtained root and pinned commitment identity;
4. decode `[height,index,topic,sender]` and compare it with the claim; and
5. when the body is supplied, recompute its message id and signature.

This proves that the exact supplied message was recorded in authenticated state at the anchored
snapshot. For a composite ordered-log, the proof key must first be wrapped with the component's
`CompositeCommitmentV1` namespace.

There is no equivalent MPF proof by message id for a state machine that did not write such a record.
Its current path is block `messagesRoot` plus the full block/evidence bundle. There is also no stock
generic Cardano validator that accepts the REST state proof and ordered-log claim. The eUTxO code
contains the cryptographic building blocks, which Phase 6 will generalize.

---

## 4. Current behavior — state machines and composition

### 4.1 Existing root contract

`AppStateMachine` is the sole configured application SPI. It provides deterministic initialization,
admission (`validate` and `validateForBlock`), privileged-system-message admission, operational
status, block application, effect-result handling, and root-fixed queries.

Stock implementations include ordered-log, key/value registry, approvals, balances, document trail,
authenticated map profiles, evidence registry, ZK machines, eUTxO, and showcase applications.

`OrderedLog` is already a useful example of extraction: its state-writing logic is a core-api helper
and is reused by `OrderedLogStateMachine` and a ZK application. This direction should be generalized,
but with a more explicit transition-plan contract for multi-write actions.

### 4.2 Existing composite pattern

`CompositeStateMachine implements AppStateMachine`. It provides capabilities a simple loop over
state machines cannot safely provide:

* exact topic routing;
* component namespaces in one authenticated root;
* deterministic profile/version/activation commitments;
* query routing;
* effect ownership, quotas, and generation-safe callbacks; and
* declared atomic cross-component workflows with bounded participant views.

`StateMachineComponentAdapter` already wraps a normal `AppStateMachine` as a `CompositeComponent`.
Existing profiles use this to compose key/value, approval, document-trail, and evidence-registry
state machines. Therefore the requested property — “a composite application is an
`AppStateMachine` and reuses existing stock state machines” — is substantially present today.

The separate component interface exists for real isolation requirements, not because composite is a
different root SPI. Removing namespaces or allowing arbitrary sibling state access would weaken the
design.

### 4.3 Gaps in the current adapter and workflow model

The present reuse is incomplete in four ways.

#### A. State-aware child admission is lost

`CompositeStateMachine.validateForBlock` routes to `CompositeComponent.validate`, but
`CompositeComponent` has no candidate-height/committed-reader equivalent. Consequently
`StateMachineComponentAdapter` cannot forward the wrapped machine's `validateForBlock` contract.
A standalone machine can therefore make a state-aware admission decision that its composite form
does not make.

This is a correctness boundary and must be fixed before presenting direct machine composition as a
transparent operation.

#### B. Synthetic block/message adaptation is brittle

The adapter constructs a routed synthetic `AppBlock`: it filters messages, recomputes
`messagesRoot`, clears the finality certificate, and retains global metadata/state-root fields.
Several workflows also construct synthetic messages or blocks and invoke another machine's public
entry points manually. This duplicates routing behavior and makes whole-block metadata semantics
easy to misuse.

#### C. Business logic remains trapped in large machines

Wrapping a whole `DocTrailStateMachine` or `KvRegistryStateMachine` is useful when a composite wants
the complete product. It does not help a custom workflow reuse “append a canonical document event”
or “apply a conditional key/value mutation” as one part of a larger atomic transition. Workflows
then duplicate code or imitate external messages.

#### D. Mutation is not transactionally scoped per message

The block candidate is atomic at commit, but `AppStateWriter` does not offer a per-message rollback
savepoint. A reusable capability that writes before all authorization/business checks finish can
leave partial candidate changes when the caller catches a rejection. Reuse therefore needs a
read/decide plan followed by an explicit commit, not helpers that mutate opportunistically.

#### E. Other root-only hooks are not transparent through the adapter

Composite handles privileged system submission and operational status for composite-profile
governance itself. It does not forward a wrapped machine's `validatePrivilegedSystemSubmission` or
merge that machine's `operationalStatus`. Direct machine composition therefore needs an explicit
routing/ownership rule for these hooks; blindly forwarding every component would create ambiguous
system-topic authority and status-key collisions.

#### F. Consensus-accepted L1 inputs have no typed execution context

Every custom machine that consumes `~l1/*` currently repeats topic matching and envelope decoding in
`apply()`. Passing a live L1 handle would break replay, while adding a separate pre-block L1 callback
would lose relative ordering between L1 observations and ordinary messages. The missing abstraction
is therefore a block-bound execution context, not an L1 database service or an out-of-band callback.

Composite has an additional gap here: `validateAgainstRuntime` currently accepts every `~*` topic
before component routing. A component that declares `~l1/<observer-id>` therefore does not receive
its wrapped machine's application-level admission check. Runtime observation verification proves
that configured observers agreed on the claim bytes; it does not prove that those bytes satisfy the
consumer's protocol-param, stake-chunk, deposit, or other domain schema.

---

## 5. Current authorization, governance, and approvals

### 5.1 Reusable capability already exists

ADR-019 is implemented in `appchain-role-workflow` and related contracts. It contains domain actor
records, keys and revisions, role policies, role-aware approvals, signatures, governance commands,
components, workflows, and client/domain APIs.

ADR-025.2 already assembles the governed authenticated-map product from:

* `DomainActorRegistryComponent`;
* `RoleAwareApprovalsComponent`;
* `AuthenticatedMapComponent`; and
* declared authorization/approval workflows.

`AuthenticatedMapDirectAuthorizer` verifies domain-separated commitments, chain/genesis binding,
deadlines, policy and actor revisions, active actor keys, signatures, approval payloads, and replay
consumption. It then returns map-specific coverage/consumption results so authenticated-map can apply
the mutations and receipt atomically.

This is not the monolithic starting point assumed by ADR-030. The storage backend is already generic,
and governed authorization is already composed externally for the advanced authenticated-map
profile.

### 5.2 Similar words currently name different consensus domains

Yano has at least four mechanisms that must remain distinct:

| Mechanism | Governs | Consensus consequence |
|---|---|---|
| App-chain membership | sequencer/member authority and finality | Who can produce/finalize blocks |
| Composite profile governance | executable component/profile epochs | Which application code/config is active |
| Domain actor/role policy governance | business identities, keys, roles, revisions | Who may authorize a business action |
| Application approval lifecycle | one proposed business action | Whether that action may execute |

“Governance” is a cross-cutting product concern, but it is not one universal state machine. Merging
these layers would make application roles capable of altering consensus membership or executable
profiles, or would force core/runtime code to understand business policy.

### 5.3 Assessment of ADR-030

| ADR-030 direction | ADR-031 decision |
|---|---|
| Separate authenticated state from authorization and approval | Accept |
| Canonical, domain-separated action commitments | Accept and reuse existing contracts |
| Deterministic, versioned, bounded policy evaluation | Accept |
| Preserve authenticated-map behavior | Preserve intended product semantics where still desirable; no byte/root/API compatibility requirement |
| Add a new generic `Actor` model | Do not duplicate ADR-019 domain actors |
| Put an authorization hook in the runtime | Reject; runtime remains application-policy agnostic |
| Policies receive generic `Object` context | Replace with closed/versioned typed commands and facts |
| Approval engine directly executes arbitrary application changes | Replace with transition plans and declared atomic bindings/workflows |
| Treat owner, role, governed, and approval as interchangeable policies | Amend; owner and action coverage are often domain-specific semantics |
| Add a broad policy SPI | Defer until two independent applications prove a safe closed extension boundary |

ADR-030 is directionally valuable, but implementing its proposed class hierarchy as written would
create a parallel identity/approval model and hide consensus-critical state reads behind a generic
service. ADR-031 instead extracts common kernels from the code that already works.

---

## 6. Target architecture

### 6.1 One public root, composition as a builder

`AppStateMachine` remains the only deployable/configured application type.

The composite API will add a preferred registration path conceptually like:

```java
ComposableAppStateMachine.builder("document-service")
        .machine(documentDescriptor, new DocTrailStateMachine(documentConfig))
        .machine(logDescriptor, new OrderedLogStateMachine(logConfig))
        .workflow(releaseWorkflow)
        .build(); // returns AppStateMachine
```

The builder applies namespace, route, profile, query, effect-owner, quota, and generation rules
directly. `CompositeStateMachine` may remain an implementation name if it is still accurate; it is
not retained as a compatibility alias. The public `CompositeComponent` and
`StateMachineComponentAdapter` APIs are removed. Any necessary routed execution port is internal to
the composition module, and component-only products are rebuilt as transition capabilities or
ordinary `AppStateMachine` implementations.

This is an API simplification, not removal of component isolation. Yano will not compose machines by
calling all of them over an unrestricted root writer.

### 6.2 Deterministic transition capabilities are the primary reuse unit

Stock machine logic will be split into three layers:

```text
wire contract / canonical codecs
              |
              v
deterministic transition capability
  (typed command + scoped reads -> decision/plan)
              |
       +------+------+
       |             |
       v             v
standalone       composite/custom
AppStateMachine  workflow/AppStateMachine
adapter
```

A capability must:

* accept a versioned typed command, deterministic context, and explicitly scoped read views;
* perform no wall-clock, network, filesystem, random, or hidden global-state access;
* return either a typed rejection or immutable `TransitionPlan`;
* declare bounded writes, deletes, effects, receipts, and one-use consumptions;
* never mutate state during the decision phase; and
* have a commit adapter that applies a valid plan without reinterpreting it.

The exact Java names are intentionally left to the first implementation spike, but the semantic
split is normative. A representative shape is:

```java
interface TransitionCapability<C, F> {
    TransitionDecision decide(C command, TransitionContext context, F facts);
}

record TransitionPlan(
        List<StateMutation> mutations,
        List<EffectRequest> effects,
        List<Consumption> consumptions,
        List<Receipt> receipts) {}
```

Plans must use canonical byte encodings and deterministic ordering. The framework validates all
bounds and authorization bindings before committing any item.

Initial extraction candidates are:

1. `FinalizedMessageIndex` from `OrderedLog`;
2. document-trail append/query-key transitions;
3. key/value conditional mutations;
4. approval proposal/vote/terminal-state transitions;
5. authorization evidence verification and approval consumption; and
6. ADR-028 epoch-parameter/stake attestation transitions over typed sequenced observations.

Standalone stock machines become thin routing/codec adapters over these same capabilities. Custom
machines and workflows call the capabilities directly instead of constructing synthetic blocks.

### 6.3 Complete lifecycle semantics for routed machines

Before direct `AppStateMachine` registration is considered transparent, the internal routed port
must carry every relevant root semantic:

* ordinary and state-aware admission;
* candidate height and committed namespaced reader;
* privileged-system-message policy;
* apply context and routed message position;
* effect-result ownership/generation;
* operational status; and
* root-fixed queries.

The composite implementation forwards `validateForBlock` to a routed machine with a namespaced
committed reader. Semantic differential tests prove the same accept/reject result in standalone and
composed forms.

Synthetic `AppBlock` projections are removed. Routed execution receives an explicit context carrying
the original global block identity, stable original message indexes, and only the messages/state
views declared for that route. No internal object pretends to be an independently finalized block.

### 6.4 Authorization capability layers

Reusable authorization will be factored into explicit layers:

1. **Evidence verification** — canonical action commitment, chain/genesis, deadline, key/signature,
   actor/key/policy revisions, and replay id.
2. **Actor and role facts** — ADR-019 domain actor registry and role policy snapshots.
3. **Approval lifecycle** — proposal identity, votes, threshold/role satisfaction, expiry, terminal
   state, and one-use consumption.
4. **Application binding** — translates an authorized action into the exact domain mutations and
   coverage rules.
5. **Application receipt** — records what was authorized and consumed using a domain-owned schema.

The first three can be reused. The last two normally remain application-specific. For example, an
authenticated-map batch must decide which mutation indexes a role or approval covers; a document
trail must decide which document/version/action the same approval authorizes.

Policy evaluators receive explicit immutable facts. They cannot open arbitrary sibling state or use
a runtime service locator. New policy types are closed, versioned, bounded consensus contracts.
An external plugin SPI will be introduced only after at least two non-map applications demonstrate
the same stable need and conformance tests can pin its complete behavior.

“Owner” stays with the resource model unless a genuinely common controller capability emerges.
Ownership can mean entry controller, document controller, token owner, or collection administrator;
collapsing these prematurely would make authorization appear reusable while moving ambiguity into
untyped context.

### 6.5 A standard optional finalized-message index

Extract the current ordered-log record operation as `FinalizedMessageIndex` (final name may differ).
It writes a canonical versioned record keyed by message id, at minimum:

```text
[schema-version, height, original-message-index, topic, sender]
```

The capability will support explicit inclusion policy (for example, whether system topics are
indexed) and a committed namespace/configuration. It is opt-in because indexing every message grows
authenticated state and changes roots.

`OrderedLogStateMachine` becomes a stock façade over this capability. Other stock or custom machines
can invoke it in the same transition plan. This provides a uniform MPF/JMT state-proof path without
forcing core runtime policy. The refactor defines one final ordered-log key/value schema for the
supported baseline; current preview bytes need not be retained, interpreted, or migrated.

### 6.6 Block execution context for sequenced L1 facts

Replace the preview apply overload chain with one immutable-context execution method. The
representative API is:

```java
public interface AppStateMachine {
    void apply(
            AppBlockExecutionContext context,
            AppStateWriter writer,
            AppEffectEmitter effects
    );
}

public interface AppBlockExecutionContext {
    AppBlock block();
    List<SequencedL1Observation> l1Observations();
    Optional<SequencedL1Observation> l1ObservationAt(int originalMessageIndex);
}

public record SequencedL1Observation(
        int originalMessageIndex,
        byte[] messageId,
        L1Observation observation
) {}
```

Names are illustrative; the following semantics are normative:

1. This is the only framework apply entry point. The current two-/three-argument preview overloads
   are removed, and all first-party machines/plugins are updated in the same change.
2. The context contains only data derivable from the finalized/candidate app block and immutable
   chain-generation configuration. It is not a service locator.
3. `l1Observations()` contains only structurally decoded observations that passed the runtime's
   proposal/catch-up acceptance rules, sorted by original block message index.
4. Verification provenance (`locally recomputed` versus `older than local window and accepted by
   certified history`) is deliberately not exposed. Nodes can have different local history, and a
   state machine must never branch on that local fact.
5. Original message index and message id remain available so capabilities can preserve block order,
   produce stable receipts, and bind results to the exact sequenced envelope.
6. The context and all returned byte arrays/collections are immutable snapshots valid only during
   the callback. Implementations must not retain them.
7. Invalid, topic/body-mismatched, ahead-of-view, or unstable observations never appear in the
   context because the block is rejected/deferred before state execution.
8. Machines still decide the application meaning of `observation.claim()`. Runtime acceptance says
   the configured observer produced the bytes; it does not decode protocol params, stake entries, a
   deposit, or another product schema.
9. The runtime, testkit, composition layer, examples, and direct library callers use this same entry
   point. There is no legacy direct-call path and no fabricated “empty” execution context.

Runtime validation should produce one bounded accepted-input index that is reused by execution,
rather than decode a large observation once for verification and again for the state machine. The
framework decodes only the observation envelope; epoch-stake chunk semantics remain lazy and
application-owned. Context construction must stay within the committed block/message limits and
must not create an unbounded second materialization of every claim.

This method preserves relative ordering without adding per-observation callbacks. A machine can
fold over `context.block().messages()` and use `l1ObservationAt(index)` at the exact position where
the reserved message occurs. Extracted transition capabilities receive the selected
`SequencedL1Observation` as an explicit typed input.

For ADR-028 the data path becomes:

```text
PostEpochTransitionEvent
    -> L1EpochObserver reads ephemeral, epoch-pinned L1EpochState
    -> canonical tagged L1Observation claim
    -> stability gate + proposer injection + follower recomputation
    -> finalized AppBlock contains the observation bytes
    -> AppBlockExecutionContext exposes the sequenced decoded observation
    -> epoch-params / epoch-stake transition capability writes authenticated state
    -> MPF state root is anchored and its typed fact can be verified on-chain
```

Replay starts at the finalized `AppBlock` step; it never calls `L1EpochState` for old epochs.

Composite routing remains explicit. A component or workflow must declare the exact
`~l1/<observer-id>` topic it owns. The composite creates a routed execution context containing only
the observations/messages visible to that route, while retaining each observation's original global
message index. It must not broadcast all L1 facts to every child or renumber them as though the
synthetic routed block were independently finalized.

Composition uses one internal routed-context contract. `CompositeWorkflow` is either reduced to a
transition capability over declared views or made internal; it is not another public lifecycle SPI.

The execution context does not replace application admission. Composite must stop blanket-accepting
declared `~l1/*` routes and forward their state-aware validation to the owning machine/component or
workflow in addition to runtime observer verification. Framework-owned topics such as effect results
and composite-profile governance retain their dedicated runtime/root handling. An L1 consumer validates
claim schema, version, bounds, and configured observer identity; the runtime validates observation
authenticity/stability.

`AppStateMachineContext` remains the construction/configuration context and does not gain an L1
handle. `AppQueryContext` remains committed-state-only. The new execution context is deliberately
separate so no off-consensus or live-ledger object leaks into deterministic apply/query code.

---

## 7. Portable proof architecture

### 7.1 Implementation-neutral commitment and proof identifiers

Consensus and portable-wire identifiers name the contract being implemented, not the library or
project that first supplied it. A conforming implementation can be replaced without changing chain
identity, while a change to root calculation, canonical bytes, proof structure, or verification
semantics requires a new identifier/version even when the same library supplies both versions.

The supported baseline separates:

1. **commitment profile id** — tree family, hash/field choice, and consensus semantics;
2. **commitment format id** — exact canonical node/root and persistence contract where a separate
   format identity is required;
3. **proof encoding id** — exact portable/native proof byte grammar; and
4. **implementation metadata** — non-consensus descriptive data such as “Cardano Client Lib
   compatible”, “ZeroJ compatible”, tested artifact versions, and conformance status.

Rename `dependencyDescriptor` to `commitmentFormatId` and `nativeProofEncoding` to
`proofEncodingId` (or `nativeProofEncodingId` if the field deliberately remains backend-native;
final record-component names may differ). The API must not encourage dependency branding in
consensus identity. Implementation metadata is reported separately and is never hashed into the
format fingerprint merely to name the provider. The fingerprint continues to cover every identifier
and property that actually changes roots, storage interpretation, or proof verification.

The clean baseline applies these candidate replacements after confirming the exact grammars with
golden vectors:

| Preview identifier | Supported-baseline identifier | Note |
|---|---|---|
| `mpf-blake2b256-v1` | unchanged | already describes the commitment profile |
| `ccl-mpf-legacy-blake2b256-v1` | `mpf-blake2b256-format-v1` | format compatibility with CCL belongs in metadata |
| `ccl-mpf-proof-wire-v1` | `mpf-proof-wire-v1` | version identifies the wire grammar, not the Java producer |
| `jmt-blake2b256-v1` | unchanged | already implementation-neutral |
| `classic-radix16-blake2b256-v1` | unchanged | already a descriptive format id |
| `ccl-classic-jmt-proof-cbor-v1` | `jmt-proof-cbor-v1` | profile identity supplies hash/commitment semantics |
| `jmt-poseidon-bls12381-v1` | unchanged | already identifies tree, hash family, and field |
| `zeroj-poseidon-jmt-v1` | `jmt-poseidon-bls12381-format-v1` | ZeroJ becomes a tested implementation |
| `zeroj-poseidon-jmt-proof-v1` | `jmt-poseidon-bls12381-proof-v1` | retain a distinct id if its proof grammar differs from generic JMT CBOR |

`-v1`, `-v2`, and later suffixes version the contract, not the dependency release. For example,
CCL and a second independent implementation may both produce `mpf-proof-wire-v1`; byte-for-byte
and adversarial-vector conformance establishes that claim. If the wire grammar changes, the result
is `mpf-proof-wire-v2` regardless of whether the producer library changed.

Use tree-family-first naming consistently. A future Poseidon MPF profile should therefore be named
`mpf-poseidon-<field>-v1`, not the ambiguous `poseidon-mpf-v1`. The currently declared Poseidon
state profile is JMT and must not be renamed as MPF. Likewise, implementation-specific module,
provider, or experimental product labels may retain `ccl`/`zeroj` only when selecting that concrete
implementation is genuinely the meaning of the label; such labels must not double as commitment or
proof-format identities.

### 7.2 Make typed proof subjects the supported surface

The current storage-shaped `StateProofEnvelope` preview API is replaced by the typed portable proof
surface. Backend-native proof bytes remain inside that envelope and may remain available through a
diagnostic/devtools API, but callers are not required to know physical prefixes or trust a root
reported by the same server.

A domain proof subject will define how application meaning maps to committed bytes:

```text
subject type + subject schema
    -> canonical physical key
    -> expected value decoder / semantic claim
    -> supported commitment profiles and verification targets
```

Examples include an authenticated-map entry, document revision, ordered-log message record,
approval outcome, epoch protocol parameter, epoch stake credential, and composite profile marker.
Subject codecs live with their contracts, not in the runtime. Composite namespace derivation is
applied by a shared client/server helper.

This removes the current need for every caller to know a physical prefix while preserving the exact
proof underneath.

### 7.3 Add compact block-message inclusion proofs

Yano will expose a versioned `MessageInclusionProof` for `AppBlock.messagesRoot` containing the
message id, original index, leaf count, sibling path, tree/hash-domain version, and block identity.
Generation and verification must exactly match the current odd-leaf duplication rule.

This proof answers “this message id was in this block” without transferring all message ids. It is
separate from a state proof and remains useful even for applications that do not use
`FinalizedMessageIndex`.

An optional bundle may combine:

* the supplied signed message and content-id verification;
* compact `messagesRoot` inclusion;
* block header and finality certificate; and
* an independently trusted L1 anchor path.

No API may label this `available=true` merely because the supplied body verifies.

### 7.4 Portable proof bundle baseline

The first supported bundle carries enough identity to prevent proof substitution:

```text
PortableProofBundleV1
  claim                         typed subject and expected assertion
  commitmentIdentity            profile + fingerprint + genesis id
  snapshot                      chain id + height + block hash + state root
  proof                         native off-chain or normalized MPF-on-chain proof
  finality                      certificate and membership context/reference
  anchor                        independently verifiable Cardano tx/output/datum reference
  contentBinding?               optional original message + message-id derivation
```

The server may assemble this bundle, but verification never trusts fields merely because they occur
together. Off-chain clients must pin either a trusted root or a full chain/committee/anchor trust
context. On-chain consumers must locate the trusted anchor output/reference input themselves and
compare the proof root with its datum.

### 7.5 Generic MPF on-chain proof profile

Promote the validated eUTxO approach into shared proof modules:

* **contracts:** bounded canonical Plutus-data ABI for MPF inclusion and exclusion;
* **client:** strict `mpf-proof-wire-v1` to normalized-proof conversion, with CCL as one tested
  producer;
* **on-chain:** reusable root-fold verifier and anchor-reference helpers;
* **vectors:** common Java/on-chain positive and adversarial conformance corpus.

The normalized proof is a transport ABI, not a new state root algorithm. It must reconstruct exactly
the released `mpf-blake2b256-v1` root.

The converter must enforce canonical lengths, maximum fold depth, key/value limits, legal branch
forms, and exact root reconstruction before encoding. The on-chain verifier repeats all checks needed
for hostile redeemers rather than trusting the converter.

The consuming validator then performs two checks:

```text
cryptographic check:
    normalized MPF proof reconstructs anchorDatum.state_root

semantic check:
    decoded key/value means the application claim required by this validator
```

The shared library provides the cryptographic check and anchor binding. Application validators own
the semantic check.

### 7.6 Complete anchor identity in the supported baseline

Replace the preview script-anchor datum and validator before release. The first supported datum
binds at least:

* datum schema version;
* chain id and chain genesis id;
* application/profile identity;
* state commitment profile id and format fingerprint;
* height, block hash, and state root;
* member keys and threshold; and
* the state-thread asset/validator identity implied by the consumed output.

There is no preview-anchor migration. The validator/script hash and state-thread deployment are
regenerated, preview anchor outputs are abandoned, and all supported deployments start from the
complete datum. The final supported schema may use wire version 1 because the earlier preview datum
never becomes a compatibility contract.

### 7.7 JMT remains off-chain

Classic JMT proofs continue through `ProofVerifier` and portable off-chain bundles. The proof bundle
must report that its verification target is off-chain. Configuration validation should reject an
application that declares “L1 state-proof consumption required” while selecting JMT.

---

## 8. Module and dependency direction

The target dependency direction is:

```text
core-api contracts and authenticated-state contracts
        |
        +--> stock transition capabilities / stock machine adapters
        |
        +--> authorization contracts and ADR-019 actor/approval capabilities
        |
        +--> composition runtime (routing, namespaces, profile, effects, workflows)
        |
        +--> proof clients and optional on-chain proof contracts
```

Stock transition capabilities must not depend on composite. Product/profile assembly modules may
depend on both stock capabilities and composite.

Where `appchain-stdlib` currently depends on composite to expose an assembled authenticated-map
profile, move that assembly/provider to a product/assembly module so stock reusable logic does not
acquire a composition dependency. Remove obsolete provider classes and update all preview callers;
do not retain forwarding façades solely to preserve package or artifact names.

The runtime remains responsible for deterministic execution, authenticated storage, finality,
effects, and proof generation. It does not import application authorization policy.

---

## 9. Preview reset and first-supported-baseline rules

1. This clean break applies only to the unreleased app-chain feature and its app-chain plugins,
   clients, storage, proofs, and anchor scripts. It does not authorize changes or deletion in the L1
   Cardano node/ledger databases.
2. Pre-refactor app-chain databases are disposable. Operators create a fresh app-chain storage
   directory and genesis/application identity after the refactor; there is no state, block, proof,
   effect, membership, profile, or anchor migration.
3. Runtime writes a new explicit app-chain storage-format marker. A non-empty store with the old or
   missing marker fails startup with a reset instruction. Runtime never guesses, partially upgrades,
   or mixes formats.
4. `AppStateMachine`, composition/workflow, observer, proof, client, and plugin SPIs may break now.
   Remove obsolete overloads, adapters, deprecated methods, unsafe verifier conveniences, duplicate
   contracts, and forwarding façades rather than carrying them into the first release.
5. State keys/values, namespaces, profile descriptors, action/approval identifiers, signatures,
   effects, blocks, proof envelopes, observation wire formats, and anchor datums may change when the
   new design is simpler, safer, or more canonical.
6. Preserve intentional product semantics only after reviewing them. Characterization tests explain
   current behavior and detect accidental loss; they are not byte/root compatibility gates and do
   not require preserving defects.
7. Retain a current algorithm/identifier, such as the CCL MPF proof format, only when it remains the
   best technical contract—not because preview data exists.
8. Regenerate fixtures, CDDL, golden vectors, scripts, example configs, client models, snapshots, and
   devtool templates from the new baseline. Delete preview-only fixtures and migration code.
9. Mixed old/new members and plugins fail before participating in consensus through catalog/SPI,
   application profile, storage marker, commitment identity, and genesis checks.
10. Once this baseline is declared supported/released, future compatibility and migrations require
    a new ADR. The preview reset is a one-time design-cleanup decision, not a permanent license to
    break deployed chains.

---

## 10. Implementation plan

### Phase 0 — characterize semantics and approve the clean baseline

* Record canonical blocks, message ids/roots, state keys/values, roots, native proofs, and finality
  envelopes for ordered-log, document-trail, key/value, approvals, authenticated-map, and a current
  composite profile.
* Run representative commands standalone and through current composition to identify intentional
  semantics, duplicated logic, and existing defects.
* Add retention vectors that distinguish full-body and id-only evidence.
* Record MPF/JMT inclusion, exclusion, tombstone, historical, and pruning-boundary behavior.
* Inventory every preview-only overload, adapter, deprecated method, unsafe verifier, duplicate
  approval/actor contract, forwarding façade, old CDDL/schema, storage reader, and vendor-branded
  consensus/wire identifier for deletion or replacement.

**Gate:** Each retained semantic behavior and each intentional breaking change is documented. No
current byte/root is presumed normative.

### Phase 1 — add block execution context and close composite lifecycle gaps

* Replace the apply overload chain with the single `AppBlockExecutionContext` method.
* Decode accepted `L1Observation` envelopes once into immutable, position-preserving context entries.
* Make the engine/effects kernel, testkit, plugins, and direct callers invoke that method exactly once.
* Update plugin TCCL/catalog boundaries to the new SPI and reject old plugin API/catalog versions.
* Route a restricted execution context through composite without losing original global indexes.
* Replace public component/workflow lifecycle APIs and `StateMachineComponentAdapter` with the one
  internal routed context plus transition capabilities.
* Replace composite's blanket `~*` admission with explicit framework-owned handling plus
  application validation for declared `~l1/<observer-id>` routes.
* Extend the internal component admission contract with candidate height and namespaced committed
  state.
* Forward a routed machine's `validateForBlock` exactly once.
* Add original global block/message identity to routed context.
* Delete synthetic routed-block construction.

**Gate:** All first-party machines/plugins compile only against the new SPI. Standalone and composed
admission, apply, L1-observation order, effect, and query semantics are equivalent where the design
requires equivalence; no legacy execution path remains.

### Phase 2 — introduce transition plans and extract stock kernels

* Define bounded canonical `TransitionDecision`/`TransitionPlan` contracts.
* Extract ordered-log message recording first; its reuse already has precedent.
* Extract document-trail and key/value mutation kernels.
* Make standalone machines delegate to those kernels using the newly approved canonical schemas.
* Replace all synthetic workflow calls with direct capability plans.

**Gate:** Rejected plans leave the candidate unchanged; standalone and composed uses of one
capability produce the same approved state/effects from the same scoped input.

### Phase 3 — simplify application composition

* Add direct `AppStateMachine` registration to the composite builder.
* Generate internal routes/descriptors from explicit developer configuration.
* Remove public expert component registration; advanced applications compose transition
  capabilities inside an `AppStateMachine`.
* Update examples so custom composite applications are presented as normal `AppStateMachine`
  providers assembled from stock machines/capabilities.

**Gate:** One canonical profile format commits routes, namespaces, capabilities, effects, queries,
and observer subscriptions; old profile markers are rejected.

### Phase 4 — consolidate authorization and approval

* Inventory duplicate legacy approvals and ADR-019 role-aware approvals by semantics, not names.
* Extract evidence verification, actor/role fact resolution, approval decision, and consumption
  planning from the governed authenticated-map path.
* Keep authenticated-map mutation coverage and receipts in the map binding.
* Use the extracted capability in one second application, preferably document-trail, before
  declaring the API stable.
* Delete duplicate legacy approval/actor APIs after all first-party callers use the consolidated
  contracts.

**Gate:** Authenticated-map and a second application use the same verifier without copied logic. New
golden vectors pin the approved action, actor/key revision, approval, consumption, and receipt
formats; preview identifiers are not migrated.

#### Phase 4 implementation result

The implementation inventory found three similarly named but semantically different mechanisms:

| Mechanism | Retained responsibility | Consolidation result |
|---|---|---|
| Basic `ApprovalsStateMachine` | Sender-key quorum over its own small command model | Retained as a distinct non-role capability; it is not an ADR-019 actor approval |
| ADR-019 actor approval lifecycle | Actor/key/organization resolution, frozen policy revision, proposal decisions, expiry and terminal state | `ActorApprovalProcessor` is the single implementation; the older `RoleApprovalWorkflow` now delegates to it instead of copying the lifecycle |
| Actor-governed policy mutation | Business-actor administration and bounded mutation activation | Remains distinct from application approval and app-chain membership governance |

`RoleAuthorizationCapability` is now the product-neutral read/decision boundary. Direct evidence
implements `DirectAuthorizationEvidenceV1`; approval bindings implement `ApprovalReferenceV1`.
The capability verifies chain/application/action binding, deadlines, current and claimed revisions,
policy status, actor role, organization status, key epoch, signature, approved proposal payload and
frozen policy digest. It then creates a pure one-use `ConsumptionPlan` containing an
application-owned replay key and canonical receipt. Rejected decisions cannot produce a plan.

Authenticated-map uses this capability while retaining its exact covered-mutation-index rules and
its existing direct/approval receipt schemas. The role-evidence document release workflow is the
second independent consumer. It binds the approval to the complete release-command hash, validates
the frozen `evidence-release` policy, and atomically writes an
`EvidenceApprovalConsumptionV1` receipt only after document-trail and evidence-registry acceptance.
The evidence receipt and key are published as golden vectors. Existing authenticated-map and
ADR-019 vectors continue to pin signed action, actor/key revision, approval reference, policy,
consumption and map receipt bytes.

No runtime authorization hook, generic object policy SPI, or second actor/approval data model was
introduced. Application bindings still own coverage and receipt meaning, as required by §6.4.

### Phase 5 — typed proof subjects and finalized-message proofs

* Replace vendor-branded commitment-format/proof-encoding identifiers using §7.1, rename the
  dependency-shaped descriptor field, and publish implementation compatibility separately.
* Add shared composite physical-key derivation to proof clients.
* Add typed proof subjects for ordered-log, document-trail, authenticated-map, approval result,
  ADR-028 epoch params/stake, and composite profile.
* Implement compact `messagesRoot` path generation and verification from the current algorithm.
* Add optional `FinalizedMessageIndex` composition with explicit state-cost/config commitment.
* Replace preview proof endpoints/models with the first supported portable proof bundle and precise
  finalization/content/availability result fields.

**Gate:** A client can prove a supplied message's block inclusion and, where indexed, its application
state record without knowing raw prefixes. Verification succeeds against an independently supplied
root and fails under every root/profile/chain/key/value substitution. No supported commitment or
proof-wire identifier names CCL, ZeroJ, a Java class, or another implementation provider.

#### Phase 5 implementation result

The supported profile records now use `commitmentFormatId` and `proofEncodingId`, and all released
format/wire identifiers are implementation-neutral. The identifier change intentionally generated
new format fingerprints, authenticated-map genesis IDs, and authorization vectors. Deterministic
Java generators and the independent Python verifiers pin the new baseline. Provider compatibility
is exposed through `StateCommitmentImplementations`; it is descriptive, reports MPF as
off-chain/on-chain and classic JMT as off-chain-only, and is not part of the profile descriptor or
fingerprint.

`StateProofSubject` and the client `ProofSubjects` catalog publish canonical subjects for finalized
messages, document heads, authenticated-map entries, approval outcomes, ADR-028 epoch parameters
and epoch stake credentials, and composite profile markers. Composite physical-key derivation uses
the same `CompositeCommitmentV1.componentKey` implementation as the runtime contract. Typed client
lookups derive that key and decode only authenticated present values.

`MessageInclusionProof` generates and verifies bounded paths against the existing
`AppBlock.messagesRoot` algorithm, including its odd-leaf duplication rule. The runtime and REST
surface can produce the proof directly from a finalized message ID; the strict client decoder
rejects unknown fields, malformed bounds, and substituted chain/message/root/path data.
`PortableProofBundle` verifies this compact claim against independently supplied block identity,
optionally binds a supplied signed `AppMessage`, and optionally verifies a typed state proof against
an independent `TrustedStateRoot`. Its result distinguishes `ID_ONLY`, verified or invalid supplied
content, state-fact status, and `AvailabilityStatus.NOT_PROVEN`; supplying a body never creates an
availability claim.

`FinalizedMessageIndex` remains an opt-in transition capability. Its canonical `Config` commits the
inclusion policy and maximum per-block state cost, publishes the maximum authenticated-write count,
and can be included by standalone or composed applications without runtime special cases.

### Phase 6 — reusable on-chain MPF verification

* Extract the eUTxO normalized proof model into shared contracts without weakening its bounds.
* Build the generic converter and reusable on-chain verifier/anchor-reference helpers.
* Replace the preview anchor datum/script with the complete chain/genesis/application/commitment
  identity baseline and deploy a fresh state thread.
* Cross-run common proof vectors through CCL verification and the actual on-chain execution target.
* Measure worst-case transaction size, CPU, and memory budgets; publish accepted bounds.
* Add a minimal reference validator that proves an ADR-028 epoch-parameter fact against a
  script-anchor reference input; use the same shared verifier for ordered-log/authenticated-map
  vectors.

**Gate:** The reference validator accepts valid proofs derived from live server output, rejects all
mutated proof/root/profile/anchor/claim vectors, and remains within published L1 budgets.

#### Phase 6 implementation result

The former `EutxoMpfProof` product contract is removed. `appchain-proof-contracts` now owns the
bounded `MpfNormalizedProof`, and `appchain-client` owns the strict `MpfProofConverter` from
`mpf-proof-wire-v1`. eUTxO withdrawal authoring consumes those shared types. Converter and contract
tests cross-check real MPF proofs, exact root reconstruction, malformed CBOR, and root/key/value and
bound substitutions. The supported on-chain envelope caps keys at 256 bytes, values at 8 KiB and
folds at 32 so a maximally configured proof cannot silently inherit the off-chain one-megabyte value
budget.

`appchain-proof-onchain` publishes `MpfOnChainVerifier`. Its reference validator locates exactly one
configured state-thread asset at the anchor script among Cardano reference inputs, validates chain
genesis, application, commitment profile and fingerprint, requires an exact typed physical key, and
folds the normalized inclusion proof against the referenced root. Application validators retain
semantic value decoding. The release-pinned Julc compiler accepts this validator as an actual Plutus
target. The existing eUTxO execution corpus exercises the same MPF algorithm end to end; its current
two-fold live vector uses a 504-byte redeemer and measures 445,952,815 CPU / 1,462,219 memory units,
below the configured transaction limits.

The seven-field preview anchor ABI and artifacts are replaced without migration. The supported
eleven-field `AnchorDatumV1` binds chain ID, chain genesis ID, application ID, commitment profile ID,
format fingerprint, height, block hash, state root, members and threshold. Both the off-chain codec
and on-chain validator reject identity substitution; the runtime also verifies every observed or
proposed datum against its locally selected identity. New Aiken artifacts were generated and their
positive/adversarial corpus executes on `julc-vm-java`. Configuration now rejects JMT when
`state.l1-proof-consumption-required=true`; JMT remains off-chain-only.

### Phase 7 — packaging and dead-code removal

* Move composition-dependent product assembly out of foundational stock logic where practical.
* Publish developer guides for standalone, composed, capability-level, off-chain proof, and MPF
  on-chain proof use.
* Delete redundant component/workflow helpers, old proof/observation codecs, old anchor artifacts,
  compatibility façades, and preview storage readers.
* Keep classic JMT explicitly off-chain and test pruning/watermark client behavior.

---

## 11. Verification strategy

### 11.1 Determinism and new-baseline integrity

* New golden roots, keys/values, blocks, observations, profiles, effects, proofs, and anchors after
  the clean design is selected.
* Standalone-versus-composite differential tests.
* Multiple messages for the same logical key in one block.
* Rejection after a planned multi-write action: no partial mutation/effect/consumption.
* Restart, snapshot restore, follower catch-up, and multi-node root agreement.
* Every first-party machine, testkit path, plugin façade, and direct caller reaches the single context
  apply method exactly once.
* Old storage markers, profile generations, plugin API/catalog versions, proofs, observations, and
  anchor artifacts fail explicitly rather than entering execution.
* Two clean nodes given the same L1 observation blocks produce identical roots.

### 11.2 Composition isolation

* Components cannot read/write sibling namespaces except declared workflow views.
* Duplicate topics/query paths/component ids fail at construction/profile activation.
* Effect callbacks cannot cross owner or generation boundaries.
* State-aware admission receives only the correct namespaced committed snapshot.
* A routed machine sees only its declared observer topics but retains original global message
  indexes.

### 11.3 L1 execution context and historical replay

* Valid block/epoch observations appear once at their original positions; malformed or topic/body
  mismatches never reach state execution.
* An observer-authentic but application-malformed epoch claim is rejected by the declared consumer's
  admission path before proposal execution/finality.
* Locally recomputed and certified-history acceptance construct byte-identical execution contexts;
  verification provenance is not observable by application code.
* A fresh node with no historical L1 ledger state replays ADR-028 protocol-param/stake observations
  from app blocks and reproduces the original roots.
* Shuffled local map iteration cannot affect ADR-028 canonical claims/chunks.
* Observation claims cannot access a retained/live `L1EpochState` after the observer callback.

### 11.4 Authorization and approval

* Domain/chain/genesis/action/policy/actor/key revision substitution.
* Expired, revoked, unknown, and wrong-role keys.
* Approval replay, double consumption, competing terminal outcomes, and deadline boundaries.
* Batch coverage gaps and extra unauthorized mutations.
* Differential vectors between authenticated-map's old path and extracted capability.

### 11.5 Proofs

* MPF/JMT inclusion, exclusion, tombstone, historical, and pruned-height cases.
* Composite local-to-physical key derivation.
* Message trees for zero, one, even, and odd leaf counts plus every index.
* Root, block, chain, height, profile, fingerprint, genesis, key, value, presence, and certificate
  substitution.
* Body-retained and id-only retention outcomes, with availability never inferred.

### 11.6 On-chain MPF

* Shared positive/negative vectors across CCL, converter, JVM on-chain model, and compiled validator.
* Non-canonical folds, excessive depth, oversized key/value, malformed branches, wrong presence,
  wrong root, and wrong anchor thread.
* Execution-budget measurements at every accepted boundary.
* At least one inclusion and one exclusion semantic claim in a reference validator.
* Decode and compare one versioned ADR-028 epoch-parameter value on-chain after MPF verification.

---

## 12. Acceptance criteria

This ADR is complete only when:

1. the new storage/genesis/profile baseline starts from an empty app-chain store, and every old or
   unmarked non-empty app-chain store fails with a clear reset instruction;
2. all old apply overloads, public component lifecycle APIs, synthetic-block adapters, deprecated
   proof/verifier paths, compatibility façades, and preview-format readers are deleted;
3. a stock `AppStateMachine` can be registered directly in a composite without losing
   `validateForBlock` or lifecycle semantics;
4. ADR-028/custom machines receive typed sequenced L1 observations through a block-bound context,
   while a node without historical L1 state can reproduce the same roots by replay;
5. ordered-log, document-trail, and key/value logic are reusable without synthetic blocks;
6. ADR-019-derived actor/role/approval capabilities are the single governed-authorization
   foundation, with newly approved canonical identifiers;
7. commitment-format and proof-wire identifiers are implementation-neutral, while tested CCL,
   ZeroJ, or other implementation compatibility is reported separately;
8. clients can request and verify typed state proofs without manually reconstructing composite
   prefixes;
9. compact message inclusion proves finalization, while APIs clearly report whether content was
   supplied/verified and never claim availability from a commitment;
10. an MPF proof produced from a server-native proof, including an ADR-028 epoch-parameter fact, is
   verified by a reusable Cardano validator against an independently located script anchor; and
11. the complete anchor datum binds chain genesis, application/profile, and commitment identity;
12. JMT is documented and enforced as off-chain-only for this release; and
13. there is no documented or executable migration path from the preview baseline.

---

## 13. Rejected alternatives

### 13.1 Replace `AppStateMachine` with `CompositeComponent`

Rejected. It would make composition leak into every application and discard useful root-level
lifecycle semantics. `AppStateMachine` remains the application boundary.

### 13.2 Compose unrestricted state machines over the same writer

Rejected. It loses namespace isolation, deterministic routing/profile identity, and effect
ownership. The current composite controls remain required.

### 13.3 Reuse only by wrapping whole state machines

Rejected as insufficient. It supports product assembly but not safe reuse of transitions inside a
larger atomic action. Transition capabilities are needed below the adapter.

### 13.4 Add authorization to authenticated storage or the runtime

Rejected. Storage commits bytes; runtime executes deterministic applications. Business policy
belongs in versioned application capabilities over explicit facts.

### 13.5 Give `apply()` a live L1 ledger-state handle

Rejected. Historical replay and certified catch-up must work after the source ledger snapshots are
gone. A live handle would also expose local retention/availability differences to consensus code.
Only canonical observations persisted in the app block enter execution context.

### 13.6 Implement ADR-030's new actor/approval hierarchy alongside ADR-019

Rejected. Two identity/revision/approval systems would be harder to audit and compose. Extract and
stabilize the implemented path first.

### 13.7 Treat `app_msgs` lookup as cryptographic proof

Rejected. It is an atomic operational index but is not in the state root and has no authenticated
path.

### 13.8 Claim a root proves data availability

Rejected. A root proves commitment to supplied data. Availability is a separate service/protocol.

### 13.9 Make generic on-chain proof support backend-neutral immediately

Rejected. MPF already has a working bounded implementation path. JMT on-chain work would increase
scope and cost without a current requirement. Backend-neutral client envelopes remain useful, but
on-chain proof ABIs may be profile-specific.

### 13.10 Preserve preview compatibility “just in case”

Rejected. App-chain is unreleased, and retaining overloads, adapters, duplicate formats, unsafe
verification conveniences, old storage readers, and anchor/proof versions would turn preview debt
into the first public contract. The supported boundary starts only after this cleanup.

---

## 14. Consequences

### Positive

* Application developers keep one mental model: every deployable application is an
  `AppStateMachine`.
* Stock machines remain easy to run alone and become easier to assemble.
* Custom applications can reuse deterministic business transitions without imitating messages or
  copying large state machines.
* Authorization and approvals become reusable without coupling core runtime/storage to business
  policy.
* Proof APIs describe exactly what is proven and where trust enters.
* MPF-anchored facts become consumable by generic Cardano applications, not only eUTxO code.
* The first supported release starts with one coherent SPI, storage format, proof model, observer
  model, composition model, and anchor identity rather than accumulated preview layers.

### Costs and risks

* Transition planning adds contracts and codecs that must themselves be consensus-stable.
* Every preview state machine, plugin, example, testkit, and direct caller must be updated together
  to the new execution method.
* Direct machine composition cannot be advertised until the state-aware admission gap is closed.
* Universal message indexing increases state size and is therefore optional rather than a silent
  runtime feature.
* Generic on-chain verification still requires application-specific semantic decoders and careful
  execution-budget management.
* Preview users must reset app-chain data, redeploy anchor scripts/state threads, regenerate clients,
  and rebuild plugins.
* Extracting already-live authorization code is security-sensitive and needs differential vectors,
  not only new unit tests.

---

## 15. Non-goals

This ADR does not:

* merge app-chain membership, executable-profile governance, business roles, and action approvals;
* add nondeterministic external identity or authorization calls to consensus execution;
* expose a live L1 ledger database, `EpochParamProvider`, stake store, or `L1EpochState` to
  `AppStateMachine.apply`;
* make all application state automatically public or retained;
* promise message-body availability from state or block roots;
* change the released MPF or JMT root algorithms;
* provide on-chain JMT verification;
* migrate or preserve preview app-chain state, roots, namespaces, plugin binaries, proof/observation
  formats, or anchor outputs; or
* weaken the requirement that the newly selected baseline be canonical, versioned, tested, and
  frozen before release.

---

## 16. Immediate next actions

1. Mark ADR-030 as refined by ADR-031 rather than implementing its proposed hierarchy directly.
2. Use Phase-0 characterization to decide semantics, then create new golden vectors; do not freeze
   current roots or bytes.
3. Replace the apply overload chain with the single `AppBlockExecutionContext` method and update all
   first-party callers/plugins in one change.
4. Replace public component/adapter lifecycle APIs and synthetic blocks with internal routed context
   plus transition capabilities.
5. Use ADR-028 epoch-params as the first typed L1-observation consumer; retain replay from app-block
   history as its merge gate.
6. Spike `TransitionPlan` by extracting the existing ordered-log helper, then document-trail.
7. Extract a proof-contract module from the eUTxO MPF implementation, keeping only technically
   justified cryptographic formats.
8. Use one epoch-param, authenticated-map, or ordered-log fact in a minimal script-anchor
   reference-input validator to establish the end-to-end on-chain proof contract.
9. Add the new storage marker/reset error, replace the preview anchor deployment, regenerate all
   schemas/fixtures/client models, and delete old readers/codecs/façades.

---

## 17. Source map for the current-state review

The principal implementation evidence reviewed for this ADR is:

| Concern | Current source |
|---|---|
| Root application SPI | `core-api/.../appchain/AppStateMachine.java` |
| Construction context (no live L1 state) | `core-api/.../appchain/AppStateMachineContext.java` |
| Current L1 observation contract | `core-api/.../appchain/l1view/L1Observation.java`, `L1Observer.java` |
| Current observation verification/injection | `runtime/.../appchain/L1ObservationService.java`, `AppChainEngine.java` |
| Current product-side observation decoding | `appchain-eutxo-ledger/.../EutxoStateMachine.java` |
| Message and block commitment | `core-api/.../appchain/codec/AppBlockCodec.java` |
| Ordered-log committed record | `core-api/.../appchain/OrderedLog.java` |
| State proof contracts/profiles | `core-api/.../appchain/state/*` |
| Evidence trust and retention semantics | `core-api/.../appchain/evidence/EvidenceVerifier.java` |
| Atomic ledger persistence/proof assembly | `runtime/.../appchain/AppLedgerStore.java` |
| Candidate execution/finality commit | `runtime/.../appchain/AppChainEngine.java` |
| MPF backend | `runtime/.../appchain/MpfAuthenticatedStateBackend.java` |
| Classic JMT backend and pruning | `runtime/.../appchain/ClassicJmtAuthenticatedStateBackend.java`, `SharedRocksDbJmtStore.java` |
| REST proof surface | `app/.../api/appchain/AppChainResource.java` |
| Off-chain native/certified verification | `appchain-client/.../ProofVerifier.java` |
| Composite root/routing | `appchain-composite/.../CompositeStateMachine.java` |
| Component contract and whole-machine adapter | `appchain-composite/.../CompositeComponent.java`, `StateMachineComponentAdapter.java` |
| Namespaced physical keys | `appchain-composite-contracts/.../CompositeCommitmentV1.java` |
| ADR-019 actors and role approvals | `appchain-role-workflow` and `appchain-role-workflow-contracts` |
| Governed authenticated-map assembly | `appchain-stdlib/.../AuthenticatedMapPreset.java`, `AuthenticatedMapAuthorizationWorkflow.java` |
| Current authorization verification | `appchain-stdlib/.../AuthenticatedMapDirectAuthorizer.java` |
| Script-anchor datum/validator | `appchain/onchain/aiken/appchain-anchor/validators/anchor.ak` |
| Product-local MPF normalization | `appchain-eutxo-client/.../EutxoMpfProofConverter.java` |
| Product-local on-chain MPF verification | `appchain-eutxo-bridge-onchain/.../mpf/MerklePatriciaForestry.java`, `ProofVaultValidator.java` |
