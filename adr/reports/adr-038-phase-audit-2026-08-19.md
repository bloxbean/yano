# ADR-038 — Remaining Phase Audit

Date: 2026-08-19
Context: after Phase 0, Phase 2, Phase 2b, the locator-generation fix (all
mainnet-validated) and Phase 2c (prototype complete, not deployed).

Audit only. **No phase below was implemented.**

---

## Current throughput position

Measured over equal 2-hour mainnet windows after the locator fix:

| dataset | blocks/s | remaining at 2026-08-19 |
|---|---:|---|
| `transaction` | 85.64 | ~3.3M blocks |
| `account_event` | 85.62 | ~3.3M blocks |
| `address_transaction` | 56.46 | ~3.9M blocks |
| **`utxo_history` (slowest)** | **31.57** | ~5.7M blocks |

At current rates full history is roughly **2 days** away. That materially changes
the value of further throughput work: the remaining phases are now mostly about
architecture, durability and operability rather than backfill speed.

## Audit table

| phase | status | measured motivation | prerequisites | unresolved correctness decisions | expected benefit | implementation risk | recommendation |
|---|---|---|---|---|---|---|---|
| **1 — configuration and profile** | Not started | **None.** Measurement showed DuckDB thread/memory settings have no effect on the append path (1t/128MB matched 8t/8GB), and the production UTXO session already runs under the production DuckDB config | New evidence that any DuckDB setting binds a real stage | None | Unknown; previously claimed 3–8x, now withdrawn | Low technically, but changing global defaults for smaller hosts is a product risk | **Defer.** Do not change defaults without evidence. If revisited, ship as an opt-in profile only |
| **3 — unified block pipeline, group-atomic transitions** | **Blocked on C1–C5** | Architecture: one cursor per lifecycle track, single decode, no cross-dataset contention, aligned tip coverage. **Not** per-commit cost (~75 ms fixed, ~0.1% of a commit) | C1 multi-job backend session; C2 group-atomic hot write; C3 group-atomic invalidation; C4 persisted group identity + migration; C5 group-atomic promotion cleanup — all on both backends | Whether group atomicity can preserve pinned-generation read semantics; whether tip alignment and available coverage stay correctly distinguished; five crash boundaries | Simplification and contention removal. **Would not raise the group rate to the fastest dataset** — combined rows/batch means the group lands near today's slowest | **High.** Largest change in the ADR; touches every archive contract | **Redesign gate, then decide.** Keep blocked until C1–C5 are explicitly approved. Its case is design quality, not throughput |
| **4 — retry without repeated derivation** | Not started, deferred | **None now.** Writer waits and discarded batches are both **zero** post-locator-fix; the 300 s stuck pauses that motivated it no longer occur | Contention returning | Bounded retry and memory accounting for retained rows | Zero while waits are zero | Low | **Defer.** Revisit only if stuck pauses reappear |
| **5 — transaction locator lifecycle** | Not implemented; **partially superseded** | The catastrophic rebuilds it targeted were removed by the generation fix. Residual: `transaction` spends ~2.65 s/commit (96% of its session) inserting ~25k locator entries, and startup rebuild risk remains latent | Atomic DuckLake mutation journal written in the same transaction as each mutation; per-consumer identity and offset for every locator including the active one; low-watermark cleanup; rebuild lease protecting the target generation | The archive/locator seam: DuckLake commits before the SQLite locator update and the replay path returns before `updateTransactionLocator`, so a failed locator update is never reconciled by replay. Currently degrades to a safe stale-negative, but is unrepaired | Removes the last shared-writer cost and makes tx-status durably safe | Medium–high; new journal is on the hot commit path | **Redesign, reduced scope.** The unreconciled seam is a genuine correctness gap and the strongest remaining reason to do this. Full shadow-rebuild machinery is no longer urgent |
| **6 — commit granularity** | Not implemented | **None.** Per-commit fixed cost measured ~75 ms; commits now take ~0.5 s for 160k rows. Larger batches would amortise almost nothing | Evidence that commit granularity binds | Peak memory bound if batches grow | Negligible on current evidence | Medium (memory) | **Reject for now.** Re-open only if per-commit fixed cost is shown to bind |
| **7 — concurrent per-table writers** | Contingency only | **None.** Writer waits are zero; after Phase 2c the constraint is decode, not the writer | Phase 3, plus Phase 5's locator generation sequencing | Pinned-generation read coherence under concurrent commits | Unclear; likely small now | High | **Reject unless measurement changes.** Do not start |

## Recommended order if work continues

1. **Validate Phase 2c on mainnet** (plan in `adr-038-phase2c-prototype-2026-08-19.md`).
   It is built, gated and reversible by configuration, and it is the only
   outstanding item with a measured throughput case.
2. **Phase 5, reduced to the correctness seam.** Make a failed locator update
   reconcilable — through replay or an atomic journal — and decide whether
   `/txs/{hash}/status` is safe to expose. This is the one unrepaired correctness
   gap in the subsystem.
3. **Phase 3, only after C1–C5 are approved as a design.** Judge it as an
   architecture investment; do not fund it with a throughput argument.
4. Leave Phases 1, 4, 6 and 7 closed pending new evidence.

## Cross-cutting items worth tracking

- **`~1.0 s quantisation` in the commit stage p99** across all four datasets
  (1.013–1.025 s max). Unexplained; not investigated.
- **Locator growth**: 5.2 GB → 7.7 GB during a two-hour window, the
  fastest-growing artefact on disk.
- **Collision-detection coverage under Phase 2c** becomes interleaving-dependent
  (bounded, best-effort, durable validation behind it). Recorded in Amendment 4.
