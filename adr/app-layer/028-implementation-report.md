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

## M4 — authenticated-state feasibility gate

Status: complete for M5 implementation; full-chain timing, three-member agreement, and source cross-verification
remain M5/M8 release gates because the stake machine did not exist before this milestone.

Implemented:

- froze the v1 end-of-epoch stake manifest, 25,000-entry chunk, `[coin, poolHash]` leaf, chunk-root, state-key,
  and authenticated completeness-metadata contracts;
- isolated the 3 MiB/150,016-item history decoder from the ordinary stdlib decoder, whose 1 MiB/2,048-item
  defensive limits remain unchanged;
- added reproducible two-pass mainnet-scale benchmarks using the released CCL MPF and classic-JMT implementations
  with persistent RocksDB stores, committing one 25,000-entry batch/version at a time as the runtime does;
- added a second MPF run that reachability-prunes from the retained final root, compacts RocksDB, reopens it, and
  regenerates and verifies inclusion, absence, and same-root completeness proofs;
- added a compiled Plutus V3 same-root pair verifier for a fact leaf plus its completeness leaf, including budget
  and wrong-root conformance tests.

Measured result (`benchmarkEpochStakeMpf`, Apple M4 Max, OpenJDK 25.0.2, bounded 4 GiB Java heap):

| Measure | Result |
|---|---:|
| Entries / chunks | 1,300,000 / 52 |
| Canonical chunk payload | 87,102,264 bytes total; 1,675,044 bytes maximum |
| First / second canonical generation pass | 2.221 s / 0.909 s |
| Persistent MPF insertion | 291.122 s |
| RocksDB growth | 3,837,958,620 bytes for one 1.3M-entry snapshot before reachability pruning |
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

The follow-up profile and pruning measurements used the identical 1.3M logical entries and 52 chunks:

| Measure | MPF, retained-root GC | Classic JMT |
|---|---:|---:|
| Persistent insertion | 123.772 s | 77.016 s |
| RocksDB before MPF GC | 3,837,967,819 bytes | n/a |
| Reachable / total MPF nodes | 1,788,217 / 8,795,118 | n/a |
| GC nodes removed / duration | 7,006,901 / 132.488 s | n/a |
| RocksDB after GC + compaction | 3,186,730,848 bytes | 1,063,546,304 bytes |
| Restart/open fixed root | 46 ms | 6.671 s |
| Sample proof generation | 647 microseconds | 1,181 microseconds |
| Largest proof wire | 805 bytes | 2,813 bytes |
| Largest fact + completeness pair | 1,476 bytes | 5,516 bytes |
| Verification target | off-chain and Cardano on-chain | off-chain only |

The machine-readable results are retained in
[`benchmarks/028-m4-epoch-stake-mpf-pruned.json`](benchmarks/028-m4-epoch-stake-mpf-pruned.json) and
[`benchmarks/028-m4-epoch-stake-jmt.json`](benchmarks/028-m4-epoch-stake-jmt.json).

GC is functionally effective—it removed 79.7% of stored MPF node records and retained-root proofs pass after
restart—but physical size fell by only 17.0%. This means the original 3.84 GB included obsolete construction
nodes, but those nodes were not the dominant physical-byte cost at this scale. MPF pruning therefore remains an
opt-in pilot, disabled by default. The final capacity model must include live-data/SST accounting and a
multi-epoch retained-root run; neither the unpruned nor pruned single-snapshot number is relabeled as measured
steady-state per-epoch growth. Classic JMT is a supported lower-storage off-chain profile, not a substitute for
the on-chain MPF contract.

Accepted budgets and SLA:

- 25,000 entries remains the maximum chunk size: the measured message is below 2 MiB and leaves 7,768 operations
  of the 32,768-operation block ceiling for metadata, receipts, and composed components;
