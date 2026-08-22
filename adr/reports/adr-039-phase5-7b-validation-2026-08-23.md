# ADR-039 Phase 5 completion, Phase 7b, and final preprod validation (2026-08-23)

## Phase 5 — all five epoch artifacts

| dataset | representation | reconstructibility |
|---|---|---|
| `EPOCH_STAKE` | IMMUTABLE_GENERATION | RECONSTRUCTIBLE |
| `ADA_POT` | ATOMIC_EVIDENCE | RECONSTRUCTIBLE |
| `REWARD` | STAGED_FILE | **IRREPRODUCIBLE** |
| `DREP_DISTRIBUTION` | STAGED_FILE | **IRREPRODUCIBLE** |
| `GOVERNANCE_PROPOSAL_STATUS` | STAGED_FILE | **IRREPRODUCIBLE** |

The three new ones are not claimed reconstructible. Rewards would need the complete
deterministic input closure — every stake and pool input at the boundary plus the exact
calculation version — and that closure is not proven, so the final calculated rows are
preserved as computed. DRep distribution is captured **whole** at the strictest class
any column requires: `amount` alone would be reconstructible, but `storedExpiry`,
`dormantEpochs`, `effectiveExpiry` and `active` are boundary state that later
governance activity overwrites, and capturing the halves separately would let
re-derived amounts diverge from recorded state. Governance proposal status is an
observation taken *at* a boundary; the mutable state that follows records the outcome,
not the observation.

### The staged-artifact mechanism, hardened not redesigned

It used an atomic rename and nothing else. A rename makes a file appear whole to a
concurrent reader; it does not make it survive power loss. Rows are now fsynced before
publication and the directory after it, in that order — a durable name pointing at
bytes that may not exist is worse than neither. Rows publish before the manifest,
because the manifest makes a job discoverable: a crash between them leaves an orphaned
file that cleanup removes, never a discoverable job whose evidence is not durable.

Size and SHA-256 are recorded and verified before a single row is served. Truncation
is the case that matters: the row loop stops at EOF, so a short rewards file would
otherwise be served as a complete epoch that simply had fewer delegators.

### The complete flow, proven

    staging capture -> fsync + checksum -> outbox reference -> sink commit
    -> receipt -> acknowledgement -> lease release -> cleanup

Ordering carries the argument at both ends. The reference is written only after the
evidence is durable, so a reference always implies evidence. Acknowledgement is what
permits release — never the reverse — so evidence is deleted only once a receipt
proves its rows exist elsewhere. **At tip, zero staged files remained**: every one was
released after its acknowledgement.

## Phase 7b — far smaller than planned, and correctly so

The epoch staging foundation is **not** obsolete: it is the projection's own write
path for the three STAGED_FILE datasets. Recomputing the closure after Phase 5 showed
`EpochArchiveStagingService`, `DurableEpochFileSource`, `EpochArchiveSource`,
`EpochArchiveJob`, `EpochSourcePage`, `EpochFactCodec` and `ArchiveSourceLease` all
reached from the projection. Deleting them, as the first draft proposed, would have
removed the only working path for three datasets.

Genuinely obsoleted by Phase 7a and removed: `BlockArchiveSource` (no block source
remains) and `UtxoPrefetchConfig` (replay-only tuning).

**One archival write path confirmed:** `ProjectionOutboxConsumer` → `sink.append` is
the only ongoing writer. The genesis bootstrap is a one-time operation on a fresh
archive.

## Final preprod validation — projection only

| | |
|---|---|
| blocks | 5,087,259 |
| **time to tip** | **65m 05s** (02:28:39 → 03:33:44) |
| throughput | p50 **1,189 blk/s**, p90 1,861, max 3,537, min 360 (63 samples) |
| batches | 4,124 |
| **drain failures** | **0** |
| contributor cursors | all four at 5,087,259 — lockstep |
| resident memory | 1.49 GB – 3.93 GB (`-Xmx4g`) |
| archive | 7.3 GB (chainstate 24 GB) |

### Datasets at tip

| table | rows |
|---|---|
| `transactions` | 6,613,955 |
| `address_transactions` | 39,064,515 |
| `account_events` | 429,765 |
| `epoch_stakes` | 4,565,194 |
| `rewards` | **2,064,557** |
| `drep_distributions` | **15,113** |
| `governance_proposal_statuses` | **807** |
| `ada_pots` | 305 |
| genesis marker | 1 row, 30,000,000,000,000,000 lovelace |

`epoch_stakes` and `ada_pots` match the differential oracle's counts exactly.

### Recovery

| | graceful | kill -9 |
|---|---|---|
| acknowledgement | 5,082,939 → 5,082,939, **no regression** | same |
| genesis | "already captured", idempotent | same |
| drain failures | 0 | 0 |
| APIs after restart | healthy | `queryable 0..5,082,939`, 4,326 behind tip |

### APIs

`GET /addresses/{addr}/transactions` and `GET /txs/{hash}` both return correct rows.
`GET /history/watermark` answers from the projection with a canonically resolved
`asOf` (slot 131,650,137, hash `966dc882…`). `GET /history/coverage` reports
`genesisCaptured: true` and all five artifact contracts.

Disk backpressure was validated separately on 2026-08-22: PAUSED at the hard limit,
RUNNING again below low-water, eight transitions, forward progress throughout.

## Three defects the live run found

**1. Removed config was rejected but not removed.** Phase 7a's guard fired on the
application's own packaged defaults, so no node could start. The dead configuration is
now gone from the shipped defaults and the devnet profile moved to the projection.

**2. The artifact guard refused every fresh archive.** Its irreproducible rule asks
"the archive does not hold this and can never produce it for epochs already passed" —
correct for a populated archive, meaningless for an empty one. Without the fix an
irreproducible artifact could never be adopted, because every archive starts empty.

**3. Artifact references collided, losing 4,887 reward rows.** The log showed
`reward epoch 83 (4887 rows)` then `reward epoch 83 (1 rows)`. Rewards stage several
parts at one boundary — calculator, MIR certificates, governance withdrawals — and
keying by `(block, dataset, epoch)` kept only the last. The surviving reference
declares its own row count, so the commit verified cleanly and the epoch looked
complete. The source generation is now part of the key.

A fourth, found the same way: the staged-job lookup took only the first binding for a
dataset, but bindings are keyed by dataset **and part**, so a job staged under one part
was invisible through another and the drain read that as missing evidence. The
fail-closed behaviour was right; the lookup was wrong.
