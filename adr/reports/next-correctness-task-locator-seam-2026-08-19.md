# Recommendation — next correctness task: the locator replay/reconciliation seam

Date: 2026-08-19
Status: **recommendation only.** No implementation started, per the standing
instruction not to begin another ADR phase automatically.

---

## Recommendation

Take the **locator lifecycle** as the next task, in the reduced scope below:
make the archive→locator seam self-reconciling and observable. Do **not** take
full Phase 5 (the durable generation-ordered delta journal) — that was scoped
against a mechanism that has since been fixed.

The locator now carries **two independent defects**, and they interact:

| | defect | status |
|---|---|---|
| **A** | The archive→locator write is split across two stores with the commit in the middle, and **idempotent replay never repairs a gap** | latent, permanent, silent |
| **B** | The locator write has become the **production throughput bottleneck** — 27–77 s per `TRANSACTION` commit, held inside the single-permit writer gate | **active on mainnet now** |

Defect B is documented separately in
[`finding-locator-write-cliff-2026-08-19.md`](finding-locator-write-cliff-2026-08-19.md).
It is a *different* mechanism from the one ADR-038 Amendment 3 fixed — that was a
read-triggered full rebuild, this is a write-side index-size cliff, and the
rebuild counter confirms zero rebuilds are occurring.

**They must be fixed together, in this order.** The obvious mitigation for B is to
move the locator write out of the DuckDB gate, since it needs no DuckDB
resources — but that widens exactly the crash window whose gap A never repairs.
Shipping B alone would make A strictly more likely to fire.

This is the right next task because it is now both the only known *unrepairable*
defect in the archive and the active production bottleneck. Everything else
outstanding is observability (ADR-034 review finding D), an upstream dependency
bug (the redeemer issue), or throughput phases the campaign has deliberately
closed.

## The defect, verified in source

### 1. The write is split across two stores, with the commit in the middle

`DuckLakeWriteSession.commit()`:

```java
188:  try (Statement sql = connection.createStatement()) {
189:      sql.execute("COMMIT");                       // DuckLake durable, generation advances
190:  }
...
201:  backend.updateTransactionLocator(connection, actualGeneration, transactionEntries);
```

The archive is durable at line 189. The locator — a **separate SQLite database** —
is updated at line 201. A crash, or any throw, between those two lines leaves the
archive holding transactions the locator has no entry for.

### 2. Idempotent replay does not repair it

```java
157:  if (replayReceipt != null) {
158:      String replayDigest = ...;
159:      if (!replayReceipt.rowCounts().equals(rowCounts) || !...orderedDigest().equals(replayDigest)) {
162:          throw new ArchiveStoreException("committed job retry has different rows: " + job.jobId());
163:      }
164:      committed = true;
165:      close();
166:      return replayReceipt;                        // <-- returns before line 201
167:  }
```

Replaying the same job verifies row counts and digest, then **returns at line
166 — before ever reaching `updateTransactionLocator`**. The mechanism that
exists specifically to make an interrupted commit whole is the one path that
cannot repair this. The gap is permanent.

### 3. The gap is absorbed silently

`DuckLakeTransactionLocator.advance()`:

```java
120:  if (current != generation - 1) {
121:      LOG.log(System.Logger.Level.DEBUG,
122:              "Locator advanced across generation gap {0}->{1} with {2} entries (no rebuild)", ...);
124:  }
```

Forward-gap tolerance is correct and deliberate — it is what fixed the
catastrophic rebuild loop. But the gap is logged at **DEBUG**, which production
does not enable, and **no counter tracks it**. `fullRebuilds` counts rebuilds;
nothing counts skipped entries. So the deployment cannot tell you whether its
locator is complete.

### 4. The consequence is degradation, not a wrong answer

This matters for scoping, so it is stated precisely.
`DuckLakeHistoryArchiveBackend.findTransaction()`:

```java
355:  var block = transactionLocator.block(read.connection(), read.generation(), txHash);
356:  var query = new ArchiveQuery(
357:      block.isPresent() ? new BlockRange(block.getAsLong(), block.getAsLong())
358:                        : new BlockRange(0, Long.MAX_VALUE), Map.of("tx_hash", txHash), ...);
360:  Optional<ArchiveRecord> located = repositories...query(session, query)...findFirst();
362:  if (located.isPresent() || block.isEmpty()) return located;
```

- **Stale positive** (locator points at the wrong block): `located` is empty,
  `block` is present → falls through to the authoritative full-range fallback.
  Correct.
