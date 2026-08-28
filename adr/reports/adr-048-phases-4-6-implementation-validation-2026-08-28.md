# ADR-048 implementation and preprod validation report

Date: 2026-08-29
Branch: `adr-048-bounded-epoch-memory`

## Outcome

Phases 0–6 are implemented in the clean preview chainstate format. Phase 7 is
intentionally deferred until the compatibility and mainnet acceptance window
closes. The implementation removes the network-sized UTXO credential map from
the normal epoch path, streams SNAP and DRep inputs from ordered indexes, bounds
reward/snapshot persistence and rollback, and owns only the outer per-pool loop
around `cf-java-rewards-calculation` 1.0.2.

This is `epoch-boundary-state-v1` with `delta-v1` and `rollback-v1`; there is no
v2 migration layer. Per the user-approved preview policy, a populated pre-v1
chainstate is rejected with a backup/resync instruction.

## Implemented design

- Phase telemetry records heap, GC, CPU, RocksDB aggregate properties, selected
  source/mode and phase timing.
- A shared byte-backed credential extractor is used by incremental index writes,
  rebuild and compatibility scan. Malformed Shelley-prefixed addresses fail
  closed; supported Byron representations are consistently excluded.
- The stake-balance index is opened through an ordered RocksDB snapshot bound to
  exact block number, slot and hash. Readiness marker version 2 invalidates the
  older extractor contract.
- Conway SNAP and DRep calculations merge ordered stake, delegation, account,
  registration and dedicated latest-deregistration indexes. Pre-Conway adds only
  a pointer-address overlay; it never rebuilds the full non-pointer map.
- Boundary source selection is proved before mutation and is carried through
  SNAP. A selected index that later becomes unavailable fails closed.
- Reward and snapshot writes flush at the lower of 10,000 operations or 4 MiB
  estimated payload. Generation `BUILDING` is never published as complete.
- `rollback-v1` records its target before mutation and reverses one bounded
  phase/chunk at a time using an explicit descriptor order and durable cursor.
- Snapshot generations contain credential-major public rows and a pool-major
  reward projection. The Yano orchestrator holds one pool at a time and uses the
  existing public CF per-pool API.
- For historical pre-Vasil rewards, `0 < d < 0.8` uses `nonOBFTBlockCount`,
  matching the CF epoch wrapper. A golden test uses unequal total/non-OBFT counts.
- `STEP_STARTED` and `(previousEpoch,newEpoch,slot,blockNumber)` are committed
  synchronously together. Startup restores the exact context before a resumed
  Conway governance phase; missing metadata fails closed.
- A partial streaming reward cursor cannot be resumed in legacy mode or without
  its complete pool-major generation, preventing double credit.
- RocksDB uses a 32 MiB shared block cache, 64 MiB database write-buffer budget,
  two background jobs and evictable L0 index/filter blocks. Sequential boundary
  scans use `fillCache(false)`.
- Native launchers and the image use a configurable 384 MiB heap default. This is
  a guardrail selected after a 352 MiB post-boundary OOM, not a completed mainnet
  acceptance claim.

## Review iteration

Claude's round-2 C1–C5 findings were reproduced and fixed:

1. the pre-Vasil effective block-count rule now matches the CF library;
2. boundary coordinates are durable and restored before Conway resume;
3. mode changes with partial reward progress fail closed;
4. malformed Shelley addresses are no longer silently omitted; and
5. the stake-index ready marker was bumped to version 2.

The reward default remains `legacy` until mainnet acceptance. Validation config
explicitly selects `streaming`. Stake-source values are validated as
`auto|index|scan`, with `full-scan` retained as an alias for `scan`.

The ADR was amended to make chunking part of the single clean v1 format. The
source-level mainnet lower bound already exceeds the 50 MiB component trigger:
more than one million facts multiplied by key/value and reverse-journal bytes
exceeds the budget before Java/native batch overhead. Actual RSS and performance
gates remain mandatory.

## Automated verification

Focused correctness suites pass for the shared extractor, reward golden parity,
partial-progress rejection, persisted boundary recovery, ordered SNAP/DRep,
stake-index marker versioning, and startup ordering. Complete `:core-api:test`
and `:ledger-state:test` suites pass.

The full runtime suite was independently run by Claude with 1,413 passing and
three skipped tests. A later local full-suite rerun stalled inside native
`RocksDB.open` in `AppChainStaleLockTest`; that exact test and the affected
ledger-state subsystem tests pass in isolation. This suite-order native lock
stall is reported rather than counted as a clean second full run.

