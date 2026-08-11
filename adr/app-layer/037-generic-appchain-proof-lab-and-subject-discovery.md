# ADR app-layer/037: Generic app-chain proof lab and typed proof-subject discovery

**Status:** Accepted and implemented — Phases 0 through 7 complete
**Date:** 2026-08-10
**Scope:** Generic app-chain console proof workflows, an authenticated block-message-root bridge,
portable message and state proof packages, runtime discovery of typed proof subjects, off-chain
verification, and bounded MPF on-chain export
**Depends on:** ADR app-layer/006, 025.x, 028, 031, 033, 035, and 036
**Supersedes:** the narrow ADR-033 section 5.2 conclusion that the current proof endpoints and
minimal manifest entries are sufficient for the generic console
**Preview reset policy:** App chains are unreleased. Wire contracts introduced by this ADR start at
version 1 without compatibility adapters for earlier preview-only console JSON.

---

## 1. Context

ADR-031 made proof generation a reusable app-chain capability rather than a property of one stock
state machine. ADR-033 exposed a committed application capability manifest, including proof-subject
summaries. ADR-028 and ADR-036 then demonstrated a product-level proof experience for Cardano
History: a user selects a meaningful fact, generates a proof, asks a semantic question about the
authenticated value, verifies the proof and claim separately, and exports a package whose result is
recomputed when imported.

The generic App Chains console already exposes most of the cryptographic primitives needed to do
the same for every application. They currently appear together under **Portable verification** in
the Operations view and require knowledge of message IDs, physical state keys, commitment roots,
and proof profiles. The page can display evidence and call native state-proof verification, but it
does not assemble those primitives into an understandable end-to-end statement.

This ADR turns the existing primitives into a generic proof lab. It does not put application logic
in the runtime or console. Application contract modules continue to own canonical keys, value
codecs, completeness rules, and semantic predicates. The runtime discovers data-only descriptions
of those contracts and exposes a uniform bounded proof API.

The common MPF/JMT state foundation authenticates every key/value mutation made by an application,
but it does not automatically insert every input message into application state. `messagesRoot` and
`stateRoot` are separate fields in the app-block header; the block hash binds both. This separation
is sound—messages are ordered inputs while state is the deterministic output—but it means a compact
message proof is not directly rooted in the L1-anchored `stateRoot`. This ADR adds a low-cost common
bridge without making the much larger per-message state index mandatory.

## 2. Current behavior

This section describes the implementation at the time of this decision. It is the baseline that
must be characterized before refactoring.

### 2.1 Finalized message lookup and block inclusion

For every app chain, the REST API can:

* look up a retained message by its 32-byte message ID;
* identify the finalized block height and original message index;
* return the finalized block and its `messagesRoot`; and
* generate `MessageInclusionProof` through
  `GET /app-chain/chains/{chain}/messages/{messageId}/proof`.

`MessageInclusionProof` is a bounded, versioned proof of:

```text
messageId -> leaf index -> messagesRoot of app block H
```

It uses the existing binary Merkle tree and odd-leaf duplication rule. Java generation and
verification are implemented and covered by vectors. This proof is universal: it does not require
the message to be copied into application state.

The generic console currently fetches and displays this object. It does not recompute the Merkle
path in the browser or present a message-inclusion verdict.

### 2.2 Finality and anchor evidence

`GET /app-chain/chains/{chain}/evidence/{messageId}` returns an `EvidenceBundle`. Depending on
retention, membership epochs, and bounded segment size, it contains:

* the message's finalized app block;
* a consecutive signed block segment up to a covering anchor block;
* finality certificates;
* the bundle-declared member keys and threshold;
* state-commitment identity for the segment tip; and
* a Cardano anchor transaction/slot reference when the bounded segment reaches an anchor.

`EvidenceVerifier` can already verify block hashes, message roots, the target message occurrence,
message content/signature when retained, finality certificates, consecutive height/previous-hash
linkage, and the relationship to the claimed anchor block. Its trusted verification mode requires
caller-pinned chain identity, members, threshold, commitment profile, and genesis identity.

The anchor reference in a bundle is evidence, not self-authenticating L1 truth. An independent
verifier must fetch the Cardano transaction, identify the unique expected state-thread output, and
decode and match its datum. The generic console currently displays the evidence JSON but does not
run this full verifier or independently resolve the Cardano output.

### 2.3 Generic authenticated-state proofs

For every authenticated-state chain, the REST API exposes:

```text
GET  /app-chain/chains/{chain}/state/proof/{physicalKey}[?height=H]
POST /app-chain/chains/{chain}/proof/verify
GET  /app-chain/chains/{chain}/anchor/commitment
```

The proof envelope binds the physical key, inclusion/exclusion/tombstone presence, optional value,
height, state root, block hash, commitment identity, native proof wire, block, and finality
certificate. Proofs may be requested at current or retained historical heights. The console can
load the latest node-confirmed anchor and request the corresponding retained proof, or accept a
root supplied independently by the user.

The release-matched verifier supports MPF and released JMT profiles. MPF is the supported
off-chain/on-chain target; JMT is off-chain only. Historical proof availability is bounded by the
backend watermark and snapshot/archive lifecycle.

The endpoint is intentionally storage-shaped. The generic console requires a caller to enter a
canonical physical key as lowercase hexadecimal and displays the authenticated value as bytes. It
cannot explain whether those bytes mean an order status, a document digest, an approval outcome,
or a balance.

### 2.4 Optional finalized-message state index

`FinalizedMessageIndex` is a reusable, consensus-applied transition capability. When enabled, it
stores a canonical authenticated record containing at least:

```text
messageId -> [height, original-index, topic, sender]
```

and a tip marker. It does not store the body. `OrderedLog` has intrinsic finalized-message indexing;
other applications may opt in through the ADR-033 wrapper and showcase option.

