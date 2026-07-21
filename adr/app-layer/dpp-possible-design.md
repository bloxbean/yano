# DPP Possible Design and Yano Capability Assessment

- **Status:** Exploratory design note; not an accepted ADR
- **Date:** 2026-07-15
- **Verification update:** 2026-07-17 — scenario-by-scenario check against the
  blueprint v0.1 flows on the ADR-013 release-closure working tree (see §3,
  "Scenario-by-scenario verification")
- **Purpose:** Preserve a possible Digital Product Passport (DPP) design for later ADR and implementation work
- **Primary source:** [Cardano Foundation DPP Blueprint for Cardano v0.1](https://github.com/cardano-foundation/cardano-dpp-standards/blob/main/DPP-Blueprint-Cardano-v0.1.md)
- **Related Yano ADRs:**
  [ADR-005](../app-layer/005-yano-app-chain-framework.md),
  [ADR-006](../app-layer/006-appchain-enterprise-extensions-and-zk.md),
  [ADR-010](../app-layer/010-deterministic-effect-system.md),
  [ADR-010.1](../app-layer/010.1-emission-versioning.md),
  [ADR-011](../app-layer/011-plugin-architecture.md), and
  [ADR-012](../app-layer/012-multi-source-oracle-and-cardano-publication.md)

## 1. Executive summary

Yano can support all four architectural patterns described by the Cardano Foundation DPP blueprint:

1. static passport anchors;
2. privacy-preserving anchored claims;
3. append-only lifecycle event logs; and
4. high-throughput, batch-root-based verification.

The underlying architecture is already a strong fit: deterministic app-chain state machines, finalized blocks, authenticated MPF state roots, Cardano L1 anchors, message and state proofs, plugin extension points, effects, finalized-block sinks, encrypted message bodies, and experimental selective-disclosure support.

However, this does **not** mean that DPP is available out of the box today. ADR-013 now supplies reusable S3-compatible object publication, IPFS pinning, acknowledged Kafka publication, and a stock deterministic composite evidence workflow. Yano still needs a first-party DPP bundle, standard contracts and canonicalization rules, a projection/query tier, and a ready-to-run gateway and verification portal. Exact Cardano representations such as one NFT or CIP-68 datum per product also require DPP-specific Cardano policies and transaction construction.

The recommended outcome is a configurable, first-party DPP solution that covers the blueprint's common scenarios without application code. Users should need custom plugins only for proprietary integrations, non-standard schemas, specialized device attestations, or custom Cardano policies.

This work should be implemented as separate modules and plugins. It should not require changes to Yano's consensus, P2P, block, or ledger fundamentals.

## 2. Important qualification

The referenced blueprint is a work in progress. Its examples, payloads, cost estimates, regulatory mappings, and on-chain formats are illustrative rather than a finalized interoperability standard.

Yano should therefore expose a versioned profile such as `CF_DPP_BLUEPRINT_0_1`; it should not hard-code the current document as an eternal wire format. Profile upgrades must be explicit and deterministic.

The blueprint also leaves important matters open, including:

- canonical hashing and serialization;
- publication and governance of trusted partner keys;
- the protocol by which a cached response remains verifiably tied to an anchored root;
- inactive, replaced, revoked, and retired passport semantics; and
- the permanent Cardano metadata/profile choice.

There is also tension between the general reference to CIP-25/CIP-68 and the static-passport discussion proposing a DPP-specific metadata label instead of label 721. Until the ecosystem profile stabilizes, those publication choices must remain configurable and versioned.

## 3. Blueprint patterns and Yano coverage

| Blueprint scenario | Yano capability today | Additional work required | Expected support |
|---|---|---|---|
| Static Passport Anchor | Deterministic registry state, document hashes, MPF proofs, finalized state roots, L1 root anchoring, and ADR-013 storage connectors | DPP schemas, canonicalization, GS1 identity mapping, connector profile configuration, portal, and optionally per-product Cardano asset/CIP-68 publication | Full through a DPP bundle; per-product Cardano representation is an optional publication profile |
| Passport updates and versioning | Deterministic state transitions and emission-version rules | DPP status/version/replacement rules; Cardano-specific update, burn-and-mint, or mint-only history executors where chosen | Full, with versioning strategy selected by profile |
| Anchored Proof / selective disclosure | Authenticated state, state/message proofs, encrypted bodies, and experimental ZK/BBS building blocks | DPP claim Merkle tree and proof format, private storage, claim salts, issuer/subject authorization, verifier tooling, and production hardening | Full in stages; baseline Merkle disclosure first |
| Append-only Event Log | Best current fit: ordered finalized messages, deterministic state, finalized stream sinks, proofs, and L1 anchors | EPCIS/DPP event contracts, actor/device key registry, domain signatures, deduplication, batch rules, projector, and timeline API | Full through a DPP bundle |
| Multi-party and IoT events | Member-authenticated submission and governed app-chain membership | Durable manufacturer/shipper/device identity independent of the submitting node, signed domain envelopes, key rotation/revocation, and device-attestation profiles | Full with DPP identity and signature rules |
| Recovery certificates and anti-double-counting | Deterministic uniqueness and lifecycle state can be enforced | Recovery-domain rules and, if required, Cardano certificate asset issue/burn policies | Full, but domain and on-chain policies must be specified |
| High-throughput batch roots | MPF state roots, block batching, configurable anchor cadence, finalized Kafka export, and proofs | Partitioning profile, projector/database, cache/CDN, precomputed proof pages, root-pinned verification, and million-product benchmarks | Architecturally supported; performance target must be demonstrated |
| Confidential data | Encrypted message bodies, retention/crypto-shredding, API-key boundary, and experimental selective disclosure | Private object store, OIDC/mTLS gateway, fine-grained regulator access, field/claim disclosure format, and production credential support | Partial today; full with external access and disclosure services |

### Overall verdict

- **Architecturally possible:** all four blueprint patterns.
- **Strongest immediate fit:** event log and batch-root verification.
- **Partially available today:** generic registry, approvals, document trail, proofs, finalized streams, and Cardano root anchoring.
- **Reusable out of the box after ADR-013/015:** immutable S3-compatible object publication, existing-CID IPFS pinning, acknowledged Kafka publication, authenticated evidence state/proofs, the approval-coordinated `evidence-v1-gated` composite workflow, and governed activation of packaged future profile generations.
- **Not yet out of the box:** a coherent DPP data model and bundle, GS1/EPCIS support, public verification portal, DPP actor/device identity and lifecycle semantics, selective-disclosure claim proofs, and DPP-specific Cardano asset publication.

### ADR-013 reuse boundary

The stock `composite` provider is a reusable mechanics and demo layer, not a
DPP implementation. A future DPP bundle can reuse:

- the committed composite profile, namespaced component state, exact routing,
  result ownership, quotas, aggregate queries, and proof translation;
- registry, approvals, document trail, and evidence-publication component
  patterns;
- `object.put`, `ipfs.pin`, and `kafka.publish` executors and receipts; and
- three-node finality, state/effect proofs, snapshots/restarts, metrics, and
  Cardano root anchoring.

It must still define and test DPP-specific truth and interoperability rules:
GS1/EPCIS canonical models, durable actor/device identities and signatures,
passport version/status/revocation semantics, claim or event Merkle formats,
recovery and anti-double-counting policy, regulator access, and optional
Cardano DPP asset/CIP-68 publication. Configuration alone must not pretend
that the generic evidence preset supplies those domain guarantees.

### Scenario-by-scenario verification against blueprint v0.1 (2026-07-17)

Verified against the blueprint's eight named flows on the working tree of
`feat/adr013-m1-p1-7-release-closure` (all three ADR-013 milestones
implemented), cross-checked with the external review in
[ADR-014](014-appchain-adr013-external-review-readiness-and-feasibility-fable.md).

**Headline: Yano can deliver the verification substance of all eight flows
with current capabilities plus configuration and off-chain glue — in several
cases with stronger guarantees than the blueprint asks for — but the
blueprint's literal on-chain artifacts (CIP-25/CIP-68 tokens, burn-and-mint
versioning, DPP metadata labels, GS1 Digital Link resolution) are not
producible today.** They all sit behind the unbuilt `cardano.dpp.*` executor
and ADR-010.2 hardening.

