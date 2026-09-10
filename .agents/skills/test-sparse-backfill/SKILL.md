---
name: test-sparse-backfill
description: Regression test - Validate fast devnet backfill across yano.block-producer.backfill-block-interval-slots values (dense, explicit, automatic, 1000 ms slots, rejections) and prove a downstream Haskell cardano-node syncs the resulting history from genesis and keeps following live blocks
---

# Test: Sparse Devnet Backfill + Haskell Sync

End-to-end regression for `yano.block-producer.backfill-block-interval-slots`: start a
regular devnet producer (NOT slot-leader) with genesis shifted three epochs into the
past, catch up to wall-clock, verify the backfilled history block by block, then prove a
Haskell `cardano-node` accepts that history from genesis and follows subsequent live
blocks.

## Run it

```bash
./scripts/sparse-backfill/run-sparse-backfill-test.sh                 # devkit matrix (default)
./scripts/sparse-backfill/run-sparse-backfill-test.sh --cases auto    # one interval
./scripts/sparse-backfill/run-sparse-backfill-test.sh --no-haskell    # Yano-side only, fast
./scripts/sparse-backfill/run-sparse-backfill-test.sh --slot-leader   # + optional slot-leader scenario
```

Default cases: `dense,interval2,auto,auto-1s,reject`. The runner prints a combined
report and exits non-zero if any case is FAIL or BLOCKED.

| case | `backfill-block-interval-slots` | genesis `slotLength` | what it proves |
|---|---|---|---|
| `dense` | 1 | 0.3 s | original dense behaviour, one block per slot — the speed baseline |
| `interval2` | 2 | 0.3 s | explicit two-slot spacing |
| `auto` | 0 | 0.3 s | automatic sparse spacing (+ graceful restart and continued live production) |
| `auto-1s` | 0 | 1.0 s | spacing is slot-based; the wall-clock conversion respects slot duration |
| `reject` | −1 and `3k/f` | 0.3 s | negative interval and forecast-window-limit interval are rejected with a useful error |
| `slot-leader` (opt-in) | 0, `f<1` | 0.3 s | Praos-eligible sparse backfill; see the caveat below |

## Scenario

Each case copies `app/config/network/devnet/pv10/` into its own directory and patches the
Shelley genesis to `epochLength=1200`, `securityParam=100`, `activeSlotsCoeff=1`,
`slotLength=0.3` (`1.0` for `auto-1s`). Three epochs are therefore
`3 × 1200 × 0.3 s = 1,080,000 ms` (`3,600,000 ms` at 1 s slots), and the wall-clock target
lands just past slot 3600. Slot and live block timers both resolve to 300 ms
(1000 ms for `auto-1s`) straight from the genesis — nothing is set explicitly.

`pv10/` is used because cardano-node 11.0.x enforces strict cost-model lengths (alonzo
166 PlutusV1 entries, conway 251 PlutusV3); the PV11 folder at
`app/config/network/devnet/` is rejected. See `test-past-time-travel` for the background.

With `k=100` and `f=1` the forecast window is `floor(3k/f) = 300` slots and automatic
empty-block spacing is `299` slots. Starting exactly at slot 0 and targeting exactly slot
3600 yields **15 backfill blocks plus genesis** — that vector is asserted in the helper's
self-test, never against a live API call (see below).

## No hardcoded block counts on live calls

A live `/epochs/catch-up` never starts at slot 0: the past-time-travel scheduler forges
sequential blocks (slot *i* = block *i*) while the test issues its HTTP calls, and the
target is `(now − genesisTimestamp) / slotLength` at the moment of the call. The runner
therefore records the real values after the fact and derives the expectation, from three
sources that must agree:

1. runtime log — `Catching up to wall-clock: N slots (current=S0, target=T)`
2. runtime log — `Sparse backfill: one block every I slots from slot S0+1 to T`
   (absent when the resolved interval is 1)
3. arithmetic — `S0 = new_block_number − blocks_produced` from the catch-up response,
   cross-checked against the slot of block `S0`

`tools/backfill_expect.py` then reproduces `DevnetBlockProducer.nextBackfillSlot` for
`(S0, T, resolved interval, epochLength)` — including the epoch-boundary pull-back and the
final target block — and the produced slots must match it exactly. Nothing polls
`/node/tip` before the call and treats that as the starting point, so scheduler races
cannot corrupt the measurement. The pre-call tip is still captured, as observation only.