This index enables a state-root proof that a message ID was recorded by the application. With MPF,
that proof can be checked by a Cardano validator against the state root in the app-chain anchor
datum. Without the index, the compact block `messagesRoot` proof remains available off-chain, but a
generic Cardano validator would additionally need a bounded and qualified block/finality/anchor
bridge. Until the block-message-root capability in this ADR is implemented, the per-message index
is the only simple generic state-root path for on-chain message claims.

The index is optional because it adds an authenticated write per indexed message plus the tip
write. It must not silently become a cost paid by every application.

### 2.5 Missing authenticated bridge for block message roots

The app-block header is canonically hashed as:

```text
[version, chain-id, height, prev-hash, l1-slot, l1-block-hash,
 timestamp, messages-root, state-root] -> blockHash
```

An L1 script anchor commits both the anchored `blockHash` and `stateRoot`. For a message in that exact
anchored block, a verifier can prove `messageId -> messagesRoot`, reconstruct the canonical header,
and compare its block hash with the anchor. For a message in an earlier block, the verifier must also
verify finality and the consecutive previous-hash chain up to the anchored block. `EvidenceBundle`
implements that bounded path off-chain.

There is currently no common authenticated state record of:

```text
block height H -> [messagesRoot, messageCount]
```

Consequently, a chain without `FinalizedMessageIndex` cannot use a later anchored state root to prove
an old message with a compact message path plus a generic state proof. This is the remaining gap
between “all application state is proof-capable” and “every finalized message has a simple anchored
proof.”

### 2.6 Capability manifest and typed client subjects

`AppCapabilityManifest.proofSubjects` currently publishes only:

```text
subjectId, componentId, keyNamespace, verificationTarget
```

The Java `ProofSubjects` catalog and product clients already implement release-matched typed
subjects for several stock facts. These subjects derive a physical key and decode a present value.
However, the manifest does not describe user inputs, claim modes, value presentation, completeness
requirements, snapshot coordinates, or on-chain support. The console cannot safely generate a form
or semantic verifier from the current four fields.

### 2.7 What the existing proofs do and do not establish

The current capabilities answer different questions:

| Mechanism | Statement established after trusted verification |
|---|---|
| Message lookup | This node currently retains a message/body; not a portable proof |
| `MessageInclusionProof` | Message ID `M` occurs at index `I` under block `H`'s `messagesRoot` |
| Finality certificate | Block `H` was threshold-signed under a caller-pinned membership policy |
| Evidence block segment | Block `H` links through consecutive finalized blocks to block `A` |
| Independently checked script anchor | Cardano L1 commits the identity/root/block described by the anchor datum |
| Finalized-message state proof | Authenticated state records `M -> [H,I,topic,sender]` |
| Typed state proof | The subject's canonical key maps to the decoded authenticated value |
| Exclusion plus completeness proof | A subject is absent from a declared complete dataset |

None of these alone proves that a body will remain retrievable in the future. A valid retained body
proves content binding, not continuing data availability.

### 2.8 What the common authenticated-state foundation achieved

The missing message bridge does not reduce the value of the MPF/JMT foundation. It established one
global authenticated application state for standalone and composite machines, common canonical
namespacing, current/historical proof APIs, implementation-neutral commitment identity, typed proof
subjects, reusable off-chain verification, reusable MPF on-chain verification, and authenticated
snapshot retention for large datasets. A state machine now writes ordinary canonical mutations and
inherits proof generation instead of implementing its own tree and proof service.

Its scope is application output state. For example, it can prove a document head, approval outcome,
balance, authenticated-map entry, effect receipt, or order status whenever the owning component
writes that fact. It cannot prove an input message ID through `stateRoot` unless a deterministic
transition writes either that message or a commitment covering it. The block-message-root capability
defined here supplies that missing common input-to-state bridge.

## 3. Problems to solve

1. The Operations page is crowded by a proof form intended for occasional use.
2. A beginner sees proof JSON rather than the statement it authenticates.
3. Message inclusion, signed finality, state recording, L1 anchoring, content binding, and data
   availability are not separated clearly.
4. Message proof/evidence is displayed but not fully verified by the console.
5. Generic state proof requires a storage key and cannot decode application meaning.
6. A raw exclusion proof can be mistaken for a business-level absence claim even when the dataset
   has no authenticated completeness marker.
7. Product-specific UI logic does not scale to stock, composed, custom, and plugin applications.
8. A proof package can carry an editable claimed result. Importers must recompute rather than trust
   that result.
9. MPF and JMT have different verification targets that must be visible before proof generation.
10. Node-reported anchors and membership must not be presented as independently trusted context.
11. Common authenticated state proves application outputs, but there is no default low-cost state
    bridge from a historical block's `messagesRoot` to a later L1-anchored `stateRoot`.

## 4. Decision summary

Yano will add a top-level **Proofs** tab beside **Operations** and **Capabilities** on the generic
App Chains page. The tab provides three workflows:

1. **Message** — locate a message, verify block inclusion/finality/anchor linkage, optionally verify
   retained content and finalized-message state, and export a portable message package.
2. **State** — discover a typed application subject, collect meaningful coordinates and a claim,
   generate a root-fixed proof, decode the authenticated value, verify it off-chain, and optionally
   export a qualified MPF on-chain package.
3. **Import and verify** — import a portable package, provide explicit trust context, and recompute
   every cryptographic and semantic result.

The existing raw-key proof form remains available under **Advanced state proof**. It is a diagnostic
surface, not the primary product experience.

Yano will add a reusable `FinalizedBlockMessageRootIndex` capability (final public name may differ).
It writes one immutable authenticated record per finalized block:

```text
height H -> [schema-version, H, messagesRoot, messageCount]
```

