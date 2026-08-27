# ADR-039 — projection memory invariant

**Date:** 2026-08-20 · **Status:** implemented for v1, stated precisely

## The invariant

Deliberately **not** described as a fully configurable hard heap bound, because it is not one.

| stage | bound | governed by |
|---|---|---|
| encoded outbox payload for a selected batch | bounded | `maxEncodedBytes` (configuration) |
| transaction decode and row emission | streaming, one fact at a time | not retained |
| derived rows across a batch | **not retained** | batch size no longer contributes |
| UTXO decode | at most **one block's** decoded fact graph | protocol block size |
| singleton envelope | one valid Cardano block and its deterministic projection expansion | protocol, plus an absolute safety guard |

Two consequences worth stating explicitly:

- **Batch size no longer contributes to decoded-fact or derived-row retention.** It does still
  govern the encoded side: the selected `ProjectionEnvelope` objects and their chunks occupy
  memory up to the configured encoded-byte bound.
- **A singleton envelope may exceed the normal batch estimate.** It remains bounded by one
  block, not by configuration. Beyond the absolute guard the node pauses visibly rather than
  risking an OOM.

## Why UTXO decode is not streamed

Row derivation resolves every output's address through a map built from the section's address
list, so outputs cannot be emitted before the addresses are known. Streaming it would mean
either duplicating derivation logic — losing the property that the differential oracle and the
projection path share one implementation — or restructuring `UtxoHistoryDataset`, which the
oracle also uses.

For v1 the one-envelope working set is accepted. It is already small and protocol-bounded, and
restructuring shared code to turn a small bounded singleton into a configurable bound is not a
good trade.

## What changed to make the rest hold

- `ProjectionChunkedInput` reads the chunk list as one stream, so `ProjectionChunking.join` is
  gone from the pipeline and encoded bytes are never duplicated. Verified against 64-byte
  chunks, which force facts to straddle boundaries ~1,000 times.
- `ProjectionFactCodec.streamTransactions` decodes and releases one fact at a time.
- The row source is **single-use**: a second `iterator()` throws. A partially consumed source
  iterated again would produce a short or duplicated pass that looks like valid data. After a
  failure the caller re-materialises from the still-unacknowledged outbox batch.

## Absolute singleton safety guard

`maxSingletonEncodedBytes` (64 MiB) and `maxSingletonRows` (4,000,000), configurable because a
protocol upgrade may legitimately raise block limits. Set far above any plausible block, so
they never fire in normal operation. Beyond them the accumulator returns `REJECTED_UNSAFE` and
the coordinator must pause visibly with diagnostics — never skip, which would lose projection
data, and never materialise, which risks taking the node down.

## Observability

`ProjectionBatchAccumulator.Stats` reports:

- largest encoded envelope;
- largest envelope row count;
- count of singleton envelopes exceeding normal batch limits;
- estimated singleton working-set high watermark;
- largest envelope refused by the absolute guard (non-zero means the node paused);
- the distribution of flush reasons.

All of these are surfaced on `/api/v1/status` under `projection`, alongside the batch regime
and both configured targets, so the observed distribution can be read next to the policy that
produced it.

## Evidence

- **~2.96 MB** largest retained working set, observed on a production preprod sync. This is an
  observation on one chain, **not a protocol maximum**, and must not be quoted as one.
- Structural streaming is covered by 8 tests: multi-chunk decode across ~1,000 boundaries,
  chunked-vs-joined equivalence, per-envelope retention across a 200-envelope batch, a dense
  datum/redeemer/multi-asset block, an oversized singleton, mid-iteration sink failure with
  deterministic retry, and single-use enforcement.
### Instrumented peak retention (measured 2026-08-20)

`ProjectionPeakRetentionMeasurementTest` measures retained heap directly rather than asserting
it by construction. The fixture is calibrated to the densest genuine preprod blocks — 300
outputs per envelope carrying 512-byte inline datums, multi-asset bundles and 512-byte
redeemers, plus 20 transactions — which is far above the average block.

Baseline is taken with the encoded envelopes already live, so the figure isolates the cost of
materialising rows from the cost of holding the batch:

```
two sections (transaction, utxo-history)
  5 envelopes ->   6,100 rows, peak retained 1,066 KiB, 4,488 B/row allocated
100 envelopes -> 122,000 rows, peak retained 1,111 KiB, 4,430 B/row allocated

all four sections (+ account-events, address-transaction)
  5 envelopes ->  28,700 rows, peak retained 2,436 KiB, 3,280 B/row allocated
100 envelopes -> 574,000 rows, peak retained 2,571 KiB, 3,265 B/row allocated
```

The four-section figures matter because the bound is a claim about the *design*, not about the
first two datasets. Twenty times the batch still costs **5.5% more retained heap** with every
shipped dataset enabled, and the address-transaction section — the heaviest per row — streams
one transaction at a time rather than one envelope, so it is finer-grained than the bound
requires.

**Twenty times the batch costs 4% more retained heap.** Allocation scales with total rows, as
it must — every row is still produced — but retention does not. A materialise-the-whole-batch
implementation would show roughly 20x here, so this distinguishes a real bound from a nominal
one.

The densest single envelope measures **1,220 rows and 1,125 KiB** with two sections, and
**5,740 rows and 6,467 KiB** with all four. That is the
figure the absolute singleton safety guard (64 MiB encoded / 4,000,000 rows) is sized against;
the guard sits roughly two orders of magnitude above anything observed.

Still not measured: the same instrumentation against a densest *mainnet* fixture. Preprod
density is the calibration source today.