## What each backfill case asserts

Yano side (`tools/verify_chain.py`):
- resolved `slotLength` / block interval match the genesis derivation
- `/epochs/shift` returns `genesis_slot=0` and `shift_millis` equal to
  `epochs × epochLength × slotLength × 1000`
- resolved interval equals `BackfillPolicy.resolveInterval(requested, k, f)`
- `blocks_produced` equals the derived expectation; every backfilled slot matches
- past-time-travel prefix is dense (block *i* at slot *i*) up to the recorded initial tip
- no gap exceeds the resolved interval, and the widest gap stays inside the forecast window
- every epoch start in range carries a block, one `Epoch transition detected` line per
  crossed boundary, no skipped-epoch batch processing, `epoch == slot / epochLength`
- hash continuity (`previous_block` → prior `hash`) across consecutive blocks
- the final block sits exactly on the recorded target
- **speed**: `elapsed < blocks_produced × block_time_ms` — the only timing assertion. It
  proves the backfill neither waited for the live block timer nor scanned intervening
  slots. Ratios (including vs. the dense baseline) are reported, never asserted.
- no validation / nonce / forecast / epoch-transition errors in the Yano log

Haskell side (`tools/haskell_check.py`), against a **fresh database** and the **same
genesis bytes** (sha256 compared, not assumed):
- adopted tips parsed from `Chain extended, new tip: <hash> at slot <n>`; zero parsed
  lines is a FAIL, not a silent pass
- genesis (slot 0) is adopted, and every crossed epoch start plus the backfilled target
  is **covered**: either logged directly (then hash-compared), or below the highest
  adopted slot. The ChainDB tracer does not log a tip line for every block during bulk
  sync — in the dense case only genesis, the epoch starts and the live blocks appear —
  so the fallback matters. It is sound because the node starts from an empty database and
  its tip hash matches Yano at the same block number; a Cardano chain is a hash chain, so
  an identical tip means an identical prefix. `verify.json` records which slots were
  verified directly and which by prefix implication.
- every required slot that was logged, plus a spread of up to 20 other adopted tips,
  resolves in Yano at exactly that slot (`GET /api/v1/blocks/<hash>`)
- `cardano-cli query tip` block/slot/hash equal Yano's block at the same number, sampled
  at the backfilled tip and again during live follow. `syncProgress` is recorded but is
  never the evidence.
- no rejection/validation/forecast errors in the node log

`auto` additionally stops Yano with SIGTERM (60 s grace), restarts it, and requires
`Block producer resuming from existing tip` in the **new** log, an advancing tip, and the
still-running Haskell peer following past the pre-restart slot.

Every Yano shutdown — the restart stop and the end-of-case stop — is also checked for a
JVM crash signature (`shutdown-clean`, `final-shutdown-clean`). A SIGSEGV exits inside the
SIGTERM grace period and would otherwise be recorded as a normal stop; see discrepancy 7.

## Rejection cases (`--cases reject`)

| sub-case | config | where it fails |
|---|---|---|
| `negative` | `-1`, past-time-travel | startup — `YanoConfig.validate`: *Backfill block interval must be non-negative (0 = automatic)* |
| `limit-live` | `300` (= `3k/f`), regular devnet | startup — `DevnetProducerFactory` resolves the interval while creating the live producer |
| `limit-ptt` | `300`, past-time-travel | `POST /api/v1/devnet/epochs/shift` — *Backfill interval must be below forecast window of 300 slots* |

For the startup cases the runner asserts the exact message appears with
`YANO_STARTUP_FAILURE code=RUNTIME_INITIALIZATION_FAILED`, and that no block producer ever
starts. It does **not** assert process exit — see discrepancies 7 and 8. For the shift case it
asserts a 4xx/5xx response whose body names the forecast window and the limit.

## Code vs. the expectations this skill was written against

Verified against the implementation (`runtime/.../blockproducer/BackfillPolicy.java`,
`DevnetBlockProducer.java`, `producer/DevnetProducerFactory.java`,
`producer/ProducerStartupCoordinator.java`, `devnet/DevnetCatchUpService.java`,
`devnet/DevnetGenesisShiftService.java`, `core-api/.../YanoConfig.java`). Everything in the
matrix matches; these details differ from a naive reading and are handled by the runner:

1. **Genesis is authoritative for slot duration.** Epoch shift and producer timing
   use Shelley genesis `slotLength`. The legacy
   `yano.block-producer.slot-length-millis` property is accepted for compatibility,
   but conflicting values are ignored with a warning. Exercise this with
   `--cases dense,interval2,auto,auto-1s --legacy-slot-length-millis 777`;
   the existing duration and shift checks must still pass at 300 ms and 1000 ms.
2. **An out-of-range explicit interval is not rejected at startup in past-time-travel
   mode.** The producer is constructed during `/epochs/shift`, so the failure surfaces on
   that call — and `DevnetGenesisShiftService` marks the runtime degraded, so the node
   needs a restart afterwards. Without past-time-travel the same value fails at boot.
   Worth tightening: validate the interval against genesis `k`/`f` at startup.
3. **`past-time-travel-mode` is a first-boot mode.** `ProducerStartupPlan.from` defers
   production whenever the flag is set, regardless of existing chain state, and a second
   `/epochs/shift` would re-shift genesis. The restart check therefore restarts in regular
   devnet mode against the same chainstate and the already-shifted `systemStart`.
4. **The runtime rewrites `systemStart` into whatever `shelley-genesis.json` it is given**
   (`resolveAndPersistGenesisTimestamp`, `persistShiftedSystemStart`). Never point a test
   at the tracked fixtures — this previously dirtied `app/config/network/devnet/pv10/` and
   broke the testkit suite (`app/docs/sparse-backfill-validation.md`). The runner copies
   first and re-checks `git status --porcelain app/config` at the end.
5. **`0` never reaches the producer.** `DevnetBlockProducer.setBackfillBlockIntervalSlots`
   rejects values below 1; `BackfillPolicy.resolveInterval` turns the configured `0` into
   the automatic spacing first. Layering, not a defect — but it means unit tests of the
   producer and of the policy must both be read to know the effective value.
6. **`cardano-cli` cannot use a long socket path.** AF_UNIX caps paths at 104 bytes; an
   absolute run-directory path fails with `pokeSockAddr: path is too long`. The runner runs
   both node and CLI with a relative `db/node.socket`.
7. **Open defect — an invalid interval can crash the JVM.** On the `limit-live` path
   (interval `3k/f`, regular devnet, so the failure happens *after* the chain store is
   open) the process intermittently dies with
   `SIGSEGV … librocksdbjni…jnilib Java_org_rocksdb_RocksDB_iterator`. The crashing thread
   is the projection drain loop, which keeps running after the failed startup and calls
   `RocksDB.newIterator` on an already-closed handle:

   ```text
   ProjectionHistoryService.drainLoop
     -> ProjectionHistoryService.drainEpochArtifactGaps
     -> ProjectionOutboxStore.acknowledgeRepairsAlreadyComplete
     -> ProjectionOutboxStore.epochArtifactGaps
     -> org.rocksdb.RocksDB.newIterator   <-- SIGSEGV, handle closed
   ```

   Reproduced in every run where the check ran after shutdown — 4 of 4 (`0.1.0-pre14`,
   JVM 25.0.2, macOS arm64); one earlier run checked too early to tell. `hs_err_pid*.log`
   is preserved in the sub-case directory. A healthy node's SIGTERM does **not** crash:
   the `shutdown-clean` / `final-shutdown-clean` stages of the backfill cases pass. The rejection message and "no block production"
   assertions still pass — the crash is a separate teardown defect, and it is not
   backfill-specific (any startup failure after the store opens can hit it). Because of it
   **the `reject` case currently reports FAIL** on `limit-live-clean-failure`; that is
   intentional — a crash must not be reported as PASS. Related: the "async drain on the
   failed-startup path" finding from the ADR-055 review.

8. **An invalid interval does not stop the process.** With `-1` the node logs
   `YANO_STARTUP_FAILURE code=RUNTIME_INITIALIZATION_FAILED` and
   *Backfill block interval must be non-negative (0 = automatic), got: -1*, then keeps
   running: a `plugin-metrics-cache` thread retries `ensureYano()` about once a second and
   re-logs `Creating Yano with network: devnet` forever. The node never produces blocks, so
   the rejection itself is correct and useful, but a misconfigured process lingers instead
   of exiting. This is generic Yano startup behaviour, not backfill-specific; the runner
   records it as a NOTE (never a silent pass) and stops the process itself.