The structural point to state honestly: the blueprint is written as a
**direct-L1 pattern library** (each product/batch gets its own Cardano
transaction, token, or datum). Yano answers the same requirements with one
authenticated MPF state root, one cadence-based anchor transaction covering
unbounded products, and per-product/per-event/per-claim proofs against that
root. That makes Yano structurally cheaper than the blueprint's own batching
math (anchor cost is independent of product count) — but a regulator or
ecosystem tool expecting to find a CIP-68 reference token or DPP metadata
label on L1 will not find one on a Yano deployment today. Convergence worth
noting: blueprint §7 recommends the Aiken Merkle Patricia Forestry library for
advanced deployments — exactly the MPF construction Yano's state root already
uses, with client-side verification shipped in `appchain-client`.

| # | Blueprint flow | Verdict today | Verified working | Missing |
|---|---|---|---|---|
| 4.1.A | Static Anchor — Registering Product Data | **~80% — literally the evidence demo** | Stage doc in S3 (`object.put`, WORM-capable), pin CID (`ipfs.pin`), register hash + fingerprints in authenticated state, Kafka ack, root anchored to Cardano — proven E2E | GTIN/GS1 identity mapping; optional per-product NFT mint (**blocked**, ADR-010.2) |
| 4.1.B | Static Anchor — Checking Product Info | **Partial** | `appchain-evidence-client` verifies MPF proof + threshold finality + anchor datum client-side — stronger than the blueprint's "portal checks hash" | GS1 Digital Link QR resolver; consumer-facing product portal (the demo UI is a scenario report viewer, not a passport portal) |
| 4.1.1 | Versioning (3 approaches) | **App-chain versioning yes; token versioning no** | Evidence machine implements versioned records (`REPUBLISH latestVersion+1`, terminal-status gating, derived current status) — functionally equivalent to CIP-68 datum-update semantics | All three blueprint approaches (datum update / burn-and-mint / mint-only) are L1 token mechanics → **blocked** on `cardano.dpp.*` |
| 4.2.A | Anchored Proof — Creating | **Partial** | Committing a 32-byte claim root + version + issuer into authenticated state works today; MPF gives per-key proofs for free | DPP claim Merkle format, salted low-entropy claims, canonical claim serialization (designed in §12, not built) |
| 4.2.B | Anchored Proof — Verifying Specific Facts | **Missing** | Proof plumbing exists (root-pinned snapshots, exclusion proofs) | The selective-disclosure serving layer: claim categorization, RBAC/token/NFT authorization, per-claim proof API; node has API-key auth only — no OIDC/mTLS gateway |
| 4.3.A | Event Log — Recording Lifecycle Events | **Strongest fit — one identity gap** | Ordered finalized messages are an append-only, consensus-agreed, proof-carrying event log — categorically stronger than the blueprint's trusted-collector model (no single trusted batcher, no retroactive-modification window) | Blueprint partners sign as **domain actors** (manufacturer/shipper/recycler/IoT). Yano's sender identity = chain member key; durable non-member actor/device registry, domain signatures, rotation/revocation not built (§8) |
| 4.3.B | Event Log — Viewing History | **Buildable with glue** | Finalized Kafka sink (ordered, cursor-tracked) + committed queries + proofs | Timeline/projector API and EPCIS 2.0 event semantics — external projector, no Java plugin needed |
| 4.4.A | High Throughput — Bulk Processing | **Architecturally superior, empirically unproven** | One root covers millions of keys with one anchor tx; no per-partition transactions needed | Partitioning profile, projector DB, and **zero load/benchmark evidence** — "millions of SKUs" is a claim, not a result |
| 4.4.B | High Throughput — Fast Scanning | **Missing tier, solved hard part** | Blueprint Open Point #1 (cached response verifiably tied to an anchor) is already solved: phase-1.4 `AppStateProofSnapshot` gives atomic `(committedHeight, root)`-pinned proofs — shipped after this note listed it as "worth considering" (§11/§15 are now partly satisfied) | The serving tier itself (cache/CDN, precomputed proof pages, <300 ms SLA) — off-chain infrastructure |