The capability is **optional at the framework/profile level and enabled by default for every newly
created stock, composed, custom, showcase, and generated app chain**. An application may disable it
only in its immutable genesis/application profile. Enablement, namespace, schema, maximum messages
per block, and configuration digest are committed into application identity; it cannot be toggled
on a running chain.

This default bridge costs one authenticated record per block, regardless of the number of messages.
It permits `messageId -> messagesRoot` verification followed by one MPF/JMT state proof against a
later anchored root. The existing `FinalizedMessageIndex` remains an optional higher-cost fast path
that costs one record per message but gives a single state proof and authenticates height, index,
topic, and sender directly.

Applications expose typed proof subjects through a data-only `ProofSubjectProvider` SPI. The
committed capability manifest continues to declare which subjects are part of the application
profile. A chain-scoped discovery endpoint returns richer presentation/query descriptors for those
declared subjects. Descriptors may define forms and identifiers but cannot supply executable UI,
JavaScript, CSS, or verifier code.

The server resolves coordinates to keys and assembles proof packages using release-matched contract
code. The browser and Java client independently verify released schemas. Descriptive verdicts in a
package are never authoritative and are always recomputed on import.

## 5. Proof terminology and verdict model

The generic console and wire packages use the following independent results.

### 5.1 Message results

```text
messageLocated       node currently found the message record/body
messageIncluded      compact Merkle path reaches the selected block messagesRoot
contentVerified      supplied full message derives the message ID and has a valid sender signature
blockFinalized       finalized by a certificate and/or reserved block record under a trusted root
finalityEvidence     CERTIFICATE | AUTHENTICATED_BLOCK_RECORD | BOTH | UNVERIFIED
anchorLinked         block evidence or authenticated block record reaches the trusted anchor/root
l1AnchorVerified     caller or independent adapter verified the actual Cardano transaction/output/datum
messageRootStateBound authenticated block record binds [height,messagesRoot,count] to the selected state root
stateRecorded        optional per-message record binds [height,index,topic,sender] to the selected state root
availability         NOT_PROVEN | LOCALLY_RETAINED
```

`LOCALLY_RETAINED` is an observation about the queried node at query time, never an authenticated
availability guarantee. Package acceptance never depends on this observation unless a future,
separate data-availability protocol defines such a claim.

The UI presents a summary statement such as:

> Message `M` is included in finalized block `H`. Its supplied content is verified. The block is
> linked to an independently verified L1 anchor at `A`. Continued body availability is not proven.

Each clause is omitted or marked unverified when its required evidence/trust context is absent.

### 5.2 State and semantic results

```text
proofValid           native inclusion/exclusion proof reconstructs the expected root
identityBound        chain/genesis/profile/fingerprint/height match trusted context
finalityValid        block certificate matches pinned membership when requested
anchorBound          expected root/height matches an independently checked anchor
factDecoded          authenticated value is canonical for the declared subject version
completenessValid    required complete-dataset marker/descriptor is authenticated
claimEvaluated       released verifier understands the requested predicate
claimSatisfied       authenticated actual value satisfies the requested predicate
accepted             every check required by the selected verification policy passes
```

A proof can be authentic while a claim is false. For example, a valid balance proof carrying 100
does not satisfy a minimum-balance claim of 150. The console must display both results and must not
turn a red semantic result into a green “proof verified” banner.

### 5.3 Trust labels

The shared labels are:

* **internal consistency only** — proof verified against a root carried in the same package;
* **caller-pinned root** — caller supplied full chain/commitment/root/height identity;
* **node-confirmed L1 reference** — the queried node reports an accepted anchor; transaction
  contents have not been independently checked;
* **caller-pinned anchor** — caller supplied and verified the complete expected Cardano anchor;
* **independently verified L1 anchor** — the verifier fetched and checked the Cardano transaction,
  state-thread asset, script address, datum, and confirmation policy.

“Verified on-chain” is used only for an actual validator execution or a qualified conformance test,
not for producing an on-chain-shaped redeemer.

## 6. Generic message proof workflow

### 6.1 Inputs and entry points

The Message view accepts a canonical message ID. Every block/message dialog also offers **Prove this
message**, which opens the Proofs tab with chain and message fixed. The workflow never silently
reuses a hidden message from another tab.

The user selects a verification policy:

* finality only;
* node-confirmed anchor linkage;
* caller-pinned root/anchor; or
* independent Cardano anchor verification when the configured L1 adapter supports it.

### 6.2 Verification sequence

The generic UI selects the shortest proof path supported by the chain's committed capabilities. The
three valid paths are:

| Path | Consensus state cost | Verification material |
|---|---:|---|
| Block evidence | no extra authenticated write | message path + header/finality + optional chain to anchor |
| Authenticated block message root | one write per block | message path + block-root state proof + trusted state root |
| Finalized-message record | one write per message plus tip | direct message-record state proof + trusted state root |

The default path for new chains is the authenticated block message root. Block evidence remains the
zero-additional-state fallback. A finalized-message record is preferred when the caller needs its
authenticated topic/sender/position or the smallest single-tree proof.

The verifier performs the applicable bounded checks in this order:

1. Validate chain ID and canonical message ID.
2. Verify supplied message structure, derived ID, sender, and signature when full content exists.
3. Verify `MessageInclusionProof` against the block's exact `messagesRoot`.
4. If `state-index:finalized-block-messages-v1` is enabled, derive the immutable block-record key,
   verify its MPF/JMT proof against the selected root, strictly decode
   `[version,height,messagesRoot,messageCount]`, and compare the root/count with the compact path.
5. If the block-root bridge is unavailable or the user requests certificate evidence, recompute
   block hashes and verify threshold finality with pinned membership.
6. When evidence must reach a later anchor block, verify consecutive heights and previous hashes.
7. Match commitment identity, selected height, state root, and anchor block as required by the
   chosen path.
