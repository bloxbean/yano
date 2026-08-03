# ADR-025 Phase 0 — contract, measurement, and dependency gates

Status: implemented baseline; production qualification remains blocked on the
release and durable-runtime gates below.

This note records the executable decisions completed before the Phase 1 state
machine and the Phase 2 backend refactor. It does not change ADR-025.

## 1. Frozen v1 contract

The canonical implementation is `AuthenticatedMapContract` in
`appchain-stdlib-contracts`. Its checked-in vectors are verified from Java and
from an independent standard-library Python implementation.

The v1 choices are:

- state-machine id/version: `authenticated-map` / `1`;
- application namespace kind: `1`, with the exact length-delimited binary key
  format defined by ADR-025;
- canonical lowercase ASCII collection ids, bounded to 64 bytes;
- opaque application keys bounded to 128 bytes;
- opaque value bytes, with application-specific canonical codecs enforced by
  the owning application before command construction;
- `ACTIVE` and included `REVOKED` tombstone entry states;
- revisions starting at one and increasing exactly once per successful
  mutation;
- `PUT`, `PUT_IF_ABSENT`, `COMPARE_AND_SET`, `TRANSFER_CONTROLLER`, `REVOKE`,
  and `RESTORE` mutations;
- a command-level `BATCH` form that preserves submitted order, rejects
  duplicate collection/key pairs, and is bounded to 128 items / 1 MiB before
  the chain's tighter genesis/message limits are applied;
- logical value hash and batch commitment using domain-separated Blake2b-256;
  and
- canonical genesis bytes binding the chain, machine, profile/fingerprint,
  framework-consensus digest, membership commitment, anchor-policy commitment,
  collections, initial entries, and resource bounds.

The initial authorization subset is deliberately limited to:

1. `open`: any already admitted signed sender;
2. `owner`: the creator/controller controls subsequent mutation; and
3. `member`: any active consensus member at the candidate height.

`governed-role` and approval-reference authorization need explicit component
state contracts and cross-component/JMT qualification. They are not silently
accepted by v1.

## 2. Profile and dependency gate

The closed profile catalog is implemented in `core-api` as
`StateCommitmentProfiles`. A Yano profile id and a dependency descriptor are
separate values and both contribute to the format fingerprint.

The implementation baseline is pinned to CCL `0.8.0-pre5-dev1`. The build gate
resolves the MPF, classic JMT, and crypto artifacts and fails if any resolve to
a different version. The classic profile additionally asserts that CCL reports
`classic-radix16-blake2b256-v1`.

This development release is not production qualification. Moving to a proper
CCL release requires all of the following to remain byte-identical or to use a
new Yano profile id:

- normalized dependency descriptor and format fingerprint;
- canonical map keys, entries, commands, and genesis bytes;
- MPF and JMT roots;
- inclusion and non-inclusion proof bytes and verification; and
- persistence, restart, snapshot, pruning, and historical-proof behavior.

The `jmt-poseidon-bls12381-v1` consensus identity is reserved, but no runtime
resolver or benchmark claims it is available. Its dependency, persistence,
proof, and measurement gates are deferred with Phase 4 because the required
ZeroJ release is unavailable.

## 3. Prepared-update/external-batch contract for Phase 2

The runtime contract must satisfy these rules:

1. `beginCandidate(baseHeight, baseRoot, targetHeight)` obtains a root-fixed
   candidate with read-your-writes behavior and one serialized writer.
2. Candidate mutation and root calculation perform no durable write—not tree
   nodes, roots, values, stale markers, version indexes, or metadata.
3. `prepare()` freezes an immutable, single-use update containing the target
   profile/version/root and all backend mutations.
4. `stage(WriteBatch)` adds those mutations to the same RocksDB batch that
   contains the finalized block, certificate, message/query indexes, framework
   state, current root, and tip.
5. Only the runtime owns `db.write(sync=true)`. A backend must not publish a
   latest root independently.
6. Closing/discarding a candidate or prepared update before the shared write
   changes no durable state.
7. A prepared update verifies the expected base height/root and rejects stale,
   repeated-with-different-root, cross-profile, or cross-generation use.
8. Fault seams exist before/after candidate freeze, backend staging, ledger
   staging, shared write, and post-write verification. Restart must observe the
   old complete state or the new complete state.

CCL's current JMT `put(version, updates)` commits through its store, so it must
not be called directly from proposal execution. Phase 2/3 must either use a
CCL non-persisting calculation API with an external batch or implement the
same `JmtStore` contract against the shared ledger batch. A separately
committed side database is not accepted.

## 4. Projection boundary

Authoritative queries are bounded point/history/proof/receipt lookups against a
specific finalized root. Search, prefix, range, full-text, aggregation, and
analytics are derived projections.

A projection response must expose at least:

- `derived=true`;
- chain and genesis identity;
- projector schema/version;
- projected-through finalized height and block hash; and
- lag/rebuild status.

Projection stores consume finalized blocks/receipts, are rebuildable, do not
enter consensus, and never label their results as authenticated range proofs.

## 5. Measurement baseline

Reproduction command:

```bash
./gradlew :appchain-stdlib-contracts:benchmarkAuthenticatedMapContracts
```

The 2026-08-03 development-machine baseline used 100,000 keys, 10,000 updates,
5,000 revocations, batches of 100, and 128 verified inclusion-proof samples.

| Profile | Operations/s | Batch p50 / p95 / p99 | Proof p50 / p95 / p99 | Mean proof bytes |
|---|---:|---:|---:|---:|
| `mpf-blake2b256-v1` | 15,310.9 | 5.88 / 9.90 / 15.68 ms | 0.056 / 0.127 / 0.227 ms | 653 |
| `jmt-blake2b256-v1` | 33,778.6 | 2.69 / 3.96 / 6.42 ms | 0.039 / 0.080 / 0.186 ms | 2,259 |

These are single-process in-memory algorithm/codec observations, not durable
runtime bounds or a comparative product claim. They intentionally exclude
RocksDB sync latency, external-batch staging, disk growth, compaction, restart,
snapshot/restore, catch-up, pruning, crash injection, and sustained soak.
Phase 3 must publish those measurements at the implemented runtime boundary.

The harness accepts `-Padr025BenchmarkKeys` and `-Padr025BenchmarkBatch`, so the
100 thousand, 1 million, 5 million, and—where practical—10 million key matrix
can be executed without changing code. Resource/time limits and every omitted
cell must be reported rather than inferred.

## 6. Phase 0 acceptance

- Strict canonical codecs and fail-closed validation: complete.
- Java cross-module vectors for keys, values, commands, genesis, roots, and
  proofs: complete.
- Independent Python validation of codecs, fingerprints, classic JMT root, and
  classic JMT proofs: complete.
- CCL development pin and descriptor gate: complete.
- Comparable MPF/classic-JMT baseline harness and 100k run: complete.
- Prepared external-batch and projection boundaries: frozen here.
- Proper CCL release qualification: pending external release, explicitly
  non-production.
- Poseidon measurement/release gate: deferred with Phase 4.
