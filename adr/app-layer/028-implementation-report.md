# ADR-028 implementation report

This report records milestone evidence, review findings, and qualification results for
[ADR-028](028-l1-historical-ledger-state-attestation-chain.md). It is updated on the ADR integration
branch after each milestone.

## M1 — epoch-boundary observation wire

Status: complete

Implemented:

- replaced the transaction-only preview observation pointer with a closed, tagged anchor family;
- defined canonical transaction (`tag = 0`) and epoch-boundary (`tag = 1`) CBOR forms;
- added CDDL, public golden vectors, defensive value semantics, canonical decode checks, and malformed-input tests;
- migrated transaction observers and consumers to require a transaction anchor explicitly;
- made the clean break visible by advancing app-block format `1 -> 2` and plugin API `2/3 -> 3/4`;
- advanced every in-tree plugin manifest and the plugin scaffolder, and added rejection coverage for old app blocks,
  old observation bytes, and old plugin manifests.

Review and iteration:

- Java record equality is reference-based for array components, so the first golden-vector test exposed that
  decoded observations were not value objects. Explicit content equality/hash implementations now cover the
  observation and transaction anchor.
- A bridge regression asserted the old untagged observation key. The stable key now includes `tx:` or `epoch:`
  so anchors from the two domains cannot collide.
- Certified-proof and catalog tests contained literal preview API/block versions. They now use the current block
  marker where appropriate, while compatibility tests explicitly exercise the rejected old marker.

Verification:

- all main and test sources compile (`compileJava compileTestJava`);
- `:core-api:test` and `:runtime:test` pass;
- `:plugin-catalog:test` passes, including old plugin API rejection;
- the certified-proof profile test passes with app-block v2;
- EUTxO ledger, Cardano bridge, ZK testkit, devtools, and plugin-conformance suites pass.

One unrelated HTTP direct-connection test could not acquire a local socket while the workstation's ephemeral
port range was exhausted by pre-existing long-running Gradle jobs. Its neighboring proof suite passed and this
environmental test will be rerun during later milestone/full-build qualification after the sockets age out.

## M2 — asynchronous epoch observation foundation

Status: complete

Implemented:

- added the narrow `L1EpochObserver`, provider, boundary, manifest, sink, and epoch-pinned state SPIs;
- added catalog discovery, legacy ServiceLoader discovery, trust-tier enforcement, and guarded plugin facades for
  the new contribution kind;
- added `l1.epoch-stability-depth`, defaulting to the ordinary L1 stability depth, and committed it in consensus
  profile v2 so members cannot run different epoch-finality policies;
- integrated a dedicated single-threaded coordinator whose block callback performs only epoch arithmetic,
  atomic bookkeeping, and a coalesced wake-up;
- added a byte-bounded RocksDB spool with `GENERATING -> READY -> OFFERED -> FINALIZED` records, exact canonical
  verification indexes, rollback invalidation, restart recovery, and fail-closed voting health;
- bounded injection to one outstanding record per job and made finalization acknowledgement advance the next
  record, preserving records on non-proposers for independent verification;
- added hard startup gates for a persistent source, retained snapshots, disabled app-block pruning, and positive
  block-depth stability.

Review and iteration:

- a stress test found that repeated coalesced wake-ups could offer later chunks before the first offered chunk was
  finalized. The spool now refuses another offer while any record in that job is `OFFERED`.
- wake-up reconciliation now uses a monotonic generation counter, closing the small end-of-run lost-wake window.
- proposal handling checks source health both before execution and immediately before persisting a vote lock;
  a source failure therefore cannot silently produce a threshold vote.

Verification:

- synthetic two-member generation produces byte-identical canonical observations;
- publisher latency remains independent of a deliberately blocked observer callback;
- restart re-offers the same unacknowledged record, and acknowledgement advances exactly one chunk;
- proposer rotation preserves verification records and begins offering only after the member becomes proposer;
- complete `:core-api:test`, `:appchain-config:test`, `:plugin-catalog:test`, and `:runtime:test` suites pass.

## M3 — protocol-parameter history

Status: complete

Implemented:

- added the RocksDB-backed host adapter that resolves the first block of the latest completed epoch boundary and
  opens a close-guarded `L1EpochState` without exposing it to app-state execution;
- added a canonical positional protocol-parameter CBOR profile covering the complete public parameter snapshot;
  every decimal/rational is encoded as an exact numerator/denominator pair;
- added the `l1-epoch-params-v1` stdlib observer and independently selectable `epoch-params` state machine;
- the machine consumes only verified observations from `AppBlockExecutionContext`, writes immutable
  `params/<epoch>` leaves plus `params/latest`, and never opens current or historical L1 state during replay;
- added stable claim/query/key contracts, capability discovery, ServiceLoader/catalog registration, and the typed
  `EpochParamsClient` query facade;
- wired the host provider automatically when the node uses the persistent default account-state store.

Review and iteration:

- the canonical codec uses a frozen array rather than an object/map shape, preventing property-name or reflection
  ordering from entering consensus bytes; nested cost-model maps are sorted explicitly;