8. Under independent mode, resolve the Cardano transaction and verify the expected script output,
   state-thread asset, inline datum, chain/genesis/application/profile identity, height, block hash,
   state root, member keys, and threshold.
9. If `state-index:finalized-message-v1` is enabled, generate and verify its typed state proof and
   compare height/index/topic/sender with the message evidence.
10. Emit independent statuses and an explanatory acceptance decision.

The implementation reuses `EvidenceVerifier`, `MessageInclusionProof`, `PortableProofBundle`, anchor
datum codecs, and released state-proof verifiers. Browser and Java implementations share canonical
golden and adversarial vectors.

### 6.3 Old messages and bounded evidence

An evidence segment may omit L1 linkage when the message-to-anchor gap exceeds the retained block,
membership-epoch, count, or byte bounds. The UI reports:

```text
finality: VERIFIED
anchor linkage: UNAVAILABLE_FROM_THIS_NODE
reason: retained/epoch/size boundary
```

It must not describe the message as unfinalized. The user may import an externally obtained segment,
use the default block-message-root or optional per-message state proof against a later retained
state root, or query an archival node.

### 6.4 Default authenticated block-message-root capability

`FinalizedBlockMessageRootIndex` is a reusable cross-cutting transition capability applied in the
same atomic state commit as the owning `AppStateMachine`. It owns a reserved versioned namespace and
writes:

```text
capability id:  state-index:finalized-block-messages-v1
proof subject: finalized-block-messages-v1
namespace:     ~yano/finalized-block-messages/v1/
```

The final Java/configuration names may differ, but these public identity strings and their versioned
semantics are frozen before Phase 2 implementation. The canonical records are:

```text
config -> [schema-version, max-messages-per-block, retention-profile]
block/H -> [schema-version, H, messagesRoot, messageCount]
```

The record deliberately excludes `stateRoot` and `blockHash`: both depend on the post-state and
including either would create a circular commitment. Height, `messagesRoot`, and count are available
before state commit and are already consensus-visible in the candidate block. The immutable record
is committed only when that block receives finality and the complete state transition commits.

Rules:

1. The framework wrapper applies the capability to stock, composed, and custom machines; business
   machines do not copy its writes.
2. Every newly created stock, composed, custom, showcase, and generated profile enables it unless
   that immutable profile explicitly disables it.
3. Disablement is accepted only at genesis. Disabled means the zero-state-cost evidence path remains
   available, and the different capability set produces a different application identity.
4. Enablement, schema, namespace, message bound, and retention profile are included in the committed
   capability manifest and application/genesis identity.
5. The reserved namespace cannot be written through application commands or component-local state.
6. A duplicate/conflicting height fails the candidate; replay of the same canonical record is
   idempotent only through the normal rollback/replay transition contract.
7. The write count is one per block, plus the configuration record at genesis. No mutable side index
   is authoritative.
8. Empty blocks record the canonical empty `messagesRoot`, count zero, and cannot produce a message
   inclusion proof.

At any later retained anchored state root `R_A`, the immutable record for height `H <= A` can be
proved directly while it remains in primary state:

```text
message M
  -> MessageInclusionProof under messagesRoot(H)
  -> state proof of block/H = [H, messagesRoot(H), count]
  -> stateRoot(A)
  -> independently verified L1 anchor
```

This is a nested proof: the compact binary message path proves membership under `messagesRoot`; the
MPF/JMT proof authenticates that root as finalized block state under the anchored application root.

Primary block records grow with block count. A separate committed retention profile may segment old
records into ADR-028 authenticated snapshots. A segment can be deleted from primary state only after
its complete secondary root and descriptor are committed there. The resulting archived proof is
`message path -> block-record snapshot proof -> primary descriptor proof -> L1 anchor`. Silent local
deletion without a committed descriptor is forbidden. The initial release may support direct
primary retention only, but must publish storage projections and a safe threshold before claiming
high-rate indefinite retention.

### 6.5 Generic on-chain message claims

The default generic on-chain message statement uses the block-message-root record plus MPF:

```text
the state root in this trusted anchor contains
block H -> [messagesRoot R, messageCount N]
and the bounded binary Merkle path proves message M is one of those N leaves under R
```

The shared validator verifies the MPF record proof against the anchor reference input, strictly
decodes the record, then verifies the bounded Blake2b-256 binary Merkle path including index, count,
odd-leaf duplication, and maximum tree depth.

When `FinalizedMessageIndex` is also enabled, a smaller alternative statement is available:

```text
the state root in this trusted anchor contains
message M -> [height H, index I, topic T, sender S]
```

That validator verifies one MPF proof and a bounded predicate over the decoded record. Enabling the
per-message index changes consensus state cost and application identity and remains explicit.

JMT versions of both statements remain off-chain only. Direct on-chain verification of the
zero-additional-state block-header/finality chain remains deferred because it requires a separately
qualified header/finality/anchor verifier.

## 7. Typed state proof subjects

### 7.1 Contract ownership

A proof subject belongs with the module that owns the authenticated bytes. It defines:

```text
semantic coordinates
  -> canonical logical key
  -> composite/snapshot physical key
  -> canonical authenticated value decoder
  -> supported claims
  -> completeness requirements
  -> supported verification targets
```

The runtime does not learn order, document, approval, balance, or Cardano semantics. It invokes the
provider selected by the application's committed manifest and returns the provider's canonical
result.

### 7.2 `ProofSubjectProvider` SPI

The exact Java names may change, but the contract is:

```text
ProofSubjectProvider
  descriptors(applicationProfile) -> ProofSubjectDescriptorV1[]
  resolve(subjectId, coordinates, scope) -> ResolvedProofSubject
  decode(subjectId, canonicalValue) -> TypedFact
  evaluate(subjectId, typedFact, claim) -> ClaimResult
  onChainExport(subjectId, proof, claim, anchor) -> optional bounded package
```