**Blueprint open points (§8 of the blueprint) Yano already answers:**

1. Cached-yet-verified responses → root-pinned proof snapshots (implemented,
   audited).
2. Partner key publication/trust → chain-governed membership (ADR-008.3) for
   members; the DPP actor registry extends it to non-members.
3. Shared hashing/serialization rules → Yano's canonical-CBOR + golden-vector
   + CDDL discipline is exactly the machinery the blueprint says the ecosystem
   lacks — contributable upstream to the CF standard.

The fourth (passport status/inactive/replaced semantics) is domain work the
DPP bundle must define either way.

**Gap list, ranked by blocking severity:**

1. `cardano.dpp.*` publication executor (CIP-25/CIP-68 mint, datum update,
   burn-and-mint, batch-root txs) — **hard-blocked** on ADR-010.2, unwritten.
   Every flow's *literal* on-chain representation depends on it.
2. DPP bundle (schemas, GTIN/GS1 mapping, EPCIS contracts, passport status
   semantics, actor/device identity registry, claim-tree format) — §6/§15
   module list stands; none of it exists yet.
3. Public serving tier (GS1 Digital Link resolver, portal, projector, cache) —
   no Java needed, but real engineering the demo does not contain.
4. Selective-disclosure authorization layer — designed (§12), absent.
5. Performance validation — High Throughput is the only pattern where the
   *architecture* is untested rather than the features.