- operators allocate at least 4 GiB heap and 8 GiB process memory to a stake/DRep history member;
- until the multi-epoch M8 measurement is complete, MPF operators reserve 4.8 GB per incoming 1.3M-entry
  snapshot and JMT operators reserve 1.4 GB, each including at least 25% headroom over its single-snapshot result;
  these are conservative pilot capacity reservations, not measured linear annual-growth claims. Retained
  observation bodies require a separate 8 GB/year budget;
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
- the pruning benchmark initially measured only deleted node count. Review required physical post-compaction
  bytes and post-restart proof checks as separate gates; deleting many small obsolete nodes does not imply an
  equivalent reduction in the reachable tree's physical representation.

Verification:

- maximum-size 25,000-entry contract round-trip and malformed/reordered/duplicate tests pass;
- the complete `appchain-stdlib-contracts` suite passes;
- the persistent 1.3M-entry benchmark passes under a 4 GiB heap;
- the persistent 1.3M-entry classic-JMT and reachability-pruned MPF benchmarks pass, including restart and real
  inclusion/absence/completeness proof verification;
- the pair verifier compiles to the actual Plutus target, accepts two proofs at one root within the pinned Cardano
  ceilings, and rejects a different root.

M4 therefore accepts direct MPF for M5. End-to-end boundary-to-anchor timing, three-member operation during L1
sync, process-death/rotation against the concrete stake machine, rollback cancellation, replay, and annualized
real-data growth remain hard M5/M8 qualification gates rather than being inferred from this component benchmark.

## M5 — epoch-stake history and semantic proofs

Status: complete for the reusable component; three-member source/anchor qualification remains the M8 deployment
gate.

Implemented:

- added a consistent RocksDB snapshot view that streams retained end-of-epoch stake in canonical
  `(credentialType, credentialHash)` order without copying the dataset into memory;
- expanded startup reconciliation across the retained snapshot horizon, so a fresh or interrupted member can
  rebuild every available boundary rather than observing only the latest one;
- added the independently selectable `l1-epoch-stake-v1` observer and `epoch-stake` state machine, registered in
  both the plugin catalog and legacy ServiceLoader discovery;
- implemented deterministic two-pass generation, a manifest followed by fixed 25,000-entry chunks, at most one
  data chunk per app block, exact per-chunk entry counts, cross-chunk ordering, immutable leaves/chunk hashes, and
  final snapshot-root recomputation;
- made reads complete-only and added a typed client for `[coin, poolHash]` values and authenticated metadata;
- wired deep-rollback detection into the durable epoch spool: unfinalized jobs above the target are invalidated,
  while crossing any finalized epoch boundary latches
  `DEEP_ROLLBACK_BELOW_FINALIZED_EPOCH_ATTESTATION` and permanently removes the member from voting;
- generalized strict MPF normalization to both non-membership terminal forms (missing branch and conflicting
  leaf), closing the absence-proof gap discovered while implementing this milestone;
- upgraded the compiled same-root verifier to bind the requested physical keys, strictly decode canonical stake
  and completeness CBOR, and enforce amount-only, pool-only, combined minimum, exact, or absence predicates.

Review and iteration:

- semantic manifest validation is kept outside the manifest/chunk decode fallback, so a well-formed but
  profile-incompatible manifest cannot be reinterpreted as a chunk;
- global canonical order is persisted as a cursor across blocks; checking only order within each chunk would have
  permitted a reordered dataset with a valid per-chunk encoding;
- duplicate chunks are no-ops only when their committed chunk hash is byte-identical; conflicting history remains
  fail-closed and write-once;
- the first on-chain CBOR decoder used a generic loop. Replacing it with bounded preferred-encoding branches both
  satisfies Julc's immutable-variable rules and makes the hostile-input cost explicit;
- absence conversion independently verifies the native wire proof before emitting its bounded terminal/fold
  representation; neither the client nor validator treats an empty query response as absence without an MPF
  non-membership proof and a complete metadata proof at the same root.

Verification:

- `HistoricalEpochStateViewTest` proves canonical iteration, point-in-time snapshot isolation, and close guards;
- `EpochStakeStateMachineTest` covers deterministic passes, rejected host reordering, incomplete-state hiding,
  missing/reordered/root-mismatched chunks, duplicate idempotency, three-member-equivalent conformance, restart,
  snapshot restore, and replay from block-bound observations alone;
- coordinator tests cover publisher independence, durable resume, proposer rotation, unfinalized rollback
  invalidation, and permanent fail-closed behavior after a finalized-boundary rollback;
- real MPF missing-branch and conflicting-leaf proof wires normalize and reconstruct their original roots;
- the compiled Plutus verifier passes all four positive stake predicates with eight total folds. The worst measured
  positive vector uses 419,652,476 CPU, 1,427,673 memory, and a 1,475-byte redeemer, below the pinned
  10,000,000,000 CPU / 14,000,000 memory / 16 KiB limits; absence plus completeness also passes those limits;
- complete ledger-state, proof-contract, stdlib-contract, stdlib, client, proof-onchain, and runtime suites pass.

The 1.3M-entry storage/ingestion evidence remains the M4 result because M5 deliberately uses the same frozen
keys, values, 25,000-entry batches, CCL MPF backend, and per-chunk commit boundary. M8 must still demonstrate the
30-minute post-stability SLA, proposer rotation, three-member root identity, L1 anchoring, and independent source
agreement in the deployed reference chain; those claims are not inferred from component tests.

## M6 — governance proposal and DRep history

Status: complete for the reusable component; independent preprod source comparison remains part of the M8
deployment qualification so it is performed against the actual three-member Cardano History chain.

Implemented:

- added rollback-safe, epoch-pinned proposal lifecycle records in canonical
  `(transactionId, governanceActionIndex)` order, including carried-forward terminal states and explicit reasons;
- fixed the existing DRep-distribution persistence path so every per-epoch write now has a matching boundary
  delta operation, and added explicit snapshot markers so an empty snapshot is distinguishable from an absent one;
- exposed proposal and DRep iterators through the same close-scoped RocksDB snapshot used by epoch stake, with
  governance datasets pinned to `newEpoch` and stake remaining pinned to `previousEpoch`;
- added the independently selectable `l1-epoch-governance-v1` observer and `epoch-governance` state machine with
  consensus-critical proposal/DRep flags and a fixed DRep chunk size;
- made proposal and DRep completeness independent: proposal lifecycle is ingested one record per block, while
  DRep voting stake uses bounded 25,000-entry chunks and the same complete-only query discipline as epoch stake;
- added canonical header, proposal, DRep chunk, metadata, state-key, query, and value contracts plus a typed
  `EpochGovernanceClient`;
- registered both contributions for plugin-catalog and legacy ServiceLoader discovery and exposed separate proof
  subjects for proposal status and DRep amount.

Review and iteration:

- the rollback audit found that the pre-existing `storeDRepDistEntry` path wrote directly to a boundary batch
  without a `DeltaOp`; rollback/replay could therefore retain voting power from a reverted boundary. The write and
  empty-snapshot marker are now journaled atomically with the governance ratification phase;
- the host persists lifecycle snapshots during boundary processing instead of asking the observer to infer history
  from active proposals. Enacted and expired proposals retain their terminal state; superseded siblings and
  invalidated descendants retain closed dropped reasons;
- terminal transitions are journaled during governance phase 1 without publishing snapshot completeness, then
  folded into the phase-2 snapshot. A crash after enactment therefore cannot erase the history needed by the
  ratification retry after the active proposal record has already been removed;
- proposal-only mode is structurally lazy: it neither checks DRep snapshot availability nor invokes the DRep
  iterator. A source double that throws on any DRep access protects this cost/isolation contract;
- both datasets persist a global cursor and recompute their committed roots before setting completeness, closing
  cross-block reorder, missing-record, fabricated-record, and partial-snapshot paths;