Providers are registered through the existing plugin/service loading boundary. A provider is active
only when the selected `AppStateMachine.capabilityManifest()` declares the same subject ID, version,
component ID, and descriptor digest. An installed but unused plugin cannot add subjects to another
chain.

Resolution and evaluation are deterministic, root-fixed, side-effect free, bounded, and unaware of
live mutable ledger state. They may use only released contract codecs and the requested authenticated
view.

### 7.3 Discovery descriptor

`GET /app-chain/chains/{chain}/proof-subjects` returns effective descriptors. A descriptor contains:

```text
schemaVersion
subjectId, subjectVersion, componentId
label, description
descriptorDigest
storageScope                 PRIMARY_STATE | AUTHENTICATED_SNAPSHOT
coordinates[]                id, type, label, constraints, encoding
claims[]                     claimId, operands, supportedTypes
factView[]                   field id, type, display unit
completeness                 NONE | MARKER | SNAPSHOT_DESCRIPTOR | PAIRED_SUBJECT
verificationTargets[]        OFFCHAIN_MPF | OFFCHAIN_JMT | ONCHAIN_MPF
retentionHints               historical/snapshot behavior
limits                       coordinate, value, proof, and claim bounds
```

The descriptor grammar is a small closed schema, not arbitrary JSON Schema. Text is escaped and
rendered as plain text. It cannot contain HTML, URLs that execute in the console, scripts, styles,
expressions, class names, or code-download locations. This preserves the ADR-035 decision that
plugin bundles do not inject UI code into the main console.

The descriptor is discovery metadata, not a trust root. Its digest binds the application profile to
the intended contract version, while proof verification still pins canonical key/value codecs,
commitment identity, root, and trusted anchor.

### 7.4 Root-fixed proof API

The generic typed endpoint is:

```text
POST /app-chain/chains/{chain}/proof-subjects/{subjectId}/proof
```

The bounded request contains:

```text
coordinates
view                         latest | height | latest-confirmed-anchor | snapshot
expectedIdentity?            caller-pinned identity/root/height
claim?                       one descriptor-declared predicate
includeEvidence              false by default
```

The response contains:

* normalized coordinates;
* canonical logical and physical key identities;
* authenticated value bytes and decoded fact when present;
* primary or snapshot proof(s);
* required completeness proof/descriptor;
* exact root, height, commitment identity, block/finality information;
* claim request and explanatory server evaluation; and
* supported export targets.

The server evaluates only after obtaining the proof at the selected immutable view. It decodes the
value carried by that proof rather than querying current state or trusting a presentation object.
The caller/browser recomputes the result.

Raw physical keys remain exposed only through the existing advanced endpoint. Subject resolution
does not disclose another component's namespace or permit arbitrary key traversal.

### 7.5 Initial common subjects

The first catalog covers capabilities already implemented by stock or reference applications:

| Subject | Example coordinates | Example claims |
|---|---|---|
| Finalized block messages | block height | messages root/count equality; message inclusion with nested path |
| Finalized message record | message ID | recorded; height/index/topic/sender equality |
| Ordered-log record | message ID or sequence | record equality; finalized position |
| Document head | entity/document ID | current revision; digest; status |
| Document revision | document ID + revision | digest/author/status equality |
| Registry entry | collection/namespace + key | present/absent; value digest/equality |
| Approval outcome | proposal ID | status; quorum reached; decision digest |
| Approval consumption receipt | proposal/action ID | consumed by action; outcome digest |
| Role/policy assignment | actor/role/policy coordinates | role membership; policy digest |
| Balance | account/asset | exact; minimum; maximum |
| Authenticated-map entry | collection + application key | present/absent; typed value/digest |
| Effect intent/result | height + ordinal or effect ID | type; status; payload/result digest |
| Composite profile marker | application/profile ID | exact configuration/profile digest |

Cardano History subjects continue to use their product page for richer domain presentation, while
the generic descriptors prove that the same protocol works for primary and authenticated-snapshot
subjects.

### 7.6 Absence and completeness

A native exclusion proof establishes only that a physical key is absent from one authenticated
root. A product-level statement such as “credential S had no stake in completed epoch E” additionally
requires proof that E's dataset is sealed and complete.

A subject may expose an absence claim only when its descriptor identifies and the response includes
the required authenticated completeness marker, paired subject, or snapshot descriptor. Otherwise
the generic UI labels the result **raw key exclusion** and does not render a domain absence verdict.

## 8. Portable package contracts

### 8.1 `appchain-message-proof-v1`

The message package contains:

```text
schema, chain/application identity
messageId
suppliedMessage?                     optional full canonical message
messageInclusionProof
evidence                             blocks, finality certificates, membership claim
blockMessageRootProof?               default authenticated [height,messagesRoot,count] state proof
stateRecordProof?                    optional finalized-message typed proof
anchorReference?
verificationPolicy
verification?                        explanatory, never authoritative
```

An importer discards/replaces `verification` and recomputes it. It requires caller-pinned trust
context for an authenticity verdict. Internal-consistency mode is always labelled as such.

### 8.2 `appchain-state-claim-proof-v1`

The state package contains:

```text
schema, subject ID/version/descriptor digest
normalized coordinates
claim
authenticated fact bytes
primary or snapshot proof set
completeness evidence?
block/finality evidence?
anchor reference/context?
verification?                        explanatory, never authoritative
```

The verifier derives the expected canonical key from coordinates, matches the proof key, verifies
the root and trust context, decodes only the proof-carried bytes, evaluates the predicate, and emits
its own result. Editable `history`, `actual`, or `verification` presentation fields cannot influence
acceptance.