Syntax and patch checks pass:

```text
bash -n app/bin/yano.sh
sh -n docker/native/entrypoint.sh
git diff --check
```

The final Liberica NIK 25.0.3 build completed in 1m40s with Parallel GC,
402.65 MB maximum heap, 334.18 MB image size and 6.67 GB build-only peak RSS.
The staged binary SHA-256 is
`33ce25b9f8bfd4051ad4e473e8a32746150b39cb3b95569e2ef8c443194c39c7`.
Build-time RSS is not a runtime acceptance measurement.

## Preprod validation

The retained validation directory is:

```text
/Volumes/data2/yano-try/adr-048-preprod-native-final-20260828
```

Its config explicitly has:

```yaml
yano:
  exit-on-epoch-calc-error: true
  epoch-boundary:
    reward-mode: streaming
```

Important observations:

- The legacy pre-Conway full-map control reached approximately 986 MiB RSS and
  failed at epoch 83.
- After the pointer-only overlay, recovered epochs 93–100 passed AdaPot; a kill-9
  after epoch-97 rewards resumed without double credit and epoch 100 matched
  Koios.
- Epochs 178–185 (Conway) passed AdaPot with the ordered stake-index path. The
  sampled process peak was 547,488 KiB (534.7 MiB); boundary times were
  0.886–7.656 seconds.
- The final correctness binary rebuilt the version-2 stake index from 1,787,928
  UTXOs in 12.835 seconds, producing 153,577 credentials and total lovelace
  `1752755130335272`.
- Epoch 187 passed AdaPot on the streaming/index path in 6.381 seconds. Its peak
  live heap was 179,103,168 bytes; rewards took 5.886 seconds, snapshot write
  274 ms and governance 212 ms.
- Koios preprod epoch 187 exactly matched Yano/embedded AdaPot evidence:
  treasury `1208505546300935`, reserves `13764153160025324`.
- The same 352 MiB process OOMed roughly one minute later while applying an
  ordinary heavy block. The boundary was already complete. Therefore 352 MiB is
  rejected as an unsafe process default and 384 MiB is used pending mainnet
  tuning.
- A subsequent restart recovered the one failed block, but its cached header
  cursor intersected ahead of durable body apply and produced a high-RSS/no-body-
  progress recovery session. That run is not used as positive memory evidence.

Earlier exact Koios comparisons also passed:

| Epoch | Treasury | Reserves |
|---:|---:|---:|
| 95 | 574916520059529 | 14416203249445502 |
| 100 | 610361479449239 | 14379913493960594 |
| 164 | 1064528229412945 | 13912473910928082 |
| 187 | 1208505546300935 | 13764153160025324 |

## Remaining rollout gates

Implementation completion is not the same as mainnet acceptance. Before
streaming rewards or the RocksDB/native budgets become production defaults, run
the ADR's fixed-range mainnet comparison for blocks/s, boundary wall time,
RocksDB stalls/flush/L0/write amplification, three consecutive Conway
boundaries, crash-after-SNAP resume, and rollback fault injection. The current
preprod result establishes correctness and a representative 534.7 MiB Conway
peak, but it does not prove the 500–600 MiB target for mainnet cardinality.

A clean mainnet sync was started from genesis at
`/Volumes/data2/yano-try/adr-048-mainnet-native-20260829` using the final binary,
`exit-on-epoch-calc-error=true`, explicit streaming rewards and continuous
five-second RSS sampling. Initial body apply reached block 63,739 in 32 seconds
with 329 MiB RSS and no runtime-degraded or calculation error. This records the
start of mainnet validation; it is not a completed boundary gate.

For a follow-up native-image A/B, Oracle GraalVM 25.1 added G1 on macOS AArch64
and 25.2.4 enables compressed 32-bit references by default. The current Liberica
NIK 25.0.3 build has Parallel GC. Compare current Parallel against Oracle
GraalVM 25.2.4 G1 (and its newer Serial Adaptive2 policy) from the same mainnet
checkpoint before changing the release toolchain.

## Compatibility cleanup

Phase 7 remains gated. Legacy reward and explicit scan modes stay available for
differential validation. The pre-Conway pointer-only pass is not temporary—it is
required because the base index deliberately excludes pointer addresses.