Two ADR-014 blockers have since been removed: ADR-015 provides authenticated,
future-height activation of reviewed profile generations packaged in the
composite catalog, and `evidence-v1-gated` closes the direct create/republish
path so the release workflow is an actual authorization gate. A DPP bundle
still has to ship its own bounded catalog, domain actor policy, migration rules,
and future compatible generations; profile governance does not invent or trust
unreviewed code.

**Claim guidance:** the defensible statement is — *"Yano implements the
blueprint's trust goals today via its appchain + MPF root + script anchor (the
blueprint's own recommended advanced structure), and will add the blueprint's
L1 token representations as an optional publication profile once Cardano
transaction hardening (ADR-010.2) lands."* Do **not** claim blueprint
conformance, GS1/EPCIS support, or CIP-25/68 compatibility — none are true
yet, and the blueprint marks GS1 Digital Link and CIP-25/68 as *mandatory*
for compliance.

## 4. Recommended proof model

The natural Yano model is a nested proof chain:

```text
source observation or product fact
        |
        v
DPP claim or event + domain signature
        |
        v
DPP claim Merkle root / passport-version state
        |
        v
Yano MPF state root
        |
        v
threshold-finalized app-chain block
        |
        v
Cardano script anchor
```

This permits a verifier to establish that:

1. a disclosed fact belongs to a committed DPP claim set;
2. that claim set belongs to the relevant passport/version or event batch;
3. that state existed under a particular Yano state root;
4. the root was finalized by the configured app-chain participants; and
5. the finalized root was anchored to Cardano.

This model supports privacy-preserving claims, event batches, and high-throughput verification without requiring one Cardano transaction per product or event.

A second, optional publication profile can create or update a wallet-visible product NFT/CIP-68 reference. That profile is useful where ecosystem discoverability or asset-level lifecycle semantics justify the additional cost and Cardano-specific complexity.

## 5. Trust statement

Yano can prove that authorized app-chain participants finalized and published a particular observation, event, or claim. It cannot by itself prove that the underlying real-world price, sensor measurement, material declaration, or business assertion is true.

Truth assurance remains a domain concern and may require:

- independent sources or inspections;
- governed issuer and partner accreditation;
- calibrated and attested devices;
- threshold or quorum policies;
- conflict and outlier rules;
- evidence retention; and
- revocation and dispute procedures.

The verification UI must distinguish at least:

- cryptographically valid;
- issued by an authorized actor;
- finalized by the DPP network;
- anchored on Cardano;
- current, replaced, revoked, or retired; and
- externally certified or corroborated, where applicable.

It must not reduce all these properties to an ambiguous green "verified" badge.

## 6. Proposed module layout

```text
Yano libraries and plugins
|
+-- appchain-dpp-contracts (or appchain-dpp-sdk)
|   +-- canonical commands and query models
|   +-- GTIN / GS1 Digital Link identity rules
|   +-- EPCIS mapping profile
|   +-- canonicalization and domain-separated hashing
|   +-- claim Merkle trees and disclosure proofs
|   +-- actor/device signature and verification types
|   +-- portable verifier library and golden vectors
|
+-- appchain-dpp
|   +-- manifested composite state-machine bundle
|   +-- product/passport registry
|   +-- versions, status, replacement, revocation, and retirement
|   +-- issuer, partner, auditor, and device roles/keys
|   +-- lifecycle events and deterministic deduplication
|   +-- event/claim batch roots
|   +-- recovery certificates and anti-double-counting rules
|   +-- DPP queries/domain read API
|   +-- DPP effects, result handling, health, and metrics
|
+-- appchain-objectstore-s3
|   +-- immutable/versioned object publication
|   +-- integrity verification and durable receipts
|
+-- appchain-ipfs
|   +-- pin an existing CID
|   +-- integrity verification and durable receipts
|
+-- appchain-kafka
|   +-- acknowledged per-action publication
|   +-- business integration events
|
+-- appchain-effects-cardano-dpp
|   +-- DPP metadata/root publication
|   +-- product-asset bootstrap
|   +-- CIP-68 create/update profile
|   +-- burn-and-mint or mint-only profile
|   +-- status/retirement publication
|   +-- batch checkpoints and recovery-certificate issue/burn
|
+-- appchain-dpp-onchain
    +-- Aiken policies and validators
    +-- versioned datum/redeemer formats
    +-- golden test vectors

External first-party services and demo
|
+-- dpp-gateway
|   +-- ERP/PIM/EPCIS ingestion and mapping
|   +-- canonicalization, storage upload, signing, and Yano submission
|
+-- dpp-projector
|   +-- finalized Kafka stream to query database/cache
|
+-- dpp-portal
|   +-- GS1 resolver and QR/consumer views
|   +-- regulator/partner OIDC or mTLS access
|   +-- selective disclosure and proof verification
|   +-- cached/CDN-backed public reads
|
+-- dpp-demo
    +-- three-node Yano cluster
    +-- MinIO/S3-compatible object storage
    +-- IPFS
    +-- Kafka, projector, and verification portal
```

One app-chain selects one state-machine provider. The DPP feature should
therefore be a composite state machine rather than expecting operators to
compose `doc-trail`, `kv-registry`, and `approvals` dynamically through YAML.
ADR-015 can govern future profiles already packaged by that DPP provider; the
provider still defines the consensus-critical component order, routes,
workflows, compatibility identities, and quotas.

No new plugin framework is needed. ADR-011's manifested bundle and existing extension points should host the state machine, effects, domain/query APIs, finalized sinks, health, and metrics.

## 7. DPP state and command outline

The exact schema belongs in a later ADR, but a useful initial scope is:

### Authenticated state

- DPP profile and schema-version registry;
- issuer, manufacturer, partner, auditor, and device identities;
- role grants, key rotations, revocations, and validity intervals;
- product identity and current passport version;
- immutable version history and content/evidence hashes;
- passport status: draft, active, replaced, revoked, retired, or inactive;
- lifecycle-event commitments and deterministic event IDs;
- batch/partition roots and manifest hashes;
- recovery-certificate state and uniqueness/consumption markers;
- effect intent and terminal publication results where required by ADR-010.

### Example commands

