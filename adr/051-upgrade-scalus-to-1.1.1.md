# ADR-051: Upgrade Scalus to 1.1.1 Without Regressing Ledger Admission

## Status

Accepted

## Date

2026-08-31

## Related decisions and evidence

- [Issue #106](https://github.com/bloxbean/yano/issues/106) requests the
  upgrade from Scalus `0.18.2` to `1.1.1` and defines the acceptance gates.
- [Issue #62](https://github.com/bloxbean/yano/issues/62) and
  [ADR-050](050-complete-live-stake-pool-retirement.md) define the pool
  lifecycle behavior that the new Scalus version must continue to admit.
  The issue #62 branch is merged into `main` at `e2566ade`.
- [The ADR-050 upstream report](reports/adr-050-scalus-pool-deposit-upstream-report.md)
  records the value-conservation gap and the expected reference-ledger
  behavior for active pool updates and duplicate registrations.
- `gradle/libs.versions.toml` pins both
  `scalus-cardano-ledger_3` and
  `scalus-bloxbean-cardano-client-lib_3` to `1.1.1`.
- `YanoCardanoMutator` replaces Scalus's
  `ValueNotConservedUTxOValidator` while retaining the remainder of
  `CardanoMutator.defaultSTSs()` in deterministic name order.
- [ADR-051 Phase 0 evidence](reports/adr-051-phase-0-2026-08-31.md) records the
  resolved Scalus `1.1.1` jar identity and direct signature inspection of
  `TxBalance.produced` and `TxBalance.consumed`. This evidence decides whether
  Yano ports or deletes the pool-deposit override.

## Decision summary

Yano will upgrade both Scalus artifacts together to `1.1.1`. The upgrade is an
adapter migration, not a ledger-semantics change.

1. Both direct Scalus coordinates will be pinned to exactly `1.1.1` in one
   change. The resolved runtime graph must select `1.1.1` for the direct ledger
   and CCL adapter artifacts and transitive `org.scalus:scalus_3`. Yano's
   separately managed `cardano-client-lib` version must not move as a side
   effect.
2. The `scalus-bridge` adapters will be changed only where the Scalus 1.x API
   requires it. Transaction decoding, protocol-parameter conversion,
   certificate-state construction, script supply, evaluation and validation
   must retain their current Yano contracts.
3. Because `1.1.1` still lacks certificate state in its produced-value
   calculation, Yano will port `YanoCardanoMutator` and
   `YanoValueNotConservedUTxOValidator` to the 1.1.1 rule API. The override must
   not be deleted merely because the major-version upgrade compiles.
4. The port must be derived from the 1.1.1
   `CardanoMutator.defaultSTSs()` at build and test time. It replaces exactly
   one default validator, preserves every other validator and mutator, and
   executes each name-sorted group deterministically.
5. The override remains removable. A future Scalus release may replace it only
   after a behavioral test proves that active pool updates, duplicate new-pool
   registrations and mixed active/new registrations conserve value without
   Yano's validator.
6. Acceptance requires serialized fresh-worktree tests, the complete devnet
   pool-lifecycle matrix, a release-parity Oracle GraalVM native build and
   native execution probes, and Plutus datum/redeemer regressions from both
   off-chain suites named in issue #106.

This decision does not change chainstate formats, public APIs, configuration,
pool lifecycle state, transaction ordering or protocol parameters.

## Context

### Why this is more than a dependency edit

The version jump crosses the Scalus `1.0` boundary. Yano directly uses Scalus
ledger models and rule APIs from mixed Scala and Java sources in
`scalus-bridge`, including:

- transaction CBOR decoding and CCL interop;
- `ProtocolParams`, `SlotConfig`, UTxO and script-supplier adapters;
- construction of transaction-local `CertState` from Yano's ledger provider;
- `CardanoMutator`, `STS`, `Context`, `State` and transaction exceptions;
- script evaluation and validation; and
- native JNI linkage for BLS builtins.

A successful compilation proves only source compatibility. It does not prove
that the resolved rule set, value-conservation behavior, Plutus data encoding,
execution budgets or native-image reachability are unchanged.

### Pool-deposit correctness must survive the upgrade

Scalus computes consumed value with `CertState` but computes produced value
without it. Its certificate deposit calculation consequently charges
`stakePoolDeposit` for every `PoolRegistration`, including an update to an
already active pool. Cardano charges a deposit only for a distinct pool ID not
active at transaction start.

The `1.1.1` bytecode makes the asymmetry precise. Consumed-side staking and
DRep refunds call private helpers `lookupStakingDeposit$1(CertState,
Credential)` and `lookupDRepDeposit$1(CertState, Credential)`. Produced-side
certificate deposits call private
`conwayTotalDepositsTxCerts(Transaction, ProtocolParams)`, which receives no
certificate or pool state. The upstream repair is therefore to thread the
transaction-start pool set into `conwayTotalDepositsTxCerts` and its
`produced` caller, matching the state-aware refund design.

Yano currently corrects the excess produced value by subtracting one pool
deposit for:

- every registration of a transaction-start active pool; and
- every duplicate registration after the first registration of one new pool
  in the same transaction.

Pool retirement certificates in that transaction do not change
transaction-start membership. Both `RetirePool -> RegPool` and
`RegPool -> RetirePool` therefore treat the registration as an active-pool
update when the pool was active at transaction start.

Phase 0 inspects the exact `1.1.1` jar selected by this branch before deciding
whether to port the correction. If that jar retains the API shape that caused
the gap, ADR-051 ports the correction and strengthens its coupling guard. If it
accepts transaction-start pool or certificate state, implementation stops and
the unmodified upstream rule set is tested before the override is deleted.

### Native behavior is a separate compatibility surface

The Scalus bridge includes BLS JNI configuration and is part of the native
application. Repository CI does not execute the resulting native binary for
this scenario. A native image that links successfully can still fail when an
evaluator, JNI symbol, resource or reflection path is first exercised.

The native gate must therefore evaluate and submit real transactions. A health
check or `ldd` result alone is insufficient.

## Decision drivers

- Consume the supported Scalus 1.x line without carrying an obsolete 0.x
  dependency.
- Preserve Cardano value conservation for active pool updates.
- Detect upstream rule-set drift instead of silently dropping or duplicating a
  rule.
- Preserve deterministic rule execution and stable diagnostics.
- Exercise semantic and native paths that compilation and JVM unit tests do
  not cover.
- Keep the migration reversible until every gate passes.
- Avoid unrelated ledger, API and chainstate changes in the dependency-upgrade
  branch.

## Invariants

1. `scalus-cardano-ledger_3`, `scalus-bloxbean-cardano-client-lib_3` and
   transitive `scalus_3` resolve to the same exact version, `1.1.1`.
2. No Scalus `0.x` artifact remains in the application runtime graph, and the
   upgrade does not change Yano's selected `cardano-client-lib` version.
3. Yano's effective validator set differs from Scalus 1.1.1 defaults by
   exactly one entry: the upstream value-conservation validator is removed and
   Yano's same-named validator is added.
4. Validator and mutator counts otherwise match the 1.1.1 default set, and
   each group executes in ascending rule-name order.
5. An active pool update pays no new pool deposit.
6. A new pool lifecycle pays exactly one pool deposit, even if its registration
   appears more than once in a transaction.
7. Mixed active and new registrations charge only the new pools.
8. Certificate order does not alter transaction-start pool membership for the
   deposit calculation.
9. All non-pool value-conservation behavior and error identity remain Scalus
   default behavior.
10. Script evaluation retains the expected datum/redeemer interpretation and
    execution-budget behavior for the Mesh and Evolution vesting suites.
11. JVM and native evaluators accept and reject the same regression fixtures.
12. The upgrade introduces no chainstate migration or runtime configuration
    change.

## Detailed decision

### 1. Pin and verify one Scalus version

Change both aliases in `gradle/libs.versions.toml` from `0.18.2` to `1.1.1` in
one commit. Do not temporarily mix 0.x and 1.x artifacts to work around
compilation failures.

Before and after the bump, record Gradle dependency-insight evidence on the
application runtime classpath for `scalus-cardano-ledger_3`,
`scalus-bloxbean-cardano-client-lib_3`, `scalus_3` and
`cardano-client-lib`. The gate is one selected Scalus version, `1.1.1`, with no
forced downgrade, duplicate 0.x module or locally substituted unpublished jar,
while Yano's own CCL version remains unchanged.

Scalus `1.1.1` retains Scala `3.3.8`, `cardano-client-lib` `0.7.2`, Scribe
`3.19.0`, Monocle `3.3.0` and Commons Compress `1.28.0`. It moves Borer core and
derivation from `1.16.2` to `1.17.0`, Magnolia from `1.3.21` to `1.3.23`, and
STTP client4 core from `4.0.25` to `4.0.26`. The Scala pin must not be bumped to
paper over a bridge compilation failure. The Borer change is treated as a CBOR
compatibility risk and requires a real-transaction decode/encode round-trip
adapter test.

### 2. Adapt at the bridge boundary

Inventory compile failures before editing. Apply compatibility changes in the
narrowest bridge component responsible for each changed Scalus type or method:

- `ProtocolParamsBridge` for ledger parameter model changes;
- `UtxoBridge`, `Types` and `CertStateBridge` for ledger model changes;
- `LedgerBridge` and the Java evaluator/validator classes for rule and
  evaluator entry-point changes; and
- `SlotConfigAdapters` and `ScalusScriptSupplier` for CCL adapter changes.

Do not leak Scalus-specific 1.x types into `core-api`, `ledger-rules` or public
Yano APIs to make the bridge compile. Do not change transaction semantics to
match a surprising new default without first documenting and testing that
semantic change.

### 3. Re-derive the pool-deposit override

Port the override from the actual `1.1.1` rule types and default rule
collection. The construction must:

1. obtain all default STSs from `CardanoMutator.defaultSTSs()`;
2. separate validators and mutators;
3. require the upstream `ValueNotConservedUTxOValidator` identity to occur
   exactly once;
4. replace that entry with `YanoValueNotConservedUTxOValidator`;
5. preserve every other object identity; and
6. sort validators and mutators independently by rule name before transit.

The guard test must compare the default and effective sets in both directions,
verify the exactly-one-entry difference, verify counts, verify name order and
verify that the replacement retains the upstream rule name for stable
diagnostics.

The semantic tests must cover active update, one new pool, duplicate new-pool
registration, mixed active/new registrations and both retirement orders. At
least one test must exercise complete value-conservation validation rather
than only the correction helper.

If inspection during implementation contradicts the recorded 1.1.1 API
evidence and proves that upstream now consumes transaction-start pool state,
stop and record the evidence. Delete the override only after the same semantic
tests pass against the unmodified Scalus rule set.

### 4. Preserve evaluation and interop behavior

Adapter tests must pin any 1.x representation change that can affect:

- protocol versions, prices, cost models and execution-unit limits;
- slot zero time, zero slot, slot length and units;
- transaction input/output conversion and inline datum handling;
- reference scripts and script lookup;
- certificate credentials, pool maps, rewards, deposits and DRep state;
- evaluator success/failure mapping and execution budgets; and
- transaction validation exception mapping.

Where 1.x adds fields, defaults must be justified from the Cardano protocol
model or the existing Yano contract. Nulls, zero values and placeholder casts
must not be introduced merely to satisfy a constructor.

`scalus.cardano.ledger.ProtocolParams` is constructed with 31 positional
arguments. Before correction commit `c1d5cf53`, Yano had zero assertions for
the execution-unit-limit mapping inside that constructor. The class-wide
`everyProtocolParameterMapsToItsOwnField` guard now supplies distinct source
values and asserts all 31 target fields, including every nested component, so
future argument transpositions fail locally. Its expected values also differ
from the bridge's hardcoded defaults, usually by one, so a source field that
silently falls back to a default fails the same guard.

### 5. Use layered, serialized acceptance gates

Run the following gates serially from a fresh worktree at the exact candidate
commit. Do not overlap Gradle daemons, devnet processes or native builds when
collecting acceptance evidence.

Exactly one Yano JVM or native node process may run at any time across the
whole repository, whether or not processes use different chainstates. Codex
owns every node start, stop and restart. The coordinator runs only module tests
from a detached worktree at Codex's exact commit and never starts a node. Every
running-node gate records its explicit data directory.

Baseline and candidate node-backed comparisons must use the stock
`app/config/network/devnet/` genesis set used by the retained off-chain
baselines, not the separate `devnet/pv10/` set. The effective protocol major
version must be `11`, and the Plutus V1, V2, and V3 cost models must contain
`332`, `332`, and `350` entries respectively. The genesis root, protocol major
version, and all three cost-model cardinalities are must-not-change values
across the baseline and candidate runs. A profile with the same network magic,
epoch length, slot length, and active-slots coefficient is not equivalent when
its protocol version or cost models differ.

Before every node start, run a repository-wide process check with
`pgrep -fl "yano.jar|/yano( |$)"` in addition to the gate's port and lock
checks. Every long-running node must start detached in its own process group,
either under a launch supervisor or a `setsid`/`nohup` equivalent, redirect to
an identified log, write an identified pidfile, and remain alive after an
unrelated tool call. Its working directory must be explicitly set to the
pinned gate working directory and verified with `lsof -a -p <pid> -d cwd -Fn`.
This requirement applies to baseline, candidate, Gate C, and Gate E launches.
Do not rely on the agent or supervisor's inherited working directory.

Treat the relative `history/` archive as run state alongside `chainstate/`.
The configured chainstate leaf and the working-directory-relative archive path
must both be absent before a baseline or candidate start, and both must be
independently fresh for the two comparison runs. Record their absence in
preflight evidence; carried-over chainstate or archive state invalidates
parity.

Before baseline or candidate throughput measurement, record `uptime` and the
top five CPU consumers and verify that no competing build or other heavy job is
running. Record the same evidence at the end. A contended run may provide
functional and CPU-independent evaluator evidence, but its throughput, latency
and wall-clock figures are not valid comparison data. Baseline and candidate
must use this identical quiet-machine precondition.

#### Gate A — bridge and application JVM tests

1. `./gradlew :scalus-bridge:test`
2. `./gradlew :app:integrationTest`

Both commands must exit zero. Record the commit, JDK, Gradle version, commands,
test totals and report paths.

#### Gate B — pool-lifecycle devnet matrix

Run `PoolLifecycleE2ETest` and retain evidence for all scenarios A-F:

- pending retirement cancelled by a later-block update;
- effective retirement and same-epoch new lifecycle;
- effective retirement and delayed new lifecycle;
- same-transaction retirement then registration;
- same-transaction registration then retirement; and
- same-block, different-transaction ordering in both directions.

The gate requires real transaction submission through the running node and no
spurious `ValueNotConservedUTxO` failure. Helper-only tests do not satisfy it.

#### Gate C — release-parity native image and smoke

Build with Oracle GraalVM 25.3 and `--gc=G1`, using the repository's documented
release-parity native build configuration. Record the builder image digest,
candidate commit, native binary path and SHA-256, effective GC and all runtime
settings.

Before the native smoke submits or evaluates a script, verify from the native
node's runtime log or API that the effective protocol major version is `11`
and the Plutus V3 cost model contains `350` entries. Record both observed
values in the gate report; naming an intended genesis path is not sufficient.

The native smoke must start the built binary, reach readiness and execute both:

- the BLS lock/evaluate/submit/confirm probe at
  `/Users/satya/Downloads/yano-ccl-test/ccl-client`, including its negative
  control; and
- submission of the evaluated transaction through the native node.

Both operations must succeed without missing JNI symbols, unsupported-feature
errors, reflection/resource failures or value-conservation errors. The exact
transaction hash and log evidence are retained.

#### Gate D — Plutus vesting regressions

Run steps 3 and 5 of
`/Users/satya/Downloads/yano-ccl-test/run-suite.sh <label> <api-url> <node-pid>`
against the accepted devnet configuration: MeshJS vesting datum/redeemer and
Evolution vesting. Each suite must evaluate and submit its lock/unlock path,
and the on-chain outcome must be observed. Record the external suite repository
identity because the harness is not stored in this worktree.

For every case, retain the transaction CBOR or fixture identity, evaluator
result, execution units, submission hash and confirmation evidence. Compare
execution units with the `0.18.2` baseline; any difference must be explained
before acceptance, even when both transactions succeed.

#### Gate E — off-chain SDK load suite

Run all five steps of
`/Users/satya/Downloads/yano-ccl-test/run-suite.sh <label> <api-url> <node-pid>`:
CCL load, MeshJS load, MeshJS vesting, Evolution load and Evolution vesting.
Retain the `results-<label>/` directory with per-step logs, `timing.txt`,
`process.txt` and mempool metric snapshots. Record submitted and confirmed
counts, error rate and throughput. Compare the candidate only with the clean
same-rig `0.18.2` baseline at `e2566ade` in
`results-issue106-baseline-e2566ade-clean`; historical runs corroborate that
the baseline is sane but do not define the candidate comparison band. The
clean baseline accepted 45,645/45,645 regular CCL transactions at 253.0 tx/s
and 9,504/9,504 chained transactions with 100% full-depth chains. A candidate
drop below 100% CCL full depth or any other regression must be explained before
acceptance. MeshJS chained-input failures remain a documented SDK limitation
and are reported separately rather than treated as the CCL parity signal.

The load suite passed. Satya decided at 20:50 +08 on 2026-08-31 to skip
`test-native-haskell-sync` for this PR. This is an explicit scope decision, not
an unexecuted acceptance item:

1. the issue-106 product diff changes the Scalus bridge and dependency pin,
   not block production, block CBOR, network synchronization or consensus;
2. the sync skill's blocks contain simple payments, so it has low power to
   detect a Scalus-specific evaluator regression;
3. Gate C exercises the same native binary more directly by evaluating,
   submitting and confirming a BLS transaction with exact JVM/native execution-
   unit parity;
4. the sync skill would require the same projection-disabled deviation as Gate
   C because of the pre-existing native provider reachability defect; and
5. a realistic Haskell takeover with the expanded cost models requires a
   separate governance-action bootstrap scenario rather than substituting the
   pre-release cardano-node 11.1.0 configuration.

The execution-unit correction also does not require mainnet or preprod replay
for this PR. It replaces an incorrectly permissive swapped limit with the true
dimensional limits. Transactions already admitted to a real Cardano chain were
validated against those true limits by cardano-node, so the correction cannot
reject a previously valid on-chain transaction. It can only reject the
over-budget transactions that affected Yano versions could previously
over-accept.

## Implementation plan

### Phase 0 — Baseline and API inventory

- Run the current `0.18.2` bridge tests and capture their totals.
- Run the complete off-chain suite on the same rig at `main` commit `e2566ade`
  and retain its `results-<label>` directory as the `0.18.2` throughput,
  error-rate, vesting and execution-unit baseline.
- Capture dependency insight for all three Scalus coordinates and Yano's CCL
  coordinate before the bump.
- Resolve and inspect the exact `1.1.1` ledger jar, record its SHA-256 and
  `javap` output for the produced/consumed signatures, and inventory the
  default-STS API.
- Record that Scala remains `3.3.8`; do not change the Scala pin in response to
  migration errors.
- Record an API migration inventory before changing adapters.

### Phase 1 — Dependency and bridge migration

- Pin both artifacts to `1.1.1`.
- Make the minimum source changes needed for `scalus-bridge` compilation.
- Add or update focused adapter tests for each changed API assumption.
- Record dependency-insight output proving a single Scalus version.

### Phase 2 — Pool-deposit override re-derivation

- Rebuild the effective rule set from the 1.1.1 defaults.
- Port the value-conservation correction.
- Strengthen the exactly-one-entry, identity and deterministic-order guards.
- Run the pool-deposit semantic matrix at unit level.

### Phase 3 — Serialized JVM and devnet validation

- Run `:scalus-bridge:test` and `:app:integrationTest` in a fresh worktree.
- Run the complete A-F pool lifecycle matrix against a clean devnet.
- Retain commands, reports, node logs and transaction hashes.

### Phase 4 — Native and Plutus validation

- Produce the Oracle GraalVM 25.3 G1 native image from the candidate commit.
- Execute the BLS lock/evaluate/submit/confirm probe with that exact binary.
- Run Mesh and Evolution vesting datum/redeemer suites.
- Compare JVM/native results and pre-/post-upgrade execution units.

### Phase 5 — Review and acceptance

- Review the exact candidate diff and resolved dependency graph.
- Consolidate phase evidence under `adr/reports/`.
- Change this ADR to Accepted only when every acceptance criterion is backed by
  a command, result, commit and evidence path.

## Acceptance criteria

- Both direct Scalus artifacts and transitive `scalus_3` resolve to `1.1.1`;
  Yano's selected `cardano-client-lib` version is unchanged.
- `scalus-bridge` compiles without spreading Scalus API types into unrelated
  modules.
- The pool-deposit override is re-derived from 1.1.1 defaults and remains in
  place while the produced-value calculation lacks transaction-start pool
  state.
- The rule-set guard proves an exactly-one-entry replacement and deterministic
  name ordering.
- Pool-deposit semantic tests pass for active, new, duplicate, mixed and both
  retirement-order cases.
- Fresh-worktree `:scalus-bridge:test` and `:app:integrationTest` runs pass
  serially.
- All A-F running-devnet pool lifecycle scenarios pass.
- The Oracle GraalVM 25.3 G1 native image builds and reaches readiness.
- Native BLS evaluation and submission both pass with transaction/log evidence.
- Mesh and Evolution vesting datum/redeemer regressions both pass against
  devnet, with execution-unit comparison evidence.
- The decision to skip `test-native-haskell-sync` is recorded with its scope
  rationale and is not represented as executed coverage.
- The five-step off-chain SDK suite passes with submitted/confirmed counts,
  error rate and throughput compared with the same-rig `0.18.2` baseline.
- No unexplained semantic, execution-budget, native linkage or dependency-graph
  difference remains.
- No chainstate format, public API or runtime configuration change is included.

## Alternatives considered

### Upgrade only the ledger artifact

Rejected. Mixing the ledger and CCL adapter versions creates an unreviewable
binary-compatibility surface and violates the single-version invariant.

### Delete the override because 1.1.1 is a major upgrade

Rejected. The 1.1.1 produced-value API still lacks the state required to
distinguish active pool updates. Compilation is not evidence of correct value
conservation.

### Copy the 0.18.2 default rule list into Yano

Rejected. A copied list can silently omit rules introduced by Scalus 1.x. The
effective set must be derived from the installed version and guarded by tests.

### Accept JVM tests without executing a native binary

Rejected. BLS JNI linkage, reachability metadata and runtime evaluator paths
are native-specific, and the relevant native binary is not exercised by CI.

### Run only one off-chain vesting client

Rejected. The Mesh and Evolution suites provide independent datum/redeemer and
transaction-construction coverage required by issue #106.

### Combine unrelated ledger cleanup with the upgrade

Rejected. A narrow dependency migration is easier to review, revert and
attribute when a semantic or native regression appears.

## Consequences

### Positive

- Yano moves to the supported Scalus 1.x line.
- Pool update admission remains aligned with Cardano ledger semantics.
- Rule-set drift becomes a visible test failure.
- Scalus 1.1.1's dimensional execution-unit limit check surfaced a latent Yano
  memory/steps mapping defect that the 0.18.2 total ordering had hidden. That
  ordering was lexicographic: it compared memory first and compared steps only
  when memory was equal, so the shipped swapped values could over-accept a
  transaction whose true memory budget exceeded the limit. The
  over-acceptance defect affects releases `v0.1.0-pre10` through
  `v0.1.0-pre13` and is corrected by unreleased commit `c1d5cf53`.
- Native and off-chain evaluation compatibility are demonstrated with executed
  transactions rather than inferred from compilation.

### Negative

- Yano continues to carry a small upstream override until Scalus exposes the
  required certificate state in produced-value calculation.
- Acceptance requires slow, environment-dependent native and devnet gates.
- External vesting harness identities and results must be retained alongside
  repository test reports.
- Override retirement must be tracked in a follow-up issue whose removal gate
  is the semantic test matrix in Detailed decision section 3; it must not be
  deleted or left as an unowned parity oracle.

### Risks and mitigations

- **Hidden transitive version skew:** inspect all three Scalus coordinates and
  Yano's CCL selection before and after, failing acceptance on Scalus 0.x or an
  unintended CCL change.
- **Default rule drift:** derive from 1.1.1 defaults and enforce exact identity,
  count and ordering guards.
- **Behavioral change masked by successful tests:** compare execution units and
  run real evaluate/submit paths from two off-chain clients.
- **Native-only failure:** build with release-parity Oracle GraalVM and execute
  BLS through the resulting binary.
- **Contaminated evidence:** run gates serially in a fresh worktree at a named
  commit and retain logs outside build directories that subsequent tasks may
  replace.

## Rollback plan

Before merge, revert the dependency and bridge commits together. Do not retain
a partial adapter migration or mixed Scalus versions. A rollback returns both
coordinates to `0.18.2` and keeps the existing pool-deposit override.

After merge, use the same atomic version/adapter revert if a production-blocking
regression appears. No chainstate rollback or migration is required because
this ADR changes transaction validation and evaluation code only.

## Accepted review decisions

1. Accept the atomic `1.1.1` pin and narrow bridge-only migration boundary.
2. Accept retaining and re-deriving the pool-deposit override for Scalus 1.1.1.
3. Accept the serialized JVM, A-F devnet, Oracle GraalVM native/BLS and
   five-step off-chain SDK gates, with native Haskell sync explicitly deferred
   as recorded above.
