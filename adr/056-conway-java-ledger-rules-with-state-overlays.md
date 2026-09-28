# ADR-056: Complete Conway Ledger Rules in Java with State Overlays

## Status

Accepted (2026-09-28). The design was reviewed by Fable and in four Codex
review rounds on PR #155; Codex approved it at `40dfd3168`. Satya accepted it
with the decisions recorded at the end of this ADR.

## Date

2026-09-28

## Related decisions and evidence

- [ADR-057](057-optional-amaru-wasm-transaction-validator.md) adds an optional
  validator built from Pragma's Amaru ledger compiled to WebAssembly. It shares
  the validation API, ledger view and overlays defined here, and it is the
  differential oracle for this ADR's conformance gates.
- [ADR-051](051-upgrade-scalus-to-1.1.1.md) upgraded Scalus to `1.1.1`. That
  upgrade, and the permanent `YanoValueNotConservedUTxOValidator` override, show
  what it costs to keep ledger admission correct through a third-party rule set.
- [ledger-state ADR-029](ledger-state/029-dynamic-transaction-validation-context.md)
  and ADR-024 (epoch-effective protocol parameters for validation, not in this
  repository) define how validation gets slot-effective parameters today.
- The 2026-09-25 research (not committed; summarised here):
  - *Estimate, from bytecode and source inspection:* Scalus `1.1.1` enforces
    about **57 of 88** leaf Conway predicate failures of the Haskell ledger.
  - It enforces **none of the 19 `ConwayGovPredFailure` constructors**, and not
    `DelegateeDRepNotRegistered`, `DelegateeStakePoolNotRegistered`,
    `ConwayWdrlNotDelegatedToDRep` or `ConwayTreasuryValueMismatch`.
  - Scalus's ledger `State` has no governance state and no treasury (the bridge
    passes an empty `govState`, `scalus-bridge/.../LedgerBridge.scala:104-109`).
  - Amaru (commit `d72e9b5`, 2026-09-25) ships 276 Haskell-cross-checked JSON
    transaction scenarios and consumes the cardano-blueprint conformance vectors.