### 8.3 Verification endpoint

The existing `/proof/verify` remains the bounded native-proof diagnostic endpoint. A new endpoint:

```text
POST /app-chain/chains/{chain}/proof-bundles/verify
```

accepts only released message/state package schemas and an explicit verification policy/trust
context. It is useful for CLI and server-side clients, but the console also verifies locally so that
verification is not reduced to asking the proof-serving node whether its own package is valid.

### 8.4 On-chain export

For a descriptor advertising `ONCHAIN_MPF`:

```text
POST /app-chain/chains/{chain}/proof-subjects/{subjectId}/onchain-export
```

converts an already verified native MPF proof into the released normalized Plutus-data ABI and
includes the bounded semantic claim. The converter verifies the native proof before export. The
Cardano validator independently repeats proof, anchor, identity, key, value, codec, and predicate
checks.

JMT descriptors never advertise this endpoint as supported. A generated redeemer is labelled
**not yet executed on-chain** until evaluated by a transaction/validator.

## 9. Console design

### 9.1 Navigation

The generic App Chains page has:

```text
Operations | Capabilities | Proofs
```

The current Portable verification panel moves from Operations to Proofs. Operations retains compact
links from blocks, messages, effects, and application records into a pre-filled proof workflow.

The Proofs tab has:

```text
Message | State | Import and verify | Advanced
```

Authenticated-snapshot catalog/operations remain capability-dependent operational views. Typed
snapshot proof generation appears under State.

### 9.2 Progressive disclosure

The default presentation answers four questions in order:

1. What fact did you ask about?
2. What authenticated value was actually found?
3. Is the proof/root/trust chain valid?
4. Does the authenticated value satisfy your claim?

Raw keys, proof wire, CBOR, block chains, certificates, descriptors, and fingerprints are collapsed
under technical details. Export remains available without forcing a beginner to interpret them.

### 9.3 Capability-driven controls

The console reads the chain's committed manifest and effective descriptors:

* block-message-root index enabled: use the default nested message-path + state-proof workflow;
* block-message-root index disabled: show the zero-state-cost block/finality evidence workflow;
* finalized-message index enabled: additionally offer the direct record proof and decoded metadata;
* MPF: offer off-chain verification and qualified on-chain export per subject;
* JMT: show off-chain only and explain why on-chain export is unavailable;
* snapshots disabled: hide snapshot view selectors;
* historical height below watermark: report proof material pruned, not fact absent;
* no completeness contract: disable semantic absence claims;
* no descriptor: retain only Advanced raw-key verification.

### 9.4 Product pages

Product pages such as Cardano History may keep specialized workflows. They use the same package,
descriptor, trust-label, and verification components rather than copying cryptographic logic.
Custom teams may still build a separate product UI; a plugin JAR supplies domain APIs and proof
descriptors but not main-console UI assets.

## 10. Security and correctness requirements

1. **No package self-trust.** Imported verdicts, decoded presentation JSON, labels, and requested
   values never affect recomputed verification.
2. **No root self-trust.** A proof matching a bundled root is internal consistency only.
3. **Exact identity binding.** Chain ID, application/profile, genesis ID, commitment profile,
   format fingerprint, proof wire version, height, block hash, and root are matched where applicable.
4. **Pinned membership.** Bundle-declared members and threshold cannot establish authenticity by
   themselves.
5. **Independent L1 boundary.** Node-reported transaction/slot data remains untrusted until the
   Cardano output/datum is independently checked.
6. **Bounded work.** Descriptor counts, coordinate sizes, proof bytes, block segments, CBOR depth,
   claim operands, values, and JSON documents have fixed release limits.
7. **Strict decoding.** Unknown fields where the wire profile is closed, duplicate fields,
   non-canonical encodings, trailing bytes, unsupported versions, and type mismatches fail closed.
8. **Subject/key binding.** The verifier derives the key from normalized coordinates and compares it
   byte-for-byte with the proven key.
9. **Proof-carried value.** Semantic evaluation reads only bytes authenticated by the verified proof.
10. **Completeness before absence.** Business absence is unavailable without its authenticated
    completeness contract.
11. **No availability inflation.** Retention observations never become cryptographic availability
    claims.
12. **No executable descriptors.** Subject discovery cannot inject code or markup into the console.
13. **Profile-qualified on-chain export.** Only MPF subjects with bounded, tested validators can
    advertise `ONCHAIN_MPF`.
14. **No circular commitment.** The block-message-root record contains height, message root, and
    count, never the current block hash or post-state root.
15. **Reserved system namespace.** Application and component commands cannot forge or overwrite the
    block-message-root or finalized-message records.
16. **Rollback and replay safety.** This ADR adds no authoritative mutable side index. The default
    block-message-root and optional per-message indexes are part of the atomic consensus transition.

## 11. Performance and retention

Message proof generation reads one finalized block and builds a logarithmic sibling path. Evidence
segments remain bounded by the existing block count and byte limits. The UI does not fetch a full
block segment until the user requests finality/anchor verification.

The default block-message-root capability adds one authenticated state write per finalized block,
independent of message count. The direct `FinalizedMessageIndex` adds one authenticated write per
indexed message plus its tip update and therefore remains opt-in. Qualification publishes bytes per
block, proof sizes/latency, root-update cost, RocksDB amplification, restart time, and projections at
1-second, 5-second, and 20-second block intervals.

Typed resolution adds no second authoritative database. It derives canonical keys and uses existing
root-fixed state/snapshot proof APIs. Providers may cache immutable descriptor documents by manifest
digest but not proof results across roots.

The response exposes:

* selected and oldest provable height;
* whether message content, block bodies, primary proof nodes, and snapshot storage are locally
  available;
* snapshot online/archive/evicted status; and
* an actionable restore/archive-node hint when proof material is unavailable.

