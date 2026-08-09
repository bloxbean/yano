# ADR app-layer/032: Out-of-the-box verifiable indexer components

**Status:** Proposed — product direction; no runtime or consensus change
**Date:** 2026-08-08
**Scope:** Off-chain indexing, archival, and verifiable read APIs for app chains
**Related:** ADR app-layer/006 (query/SSE/webhook/evidence extensions), 022 (OOB capability catalog),
031 (typed proof subjects, proof bundles, finalized-message index)
**Depends on:** ADR-031 Phase 5 (typed proof subjects and codecs)

---

## 1. Context

App-chain nodes commit truth: finalized blocks with full message bodies, authenticated state, and
roots that can be anchored to Cardano. They do not promise long-term availability or rich queries.
Retention may strip message bodies while keeping identifiers and certificates, and applications such
as `doc-trail` deliberately keep only `entryHash + ref` on chain while documents live off-chain
(ADR-031 §3.5 separates **finalized**, **state-recorded**, **content-verified**, and **available**).

Today each product builds its own indexer. The eUTxO extension ships a complete one —
`appchain-eutxo-indexer-core`/`-jdbc` with coordinator, projector, pluggable store, checkpoints,
cursors, coverage/health, and a written replay contract (`INDEXER_REPLAY_CONTRACT.md`) — but it is
product-local. Every other application either goes without an indexer or copies this work.

ADR-031 removes the main blocker to a generic offering: once state machines publish typed proof
subjects and contract-owned codecs, an indexer can decode any stock machine's keys and values
instead of treating state as opaque bytes.

## 2. Decision

Yano will provide **out-of-the-box indexer components** as a read-side product family:

1. **A generic indexer core** — follow finalized blocks, checkpoint, replay, project into pluggable
   sinks — extracted by generalizing the proven eUTxO indexer pattern.
2. **Per-state-machine indexer modules** built on ADR-031 typed contracts (ordered-log,
   authenticated-map, doc-trail, approvals first).
3. **A content archiver** for hash-plus-reference applications (doc-trail): fetch the referenced
   body at ingest, verify it against the committed hash, and store it durably.
4. **A verifiable read API** that serves indexed data together with its proof chain (ADR-031 proof
   bundle v2) back to the app-chain root and, where anchored, the Cardano anchor.

The indexer is **derived state and never an authority** — the same doctrine as the eUTxO replay
contract. It is strictly off-consensus: no determinism constraints, independent release cadence,
free choice of storage.

## 3. Architecture

```text
app-chain node (canonical)                      indexer product (derived)
  finalized blocks + certs      --- follow --->   indexer core
  state queries / proofs        --- verify --->     checkpoints, replay, cursors
  SSE / webhook (ADR-006)       --- notify --->     projector -> sink (JDBC first)
                                                      |         |
                                                per-machine   content
                                                modules       archiver (ref fetch + hash check)
                                                      |
                                                verifiable read API
                                                  (rows + proof bundle v2 + anchor ref)
```

### 3.1 Indexer core

Responsibilities, generalized from `EutxoIndexCoordinator`/`EutxoProjector`/`EutxoIndexStore`:

* tail finalized blocks (poll or SSE), honoring finality certificates — no rollback handling past
  certified height;
* durable checkpoints committed in the same transaction as projected rows (position persisted only
  after the sink commit, as the eUTxO replay contract already requires);
* deterministic replay from canonical sources so an index can always be rebuilt from a node;
* `chainId` partitioning of every index;
* coverage, health, and metrics surfaces.

### 3.2 Per-machine modules

Each module consumes its state machine's typed subject/codec contract (ADR-031 Phase 5) and owns its
relational schema:

| Module | Projects | Notes |
|---|---|---|
| ordered-log | message history, position records, bodies | body capture before retention stripping |
| authenticated-map | entry history with revisions, receipts | governed actions include consumption records |
| doc-trail | per-entity trails, entry hashes, refs | pairs with the content archiver |
| approvals | proposals, votes, terminal outcomes | decision-trail queries |

