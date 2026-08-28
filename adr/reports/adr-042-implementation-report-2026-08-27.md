# ADR-042 implementation and validation report

Date: 2026-08-27

Branch: `feat/byron-projection-unify`

Target base: `feat/byron-utxo-apply` (PR #90, stacked on PR #86)

## Result

ADR-042 is implemented and its acceptance gate passes. Byron main-block
projection now stages through the UTXO apply batch, the legacy
`proj_byron_utxo` resolver is physically retired by its RocksDB owner, and
EBBs retain the dedicated synchronous carrier. The changed mainnet
`address_transactions` path is exactly equal to the PR #86 oracle through the
fixed block-5,081,500 coordinate.

## Implementation

- `ByronUtxoApplier` captures consumed addresses from committed UTXOs and
  intra-block outputs through the shared `ConsumedAddressCapture`, then stages
  projection sections, UTXO changes, the UTXO cursor and contributor cursors
  in the store-owned batch.
- `CanonicalProjectionContributor` has a Byron-main entry point;
  `CanonicalProjectionCollector` applies one six-case, monotonic reconciliation
  rule to both eras.
- `ByronBlockProjectionEvent` is EBB-only. Its main-block factory, accessor and
  discriminator were removed immediately; `BodyFetchManager` no longer emits
  a second main-block projection event.
- Startup reconciles the outbox to the canonical full point before drain,
  including sink/acknowledgement fail-closed checks. Snapshot replacement
  holds the maintenance lease and rebinds native handles before services
  resume.
- Explicit UTXO rebuild temporarily installs the no-op contributor, restores
  the live contributor in `finally`, keeps normal drain work independent, and
  validates the rebuilt full point.
- `ADDRESS_TRANSACTION` now fails projection initialization when any built-in
  or plugin UTXO filter is configured. The preflight and live filter chain use
  the same resolver and agree before sync starts.
- The chain-state RocksDB owner conditionally opens an existing
  `proj_byron_utxo` family, drops it before publishing shared access, removes
  and closes its handle, and never creates it for a fresh database.
- Continuity observation caches the retained applied point across apply,
  rollback and reinitialization. This removed an iterator seek from every
  block while retaining the era-neutral safety check.

## Review findings incorporated

Implementation and review exposed several composition issues that were fixed
before acceptance:

- outbox recovery had to run before the consumer could acknowledge or prune a
  pending non-canonical suffix;
- filter discovery had to be available before projection initialization, not
  only when the later live chain was assembled;
- devnet block-producer startup needed a single Shelley-genesis seeding owner
  to avoid additive stake-balance duplication;
- snapshot restore needed dynamic projection handle rebinding and a history
  directory outside the replaceable chain-state tree; and
- an initial mainnet candidate ran below the 3,000 blocks/s gate because the
  continuity observer sought the delta CF on every apply. Caching the retained
  point raised the final measured rate above the gate without weakening the
  check.

## Automated verification

The final broad regression command passed:

```text
./gradlew :core-api:test :runtime:test :ledger-state:test \
  :archive-modules:archive-core:test \
  :archive-modules:archive-store-ducklake:test :app:test --no-daemon
```

Result: 2,675 tests, 0 failures/errors, and 3 pre-existing runtime skips.
Module counts were core-api 273, runtime 1,405, ledger-state 276, archive-core
328, DuckLake 99 and app 294. The Quarkus packaged build and focused ADR-042
tests also passed. `git diff --check` passes.

The tests cover the shared consumed-address capture, intra-block Byron spends,
atomic batch failure, all six reconcile cases, EBB coordinate semantics,
exact-point pre-drain recovery, filter preflight, rebuild suspension,
snapshot-handle rebinding, legacy-CF removal, async resolution, and continuity
cache behavior after rollback.

## Devnet and downstream-node validation

The current BP devnet composition passed:

```text
./gradlew :app:e2eTest --tests '*DevnetApiE2ETest' --no-daemon
```

Result: 6/6 tests. This includes projection-enabled snapshot restore and a
subsequent projection-backed rollback.

The real Haskell downstream-node composition passed:

```text
./gradlew :app:haskellSyncTest --tests '*RegularBPSyncTest' --no-daemon
```

Result: 1/1. `cardano-node` 11.0.1 followed the Yano producer through slot
1,203 (target 1,200), crossed an epoch boundary, and ended at the exact same
tip. All validation processes were stopped after the runs.

## Fresh-mainnet performance leg

The candidate used a fresh `mainnet,wallet` data set at:

```text
/Users/satya/Downloads/yano-adr042-mainnet-candidate-cache-20260827
```

Packaged JAR SHA-256:
`cdcfaa1375bbc1a46e44a1598ff2f4d68ff17013d8e48e6deeb7df7ec67351be`.

Block 99 was logged at `03:10:21.942` and Byron block 4,490,424 at
`03:33:38.220`: 4,490,325 blocks in 1,396.278 seconds, or **3,215.9
blocks/s**. The run therefore passes the issue-#88 reference-machine gate of
3,000 blocks/s. It captured the exact Shelley-start total
`31111977147073356` lovelace and continued beyond the comparison pin with:

- zero Byron and Shelley unresolved inputs;
- zero continuity warnings;
- zero projection drain failures; and
- archive acknowledgement beyond block 5,081,500.

## Mainnet differential oracle

The legacy leg was built from PR #90 base commit `d65cddcb`, where the PR #86
Byron carrier and `ByronOutputAddressIndex` path are intact. Its original
`WriteOptions.setSync(true)` behavior measured about 128 blocks/s, consistent
with issue #88 and impractical for the fixed-point run. In an isolated detached
validation worktree only, `ProjectionOutboxStore.commit` was changed to
`setSync(false)`. This changes durability latency, not envelope construction,
batch contents, row encoding or archive identity. No such change exists in the
implementation branch.

Legacy validation directory:

```text
/Users/satya/Downloads/yano-adr042-mainnet-legacy-fast-20260827
```

Validation-only JAR SHA-256:
`44c0bbe05cc533fac92ce3bba7862286cb159fbe3999a2f7cd42a0b1e187524f`.
The leg was stopped gracefully after reaching chain block 5,104,607 and archive
ACK 5,099,932, with zero unresolved inputs, continuity warnings or drain
failures.

At the lesser fixed coordinate, block 5,081,500, the comparison covered every
column of `address_transactions`:

```text
rows                 14,667,701 on each leg
distinct tx_hash      3,184,885 on each leg
minimum block             3,314 on each leg
maximum block         5,081,499 on each leg
100k-block buckets           51; 0 mismatches
EXCEPT ALL legacy-only        0
EXCEPT ALL candidate-only     0
```

The compared fields included subject type/key, address, stake address,
transaction and block hashes, block number, slot, epoch, block time, transaction
index, input/output and collateral counts, and `archive_job_id`. Subject counts
also matched exactly: 12,610,950 address rows, 1,263,593 payment-credential
rows and 793,158 stake-credential rows. This covers all Byron main blocks and
the Shelley suffix through the pin, including Shelley consumption of
Byron-created outputs.

The earlier ADR-039 phase-7a oracle remains the accepted differential for the
unchanged section builders and other datasets. ADR-042's fresh mainnet oracle
targets the path this decision actually replaces: `ADDRESS_TRANSACTION`.

Both legs reported the same pre-existing epoch-artifact marker,
`epoch boundary slot mismatch`. It is outside the transaction-section path,
did not cause a projection drain failure, and is not treated as ADR-042 parity
evidence or as an ADR-042 regression.

## Release notes

Yano preview users should note these source-breaking changes, made without a
deprecation cycle because neither API was part of a released compatibility
contract:

- the main-block half of `ByronBlockProjectionEvent` is removed; the type is
  now EBB-only and main-block consumers use `ByronMainBlockAppliedEvent`;
- `ProjectionCfNames.PROJ_BYRON_UTXO` is removed, and an existing physical
  `proj_byron_utxo` column family is dropped on first open; and
- `ADDRESS_TRANSACTION` projection refuses a configured UTXO filter during
  initialization because complete consumed-address history requires the
  unfiltered full-state store.