## Optional slot-leader scenario (`--slot-leader`)

Past-time-travel **slot-leader** backfill (`past-time-travel-slot-leader-mode=true`) with
`f < 1`: the catch-up forges only slots the pool is eligible for, so the tip may legitimately
sit behind the processed target and spacing is a lower bound, not an equality. The runner
checks the `Slot-leader backfill complete: blocks=…, checks=…, processedSlot=…` line
(blocks and leadership checks both > 0), that no gap exceeds the forecast window, and that a
Haskell node accepts the history. Automatic slot-leader spacing is `floor(3k/f)/2` — half
the window, leaving search headroom — not `window − 1`.

Genesis for this case uses `k=50`, `f=0.5` so that both `3k/f` and `10k/f` stay within
`epochLength=1200`; cardano-node validates the Shelley genesis against those bounds and
refuses it otherwise. Verify the bound from the Haskell startup log if you change `k`/`f`.

**Known blocker:** a fresh slot-leader devnet fails at `/epochs/shift`, before any backfill,
with `Canonical block hash is required` — `DevnetGenesisShiftService` calls
`storeGenesisUtxosIfNeeded` before `startSlotLeaderTimeTravel`, which does not synchronously
create a genesis block. The runner detects this and records the case **BLOCKED**. Never
disable UTXOs to make it pass, and never report it as PASS.

## Isolation and cleanup

- Per case: its own genesis copy, chainstate, history archive, app-chain store, Haskell
  database + socket + config + topology, logs, and probed ports (never 7070 / 13337 /
  3002 / 32000 / 12788 / 12798).
- Only PIDs this run started are ever signalled — recorded in `<run-dir>/started-pids`, no
  `pkill`, no port-based killing. Existing developer nodes and databases are untouched.
- The shared `test-data-dir/haskell-node/` supplies only the **binary**;
  `setup-haskell-test-node.sh` runs only when that binary is missing.
- Everything is preserved under `test-data-dir/sparse-backfill/<timestamp>/` (gitignored):
  effective genesis + sha256s, `node-config.json`, the exact `java` command, all logs,
  `verify.json`, `haskell-*.json`, `stages.tsv`, and `report.md`.
- Three statuses: **PASS**, **FAIL**, **BLOCKED**. A blocked or skipped scenario is never
  reported as PASS.
- The runner makes no commits and never modifies tracked fixtures.

## Last validated

Full default matrix, 2026-09-10, macOS arm64, Yano `0.1.0-pre14` uber-jar (JVM 25.0.2),
cardano-node 11.0.1 (`macos-amd64`), `epochLength=1200`, `k=100`, `f=1`, 3-epoch shift:

| case | interval → resolved | slot ms | initial → target slot | blocks | backfill | Haskell sync | result |
|---|---|---|---|---|---|---|---|
| dense | 1 → 1 | 300 | 10 → 3613 | 3603 | 3510 ms | 7468 ms | PASS |
| interval2 | 2 → 2 | 300 | 9 → 3613 | 1803 | 2128 ms | 7463 ms | PASS |
| auto | 0 → 299 | 300 | 9 → 3611 | 15 | 564 ms | 5379 ms | PASS |
| auto-1s | 0 → 299 | 1000 | 3 → 3603 | 16 | 520 ms | 5379 ms | PASS |
| reject | −1 / 300 | 300 | — | — | — | — | FAIL — see #7 |

Notes from that run:
- The automatic spacing stayed 299 **slots** at both 300 ms and 1000 ms slots, while the
  epoch shift correctly scaled with slot duration (1,080,000 ms vs 3,600,000 ms) — that is
  the slot-based-vs-wall-clock check.
- `auto-1s` produced slots
  `302, 601, 900, 1199, 1200, 1499, 1798, 2097, 2396, 2400, 2699, 2998, 3297, 3596, 3600, 3603`
  — 299-slot spacing, an extra block at each epoch start, and the target.
- Every case placed its final block exactly on the recorded target, and the Haskell node's
  tip matched Yano by slot, block number and hash in every sample.
- Backfill never waited for the block timer: `auto` covered ~1,080,600 ms of wall-clock
  time in 564 ms (~1900x); dense covered the same span in 3510 ms. Sparse is ~6x faster
  than the dense baseline here, and the gap widens with the shifted span.