- `actor.register`, `actor.rotate-key`, `actor.revoke-key`;
- `product.register`;
- `passport.publish-version`, `passport.replace`, `passport.revoke`, `passport.retire`;
- `event.append`, `event.batch-close`;
- `claim.publish-root`;
- `recovery.issue`, `recovery.consume`, `recovery.revoke`;
- `publication.request`, with effect results handled by the deterministic effect protocol.

These are illustrative names, not frozen wire identifiers.

## 8. Domain authentication and deterministic validation

The node that transports a message is not necessarily the manufacturer, shipper, auditor, recycler, or IoT device that authored the DPP event. Node/member authentication must not be treated as durable business-actor identity.

Every governed DPP mutation should carry an inner domain-signed envelope containing at least:

- profile/schema version;
- chain/domain ID;
- command type and canonical payload hash;
- actor or device ID and key ID;
- nonce or deterministic event ID;
- issued/observed time subject to an explicit validation policy; and
- signature.

The DPP state machine must re-verify this signature during deterministic `apply()` using the actor/key registry in authenticated state. It must also check role, validity interval, revocation, replay/deduplication, and command-specific authorization.

This rule prevents a privileged node or REST caller from silently becoming the claimed real-world author.

## 9. Canonicalization and hashing requirements

Raw JSON text must never be used directly as a consensus-critical content hash. A DPP profile must specify, with golden test vectors:

- canonical serialization, such as canonical CBOR or a precisely selected JSON canonicalization profile;
- integer, decimal, timestamp, Unicode, byte-string, and field-order rules;
- JSON-LD normalization rules if JSON-LD semantics are used;
- domain separation for document, claim, event, batch, and state hashes;
- Merkle leaf and internal-node encodings;
- absent versus null semantics;
- array ordering rules;
- schema/profile version binding; and
- salts or keyed commitments for low-entropy private values.

The same portable verifier implementation and vectors should be usable by the gateway, state-machine tests, portal, and external verifiers.

## 10. Effect connector requirements relevant to DPP

This section should feed directly into the planned effect-connectors ADR.

### 10.1 `object.put`

The DPP profile should prefer a pre-staged object model:

1. the gateway canonicalizes and uploads a potentially large document;
2. it computes and submits the content hash and immutable object reference to Yano;
3. the deterministic state transition records the intent;
4. `object.put` copies, promotes, locks, or publishes the pre-staged object; and
5. the executor returns a durable receipt that is recorded through ADR-010 result handling.

The executor should support:

- a configured source alias and object key, not an arbitrary caller-controlled URL;
- destination/bucket allowlists;
- required expected digest and size;
- conditional create / no-overwrite semantics;
- bucket versioning and an optional Object Lock/WORM profile;
- stable idempotency keys;
- server-side encryption configuration without secrets in effect payloads;
- receipt fields such as bucket, key, version ID, ETag/checksum, and completion time; and
- bounded payload/reference sizes and SSRF/path-traversal protection.

### 10.2 `ipfs.pin`

The safest baseline action pins an **existing CID** through one or more configured providers. Uploading arbitrary bytes and computing a CID is a distinct `ipfs.add-and-pin` workflow and should not be hidden inside the initial pin action.

The executor should support:

- CID and optional expected codec/hash constraints;
- provider/profile alias rather than embedded credentials or arbitrary endpoints;
- idempotent pin status checks;
- replication/persistence policy;
- optional content retrieval and digest verification;
- durable provider receipts; and
- terminal failure policy suitable for content that cannot be recovered.

IPFS content is public and persistent by default. Confidential DPP documents must be encrypted before publication or kept in a private object store. Merely omitting a decryption key from the chain is not a complete access-control design.

### 10.3 `kafka.publish`

Yano's finalized-block Kafka sink should remain the primary mechanism for bulk projection, indexing, and timeline construction. A per-action `kafka.publish` effect is still useful for acknowledged business events that have their own retry/result lifecycle.

The executor should support:

- configured cluster and topic aliases;
- topic allowlists;
- stable message key and idempotency ID;
- schema/profile version headers;
- deterministic payload or committed object reference;
- acknowledgement level and bounded retry policy; and
- receipt data including topic, partition, and offset.

### 10.4 `cardano.dpp.*`

DPP Cardano actions must be layered on the production hardening planned for the Cardano effect executor. They should not grow directly out of a preview-oriented ADA-payment executor without completing wallet safety, transaction lifecycle, change handling, fee/limit policy, confirmation, rollback, and reconciliation work.

Likely DPP actions include:

- publish a DPP root/checkpoint and metadata reference;
- create/update a CIP-68 product reference;
- mint a replacement/version asset;
- burn or retire an asset where the selected profile requires it;
- issue/consume a recovery certificate; and
- publish a batch root.

Native-asset transfer and generic payment remain separate reusable Cardano actions rather than being overloaded as DPP publication.

## 11. Query, proof, and portal architecture

The public QR/GS1 scan path should be an external projection service backed by a database, cache, and CDN. It should not put million-product public traffic directly through an in-process plugin domain API.

The normal read flow is:

```text
finalized Yano block
        |
        v
Kafka finalized-block sink
        |
        v
DPP projector -> query database -> cache/CDN -> portal
                                      |
                                      v
                              proof verification
```

The portal may show a cached response as anchored only when the cached passport/event view, proof, exact state root, and Cardano anchor all refer to the same committed version. Cache entries must be invalidated or superseded on replacement, revocation, retirement, actor-key revocation, or root/profile changes.

A useful shared Yano enhancement is a proof-carrying committed query or root-pinned proof API. A query followed by a separate "current root" proof call can race with a new block. The query result should identify its exact block/root, and the proof endpoint should be able to prove against that committed root.

If an in-process plugin domain API is ever used for protected DPP writes, its request context will need authenticated principal/claims and a write-capable submission interface. The preferred first design is instead an external authenticated gateway, which keeps public/regulator authentication and high-volume concerns outside the node process.

## 12. Privacy and selective disclosure

Baseline support should use salted claim commitments and Merkle inclusion proofs:

1. split a private passport into profile-defined claims;
2. salt and canonicalize each claim;
3. commit the claim leaves into a Merkle root;
4. store only the root and permitted public data in authenticated state;
5. store the private document or claims in an encrypted private store; and
6. disclose selected claim, salt, and inclusion proof to an authorized verifier.

This proves that the disclosed claim was committed without revealing every sibling claim. Low-entropy values require strong random salts because unsalted hashes can be guessed.

JSON-LD Verifiable Credentials, BBS signatures, and ZK policies can be optional later profiles after their wire formats, key lifecycle, revocation, issuer governance, and production readiness are established. They are not required for the first useful DPP demo.

## 13. Out-of-the-box experience

A user should be able to start the reference DPP stack, configure a profile, and run an end-to-end scenario without Java coding:

1. start three Yano members plus MinIO, IPFS, Kafka, projector, and portal;
2. register sample manufacturer, auditor, shipper, and recycler identities/keys;
3. import a sample product/passport through JSON, CSV, or EPCIS mapping;
4. canonicalize and place the full document in object storage and optionally IPFS;
5. register the passport hash/reference and issue a storage-publication effect;
6. append manufacturing, shipping, inspection, repair, and recycling events;
7. finalize a batch and anchor the Yano root to Cardano;
8. scan a GS1-compatible QR URL;
9. view public product data, lifecycle timeline, current status, and proof/anchor details;
10. log in as a regulator and selectively disclose a private claim; and
11. optionally publish a per-product Cardano asset/CIP-68 reference when that profile is enabled.

Configuration rather than custom code should select:

- DPP/schema profile version;
- GTIN/company namespace;
- actor roles and governed key policy;
- object-store/IPFS targets;
- public/private disclosure categories;
- event partition and batch size;
- Yano and Cardano anchor cadence;
- Cardano publication mode: Yano-root only, batch root, or per-product asset;
- ERP/PIM/EPCIS field mappings from supported profiles; and
- cache and retention policies.