High-cardinality datasets use ADR-028 authenticated snapshots. Restoring an archived snapshot is an
explicit node-local operation and does not alter consensus state or its root.

For chains whose block history would make direct primary retention unbounded, a committed segmented
snapshot profile moves sealed height ranges into authenticated snapshots. This is a consensus-visible
proof-layout choice fixed at genesis, not node-local pruning. Until that profile is implemented and
qualified, disabling the default block-root index is the supported zero-extra-state choice for very
high block-rate chains.

## 12. Alternatives considered

### 12.1 Keep only the raw-key proof form

Rejected as the primary experience. It is useful for debugging but exposes storage layout, cannot
decode meaning, and makes safe absence and semantic claims impractical.

### 12.2 Hardcode every stock state machine in the console

Rejected. It would couple console releases to application modules, fail for composition/custom
plugins, and duplicate key/value/claim semantics.

### 12.3 Allow plugin JARs to inject JavaScript/CSS into the main console

Rejected. It creates a remote-code/UI supply-chain boundary, inconsistent UX, and version skew.
Data-only descriptors plus generic components cover common proof workflows; specialized products
can ship a separate UI.

### 12.4 Make finalized-message indexing the default

Rejected. Universal compact message proofs already exist, while mandatory indexing increases every
application's authenticated write volume in proportion to message count. It remains opt-in except
where intrinsic to the application contract. The block-message-root index provides the default
bridge with one write per block.

### 12.5 Keep only block-header/finality evidence

Rejected as the default. It has zero additional state cost, but a message below the latest anchor may
require a long retained header segment, membership transitions complicate verification, and the
generic MPF on-chain foundation cannot consume it directly. It remains the fallback when the default
block-root capability is disabled.

### 12.6 Describe any valid proof as proving message availability

Rejected. Inclusion/finality prove commitment. A supplied or locally retained body proves content
binding at verification time, not durable retrieval.

### 12.7 Trust server-side verification alone

Rejected at trust boundaries. The server verifier is useful for diagnostics and non-browser clients,
but a proof-serving node cannot independently authenticate its own root or claimed L1 reference.

## 13. Implementation plan

### Phase 0 — freeze current behavior and language

* Capture current message lookup, compact proof, evidence, native state proof, anchor commitment,
  manifest, historical-watermark, and snapshot responses.
* Characterize explicitly that `messagesRoot` and `stateRoot` are sibling block-header commitments
  and that only state written by an application is automatically MPF/JMT-provable.
* Add golden and adversarial vectors shared by Java and browser verification.
* Freeze the verdict/trust vocabulary in section 5.
* Add regression tests proving that retained content does not imply availability and internal
  consistency does not imply an authentic root.

**Gate:** Current behavior is documented and every trust-boundary distinction has a negative test.

### Phase 1 — separate generic Proofs UI

* Add the top-level Proofs tab and move Portable verification out of Operations.
* Add Message, State, Import, and Advanced sections.
* Add **Prove this message/state/effect** links from existing dialogs.
* Preserve current raw-key and externally supplied-root workflows.

**Gate:** The Operations page regains its current density, no proof capability is lost, and browser
tests cover direct links, refresh, back/forward navigation, accessibility, and narrow layouts.

### Phase 2 — default authenticated block-message-root bridge

* Freeze the reserved namespace, configuration record, immutable block record, canonical CBOR, and
  capability/subject IDs for `FinalizedBlockMessageRootIndex`.
* Add one reusable atomic state-machine wrapper/capability used by stock, composed, and custom
  applications without copying writes into business machines.
* Enable it by default in new stock profiles, showcase chains, and generated templates; support an
  explicit genesis-only disablement committed into application identity.
* Add typed block-record proof generation and strict MPF/JMT verification.
* Implement strict browser decoding and compact message-path verification against the authenticated
  block record.
* Measure one-record-per-block storage/root-update cost and publish high-rate projections.

**Gate:** Default-enabled stock/composed/custom chains produce identical records and roots on every
member; disabled chains preserve the zero-write baseline; mutations of height, count, message root,
path, profile, key, value, and state root fail at the expected layer.

### Phase 3 — complete message evidence and optional direct index

* Integrate the existing evidence verifier fallback, including caller-pinned membership and bounded
  header linkage when the block-root capability is disabled or certificate evidence is requested.
* Add independent Cardano script-anchor resolution through the configured L1 adapter.
* Detect the committed `state-index:finalized-message-v1` capability.
* Resolve and decode its typed subject without asking for a physical key.
* Cross-check its height/index/topic/sender against block evidence.
* Define/export/import `appchain-message-proof-v1` carrying whichever proof paths are present and
  recomputing every verdict.
* Report bounded-evidence, proof-retention, and body-retention limitations independently.

**Gate:** Default block-root, optional direct-message, and zero-state-cost evidence paths produce the
same message-inclusion result under equivalent trust; all anchor/certificate/membership/path
substitutions fail; availability remains `NOT_PROVEN`.

### Phase 4 — typed proof-subject SPI and discovery

* Add `ProofSubjectProvider`, the closed descriptor schema, manifest digest binding, and provider
  collision checks.
* Add chain-scoped discovery and root-fixed typed proof endpoints.
* Add strict limits, plugin lifecycle handling, and descriptor cache invalidation by manifest digest.
* Keep the raw endpoint as Advanced diagnostics.

**Gate:** A custom plugin can contribute a data-only subject without runtime or console source
changes, while undeclared/colliding/mismatched providers are rejected deterministically.

### Phase 5 — stock subject catalog and claims

* Publish descriptors and released codecs for ordered log, document trail, registry, approvals,
  roles/policies, balances, authenticated map, effects/receipts, and composite profile markers.
