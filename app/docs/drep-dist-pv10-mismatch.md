# DRep Distribution PV10 Mismatch Investigation

## Summary

After the V9 reverse index delegation cleanup fix, **all 29 PV9 epochs (508-536) match DBSync exactly** with zero mismatches. After PV10 transition (epoch 537+), mismatches begin at epoch 540 and grow to 15 unique DReps by epoch 616.

- All diffs are **negative** (Yano < DBSync) — Yano UNDER-counts
- Opposite direction from the V9 issue (which was Yano > DBSync)
- Growing from 1 DRep at epoch 540 to 15 DReps at epoch 616
- Total diff grew from -72M lovelace (epoch 540) to -4.07T lovelace (epoch 616)
- AdaPot PASSES through epoch 616 despite these diffs

## Epoch 617 Anomaly

Epoch 617 shows a sudden explosion from 14 to **702 mismatches** and an AdaPot FAIL (treasury=+25,506, reserves=-31,519,756). This was caused by **two concurrent yano-node processes** (PIDs 89740, 97887) running simultaneously and corrupting RocksDB state. The epoch 617 data should be discarded — it is not a code bug.

## Mismatch Progression by Epoch (clean data: 540-616)

| Epoch | Mismatched DReps | Total Diff (lovelace) | Notes |
|-------|-----------------|----------------------|-------|
| 540 | 1 | -71,738,440 | First mismatch (008e) |
| 541 | 2 | -392,846,307 | +b6f4 appears |
| 545 | 3 | -186,616,391 | +bc5c appears (tiny, disappears at 548) |
| 548 | 2 | -169,257,495 | bc5c resolved |
| 551 | 3 | -663,627,035,320 | +b102 (+67cc appears at 551, disappears at 572) |
| 558 | 4 | -663,719,242,880 | +8b75 appears (tiny at first, jumps at 565) |
| 562 | 5 | -2,370,042,871,505 | +1c12 appears (MISSING_YANO, ~1.7T) |
| 563 | 6 | -2,370,610,332,631 | +e243 appears (~21M) |
| 565 | 7 | -2,956,655,913,230 | +8f2b appears; 8b75 jumps from ~1K to ~565B |
| 570 | 8 | -2,930,640,691,287 | +86c9 appears (~3B) |
| 571 | 9 | -2,939,102,061,130 | +d2e0 appears (~410M) |
| 581 | 10 | -2,754,032,478,799 | +5521 appears (~5.9B); +aff4 appears (~36M) |
| 582 | 12 | -2,758,995,089,596 | +0655 appears (~107M); +17ee appears (~532M) |
| 592 | 12 | -3,655,949,264,106 | 008e jumps from ~70M to ~861B |
| 595 | 12 | -2,157,427,653,728 | 1c12 drops from ~1.5T to ~9.5B |
| 597 | 12 | -2,176,169,351,695 | +812a appears (~20.7B) |
| 606 | 12 | -2,168,797,929,450 | +c50b appears (~511M) |
| 608 | 12 | -3,550,067,788,938 | 1c12 jumps back from ~10B to ~1.39T |
| 612 | 14 | -4,068,877,576,923 | +5f4d appears (~68M); +a530 appears (~184M); 8b75 jumps from ~574B to ~879B |
| 614 | 15 | -4,071,300,759,888 | +4f28 appears (~8.8M) |
| 616 | 14 | -4,072,977,887,182 | c50b disappears |

## Detailed Per-DRep Analysis

### 1. DRep 1c12c18fff75038f1181d6dcca10c5255df430301c0a264a9500381e — MISSING_YANO

**First mismatch**: epoch 562. **Type**: Entirely missing from Yano's DRep distribution. **Largest contributor.**

This DRep exists in DBSync but Yano never includes it in its distribution. The amount fluctuates significantly:

| Period | DBSync Amount | Notes |
|--------|--------------|-------|
| 562-572 | ~1.70T-1.71T | Large, stable |
| 573-577 | ~1.70T | Slight drop |
| 578-594 | ~1.50T | Drop of ~200B at epoch 578 |
| 595-607 | ~9.5B-10.0B | **Massive drop** to only ~10B |
| 608-609 | ~1.39T | Jumps back up |
| 610-616 | ~1.60T | Grows again |

**Pattern**: This DRep is not a ghost — it has substantial delegated stake in DBSync. The fluctuating amount suggests delegators coming and going. Yano never sees it at all.

**Possible cause**: DRep registered in a way that Yano's DRep tracking misses entirely. Check if this DRep was registered via a script credential or if it has unusual registration/deregistration patterns that cause Yano to treat it as deregistered (tombstoned).

### 2. DRep 008e918639050ec8b708e5d8ff5224595098a28f0fc6671c66e292ab — DIFF, two phases

**First mismatch**: epoch 540. Two distinct phases:

| Phase | Epochs | Diff | Notes |
|-------|--------|------|-------|
| Phase 1 | 540-550 | -70M to -70M | Small, stable ~70M diff (~70 ADA) |
| Gap | 551-591 | 0 | **Matches perfectly for 41 epochs!** |
| Phase 2 | 592-616 | -861B to -868B | Massive jump, ~862B (~862K ADA) |

**Phase 1 detail (epochs 540-550)**:
```
540: Yano 71,971,568,924,981 vs DBSync 71,971,640,663,421 → diff -71,738,440
550: Yano 80,854,805,757,689 vs DBSync 80,854,875,686,872 → diff -69,929,183
```
Stable ~70M diff with slow drift (~1.8K/epoch from reward accumulation).

**Phase 2 detail (epochs 592-616)**:
```
592: Yano 92,980,038,150,382 vs DBSync 93,841,358,160,696 → diff -861,320,010,314
616: Yano 96,948,981,187,347 vs DBSync 97,817,160,268,829 → diff -868,179,081,482
```
~861B diff growing by ~250M/epoch.

**Pattern**: The gap between epochs 550-592 means the original ~70M issue was resolved, then a much larger issue appeared at epoch 592. Something happened around epoch 591-592 for this DRep's delegators.

### 3. DRep b102197ee2affaebd50fcb8ca69fb4fa9eba931f4cc219f18db6d7e6 — DIFF, fluctuating

**First mismatch**: epoch 551. Consistently present through epoch 616.

| Period | Diff Range | Notes |
|--------|-----------|-------|
| 551-565 | -648B to -649B | Stable ~648B diff |
| 566-567 | -641B | Drops slightly |
| 568-569 | -647B to -646B | Back up |
| 570 | -630B | Another drop |
| 571-581 | -629B to -637B | Fluctuating |
| 582-589 | -627B to -629B | |
| 590-591 | -536B | Significant drop |
| 592-593 | -660B | Jump up |
| 594 | -560B | Drop again |
| 595-603 | -661B to -663B | Stable ~662B |
| 604-616 | -650B to -652B | Stable ~651B |

**Pattern**: Diff fluctuates between ~536B and ~663B. The changes correlate with specific epoch boundaries, suggesting delegators whose stake appears/disappears from this DRep at those epochs. The magnitude (~650B = ~650K ADA) suggests 1-2 specific delegators.

### 4. DRep 8b75035882d4165bea8000c4d3f2c123ae33c1d92a751a78135a2402 — DIFF, three phases

**First mismatch**: epoch 558. Three distinct phases:

| Phase | Epochs | Diff | Notes |
|-------|--------|------|-------|
| Phase 1 | 558-564 | -273 to -942 | Tiny (sub-1K lovelace) |
| Phase 2 | 565-577 | -551B to -565B | **Massive jump** to ~552B at epoch 565 |
| Phase 3 | 578-611 | -569B to -574B | Grows by ~18B at epoch 578 |
| Phase 4 | 612-616 | -879B to -880B | **Another jump** +306B at epoch 612 |