- **Locator gap** (`block.isEmpty()`): the query already used
  `BlockRange(0, Long.MAX_VALUE)`, so `located` *is* the authoritative answer.
  Correct.

So a locator gap **never produces a wrong answer**. What it produces is a
**silent, permanent, unbounded performance cliff**: every lookup for an affected
transaction degrades from a point lookup to a full scan over a mainnet-scale
`chain_transaction`. That is the same multi-minute stall the ADR-034 review
flagged as L2, arriving by a different route — and accumulating with every
interrupted commit, forever.

### 5. There is no repair path, and one is already half-written

`DuckLakeTransactionLocator.removeJobs(generation, jobs)` — the incremental
invalidation path — **exists and has no callers**. The only recovery today is
`rebuild()`, a full unbounded scan of `chain_transaction`.

## Why now, and why reduced

| | |
|---|---|
| **Why now** | Defect A is the last known defect that is both unrepairable and silent; defect B is costing production throughput today (~8 days to catch-up at the current rate versus ~19 hours before the cliff). Neither can be fixed safely without the other |
| **Why not full Phase 5** | The generation-ordered delta journal was scoped when a *read-triggered rebuild* consumed 99.9% of write-session time. That mechanism was fixed in Amendment 3 and is confirmed dormant (zero rebuilds). The journal is a large, high-risk change aimed at a problem that no longer exists in that form |
| **Why not ADR-034 finding D first** | D is observability for failures that *are* contained. This is an unrepairable state divergence. Divergence outranks visibility — though the counter below is a down-payment on D |
| **Scale context** | Locator is **20.62 GB** and the `transaction` dataset is **97.7%** complete, so it is near its final size. Repair work is cheapest to design now, before it is load-bearing for wallet traffic |

## Proposed reduced scope

Four increments, smallest first. Each is independently useful and independently
revertible.

1. **Count and expose locator gaps.** Promote the DEBUG line to a counter
   (`skippedGenerations`, `skippedEntries`) beside the existing `fullRebuilds`,
   and surface all three in the `/status` history payload. Turns "is my locator
   complete?" from unanswerable into a number. Smallest possible step, no
   behaviour change.
2. **Reconcile on replay.** Move `updateTransactionLocator` so the replay path
   also performs it — the replay session already knows `transactionEntries` and
   has verified the digest matches, so re-applying is safe and idempotent
   (`ON CONFLICT ... DO UPDATE` already). This closes the permanence: an
   interrupted commit becomes repairable by the retry that already happens.
3. **Wire `removeJobs`.** Use the existing method on the invalidation path so a
   rollback removes locator entries incrementally instead of forcing a full
   rebuild — deleting a dead code path and an unbounded scan together.
4. **Address the write cliff (defect B).** Only after (2) has closed the repair
   gap, since every candidate widens the crash window. Cheapest first: a single
   persistent locator connection with a real `cache_size`/`mmap_size` instead of a
   fresh ~2 MB-cache connection per `advance()`; then moving the locator write out
   of the DuckDB gate; then structural options (partition by block range, 16-byte
   job id instead of a 36-char UUID string, or defer the whole locator build to
   the catch-up→live transition). See the finding report §8.
5. **Bounded background repair.** Given the counter from (1), a
   maintenance-cadence job that re-derives locator entries for the affected
   generation ranges only, bounded by the existing maintenance time budget. Only
   if (1) shows real gaps in production.

Steps 1–3 are small and local. Step 4 is the throughput work and needs its own
measurement. Step 5 is gated on evidence from step 1 — do not build it
speculatively.

## Verification the scope should carry

- A test that kills the session between the DuckLake `COMMIT` and
  `updateTransactionLocator`, restarts, replays the same job, and asserts the
  locator contains every entry — the case that fails today.
- A test that a locator gap yields a *correct* answer via full-range fallback
  (locking in the behaviour above so a future optimisation cannot turn a gap into
  a false negative).
- A test that `removeJobs` on invalidation leaves the locator consistent without
  a full rebuild, asserting `fullRebuilds` did not increment.
- Extend the shared backend conformance fixture so SQLite (which uses a real
  B-tree index and is unaffected) and DuckLake are held to the same
  find-transaction post-conditions.

## Explicitly out of scope

The generation-ordered delta journal; any further throughput work; the redeemer
issue (upstream yaci-core, tracked in
`issue-redeemer-size-mismatch-2026-08-19.md`); ADR-034 review findings A–D beyond
the counter in step 1.