- **Measured baseline** (Phase 2, 2026-09-29; the full report is
  [`ledger-conformance/docs/baseline-2026-09.md`](../ledger-conformance/docs/baseline-2026-09.md)).
  The catalogue has **88** Conway leaf constructors at cardano-ledger
  `f649f975`, **85** of them reachable at PV 10–11. Over Amaru's 276 scenarios
  and 16 mutants (verdict / first-failure constructor; where Haskell always
  reports a fault with several constructors, any of them counts), after the
  Scalus engine fixes of Phase 2:

  | Engine | Scenarios: verdict | Scenarios: constructor | Mutants | Constructors demonstrated |
  |---|---:|---:|---:|---:|
  | `scalus-legacy` (today's admission path) | 170/276 | 102/276 | 16/16 | 22/85 |
  | `scalus-legacy` + supplementary rules | 208/276 | 138/276 | 16/16 | 31/85 |
  | `scalus` engine (step 1d, over the view) | 193/276 | 184/276 | 16/16 | 35/85 |
  | copied Java rules (`LedgerStateValidator`) | 158/276 | 31/276 (117 anywhere in the list) | 7/16 | 19/85 |
  | `amaru` (reference) | 276/276 | 275/276 (+1 PV 9 `EraNotSupported`) | 16/16 | 56/85 |

  The scenarios and mutants exercise 56 of the 85 constructors; the Scalus
  engine reports 35 of those 56 as Haskell does, so the estimate above held
  roughly for what Scalus checks, but the legacy path loses much of it to
  decoding and error naming (Phase 2 results below).
- Haskell `cardano-ledger` is the source of truth for rule semantics. Amaru and
  Scalus are references and oracles only. Where they disagree with Haskell,
  Haskell wins, and Yano records the divergence.
- **Pinned Haskell revisions** (Phase 1, 2026-09-28; details in
  [adr-056-haskell-pinned-revisions](reports/adr-056-haskell-pinned-revisions.md)):
  - **cardano-node 11.1.2** (`fef83fed`), the current release;
  - **cardano-ledger `f649f975`** (cardano-ledger-conway 1.23.0.0; shelley
    1.19.0.1 at `4c81e909`);
  - **ouroboros-consensus 4.2.1.0 (`82ecba32`)**.

  Every Haskell reference in this ADR is re-checked against these revisions
  and recorded in `ledger-rules/docs/conway-rule-coverage.md`. The
  release-QA node (11.0.1) has identical Conway predicate failures, PV gates
  and check order; only the `IsValid` → `IsPhase2Valid` rename differs.

## Decision summary

1. **One Java rules module.** Rename `ccl-ledger-rules` to `ledger-rules`, fold
   it into the existing `ledger-rules` API module, and move the code from the
   copied `com.bloxbean.cardano.client.ledger` package, and the existing
   `org.yanoproject.ledgerrules` API package, to one package root:
   **`org.yanoproject.ledger.rules`** (layout in §1). No `com.bloxbean` package
   remains in Yano's ledger-rules code. The Aiken and julc script evaluators move
   to a new `script-evaluators` module so `ledger-rules` stays pure Java.
2. **A complete Conway transaction transition.** Implement Conway `LEDGER`, plus
   the `MEMPOOL` rule for admission, as one ordered transition that mirrors the
   Haskell composition. Each leaf predicate failure maps to a typed failure
   named after the Haskell constructor.
   - **Scope:** Conway, **protocol version 10 and later**, like Amaru.
   - The PV9 Conway bootstrap phase is out of scope.
3. **Ledger view plus overlays.** Validation reads state through a read-only
   `LedgerView`. The base view is canonical state **ticked to the validation
   slot**, so an epoch boundary between the tip and that slot is applied first.
   An `OverlayLedgerView` stacks the effects of earlier transactions on top:
   - **Intra-transaction:** certificates apply in order. UTXOW still sees
     pre-certificate state, as in Haskell.
   - **Mempool:** a new transaction sees every transaction admitted before it.
   - **Block production:** a candidate sees every transaction selected before
     it in the same block.
4. **Phase-2 stays with Scalus.** Plutus execution during validation goes
   through a `ScriptPhaseEvaluator` SPI backed by Scalus. ExUnits evaluation
   (`TransactionEvaluator`, `/utils/txs/evaluate`) is unchanged.
5. **Choosable engine, safe rollout.**
   - `yano.validation.engine` selects the admission engine: `scalus` (the
     default until the gates pass), `java`, or `amaru` (ADR-057, optional).
   - `yano.validation.shadow-engines` runs other engines on immutable snapshots
     and records disagreements without affecting admission.
6. **Oracle-driven completeness.** Completeness is measured, not claimed. The
   gates use:
   - a per-constructor coverage matrix;
   - Amaru's scenarios;
   - cardano-blueprint vectors;
   - differential runs against Amaru-wasm;
   - shadow validation of synced PV10+ blocks;
   - a mutation matrix;
   - JVM/native parity.
7. **One PR, reviewed phase by phase.** The work lands as one PR (#155) from
   `feat/conway-ledger-rules`. Each logical step or phase is implemented,
   reviewed by independent sub-agent reviewers (Fable), fixed, tested, then
   committed and pushed to the PR (see Implementation plan).

## Context

### What runs today

- **Admission.** `DefaultMemPool.tryAdmit`
  (`runtime/.../chain/DefaultMemPool.java:62`) validates through
  `effectiveValidator.validate(ownedTxBytes, txHash, resolver)` (`:154-160`).
  `TransactionValidationService.validate`
  (`runtime/.../blockproducer/TransactionValidationService.java`) resolves
  regular, reference and collateral inputs through that resolver, then calls
  `TransactionValidator.validate(byte[] txCbor, Set<Utxo> inputUtxos)`
  (`ledger-rules/.../TransactionValidator.java`).
- **Engine.** `DefaultTransactionServicesFactory`
  (`tx-services/.../DefaultTransactionServicesFactory.java:113`) always creates
  `ScalusBasedTransactionValidator`
  (`scalus-bridge/.../ScalusBasedTransactionValidator.java:142`).
  - `LedgerBridge` → `YanoCardanoMutator.transit`
    (`scalus-bridge/.../YanoCardanoMutator.scala:31-34`) runs phase-1 and
    phase-2.
  - Account, pool and DRep state comes from the canonical
    `LedgerStateProvider`, via `CertStateBridge.build`
    (`scalus-bridge/.../CertStateBridge.scala:27`).
- **Supplementary rules are off.** The two CCL rules
  (`CertificateValidationRule`, `GovernanceValidationRule`) run only when
  `yano.validation.supplementary-rules-enabled=true`.
  `app/src/main/resources/application.yml:336-341` defaults it to `false`,
  because they "don't yet account for intra-tx state changes within a single
  block".
- **Lossy error phases.** Phase labelling is derived from the Scala exception
  class name (`ScalusBasedTransactionValidator.java:322`). Phase-1 failures whose
  class names contain "Script" are reported as phase-2.

### Overlays exist only for UTxOs

- **Mempool.** `DefaultMemPool` keeps `producedByOutpoint`/`spentByOutpoint`
  (`DefaultMemPool.java:40-41`). Its `resolve` (`:849-856`) lets a transaction
  spend outputs of earlier mempool transactions.
  - After a canonical rollback, `TxSubsystem.onCanonicalRollbackApplied`
    (`runtime/.../tx/TxSubsystem.java:655-660`) calls `revalidate`
    (`DefaultMemPool.java:544`), which re-checks input availability only.
  - After a new block, `DefaultMempoolEvictionPolicy.onBlockApplied` removes
    included and conflicting transactions.
- **Block production.** `BlockTransactionSelectors.selectMempool`
  (`runtime/.../tx/BlockTransactionSelectors.java:111-136`) re-validates each
  candidate against a `BlockBuildUtxoOverlay`
  (`runtime/.../blockproducer/BlockBuildUtxoOverlay.java`). That overlay tracks
  only spent and produced outpoints.
- **Canonical state only.** Certificate, governance and account state always
  comes from the canonical tip. So:
  - two mempool transactions can both register the same stake credential and
    both pass;
  - a withdrawal or delegation that depends on an earlier in-mempool
    registration or deregistration is judged against the wrong state;
  - a vote on a proposal submitted earlier in the same block is rejected as
    "action does not exist".

### Validation ignores the epoch boundary

- **Only parameters follow the slot.** Only protocol parameters are resolved
  for the current slot (`EpochProtocolParamsSupplier`). All other state is the
  un-ticked tip. `ledger-state` runs the epoch transition only when the first
  block of the new epoch is applied (`AccountStateEventHandler.onPreEpochTransition`).
- **What goes wrong.** Between the boundary and that block, and whenever Yano
  forges the first block of an epoch itself, several checks read the previous
  epoch: withdrawals of just-distributed rewards, `ConwayTreasuryValueMismatch`,
  votes on proposals that just expired or were enacted, delegation to pools
  that just retired, committee expiry, and enacted parameter changes. Haskell
  validates mempool transactions against the ledger state ticked to the next
  slot.

### The copied CCL rules are a start, not an engine

- **What exists.** `ccl-ledger-rules` (4,056 lines, 39 classes) has about 2.3k
  lines of rules in 10 classes, state slices (including Yano-backed
  `Yaci*Slice`s) and a `LedgerContext`
  (`ccl-ledger-rules/.../LedgerContext.java`).
- **What it isn't.** It checks rules independently against canonical slices. It
  is not a state transition, has no typed failures and no protocol version
  gating, and has **one** test class (`TxBalanceCalculatorTest`).
- **What Yano provides.** `ledger-state` (about 20k lines) already tracks almost
  everything the rules need.
  - The `GovernanceStateStore` exposes `getLastEnactedAction(GovActionType)`,
    `getConstitution`, `getNumDormantEpochs` and `getVotesForProposal`.
  - Proposal records carry `expiresAfterEpoch`/`prevActionTxHash`; committee
    records carry `expiryEpoch`.
  - `LedgerStateProvider` covers accounts, pools, DReps and the committee, but
    not votes, lineage or the constitution. Those come from the governance
    store directly.

## Decision drivers

- **Correct admission on Yano-produced blocks.** An invalid transaction that
  Yano admits is diffused and can end up in a Yano-produced block that Haskell
  followers reject. A valid transaction that Yano wrongly rejects is a liveness
  and user-facing bug.
- **Haskell mempool and block semantics.** The mempool state is the ticked tip
  plus mempool transactions applied in order. Block application folds
  transactions in order.
- **One language and one model.** No Scala joint compilation, no second decoder,
  no reconstructed UTxO model. Errors are per constructor.
- **Hard-fork readiness.** Yano needs to control protocol version gates (PV11
  changes, Dijkstra).
- **Native-image parity.** No JNI dependency beyond the phase-2 evaluator.

## Invariants

1. **Source of truth.** Rule semantics follow Haskell `cardano-ledger` for the
   active protocol version.
2. **Fail closed on unavailable state, not on absent records.** Every
   `LedgerView` read returns one of three outcomes: **present** (with a value),
   **confirmed absent**, or **unavailable** (read failure, store not ready,
   snapshot expired).
   - Confirmed absence is ordinary ledger data: a pool registering for the
     first time, a credential registering after a deregistration. It reaches
     the rules, which report the typed ledger failure if one applies.
   - An unavailable read, a conversion error or an unexpected exception rejects
     the transaction as a phase-1 engine failure (`LedgerStateUnavailable`). It
     never admits it.
3. **Validation is side-effect free.** A successful validation returns
   `TxEffects`. Only the caller applies them to an overlay. Canonical RocksDB
   state is never mutated by validation.
4. **Deterministic effects.** `TxEffectsDeriver` computes effects from
   (transaction, pre-state view, protocol parameters, **phase-2 verdict**).
   - For a given verdict, effects don't depend on the engine.
   - For `isValid=false` transactions, effects are collateral consumption plus
     collateral return only.
   - Under `engine: scalus`, proposals and votes enter the overlay without the
     GOV checks Scalus can't perform. This is accepted and documented, and it
     is one reason Scalus stops being the default.
5. **Order is semantics.** It mirrors Haskell Conway `LEDGER`:
   - **Validity branch.** When `isValid=true`: the pre-checks (treasury value,
     reference-script size, withdrawals, DRep-delegated withdrawals), then
     `CERTS`, then `GOV`, all against the intra-transaction overlay in body
     order. When `isValid=false`, `CERTS` and `GOV` don't run.
   - **UTXOW reads pre-certificate state.** `UTXOW`/`UTXO`/`UTXOS` receive the
     **pre-certificate** certificate state. That is how Haskell computes deposit
     refunds and needed witnesses. Only UTxO effects and the transaction's
     certificate and governance effects flow forward to later transactions.
   - **Across transactions,** overlays apply in admission or selection order.
6. **Ticked base.** The base view for validation at slot `s` is canonical state
   advanced through any epoch boundary between the tip and `s`
   (see Detailed decision §3).
7. **Conway, PV10 and later.** The Java engine returns `EraNotSupported` for
   non-Conway bodies or a ticked protocol version below 10, and Yano never falls
   back to "accept". This applies to mempool admission and block production.
   Networks still at PV9 keep `engine: scalus`.
8. **Canonical state is read-only here.** No change to canonical ledger-state
   mutation, rewards, AdaPot or ratification. This ADR only reads them.

## Detailed decision

### 1. Module layout

| Module | After this ADR |
|---|---|
| `ledger-rules` | Validation API, `LedgerView` and overlays, the ticking adapter, `TxEffects`, and the Conway rule engine. Pure Java. Depends on `core-api` and CCL core. |
| `ccl-ledger-rules` | **Removed.** Its code moves into `ledger-rules` under `org.yanoproject.ledger.rules.*`. Removed from `settings.gradle` and the BOM. No external consumer was found in yano-x, yaci-store or yaci-devkit, so no relocation shim is needed. |
| `script-evaluators` (new) | `AikenTxEvaluator`, `JulcTxEvaluator` and `YaciScriptSupplier`, moved from `ledger-rules` with their tests, into `org.yanoproject.ledger.scripteval`. They keep the `TransactionEvaluator` interface. |
| `scalus-bridge` | The Scalus engine, the Scalus `TransactionEvaluator`, and the Scalus `ScriptPhaseEvaluator`. Package unchanged (`org.yanoproject.scalusbridge`). |
| `amaru-validator` (ADR-057) | Optional. Depends on `ledger-rules`. Package `org.yanoproject.ledger.amaru`. |

**Package layout.** The root is `org.yanoproject.ledger`, a domain name as
`AGENTS.md` requires, not a product name. `org.yanoproject.ledgerstate` is not
renamed by this ADR.

| Package | Contents |
|---|---|
| `org.yanoproject.ledger.rules` | Public API: `LedgerValidationEngine`, `TransactionValidator` (legacy), `TransactionEvaluator`, `TxValidationRequest`/`TxValidationOutcome`, `ValidatedTx`, `ValidationEnv`, `LedgerFailure`/`LedgerRuleName`, `TxIdentity`, `ValidationResult`/`ValidationError`, parameter and slot-config suppliers |
| `org.yanoproject.ledger.rules.view` | `LedgerView`, `Lookup`, `CanonicalLedgerView`, `TickedLedgerView`, `OverlayLedgerView`, `InMemoryLedgerView` (fixtures); state records in `view.model` |
| `org.yanoproject.ledger.rules.effects` | `TxEffects`, `TxEffectsDeriver` |
| `org.yanoproject.ledger.rules.phase2` | `ScriptPhaseEvaluator` SPI and result types |
| `org.yanoproject.ledger.rules.conway` | `ConwayLedgerTransition`, protocol version gates, REAPPLY static/dynamic labels |
| `org.yanoproject.ledger.rules.conway.failure` | The typed constructors per Haskell rule, which produce the API's `LedgerFailure` |
| `org.yanoproject.ledger.rules.conway.{mempool,ledger,certs,gov,utxow,utxo,utxos}` | One package per Haskell rule family |
| `org.yanoproject.ledger.rules.util` | Former CCL utilities: `TxBalanceCalculator`, `LedgerMinFeeCalculator`, `NativeScriptEvaluator`, `RequiredWitnessResolver`, `UtxoUtil` |

The copied CCL state slices (`UtxoSlice`, `AccountsSlice`, …, and the `Yaci*Slice`
implementations) are replaced by `LedgerView` in Phase 1. Until then they move
unchanged into `org.yanoproject.ledger.rules.view.slice`.

### 2. Validation API

```java
/** The engine SPI: scalus | java | amaru (§7). */
public interface LedgerValidationEngine {
    String name();
    TxValidationOutcome validate(TxValidationRequest request);
}

/** Unchanged legacy interface over canonical state; removed in Phase 6. */
public interface TransactionValidator {
    ValidationResult validate(byte[] txCbor, Set<Utxo> inputUtxos);
}

public record TxValidationRequest(byte[] txCbor, LedgerView view, ValidationEnv env,
                                  Rule rule, Origin origin,
                                  ValidatedTx previous) {        // null = never validated
    public enum Rule { MEMPOOL, LEDGER }            // §4: admission vs block contexts
    public enum Origin { LOCAL, PEER, BLOCK_BUILD, SYNC }
}

public record ValidationEnv(long currentSlot, long currentEpoch, int protocolMajor,
                            int protocolMinor, NetworkId networkId, SlotConfig slotConfig,
                            byte[] phase2EnvDigest) {}           // §6 invalidation rules

/** Provenance of a successful full validation; kept by the mempool per transaction. */
public record ValidatedTx(byte[] txCbor, byte[] txId, int validatedProtocolMajor,
                          long validatedEpoch, byte[] validatedPhase2EnvDigest,
                          boolean phase2Valid, Origin origin) {}

public sealed interface TxValidationOutcome {
    record Valid(TxEffects effects, ValidatedTx validated, boolean reapplied)
            implements TxValidationOutcome {}
    record Invalid(List<LedgerFailure> failures) implements TxValidationOutcome {}
}

/** Result of every LedgerView read (invariant 2). */
public sealed interface Lookup<T> {
    record Present<T>(T value) implements Lookup<T> {}
    record Absent<T>() implements Lookup<T> {}
    record Unavailable<T>(String reason) implements Lookup<T> {}
}
```

- The caller doesn't choose `FULL` or `REAPPLY`. The validator decides from
  `previous` and the environment, using the invalidation rules in §6. So
  mempool rebuilds and block selection can't disagree about when a cached
  verdict is still usable.
- `ValidatedTx` is the only form in which a transaction's earlier verdict is
  carried. Raw bytes plus a mode flag can't express where a verdict came from.
- **Two interfaces until Phase 6.** `LedgerValidationEngine` is the new engine
  SPI. `TransactionValidator` keeps its current single method unchanged: it is
  the legacy adapter that runtime, Scalus and tests still call (and implement as
  a lambda). Phase 6 moves callers to `LedgerValidationEngine` and removes it.
- **Parameters come from the view.** `ValidationEnv` holds only what is not
  ledger state. Epoch-effective protocol parameters (deposits,
  `govActionLifetime`, `drepActivity`, cost models) are read from
  `LedgerView.protocolParams()`, so ticking changes them with the rest of the
  state. `protocolMajor`/`protocolMinor` repeat the view's version for PV gates.
- **Transaction id.** `TxIdentity` hashes the body bytes sliced from the
  original transaction bytes, never a re-serialisation.

`LedgerFailure` carries:
- the Haskell rule (`MEMPOOL`, `LEDGER`, `CERTS`, `DELEG`, `POOL`, `GOVCERT`,
  `GOV`, `UTXOW`, `UTXO`, `UTXOS`);
- the constructor name valid for the active protocol version;
- the phase;
- structured detail.

It maps losslessly to the existing `ValidationResult`/`ValidationError`, so
REST, n2n and n2c rejection paths are unchanged.

### 3. `LedgerView`, ticking, overlays

`LedgerView` is read-only and answers what Conway rules need:

| Area | Reads |
|---|---|
| UTxO | `utxo(Outpoint)`, including inline datum, datum hash, reference script |
| Accounts | registration, deposit, reward balance, pool delegation, DRep delegation |
| Pools | registered params (including VRF key hash), deposit, pending retirement |
| DReps | registration, deposit, expiry |
| Committee | member by cold credential, hot authorization, resignation, member expiry, candidates from pending `UpdateCommittee` proposals |
| Governance | proposal by id (action type, expiry, parent), enacted root per purpose, constitution guardrail script hash, dormant epoch count |
| Pots | treasury at epoch start |
| Parameters | epoch-effective `ProtocolParams` and cost models (ADR-024) |

- **`CanonicalLedgerView`** reads `UtxoState`, `LedgerStateProvider`, the
  `GovernanceStateStore` and the epoch parameter tracker. Reads follow the
  three-outcome contract of invariant 2. The one accessor still to confirm is
  the candidate payload of pending `UpdateCommittee` proposals.
- **Canonical snapshot contract.** A `CanonicalLedgerView` is always backed by
  a **`CanonicalSnapshot` of one fully applied canonical tip**. It never reads
  live stores, whose parts could describe different tips while a block is being
  applied.
  - **Storage facts** (confirmed in Phase 1).
    - UTxO, account, pool, DRep and governance state appear to live in **one
      RocksDB instance**, as column families. `DirectRocksDBChainState.java:295`
      is the only production `RocksDB.open`. `DefaultAccountStateStore`
      receives that `db` (`DefaultAccountStateStore.java:369`).
    - Yano already reads through RocksDB snapshots elsewhere, for example
      `RocksUtxoReadView.java:54`.
    - A RocksDB snapshot is consistent at the database level. But on its own it
      can be taken between two of a block's writes, so it isn't necessarily a
      fully applied ledger tip.
  - **Writer protocol.** A `CanonicalStateGate` is a fair read-write lock
    around canonical application.
    - Forward block application and rollback each hold the **write lock** from
      before their first store mutation until after their last one, across
      every store the view reads (UTxO, accounts, pools, DReps, governance,
      epoch parameters, AdaPot).
    - Writers are already serialized by the single chain-application path. The
      gate makes that explicit.
    - Before releasing the write lock, the writer publishes `canonicalGeneration`
      (monotonic) and the tip (slot, hash) in a `volatile` field.
    - Event listeners (mempool, block producer) are notified **after** release.
  - **Acquisition.** `CanonicalSnapshot.acquire()`:
    1. Takes the gate's **read lock**, so it waits while a writer is mid-block.
    2. Reads `canonicalGeneration` and the tip.
    3. Takes one RocksDB `Snapshot` of the shared instance.
    4. Copies every validation-visible value that is held in memory rather than
       in RocksDB into an immutable object tagged with the same generation:
       effective protocol parameters and cost models, and any in-memory
       governance or AdaPot cache. Phase 1 lists each of them.
    5. Releases the read lock.

    Acquisition is O(1) plus that small copy, so writers are delayed only
    briefly. Every later read goes to the retained RocksDB snapshot or the
    immutable copy, never to live stores. A write can't overlap a capture, and
    no individual read batch needs checking.
  - **Stores outside the shared instance.** If Phase 1 finds a
    validation-visible store outside the shared RocksDB, it must be copied
    immutably at step 4 or versioned by generation. The option of checking a
    generation before and after each read batch is **rejected**: it can't
    detect a writer that is part-way through a block, and it can't make
    separately checked batches into one snapshot.
  - **No "open generation G later".** A snapshot exists only as the handle
    returned by `acquire()`. Nothing opens an arbitrary historical generation
    afterwards. A consumer that needs the current tip acquires a new handle.
  - **Reference-counted ownership.**
    - `CanonicalSnapshot` is reference counted (`retain()`/`release()`). The
      RocksDB snapshot and the immutable copy are freed when the count reaches
      zero.
    - Every derived view (`TickedLedgerView`, `OverlayLedgerView`, a shadow
      request) **retains** its base snapshot for as long as it exists, and
      releases it when it is retired.
    - Handing a view to another owner transfers a reference. It never closes
      the base.
  - **Bounded resources.**
    - At most `yano.validation.max-live-snapshots` (default 4) generations can
      be retained at once: the published mempool base, a rebuild in progress,
      block selection, and shadow work.
    - When the cap is reached, new **shadow** requests are dropped (counted in
      `yano_validation_shadow_dropped_total`), never admission or block
      selection.
    - A shadow task older than `yano.validation.snapshot-max-age-ms` (default
      30 s) is cancelled and releases its reference. Its result is discarded,
      not counted as a disagreement.
    - A metric exports live snapshots per generation, so a leak is visible.
- **`TickedLedgerView(canonical, slot)`** — the answer to "what is the state at
  slot `s`".
  - If `s` is in the tip's epoch, it is the canonical view.
  - If an epoch boundary lies between them, it overlays the boundary's
    validation-visible effects:
    - rewards credited for the completed epoch;
    - the treasury and reserves of the new epoch;
    - pool retirements that take effect;
    - DRep expiry;
    - proposals expired, and those enacted with their new roots;
    - committee changes;
    - the new epoch's protocol parameters.
  - **Expired proposals stay readable until removed.** Haskell keeps a proposal
    in `Proposals` after its `expiresAfter` epoch until a boundary removes it,
    so GOV reports `VotingOnExpiredGovAction` (`Gov.hs:360-362, 607`), not
    `GovActionsDoNotExist` (`:605`), and the same set feeds the committee
    candidates (`Ledger.hs:370`). The boundary into epoch `E+1` removes a
    proposal when `expiresAfter < E`, where `E` is the epoch ratification ran
    in (`reCurrentEpoch`, `Ratify.hs:357-358`, `DRepPulser.hs:398-404`), **not**
    `expiresAfter < E+1`. The ticked view must apply exactly that rule.
  - These values come from `ledger-state`'s epoch processing, run as a pure
    **dry run** over a snapshot, never persisted.
  - If a dry run isn't available for a value, the ticked view fails closed: it
    rejects transactions whose verdict depends on that value, and never guesses.
  - Its result is cached per (`canonicalGeneration`, target epoch). A cache
    entry retains its `CanonicalSnapshot` and releases it on eviction, when the
    generation it belongs to is no longer published and no view uses it.
  - Phase 1 establishes which boundary values `ledger-state` can compute
    without persisting. That is the riskiest dependency in this ADR, so it has
    its own gate.
- **`OverlayLedgerView(base)`** is an ordered stack of `TxEffects` over a base
  view.
  - `apply(TxEffects)` pushes a layer. Reads resolve newest layer first.
  - Layers are immutable, and a view is a persistent (shared-structure) stack.
    `freeze()` returns an immutable view containing the same layers plus a
    retained reference to the **same** `CanonicalSnapshot`. Asynchronous shadow
    engines therefore see exactly the generation and layers the admission
    request saw, never a later tip.
  - `rebuild(...)`: see §6.
- **`TxEffects`** holds what a valid transaction changes and later rules can
  observe:
  - UTxO consumed and produced;
  - account register, unregister and delegate, with deposits;
  - rewards set to zero by withdrawals;
  - pool register, re-register and retire;
  - DRep register, update and unregister, with deposits and activity;
  - committee hot authorizations and resignations;
  - new proposals (with ids and parents);
  - votes.

### 4. The Conway transition in Java

`ConwayLedgerTransition.apply(view, env, tx, rule)` follows Haskell Conway.
Admission and mempool rebuilds run `MEMPOOL`, which runs its own checks and then
invokes `LEDGER` (`Conway/Rules/Mempool.hs`). Block selection and shadow sync
run `LEDGER` only (`Conway/Rules/Ledger.hs`), without the admission-only
checks.

0. **MEMPOOL** (only when `rule = MEMPOOL`), against the **incoming** state,
   before anything in `LEDGER`:
   - `ConwayMempoolFailure` when **every spending input** is already spent
     ("probably a duplicate"). Reference and collateral inputs are not
     considered. If this fails, every other check is skipped, as Haskell's
     `whenFailureFreeDefault` does. So a duplicate reports only this failure.
   - While `hardforkConwayDisallowUnelectedCommitteeFromVoting` is off
     (PV ≤ 10): reject votes by unelected committee members with
     `ConwayMempoolFailure`, judged against the incoming committee state, not
     post-certificate state. From PV11 the same check lives in `GOV` as
     `UnelectedCommitteeVoters`, so it also applies to blocks.
   - Haskell has no separate MEMPOOL failure type. `ConwayMempoolFailure` is a
     constructor of `ConwayLedgerPredFailure` (`Conway/Rules/Mempool.hs:86`,
     `Ledger.hs:124`).

   `LEDGER` then runs unless the all-spent check failed. The unelected-voter
   check uses `failOnNonEmpty`, which records its failure without
   short-circuiting, so `LEDGER`'s failures accumulate after it
   (`Conway/Rules/Mempool.hs:103-138`: `trans @"LEDGER"` sits inside the
   `whenFailureFreeDefault` block that only the all-spent check can skip):

1. **If `isValid=true`, the LEDGER pre-checks**, against pre-certificate state:
   - treasury value (`ConwayTreasuryValueMismatch`);
   - total reference-script size, from spending inputs **and** reference
     inputs;
   - `ConwayWdrlNotDelegatedToDRep` (PV ≥ 10), on pre-certificate accounts;
   - from PV11 (`hardforkConwayMoveWithdrawalsAndDRepChecksToLedgerRule`):
     `ConwayWithdrawalsMissingAccounts` / `ConwayIncompleteWithdrawals`, then
     the DRep activity updates and the withdrawal drain, before `CERTS`.
     Before PV11 the withdrawal check and drain are the `CERTS` base case
     (`WithdrawalsNotInRewardsCERTS`), which runs before the first
     certificate (`Certs.hs:222-241`).

   Failures accumulate, as in Haskell STS: later checks still run and report
   their failures. Only `whenFailureFree` blocks are skipped.
2. **If `isValid=true`, CERTS.** Certificates fold over an intra-transaction
   overlay: `DELEG`, `POOL` and `GOVCERT`, with deposit and refund correctness.
   Their PV gates include:
   - delegatee existence checks (PV10);
   - `hardforkConwayDELEGIncorrectDepositsAndRefunds` (PV11);
   - `VRFKeyHashAlreadyRegistered` (PV11).
3. **If `isValid=true`, GOV.** Proposals and votes against the post-certificate
   overlay:
   - proposal lineage per purpose (enacted root plus in-flight);
   - hard-fork version chaining;
   - guardrails script hash;
   - malformed parameter updates;
   - committee update conflicts and expiry;
   - voter existence, committee hot-key authorization and resignation, and
     unelected committee voters (PV11 in `GOV`);
   - expired actions and disallowed voter classes.

   The PV9 bootstrap disallow rules are out of scope (invariant 7).
4. **UTXOW and UTXO**, with the **pre-certificate** certificate state
   (invariant 5):
   - **Witnesses:** vkey and bootstrap witnesses; needed key hashes across all
     purposes; native scripts; script-hash and redeemer-purpose exactness
     (including reference scripts); datum rules; script integrity hash with
     language views; metadata; malformed scripts.
   - **UTXO:** fees (including tiered reference-script fees), collateral,
     min-UTxO, value size, network ids, validity interval and forecast horizon,
     and value conservation (deposits, refunds, proposal deposits, donations).
5. **UTXOS.** Phase-2 through `ScriptPhaseEvaluator`, compared with the
   transaction's `isValid` flag.

Protocol version gates are data: each check declares its PV range, and the
constructor it reports is the one valid at that version. The coverage matrix
and the Amaru differential mapping both key on (constructor, PV).

The existing CCL rule classes are starting material. Each is either extended
into its family or rewritten where its model (independent checks against
canonical slices) conflicts with the transition model.

### 5. Phase-2 SPI

```java
public interface ScriptPhaseEvaluator {
    ScriptPhaseResult evaluate(Transaction tx, Map<Outpoint, Utxo> resolvedInputs,
                               ProtocolParams params, SlotConfig slotConfig);
}
```

- **Scalus implementation.** `scalus-bridge` runs the Scalus CEK machine for
  each redeemer, with the declared ExUnits as budget. It returns pass/fail per
  script with logs.
- **julc later.** `julc-vm-java` can follow once it passes the public Plutus
  conformance suite (out of scope).
- **Unchanged.** `TransactionEvaluator` and
  `yano.block-producer.script-evaluator` are not affected.

### 6. Runtime integration

**Phase-2-invalid transactions: rejected at admission in this ADR.**
- **Current block-builder limitation.** Yano's block builder can't yet encode a
  phase-2-invalid transaction. `DevnetBlockBuilder.splitTransaction`
  (`runtime/.../blockproducer/DevnetBlockBuilder.java:479-505`) ignores
  `is_valid` ("For now, assume all txs are valid"). `computeBlockBody` always
  emits an empty `invalid_txs` array (`:220-252`). The signed builder inherits
  this. Admitting a transaction whose effects are collateral-only would
  therefore produce blocks that Haskell rejects.
- **Admission policy for every origin** (local and peer):
  - a transaction claiming `isValid=true` whose scripts fail is rejected
    (`UTXOS.ValidationTagMismatch`);
  - a transaction submitted with `isValid=false` is rejected with the Yano
    policy failure `Phase2InvalidTxNotSupported`, even if it is ledger-valid.

  This matches Yano's behaviour today: Scalus rejects any phase-2 failure.
- **Collateral effects still exist.** `TxEffectsDeriver` keeps its
  collateral-only branch, because shadow sync validates synced blocks that
  contain phase-2-invalid transactions.
- **Follow-up, not in this PR.** Admitting peer transactions as phase-2 invalid
  (Haskell's `DoNotIntervene`, which collects collateral) needs its own ADR,
  covering:
  - `ValidatedTx` keeping the **corrected** validity flag when a peer
    transaction arrives claiming `isValid=true`. Mempool storage, diffusion and
    block encoding must all use the corrected transaction bytes. The body, and
    so the transaction id, are unchanged; only the `is_valid` field changes.
  - Collateral-aware mempool indexes: spend collateral, produce the collateral
    return, not the regular inputs or outputs.
  - Populating `invalid_txs` in block encoding, and in body size and hash.
  - A Haskell-follower test with a phase-2-invalid transaction in a
    Yano-produced block.
  - Matching `WhetherToIntervene` at consensus 4.2.1.0 (`Shelley/Eras.hs:238-271`):
    - a peer transaction is first applied with its flag forced to
      `IsValid True`;
    - if the **only** failure is `ValidationTagMismatch`, it is re-applied as
      `IsValid False`, collecting collateral, and that flipped transaction is
      what gets stored and forged;
    - local submissions keep their flag, so a wrong flag is rejected.

**Mempool.**
- **One immutable published state.** The mempool's ledger-facing state is one
  immutable `MempoolLedgerState`. It holds:
  - the ordered `ValidatedTx` list;
  - the UTxO produced/spent indexes and dependency edges;
  - the `OverlayLedgerView`, one layer per transaction, in list order;
  - a retained reference to the overlay's `CanonicalSnapshot`;
  - a monotonic `mempoolGeneration`;
  - an append-only **mutation log**, with an entry per append or removal
    since the state's creation.

  Under the mutation lane, every change builds a new `MempoolLedgerState` and
  swaps the published reference. So the list, provenance, indexes and overlay
  are always published together, and they are consistent with each other.
- **Lock order.** The canonical gate (§3) and the mempool lane are never held
  together.
  - Admission reads only the published state's own snapshot, never the gate.
  - Canonical writers notify the mempool only after releasing the gate.
  - Rebuilds acquire their snapshot before taking the lane. This includes the
    synchronous fallback (step 6 below).
  - Freshness checks under the lane read the **published** `canonicalGeneration`
    and tip, a `volatile` read. They never take the gate.
- **Admission** (under the lane). Run rule `MEMPOOL` against the published
  overlay. On success, append the `ValidatedTx` and its effects layer and swap.
  - The admission holds a reference to the published state for its duration.
    A concurrent swap therefore can't release the base it is reading.
  - Any canonical UTxO the transaction needs, even one no earlier transaction
    touched, is read from that retained snapshot.
- **Removal, eviction or clear while the canonical tip is unchanged** (under
  the lane, synchronously).
  1. Truncate the overlay to the layer before the earliest removed transaction.
  2. Re-apply the remaining suffix in order. The canonical base is unchanged,
     so every `previous` is still valid under the invalidation rules below.
     Re-application skips signatures and Plutus, so it costs only the dynamic
     checks.
  3. Drop any suffix transaction that now fails (cascading). This covers
     certificate and governance dependencies, not just UTxO parents: a
     delegation whose registering transaction was evicted fails re-application
     and is dropped.
  4. Swap.

  A removed transaction's effects therefore never stay visible, even briefly,
  to later admissions. The suffix re-application time is part of the Phase 6
  budget.
- **Rebuild on a canonical change without stalling admission.** Triggers: a
  forward block, a rollback, or an epoch change of the next slot. One rebuild
  worker runs at a time. Triggers that arrive while it runs are coalesced into
  one follow-up rebuild against the newest snapshot.
  1. Acquire a new `CanonicalSnapshot` (§3), outside the lane. Record its
     generation `Gs` and the **target epoch** `Es`, the epoch of the `nextSlot`
     the ticked view is built for.
  2. Under the lane, take a reference to the published `MempoolLedgerState` S
     and record its mutation-log position.
  3. Outside the lane, fold S's `ValidatedTx` list over
     `TickedLedgerView(newSnapshot, nextSlot)` with rule `MEMPOOL`. Each
     transaction's `previous` decides between re-application and full
     validation. Failures are dropped. This produces a candidate state R.
  4. Under the lane, in this order:
     1. **Mempool descent:** S's mutation log since the recorded position must
        contain only appends. If so, validate those appended transactions in
        order on top of R, dropping failures.
     2. **Canonical freshness, as the last action before the swap:** the
        published `canonicalGeneration` still equals `Gs`, and the epoch of the
        current `nextSlot` still equals `Es`. Mempool ancestry is never taken
        as proof of canonical freshness. Because this check comes after append
        reconciliation, time spent validating appended transactions can't
        hide a canonical change.
     3. If both pass, swap in the result immediately.
  5. If either check fails, **discard** R and release `newSnapshot`:
     - A canonical freshness failure restarts from step 1 against the newest
       snapshot.
     - A descent failure (removal, eviction or clear) also restarts from
       step 1.
  6. **Synchronous fallback.** After `yano.validation.rebuild-max-restarts`
     (default 3) consecutive discards, the next attempt changes how it uses the
     lane, but not the lock order:
     - It acquires the snapshot outside the lane, exactly as in step 1.
     - It then takes the lane **before** step 2 and holds it through steps
       2–4, so the mempool can't change. Only the canonical writer can then
       invalidate the attempt, and the freshness check in step 4 still applies.
     - If the canonical generation or target epoch moved during the attempt, it
       releases the lane and the snapshot and tries again, up to
       `yano.validation.rebuild-sync-attempts` (default 3).
  7. **Catching up.** Holding the lane can't stop blocks from arriving. If the
     synchronous attempts are also exhausted (for example during fast catch-up
     sync, when blocks arrive faster than a fold), the mempool enters
     `CATCHING_UP` rather than publish a stale result:
     - Admission is rejected with a retryable `MempoolCatchingUp` status. Local
       submitters get a retryable error; peer transactions aren't requested.
     - Block selection skips the mempool.
     - The worker keeps retrying with each newly published generation. The
       first successful publication returns the mempool to `READY`.
     - `CATCHING_UP` is exported as a metric and on the health endpoint.
  8. **Ownership transfer.**
     - On a successful swap, the new published state keeps the reference to
       `newSnapshot` that the rebuild acquired. The rebuild does not release
       it.
     - The replaced state is **retired**. It releases its own snapshot
       reference only once every admission and frozen shadow view that
       retained it has released theirs, through reference counting.
     - Every discarded attempt releases its own `newSnapshot`.

  This keeps O(N) × validation time off the admission path. A rebuild is never
  published for a generation or target epoch that was already superseded at the
  final pre-swap check. It never resurrects a removed transaction, never keeps a removed
  transaction's effects, and never holds the canonical gate and the mempool lane
  together.
- **Lag contract.** The published mempool state can lag the canonical tip.
  The design does **not** bound that lag to a number of generations. It
  guarantees:
  - **Internally consistent.** The published state is a consistent view of
    one canonical generation plus the mempool's own transactions, and its
    snapshot stays retained while in use (§3).
  - **Provisional admissions.** While a newer generation exists, admissions
    are provisional against the older one. The rebuild that follows
    re-validates them, and drops any that fail.
  - **Multi-generation lag is possible.** Several blocks can be published
    during one fold, or in the explicitly allowed race between the step-4
    freshness check and a canonical publication immediately after the swap.
    Every canonical publication notifies the mempool after the gate is
    released, so a rebuild is always pending while the published state is
    behind.
  - **Bounded retries.** If rebuilds can't catch up within the step-5 and
    step-6 attempt limits, the mempool enters `CATCHING_UP` (step 7) and stops
    admitting until a rebuild publishes.
  - **Forging is independent.** Block selection never relies on the mempool
    overlay. It acquires its own snapshot and re-validates every candidate
    (block production, below), so a lagging mempool can't put an invalid
    transaction into a block.
- **Which events trigger it.** Forward application and rollback both notify
  the mempool after the canonical gate is released and `canonicalGeneration` is
  published. This replaces `onCanonicalRollbackApplied`
  (`TxSubsystem.java:655`), which fires on the UTxO rollback alone.
- **Epoch boundary.** When the next slot crosses into a new epoch, the ticked
  base changes. That triggers a rebuild, which applies the invalidation rules
  below.

**Re-application and invalidation.** Mempool rebuilds and block selection use
the same rule. The validator re-applies a transaction (skipping static checks)
only when `previous` is present **and** none of the following changed since
`previous` was produced:
- **protocol major version**. At the pinned revision, cardano-ledger has
  `reapplyValidatedTx`, which forces full validation on a protocol-major
  change (`Shelley/API/Mempool.hs:420-439`). Consensus 4.2.1.0 (node 11.1.2)
  still calls the deprecated `reapplyTx`, which has no such guard; consensus
  main switches to `reapplyValidatedTx` for node 11.2. Yano applies the guard
  now. That is stricter than node 11.1.2, and strictly safer;
- **`phase2EnvDigest`**: the hash of what a phase-2 verdict depends on besides
  the resolved inputs. That is the cost models of the languages the transaction
  uses, the ExUnits price and limit parameters, and the Plutus language
  versions allowed at the current protocol version;
- **resolved inputs**: any spending, reference or collateral input resolving to
  different bytes (for example after a rollback);
- **origin**: a `LOCAL` or `PEER` verdict can be re-applied in `BLOCK_BUILD`;
  a `SYNC` verdict is never re-used for admission.

If any of these changed, the transaction is validated in full, including
Plutus. Its new `ValidatedTx` replaces the old one. Phase 6 includes a
**pending-mempool hard-fork test**: transactions sit in the mempool across a
protocol-major change and a cost-model change, and every re-application is
checked to be a full validation.

**Static checks** follow Haskell `reapplyTx`, which skips `lblStatic`-labelled
checks (`Shelley/API/Mempool.hs`). Phase-2 is part of the static set
(`Alonzo/Rules/Utxos.hs`, `when2Phase`). Every other check is re-run against
the new environment.

| Skipped in REAPPLY (static) | Re-run in REAPPLY (depend on state or environment) |
|---|---|
| vkey and bootstrap signature verification (`validateVerifiedWits`), metadata hash/validity, malformed script witnesses and reference scripts, empty inputs, bootstrap address attributes, network ids (tx body, outputs, withdrawals), max tx size, **Plutus execution** (`when2Phase`) | input existence, validity interval and forecast, fees, collateral, min-UTxO, value size, ExUnits limits, **native script evaluation** (dynamic in Conway: `Babbage/Rules/Utxow.hs` uses `runTest`), missing/extraneous scripts, datums, redeemers, script integrity hash (cost models may have changed), needed witnesses, reference input disjointness, `CollectErrors`, all CERTS, GOV and LEDGER checks, MEMPOOL |

The static set is taken from the `lblStatic`/`runTestOnSignal` labels at the
pinned revision (`Babbage/Rules/Utxow.hs`, `Babbage/Rules/Utxo.hs`,
`Alonzo/Rules/Utxos.hs`).

**Block production.**
- `BlockTransactionSelectors.selectMempool` acquires one `CanonicalSnapshot`
  (§3) and releases it after the block is forged or the selection is discarded.
  It builds a fresh block-local `OverlayLedgerView` over
  `TickedLedgerView(snapshot, forgeSlot)`. It deliberately does not reuse the
  mempool overlay: only selected transactions are applied, in selection order.
- It validates each candidate with rule `LEDGER`, passing the candidate's
  `ValidatedTx`. The invalidation rules above decide between re-application and
  full validation.
- If `canonicalGeneration` changes before the block is forged, the selection is
  discarded and redone.
- `BlockBuildUtxoOverlay` is removed.

**Scalus under overlays.** An overlay-aware adapter makes `OverlayLedgerView`
look like a `LedgerStateProvider`, so `CertStateBridge` reads mempool-local,
block-local and ticked state.

### 7. Engine selection and shadowing

```yaml
yano:
  validation:
    engine: scalus            # scalus | java | amaru
    shadow-engines: []        # e.g. [java] or [java, amaru]
    shadow-dump-dir: ""       # when set, write a replayable bundle per disagreement
    shadow-sync: false        # validate synced PV10+ blocks with the java engine (observe only)
```

- The admission verdict comes only from `engine`. Shadow engines never change
  admission: with `engine: scalus`, admission stays on the legacy
  `TransactionValidator` path unchanged, and shadows validate against their own
  `SHADOW` snapshot and are compared with the legacy verdict (valid/invalid,
  plus the Haskell constructor where the legacy failure name maps to exactly
  one). Only a non-`scalus` admission engine switches admission to the engine
  API.
- **Shadow engines** receive `TxValidationRequest` with a `freeze()`d view
  (§3). That view retains the admission's own `CanonicalSnapshot` and overlay
  layers, and shadow engines run asynchronously.
  - Disagreements increment
    `yano_validation_disagreements_total{engine,rule}` and are logged once
    with the transaction hash.
  - When `shadow-dump-dir` is set, a self-contained bundle (tx CBOR plus the
    resolved view slice) is written for replay in tests.
- **Deprecations.** `supplementary-rules-enabled` and
  `default-validator-enabled` (`application.yml:336-341`, and the profile
  overrides) are deprecated.
  - `supplementary-rules-enabled=true` maps to `engine: scalus` plus the Java
    GOV and GOVCERT families as a post-filter until Phase 8. Combined with a
    non-`scalus` admission engine it is a startup error, never silently
    dropped.
  - Both keys are removed in Phase 8, and startup logs a warning if they are set.

### 8. Conformance and completeness

| Oracle | What it proves | Where |
|---|---|---|
| **Coverage matrix** | Every leaf Conway constructor across `MEMPOOL`/`LEDGER`/`CERTS`/`DELEG`/`POOL`/`GOVCERT`/`GOV`/`UTXOW`/`UTXO`/`UTXOS` at PV10–11 (the count is measured in Phase 2) has a rule and at least one passing negative test. Every PV gate is tested on both sides. A unit test fails the build if a constructor has no `@Covers("GOV.ProposalDepositIncorrect")` test. | `ledger-rules/docs/conway-rule-coverage.md` |
| **Amaru scenarios** | Haskell-cross-checked JSON scenarios (initial state, tx, expected Haskell predicate) load into an in-memory `LedgerView`. The verdict and the constructor must match. | Vendored copy, pinned to the Amaru tag used by ADR-057, with Apache-2.0 NOTICE, under `ledger-rules/src/conformanceTest/resources/amaru/` |
| **cardano-blueprint vectors** | Conway CBOR vectors generated from the Haskell Imp tests. Needs a `NewEpochState` decoder. Tick and epoch events are skipped, as in Amaru. | `conformanceTest`, pinned tarball with checksum |
| **Amaru-wasm differential** | For every scenario, mutated transaction and shadow-dump bundle, the Java verdict equals the Amaru verdict. Divergences are triaged against Haskell and recorded. | ADR-057 Phase D |
| **Shadow sync validation** | Every transaction of every synced PV10+ Conway block (preprod, preview, mainnet from the PV10 boundary) validates against the ticked pre-block state plus a block-local overlay. `invalidTransactions` must come out phase-2 invalid, all others valid. Catches false rejections and PV drift. It cannot catch false acceptances. | `yano.validation.shadow-sync=true` |
| **Mutation matrix** | From valid base transactions, at least one mutant per constructor is rejected with that constructor. | `conformanceTest` |
| **Haskell differential** (optional) | Mutated invalid transactions submitted over n2c to a local devnet Haskell node (compatibility folder). Compare the `ApplyTxError` constructors. Never submit to a public network. | Manual / CI-optional |
| **Native parity** | The conformance suite runs in the native test image. | Same pattern as the ADR-051 native gate |

A Gradle source set `conformanceTest` and a task `:ledger-rules:conformanceTest`
run everything except shadow sync and the Haskell differential.

**Deviations recorded in Phase 2 (2026-09-29).**

- **A module, not a source set.** The harness is the test-only module
  `ledger-conformance` (`:ledger-conformance:test`, and
  `:ledger-conformance:conformanceReport` for the generated documents), not
  `:ledger-rules:conformanceTest`. It runs every engine side by side, and
  `scalus-bridge` and the optional `amaru-validator` both depend on
  `ledger-rules`, so a `ledger-rules` source set could not depend on them. The
  module has no main sources, removes its publication, and is in the root
  build's `centralDeploymentExclusions`.
- **Shared fixtures in `ledger-rules` test fixtures.** The constructor
  catalogue (`conway-constructors.json`), the `@Covers` annotation and the
  Amaru scenario loader live in `ledger-rules`' `testFixtures`, so the Phase 3–5
  rule tests in `ledger-rules` can be annotated too; the coverage scan reads
  both modules' test classes.
- **Amaru's scenarios are not vendored.** They are read from a clone at the
  pinned tag (`-PamaruScenariosDir` / `AMARU_SCENARIOS_DIR`; the
  `amaru-wasm.yml` `conformance` job clones it and checks the pinned commit).
  Without them the scenario parts skip; the mutation matrix and the coverage
  scan always run.

## Implementation plan

**Branch model.** `feat/conway-ledger-rules` is the integration branch and the
single PR (#155) into `main`. Each logical step or phase is:
1. implemented;
2. reviewed by independent sub-agent reviewers (Fable), with every rule
   cross-checked against the Haskell ledger or Amaru;
3. fixed;
4. tested;
5. committed and pushed to the PR, with the PR description updated.

Order across both ADRs:

| Step | Contents |
|---|---|
| S1 | 056-P0 (mechanical rename) |
| S2 | 056-P1, P2 + 057-A, B |
| S3 | 056-P3, P4, P5 |
| S4 | 056-P6 + 057-C |
| S5 | 056-P7, P8 + 057-D, E |

The final PR merges once S5's gates are green.

### Phase 0 — Module consolidation (no behaviour change)

- Move the `ccl-ledger-rules` sources into `ledger-rules`, from
  `com.bloxbean.cardano.client.ledger.*` to the `org.yanoproject.ledger.rules.*`
  layout in §1.
- Rename the existing API package `org.yanoproject.ledgerrules` to
  `org.yanoproject.ledger.rules`, and update every importer: `runtime`,
  `tx-services`, `scalus-bridge` (Java and Scala sources), `app`, and tests.
- Move the Aiken and julc evaluators to `script-evaluators`
  (`org.yanoproject.ledger.scripteval`).
- Update `settings.gradle`, the BOM, native-image metadata (any
  reflection/resource/JNI config naming the old packages), Quarkus
  configuration that names classes, and docs.
- Move tests: `TxBalanceCalculatorTest`, and the evaluator tests to
  `script-evaluators`.
- Gates:
  - the full JVM suite is green;
  - a native smoke test passes;
  - `grep` finds no `com.bloxbean.cardano.client.ledger` and no
    `org.yanoproject.ledgerrules` left in Yano sources.

### Phase 1 — API, views, ticking, effects, engine selection

- Add `TxValidationRequest`/`Outcome`, `LedgerView`, `CanonicalLedgerView`,
  `TickedLedgerView`, `OverlayLedgerView` and `TxEffectsDeriver`.
- Add the Scalus engine adapter and the overlay-aware `LedgerStateProvider`.
- Add the `engine`/`shadow-engines` config. The default stays `scalus`.
- Add `ValidatedTx`, the `Lookup` read contract, the `CanonicalStateGate`
  around whole-block application and rollback, `canonicalGeneration`
  publication, and reference-counted `CanonicalSnapshot` acquisition.
- Pin the `cardano-ledger` and ouroboros-consensus revisions, and re-check every
  Haskell reference in this ADR against them.
- Gates:
  - **Snapshot gate:**
    - Confirm that every validation-visible store is in the shared RocksDB
      instance, or is copied immutably at acquisition.
    - Instrument a writer to **pause after the UTxO update and before the
      account/governance updates** while a capture is attempted. The capture
      must wait for the writer; it never returns mixed data.
    - Separately, inject a write **between two read batches** of one snapshot.
      Both batches must return the snapshot's generation.
    - A soak test applies forward blocks and rollbacks under concurrent
      acquisition, and no snapshot ever mixes two tips.
  - **Ownership gate:**
    - Complete a rebuild, let the rebuild release its local resources, then
      admit a transaction spending a canonical UTxO outside the rebuild's read
      set. The read succeeds from the published state's retained snapshot.
    - Keep a frozen shadow view alive while the mempool state is replaced
      twice. It still reads its original generation.
    - After all holders finish, the live-snapshot metric returns to the
      published baseline: no leaked handles.
  - **Lookup gate:** a fresh pool registration, deregistration followed by
    registration, and a first-time DRep registration all reach the rules as
    confirmed absence. An injected store failure yields `LedgerStateUnavailable`.
  - **Ticking gate:** for the last N preprod and preview epoch boundaries, the
    `TickedLedgerView` dry run equals the state `ledger-state` persists after
    the real boundary, for every validation-visible value in §3.
  - **Overlay tests:** register→delegate across two transactions,
    register→deregister refund, proposal→vote in one block, UTXOW sees
    pre-certificate refunds, snapshot isolation.
  - **Effects cross-check:** `TxEffectsDeriver` agrees with `ledger-state`
    block application for a sample of synced **PV ≥ 10** blocks. Earlier blocks
    are excluded: before PV10 Haskell's DRep reverse-delegation index could
    diverge from the forward delegations (`Deleg.hs:363-373`, repaired at the
    PV10 fork by `HardFork.hs:70-104`), and the overlay clears delegations of a
    deregistered DRep from the forward side.

#### Phase 1 results: engine selection and shadowing (step 1d, 2026-09-29)

- **Engine SPI.** `LedgerValidationEngineFactory` (ServiceLoader) with an
  `EngineContext`: `scalus` (scalus-bridge) and `amaru` (amaru-validator, only
  in `-PwithAmaru=true` builds). `java` is refused at startup until Phases 3–5;
  `amaru` without its module, an admission engine listed as a shadow, and
  `supplementary-rules-enabled` with a non-`scalus` engine also stop startup.
- **Admission** with a non-`scalus` engine: one `ADMISSION` snapshot per
  admission, acquired before the mempool lane, ticked to the slot after the
  tip; UTxOs through the mempool resolver over that snapshot (chained outputs
  until Phase 6); rule `MEMPOOL`, origin `LOCAL` (REST, n2c) or `PEER` (n2n);
  the outcome maps to `ValidationResult`. Block selection stays on the legacy
  validator (Phase 6).
- **Scalus engine adapter** over the view, with an overlay-aware
  `LedgerStateProvider`; only Plutus evaluation outcomes are phase 2 (the
  legacy class-name labelling is fixed there, the legacy path is unchanged).
- **Scalus `ScriptPhaseEvaluator`**: `MalformedScriptWitnesses`,
  `MalformedReferenceScripts` (the transaction's own outputs, as Haskell),
  `CollectErrors` (`NoCostModel`; `BadTranslation` for Conway-only features,
  certificates and purposes under V1/V2, V1 inline datums and reference
  scripts, Byron addresses, V3 non-disjoint reference inputs from PV 11, and
  `TimeTranslationPastHorizon`). The horizon is the hard-fork combinator's: the
  first epoch boundary at or after `next(tip) + 3k/f`, exclusive
  (ouroboros-consensus `HardFork/History/Summary.hs`, `Shelley/Ledger/Ledger.hs`
  `StandardSafeZone`). The Amaru scenario gate passes 276/276 in both
  `phase2: amaru` and `phase2: scalus` mode.
- **Shadows**: bounded pool, frozen views owning a snapshot reference, the
  live-snapshot cap, the max-age cancel, disagreement counters, logs and JSON
  replay bundles (`ShadowDumpBundle`).
- **Decision 6a — fresh devnets.** Before Yano persists its Conway genesis
  bootstrap (a devnet starting in Conway does so at its first boundary), the
  canonical view answers from the Conway genesis: constitution and guardrail,
  committee members and threshold (`Conway/Translation.hs:169-178`), the
  initial treasury Yano stores in its first AdaPot, empty roots, proposals and
  dormant epochs. Nothing is persisted (invariant 8). A genesis with
  `initialDReps` or `delegs` (`Conway/Transition.hs:82-92`) makes account and
  DRep reads unavailable until the bootstrap is persisted. A ticked view across
  the bootstrap boundary itself stays unavailable.
- **Decision 6b — producer window.** When a producer has applied the next
  boundary before forging (ledger epoch one ahead of the next slot's epoch),
  admission validates at the first slot of the ledger epoch on the unticked
  canonical view; larger gaps stay unavailable.
- **Dry run off the lane.** A ticked boundary dry run is computed when the
  admission snapshot is acquired, before the mempool lane, and shared by every
  snapshot of the same canonical generation.
- **Native image (Phase 7/ADR-057 Phase E).** The engine factories are
  `META-INF/services` entries; native builds must register them (GraalVM's
  service-loader support covers classpath services; the Amaru AOT classes are
  Phase E).

### Phase 2 — Conformance harness first

- Add the `conformanceTest` source set, the Amaru scenario loader and
  (constructor, PV) mapping, the coverage-matrix tooling and the mutation
  framework.
- Publish a **baseline** report: the measured pass rate for Scalus, Scalus plus
  supplementary rules, and the current Java rules.

#### Phase 2 results: conformance harness and baseline (2026-09-29)

- **Harness** (`ledger-conformance`, deviations in §8): an engine-agnostic
  runner (case → `InMemoryLedgerView` → rule `LEDGER`, origin `SYNC`) records
  per case the expected and actual verdict and `RULE.Constructor`. Legacy
  validators are adapted as the node runs them: the legacy Scalus path sits
  behind `TransactionValidationService`'s pre-checks (a transaction CCL cannot
  decode is `ENGINE.DecodingFailure`, an unresolvable input
  `UTXO.BadInputsUTxO`), with resolved inputs as CCL UTxOs, a script supplier
  and a `LedgerStateProvider` over the view. Their free-text and Scalus
  class-name errors are named after Haskell where the text allows, otherwise
  reported as `UNMAPPED`.
- **Constructor matching**: an engine's first failure must be the expected
  constructor. Where Haskell always reports a fault with several constructors,
  the case carries Haskell's ordered list and any constructor of it matches,
  so the harness never pushes an engine away from Haskell's order. Today that
  is the empty-collateral fault: Babbage `feesOK` part 2 runs
  `validateTotalCollateral` with `sequenceA_` (`Babbage/Rules/Utxo.hs:226-239`),
  so Haskell reports `[InsufficientCollateral, NoCollateralInputs]` (scenario
  00278 and the `no-collateral` mutant; a single-fault `NoCollateralInputs`
  mutant cannot exist).
- **Coverage matrix**: 88 constructors, 85 in scope; `@Covers` scan and
  scenario mapping generate `ledger-rules/docs/conway-rule-coverage.md`. Today
  16 constructors have a test and a scenario, 40 only a scenario, 29 neither.
  Gaps are reported; `-Pconformance.strict=true` fails on them (and on test
  classes the scan cannot inspect) from the Phase 5 gate.
- **Mutation framework**: signed Conway transactions from Amaru's corpus test
  keys against a preprod-like PV 10 view; each edit is rebuilt (exact minimum
  fee, hashes) and signed again over the body as encoded. 16 mutants cover UTXO
  and UTXOW basics; Amaru rejects each with its constructor only.
- **Scalus engine fixes found by the baseline** (`scalus-bridge`, with tests on
  scenarios 00040, 00059, 00060 and 00031):
  - *Transaction size*: Scalus's `TransactionSizeValidator` re-encodes the
    four-element transaction, `is_valid` included; Haskell sizes
    `toCBORForSizeComputation` (`Alonzo/Tx.hs:324-331, 432-443`, Conway
    `Tx.hs:86`), the list header plus the stored body, witness and auxiliary
    bytes. `YanoTransactionSizeValidator` replaces it in `YanoCardanoMutator`,
    so the legacy path gets the same fix (its only behaviour change: a
    transaction exactly at `maxTxSize`, scenario 00040, is valid).
  - *`DRepException`* is now named `GOVCERT.ConwayDRepAlreadyRegistered`,
    `ConwayDRepNotRegistered`, `ConwayDRepIncorrectDeposit` or
    `ConwayDRepIncorrectRefund` (`GovCert.hs:211-219, 236-242, 257-258`)
    instead of `ENGINE.ScalusEngineFailure`.
  - *Decoding*: a transaction Scalus cannot decode is `ENGINE.DecodingFailure`
    (`TransactionDecodingException`), not an engine crash.
- **Baseline** (table in "Related decisions and evidence"; full report in
  `ledger-conformance/docs/baseline-2026-09.md`). Findings:
  - *Legacy Scalus path*: its strict decoder rejects 106 scenarios Haskell
    decodes (indefinite-length maps and arrays), 46 of them valid; with 00279
    (a Byron collateral address the CCL-UTxO conversion cannot parse) it
    rejects 47 valid scenarios. The engine path's definite-length fallback
    decodes all but 8 (tag-258 sets inside proposal procedures, an indefinite
    map in update-committee proposals, and the empty input set of 00074).
    Legacy errors keep only the Scalus class name, so `StakeCertificates` and
    `StakePool` failures cannot be named. It accepts 59 invalid scenarios (no
    GOV, GOVCERT, delegatee or LEDGER-level checks).
  - *Supplementary rules*: close most GOV gaps (12 invalid accepted instead of
    59) but reject 9 more valid scenarios: no intra-transaction state (a vote
    by a hot key authorised earlier in the same transaction, register then
    deregister or delegate), committee members without a term, zero-amount
    treasury withdrawals, SPO votes on security-group parameter changes.
  - *Scalus engine*: 79 invalid scenarios accepted (58 of 59 GOV, 8 of 12
    GOVCERT, 8 DELEG delegatee-existence scenarios, and the LEDGER checks
    `ConwayTreasuryValueMismatch`, `ConwayTxRefScriptsSizeTooBig`,
    `ConwayWdrlNotDelegatedToDRep`); 4 valid rejected, all decoding (00031,
    00144–00146). Known gap: `InputSetEmptyUTxO` cannot be reported, because
    Scalus's decoder rejects an empty input set (00074 comes out as
    `ENGINE.DecodingFailure`).
  - *Copied Java rules*: they re-serialise the transaction, so signatures are
    checked over a re-encoded body (104 valid scenarios rejected with
    `InvalidWitnessesUTXOW`) and the minimum fee uses the re-encoded size with
    the `is_valid` byte (both valid mutation bases rejected with
    `FeeTooSmallUTxO`). They are independent checks, so the expected
    constructor is often present but not first (117 found, 31 first). Phase 3
    must hash and size the original bytes.
  - *Amaru*: 275/276 plus the PV 9 scenario refused by design (invariant 6); no
    disagreement with the Haskell expectations.
  - The ADR's 57/88 estimate: the Scalus engine reports 35 of the 56
    constructors the scenarios and mutants exercise; the remaining 29
    constructors need Phase 3–5 mutants before any engine can be measured on
    them.
- **CI**: the default build runs `:ledger-conformance:test` without the corpus
  (mutation matrix, coverage scan). `amaru-wasm.yml` gains a `conformance` job
  (triggered also by changes to `ledger-conformance`, `ledger-rules` and
  `scalus-bridge`) that runs it with the module it just built and the
  scenarios at the pinned tag, and uploads the reports.

### Phase 3 — UTXO, UTXOW, UTXOS

- Implement the transition skeleton, typed failures, PV gates, the three
  families, the Scalus `ScriptPhaseEvaluator`, and the REAPPLY static/dynamic
  labelling.
- Gate: all UTXO-family scenarios pass, and their matrix rows are complete.

### Phase 4 — CERTS, DELEG, POOL, GOVCERT

- Gate: the certificate scenarios pass, and their matrix rows are complete.

### Phase 5 — GOV, LEDGER pre-checks, MEMPOOL

- Implement `MEMPOOL` as the admission entry point in front of `LEDGER` (§4,
  step 0).
- Gates:
  - all Amaru scenarios pass, or each remaining one has a recorded,
    Haskell-backed divergence;
  - the coverage matrix is 100%;
  - MEMPOOL fixtures:
    - an all-inputs-spent duplicate reports **only** `ConwayMempoolFailure`;
    - at PV10, an unelected-committee vote is judged against the incoming
      committee state, even when the same transaction's certificates would
      change it;
    - at PV11 the check moves to `GOV`;
    - rule `LEDGER` (block selection, shadow sync) never reports a MEMPOOL
      failure.

### Phase 6 — Runtime overlays

- Add the immutable `MempoolLedgerState`, synchronous truncate-and-reapply on
  removal, the off-lane rebuild that publishes only on append-only descent with
  snapshot ownership transfer, rebuild triggers for forward blocks, rollbacks and epoch
  boundaries, per-transaction `ValidatedTx` provenance with the shared
  invalidation rules, the phase-2-invalid rejection policy, and the
  block-production overlay.
- Remove `BlockBuildUtxoOverlay` and the legacy validator overload.
- Migrate the mempool and selector tests.
- Gates:
  - **pending-mempool hard fork:** transactions sit in the mempool across a
    protocol-major change and across a cost-model parameter change. Every
    re-application is a full validation (including Plutus), and transactions
    that became invalid are dropped;
  - **publication race:** a forward block, a rollback, and a slot crossing into
    a new epoch are each injected during the fold (step 3). This is tested with
    the mempool unchanged, and separately with appends only, while the
    follow-up rebuild worker is **deliberately held**, so a queued follow-up
    can't mask a stale publication. A separate case publishes a block **during
    append reconciliation** in step 4. In every case the candidate is
    discarded, never published;
  - **multi-generation lag:** several blocks are published during one fold.
    The mempool state stays internally consistent, provisional admissions are
    re-validated by the next rebuild, and a transaction invalid at the tip is
    never selected into a block;
  - **synchronous fallback and catching up:**
    - Force the discard limit, then check that the fallback acquires its
      snapshot without holding the lane: a lock-order assertion fails the test
      if the gate and the lane are ever held together.
    - Advance the canonical tip during the fallback. It retries, then enters
      `CATCHING_UP`.
    - While `CATCHING_UP`, admission returns the retryable status and block
      selection skips the mempool.
    - The next successful publication returns the mempool to `READY`;
  - **mempool removals during a rebuild:** removal, TTL eviction and clear are
    each injected during an off-lane rebuild, with the canonical tip unchanged.
    The case includes stake register (A) → delegate (B) with independent UTxO
    inputs, where A is evicted. Checks:
    - removed transactions are never resurrected;
    - their effects are absent;
    - B is re-validated and dropped;
    - a concurrent append plus removal also takes the discard-and-restart
      path;
    - an append-only interleaving publishes with the appended transactions
      re-validated on the new base;
  - **removal without a rebuild:** evicting A synchronously truncates and
    re-applies the suffix, so an admission immediately after the eviction
    can't observe A's registration;
  - **phase-2-invalid:** an `isValid=false` transaction, and an `isValid=true`
    transaction whose script fails, are both rejected from local and peer
    origins, and no Yano-produced block contains a non-empty `invalid_txs`;
  - devnet end-to-end with dependent chains in one block and in the mempool
    (stake register→delegate→vote, DRep register→delegate, proposal→vote,
    register→deregister);
  - **an epoch crossing with chains pending**, including a withdrawal of the
    new epoch's rewards and a vote on a proposal that expires at the boundary;
  - rollback while chains are pending;
  - the Haskell follower stays in lock-step (`test-haskell-sync`);
  - admission p99 and rebuild time within the budget (decision 3 below).

### Phase 7 — Blueprint vectors, differential, shadow sync, native parity

- Gates:
  - blueprint Conway vectors at or above Amaru's recorded pass set;
  - zero unrecorded Java-vs-Amaru divergences;
  - zero false rejections in shadow sync over preprod, preview and mainnet from
    the PV10 boundary to the tip.

### Phase 8 — Switch the default, clean up

- Set `engine: java`. Keep `scalus` selectable, and as a default shadow engine
  for one release.
- Remove the deprecated keys and profile overrides. Migrate or delete the
  Scalus-specific tests that no longer apply (`ScalusBasedTransactionValidatorTest`,
  `CertStateBridgeTest`, `YanoValueNotConservedUTxOValidatorTest` stay while
  `scalus` stays selectable).
- Update docs, the release notes and the testkit end-to-end profiles
  (`DRepValidationTestProfile`).

## Acceptance criteria

- The coverage matrix is 100% for the Conway PV10–11 leaf constructors, and every
  PV gate is tested on both sides.
- The Amaru scenarios, blueprint vectors, mutation matrix, shadow sync and
  native parity gates all pass.
- Dependent chains in the mempool and in one block, and across an epoch
  boundary, validate identically under `java` and `amaru`, and under `scalus`
  for the state it models. A Haskell follower accepts every Yano-produced block
  in the Phase 6 matrix.
- With `engine: java`, the submit path calls Scalus only for phase-2 script
  execution.
- Canonical ledger-state, reward, AdaPot and ratification outputs are
  unchanged. The existing verification suites stay green.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| The ticked-view dry run diverges from real boundary processing | Phase 1 ticking gate against persisted boundaries; fail closed on values not computable as a dry run |
| Value conservation, script integrity hash or min-UTxO diverges from Haskell | Amaru scenarios, blueprint vectors, shadow sync, Amaru differential |
| Mempool rebuild latency under load | Off-lane rebuild and swap; REAPPLY; measured budget gate |
| A rebuild or snapshot mixes two canonical tips | Canonical write gate around whole-block application; snapshots acquired under the read lock; paused-writer and mid-read write gates |
| A published or shadow view reads through a released snapshot | Reference-counted `CanonicalSnapshot`; ownership transferred on swap; retire-then-release; ownership gate with leak metric |
| Removed transactions resurrected, or their effects kept | Immutable `MempoolLedgerState`; synchronous truncate-and-reapply on removal; rebuild publishes only on append-only descent |
| Mempool unavailable (`CATCHING_UP`) during fast catch-up sync | Retryable status, never a stale publication; health and metric visibility; exits on the first successful publication |
| A cached phase-2 verdict survives a hard fork or cost-model change | `ValidatedTx` provenance; shared invalidation rules; pending-mempool hard-fork gate |
| Valid first-time registrations rejected as "missing state" | Three-outcome `Lookup`; Phase 1 lookup gate |
| A phase-2-invalid transaction reaches a block the builder can't encode | Rejected at admission until the follow-up ADR lands |
| PV11 constructor and ordering changes | (constructor, PV) keyed matrix; tests on both sides of each gate |
| The scope makes the final PR hard to review | Stacked PRs into the integration branch, each gated and reviewed |

## Rollback plan

- **Behaviour.** Until Phase 8, `engine: scalus` is the default, so a
  regression in the Java engine is contained by configuration. After Phase 8,
  set `yano.validation.engine=scalus` to roll back without a redeploy of code.
- **Module rename.** Phase 0 is mechanical and isolated in S1. Reverting S1
  restores `ccl-ledger-rules`. The BOM entry for `yano-ccl-ledger-rules` is
  dropped only in the final merge, and the release notes list it.

## Alternatives considered

- **Keep Scalus and enable the supplementary rules.** It still misses about a
  dozen constructors, has no intra-transaction or same-block state, and
  Scalus's `State` can't carry governance state or the treasury.
- **Contribute the missing rules to Scalus.** GOV needs an upstream redesign of
  Scalus's state, in Scala 3, on upstream's schedule. The decode, projection and
  error-mapping costs remain.
- **Make Amaru-wasm the only engine.** ADR-057 keeps it optional. The API is
  pre-1.0 and changes often, it needs nightly Rust and a wasm C toolchain, it
  runs about 15–20× slower than native, and it can't validate pre-Conway
  history. It is more valuable as an oracle.
- **Include PV9 (Conway bootstrap).** It is small, but Amaru, the oracle,
  doesn't support it, and no live network is still at PV9. It can be added
  later with its own gates.
- **Full Byron→Conway block validation.** This is a separate decision. The
  transition, ticking and overlay here are its foundation.

## Consequences

### Positive

- Complete, Haskell-named Conway admission rules, owned in Java.
- Correct intra-transaction, mempool, same-block and epoch-boundary semantics
  under every engine.
- Scalus is reduced to a phase-2 evaluator.
- A reusable conformance harness that also measures Scalus and Amaru.

### Negative

- Large scope: about 14–22 person-weeks for the rules, 6–10 for the harness,
  plus the ticked view. That is a working estimate, not a commitment.
- Yano owns every future hard fork (about 4–8 person-weeks per era, including
  Dijkstra).
- Divergence risk remains in value conservation, the script integrity hash and
  min-UTxO. The oracles mitigate it; they don't eliminate it.

## Decisions (accepted 2026-09-28)

1. **Scope:** Conway **PV10+ only**. The PV9 bootstrap phase is out of scope.
2. **Ticked base view:** an in-memory dry run of the validation-visible boundary
   effects (§3), with its Phase 1 gate. Accepted.
3. **Mempool budget.** Initial targets, measured in Phase 6 at the configured
   maximum mempool size (`yano.tx.mempool.max-txs` = 10,000; JVM, warm).
   Changing them needs a recorded reason in this ADR.

   | Operation | Target |
   |---|---|
   | Admission latency, `engine: java`, transaction without Plutus | p99 ≤ 20 ms |
   | Admission latency, `engine: java`, transaction with Plutus | p99 ≤ 20 ms plus script evaluation time |
   | Synchronous truncate-and-reapply on removal (lane held) | ≤ 50 ms for a 1,000-transaction suffix; ≤ 500 ms for 10,000 |
   | Off-lane rebuild with re-application only | ≤ 2 s for 10,000 transactions |
   | Off-lane rebuild that needs full validation (hard fork or cost-model change) | No latency target; `CATCHING_UP` is the accepted fallback |

4. **Delivery:** one PR (#155), reviewed phase by phase by sub-agent reviewers,
   and committed and pushed after each reviewed phase (Branch model above).
5. **Where `ledger-rules` lives:** it stays in the Yano project. There is **no
   plan** to move it to CCL.
6. **Phase-2-invalid transactions:** rejected from every origin in this PR.
   Collateral-collecting admission, with block-builder `invalid_txs` support,
   is deferred to a follow-up ADR.
7. **Package root:** `org.yanoproject.ledger.rules`, which also renames the
   existing `org.yanoproject.ledgerrules` API package.
