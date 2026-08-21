# ADR-039 as-of pointer index — measured overhead

**Date:** 2026-08-20
**Method:** read-only census of a real preprod chainstate synced to tip (block 5,074,810,
Conway), counting the authoritative `PREFIX_STAKE_EVENT` log. That log records every stake
registration and deregistration, and the derived index holds exactly one entry per
deregistration — so the count is exact, not modelled.

## Preprod, full sync to tip

```
stake events total      68,476
registrations           51,440
deregistrations         17,036     <- one index entry each
pointer registrations   51,440
```

| quantity | value |
|---|---|
| index entries | **17,036** |
| bytes per entry | **42** (1 prefix + 1 credType + 28 hash + 8 slot + 2 txIdx + 2 certIdx; empty value) |
| index logical bytes | **715,512 B = 0.68 MiB** |
| chainstate total | 31 GB |
| **index share of chainstate** | **0.002%** |
| extra RocksDB writes | 17,036 puts over 5,074,810 blocks = **one per ~298 blocks** |
| extra delta ops | one per put, in the batch that already writes the stake event |

## History-disabled regression

The index write is unconditional in core — it happens on every deregistration certificate
whether or not projection history is enabled. That is the "unconditional low-volume core
index" case, and its overhead is now measured rather than assumed:

```
3.4e-6 additional puts per block
```

An end-to-end A/B cannot meaningfully measure this. The isolated benchmark's noise floor is
16–49% (see `adr-039-measurements-2026-08-20.md`); an effect of one small put per 298 blocks
is many orders of magnitude below it. Claiming a measured pass from such a benchmark would
be false precision. The honest basis is the write count itself, which is exact.

The write is also inside the batch the deregistration path already commits, so it adds no
additional batch, no additional sync, and no additional lock acquisition — only 42 bytes to
an existing write.

## Mainnet extrapolation, explicitly not measured

Mainnet was **not** measured: the only mainnet chainstates on this machine belong to
existing deployments, which must not be touched. Scaling by activity rather than by block
count, mainnet plausibly holds low millions of deregistrations, i.e. order **100–250 MiB**
of index at 42 B/entry. That is an estimate and is labelled as one; it should be confirmed
on the first fresh mainnet sync, where `pointerIndexStatus()` reports entry count and bytes
directly.

Even at the top of that range the index is reclaimable in full once the Conway gates pass,
and it is included in the aggregate archive-retained disk accounting until then.

## Lookup cost

Resolution is one bounded `seek` into a credential-major prefix, returning at the first
entry past the registration coordinate. No scan of the slot-major event log, and no
iteration proportional to chain length. p50/p95/p99 under load are not yet measured; they
require the DuckLake sink wiring and are listed as outstanding.

## What is measured versus inferred

| claim | basis |
|---|---|
| 17,036 entries, 0.68 MiB on preprod-to-tip | **measured** (read-only census) |
| 42 bytes per entry | **exact** (key layout) |
| one extra put per ~298 blocks on preprod | **measured** |
| history-disabled overhead negligible | **inferred from the exact write count**, not from an end-to-end A/B, which cannot resolve it |
| mainnet 100–250 MiB | **estimated**, not measured |
| lookup p50/p95/p99 | **not yet measured** |
