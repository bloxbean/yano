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
