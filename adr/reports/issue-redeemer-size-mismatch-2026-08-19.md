# Issue — `BlockSerializer: redeemer does not have the same size`

Date: 2026-08-19
Severity: **data completeness — archived redeemer coverage is silently incomplete**
Origin: **yaci-core 0.4.3**, not Yano
Relationship to ADR-038 Phase 2c: **none — predates it.** Tracked separately and
must not be fixed as part of Phase 2c.

---

## 1. Summary

For certain mainnet blocks, yaci-core's `BlockSerializer` finds that the number of
raw redeemer byte-chunks does not match the number of parsed `Redeemer` objects.
It logs an `ERROR` and **skips the loop that attaches raw CBOR to each redeemer**.

Yano's `YaciUtxoHistoryDecoder` then **silently drops** every redeemer whose CBOR
is null:

```java
if (redeemer == null || redeemer.getTag() == null || redeemer.getCbor() == null
        || redeemer.getCbor().isBlank() || ...) continue;
```

**Net effect: for affected blocks, redeemer rows are omitted from the archive with
no error, no coverage gap and no digest mismatch.** Coverage records still claim
the range is complete, because from the archive's point of view the derivation
produced no redeemer rows for those transactions.

This is exactly the "silently incomplete history" ADR-034 forbids, arriving
through a dependency rather than through Yano's own logic.

## 2. Evidence

### Counts

| log | occurrences |
|---|---:|
| pre-Phase-2c window | **70** |
| Phase 2c measurement window | **18** |
| current run (partial) | 7 |
| **distinct occurrences across retained logs** | **95** |

The equivalent `datum does not have the same size` message occurs **0** times.

### Affected block range

Occurrences span blocks **11,139,773 – 13,431,510**. Sample of affected blocks:

```
12089013  12094866  12137962  12319353  12528467
12528470  12684680  12684693  12693040
```

### Originating dataset and thread

Emitted on `yano-archive-projection-*` threads, i.e. during block decoding for
whichever projection reaches the block first. It is **not** emitted by the
`yano-archive-utxo-decode-*` prefetch threads, which is one reason it is unrelated
to Phase 2c.

Note the sequencing: redeemers are a `UTXO_HISTORY` table, and `utxo_history`
coverage currently trails at ~9.4M while these blocks are at 11.1M–13.4M. The
errors seen so far come from the faster datasets decoding those blocks. **The data
loss will materialise when `utxo_history` reaches this range**, because it decodes
the same bodies through the same yaci-core path.

## 3. Decoder behaviour, determined from bytecode

`BlockSerializer.handleWitnessDatumRedeemer` (yaci-core 0.4.3), disassembled:

```
709: aload 12                     // raw redeemer byte-chunk list
711: List.size()
716: aload 9                      // parsed Redeemer list
718: List.size()
723: if_icmpeq  743               // sizes match -> run the enrichment loop
726: getstatic  log
729: ldc        "block: {} redeemer does not have the same size"
735: Logger.error(...)
740: goto       967               // sizes differ -> SKIP the enrichment loop
743: (loop) pair each parsed Redeemer with its raw bytes (Tuple._2)
```

Answering the specific questions asked:

| question | answer |
|---|---|
| does it drop a redeemer? | **Indirectly yes.** yaci keeps the parsed `Redeemer` objects but never attaches their CBOR; Yano then skips any redeemer with null CBOR, so the row is dropped |
| does it skip a transaction? | **No.** The transaction and all its other facts — outputs, inputs, assets, datums — are archived normally |
| does it retry the block? | **No.** No retry, no exception, no abort |
| does it continue with partial facts? | **Yes.** The block commits with an incomplete redeemer set |
| can archived redeemer coverage be complete despite the error? | **No.** For affected blocks the `transaction_redeemers` rows are missing while coverage reports the range complete |

The `transaction_redeemers` schema declares `redeemer_cbor` as **non-nullable**
(`b("redeemer_cbor")`), so archiving a redeemer without its CBOR is not possible
without a schema change — dropping the row is the only behaviour the current
schema permits.

## 4. Why this is not attributable to Phase 2c

- 70 occurrences appear in the **pre**-Phase-2c log, before ordered prefetch existed.
- The message originates in yaci-core's serializer, not in Yano code.
- It is emitted by projection threads, not by the prefetch decode threads.
- Phase 2c changes *when* blocks are decoded, never *how*; decode is a pure
  function of its bytes, proven by `RealBlockDecoderConcurrencyTest`.

## 5. Open questions before a fix

1. **Which encodings trigger it.** The likely cause is Conway map-encoded
   redeemers versus the pre-Conway list encoding, where the raw-chunk splitter and
   the parser disagree on element count. Needs confirmation against an affected
   block body.
2. **How many redeemers are lost per occurrence** — the whole witness set for that
   transaction, or a subset.
3. **Whether already-archived ranges are affected.** `utxo_history` has not yet
   reached 11.1M+, so the archive may not yet contain any loss. This should be
   confirmed by querying `transaction_redeemers` for the affected blocks once
   coverage passes them.
4. **Remediation path.** Options: fix or patch yaci-core's chunk splitting; derive
   redeemer CBOR independently in Yano; or fail closed rather than dropping rows,
   so incomplete redeemer data becomes a visible archive failure instead of a
   silent omission.

## 6. Recommended handling

- **Do not classify as harmless.** It produces silently incomplete history in a
  dataset that reports itself complete.
- **Do not bundle into ADR-038 Phase 2c.** Unrelated cause, unrelated code path.
- Treat as an upstream yaci-core defect with a Yano-side decision about whether to
  fail closed instead of skipping.
- Highest-value immediate step: once `utxo_history` passes block 11,139,773,
  query `transaction_redeemers` for the affected blocks and confirm empirically
  whether rows are missing.