* Implement exact/minimum/maximum/status/digest/membership predicates only where semantically valid.
* Require completeness contracts for absence.
* Reuse the same components from product pages and Java clients.

**Gate:** Each showcase foundation and reference application has at least one understandable typed
proof workflow, and the same proof verifies across all three nodes.

### Phase 6 — portable state claims and qualified on-chain export

* Define/export/import `appchain-state-claim-proof-v1`.
* Implement the bounded server verifier and independent browser verifier.
* Add the default nested MPF block-message-root plus bounded binary-Merkle on-chain verifier.
* Add the smaller direct-message MPF export when `FinalizedMessageIndex` is enabled.
* Add MPF normalized redeemer export for other subjects with released validators.
* Publish transaction size/CPU/memory bounds and cross-language vectors.

**Gate:** Authentic-proof/false-claim cases are rejected semantically; every mutated identity,
proof, value, completeness, claim, and anchor field fails; qualified validators execute within the
published Cardano budgets.

### Phase 7 — showcase, qualification, and documentation

* Add short guided scenarios for orders, documents, balances, approvals, authenticated map, and
  finalized messages.
* Demonstrate default block-root, explicitly disabled, and optional per-message index profiles so
  presenters can explain the proof/storage trade-off.
* Cover MPF on-chain and JMT off-chain behavior explicitly.
* Test three-node root/descriptor agreement, restart, rollback/replay, catch-up, pruning watermark,
  snapshot archive/restore/evict, and old-message evidence limits.
* Add operator, application-author, plugin-author, independent-verifier, and Cardano-validator docs.
* Run full clean build and devnet/preprod browser smoke tests.

**Gate:** A new presenter can explain and demonstrate the difference between inclusion, finality,
state recording, anchoring, semantic claim satisfaction, and availability without inspecting raw
CBOR or physical keys.

## 14. Testing matrix

The implementation must include at least:

* one, odd, even, maximum, duplicate, and substituted message Merkle paths;
* default-enabled and explicitly disabled block-message-root profiles with committed identity drift;
* empty, one-message, maximum-message, duplicate-height, altered-count/root, rollback/replay, and
  late-follower block records;
* verification of an old block record against a later anchored root, plus segmented snapshot
  archive/restore when that retention profile is implemented;
* full message, canonical retention tombstone, wrong body, wrong sender, wrong signature;
* insufficient, duplicate, unknown, and rotated finality signers;
* anchored/unanchored, bounded-gap, membership-boundary, missing-block, and altered-link evidence;
* node-reported, caller-pinned, independently verified, wrong-network, wrong-script, wrong-asset,
  wrong-datum, and rolled-back Cardano anchors;
* MPF/JMT inclusion, exclusion, tombstone, historical height, pruned height, and wrong profile;
* true and false exact/min/max/status/digest claims over the same authentic proof;
* completeness-present, incomplete, wrong-epoch/root, and missing completeness evidence;
* standalone/composed/plugin subjects with identical contract semantics;
* descriptor collision, digest mismatch, excessive descriptors/coordinates, and hostile text;
* three-node proof/package byte identity where deterministic and cross-node verification otherwise;
* independent Java/browser/on-chain golden vectors for each released verification target; and
* UI keyboard navigation, focus, error recovery, copy/export/import, mobile layout, and plain-language
  trust labels.

## 15. Consequences

### Positive

* Every app chain gets a common, understandable proof experience.
* Stock, composed, custom, and plugin applications reuse one verification foundation.
* Application semantics remain in contract modules rather than the runtime or console.
* Message proof language becomes precise about finality, content, state recording, anchoring, and
  availability.
* Every new stock chain has a low-cost, state-root-anchored message proof path by default.
* Proof cost is proportional to blocks rather than messages unless the direct index is selected.
* Typed claims can evolve independently while retaining the exact native proof underneath.
* MPF on-chain export and JMT off-chain verification are visible and fail closed.
* Product pages and the generic console converge on the same package and trust contracts.

### Costs

* Release-matched browser verification and cross-language vectors add maintenance work.
* Each typed subject needs a stable coordinate/key/value/claim contract and negative tests.
* Independent Cardano anchor resolution requires network access and a trusted network/script
  configuration.
* The default block-message-root bridge adds one consensus state write per block and changes new
  application identities relative to the previous preview profile.
* The optional direct message index adds substantially more writes for high-message-volume chains.
* Direct primary block-record retention still grows with block count until the segmented snapshot
  retention profile is implemented and qualified.
* Rich evidence packages may be unavailable for old messages even when finality itself remains
  provable from retained or external material.

## 16. Acceptance criteria

This ADR is complete when:

1. the generic UI has a dedicated Proofs tab and Operations no longer carries the full proof lab;
2. a message ID can produce a verified, portable statement with separate inclusion, content,
   finality, anchor, state-record, and availability results;
3. every new stock/showcase/generated profile enables the one-record-per-block authenticated
   message-root bridge by default, while an explicit genesis-only disablement preserves the
   zero-extra-state evidence path;
4. an old message can be verified by a compact message path plus a block-record MPF/JMT proof
   against a later retained anchored state root;
5. imported message/state packages are recomputed and altered embedded verdicts have no effect;
6. typed subjects are discovered from the effective application profile without executable UI
   injection;
7. every showcase stock/reference application exposes at least one useful typed proof subject;
8. absence is described semantically only with authenticated completeness;
9. the default nested block-message MPF proof and qualified typed subjects can export and validate
   bounded on-chain packages against script-anchor
   reference inputs;
10. JMT is clearly and correctly off-chain only;
11. old/pruned/archived material is distinguished from a missing fact;
12. three-node, rollback/replay, adversarial, browser, and full-build qualification passes; and
13. documentation enables an independent verifier to reproduce the result without trusting the
    proof-serving node.