- state writes are idempotent only for byte-identical repeats; a conflicting value for an existing historical epoch
  fails closed rather than mutating history;
- the provider resolves boundary identity from retained canonical chain indexes, while the app machine proves the
  G6 replay property using only retained app-block observation bytes.

Verification:

- `StateMachineConformance` passes across three independent executions, restart, snapshot restore, and replay;
- full stdlib, appchain-client, core-api, ledger-state, and plugin-catalog test suites pass;
- focused runtime epoch-coordinator regression tests pass.

The live preprod source comparison is retained as part of the final preprod qualification because it requires the
new Cardano History chain to observe an actual epoch boundary after deployment.

## M4 — direct-MPF feasibility gate

Status: complete for M5 implementation; full-chain timing, three-member agreement, and source cross-verification
remain M5/M8 release gates because the stake machine did not exist before this milestone.

Implemented:

- froze the v1 end-of-epoch stake manifest, 25,000-entry chunk, `[coin, poolHash]` leaf, chunk-root, state-key,
  and authenticated completeness-metadata contracts;
- isolated the 3 MiB/150,016-item history decoder from the ordinary stdlib decoder, whose 1 MiB/2,048-item
  defensive limits remain unchanged;
- added a reproducible two-pass mainnet-scale benchmark using the same CCL MPF implementation and a persistent
  RocksDB node store, committing one 25,000-entry batch at a time as the app-chain runtime does;
- added a compiled Plutus V3 same-root pair verifier for a fact leaf plus its completeness leaf, including budget
  and wrong-root conformance tests.

Measured result (`benchmarkEpochStakeMpf`, Apple M4 Max, OpenJDK 25.0.2, bounded 4 GiB Java heap):

| Measure | Result |
|---|---:|
| Entries / chunks | 1,300,000 / 52 |
| Canonical chunk payload | 87,102,264 bytes total; 1,675,044 bytes maximum |
| First / second canonical generation pass | 2.221 s / 0.909 s |
| Persistent MPF insertion | 291.122 s |
| RocksDB growth | 3,837,958,620 bytes per retained epoch |
| Restart/open fixed root | 534 ms |
| Sample proof generation | 427 microseconds average |
| Largest proof wire / steps | 805 bytes / 6 |
| Largest fact + completeness wire pair | 1,476 bytes |
| Observed post-run heap growth | 506,915,760 bytes |
| Compiled pair-validator fixture | 8 folds, 1,300-byte redeemer |
| Compiled pair-validator budget | 346,086,333 CPU; 1,164,740 memory |

The machine-readable result is retained in
[`benchmarks/028-m4-epoch-stake-mpf.json`](benchmarks/028-m4-epoch-stake-mpf.json). An initial deliberately
in-memory run saturated a 4 GiB heap and entered full-GC thrash. That finding changed the benchmark and production
rule: mainnet-scale MPF nodes must be persisted and committed per chunk; a whole-epoch in-memory node store is not
a supported implementation.

Accepted budgets and SLA:

- 25,000 entries remains the maximum chunk size: the measured message is below 2 MiB and leaves 7,768 operations
  of the 32,768-operation block ceiling for metadata, receipts, and composed components;
- operators allocate at least 4 GiB heap and 8 GiB process memory to a stake/DRep history member;
- retained authenticated-state growth is budgeted at 4.8 GB/epoch and 350 GB/year per member, including 25%
  headroom over the synthetic RocksDB result; retained observation bodies require a separate 8 GB/year budget;
- after the k-block stability wait, a 1.3M-entry epoch must become app-final within 30 minutes and be included in
  the next configured L1 anchor batch; the total boundary-to-anchor SLA is therefore the stability wait plus at
  most 60 minutes;
- proof keys, values, six measured MPF steps, and the proof pair fit the released 256-byte key, 8 KiB value,
  32-fold, and transaction-budget envelopes with substantial headroom.

Review and iteration:

- the first implementation raised the shared stdlib CBOR limits. Review rejected that broad attack-surface
  change; only Cardano-history chunks now use the larger strict codec;
- Java records containing byte arrays again required explicit value equality so round-trip contract comparisons
  cannot accidentally use array identity;
- completeness is one authenticated metadata value bound to the manifest and is true iff all chunks were received;
  positive and negative claims therefore use the same two-proof model;
- the benchmark now performs two independent canonical passes, uses bounded persistent per-chunk writes, reopens
  the database at the fixed root, and measures inclusion, exclusion, and completeness proof material.

Verification:

- maximum-size 25,000-entry contract round-trip and malformed/reordered/duplicate tests pass;
- the complete `appchain-stdlib-contracts` suite passes;
- the persistent 1.3M-entry benchmark passes under a 4 GiB heap;
- the pair verifier compiles to the actual Plutus target, accepts two proofs at one root within the pinned Cardano
  ceilings, and rejects a different root.

M4 therefore accepts direct MPF for M5. End-to-end boundary-to-anchor timing, three-member operation during L1
sync, process-death/rotation against the concrete stake machine, rollback cancellation, replay, and annualized
real-data growth remain hard M5/M8 qualification gates rather than being inferred from this component benchmark.
