# ADR-025 Phase 2 — backend-neutral prepared commitment runtime

Status: implemented and qualified for the MPF backend; classic JMT remains a
Phase 3 backend.

## 1. Stable backend boundary

`core-api` now defines the profile-neutral contracts
`AuthenticatedStateBackend`, `CandidateState`, `PreparedStateCommit`,
`StateSnapshot`, `StateProof`, and `StateIntegrityReport`.

The behavioral boundary is:

1. `beginCandidate(baseHeight, baseRoot, targetHeight)` validates the exact
   finalized head and creates a single-height read-your-writes view.
2. Candidate state is not durable. `discard()` and close abandon every
   mutation.
3. `prepare()` freezes the target root and backend mutations into a single-use
   prepared commit.
4. The runtime-only prepared extension stages backend mutations into the same
   RocksDB `WriteBatch` as the finalized block, finality certificate, effects,
   message/query indexes, replay floors, governed-membership metadata, current
   state root, and ledger tip.
5. Only `AppLedgerStore` executes `db.write(sync=true)`. It rejects stale base
   roots/heights, wrong target heights, root mismatches, and cross-profile or
   cross-genesis prepared commits before publication.

The production proposer, follower, certified catch-up, and conformance paths
all use this boundary. The older direct commit overload remains package-private
only for focused legacy ledger tests; no production path uses it.

## 2. MPF adapter and compatibility

The CCL MPF adapter evaluates a candidate against an in-memory `NodeStore`
overlay. Reads first see frozen candidate mutations and then the durable CCL
`RocksDbNodeStore`. Preparation deep-copies the final node operations. Finality
stages those operations with CCL's existing namespace prefix into the shared
ledger batch.

This changes persistence timing, not the MPF algorithm, hash function, node
codec, key hashing, root calculation, or native proof wire codec. A direct CCL
baseline and the prepared runtime produce identical legacy roots and inclusion
proof bytes.

Pre-ADR ledgers remain `legacy MPF`: absence of all three state-identity
settings selects the existing root behavior and writes no new state marker or
identity metadata. Existing roots therefore remain reproducible byte-for-byte.

## 3. Genesis-selected identity

New explicit chain generations configure all of these settings together:

```text
state.commitment-profile
state.format-fingerprint
state.genesis-id
```

Values are exact canonical lowercase identities; partial, blank, whitespace,
alias, unknown-profile, fingerprint-mismatch, and non-lowercase-hex forms fail
before participation. `AuthenticatedMapGenesisFactory.settings(...)` derives
the profile fingerprint and canonical authenticated-map genesis id and emits
the complete set.

At height 1 an explicit chain commits the canonical identity under
`~yano/state-commitment/v1`. The same atomic batch retains profile,
fingerprint, and genesis id in ledger metadata. Later transitions, restarts,
state-machine construction, catch-up replay, and snapshot restore compare the
configured identity with retained state and metadata. A wrong peer computes a
different height-1 root or fails the retained marker check and cannot vote for
or apply that chain history.

Legacy configuration cannot open an explicit ledger. Explicit configuration
cannot adopt a legacy, partial, differently fingerprinted, or differently
generated ledger. This phase deliberately defines no in-place migration.

## 4. Proof, status, snapshot, and integrity surfaces

`StateProofEnvelope` binds the proof schema, chain id, finalized block hash,
finality certificate, height/root snapshot, profile/genesis identity,
canonical key, presence classification, native proof encoding, and native
proof bytes. Current and retained-height envelope methods build against one
immutable finalized root. Existing MPF proof endpoints remain compatible;
REST and client verification dispatch are completed in Phase 5.

Status exposes identity schema, profile, backend family, format fingerprint,
genesis id, legacy mode, and oldest provable height. Backend integrity checks
bind the tip block, committed root, and MPF root node.

Snapshot manifest version 2 binds the identity schema, selected profile,
format fingerprint, genesis id, and legacy flag in addition to the prior tip,
membership, anchor, and file hashes. Version-1 manifests remain restorable only
for legacy MPF chains; an explicit generation requires a version-2 identity
binding.

## 5. Failure-boundary qualification

Deterministic injection covers:

- candidate open;
- before and after candidate preparation;
- before and after backend staging;
- before ledger/index staging;
- before the shared durable write;
- after the durable write;
- after post-write verification; and
- before and after restart identity verification.

For every point before `db.write`, close/reopen observes the complete old state:
no tip, root, state leaf, message index, or query index from the candidate is
visible. For both post-write points, close/reopen observes the complete new
state and every index. Prepared updates are single-use; a competing update from
the same base is rejected as stale after the winner commits.

Primary verification commands:

```bash
./gradlew :core-api:test :appchain-stdlib:test :runtime:test
./gradlew test
```

Phase 2 does not claim a JMT implementation. Phase 3 supplies
`jmt-blake2b256-v1` through the same prepared and atomic commit boundary.