- `reject`: both messages verified (`Backfill block interval must be non-negative
  (0 = automatic), got: -1`, and HTTP 400
  `Backfill interval must be below forecast window of 300 slots`), no block production in
  either — but `limit-live` hit the JVM crash in #7.
- The optional `--slot-leader` scenario (`k=50`, `f=0.5`) reproduced the known bootstrap
  blocker and was reported **BLOCKED**, not PASS: `POST /epochs/shift` returned
  `{"error":"Epoch shift failed: Failed to store Shelley genesis UTXOs"}` with
  `IllegalArgumentException: Canonical block hash is required` in the node log — the
  failure happens before any slot-leader backfill runs, so eligible-block/search-count
  coverage for `f < 1` remains unvalidated end to end.

## Pass criteria

- Every case in the requested matrix is PASS. Today the `reject` case fails on
  `limit-live-clean-failure` (#7) — fix or track that defect rather than relaxing the check.
- `backfill_expect.py --self-test` passes (it runs first; a failure means the runtime
  algorithm changed and the mirror must be updated before any result is trusted).
- `git status --porcelain app/config` is unchanged by the run.

## Manual reproduction (one case, without the runner)

```bash
CASE=/tmp/sb-manual; rm -rf $CASE; mkdir -p $CASE
python3 scripts/sparse-backfill/tools/prepare_genesis.py \
  --source app/config/network/devnet/pv10 --dest $CASE/genesis \
  --slot-length 0.3 --epoch-length 1200 --security-param 100 --active-slots-coeff 1.0
cd $CASE && java -Dquarkus.profile=devnet -Dquarkus.http.port=21000 \
  -Dyano.server.port=21001 -Dyano.storage.path=$CASE/chainstate -Dyano.history.dir=$CASE/history \
  -Dyano.genesis.shelley-genesis-file=$CASE/genesis/shelley-genesis.json \
  -Dyano.genesis.byron-genesis-file=$CASE/genesis/byron-genesis.json \
  -Dyano.genesis.alonzo-genesis-file=$CASE/genesis/alonzo-genesis.json \
  -Dyano.genesis.conway-genesis-file=$CASE/genesis/conway-genesis.json \
  -Dyano.genesis.protocol-parameters-file=$CASE/genesis/protocol-param.json \
  -Dyano.block-producer.vrf-skey-file=$CASE/genesis/vrf.skey \
  -Dyano.block-producer.kes-skey-file=$CASE/genesis/kes.skey \
  -Dyano.block-producer.opcert-file=$CASE/genesis/opcert.cert \
  -Dyano.block-producer.past-time-travel-mode=true \
  -Dyano.block-producer.backfill-block-interval-slots=0 \
  -jar <repo>/app/build/yano.jar > $CASE/yano.log 2>&1 &
curl -s -X POST localhost:21000/api/v1/devnet/epochs/shift -H 'Content-Type: application/json' -d '{"epochs":3}'
curl -s -X POST localhost:21000/api/v1/devnet/epochs/catch-up
grep -E "Sparse backfill|Catching up to wall-clock|Epoch transition detected" $CASE/yano.log
```

Copy `$CASE/genesis/*.json` into a fresh Haskell instance **after** the shift (that is when
`systemStart` is rewritten), point its topology at port 21001, and start it with an empty
`db/`.

## Files

- `scripts/sparse-backfill/run-sparse-backfill-test.sh` — driver and case definitions
- `scripts/sparse-backfill/lib/{common,yano,haskell}.sh` — process/port/isolation helpers
- `scripts/sparse-backfill/tools/backfill_expect.py` — mirror of `BackfillPolicy` +
  `nextBackfillSlot`, with the Java test vectors as a self-test
- `scripts/sparse-backfill/tools/prepare_genesis.py` — isolated genesis copy + patch
- `scripts/sparse-backfill/tools/verify_chain.py` — Yano-side verification
- `scripts/sparse-backfill/tools/haskell_check.py` — Haskell-side verification
- `scripts/sparse-backfill/tools/render_report.py` — combined report

Related: `test-past-time-travel` (dense past-time-travel + Haskell sync),
`app/docs/devnet-modes.md`, `app/docs/sparse-backfill-validation.md`.