- zero-entry datasets are complete at header application only when the host supplied an explicit persisted
  snapshot marker; absence of a source snapshot remains a generation failure.

Verification:

- real-RocksDB rollback/replay restores proposal records, DRep entries, and both snapshot markers exactly;
- the historical view proves canonical proposal order, explicit empty DRep presence, snapshot isolation, and
  close guards;
- governance observer/state-machine tests cover proposal-only zero-DRep access, independent completeness,
  complete-only queries, rejected missing/reordered/fabricated claims, and deterministic restart/snapshot/replay
  through `StateMachineConformance`;
- canonical contract round trips, malformed/tag/order rejection, and released MPF key/value envelope limits pass;
- complete ledger-state, stdlib-contract, stdlib, client, runtime, and plugin-catalog suites pass.

M8 must compare the generated proposal and DRep roots/claims with Yaci Store, Koios, and the available DBSync
datasets after the refreshed preprod nodes have crossed a boundary with this persistence format. This report does
not substitute comparisons between external sources for comparison of the deployed attestation itself.

## M7 — typed Cardano History proof surface

Status: complete

Implemented:

- replaced the preview typed-history key helpers with the exact ASCII keys written by the params, stake, and
  governance state machines, including independent stake/proposal/DRep completeness subjects;
- added strict protocol-parameter value validation, typed stake `[coin,poolHash]`, proposal lifecycle, and DRep
  amount decoders plus exact physical composite-key binding;
- added portable semantic proof bundles for stake minimum/pool/exact/absence, exact proposal status/reason, and
  DRep minimum/exact/absence claims; every negative or aggregate claim requires a same-root complete metadata
  proof;
- added an anchor adapter that derives the trusted state identity from an independently selected eleven-field
  Cardano anchor output and rejects chain/application/genesis/profile/fingerprint mismatches;
- added an exact-height `CardanoHistoryProofClient` that obtains the fact and completeness proofs through the
  generic ADR-031 endpoint rather than introducing epoch-specific core REST routes;
- expanded the compiled Cardano validator to consume the unique thread-token anchor reference input directly,
  verify its script address and commitment identity, then evaluate stake, proposal, or DRep proof pairs under the
  anchored MPF root;
- kept the same typed off-chain bundles profile-neutral. A chain created with `jmt-blake2b256-v1` verifies the
  identical semantic subjects using classic-JMT wire proofs, while capability discovery advertises its
  verification target as off-chain only.

Review and iteration:

- the first typed helpers encoded epochs/credentials in a private binary format that no state machine actually
  wrote. Exact contract-key delegation removed that latent proof API defect;
- a first semantic Cardano validator accepted a root as its spending datum. That proved pair correctness but did
  not authenticate the root. The released reference now obtains the root only from exactly one expected anchor
  reference input;
- a first wrapper tried to reuse another Julc validator's nested redeemer type. Julc correctly rejected that
  cross-validator source type. Anchor consumption and semantic verification now live in one independently
  compilable validator instead of maintaining duplicated MPF algorithms;
- classic JMT is selected only in the immutable state-commitment genesis identity; no parallel
  Cardano-History-specific profile switch was added.

Verification:

- real MPF fixtures verify every typed params/stake/proposal/DRep claim and real non-membership claims against one
  `AnchorDatumV1`, and reject wrong root or commitment identity;
- a real classic-JMT fixture verifies the same typed stake and absence semantics against a JMT-tagged Cardano
  anchor entirely off-chain;
- the compiled Cardano validator consumes the anchor reference input, passes all stake predicates, absence,
  proposal status/reason, and DRep minimum/exact checks within the pinned Cardano transaction limits, and rejects
  missing or wrong-root anchors;
- the full 1.3M JMT and pruned-MPF benchmark results above provide persistent proof/restart evidence for both
  selectable profiles.
