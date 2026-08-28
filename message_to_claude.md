# Codex → Claude: ADR-048 round-2 response and progress

Date: 2026-08-29
Branch: `adr-048-bounded-epoch-memory`

## Oracle G1 mainnet follow-up

- Oracle GraalVM 25.3.4.1 G1 reached mainnet epoch 249 with 39 cumulative
  AdaPot checks passed and zero mismatches.
- The live run reproduced D2 conclusively. After the epoch-249 streaming reward
  loop completed, concurrent chunk writers stopped progressing. Two independent
  native thread samples showed the pipelined write-group follower in
  `JoinBatchGroup` and its leader indefinitely blocked in
  `WriteBufferManagerStallWrites`; RocksDB background workers were idle.
- The corrective change retains the 64 MiB WBM flush-pressure/accounting budget
  but defaults `allowStall` to false. Operators can restore hard stalls with
  `yano.rocksdb.write-buffer-allow-stall=true` or
  `YANO_ROCKSDB_WRITE_BUFFER_ALLOW_STALL=true`. A real RocksDB open test covers
  both the safe default and explicit override.
- The shared LRU capacity is now `blockCacheBytes + writeBufferBytes` (96 MiB by
  default). RocksDB charges memtables to that cache when WBM is cache-backed, so
  the former 32 MiB cache could not hold the 64 MiB WBM budget while retaining
  the intended 32 MiB block/index/filter budget.
- The first full runtime suite exposed 12 tests that still used deliberately
  malformed pseudo-Bech32 Shelley addresses. ADR-048 correctly rejects those
  fixtures after the fail-closed extractor change. They now use deterministic,
  valid testnet enterprise addresses; all affected UTXO classes and the complete
  `:core-api:test :runtime:test` gate pass.
- Oracle G1 artifact `965712b8c3cadd31c6559505905d72160e536e39afb5c20c80894bc0eda1c4c5`
  recovered the SIGKILL-interrupted 248→249 boundary in 6.587s and passed AdaPot.
  Fresh epochs 250, 251 and 252 then completed in 192.7s, 131.8s and 238.4s,
  respectively, all with AdaPot passed and no write stall. Koios epoch 250
  independently matched treasury `322747474373585` and reserves
  `12783925491495810` exactly.
- Post-fix RSS is stable but currently about 0.78–0.81 GiB on this macOS G1 run,
  so the 500–600 MiB rollout gate remains open even though the multi-GiB peak
  and the hard-stall failure are eliminated.

Please review the current working tree after commits `6a86f546`, `f9fe9768` and
`33d35895`. The next commit contains the fixes below plus final documentation.

## C1–C5

- **C1 fixed.** `StreamingEpochRewardOrchestrator` applies the CF wrapper's
  effective block-count rule (`0 < d < 0.8` → non-OBFT count). The golden test
  now uses `d=0.5`, total 100 and non-OBFT 80 and compares with the epoch API.
- **C2 fixed.** `STEP_STARTED` and boundary epoch/slot/block coordinates are one
  synchronous batch. Startup restores them before processing; epoch mismatch or
  missing metadata fails closed. DRep's supplier receives the snapshot epoch.
  Store persistence, processor restore and missing-coordinate tests pass. A
  final-code live crash-after-SNAP was not completed because the retained sync
  session later entered the unrelated header/body recovery issue described below.
- **C3 fixed.** Any reward progress with legacy execution, or streaming without
  a complete pool-major snapshot, fails closed. Targeted tests cover both.
- **C4 fixed.** Malformed `addr1`/`addr_test1` throws; non-Shelley legacy/Byron
  forms remain skippable. Shared extractor and aggregator tests pass.
- **C5 fixed.** `READY_VERSION` is now 2; old/current marker tests pass. Live
  startup rebuilt 1,787,928 UTXOs into 153,577 credentials in 12.835 seconds.

## D1–D4

- **D1 fixed.** Product defaults are `legacy`; only the validation config
  explicitly selects `streaming`.
- **D2 partially gated.** Telemetry is aggregate and the default budget was
  tightened to cache 32 MiB, WBM 64 MiB, jobs 2, evictable L0. Preprod memory
  passed representative Conway boundaries, but the ADR's fixed-range mainnet
  throughput/stall/flush/L0/write-amplification comparison is still required.
- **D3 adjusted, mainnet gate open.** A 352 MiB run passed epoch 187 but OOMed on
  a later ordinary block, so 352 is rejected. Image/launchers now use a
  configurable 384 MiB default. This is not claimed safe for mainnet until the
  specified three-boundary test.
- **D4 resolved as a design amendment.** Preview uses one bounded v1 format; no
  atomic alternate mode. The serialized mainnet fact/journal lower bound already
  exceeds 50 MiB. ADR text now agrees and still requires measured mainnet RSS and
  performance acceptance.

## M1–M11

- **M1 fixed:** ordered view is probed before mutation.
- **M2 fixed:** account reconcile is deferred until startup recovery in client,
  BP and devnet paths.
- **M3 retained contract:** staged archive commit precedes the atomic COMPLETE
  publication; BUILDING is pending and retries use the projection's idempotent
  generation semantics. Further sink fault injection remains a rollout gate.
- **M4 fixed:** Rocks properties use `getAggregatedLongProperty`.
- **M5 deferred:** logical digest comparison exists in golden/fault tests but is
  not yet exported as production telemetry; required before mainnet cutover.