## 14. When a custom plugin or adapter is still appropriate

Custom code should be limited to genuinely domain-specific concerns, such as:

- proprietary ERP, PIM, MES, PLM, or IAGON integration;
- a regulatory schema not covered by a shipped profile;
- specialized IoT device attestation or hardware root of trust;
- custom issuer/approval/recovery governance;
- non-standard event normalization or conflict resolution;
- custom Cardano minting/validator policy;
- proprietary identity, KMS, HSM, or Vault integration; or
- an alternative projection/search backend.

Common DPP registration, versioning, event, proof, storage, batching, Cardano-root publication, health, and metrics behavior should be first-party and reusable.

## 15. Core versus module changes

### No DPP-driven core changes expected

The proposed DPP semantics do not require changes to:

- app-chain consensus or finality;
- peer-to-peer transport;
- block format fundamentals;
- MPF authenticated state fundamentals; or
- Cardano node/ledger consensus logic.

### Modules/plugins that must be added

- DPP contracts/verifier SDK;
- composite DPP state-machine bundle;
- S3-compatible object-store effect executor;
- IPFS pin effect executor;
- optional per-action Kafka executor;
- projector and portal/reference gateway; and
- later, DPP-specific Cardano publication executor and on-chain code.

### Shared framework follow-ups worth considering

- production Cardano-effect hardening before material funds or DPP assets;
- proof-carrying committed queries / root-pinned proofs;
- principal-aware and possibly write-capable domain API only if protected in-process APIs become a requirement;
- production KMS/HSM/Vault-backed signer/provider implementations; and
- high-throughput benchmarks and operational profiles.

These are bounded shared improvements, not a DPP-specific rearchitecture.

## 16. Suggested delivery order

1. Write the reusable effect-connectors ADR, incorporating the DPP requirements in section 10.
2. Write a separate DPP ADR defining versioned profiles, commands, state, signatures, proofs, status transitions, and publication modes.
3. Implement the DPP contracts/verifier SDK and composite state-machine plugin.
4. Implement `object.put` and `ipfs.pin`, then build the object-store/IPFS-backed demo flow.
5. Implement the external gateway, projector, public portal, and GS1 QR resolution.
6. Reuse the finalized Kafka sink for projection and add `kafka.publish` only for per-action business events.
7. Benchmark batch sizes, projection latency, proof generation, cache behavior, and million-product data volumes.
8. Complete production Cardano-effect hardening.
9. Add optional DPP Cardano asset/CIP-68, batch checkpoint, and recovery-certificate profiles.
10. Add production selective-disclosure/credential and regulator-access profiles as standards and requirements stabilize.

## 17. Acceptance target for a future DPP ADR

A future accepted ADR should not claim "DPP support" until it defines and tests at least:

- deterministic canonicalization with cross-language golden vectors;
- domain actor/device signatures, governance, key rotation, revocation, and replay protection;
- passport registration, update, replacement, revocation, and retirement;
- append-only lifecycle events and deterministic deduplication;
- object-store/IPFS integrity and idempotent effect receipts;
- nested claim/state/finality/Cardano proof verification;
- root-pinned query/cache semantics;
- confidential-data handling and disclosure boundaries;
- recovery/anti-double-counting semantics if advertised;
- restart, replay, rollback, failed-effect, and reconciliation tests;
- multi-node end-to-end tests; and
- scale benchmarks matching any published QR latency or product-count claim.

## 18. Decision deferred

This note intentionally does not decide:

- the final DPP wire/schema standard;
- whether the default Cardano profile is root-only, batch-root, or per-product CIP-68;
- the specific metadata label or minting policy;
- the exact GS1 resolver deployment model;
- a preferred IPFS provider or centralized object-store vendor;
- whether VC/BBS/ZK disclosure is part of DPP v1;
- the recovery-certificate asset model; or
- the exact boundary between the DPP gateway and portal.

Those choices belong in the future DPP ADR and related connector/Cardano sub-ADRs after requirements and interoperability profiles are stable.