**Key transitions**:
- Epoch 565: +565B jump (a specific delegator's entire stake)
- Epoch 578: +17B jump
- Epoch 612: +306B jump

### 5. DRep 67ccad9c9b93f3c41d0573e67d1471dae74b6b5d65a58ca9ca00c46d — DIFF, temporary

**First mismatch**: epoch 551. **Disappears after epoch 571.**

```
551: diff -15,536,226,919 (~15.5B)
566: diff  -4,013,963,176 (drops to ~4B)
571: diff -13,774,303,474 (last mismatch)
572+: matches perfectly
```

**Pattern**: Self-resolved after ~20 epochs. Likely a delegator event that eventually corrected itself.

### 6. DRep b6f4547ad049d7443ee5695761e1aa5e446cfccf72c7ed0d8ad8edfa — DIFF, small & persistent

**First mismatch**: epoch 541. Present through epoch 616 (76 consecutive epochs).

| Period | Diff Range | Notes |
|--------|-----------|-------|
| 541-545 | -110M to -321M | Larger, fluctuating |
| 546-550 | -58M to -101M | Smaller |
| 551-574 | -37M to -705M | Very erratic |
| 575-616 | -59M to -111M | Settling to ~60M |

**Pattern**: Long-lived but relatively small diff (~60M = ~60 ADA by epoch 616). Slowly drifting upward at ~300 lovelace/epoch.

### 7. DRep 8f2b45094df19c26eeea7d19a7293c6203adc09ddc7bfa7a7db3f372 — DIFF, growing

**First mismatch**: epoch 565. Present through epoch 616.

```
565: diff -20,200,712,939 (~20B = ~20K ADA)
578: diff -30,157,247,302 (jumps at 578)
580: diff -40,048,356,756 (jumps again at 580)
616: diff -40,648,570,218 (stable ~40.6B)
```

**Pattern**: Two step increases at epochs 578 and 580, then slow growth.

### 8. DRep e243608ba1c93c5dfcb469f8bd0c9ec4ef0b1ad8a2b240291fdf74e4 — DIFF, tiny

**First mismatch**: epoch 563. Present through epoch 616.

```
563: Yano 6,916,190 vs DBSync 28,023,362 → diff -21,107,172
616: Yano 7,003,742 vs DBSync 28,405,368 → diff -21,401,626
```

**Pattern**: Yano sees ~7M lovelace, DBSync sees ~28M. Stable ~21M diff (~21 ADA). Very small.

### 9. DRep d2e0ed6a4b1b3233f071244420293f7eeb077ebf758e74b3876d4552 — DIFF, stable

**First mismatch**: epoch 571. Present through epoch 616.

```
571: diff -409,519,115 (~410M = ~410 ADA)
616: diff -414,836,940 (~415M)
```

**Pattern**: Very stable ~410-415M diff. Slow growth (~120K/epoch). Consistent with one delegator's stake (~410M) missing from Yano's calculation plus reward accumulation.

### 10. DRep 55215f98aa7d9e289d215a55f62d258c6c3a71ab847b76de9ddbe661 — DIFF, growing

**First mismatch**: epoch 581. Present through epoch 616.

```
581: diff -5,891,742,354 (~5.9B)
598: diff -7,364,534,432 (~7.4B, peak)
616: diff -7,220,547,978 (~7.2B)
```

**Pattern**: Step increase from ~5.9B to ~7.3B around epoch 582, then stable. One delegator's stake (~7B = ~7K ADA).

### 11. DRep aff45cba7bc52fae256de95178476ea96ede79c58e4bdfdc46c582f6 — DIFF, erratic

**First mismatch**: epoch 581. Present through epoch 616.

```
581: diff    -35,870,755
582: diff -1,952,759,932 (spike)
584: diff    -34,369,827 (back to small)
602: diff -3,554,106,625 (spike)
608: diff    -35,166,128 (back to small)
616: diff    -31,949,856
```

**Pattern**: Mostly small (~30-60M) but with occasional large spikes (up to 3.5B). Very erratic — suggests a delegator that repeatedly delegates/undelegates or has stake that Yano intermittently sees.

### 12. DRep 0655f3a1c76788d839212adc459b188b84e680f30ae944c593fa18ae — DIFF, temporary

**First mismatch**: epoch 582. **Disappears after epoch 591.**

```
582: diff -106,866,266 (~107M)
591: diff  -91,992,046 (~92M)
592+: matches perfectly
```

**Pattern**: Small and self-resolved after 10 epochs.

### 13. DRep 17eeeba0933a294254a21e46e186fd80467ae663c0dd7dbcd9fb3f86 — DIFF, temporary

**First mismatch**: epoch 582. **Disappears after epoch 599.**

```
582: diff -532,076,206 (~532M = ~532 ADA)
599: diff -499,207,705 (~499M, last mismatch)
600+: matches perfectly
```

**Pattern**: Moderate (~500M) and self-resolved after 18 epochs.

### 14. DRep 86c986afcf6a287eb706df965412e29f491bdb153dc531d210869a3f — DIFF, temporary

**First mismatch**: epoch 570. **Disappears after epoch 596.**

```
570: diff -3,038,103,863 (~3B)
586: diff -4,459,558,438 (step increase at 586)
596: diff -4,093,266,431 (last mismatch)
597+: matches perfectly
```

**Pattern**: Step increase at epoch 586, then self-resolved.

### 15. DRep 812a62c49f2e340a172eddcc1b737694fe23aef57272b6b647f88646 — DIFF, stable

**First mismatch**: epoch 597. Present through epoch 616.

```
597: diff -20,701,883,376 (~20.7B = ~20.7K ADA)
616: diff -20,823,006,620 (~20.8B)
```

**Pattern**: Very stable ~20.7-20.8B diff, growing at ~6M/epoch. Consistent with one delegator's stake missing.

### Late arrivals (epochs 606-614)

| DRep | First | Last | Diff | Pattern |
|------|-------|------|------|---------|
| c50b9b98 | 606 | 614 | -511M to -1.07B | Fluctuating, disappears at 615 |
| 5f4dc306 | 612 | 616 | -67.8M | Perfectly stable (exact same diff every epoch) |
| a5302bbb | 612 | 616 | -184M | Stable, slow growth |
| 4f281153 | 614 | 616 | -8.8M | Small |

## Common Characteristics

1. **All negative** — Yano consistently UNDER-counts (opposite of V9 issue)
2. **PV10 only** — zero mismatches in PV9 (epochs 508-536) or PV10 transition epoch 537-539
3. **One-time jumps** — new mismatches appear as sudden diffs (not gradual growth), suggesting specific delegator events
4. **Stable after appearance** — once a diff appears, it stays roughly constant with slow reward drift (~0.03%/epoch)
5. **Some self-resolve** — DReps 67ccad, 0655f3, 17eeeb, 86c986 resolved themselves after 10-27 epochs
6. **Some have multiple phases** — DReps 008e91, 8b7503 show distinct jumps at later epochs (new delegator events)
7. **AdaPot still PASSES** — these diffs don't affect reward calculation or treasury tracking through epoch 616

## Categorization

### Category A: MISSING_YANO (DRep not in Yano at all)
- **1c12c18f**: ~1.6T at epoch 616. DRep exists in DBSync but Yano never includes it.

### Category B: Large persistent diff (>100B)
- **008e9186**: ~868B at epoch 616 (was ~70M before epoch 592, then jumped)
- **b102197e**: ~652B at epoch 616 (fluctuating 536B-663B)
- **8b750358**: ~880B at epoch 616 (jumped twice: at 565 and 612)

### Category C: Medium persistent diff (1B-100B)
- **8f2b4509**: ~40.6B at epoch 616
- **812a62c4**: ~20.8B at epoch 616
- **55215f98**: ~7.2B at epoch 616

### Category D: Small persistent diff (<1B)
- **d2e0ed6a**: ~415M at epoch 616
- **b6f4547a**: ~61M at epoch 616
- **e2436088**: ~21M at epoch 616
- **aff45cba**: ~32M at epoch 616 (erratic)
- **5f4dc306**: ~68M at epoch 616
- **a530277b**: ~184M at epoch 616
- **4f281153**: ~8.8M at epoch 616

### Category E: Self-resolved
- **67ccad9c**: resolved at epoch 572 (was ~15B)
- **0655f3a1**: resolved at epoch 592 (was ~107M)
- **17eeeba0**: resolved at epoch 600 (was ~500M)
- **86c986af**: resolved at epoch 597 (was ~4B)
- **bc5c2f2a**: resolved at epoch 548 (was ~7M)
- **c50b9b98**: resolved at epoch 615 (was ~1B)

## Impact Assessment

At epoch 616:
- Total credential DRep diff: -4.07T lovelace
- Total DRep distribution: ~14.6Q lovelace
- **Impact: 0.028%** of total distribution
- Yano UNDER-counts, which is the conservative/safe direction for ratification (proposals may ratify slightly later, never earlier)

## Possible Root Causes

The one-time jump pattern (not gradual growth) suggests specific delegator events that Yano handles differently:

1. **Delegation state divergence in PV10**: After V9→V10 transition, some residual UMap state from V9 may persist in Haskell's implementation but not in Yano's. When a delegator redelegates or a DRep deregisters/re-registers in PV10, Haskell's UMap cleanup may produce different delegation mappings than Yano's.

2. **DRep 1c12c18f (MISSING_YANO)**: Most likely a script DRep or a DRep with unusual registration pattern that Yano's tracking misses entirely. Needs investigation in governance block processor to see if/when this DRep was registered and what type it is.

3. **Phase transitions (008e9186, 8b750358)**: DReps that show sudden jumps at specific epochs suggest specific delegation transactions that Yano processes differently. The self-resolution of some DReps (Category E) confirms this is about specific state transitions, not a systematic error.

4. **Reward accumulation on missing stake**: The slow growth component (~0.03%/epoch) is consistent with rewards being distributed on stake that Yano doesn't track.

## Next Steps for Debugging

1. **DRep 1c12c18f** (highest priority — 1.6T, MISSING_YANO):
   - Check registration type (key vs script) in Yaci Store
   - Check if tombstoned in Yano's governance state
   - Trace delegation events around epoch 562

2. **DRep 008e9186** (second priority — 868B, phase jump at 592):
   - Trace what delegation events occurred at epoch 591-592 boundary
   - Check if a specific delegator's stake (~861B) was added that Yano misses

3. **DRep 8b750358** (third priority — 880B, three phase jumps):
   - Trace delegation events at epochs 565, 578, 612
   - Each jump is a distinct delegator

4. For self-resolved DReps: check if the resolution correlates with DRep deregistration/re-registration

## Verification Data

- Yano parquet exports: `app/data/epoch={N}/drep_dist.parquet`
- DBSync reference: `/Users/satya/work/dbsync-parquet/dbsync-mainnet-parquet/drep_distr_from504_with_hash.parquet`
- Comparison script used: inline python comparing by `drep_hash` column, excluding virtual DReps (drep_always_*)

## Related

- V9 delegation cleanup fix: all 29 PV9 epochs (508-536) match exactly (FIXED)
- Previous V9 investigation: `drep-dist-mismatch-investigation.md` (18 DReps, +1.2T — FIXED)
- AdaPot: PASSED epochs 508-616 (109 consecutive). FAILED epoch 617 (dual-process corruption, not a code bug)
- Epoch 575-576: PASSED. 27 proposals ratified at 574→575, 2 at 575→576 (correct timing)