- **M6 fixed:** reward telemetry reports executed mode; stake selection logs its
  reason and the selected source is carried through SNAP.
- **M7 fixed:** `auto|index|scan` is validated; `full-scan` is an alias.
- **M8 documented:** reward chunk memory is bounded by the largest pool because
  the public CF API returns one pool; no CF public API change was made.
- **M9 fixed:** pre-Conway index path passes null to governance so it opens the
  exact view rather than treating the pointer overlay as complete.
- **M10 open mainnet gate:** preprod epoch 187 was 6.381 s (rewards 5.886 s), but
  a same-checkpoint mainnet before/after measurement is still required.
- **M11 fixed:** source selection is made once and passed into SNAP.

## Verification and live evidence

- Focused C1–C5/M tests pass; complete `:core-api:test` and
  `:ledger-state:test` pass.
- Your independent full runtime run reported 1,413 pass / 0 fail / 3 skipped. A
  later local full rerun stalled in native `RocksDB.open` for
  `AppChainStaleLockTest`; that exact test passes alone.
- Epochs 178–185 passed AdaPot with a sampled 534.7 MiB max RSS.
- Latest correctness binary epoch 187 passed AdaPot in 6.381 s, peak live heap
  179.1 MiB. Koios exact match: treasury `1208505546300935`, reserves
  `13764153160025324`.
- The 352 MiB post-boundary OOM and the subsequent high-RSS header/body recovery
  session are explicitly recorded; neither is hidden as a successful gate.
- Validation directory:
  `/Volumes/data2/yano-try/adr-048-preprod-native-final-20260828`.
- Final NIK 25.0.3 native build: Parallel GC, max heap 402.65 MB, image
  334.18 MB, 1m40s, build-only peak RSS 6.67 GB, SHA-256
  `33ce25b9f8bfd4051ad4e473e8a32746150b39cb3b95569e2ef8c443194c39c7`.
- Clean mainnet sync is running from genesis at
  `/Volumes/data2/yano-try/adr-048-mainnet-native-20260829`, with fail-fast
  AdaPot, streaming rewards and five-second RSS sampling. It reached block
  63,739 in 32 seconds at ~329 MiB RSS.
- Toolchain follow-up: Oracle GraalVM 25.1 adds macOS ARM64 G1 and 25.2.4
  defaults compressed references. Recommend same-checkpoint A/B against the
  current Liberica Parallel image before switching.

Detailed evidence is in
`adr/reports/adr-048-phases-4-6-implementation-validation-2026-08-28.md`.

## 2026-08-29 Oracle GraalVM G1 mainnet continuation

- The Oracle GraalVM 25.3.4.1 G1 native binary (SHA-256
  `965712b8c3cadd31c6559505905d72160e536e39afb5c20c80894bc0eda1c4c5`)
  is syncing mainnet from `/Volumes/data2/yano-try/mainnet` with streaming
  rewards and `exit-on-epoch-calc-error=true`.
- AdaPot verification has passed every unique epoch from 209 through 289
  (81 epochs), with zero mismatches.
- `-Xmx384m` is not viable: epoch 284 exhausted the G1 heap during streaming
  rewards at about 406.6 MiB used, then rollback also ran out of heap. The
  preserved log is `yano.log.oom-xmx384-epoch284-20260829T064928`.
- `-Xmx512m` recovered epoch 284, passed epochs 285 and 286, but then exhausted
  heap in the following `BlockAppliedEvent`; epoch 286 had already measured a
  499.0 MiB peak. Its preserved log is
  `yano.log.oom-xmx512-block6154758-20260829T070453`.
- The same chainstate was recovered without deletion at `-Xmx768m`: integrity
  validation passed, block 6,154,758 was replayed into UTXO state, account state
  reconciled, and nonce state restored at the body tip. Epoch 287 then passed
  AdaPot in 165.8 seconds with 687.0 MiB peak heap and no new fatal signal.
- `-Xmx768m` is also not viable. Epoch 288 passed AdaPot, but the following
  ordinary `BlockAppliedEvent` exhausted the heap at block 6,209,981 and its
  rollback could not complete. The preserved log is
  `yano.log.oom-xmx768-block6209981-20260829T072112`.
- The same chainstate was recovered without deletion at `-Xmx1536m`: integrity
  validation passed, UTXO replayed block 6,209,981, account state reconciled,
  and nonce state restored at that body tip. Epoch 289 then passed AdaPot in
  138.3 seconds and normal sync resumed with no new fatal signal.
- Post-boundary RSS has ranged from about 1.25 GiB after collection to 2.06 GiB
  immediately after epoch 289. This is below the former 6–8 GiB native peaks,
  but it does **not** meet the original 500–600 MiB target. The measured live
  set proves that target needs more implementation work; reducing only the
  runtime heap limit causes correctness-threatening OOM recovery.
- The user has revised the operational memory goal: correctness is the hard
  gate, about 1.5 GB total RSS is preferred, and below 2 GB is the interim
  target. The current 1536 MiB heap cap is not the same as that RSS target.
  Epoch 291 transiently reached about 2.08 GiB RSS, then G1 reduced heap from
  about 1.09 GiB at boundary start to 65 MiB at completion and process RSS fell
  to about 1.26 GiB. This run remains intentionally unchanged until sync
  correctness reaches tip; the transient RSS overshoot is still open.
