# ADR-048 phases 0–3 implementation report

Date: 2026-08-28
Branch: `adr-048-bounded-epoch-memory`

## Phase 0 — attribution

Added low-allocation boundary telemetry with phase wall/CPU time, heap,
process RSS where the platform exposes it, GC deltas, and RocksDB block-cache,
pinned-cache, memtable, table-reader, and pending-compaction properties. The
processor retains the last summary for tests and future operational exposure.

The JVM/native checkpoint attribution matrix is intentionally deferred to the
live validation stage. In-process RSS is unavailable on macOS, so the live run
must use the skill-required external one-second sampler as the authoritative RSS
source.

## Phase 1 — ordered live stake index

- Added a storage-neutral, close-scoped ordered stake-balance view.
- The view owns a RocksDB snapshot, uses `fillCache(false)`, validates positive
  rows and fails closed unless block number, slot and hash match exactly.
- Persisted the UTXO last-applied hash in the same batch as UTXO/index changes.
  Rollback now restores or removes number, slot and hash together.
- Versioned the stake-balance ready marker.
- Replaced the three divergent address parsers with one shared extractor that
  accepts both Bech32 and stored raw-hex representations.
- Changed fallback UTXO iteration to bypass the block cache.
- Introduced clean preview chainstate marker `epoch-boundary-state-v1` and
  explicit non-destructive rejection of non-empty pre-v1 account state.
- Added a dedicated, rollback-journaled latest-deregistration coordinate index
  with its own v1 completeness marker. Pointer-index cleanup cannot affect it.
- Removed the 300-second timeout followed by a second full UTXO scan. The one
  submitted fallback scan is now awaited once and failures abort the boundary.

## Phase 2 — streaming SNAP and governance

- Conway SNAP walks the ordered live balance view instead of retaining the full
  UTXO credential map.
- The snapshot-validity path reads the dedicated latest-deregistration index;
  it does not reuse the incomplete/cleanable pointer index.
- DRep distribution opens a new coordinate-bound balance view on every normal
  or resumed governance execution.
- Removed the resumed-run dependency on pool snapshot amounts, fixing the
  existing double-reward/non-pool-delegator crash-resume defect.
- `auto` is now the default balance mode; `full-scan` remains an explicit legacy
  differential path and `index` is fail-closed.

## Phase 3 — immediate reward lifetime reductions

- Canonicalized duplicate pool-hash strings while decoding retained snapshots.
- Built delegator and owner sets directly instead of list-to-set/set-to-set
  copies.
- Avoided the post-Babbage duplicate deregistration HashSet.
- Added filtered deregistration-event queries and point registration checks so
  reward input assembly does not retain every current account solely for
  membership tests.
- Used a read-only relevant-credential union view instead of copying every
  snapshot credential into another HashSet.

## Review findings applied

- F1: separate complete latest-deregistration index; no pointer-index reuse.
- F2: independent DRep stake view on resumed governance.
- F3: atomic UTXO block hash plus clean-v1 chainstate policy.
- F4: no reward-library fork; pool-major orchestration remains Phase 5 work.
- F5: no chunked generation has been enabled yet; artifact atomicity is
  unchanged through these phases.

## Tests at this gate

```text
./gradlew :ledger-state:test \
  --tests '*OrderedStakeSnapshotTest' \
  --tests '*DRepDistributionCalculatorTest' \
  --tests '*EpochBoundaryProcessorTest' \
  --tests '*BoundaryRecoveryIdempotencyTest' \
  --tests '*EpochRewardCalculatorTest' \
  :runtime:test \
  --tests '*DefaultUtxoStoreTest' \
  --tests '*StakeBalanceIndexStartupMigrationTest' \
  :core-api:test --tests '*StakeCredentialExtractorTest'
```

Result: `BUILD SUCCESSFUL` (24 seconds).

The focused suite covers ordered cursor coordinates and rollback, ready-marker
migration, Bech32/raw-hex extraction parity, SNAP stake-plus-live-reward output,
and DRep no-crash map versus coordinate-view parity.
