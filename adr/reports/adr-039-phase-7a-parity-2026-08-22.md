# ADR-039 Phase 7a parity gate — ACCEPTED (2026-08-22)

Route A2: two sequential, isolated preprod syncs, one primary archive sink each.

| | legacy leg | projection leg |
|---|---|---|
| directory | `adr039-oracle-legacy-20260822-1200` | `adr039-oracle-projection-20260822-1800` |
| writer | ADR-034 replay worker (projection off) | ADR-039 projection (replay worker off) |
| archived through | 5,081,500 (all four datasets) | 5,081,949 acknowledged |
| archive size | 11 GB | 6.3 GB |
| duration | ~4h20m at ~330 blk/s | ~70m at ~1,650 blk/s |
| drain failures | n/a | **0** |

Pinned coordinate **5,081,500** — the lesser of the two, so live chain advancement
cannot affect the comparison. 51 buckets of 100,000 blocks, spanning Byron main
blocks and EBBs from block 0.

## Result: zero differences

| dataset | table | rows | verdict |
|---|---|---|---|
| TRANSACTION | `transactions` | 6,610,099 | IDENTICAL |
| TRANSACTION | `chain_transaction` | 6,610,099 | IDENTICAL |
| UTXO_HISTORY | `transaction_inputs` | 27,464,431 | IDENTICAL |
| UTXO_HISTORY | `transaction_outputs` | 22,081,092 | IDENTICAL |
| UTXO_HISTORY | `transaction_output_assets` | 23,844,931 | IDENTICAL |
| UTXO_HISTORY | `transaction_output_amounts` | 45,926,023 | IDENTICAL |
| UTXO_HISTORY | `transaction_datums` | 1,142,087 | IDENTICAL |
| UTXO_HISTORY | `transaction_redeemers` | 6,405,625 | IDENTICAL |
| UTXO_HISTORY | `output_lifecycle` | 22,081,092 | IDENTICAL |
| UTXO_HISTORY | `address_utxo_amounts` | 45,926,023 | IDENTICAL |
| UTXO_HISTORY | `unspent_as_of_pin` (derived) | 4,379,079 | IDENTICAL |
| ACCOUNT_EVENT | `account_events` | 428,713 | IDENTICAL |
| ADDRESS_TRANSACTION | `address_transactions` | 39,038,285 | IDENTICAL |

**VERDICT: PARITY.** No mismatches, so nothing needed the issue-#74 carve-out; #74 was
never invoked.

## Method

Per-bucket digests are order-independent: `bit_xor` plus `sum` plus `count` over
per-row hashes. `bit_xor` alone would let a duplicated row cancel out, which is why
the count and sum travel with it. Row text is canonical — BLOBs hexed, NULLs
distinguished from empty.

`archive_job_id` is the only excluded column. It is derived from the producing job's
identity — a worker job UUID on one side, a deterministic projection identity on the
other — so it differs by construction and comparing it would report a difference on
every row while saying nothing about the facts. Every other column is compared.

`unspent_outputs` is **not** compared as a physical table. It is the current UTXO set,
and spent outputs are removed rather than marked, so an archive that ran to a later
tip has legitimately deleted rows the other still holds — the first run showed exactly
that, legacy 3 rows against projection 0 over the same range. "Unspent as of the pin"
is instead derived on both sides from `output_lifecycle`, which retains the spend
columns.

The comparison ran through the duckdb CLI rather than the Python module: the module
bundles ducklake 0.4 against these 1.0 catalogs and would have demanded
`AUTOMATIC_MIGRATION`, which rewrites the catalog — unacceptable on archives that are
evidence.

## What the oracle found before it passed

**Genesis was missing entirely.** The projection archive held no `genesis_byron` row
at all — its lowest block was 47 — while the legacy archive carried the full
30,000,000,000,000,000 lovelace Byron distribution. Only a differential oracle could
have found this: every other check the projection passes is about what blocks
produced, and genesis is produced by no block.

**Then the coordinate was wrong.** Once captured, the row differed from legacy in one
field: legacy attributed it to block 1, slot 2; the projection to block 0, slot 0.
Block 0 on a Byron network is the epoch boundary block, not a canonical chain block —
the codebase documents this in `firstCanonicalBlockNumber`. `GenesisBlockEvent` fires
for the first block *applied*, which is that EBB: the right trigger, the wrong
coordinate.

**And the provider had silently returned nothing.** The first fixed run reported
"0 rows, 0 lovelace" because the provider captured `genesisConfig` at construction,
before it was loaded. Empty is now loud: "not loaded yet" must never be recorded as
"none".

Three defects, one dataset, all invisible to every non-differential check.
