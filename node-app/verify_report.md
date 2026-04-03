# Yaci-Node Verification Report

**Date:** 2026-04-02 23:21:48
**Epochs:** 159 — 280
**Sources:** DBSync Yaci-Store

- DBSync: `localhost:5434/cexplorer`
- Yaci-Store: `localhost:5432/postgres.preprod`
- Parquet: `data/`

## Results

| Check | Source | Total | Matched | Mismatched | Status |
|-------|--------|------:|--------:|-----------:|--------|
| AdaPot | DBSync | 121 | 121 | 0 | PASS |
| AdaPot | Store | 118 | 118 | 0 | PASS |
| DRep individual | DBSync | 10153 | 10153 | 0 | PASS |
| DRep individual | Store | 10153 | 9333 | 0 | PASS |
| Epoch stake | DBSync | 121 | 121 | 0 | PASS |
| Epoch stake | Store | 118 | 118 | 0 | PASS |
| Proposals | DBSync | 86 | 86 | 0 | PASS |
| DBSync↔Store | 3-way | 114 | 2 | 112 | **FAIL** |

**Result: SOME CHECKS FAILED**