Modules are additive; a deployment enables only the machines its chain runs. Custom state machines
can ship their own module against the same core SPI.

### 3.3 Content archiver

For hash-plus-reference data the archiver closes the availability gap:

* at ingest, fetch the body from `ref` (HTTP, IPFS, object store);
* recompute and compare the committed hash — mismatches are recorded, never silently stored;
* store the verified body under its hash; serve it by hash thereafter.

This turns "the chain committed to this document" into "and here is the document, verifiably."
An archiver retention/storage SLA is the deployment's data-availability promise; the chain itself
never makes one.

### 3.4 Verifiable read API

Indexed answers can carry their evidence: a queried row links to the message id / state key it was
derived from, and the API can attach an ADR-031 proof bundle v2 (state proof or message-inclusion
proof, finality certificate, anchor reference). Consumers verify against an independently trusted
root or anchor; the indexer is a convenience, not a trust root.

## 4. Principles

1. **Never an authority.** Consensus, proof verification, and accounting never read the index.
2. **Verify on ingest.** Bodies and refs are checked against committed hashes before storage.
3. **Rebuildable.** Dropping the index database and replaying from a node yields the same rows.
4. **Honest availability.** The API distinguishes finalized / state-recorded / content-verified /
   available, per ADR-031 vocabulary; archiver coverage is reported, not assumed.
5. **Read-side freedom.** No determinism rules, no consensus coupling, storage per deployment.

## 5. Milestones

* **IX-M1 — Core extraction.** Generalize the eUTxO indexer core (coordinator, checkpoints, replay,
  sink SPI, JDBC sink) into a product-neutral module; eUTxO indexer becomes its first consumer or
  remains as-is with the new core proven by IX-M2.
* **IX-M2 — First modules.** ordered-log and doc-trail modules over ADR-031 typed contracts,
  including body capture and the doc-trail schema.
* **IX-M3 — Content archiver.** Ref fetching, hash verification, mismatch reporting, hash-addressed
  storage; wired to doc-trail.
* **IX-M4 — Verifiable read API.** Row-to-evidence linkage and proof-bundle attachment; served
  through the existing app REST surface or a standalone service.
* **IX-M5 — Packaging.** Distribution via the plugin/product catalog (ADR-022) and a Spring starter;
  authenticated-map and approvals modules.

IX-M1 touches no state-machine behavior and can start immediately (coordinating module moves with
ADR-031 Phase 6, which relocates code from the same eUTxO tree); IX-M2+ need Phase 5 typed subjects.

## 6. Non-goals

* No consensus or runtime changes; no new commitments or proof formats.
* No promise of universal data availability — availability is a deployment SLA built with these
  components, not a chain property.
* No replacement of the node query/proof APIs; the indexer complements them.
* No indexing of non-finalized data.

## 7. Acceptance criteria

1. A chain running stock machines gets a working indexer by configuration only.
2. Index rebuild from a node reproduces identical rows (golden replay test).
3. Doc-trail deployment serves a document by hash with a verification chain to the anchored root.
4. A tampered body or wrong-root proof is rejected by the verifiable read path.
5. The eUTxO indexer's guarantees are preserved or improved by the shared core.

## 8. Open questions

* Schema versioning and migration policy for module-owned relational schemas.
* Multi-chain deployments: one database with `chainId` partitioning (current eUTxO practice) versus
  database-per-chain.
* Whether the archiver should support pluggable fetchers (IPFS, S3, HTTP) in IX-M3 or start
  HTTP-only.
* Standalone service versus embedded-in-node-app packaging as the default distribution.
* Proof capture at ingest/anchor time versus on-demand node queries: node proof retention is bounded
  (JMT pruning watermark, operator-dependent MPF retention), so serving proofs for old data may
  require the indexer to store them when they are still obtainable.
