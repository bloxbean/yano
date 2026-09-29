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
   - **Scope:** Conway, **protocol version 9 (the bootstrap phase) and later**
     (Phase 5b; until then PV10 and later, like Amaru). Amaru, the oracle,
     stays PV10+.
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
7. **Conway, PV9 and later.** The Java engine returns `EraNotSupported` for
   non-Conway bodies or a ticked protocol version below 9 (or above the latest
   the pinned rules know, 11), and Yano never falls back to "accept". This
   applies to mempool admission and block production. The bootstrap phase (PV9)
   is in scope since Phase 5b: its differences are protocol-version ranges on
   the checks (`PvRange.BOOTSTRAP` / `POST_BOOTSTRAP`). Amaru stays PV10+
   (invariant 6), so PV9 evidence is Haskell source, not the oracle.
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

   At PV9 the bootstrap disallow rules (`DisallowedProposalDuringBootstrap`,
   `DisallowedVotesDuringBootstrap`) run too (Phase 5b, invariant 7).
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

Protocol versions are data. Since Phase 5c each check is a unit in a
**versioned rule set**: `pv9 = base`, `pv10 = pv9.with(delta10)`,
`pv11 = pv10.with(delta11)`. The engine picks the rule set from the ledger
protocol major of the state it validates against, and fails closed when no rule
set exists for that major. A version that changes behaviour supersedes a check
instead of editing it. The rule families contain no version branches. See
"Phase 5c results" for details. The coverage matrix, the mutation world matrix
and the Amaru differential mapping all key on (constructor, PV).

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

#### Phase 3a results: engine skeleton, UTXO and UTXOS (2026-09-29)

- **Raw transaction model** (`org.yanoproject.ledger.rules.conway.tx`).
  `RawTransaction` reads the original bytes: the body, witness-set and
  auxiliary-data slices, the transaction id (blake2b-256 of the body slice),
  Haskell's size (`toCBORForSizeComputation`: `1 + body + witnesses + (aux |
  null)`, the same rule as `YanoTransactionSizeValidator`), every output and
  the collateral return as slices (their lengths are the `Sized` sizes of the
  minimum-UTxO rule), the scalar body fields (a validity bound is absent or
  present, never `0`), inputs, withdrawals, mint, and the redeemers keyed as
  Haskell's `Redeemers` map (a later duplicate key wins in both forms:
  `decodeMapRedeemers` reverses its accumulator before `Map.fromList`,
  Alonzo/TxWits.hs:571-577), never empty, keyed by a `Word8` tag 0–5 and a `Word32` index.
  Definite and indefinite containers and tag-258 sets are read (indefinite
  byte strings with definite chunks only). It mirrors Conway's version-9
  decoder, and everything Haskell would not decode is
  `ENGINE.DecodingFailure`: unknown body or witness-set keys, duplicate keys
  in any map (body, witness set, withdrawals, mint and value policies and
  asset names, output maps), empty set-like body fields (certificates,
  withdrawals, mint, collateral, required signers, reference inputs, votes,
  proposals), a zero donation, empty witness lists, zero multi-asset
  quantities or empty asset maps, out-of-range mint quantities, fixed-size
  vkeys, signatures and hashes, duplicate inputs, missing required fields,
  and output addresses Haskell's strict decoder refuses (unused header bits,
  lengths, left-over bytes, pointers that do not fit `Word32`/`Word16`, Byron
  addresses with a bad CRC or shape). Not checked: the compact-representation
  bound on multi-assets (`isMultiAssetSmallEnough`). Validity bounds are
  `Word64`. The CCL `Transaction` is kept for structure only
  (certificates, proposals, votes) and is never re-serialised.
- **Transition** (`ConwayLedgerTransition`, `JavaLedgerValidationEngine`,
  `JavaEngineFactory`, name `java`). Rooted at `MEMPOOL` (with `MempoolRule`)
  or `LEDGER`; `LEDGER` pre-checks, `CERTS` and `GOV` are `SubRule` slots that
  record nothing until Phases 4–5, `UTXOW` is the script preparation only
  until Phase 3b. The preparation runs whenever a Plutus script can be needed:
  redeemers, a Plutus witness, or a Plutus reference script on a resolved
  spending or reference input (and own outputs' reference scripts, for
  well-formedness). A needed Plutus script without a redeemer is therefore
  `UTXOS.CollectErrors [NoRedeemer …]` now (the Scalus evaluator reports
  `NoRedeemer`); **3b dependency:** `UTXOW.MissingRedeemers`, which Haskell
  reports for the same fault and lists first, comes with Phase 3b. Without an
  evaluator such a transaction fails closed. `UTXOW → UTXO → UTXOS` are nested as in Haskell.
  - *Failure order.* `RuleFrame` reproduces `small-steps` exactly: a failing
    predicate prepends its failures reversed, a sub-rule prepends its list one
    failure at a time (`Extended.hs:668-731`), and nothing reverses the final
    list. Rooted at `LEDGER`, `UTXOW` failures therefore come first in
    execution order, then `UTXOS`, then `UTXO`'s in reverse execution order;
    rooted at `MEMPOOL` everything flips once more. The Phase 2 note that
    Haskell reports `[InsufficientCollateral, NoCollateralInputs]` is the
    `MEMPOOL` order; the `LEDGER` list is `[NoCollateralInputs,
    InsufficientCollateral]` (both are accepted by the harness).
  - *`whenFailureFree`* is the transition-wide "is failing" flag, so Plutus
    runs only when no rule of the transition has failed.
  - *PV gates and REAPPLY labels are data*: `ConwayPredicate` gives each
    constructor its rule, PV range, static/dynamic label and Haskell location;
    `TransitionContext.check` skips a check outside its PV range, and a static
    check on re-application. A test checks the enum against the catalogue.
  - *REAPPLY* (`ReapplyPolicy`, §6): re-application needs `previous` for the
    same transaction and `is_valid` flag, the same protocol major version and
    phase-2 environment digest, the same resolved spending, collateral and
    reference inputs, and an origin rule (`SYNC` verdicts are re-used for
    `SYNC` only). **API change:** `ValidatedTx` gains `resolvedInputsDigest`
    (the old seven-argument constructor remains and records none; such a
    `previous` is never re-applied).
  - *Phase-2 SPI change:* `ScriptPhaseEvaluator.collect(...)` separates
    Haskell's collection step (`?!: CollectErrors`, dynamic, also after other
    failures and on re-application) from execution (`when2Phase $
    whenFailureFree`, static). The default runs `evaluate`; the Scalus
    evaluator overrides it without running scripts.
  - *Gating:* the factory refuses to create the engine (as admission or
    shadow) unless `yano.validation.java-engine.experimental=true`; the node
    stops at startup with the old "'java' is not available yet" message.
- **UTXO** (`conway.utxo.UtxoRule`, `MinFee`, `ValueBalance`): all 21
  reachable constructors in Haskell's order (`babbageUtxoValidation`,
  Babbage/Rules/Utxo.hs:342-412): exact minimum fee (size, ExUnits price
  rounded up, tiered reference-script fee over spending ∪ reference inputs,
  checked against Haskell's `tierRefScriptFee` unit vector); collateral parts
  3–7 only with redeemers; bad inputs over spending ∪ collateral ∪ reference;
  value conservation against the pre-certificate state with Haskell's
  deposit/refund functions (`shelleyTotalRefundsTxCerts`' same-transaction
  registration set, recorded deposits, DRep refunds from the certificate,
  pool deposits only for unregistered pools, proposal deposits, donation,
  withdrawals, mint); minimum UTxO from each output's original size; value
  size from Haskell's re-serialisation; bootstrap attributes; the three
  network checks; `BabbageNonDisjointRefInputs` at PV 9–10 only.
- **UTXOS** (`conway.utxos.UtxosRule`): `CollectErrors` from the evaluator's
  preparation; `ValidationTagMismatch` in both directions (no scripts pass
  trivially, so `isValid = false` without scripts is `PassedUnexpectedly`);
  `isValid = false` transactions get collateral-only effects and, except from
  `SYNC`, `ENGINE.Phase2InvalidTxNotSupported`.
- **Gate** (`JavaEnginePhase3aGateTest`, now `JavaEnginePhase3GateTest`): the 148 scenarios expected to pass
  or to fail in `UTXO`/`UTXOS` (114 + 34): 147 match Amaru's expectation
  (verdict and first constructor, or a constructor of Haskell's list), 1
  (00280) matches Haskell where Amaru diverges (below). The mutation matrix grows to 28
  mutants; the Java engine reports exactly Haskell's failure list on every
  `UTXO`/`UTXOS` mutant and accepts both bases. Baseline row (regenerated
  `ledger-conformance/docs/baseline-2026-09.md`): `java-engine` 165/276
  verdicts, 154/276 constructors, 22/28 mutants (the 6 misses are `UTXOW`),
  114/114 pass scenarios, about 0.25 ms per scenario. Coverage: every
  reachable `UTXO`/`UTXOS` constructor has a `@Covers` test.
- **Haskell-vs-Amaru findings** (Haskell wins; divergences are recorded in the gate
  test and the mutation matrix):
  - *Forecast horizon.* Haskell's `UTXO.OutsideForecast` is unreachable in
    Conway at `f649f975`: `validateOutsideForecast` translates the bound with
    `unsafeLinearExtendEpochInfo slotNo ei` (Alonzo/Rules/Utxo.hs:377-386,
    cardano-slotting `EpochInfo/Extend.hs`), which cannot fail for a
    translatable current slot. The Java check is kept, in Haskell's order, as
    a no-op; the catalogue marks the constructor unreachable at the pin (84
    constructors in scope, no negative test). The horizon is still enforced,
    by the script context: Conway's mempool (`defaultApplyTxWithValidation
    @"MEMPOOL"`, Conway.hs:50-59, `mkStAnnTx (epochInfo globals)`,
    Shelley/API/Mempool.hs:283-293) and `LEDGERS` (Babbage/Rules/Ledgers.hs:
    126-133) use the unextended epoch info (the linear extension is
    Alonzo-era only, Alonzo.hs:83-90), so `transValidityInterval`
    (Alonzo/Plutus/TxInfo.hs:252-274) fails and `UTXOS` reports
    `CollectErrors [BadTranslation TimeTranslationPastHorizon]`
    (Babbage/Rules/Utxos.hs:143, 206). The Scalus evaluator's
    `ForecastHorizon` check provides it; the node wires it
    (`ValidationEngineBootstrap`) and so does the harness (from each case's
    era history). Amaru's Haskell checker names that failure
    "OutsideForecast" (`ValidatePhaseOne/Run.hs:443-445`), so the corpus
    alias now maps `OutsideForecast` to `UTXOS.CollectErrors`
    (`AmaruCorpusNames`, and in `amaru-validator-wasm` `failure.rs` and
    `tests/amaru_scenarios.rs`; the module was rebuilt and `cargo test`
    passes all scenarios). Scenario 00088 is `UTXOS.CollectErrors`, no longer
    a divergence.
  - *Horizon basis.* The horizon is based on `next(tip)` of the state the
    transaction is applied to. The engine uses `ValidationEnv.currentSlot`
    (`TransitionContext.forecastBasisSlot`), exact for `MEMPOOL` and for the
    Amaru fixtures; for block validation it is the block's slot, which can
    only make the horizon later. Phases 6 (block building) and 7 (shadow
    sync) must pass the tip explicitly before they use the engine.
  - *Scenario 00280*: Amaru names an oversized Byron attribute
    `OutputTooBigUTxO`; the value is 5 bytes, so Haskell reports
    `OutputBootAddrAttrsTooBig` (mutant `boot-addr-attrs-too-big` likewise).
  - *Non-ADA collateral*: Amaru maps it to `ValueNotConservedUTxO`; Haskell
    reports `CollateralContainsNonADA` (mutant `collateral-non-ada`).
  - *First failures*: Amaru stops at its own first failure, Haskell lists
    every failure. Scenario 00050 is `[ValueNotConservedUTxO, BadInputsUTxO]`
    in Haskell (the unknown input is left out of the consumed value), 00124
    `[ValueNotConservedUTxO, InsufficientCollateral]`; both are now in
    `HaskellFailureLists`.
- **Follow-up for the evaluator** (not changed here): the Scalus evaluator
  does not report `BadTranslation (TranslationLogicMissingInput …)` for a
  script transaction with an unknown spending or reference input; that only
  changes which failure comes first.
- **Precondition**: prices and `minFeeRefScriptCostPerByte` reach the rules
  as CCL `BigDecimal`s and are used exactly; that is exact for every value
  with a terminating decimal expansion (all public networks and fixtures). A
  non-terminating rational (for example 1/3) needs numerator/denominator in
  the view first (`ConwayParams`).
- **Deviation**: the mutation world, builder and test keys moved from
  `ledger-conformance` to `ledger-rules`' test fixtures
  (`org.yanoproject.ledger.rules.fixtures.tx`) so the rule unit tests use
  them; mutants can record an Amaru divergence (`Mutation.amaruReports`).

#### Phase 3b results: UTXOW (2026-09-29)

- **`UTXOW`** (`conway.utxow.UtxowRule`): all 18 constructors, as `babbageUtxowTransition`
  (Babbage/Rules/Utxow.hs:328-391) runs them, each `runTest` / `runTestOnSignal` one predicate whose
  `sequenceA_` failures accumulate in order: `ScriptWitnessNotValidatingUTXOW` (dynamic in Conway);
  `ExtraneousScriptWitnessesUTXOW`, `MissingScriptWitnessesUTXOW`; `UnspendableUTxONoDatumHash`,
  `MissingRequiredDatums`, `NotAllowedSupplementalDatums`; `ExtraRedeemers`, `MissingRedeemers`;
  `InvalidWitnessesUTXOW` (static); `MissingVKeyWitnessesUTXOW`; the metadata checks (static); the
  malformed-script checks (static); then the integrity check, `PPViewHashesDontMatch` below protocol version 11
  and `ScriptIntegrityHashMismatch` (with the expected preimage) from 11. Then `UTXO` as before.
  - *Scripts.* `scriptsProvided` = witness scripts ∪ reference scripts of the spending and reference inputs
    (`getBabbageScriptsProvided`, reference wins in the union); `scriptsNeeded` = `getConwayScriptsNeeded`
    (Conway/UTxO.hs:62-106): spending inputs (Set order) with a script payment credential, withdrawals (Map
    order: network, script before key, hash) with a script credential, every certificate by position (no Alonzo
    dedup) with `getScriptWitnessConwayTxCert` (tag 0, registration without deposit, needs none), minted policies,
    voters (Ord `Voter`: committee, DRep, pool; script before key) with a script credential, and the guardrails
    policy of parameter-change and treasury-withdrawal proposals. Indices count every element. Native scripts
    are decoded from their original bytes and evaluated with `evalTimelock` (vkey witnesses only, validity
    interval with absent bounds failing).
  - *Key witnesses.* `getConwayWitsVKeyNeeded` (:174-199): certificate authors (pool id for pool certificates,
    none for tag 0), payment keys (or bootstrap roots) of spending ∪ collateral inputs, pool owners, key
    withdrawals, required signers, key voters. **Conway ignores the certificate state here**
    (`getWitsVKeyNeeded _ = …`, :148): the catalogue note and the pinned table said "uses the pre-CERTS
    certState", corrected. Provided = vkey witness hashes ∪ `bootstrapWitKeyHash` (blake2b-224 of SHA3-256 of
    `83 00 82 00 58 40 ‖ vkey ‖ chain code ‖ attributes`).
  - *Signatures* over the transaction id with libsodium's `crypto_sign_ed25519_verify_detached` rules
    (`utxow.Ed25519`: canonical `S`, no small-order `R` or key, canonical key, then the cofactorless check of
    CCL's provider, which compares the recomputed `R` bytewise). Failures list vkey witnesses (Set order: key hash,
    then signature hash) then bootstrap witnesses (Set order: key hash).
  - *Datums.* Required hashes from spending inputs locked by a provided Plutus script with a datum hash; no datum
    under a PlutusV1/V2 script is `UnspendableUTxONoDatumHash` (V3 exempt, CIP-69); supplemental = datum hashes
    of all outputs (with the collateral return) and of the reference inputs' outputs.
  - *Redeemers.* `extSymmetricDifference` of the redeemer keys and the purposes of needed scripts provided as
    Plutus: extras in redeemer-map order, missing in `scriptsNeeded` order. A needed Plutus script without a
    redeemer is `[UTXOW.MissingRedeemers, UTXOS.CollectErrors [NoRedeemer]]` (`HaskellFailureLists`).
  - *Script integrity* (`utxow.ScriptIntegrity`): absent without redeemers, datums and used languages; else
    blake2b-256 of the redeemers' original bytes (or `a0`, the memoised empty `Redeemers` at protocol version 9),
    the datums' original bytes (if any) and `encodeLangViews` of `plutusLanguagesUsed` (the languages of the
    needed, provided Plutus scripts): a definite map sorted shortlex by tag; PlutusV2/V3 tag `01`/`02`, value the
    definite list; PlutusV1 tag `41 00` and value a byte string holding the indefinite list (Alonzo's quirk); a
    missing cost model is `null`. Cost models come **only** from the view's raw lists (`costModelsRaw`, the
    ledger's parameter order): the named map's key order is not the ledger's, so a view without raw lists (or
    with a language only in the named map) is `ENGINE.LedgerStateUnavailable`. The `LedgerView.protocolParams()`
    Javadoc states the contract, and the node's views now carry the raw lists (`ProtocolParamsMapper.fromSnapshot`
    copied only the named map; fixed, with `ProtocolParamsMapperTest` and `TickedLedgerViewEquivalenceTest`). Checked byte-for-byte against a hand-computed vector and against
    CCL's independent encoder.
  - *Metadata.* `hashTxAuxData` over the original bytes. At the pin `InvalidMetadata` is only
    `validateAlonzoTxAuxData`, the well-formedness of the auxiliary data's Plutus scripts: the 64-byte metadatum
    limits are decoding failures (`decodeMetadatum`, Metadata.hs:151-185), so the decoder enforces them.
  - *Malformed scripts.* Owned by `UTXOW` now: the Plutus witness scripts and the reference scripts of the outputs
    and collateral return (and, for `InvalidMetadata`, the auxiliary data's) are judged by
    **`utxow.PlutusScriptDecoder`**, a Java reimplementation of plutus-ledger-api 1.65.0.0 `deserialiseScript`
    (the version cardano-node 11.1.2 is built with; pinned in the revisions report): a definite CBOR byte string
    (bytes after it are a `RemainderError` for PlutusV3 only), then the flat program (version, terms without
    recursion, filler, no trailing bytes), `constr`/`case` only from program version 1.1.0, builtins per
    `builtinsAvailableIn` (language × protocol version), from protocol version 11 constant types of at most 32
    nodes and `constr` of at most 1024 fields, and constants decoded by type (kinds checked; BLS values do not
    flat-decode; `Data` with plutus-core's CBOR rules; `Value` canonical). The program's Plutus Core version is
    **not** a phase-1 check: `plcVersionsAvailableIn` is enforced when the script runs (`mkTermToEvaluate`,
    Eval.hs:118-122), so a 1.1.0 program as PlutusV1 at protocol version 10 is well formed in Haskell too (the
    review's example; verified in the plutus source). A script the Java decoder accepts must also decode with the
    phase-2 evaluator (new SPI method `ScriptPhaseEvaluator.isWellFormed`; Scalus uses `PlutusScript.isWellFormed`;
    an evaluator that cannot tell leaves the Java verdict). The Scalus evaluator's `collect` no longer reports the
    malformed checks (its `evaluate` still refuses to run a malformed script), and the engine ignores `UTXOW`
    failures from `collect`. `collect` is called only when the transaction needs a Plutus script it provides
    (Haskell's context collection is otherwise `Right []`); without an evaluator such a transaction fails closed.
    The decoder agrees with Scalus on all 48 Plutus scripts of the Amaru corpus and accepts three real Aiken
    PlutusV3 validators.
- **Decoding** (`RawTransaction`, all `ENGINE.DecodingFailure`): vkey and bootstrap witnesses, witness scripts
  (native scripts decoded; Plutus lists non-empty and without two scripts of one hash, `scriptDecoderV9`), datum
  hashes, certificates, voters (no duplicate), proposals, required signers, the output datum option and reference
  script, and the auxiliary data (`AlonzoTxAuxData`: map, two-field array or tag 259 with keys 0–4 and no
  duplicate; `Map Word64 Metadatum` without duplicate labels; metadatum integers of 64 bits, strings of at most 64
  bytes, text valid UTF-8 per chunk). **Fix:** a bootstrap witness's chain code had to be 32 bytes; Haskell checks
  that only from protocol version 12 (Keys/Bootstrap.hs:72-78). Certificates and proposal procedures are `OSet`s,
  whose decoder rejects two equal elements (`decodeSetLikeEnforceNoDuplicates`, at every version): duplicates are
  compared by a canonical re-encoding (`tx.CborCanonical`: definite lengths, shortest heads, joined strings,
  sorted maps and sets, including a pool registration's owners and an update-committee action's removals, and
  reduced tag-30 rationals), so a re-encoded copy is a duplicate too.
- **Bootstrap witnesses** are a `Set` ordered by key hash only; `Set.fromList` keeps the last of equal ones, so
  only the last witness for a key is verified (was the first).
- **Tests.** `UtxowRuleTest` (one `@Covers` test per constructor with Haskell's whole list, witness needs per
  purpose, bootstrap witnesses, reference scripts, the integrity PV gate at 10 and 11, several faults in order,
  REAPPLY skipping the static checks), `ScriptIntegrityTest`, `Ed25519Test`, `WitnessDecodingTest`, and the
  Scalus evaluator's `isWellFormed`. The mutation world gained a PlutusV2 cost model and script, datum-hash,
  native-script, timelock, malformed-script and Byron UTxOs; the builder gained PlutusV2 scripts, datums,
  auxiliary-data Plutus scripts, bootstrap witnesses, required signers, votes, and computes the integrity hash as
  `mkScriptIntegrity` from the final witness bytes. Eleven new mutants (39 in all) cover every `UTXOW`
  constructor the protocol-version-10 world can express, plus two decoder mutants (41 in all): a PlutusV3 script
  with a byte after its CBOR byte string, and one using `expModInteger` before protocol version 11. Amaru confirms
  each single fault except the trailing-byte one, which Amaru accepts (a recorded divergence: Haskell's
  `RemainderError`); the Java engine reports exactly Haskell's list on all 41. `ScriptIntegrityHashMismatch`
  (PV11 only) is unit-tested. `PlutusScriptDecoderTest` covers each decoder rule.
- **Gate** (`JavaEnginePhase3GateTest`): the 167 scenarios expected to pass or to fail in `UTXOW`/`UTXO`/`UTXOS`
  (114 + 19 + 30 + 4): 166 match Amaru and Haskell, 1 (00280) matches Haskell where Amaru diverges. Every one of
  the 19 `UTXOW` scenarios reports exactly the expected constructor and nothing else. Coverage: all 18 `UTXOW`
  rows covered (9 test + scenario, 9 test). Baseline row: `java-engine` 180/276 verdicts, 171/276 constructors,
  41/41 mutants, 114/114 pass scenarios, about 0.5 ms per scenario.
- **Findings.**
  - Amaru's Haskell checker accepts an expected predicate anywhere in Haskell's list
    (`ValidatePhaseOne/Run.hs:263-270`), not only first; the `RuleFrame` note is corrected. `LEDGER` lists
    `UTXOW`'s failures before `CERTS`' (`small-steps` prepends each sub-rule's list), so scenarios 00102/00103
    (script credential deregistered then delegated, no script witness) are
    `[UTXOW.MissingScriptWitnessesUTXOW, DELEG.StakeKeyNotRegisteredDELEG]` in Haskell; recorded in
    `HaskellFailureLists` for Phase 4.
  - CCL writes auxiliary data with metadata and only PlutusV3 scripts in the Shelley form, dropping the scripts
    (`AuxiliaryData.getAuxiliaryData`); the fixtures avoid that combination.
- **Deferred.** PV11 mutants need a PV11 mutation world. Because a script must also decode with Scalus, a
  PV11 script Scalus 1.1.1 cannot decode (for example batch-6 builtins or `Value`/array constants, if Scalus lacks
  them) would be rejected although Haskell accepts it; revisit before protocol version 11. Precondition: the view's
  raw cost models are Haskell's `costModelsValid`. `AmaruCorpusNames` has no entry for the nine `UTXOW` names the
  corpus does not use (they must be added together with `amaru_scenarios.rs`).

### Phase 4 — CERTS, DELEG, POOL, GOVCERT

- Gate: the certificate scenarios pass, and their matrix rows are complete.

#### Phase 4 results: CERTS, DELEG, POOL, GOVCERT (2026-09-29)

- **`CERTS`** (`conway.certs.CertsRule`, `conwayCertsTransition`, Conway/Rules/Certs.hs:204-246, and `CERT`,
  Cert.hs:210-223), run by `LEDGER` only when `isValid = True`, after the pre-checks and before `GOV`
  (`ConwayLedgerTransition.standard()` now plugs `LedgerPreChecks` and `CertsRule`; `GOV` is still empty).
  - *Recursion.* `CERTS (gamma :|> c)` runs `CERTS gamma` as a sub-rule, then `CERT c` on the state `gamma` left, so
    the base case (`Empty`) runs before the first certificate and certificates run in body order. The failure list
    is built by exactly this nesting: frame `CERTS(k)` folds in `CERTS(k-1)`, then `CERT(k)`, which folds in
    `DELEG`/`POOL`/`GOVCERT` (`CERT` frames carry the new `LedgerRuleName.CERT`, which no failure names). One
    certificate's failures reach `LEDGER` in execution order (four reversals), and so
    do several certificates' single failures, but a base-case failure interleaves: with `W` and failing
    certificates `A, B, C` Haskell lists `[B, W, A, C]` (`CertsRuleTest#theRecursionListsFailuresInHaskellsOrder`).
    `LEDGER` lists `UTXOW`'s own failures, then `UTXO`'s, then `CERTS`'.
  - *After a failure nothing stops*: `small-steps` continues with the state the failed sub-rule returned
    (Extended.hs:713-724), and later certificates see it. `CertState` steps `TransitionContext.certState()` (an
    `IntraTxFold`) with `TxEffectsDeriver.preCertificateChanges`/`certificateChanges`, the functions that derive a
    valid transaction's effects, so validation and effects cannot diverge. For a failing certificate this is also
    Haskell's resulting state, except where Haskell returns its input state (deregistration or delegation of an
    unregistered credential, deregistration or update of an unregistered DRep, retirement of an unregistered pool):
    the rules then signal "no change". Consequences Haskell has and the engine reproduces: a failing registration of
    a registered credential re-registers it with balance 0 (`registerConwayAccount` overwrites), so a deregistration
    after it reports no `StakeKeyHasNonZeroAccountBalanceDELEG`; a failed deregistration leaves the credential free
    for a registration after it. (Haskell also records the retirement of an unregistered pool in `psRetiring`; nothing
    in `CERTS` or `GOV` reads it for a pool that does not exist, so the fold keeps no entry.)
  - *Base case.* Before protocol version 11: `WithdrawalsNotInRewardsCERTS` (`withdrawalsThatDoNotDrainAccounts`: a
    withdrawal on another network or without an account is invalid, one that is not the exact balance incomplete)
    against the incoming accounts, then the dormant-DRep bump, the voting DReps' expiry refresh and the drain
    (Certs.hs:223-241; the drain skips accounts that do not exist, `updateAccountBalances`, which only a failing
    transaction has). From 11 (`hardforkConwayMoveWithdrawalsAndDRepChecksToLedgerRule`) the base case is the identity
    and `conway.ledger.LedgerPreChecks` does both before `CERTS` (Ledger.hs:383-392): `testIncompleteAndMissingWithdrawals`
    (Shelley/Rules/Ledger.hs:351-359) as two `LEDGER` predicates, `ConwayWithdrawalsMissingAccounts` (another network
    or no account) then `ConwayIncompleteWithdrawals` (so `LEDGER` lists the incomplete ones first), against the
    incoming accounts, then the same pre-certificate step. They share `CertsRule.withdrawalsThatDoNotDrainAccounts`
    with the base case. `LedgerPreChecks` keeps marked slots, in Haskell's order before them, for the Phase 5
    predicates `ConwayTreasuryValueMismatch` (:364), `ConwayTxRefScriptsSizeTooBig` (:365) and
    `ConwayWdrlNotDelegatedToDRep` (:379-381). **Hard gate unchanged:** no protocol-version-11 admission with
    `engine: java` before Phase 5 completes those three and `GOV` (the engine stays behind the experimental flag).
- **`DELEG`** (`DelegRule`, Deleg.hs:187-301), **`POOL`** (`PoolRule`, Shelley/Rules/Pool.hs:209-323) and
  **`GOVCERT`** (`GovCertRule`, GovCert.hs:180-276): all 20 constructors (every check is unlabelled, so dynamic;
  REAPPLY runs them all), in Haskell's order within each certificate:

  | Constructor | Condition (order within the certificate) | PV |
  |---|---|---|
  | `IncorrectDepositDELEG` / `DepositIncorrectDELEG` | tags 7, 11–13: stated deposit ≠ `ppKeyDeposit` (first) | ≤ 10 / ≥ 11 |
  | `IncorrectDepositDELEG` / `RefundIncorrectDELEG` | tag 8, registered credential: stated refund ≠ recorded deposit (first) | ≤ 10 / ≥ 11 |
  | `StakeKeyRegisteredDELEG` | tags 0, 7, 11–13: credential registered (after the deposit) | all |
  | `StakeKeyHasNonZeroAccountBalanceDELEG` | tags 1, 8: balance ≠ 0 (after the refund) | all |
  | `StakeKeyNotRegisteredDELEG` | tags 1, 8 (last) and 2, 9, 10 (after the delegatee): no account; state unchanged | all |
  | `DelegateeStakePoolNotRegisteredDELEG` | tags 2, 10, 11, 13: pool not in the running state (before the DRep) | all |
  | `DelegateeDRepNotRegisteredDELEG` | tags 9, 10, 12, 13: DRep credential not registered (predefined DReps pass) | ≥ 10 |
  | `WrongNetworkPOOL` | tag 3: reward account network ≠ ledger network (first) | all |
  | `PoolMedataHashTooBig` | tag 3: metadata hash > 32 bytes | all |
  | `StakePoolCostTooLowPOOL` | tag 3: cost < `minPoolCost` | all |
  | `VRFKeyHashAlreadyRegistered` | tag 3: VRF key hash held by any pool (active or future), unless a re-registration keeps its active one (last) | ≥ 11 |
  | `StakePoolNotRegisteredOnKeyPOOL` | tag 4: pool not registered (then the epoch) | all |
  | `StakePoolRetirementWrongEpochPOOL` | tag 4: not `cEpoch < e ≤ cEpoch + eMax` | all |
  | `ConwayDRepAlreadyRegistered`, `ConwayDRepIncorrectDeposit` | tag 16, in this order (deposit vs `ppDRepDeposit`) | all |
  | `ConwayDRepNotRegistered`, `ConwayDRepIncorrectRefund` | tag 17, in this order (refund vs recorded deposit, registered only); tag 18: not registered | all |
  | `ConwayCommitteeHasPreviouslyResigned`, `ConwayCommitteeIsUnknown` | tags 14, 15, in this order: resignation in the running committee state; neither elected nor a candidate of a pending `UpdateCommittee` | all |

  Environment: `DELEG` reads the pools and DReps of the running state (a pool or DRep registered earlier in the
  transaction is a delegatee, one deregistered earlier is not); `GOVCERT` judges membership against the state
  before the transaction (the committee changes only at a boundary, and `committeeProposals` is taken before `GOV`
  adds this transaction's proposals, Ledger.hs:367-370: scenarios 00201/00205), but resignation against the running
  state (00208/00209). **VRF semantics** follow `psVRFKeyHashes` exactly, including Haskell's quirk that a pool
  re-registering twice in one epoch with the same new VRF key hash fails the second time (`sppVrf == spsVrf ||
  Map.notMember sppVrf psVRFKeyHashes`, Pool.hs:279-282), and that a VRF key hash a re-registration left in the
  same transaction is free again. Unavailable reads fail closed (`ENGINE.LedgerStateUnavailable`).
- **Raw certificates.** `RawCertificate` now reads every field the rules check from the original bytes: deposits and
  refunds, the delegatee (pool, DRep kind 0–3), the committee hot credential, the retirement epoch, and of a pool
  registration the VRF key hash (32 bytes, else `DecodingFailure`), cost, reward account (an account address,
  header `& 0xEE == 0xE0`) and metadata hash size. The CCL certificate is used only for the effects step; the two
  lists must agree tag by tag, otherwise `ENGINE.JavaEngineFailure`.
- **Bounded fields at decoding** (`tx.BoundedFields`, `ENGINE.DecodingFailure` otherwise; decoder version ≥ 9):
  `Url` and `DnsName` at most 128 bytes of valid UTF-8 (BaseTypes.hs:678-697); `Anchor` a two-element array with a
  32-byte hash (:996-1004); the pool margin a tag-30 `UnitInterval` (two integers, non-zero denominator, reduced
  ratio in [0, 1], `Word64` parts; Plain.hs:159-167, BaseTypes.hs:386-402); relays `[0, port/null, ipv4/null,
  ipv6/null]`, `[1, port/null, dns]`, `[2, dns]` with a `Word16` port and 4- or 16-byte addresses (StakePool.hs:406-421,
  `binaryGetDecoder` refuses left-over bytes). Applied to the anchors of certificates (tags 15, 16, 18), proposals,
  the `NewConstitution` constitution and voting procedures (`[vote, anchor/null]`, which `RawTransaction` now walks
  instead of skipping), and to a pool's metadata URL, margin and relays (`BoundedFieldsTest`).
- **Tests.** `DelegRuleTest`, `PoolRuleTest`, `GovCertRuleTest`, `CertsRuleTest` (36 tests) and
  `LedgerPreChecksTest` (4): one `@Covers` test per
  constructor with Haskell's whole list, both sides of each PV gate, several failures of one certificate, and
  same-transaction sequencing (register → delegate / deregister, deregister → re-register / delegate, DRep
  registered → delegated to, DRep deregistered → delegation fails, pool registered → delegated to / retired,
  retiring pool still a delegatee, resign → authorise, withdrawal drained → deregistration, the two failure
  continuations above, `isValid = false` skips `CERTS`, effects of a register → delegate → vote-delegate
  transaction). The mutation world gained certificate state with two keys outside Amaru's corpus (`dev-77`,
  `dev-bb`: stake accounts, two pools, a DRep, an elected and a resigned committee member; `dev-42` and `dev-aa`
  stay unregistered) and a 2,000 ADA UTxO for deposits, and a **protocol-version-11 world** (`Mutation.protocolMajor`,
  bases valid in both). 25 new mutants (66 in all): one per `CERTS`/`DELEG`/`POOL`/`GOVCERT` constructor, and the
  PV-11-only constructors (`DepositIncorrectDELEG`, `RefundIncorrectDELEG`, `VRFKeyHashAlreadyRegistered`,
  `ConwayWithdrawalsMissingAccounts`, `ConwayIncompleteWithdrawals`, and 3b's `ScriptIntegrityHashMismatch`) in the
  PV 11 world. The Java engine reports exactly the single covered constructor
  on each.
- **Gate** (`JavaEnginePhase4GateTest`): all 209 scenarios of the implemented families and every pass scenario
  (114 pass, 2 `CERTS`, 21 `DELEG`, 7 `POOL`, 12 `GOVCERT`, 19 `UTXOW`, 30 `UTXO`, 4 `UTXOS`): 208 match Amaru and
  Haskell, 1 (00280) matches Haskell where Amaru diverges. All 114 pass scenarios stay valid with `CERTS` running
  (no false rejection from the intra-transaction state). Scenarios 00072 and 00273 (a registration stating a wrong
  deposit, the transaction balanced with the stated amount) are `[UTXO.ValueNotConservedUTxO,
  DELEG.IncorrectDepositDELEG]` in Haskell: value conservation charges `ppKeyDeposit`, not the stated amount
  (`conwayTotalDepositsTxCerts`, Conway/TxCert.hs:817-826); recorded in `HaskellFailureLists`. Coverage: all 21
  certificate rows have a test (14 test + scenario, 7 test only: the PV 11 constructors, `PoolMedataHashTooBig` and
  three `GOVCERT` DRep constructors the corpus does not exercise), and so do the two PV 11 `LEDGER` withdrawal rows
  (test only); the matrix names the Java rule class.
- **Baseline** (regenerated `ledger-conformance/docs/baseline-2026-09.md`): `java-engine` 213/276 verdicts,
  211/276 constructors, 66/66 mutants, 4/4 bases, 63/84 constructors demonstrated, about 0.54 ms per scenario
  (one pass). Of
  the 65 constructor misses, 64 are the `GOV` (59) and `LEDGER` (5) scenarios of Phase 5 and one is 00280 (the
  recorded divergence, where the engine reports Haskell's constructor).
- **Haskell-vs-Amaru divergences** (Haskell wins; recorded on the mutants, `Mutation.amaruReports`):
  - *`ConwayDRepNotRegistered` on update:* Amaru's `DRepsSlice::update` (context/default/validation.rs:263-266 at
    the pinned tag) does not check the registration and accepts `UpdateDRepCert` of an unregistered DRep; Yano's
    adapter then cannot derive its effects and reports `ENGINE.AmaruEngineFailure`. Haskell: GovCert.hs:256-258.
  - *`PoolMedataHashTooBig`:* Amaru's decoder refuses a metadata hash that is not 32 bytes (`DecodingFailure`);
    Haskell decodes any size (`pmHash :: ByteArray`, StakePool.hs:522-524) and `POOL` rejects it.
  - *`VRFKeyHashAlreadyRegistered` (PV 11):* Amaru accepts; its request carries pool ids only, no VRF index.
  - Amaru agrees on every other new mutant, including the PV 11 names `DepositIncorrectDELEG`,
    `RefundIncorrectDELEG`, `ConwayWithdrawalsMissingAccounts`, `ConwayIncompleteWithdrawals` and
    `ScriptIntegrityHashMismatch`.
- **Pinned reference module.** A review run with a module built before Phase 3a's `OutsideForecast →
  UTXOS.CollectErrors` alias scored Amaru 274 (00088) instead of 275. The `amaru-validator-wasm` crate version is now
  bumped whenever the failure mapping changes (0.1.0 → 0.1.1 for that alias) and `amaru_version()` appends
  `crate=<version>`; the conformance harness's reference engine refuses a module whose crate version is not the one
  in `amaru-validator-wasm/Cargo.toml` (checked once, before any case runs, with a message naming the module and the
  rebuild command), and the generated baseline records the module's crate version and sha256. The strict 275/276
  assertion stays.
- **Test fixture change:** `UtxoRuleTest`'s mainnet-withdrawal test now expects `[UTXO.WrongNetworkWithdrawal,
  CERTS.WithdrawalsNotInRewardsCERTS]`: at PV ≤ 10 the base case treats a withdrawal on another network as one
  without an account.
- **Phase 5 hooks.** `LedgerPreChecks` is the `LEDGER` slot: Phase 5 fills its three marked slots
  (`ConwayTreasuryValueMismatch`, the reference-script size, `ConwayWdrlNotDelegatedToDRep` on the pre-certificate
  accounts), which run before the PV 11 withdrawal checks already there. `GOV` must read `TransitionContext.certState().current()`
  (`certStateAfterCERTS`) for voters, delegations and committee hot keys, and the pre-transaction proposals
  (`preState()`) plus its own earlier proposals for lineage.

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

#### Phase 5 results: GOV, LEDGER pre-checks, MEMPOOL, strict coverage (2026-09-29)

- **`GOV`** (`conway.gov.GovRule`, `conwayGovTransition`, Conway/Rules/Gov.hs:446-613), run by `LEDGER` when
  `isValid = True` after `CERTS` (`ConwayLedgerTransition.standard()` now plugs it). Every check is its own predicate
  (small-steps accumulates, nothing short-circuits); `GOV`'s failures reach `LEDGER`'s list in execution order. All 17
  constructors in scope at PV 10–11 (the two PV 9 bootstrap constructors stay out of scope, invariant 7), all dynamic:

  | Constructor | Condition (Haskell order) | PV |
  |---|---|---|
  | `UnelectedCommitteeVoters` | committee voters whose hot key no *elected* member authorised in the post-`CERTS` committee state; before the proposals (:478-481) | ≥ 11 |
  | `ProposalCantFollow` | hard fork: `pvCanFollow` against the current version when the parent is the enacted root or the major is beyond the next, else against an in-flight hard-fork parent (`preceedingHardFork`, :673-695; Word32 minor) | all |
  | `MalformedProposal` | parameter change not `ppuWellFormed pv` (below) | all |
  | `ProposalReturnAccountDoesNotExist` | the return account's credential (any network) not registered after `CERTS` (:504-508) | ≥ 10 |
  | `TreasuryWithdrawalReturnAccountsDoNotExist` | treasury withdrawal accounts not registered after `CERTS` (:509-520) | ≥ 10 |
  | `ProposalDepositIncorrect` | deposit ≠ `ppGovActionDeposit` (:522-530) | all |
  | `ProposalProcedureNetworkIdMismatch` | return account on another network (:532-535) | all |
  | `TreasuryWithdrawalsNetworkIdMismatch`, `InvalidGuardrailsScriptHash`, `ZeroTreasuryWithdrawals` | treasury withdrawals, in this order: accounts on another network; policy ≠ the constitution's guardrail; sum = 0 (an empty map too) (:538-550) | all |
  | `ConflictingCommitteeUpdate`, `ExpirationEpochTooSmall` | update committee, in this order: added ∩ removed; an added member's expiry ≤ current epoch (:551-556) | all |
  | `InvalidGuardrailsScriptHash` | parameter change: policy ≠ the constitution's guardrail (:557-558) | all |
  | `InvalidPrevGovActionId` | `proposalsAddAction` fails (last, :561-566); the proposal is then not added | all |
  | `VotersDoNotExist` | voters absent after `CERTS`: committee hot keys without a current authorisation, DReps, pools (:586-604) | all |
  | `GovActionsDoNotExist` | known voters' votes on actions not in `Proposals` (with this transaction's accepted proposals) (:568-605) | all |
  | `VotingOnExpiredGovAction` | known votes with `currentEpoch > expiresAfter` (:607) | all |
  | `DisallowedVoters` | committee on `NoConfidence`/`UpdateCommittee`; stake pools on `NewConstitution`, `TreasuryWithdrawals` and parameter changes outside the security group; DReps never (Governance/Internal.hs:350-497) (:608) | all |

  - *Lineage model.* `Proposals` = the view's proposals (Haskell's `pProps`, expired ones included until a boundary
    removes them) plus this transaction's proposals accepted so far, with the view's enacted roots. A parent is valid
    when it equals the purpose's root (`SNothing` only while nothing of the purpose was enacted) or is a proposal of the
    same purpose (a node of that purpose's graph); treasury withdrawals and info actions have no lineage. A proposal
    that fails another check is still added. Haskell also lets a proposal or vote name an earlier proposal of the same
    transaction; a real transaction cannot (the action id contains the hash of the body naming it), so the tests use
    `GovRule.apply(frame, proposalTxId)` to exercise it.
  - *Stake-pool votes on parameter changes* need the changed keys: from the transaction's bytes for its own proposals,
    from `ProposalState.paramUpdateKeys` for the view's (the canonical view reads them from the stored action CBOR);
    unknown keys fail closed (`ENGINE.LedgerStateUnavailable`), as does an in-flight hard-fork parent without its
    action payload.
- **Raw proposal decoding** (`tx.RawProposal`, rewritten; `tx.RawParamUpdate`, new): every field `GOV` checks is read
  from the original bytes, as Haskell's `DecCBOR` at decoder versions 9–11 (Procedures.hs:522-535, 875-941): the
  deposit and withdrawal amounts `Word64`, account addresses (header `& 0xEE == 0xE0`, 29 bytes), `GovActionId`
  `[32-byte txid, Word16]`, the protocol version `[Word32 ≤ 12, Word32]` (`decodeProtVer`: `succVersion (ProtVerHigh
  ConwayEra)`), maps and sets without duplicates (optional tag 258), expiry epochs `Word64`, the quorum a
  `UnitInterval`, 28-byte script hashes; anything else is `ENGINE.DecodingFailure`. **`PParamsUpdate`**
  (Core/PParams.hs:257-294 over Conway's `eraPParams`): keys 0–11 and 16–33 only (12, 13, 15 are not Conway
  parameters, 14 is not updatable), no duplicates; coins `Word64` (0, 1, 5, 6, 16, 17, 30, 31), `Word32` (2, 3, 7,
  22, 28, 29, 32), `Word16` (4, 8, 23, 24, 27), `NonNegativeInterval` (9, 33) and `UnitInterval` (10, 11) as tag-30
  rationals with `Word64` reduced parts, cost models (a `Word8`-keyed map of `Int64` lists, unknown languages kept,
  any length: plutus-ledger-api 1.65 only warns on too few), prices, ex-units (≤ `Int64` max), 5 pool and 10 DRep
  thresholds. **`ppuWellFormed pv`** (Conway/PParams.hs:935-963): keys 2, 3, 4, 22, 23, 28, 29, 6, 30, 31 non-zero;
  17 non-zero outside PV 9; non-empty; from PV 11 key 8 (`nOpt`) non-zero. The **security group** is keys
  {0, 1, 2, 3, 4, 17, 21, 22, 30, 33}. Voting procedures now decode the action ids (`[txid, Word16]`, no duplicate per
  voter), refuse an empty per-voter map and a vote other than 0–2 (Procedures.hs:408-416); `RawTransaction.votes()`
  exposes them in Haskell's order.
- **`LEDGER` pre-checks** (`conway.ledger.LedgerPreChecks`, Ledger.hs:361-392), before the PV 11 withdrawal checks:
  `ConwayTreasuryValueMismatch` (a stated `currentTreasuryValue` ≠ the chain account state's treasury, which only a
  boundary changes: the view's epoch treasury), `ConwayTxRefScriptsSizeTooBig` (`txNonDistinctRefScriptsSize` over
  spending ∪ reference inputs, an input in both counted once, > `ppMaxRefScriptSizePerTxG`, the Conway constant
  200 KiB, Conway/PParams.hs:981), `ConwayWdrlNotDelegatedToDRep` (PV ≥ 10: key-hash withdrawal accounts, any network,
  whose account is missing or has no DRep delegation, on the accounts *before* the certificates; predefined DReps
  count as delegated).
- **`MEMPOOL`**: `MempoolRule` was already in front of `LEDGER`; `ConwayMempoolFailure` is now a `ConwayPredicate`
  too (for the catalogue and the matrix). Fixtures (`MempoolTransitionTest`, through the whole transition): an
  all-inputs-spent duplicate reports only `ConwayMempoolFailure` (with a wrong treasury value as a second fault) and
  rule `LEDGER` reports that transaction's own failures instead; at PV 10 an unelected committee vote is judged on the
  incoming committee state even when an elected member authorises the key in the same transaction (MEMPOOL rejects,
  LEDGER accepts); at PV 11 the check is `GOV`'s (`UnelectedCommitteeVoters`, for blocks too), and the in-transaction
  authorisation makes the vote valid; the unelected-vote failure does not stop `LEDGER` (`MEMPOOL`'s list holds
  `LEDGER`'s failures, then its own).
- **Tests.** `GovRuleTest` (one `@Covers` test per `GOV` constructor with Haskell's whole list, the lineage cases
  — in-flight parent, enacted root, root of another purpose, wrong purpose, missing, same-transaction parent, a parent
  that was not added, a forward reference —, hard-fork chaining against the current version, an in-flight hard fork and
  within the transaction, committee conflicts and expiry, the voter matrix for every action type, stake-pool votes on
  each of the 30 Conway keys, voters registered/deregistered/resigned/authorised earlier in the transaction, expired
  actions at epochs 6/7, votes on same-transaction proposals, PV 11 unelected voters), `LedgerPreChecksTest` (+6),
  `MempoolTransitionTest` (5), `RawProposalTest` (8: every parameter type, refused keys and bounds, `ppuWellFormed` per
  PV, security group, proposal fields and decoder refusals, `pvCanFollow`). The mutation world gained governance state
  (two standing proposals, a treasury, `dev-77`'s hot key `dev-42`, an unelected member with hot key `dev-aa`, `dev-cc`
  registered without delegations — new test key `0xCC` × 32 —, a 250,000 ADA UTxO for deposits and a UTxO with a
  205,000-byte PlutusV2 reference script) and the builder a `currentTreasuryValue` and a raw body edit (for the
  Conway parameter keys CCL cannot write). **19 new mutants (85 in all)**: every `GOV` and `LEDGER` constructor except
  `VotingOnExpiredGovAction` (the worlds are at epoch 0; the unit tests and 17 scenarios cover it); the
  `withdrawal-missing-account-v11` mutant now withdraws from a script credential, since a key credential without an
  account is also `ConwayWdrlNotDelegatedToDRep`. The Java engine reports exactly the covered constructor on all 85;
  Amaru agrees on all 19 new ones except `unelected-committee-voter-v11`, where it reports `VotersDoNotExist` (a naming
  alias: Amaru has no separate name, and its Haskell checker normalises `UnelectedCommitteeVoters` to
  `VotersDoNotExist`, `ValidatePhaseOne/Run.hs:532-533`).
- **Gate** (`JavaEnginePhase5GateTest`, all 276 scenarios): **274 match Amaru and Haskell**, 1 (00280) matches Haskell
  where Amaru diverges (recorded in Phase 3a), 1 (00203) is protocol version 9, refused by design with
  `ENGINE.EraNotSupported` as Amaru refuses it (invariant 7). Harness additions: a corpus alias
  (`AmaruCorpusNames.aliases`: `VotersDoNotExist` also accepts `GOV.UnelectedCommitteeVoters`, scenario 00171 at PV 11)
  and Haskell's lists for 00148/00150 (`[ProposalReturnAccountDoesNotExist, InvalidPrevGovActionId]`: those scenarios
  register no account). **Coverage matrix 84/84** (56 test + scenario, 28 test only, no gap); **`conformance.strict` is
  now on by default** in `ledger-conformance` (`-Pconformance.strict=false` reports instead) and passed explicitly in
  the `amaru-wasm.yml` conformance job.
- **Baseline** (regenerated `ledger-conformance/docs/baseline-2026-09.md`, module rebuilt with
  `build-wasm.sh`): `java-engine` **276/276 verdicts, 274/276 constructors, 85/85 mutants, 4/4 bases, 83/84
  constructors demonstrated** (the 84th, `ConwayMempoolFailure`, is reported under rule `MEMPOOL`, which the harness
  does not run), about 0.57 ms per scenario; `amaru` 276/276, 275/276, 78/85 mutants (7 recorded divergences).
- **Engine gating.** `JavaEngineFactory` still requires `yano.validation.java-engine.experimental=true` (defaults are
  Phase 8's), but no longer says the rules are incomplete: the message and Javadoc now say the engine has not yet run
  behind the runtime overlays, shadow sync and the native gate (Phases 6–7). `LedgerValidationEngines` names a missing
  `java` factory instead of "not available yet".
- **Regressions**: `:ledger-rules:test` (321), `:ledger-conformance:test` with the corpus and Amaru, `:scalus-bridge:test`
  with the corpus (84), `:runtime:test --tests '*validation*'` (59), `:tx-services:test` (29): green.
- **Review** (independent Fable pass against `f649f975`): approve; GOV, `ppuWellFormed`, the security group, the
  lineage, the `LEDGER` pre-checks and `MEMPOOL` confirmed. Applied:
  - *Definite-length strings (decoder audit, major).* Below decoder version 12 Haskell reads every ledger byte string
    with `decodeBytesDefinite` / `decodeByteArrayDefinite` (cardano-ledger-binary Decoder.hs:350-376, 1426-1447):
    hashes and fixed-size keys (`PackedBytes`, DecCBOR.hs:492-498), addresses (Address.hs:483-488, 913-916), asset
    names (Mary/Value.hs:126-134), Plutus binaries (Language.hs:250-252), vkey and bootstrap witnesses
    (WitVKey.hs:76-80, Bootstrap.hs:72-84), the tag-24 wrappers of inline datums and reference scripts
    (`decodeNestedCborBytes`, Decoding.hs:238-239) and Byron address payloads (decoded at `byronProtVer`); text with
    cborg's definite-only `decodeString`. `CborReader.readDefiniteBytes`/`readDefiniteText` now read every one of them
    (inputs, outputs, datum hashes, reference scripts, policies, asset names, certificates, the pledge as a `Word64`,
    withdrawals, required signers, the auxiliary-data and script-integrity hashes, witnesses, witness and auxiliary-data
    Plutus scripts, native-script key hashes, proposals, anchors, voters, Byron payloads). Only metadata
    (`decodeMetadatum`, Metadata.hs:151-185, 64 bytes on the concatenation) and Plutus `Data` keep chunks.
  - *Plutus `Data` at decode time.* Haskell decodes witness datums and redeemer data with plutus-core's `decodeData`
    (`DecCBOR (PlutusData era) = Cborg.decode`, Plutus/Data.hs:99-103) and inline datums through `makeBinaryData`
    (:220-239); the Java decoder skipped them. The `Data` decoder of `PlutusScriptDecoder` moved to
    `tx.PlutusData` and now also validates those three (byte strings definite ≤ 64 bytes or chunked with chunks ≤ 64,
    tags 121–127, 1280–1400 and 102, bignums, no text or other tags, no trailing bytes).
  - *Bignum rationals.* Rational parts accept cborg's bignums (one-byte tag heads `c2`/`c3`, definite payload), as
    `decodeInteger` does (Plain.hs:159-167); `ExUnits` stay `Word64` (`decNat` decodes a `Word64`, ExUnits.hs:204-212).
  - *`GovRule`*: the `proposalsAddAction` state change no longer runs inside the predicate's supplier.
  - Tests: `DefiniteLengthDecodingTest` chunks 33 fields (each refused) and a Byron payload, and checks that chunked
    metadata bytes and text and chunked `Data` byte strings are accepted, a 65-byte `Data` byte string and a non-`Data`
    inline datum refused; `RawProposalTest` gains the indefinite-string and bignum cases.
  - The 276-scenario gate, the 85 mutants and every regression suite stay green after the audit.
- **Phase 6 dependencies** (not implemented here; the runtime views must provide them before the engine validates
  against canonical or mempool state):
  - *(a) Committee records.* Canonical committee records must keep members whose term has expired but who were not
    replaced; otherwise `UnelectedCommitteeVoters` (PV 11, `authorizedElectedHotCommitteeCredentials` reads
    `committeeMembers`, not the expiry) reports false positives, and `VotersDoNotExist` loses their hot keys.
  - *(b) Proposal expiry.* `ProposalState.expiresAfterEpoch` must equal `gasProposedIn + govActionLifetime`
    (`mkGovActionState`, Gov.hs:409-417); verify the values yaci supplies.
  - *(c) In-block and mempool governance.* Proposals and votes of earlier transactions of the same block or mempool
    must come from the effects overlay (`TxEffectsDeriver`'s `ProposalSubmitted`), so votes on them and parents naming
    them resolve.
  - *(d) Treasury.* `LedgerView.treasury()` must be the treasury of the ticked epoch (the chain account state the
    transaction is applied to), also across an epoch boundary between the tip and the validated slot.
- **Remaining risks before Phases 6/8.** (1) The node's views must carry every proposal's parameter-update keys and
  action payload, or stake-pool votes on parameter changes and hard-fork chaining fail closed. (2) Same-transaction
  references are exercised only through the test hook. (3) `VotingOnExpiredGovAction` has no mutant (epoch-0 worlds).
  (4) PV 11 is covered by unit tests and 7 PV 11 mutants only; Amaru's corpus has one PV 11 GOV scenario (00171).
  (5) `GOV`'s `Proposals` state change stays the effects deriver's (it records proposals and votes, not the removal of
  replaced or deregistered-DRep votes, which only ratification reads).

#### Phase 5b results: Conway protocol version 9, the bootstrap phase (2026-09-29)

Decided 2026-09-29 by Satya, so that Phase 7 shadow sync can validate every Conway-era transaction (Decisions item 1).
`JavaLedgerValidationEngine.SUPPORTED` is now PV 9–11; anything else is still `ENGINE.EraNotSupported`. The Amaru engine
keeps its own PV 10 minimum (invariant 6) and refuses PV 9, so PV 9 evidence is the Haskell source (cardano-ledger
`f649f975`, `hardforkConwayBootstrapPhase pv = pvMajor pv == 9`, Conway/Era.hs:257-258). Every PV difference is data:
`PvRange.BOOTSTRAP` (9 only) and `PvRange.POST_BOOTSTRAP` (`unless` bootstrap, 10+) on `ConwayPredicate`, or one named
helper per behaviour; no rule has a `pv == 9` branch.

- **Every use of `hardforkConwayBootstrapPhase` and every PV 9/10 gate in the Conway rules and the helpers they use:**
  - *GOV* `DisallowedProposalDuringBootstrap` (new, `BOOTSTRAP`): `checkBootstrapProposal` (Gov.hs:435-444), the first
    check of `processProposal` (:483); allowed: `ParameterChange`, `HardForkInitiation`, `InfoAction`
    (`isBootstrapAction`, :633-639; `GovRule.isBootstrapAction`).
  - *GOV* `DisallowedVotesDuringBootstrap` (new, `BOOTSTRAP`): `checkBootstrapVotes` (:378-391) at :606, after
    `GovActionsDoNotExist` and before `VotingOnExpiredGovAction` / `DisallowedVoters`: DReps only on `InfoAction`,
    committee and pools only on bootstrap actions.
  - *GOV* `ProposalReturnAccountDoesNotExist`, `TreasuryWithdrawalReturnAccountsDoNotExist` (Gov.hs:504-520),
    *LEDGER* `ConwayWdrlNotDelegatedToDRep` (Ledger.hs:379-380), *DELEG* `DelegateeDRepNotRegisteredDELEG`
    (Deleg.hs:220-226): `POST_BOOTSTRAP` (gated since Phases 4-5, now named).
  - *GOV* `MalformedProposal`: `ppuWellFormed` allows `coinsPerUTxOByte` (17) = 0 at PV 9 (Conway/PParams.hs:949-950);
    `RawParamUpdate`'s non-zero rules are now a key → `PvRange` table (17 `POST_BOOTSTRAP`, 8 from 11).
  - *GOVCERT* `computeDRepExpiryVersioned` (GovCert.hs:282-292): a DRep registered at PV 9 expires at
    `currentEpoch + drepActivity`, ignoring dormant epochs; `TxEffectsDeriver.drepExpiryVersioned`, which now reads the
    version from the protocol parameters, as Haskell does, instead of the environment.
  - *PlutusV3 context* (Conway/TxInfo.hs:572-581, certifying purpose :636-640): at PV 9 `transTxCert` gives
    `TxCertRegStaking cred Nothing` / `TxCertUnRegStaking cred Nothing` for `reg_cert` / `unreg_cert` (tags 7, 8), in
    the `TxInfo` certificates, the `TxInfo` redeemer map's certifying purposes and the certifying `ScriptInfo`; tags
    0/1 never carry a deposit and `RegDepositDelegTxCert` (11–13) always does. Plutus V1/V2 contexts are unaffected
    (`transTxCertV1V2`, :383-397, maps tags 7/8 to deposit-less `DCertDelegRegKey`/`DCertDelegDeRegKey` at every PV).
    Scalus 1.1.1 (`LedgerToPlutusTranslation.getTxCertV3`, no protocol version) always includes the deposit, so the
    Scalus evaluator now builds the bootstrap-phase V3 context itself: `scalus-bridge` `BootstrapPhaseContexts` runs
    PV 9 transactions with a tag 7/8 certificate through its own evaluation loop (Scalus's `evalPlutusScriptsWithContexts`
    semantics, validate mode) over the translated `TxInfo` and `ScriptInfo`; every other transaction keeps Scalus's
    evaluator. `ScriptPhaseEvaluator.translatesBootstrapPhaseCertificateDeposits()` (default false; Scalus: true)
    tells the engine; an evaluator that does not translate makes `UtxosRule` fail closed with
    `ENGINE.PhaseTwoContextUnsupported`, only when `plutusLanguagesUsed` (now on `TransitionContext`, set by `UTXOW`)
    contains PlutusV3. Verified with real PlutusV3 certifying scripts that read the deposit in the `TxInfo` and the
    `ScriptInfo` (`BootstrapPhaseContextsTest`: `Nothing` at PV 9, `Just` at 10, for `reg_cert` and `unreg_cert`).
  - *DELEG* `preserveIncorrectDelegation = pvMajor pv < 10` (Deleg.hs:288, 298, 348-372): a re-delegation to another
    DRep credential leaves a stale reverse entry, and a delegation to an unregistered DRep (allowed at PV 9) adds
    none, so a PV 9 `UnRegDRep` (GovCert.hs:246-255) can clear a delegation that moved on and keep one to the DRep
    itself. Not modelled by `OverlayLedgerView` (no reverse index in `LedgerView`), deliberately: no PV 9 rule reads a
    DRep delegation (the only reader is `ConwayWdrlNotDelegatedToDRep`, `POST_BOOTSTRAP`), overlays never span an epoch
    boundary, and the PV 10 repair (`updateDRepDelegations`, HardFork.hs:70-104) is canonical ledger-state's
    (`rebuildDRepDelegReverseIndexIfNeeded`); the ticked view fails closed on a boundary that enacts a hard fork.
  - Verified unchanged at PV 9: the `MEMPOOL` unelected-committee check (PV ≤ 10, Mempool.hs:123);
    `WithdrawalsNotInRewardsCERTS`, `IncorrectDepositDELEG`, `PPViewHashesDontMatch`, `BabbageNonDisjointRefInputs`
    (PV 9–10); `DisallowedVoters` (the `is*VotingAllowed` helpers use `emptyPParams`/`def`, no PV dependence);
    Plutus language (V3 from `changPV` 9), `builtinsAvailableIn` (V3 batches 1–4 from 9, batch 5 and V2 4b from 10)
    and semantics variants (no 9/10 difference) — `PlutusScriptDecoder` was already exact; decoders: every
    `ifDecoderVersionAtLeast` on the Conway transaction path is `@9` (Conway's minimum) or `@12`, so decoder version 9
    equals 10. Ratification-side bootstrap rules (Governance/Internal.hs:467, 519; Ratify.hs:216) are not
    transaction rules.
- **Catalogue:** the two bootstrap constructors are reachable (PV 9): **86 in scope**; every ungated constructor starts at
  PV 9, the four `unless`-bootstrap ones at 10; `OutsideForecast` and `OutputTooSmallUTxO` stay unreachable. Strict
  coverage: 86/86 (56 test + scenario, 30 test, 0 gap).
- **Tests:** `GovRuleTest` (both bootstrap constructors, every action and voter class, order against the other GOV
  checks; return accounts and key 17 at 9 vs 10), `DelegRuleTest` (DRep delegatee at 9 vs 10; a re-delegation and the
  old DRep's deregistration in one PV 9 transaction), `LedgerPreChecksTest` (withdrawal delegation at 9 vs 10),
  `UtxosRuleTest` (with an evaluator that does not translate: PlutusV3 fails closed at PV 9, runs at 10 or with a
  translating evaluator; a PlutusV2 certifying script with a tag 7 certificate, tag 0 and script-free transactions
  pass), `TxEffectsDeriverTest`, `JavaEngineFactoryTest` (9–11 valid; 8 and 12 refused), `BootstrapPhaseContextsTest`
  (scalus-bridge, above). Refactor readiness: `CertsRule.WITHDRAWALS_AND_DREP_CHECKS_IN_LEDGER` (`PvRange.from(11)`)
  replaces `protocolMajor > 10`, and `ValidationEnv.ledgerProtocolMajor(params)` is the one "parameters' version, else
  the environment's" helper.
- **Mutation matrix:** a PV 9 world (`WORLDS` 9, 10, 11; its bases are valid under the Java and Scalus engines) with
  `bootstrap-proposal-v9`, `bootstrap-treasury-withdrawal-v9` (the account checks are skipped, so it is the only
  failure) and `bootstrap-drep-vote-v9`, each citing its Haskell lines, Amaru marked `AMARU_REFUSES_PV9`; a new PV 10
  mutant `malformed-proposal-coins-per-byte` (Amaru confirms); and `BOOTSTRAP_ACCEPTED`: four PV 10 mutants
  (withdrawal not delegated, DRep delegatee, return account, key 17) whose edits the PV 9 world accepts, the rejecting
  side confirmed by Amaru. 89 mutants; `java-engine` 89/89.
- **Corpus:** scenario **00203** (PV 9.0 starting state, a hard fork to 11.0 chained to an in-flight 10.0; expected
  `ProposalCantFollow`) is now validated and matches. Phase 5 gate: 274 match Amaru and Haskell, 1 matches Haskell where
  Amaru diverges, 1 (00203) matches Haskell at PV 9 = 276/276. Amaru still refuses 00203 (`EraNotSupported`).
- **Regressions:** `:ledger-rules:test` (338, 2 skipped), `:ledger-conformance:test` with the corpus and Amaru (102),
  `:scalus-bridge:test` with the corpus (91, 1 skipped): green. Coverage matrix and baseline regenerated (the baseline
  now records the Amaru module built in this worktree, sha256 `c43eeb37…`). No runtime change: no view-level guard
  refused PV 9.
- **Not exact, by design or limitation:** (1) The overlay's DRep delegations after a PV 9 deregistration (above;
  unobservable by any verdict). (2) The bootstrap constructors have Java-only evidence plus one corpus scenario;
  Phase 7 shadow sync over the PV 9 epochs of preprod and mainnet is their cross-check.
- **Phase 7 inputs.** (a) Block level: `totalRefScriptSizeInBlock` (Conway/Rules/Bbody.hs:357-362) measures each
  transaction's reference scripts against the pre-block UTxO at PV ≤ 10 (so PV 9 too) and cumulatively from PV 11;
  shadow sync of whole blocks must follow the same split. (b) The PV 9 overlay's forward-delegation divergence after a
  DRep deregistration surfaces only if shadow sync diffs state or effects, not verdicts; a PV 9 state or effects diff
  must compare with Haskell's reverse-index semantics or exclude account DRep delegations.

#### Phase 5c results: versioned rule sets (2026-09-29)

Required by Satya: new rules for new protocol versions must be easy to add without risk to existing rules. Phase 5c is
a pure refactor of the Java engine: verdicts are unchanged, and the test coverage per protocol version grows. The
contributor guide is `ledger-rules/README.md`.

**Versioned rule sets.**

- **Units.** Every Haskell predicate check is a `RuleUnit`: a `PredicateCheck`, or a custom unit for multi-failure
  predicates. Each has:
  - a stable id: `RULE.Constructor`, with `#suffix` where one constructor has several checks, e.g.
    `GOV.InvalidGuardrailsScriptHash#treasuryWithdrawals`;
  - a static/dynamic label;
  - a Haskell reference;
  - an implementation that reads one narrow subject and returns the predicate's failures.

  State steps are units too, so they can be versioned: `UTXOW.prepareScripts`, `CERTS.preCertificateStep`,
  `LEDGER.preCertificateStep`, `CERT.applyCertificate`, `GOV.proposalsAddAction`. Versioned functions are `RulePolicy`
  objects (today one: `GOVCERT.computeDRepExpiry`).

  Units are stateless named classes, grouped per family in `*Checks` files:
  `UtxowChecks`, `UtxoChecks`, `UtxosChecks`, `LedgerChecks`, `CertsChecks`, `DelegChecks`, `PoolChecks`,
  `GovCertChecks`, `GovChecks`, `MempoolChecks`. A Haskell predicate that reports several constructors
  (`babbageMissingScripts`, `missingRequiredDatums`, `hasExactSetOfRedeemers`, `validateMetadata`,
  `validateScriptsWellFormed`) becomes one unit per constructor, in the predicate's order. Small-steps prepends a
  predicate's failures reversed, so adjacent units give exactly the same list.
- **Scopes.** `ConwayScopes` defines 21 ordered scopes: `MEMPOOL`, `LEDGER`, `CERTS`, one per `DELEG`, `POOL` and
  `GOVCERT` certificate kind, `CERT`, `GOV`, `GOV.proposal`, `GOV.votes`, `UTXOW`, `UTXO`, `UTXOS`. Each scope has a
  subject type (`UtxowSubject`, `UtxoSubject`, `CertSubject`, `GovSubject`, `ProposalSubject`, `VotesSubject`,
  `MempoolSubject`, or `TransitionContext`).

  The family runners (`MempoolRule`, `LedgerPreChecks`, `CertsRule`, `GovRule`, `UtxowRule`, `UtxoRule`, `UtxosRule`)
  only build subjects and run scopes, through `RuleFrame.run`. `RuleFrame.run` skips static units on re-application and
  records each unit as one predicate. `RuleFrame` remains the authority for failure-list order. `MEMPOOL`'s
  all-inputs-spent check is the one unit that halts (`whenFailureFreeDefault`). The UTXOS script run keeps its own
  `whenFailureFree`.
- **Base plus deltas.** The base (`ConwayBaseRules`) is the PV 9 rule set. `ConwayDelta10` and `ConwayDelta11` make
  PV 10 and PV 11. A delta can make four changes:
  - add a unit, at a position relative to an existing id;
  - supersede a unit by id with a new implementation, which takes the same place and may carry a new id when Haskell
    renamed the constructor;
  - retire a unit;
  - supersede a policy.

  `ConwayRuleSet.with` never changes its receiver. A unit does not declare its versions. They come from the composition:
  from the version whose delta introduced it (the manifest's `since`) up to the version before a delta retired it
  (`ConwayRuleSets.versionsOf`). So adding a version never edits an existing unit.

  `ConwayRuleSets` checks the composition when it loads. A failure here makes the engine unusable, not wrong. It
  checks that:
  - deltas are consecutive;
  - every id a delta names exists;
  - units are named classes;
  - each version reports a constructor **exactly** when `ConwayPredicate.pvRange` contains the version.

  The last check ties the rule sets to the pinned catalogue. It is per constructor. `ConwayRuleSetsTest` adds a
  per-scope check: from one version to the next, the scopes that report a constructor, and the scopes that hold a unit
  id, change only in scopes the delta names. It also requires a superseded or retired unit to be replaced in every
  scope that holds it. The only scope move today is `preCertificateStep` from `CERTS` to `LEDGER` at 11, as intended.

  A scope the base does not fill is empty. Manifests omit empty scopes, so a scope added later (for example a new
  certificate kind) appears only in its own version's manifest.

  An empty delta passes the composition check. The Haskell gate review (step 1 of the checklist below) is the only
  guard that a new version needs changes.
- **Selection.** `JavaLedgerValidationEngine` looks up `ConwayRuleSets.forProtocol(ledgerProtocolMajor)`. An empty
  result is `ENGINE.EraNotSupported`. `SUPPORTED` is now derived from the registered rule sets (9–11). The
  engine-neutral `MempoolRule.apply(body, view, major)` (used by the Scalus and Amaru engines) and
  `TxEffectsDeriver.drepExpiryVersioned` use `forProtocolOrLatest`. For a version newer than the latest rule set it
  returns the latest and logs a WARN once per version, so a rollout is visible. It throws for a version before Conway
  (< 9). The Scalus engine turns that into a fail-closed `ENGINE` failure. For every Conway version, both keep their
  previous answers. `RuleFrame.run` and `MempoolRule.apply` share one loop (`ScopeRunner`).

**How each version-dependent behaviour moved.**

| Behaviour | Base unit (PV 9) | Delta |
|---|---|---|
| Unelected committee voters | `MEMPOOL`: `LEDGER.ConwayMempoolFailure#unelectedCommitteeVoters` | Δ11: retire it; add `GOV.UnelectedCommitteeVoters` (first in `GOV`) |
| Withdrawal checks and pre-certificate step, CERTS → LEDGER | `CERTS.WithdrawalsNotInRewardsCERTS`, `CERTS.preCertificateStep` | Δ11: retire both; add `LEDGER.ConwayWithdrawalsMissingAccounts`, `LEDGER.ConwayIncompleteWithdrawals`, `LEDGER.preCertificateStep` (was `CertsRule.WITHDRAWALS_AND_DREP_CHECKS_IN_LEDGER`) |
| Script integrity constructor | `UTXOW.PPViewHashesDontMatch` | Δ11: supersede with `UTXOW.ScriptIntegrityHashMismatch` |
| DELEG deposit and refund constructors | `DELEG.IncorrectDepositDELEG#deposit` (`ConwayRegCert`, `ConwayRegDelegCert`), `#refund` (`ConwayUnRegCert`) | Δ11: supersede with `DELEG.DepositIncorrectDELEG`, `DELEG.RefundIncorrectDELEG` |
| Duplicate VRF keys | none | Δ11: add `POOL.VRFKeyHashAlreadyRegistered` after the cost check |
| Disjoint reference inputs | `UTXO.BabbageNonDisjointRefInputs` | Δ11: retire |
| `ppuWellFormed` non-zero keys (was `RawParamUpdate.NON_ZERO`) | `GOV.MalformedProposal`, `nonZero=[2,3,4,22,23,28,29,6,30,31]` | Δ10: supersede (+17 coinsPerUTxOByte); Δ11: supersede (+8 nOpt) |
| Bootstrap-only GOV checks | `GOV.DisallowedProposalDuringBootstrap`, `GOV.DisallowedVotesDuringBootstrap` | Δ10: retire both |
| `unless` bootstrap (`POST_BOOTSTRAP`) | none | Δ10: add `LEDGER.ConwayWdrlNotDelegatedToDRep`, `GOV.ProposalReturnAccountDoesNotExist`, `GOV.TreasuryWithdrawalReturnAccountsDoNotExist`, `DELEG.DelegateeDRepNotRegisteredDELEG` (two scopes) |
| PlutusV3 context without certificate deposits (was `UtxosRule.CERTIFICATE_DEPOSITS_OMITTED`) | `UTXOS.ValidationTagMismatch` = `BootstrapPhasePlutusExecution` | Δ10: supersede with `PlutusExecutionAfterBootstrap` |
| DRep expiry (was the branch in `drepExpiryVersioned`) | policy `DRepExpiries.Bootstrap` | Δ10: supersede with `DRepExpiries.DormantAdjusted` |

Not moved, deliberately:

- `PlutusScriptDecoder`'s per-version builtin and limit tables. They mirror plutus-ledger-api, which keys them by
  version.
- `ProposalCantFollow`'s use of the current version. That is data, not a gate.

**Frozen manifests.** `ConwayRuleSet.manifest(fingerprint)` lists every non-empty scope's units in execution order.
Each line has id, kind, label, `since`, implementation class, `content`, `variant` (parameters) and Haskell reference.
Policies follow. The committed copies are
`ledger-rules/src/test/resources/org/yanoproject/ledger/rules/conway/ruleset/conway-pv{9,10,11}.manifest`.

`content` is a sha256 prefix of the unit's normalized source (`SourceFingerprints`). It is taken from
`src/main/java`, so it does not depend on the toolchain. It covers:

- the class declaration (nested classes are located by brace matching over tokens);
- its superclasses in `org.yanoproject`;
- the non-type members of those classes' files (the shared helpers).

Comments and whitespace are ignored. So the manifests pin both the composition and each unit's content. Behaviour is
pinned by the per-protocol-version gates, but only where a mutant or scenario exercises it. Subjects, the `tx` decoders
and helpers in other files are not in the digest.

**Rule:** units of a released protocol version are immutable. The only exception is a Haskell-cited bug fix that
applies to every version containing the unit. That fix is an edit plus regenerating every affected manifest, and each
must be reviewed; regenerating is the explicit "I mean all these versions" act. Any other change is a superseding unit
in the newest delta.

`ConwayRuleSetManifestTest` has one test per version. A difference fails with a scoped line diff:
"rule set for PV10 changed: … — if intentional, regenerate with
`./gradlew :ledger-rules:test --tests '*ConwayRuleSetManifestTest' -PupdateRuleManifests=true` and review the diff".
It also checks that there is exactly one manifest per supported version. The pv10 → pv11 manifest diff reads as
`ConwayDelta11`.

`ConwayRuleSetsTest` checks the structure:

- every prefix of the deltas composes byte-identical earlier manifests;
- `with` leaves its receiver unchanged;
- deltas are consecutive;
- unknown or duplicate ids fail;
- a hypothetical PV 12 that revives a retired check, drops a live one, or uses an anonymous class fails the
  composition check;
- versions derived from the composition equal the catalogue ranges;
- policies are versioned;
- every unit and policy field is final;
- a delta changes the reporting and holding scopes only where it names them (above).

**Per-PV regression pinning.**

- *Mutation world matrix* (`MutationWorldMatrixTest`, one dynamic test per version). Every mutant is built and
  validated in the 9, 10 and 11 worlds. Where a gate changes Haskell's verdict, `Mutations.WORLD_EXPECTATIONS` gives
  the verdict and cites the gate: 42 entries, including renames, bootstrap starts and stops, and `MEMPOOL`-rule
  expectations for `ConwayMempoolFailure`. `worldCases` refuses a replay whose list names a constructor that does not
  exist in that world.

  The Java engine must return Haskell's exact list. Amaru cross-checks the 10 and 11 worlds. The counts are pinned:
  96, 96 and 95 cases, with 8, 4 and 4 valid. Result: 0 misses, 0 Amaru disagreements.

  Two new mutants cover the constructors that the replays did not reach at some version:
  `hard-fork-cant-follow-v11` (11.2 at 11.0; a major above 12 does not decode) and `wrong-network-withdrawal`
  (Haskell's three-failure list; Amaru confirms). Three scope-level mutants exercise the DELEG units that live in
  several certificate-kind scopes, through `ConwayRegDelegCert`:
  - `reg-deleg-deposit-incorrect` (tag 11): `IncorrectDepositDELEG` at 9 and 10, `DepositIncorrectDELEG` at 11;
  - `vote-reg-deleg-drep-not-registered` (tag 12) and `stake-vote-reg-deleg-drep-not-registered` (tag 13): valid at
    9, `DelegateeDRepNotRegisteredDELEG` from 10.

  Amaru confirms all three at 10 and 11. That brings the total to 94 mutants, and `java-engine` passes 94/94.
- *Coverage per version.* `CoverageMatrix` now also counts, for each constructor at each supported version where it
  exists, the world cases and `@Covers(pv = …)` tests that reject a transaction with it. `@Covers` gained `pv`, and
  `GovRuleTest` covers `VotingOnExpiredGovAction` at 9, 10 and 11. Strict mode fails on any gap.
  Result: PV 9 75/75, PV 10 77/77, PV 11 80/80. Overall strict coverage is still 86/86. The generated
  `conway-rule-coverage.md` has a "Coverage per protocol version" table, a "Covered at PV" column, and a "Java rule"
  column that lists the units with their versions.
- *Phase 5 gate per version* (`JavaEnginePhase5GateTest`, one dynamic test per version), with pinned tallies:
  PV 9: 1 scenario (bootstrap); PV 10: 273 (272 match, 1 recorded divergence); PV 11: 2 (both match).

**Behaviour preservation.** A temporary harness recorded the Java engine's full verdict, before and after the refactor,
for 2212 combinations: every Amaru scenario, base transaction, bootstrap acceptance, and every mutant in every world,
each under rules `LEDGER` and `MEMPOOL` and in full and re-apply mode, with every failure's constructor and detail. The
two runs are identical. The only new lines belong to the five new mutants. This was re-run after the review fixes:
the 2212 original lines are unchanged, plus 60 new lines.

`:ledger-rules:test` passes: 355 tests, 2 skipped (338 before; the new tests are the rule-set, manifest, fingerprint
and per-version tests). `:ledger-conformance:test` passes with the corpus and Amaru: 115 tests. `:scalus-bridge:test`
passes: 91 tests (14 skipped without the corpus, 1 with it). The Phase 3/4/5 gates are unchanged, and the baseline scenario columns are unchanged.

##### How to add a protocol version (or a new era)

1. List the pinned Haskell's `hardfork…` gates and `pvMajor` comparisons that change at the new version N:
   Conway/Era.hs, the rules, `ppuWellFormed`, TxInfo, and plutus-ledger-api `builtinsAvailableIn`. An empty delta passes
   every check, so this list is the only guard that N needs changes.
2. Update the catalogue ranges (`conway-constructors.json`, `ConwayPredicate`). `ConwayPredicateCatalogueTest` keeps
   them aligned.
3. Write `ConwayDeltaN`: add, supersede or retire units, with new unit classes that cite Haskell. Existing unit classes
   are never edited. Register it in `ConwayRuleSets.DELTAS`. The composition check names any missing or stale unit. A new
   certificate kind or branch is a new scope in `ConwayScopes`, filled by the delta.
4. Run `-PupdateRuleManifests=true`. Review:
   - `conway-pvN.manifest` is new;
   - no earlier manifest changes, in composition or `content`;
   - a new scope appears only in `conway-pvN.manifest`.
5. Add N to `Mutations.WORLDS` and `MutationWorldMatrixTest.PINNED`. Add mutants for the new constructors and
   `WORLD_EXPECTATIONS` for the gates that change. Coverage per version must stay complete.
6. Refresh the Amaru module and scenarios, and the Haskell pins (`adr-056-haskell-pinned-revisions.md`,
   `ConformanceSettings.AMARU_TAG`). Add the corpus versions to `JavaEnginePhase5GateTest.PER_VERSION`.
7. Check the things outside the rule sets: `PlutusScriptDecoder` tables, the phase-2 evaluator, and parameter decoding.
8. Record an ADR entry with the gates, the delta, the counts and any divergences.

A new era is not a delta. It needs a new transition skeleton with its own scopes, subjects, catalogue and base rule set.
It reuses units where Haskell reuses a rule, and the engine's selection extends to it. Its versions then follow the
same base-plus-delta model.

### Phase 6 — Runtime overlays

- Add the immutable `MempoolLedgerState`, synchronous truncate-and-reapply on
  removal, the off-lane rebuild that publishes only on append-only descent with
  snapshot ownership transfer, rebuild triggers for forward blocks, rollbacks and epoch
  boundaries, per-transaction `ValidatedTx` provenance with the shared
  invalidation rules, the phase-2-invalid rejection policy, and the
  block-production overlay.
- Remove `BlockBuildUtxoOverlay` and the legacy validator overload. *(Moved to
  Phase 8, see "Phase 6a results": the overlays apply whenever an engine-API
  admission engine is configured; the legacy default `engine: scalus` keeps
  today's path, `BlockBuildUtxoOverlay` and the overload until the default
  flips.)*
- Migrate the mempool and selector tests.
- Delivered in two steps: **6a** the mempool ledger-state overlay machinery,
  **6b** the block-production overlay, the devnet and Haskell-follower gates
  and ADR-057 Phase C.
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

#### Phase 6a results: mempool ledger-state overlays (2026-09-29)

- **Scope.** The overlays run whenever an engine-API admission engine is configured (`yano.validation.engine` other
  than `scalus`: `java` behind `java-engine.experimental`, `amaru`, and the Scalus engine adapter if it is ever
  selected for admission; today the name `scalus` means the legacy path). `TxSubsystem.setValidationEngines` then
  installs a `LedgerMempool` instead of `DefaultMemPool`. **Deviation:** the legacy default (`engine: scalus`, with
  or without shadow engines) keeps `DefaultMemPool`, the legacy `TransactionValidator`, `BlockBuildUtxoOverlay` and
  the legacy overload unchanged; their removal moves to **Phase 8**, when the default flips. Block selection keeps
  the legacy selector in 6a (6b replaces it).
- **Components.** `org.yanoproject.runtime.mempool`: `LedgerMempool` (implements `MemPool`), the immutable
  `MempoolLedgerState` (ordered `MempoolEntry` list with `ValidatedTx` provenance and origin, `MempoolIndexes` —
  produced/spent/reference-script indexes and dependency edges as persistent maps —, the `OverlayLedgerView` with
  one layer per transaction, the retained `MempoolBase`, `mempoolGeneration`, lineage and mutation log),
  `MempoolBase` (a reference-counted canonical base: the `TickedLedgerView` at the admission slot, its
  `CanonicalMark` = (generation `Gs`, target epoch `Es`) and the forecast basis), `MempoolBaseSource` /
  `GateMempoolBaseSource` (one `REBUILD` snapshot per base; the boundary dry run is computed there, before the
  lane), `LedgerMempoolStatus`; `runtime.validation.LedgerAdmissionScope` (hands the published overlay to the
  validation listener). `MempoolUtxoOverlayView` is deleted. ledger-rules: `util.PersistentMap` (a HAMT),
  `OverlayLedgerView`'s per-key index, `ValidationEnv.forecastBasisSlot`. The gate gained publication listeners,
  `admissionSlot(tip)`/`admissionEpoch(tip)` and `isHeldByCurrentThread()`.
- **State machine.** `READY` ⇄ `CATCHING_UP`. Every mutation (admission, removal, eviction, clear, TTL, a
  rebuild's swap) runs under the fair lane, builds a new state and swaps the volatile published reference; queries
  read it lock-free. A rebuild cycle is: up to `rebuild-max-restarts` (3) off-lane attempts → up to
  `rebuild-sync-attempts` (3) synchronous attempts → `CATCHING_UP`; the first publication returns to `READY`.
  While `CATCHING_UP`: admission returns the retryable `MempoolAdmissionResult.Status.CATCHING_UP`
  (`MempoolAdmissionException.retryable()`, REST 503 with `Retry-After`), `hasPendingTransactions`/`drainForBlock`
  skip the mempool, announced peer transactions are not requested (`TxCatalog.admitting()`, the tx-submission
  handler), the subsystem health is `DEGRADED` with `mempoolLedgerState=CATCHING_UP`, and the node metrics export
  `yano.node.mempool.catching.up` and `yano.node.mempool.canonical.lag.generations`; retries follow every canonical
  publication and a 1 s timer.
- **Lock order.** Bases are acquired only while the lane is not held (initial publication, every attempt's step 1,
  the synchronous attempt before it takes the lane); the lane is never taken while the gate is held; freshness
  under the lane is `MempoolBaseSource.current()`, a volatile read of the published tip; canonical writers notify
  the mempool through gate publication listeners after release, which only schedule the worker. Both directions
  are asserted and counted (`lockOrderViolations`, 0 in every gate).
- **Admission** (under the lane): duplicate, conflict and capacity checks on the published indexes; the
  validation event runs with a `LedgerAdmissionScope` (published overlay, retained base, the base's environment
  with `forecastBasisSlot = next(tip)`); the default listener (`EngineAdmission`) runs rule `MEMPOOL` and records
  the outcome; the mempool appends the `ValidatedTx` and the effects layer and swaps. Plugins see UTxOs through the
  same overlay. Shadow engines get the (immutable) overlay and retain the base. There is no per-admission snapshot
  any more; the published state retains one.
- **Removal, eviction, clear, TTL**: synchronous truncate-and-reapply (overlay `truncateTo` the first removed
  layer, indexes minus the suffix, suffix re-validated in order with `previous`; failures drop, so certificate and
  governance dependents cascade). **Deviations:** confirmation removal (`removeByTxHashes`,
  `removeConflictingInputs`) and the rollback `revalidate` only schedule the rebuild — removing a confirmed
  transaction against the old base would cascade its now-canonical dependents; a block-selection
  `removeInvalidated` while the published state lags is deferred to the pending rebuild for the same reason.
- **Rebuild** exactly per §6 steps 1–8 (single worker, coalesced triggers; step 4 = append-only descent from the
  mutation log first, then the canonical freshness check as the last action before the swap; discards release
  their base; the swap retires the old base only when every admission and frozen view released it). **Triggers:**
  gate publications (forward blocks, rollbacks, producer boundary sections — replacing `onCanonicalRollbackApplied`
  on this path), the block-applied/rollback events (coalesced), and any admission that sees a stale mark (which
  also covers a target-epoch change). With no canonical state an empty mempool publishes the unavailable base
  (admission fails closed, `LedgerStateUnavailable`); a non-empty one discards and eventually enters
  `CATCHING_UP` rather than drop transactions it cannot re-validate.
- **Re-application and invalidation**: every re-validation passes the entry's `ValidatedTx`; `ReapplyPolicy`
  decides (protocol major, `phase2EnvDigest`, resolved-inputs digest, origin; a `SYNC` verdict is never passed for
  admission). All three engines record the resolved-inputs digest (`ReapplyPolicy.resolvedInputsDigest` over
  outpoints for the adapters) and re-apply: the java engine skips its static checks and Plutus; the **Scalus
  adapter** runs `YanoCardanoMutator.reapply` (Scalus's validators minus the `lblStatic` ones — signatures,
  metadata, script well-formedness, empty inputs, bootstrap attributes, the three network checks, size — and the
  mutators minus `PlutusScriptsTransactionMutator`); the **Amaru adapter** runs the module in phase-one mode and
  no phase-2 evaluation (the module's static checks cannot be skipped from outside).
- **Phase-2-invalid policy** is enforced on this path by the engines (`isValid=true` with a failing script →
  `UTXOS.ValidationTagMismatch`; `isValid=false` → `ENGINE.Phase2InvalidTxNotSupported`), verified from `LOCAL` and
  `PEER`.
- **Phase 5 dependencies.** (a) committee records keep expired, unreplaced members (only `UpdateCommittee`
  enactment removes them); (b) `expiresAfterEpoch = proposedIn + govActionLifetime` (`GovernanceBlockProcessor`);
  (c) mempool proposals and votes resolve through the effects overlay (gate test below); (d) the treasury across a
  boundary between the tip and the admission slot still fails closed in the ticked view (retryable once the
  boundary block lands) — carried to 6b/7. The forecast basis is `next(tip)` (`ValidationEnv.forecastBasisSlot`,
  also passed to Amaru's phase-2 evaluator), not the producer-window slot.
- **Budget** (decision 3; `LedgerMempoolBudgetTest`, java engine, 10,000 signed non-Plutus payments, JVM 25,
  after a warm-up round; `-PmempoolBenchmark=true`):

  | Operation | Target | Measured | O(layers) overlay (before the index) |
  |---|---|---|---|
  | Admission p99 (to 10,000 txs) | ≤ 20 ms | 0.19 ms (p50 0.14, max 5.8) | 0.49 ms |
  | Truncate-and-reapply, 1,000 suffix | ≤ 50 ms | 18.9 ms | 599 ms |
  | Truncate-and-reapply, 9,998 suffix | ≤ 500 ms | 170 ms | 3,064 ms |
  | Off-lane rebuild, re-application only, 10,000 | ≤ 2 s | 160 ms | 2,960 ms |
  | Rebuild with full validation (PV change), 9,998 | none | 1.37 s | 3.1 s |

  Engine part of the off-lane rebuild for the adapters (`RebuildBenchmark` in the ledger-rules test fixtures;
  `ScalusEngineReapplyTest` / `AmaruEngineReapplyTest` with `-PmempoolBenchmark=true`; 10,000 non-Plutus payments;
  the mempool's own bookkeeping adds about 20 ms):

  | Engine | Full validation | Re-application | Target (re-application rebuild) |
  |---|---|---|---|
  | `scalus` adapter | 862 ms | 184 ms | ≤ 2 s: met |
  | `amaru` (wasm, `phase2: scalus`) | 8.27 s | 8.04 s | ≤ 2 s: **not met** |

  **Recorded reason (decision 3):** Amaru's cost is the module call itself (`required_keys` plus `validate`, about
  0.8 ms per transaction in Chicory), and phase-one mode only saves Plutus, so a re-application rebuild costs about
  the same as full validation; the 2 s target holds for Amaru up to roughly 2,400 mempool transactions. Above that a
  rebuild can take several seconds and, on a fast devnet with a large mempool, lead to `CATCHING_UP` (the accepted
  fallback, never a stale publication). Amaru stays the optional oracle (ADR-057); the default and `java` meet the
  target.

  The O(layers) overlay misses three targets, so `OverlayLedgerView` now carries a persistent per-key index per
  layer node (reads `O(log32 n)`, `apply` `O(changes · log32 n)`, `truncateTo` reuses the node's index); the lazy
  DRep-deregistration and dormant-bump rules read short per-event lists. The 25 overlay tests pass unchanged.
- **Gates** (all green): `LedgerMempoolTest` (18, java engine over `MutationWorld` states, the rebuild worker held
  so a queued follow-up cannot mask a race): certificate effects of earlier mempool transactions; proposal → vote
  and its cascade; removal without rebuild (A evicted → B dropped, A's registration invisible to the next
  admission, suffix re-applied); TTL and clear; rebuild drops confirmed, re-applies dependents; publication race
  (block, rollback, epoch crossing during the fold, with the mempool unchanged and with appends; a block during
  append reconciliation) → candidate discarded, never published for the stale mark; append-only interleaving
  publishes with the appends re-validated on the new base; removal/TTL/clear and append+removal during a rebuild →
  restart, nothing resurrected, B dropped; multi-generation lag (three publications in one fold) consistent, a
  provisional admission dropped by the next rebuild; pending-mempool hard fork (same parameters re-apply; PV 10→11
  and a cost-model change re-validate in full with Plutus, the failing script transaction dropped); synchronous
  fallback holds the lane only after acquiring its base (asserted in the fold), `CATCHING_UP` → `READY`; a lane
  request with the gate held is a counted violation; frozen view across two swaps, no leaked bases;
  phase-2-invalid from `LOCAL` and `PEER`; no canonical state keeps the transactions. `LedgerMempoolCanonicalGateTest`
  (real gate over one RocksDB): the ownership gate (a canonical UTxO outside the rebuild's read set admitted from
  the published state's retained snapshot; a frozen view keeps its generation across two swaps; the live-snapshot
  count returns to the published baseline, 0 after close) and the synchronous fallback with real canonical writes
  from the writer's thread while the lane is held (no violation, `CATCHING_UP`, `READY`). `EngineAdmissionTest`
  (TxSubsystem end to end) gains `CATCHING_UP` (retryable submit, selection skipped, `DEGRADED`, `admitting()`
  false) and a gate publication rebuilding the published state. Mutation checks: disabling the freshness check fails
  5 gates, disabling the descent check fails 2. `PersistentMapTest` (randomised against `HashMap`, collisions).
- **Block selection (6a).** Selection keeps the legacy validator over a block-local UTxO overlay; confirmed
  transactions still in a lagging published state fail it and are not selected. When no selection validator exists
  (the Scalus validator can fail to initialise while an engine is configured, `DefaultTransactionServicesFactory`),
  `BlockTransactionSelectors` never selects from a `LedgerMempool` that lags the canonical tip or is `CATCHING_UP`
  (a fresh one was validated in order at the tip); 6b replaces this with the block-build overlay.
- **Review fixes (Fable, 2026-09-29).** A block-selection invalidation re-checks freshness under the lane before
  truncating; a rebuild marks its base transferred before any callback, and observer failures are logged; `close()`
  waits for a running rebuild at most 10 s, and every swap re-checks the closed flag under the lane, so nothing is
  published or leaked after close; the freshness mark and snapshot acquisition share one epoch computation
  (`CanonicalStateGate.withEpochs`). `LedgerMempoolStressTest` (4 admitters, the real worker, a publisher of blocks,
  rollbacks and epoch crossings, an evictor with evict/TTL/clear/invalidation, and a checker; 3 s, about 16,000
  admissions, 480 published rebuilds, several `CATCHING_UP` round trips) keeps every published state consistent,
  with no lock-order violation and no leaked base.
- **Plugins and clients.** A plugin validation listener on this path can only add rejections: the verdict and the
  effects come from the admission engine (recorded in the scope; the mempool validates itself if no listener ran
  it), so a plugin cannot admit what the engine rejects or change the effects. While `CATCHING_UP`, n2n tx-id
  announcements are skipped (planned as ignored, not requested, never stalled; the peer re-announces later), local
  REST submission returns 503 with `Retry-After: 5`, and n2c local submission gets the retryable rejection.
- **Other deviations.** With an engine-API admission engine the mempool validates even when the deprecated
  `default-validator-enabled=false` removed the default listener (it cannot append without effects). Switching the
  mempool implementation at assembly drops what the legacy mempool held (it is empty then). `ValidatedTx` keeps its
  own copy of the transaction bytes, so the mempool stores each body twice. The Amaru validation pool grows by one
  thread (the rebuild worker folds next to admission). `rebuild-max-restarts` and `rebuild-sync-attempts` are read
  from the runtime globals.
- **For 6b.** The block-production overlay (`selectMempool` on its own `BLOCK_BUILD` snapshot, rule `LEDGER`,
  `previous` from the entries, forecast basis = slot after the previous block, discard on a generation change);
  the devnet end-to-end chains in one block and in the mempool, the epoch crossing with chains pending (including
  the ticked treasury, dependency (d)), rollback with chains pending, the Haskell follower; ADR-057 Phase C.

#### Phase 6b results: block-production overlay, devnet and Haskell-follower gates (2026-09-29)

- **Scope.** Like 6a, the block-production overlay runs whenever an engine-API admission engine is configured
  (the mempool is a `LedgerMempool`); the legacy default (`engine: scalus`, `DefaultMemPool`) keeps its selector,
  the legacy validator and `BlockBuildUtxoOverlay` until Phase 8.
- **Selection** (`LedgerMempool.selectForBlock(forgeSlot)`, called by `BlockTransactionSelectors` through the new
  `BlockTransactionSelector.drainForBlock(forgeSlot)`; every producer passes its forge slot):
  1. Read the published entries (lock-free; admission is never blocked by a selection) and acquire one
     `BLOCK_BUILD` base (`MempoolBaseSource.acquireForBlock`, `GateMempoolBaseSource`): a canonical snapshot ticked to
     the forge slot, with the forecast horizon based on the slot after the tip (the forged block's parent,
     `ValidationEnv.forecastBasisSlot`). Both happen between the producer's boundary section and its store section,
     with neither the lane nor the gate held (asserted, counted as lock-order violations). Because the producer has
     already applied the boundary, the view is normally the canonical one; the producer-window adjustment of 1d
     (`admissionSlot`) is an admission concern and is not needed for a forge slot.
  2. Fold the entries in mempool order over a fresh block-local `OverlayLedgerView` with rule `LEDGER`, origin
     `BLOCK_BUILD`, `previous` = the entry's `ValidatedTx` (never a `SYNC` one); the engine's invalidation rules
     decide between re-application and full validation. A valid candidate's effects are applied before the next is
     validated, so UTxO, certificate and governance chains are selected in order. An entry whose provenance is
     phase-2-invalid is never selected.
  3. Failures: a ledger-rule failure, and a `Valid` verdict that is phase-2-invalid (only a `SYNC` verdict may
     be), are not selected and are removed with their dependents through the deferred-safe `removeInvalidated`
     after the fold (deferred to the pending rebuild while the published state lags, so a transaction invalid only
     because it was just confirmed never cascades its dependents); a transient failure (any `ENGINE` failure other
     than `Phase2InvalidTxNotSupported` and `DecodingFailure`: an unavailable read, a busy or unhealthy engine,
     effects that cannot be derived or applied) skips the candidate without removal, and every later failure of the
     same selection is skipped without removal too. A candidate whose own validation fails transiently in 32
     consecutive selections (`TRANSIENT_SKIP_LIMIT`) is removed as invalid, with a warning naming the failure, so a
     persistent failure is not re-validated every block until its TTL.
  4. The work is bounded: candidates beyond twice `maxBlockSize` bytes are not validated (the builder keeps a
     prefix that fits the block, as before: `fitTransactions` then applies the size and ex-unit limits unchanged).
  5. If the published canonical generation moved during the fold, the selection is discarded and redone on a new
     base (at most 3 attempts, then nothing is selected). The producer checks again inside its store section, just
     before storing (`BlockTransactionSelector.selectionCurrent()`, `BlockProducerHelper.requireCurrentSelection`):
     a stale selection throws `StaleBlockSelectionException`, the section is marked unchanged, a signed builder's
     pending nonce state is rolled back, and the next production attempt selects again.
- **Builder guard.** `DevnetBlockBuilder.splitTransaction` reads `is_valid` from the transaction it already
  decodes and refuses one that claims `false` with `UnfitBlockTransactionException` (thrown while the body is
  computed, before any nonce state is staged), so the producer invalidates it and its dependents instead of
  failing every block; `invalid_txs` stays empty (decision 6). The unused slot-less
  `BlockProducerHelper.drainMempool` is removed.
- **Counters** on `LedgerMempoolStatus` (health details, metrics): selections, redos, re-applied and fully
  validated candidates, rejected and skipped candidates, last selection time.
- **Dependency (d), the ticked treasury.** Not computed: the treasury of a new epoch depends on the rewards, which
  the dry run does not compute exactly, so `TickedLedgerView.treasury` stays fail-closed (retryable). It only
  matters for admission while the slot after the tip is in the next epoch and the producer has not applied the
  boundary; block selection runs after the boundary section, on the canonical view. The devnet gate covers a
  `currentTreasuryValue` admitted before the boundary block (never forged: `LEDGER.ConwayTreasuryValueMismatch` in
  both the rebuild and the selection once the treasury moved) and one built after it (forged); the Haskell-follower
  workload forges one after its first boundary.
- **Devnet gate** (`JavaEngineDevnetGateTest`, tx-services, about a minute: run with `-PledgerRulesGate=true`,
  which `integration.yml` does in its integration job; the shared `LedgerRulesDevnetMatrix`,
  `DevnetGateNode`, `BlockRevalidator`, `GateWallet` and `GateTxFactory` are tx-services test fixtures, never
  published). An in-process devnet producer (isolated temporary RocksDB and port, 100-slot epochs of 0.2 s,
  500 ms blocks, governance action lifetime patched to 1 epoch), `engine: java` with the experimental flag,
  transactions built with QuickTx against a local wallet so children spend pending parents, paid from the
  devnet genesis funds:
  - **A, one block:** with the producer stopped, stake register → delegate → vote delegation, DRep register →
    delegate, proposal → vote, register → deregister (10 transactions, each admitted while its parents were
    pending) plus three rejections (`DELEG.StakeKeyNotRegisteredDELEG`, `GOV.GovActionsDoNotExist`,
    `DELEG.StakeKeyRegisteredDELEG`); all ten forged in one block, mempool empty afterwards.
  - **B, across blocks:** a chain forged over three blocks; each parent-child pair is built first and submitted
    right after a block, and the gate fails if a parent was confirmed before its child was admitted.
  - **D, rollback:** a delegation and an independent payment pending, the registration's block rolled back (devnet
    rollback API): the rebuild drops the delegation (`LEDGER.ConwayMempoolFailure`: its only input was the rolled-back
    registration's change), keeps the payment, which is forged; the resubmitted chain is forged again.
  - **C, epoch crossing** with chains pending (the producer stopped across the boundary): a vote on a proposal in
    its last epoch is never forged (`GOV.VotingOnExpiredGovAction`, seen by the rebuild and by block selection); the
    pending register → delegate chain is forged in the first block of the new epoch; the stale treasury
    transaction is dropped, the fresh one forged (the gate fails if the treasury did not move, which would make
    this case vacuous); the proposal refunds (2,000 ADA) credited at the later boundary
    are withdrawn in full (after a DRep vote delegation, which PV10 requires: `ConwayWdrlNotDelegatedToDRep`).
  - **Independent re-validation:** every block with transactions (12–13 blocks, 423 transactions per run) is re-read
    from the stored block bytes and validated in order by a separate java engine instance with rule `LEDGER`,
    origin `SYNC`, full validation, against the canonical snapshot of the publication just before the block: every
    transaction valid, ids equal to the applied block's, `invalid_txs` empty.
  - Mempool status after the run: `READY`, 0 lock-order violations, 0 selection redos, every selected candidate
    re-applied (the admission verdict was reusable), 2 candidates rejected by selection (the expired vote and the
    stale treasury value), 1 removal deferred to a rebuild, 0 synchronous fallbacks. The gate itself passed in six
    runs (three alone, one next to `:app:test`, two inside the parity test).
  - Unit gates (`LedgerMempoolBlockSelectionTest`, 7): chains in order over the block-local overlay with rule
    `LEDGER`, origin `BLOCK_BUILD`, `previous`, forge slot and `next(tip)`; a lagging mempool selects the
    dependents of just-confirmed transactions and defers the removal; a transaction invalid at the forge slot
    (TTL) is rejected and removed with its dependent; a canonical publication during the fold redoes the
    selection, and `selectionCurrent` turns false after one; a transient failure skips without removal and taints
    the rest; a transient failure that persists for `TRANSIENT_SKIP_LIMIT` selections removes the candidate (and
    its dependent); no base or `CATCHING_UP` selects nothing. `DevnetBlockProducerTest` gains the stale-selection discard
    (nothing stored, the redone selection forged) and the `isValid=false` guard.
- **Budget with block production running** (decision 3; 400 chained payments from two payers submitted while the
  producer forges every 500 ms; JVM 25, Apple M4 Max; five runs): `engine: java` admission p50 0.23–0.40 ms,
  p99 0.40–1.22 ms, max 0.6–1.7 ms (target p99 ≤ 20 ms), last block selection 19–42 ms, last rebuild 8–39 ms;
  `engine: amaru` (three runs) p50 1.2–1.4 ms, p99 2.1–2.9 ms, max 2.5–138 ms (the maximum is a call waiting for
  a busy instance), last selection 45–278 ms, last rebuild 1–331 ms.
- **Haskell follower** (`test-haskell-sync` harness, `qa/harness/haskell-sync.sh jvm` with the new optional knobs
  `YANO_EXTRA_OPTS`, `HS_GENESIS_PATCH`, `HS_WORKLOAD`, `HS_MIN_SLOT`, `HS_TIMEOUT`; defaults keep the standard
  test): **PASS**, run `haskell-sync-jvm` of 2026-09-29 14:09–14:17
  (logs: the scratchpad `p6b/qa/home/runs/haskell-sync-jvm/{yano.log,haskell.log,workload.log}` and
  `p6b/qa/haskell-sync-run2.log`). Yano from the packaged jar with `-Dyano.validation.engine=java
  -Dyano.validation.java-engine.experimental=true`, the pv10 devnet genesis patched to 600-slot epochs and a
  1-epoch action lifetime (both nodes read the same copy), and `HaskellFollowerWorkloadTest` (tx-services,
  enabled by `-Dyano.gate.remote-url`) submitting through the REST API: the ten-transaction chain back to back
  (forged in blocks 169–170), a chain across blocks (171–172), a `currentTreasuryValue` transaction in epoch 1
  (block 599; the treasury there is 0 on both sides), and the withdrawal of the 2,000 ADA proposal refunds in the
  first block of epoch 3 (block 1753). The Haskell node (cardano-node from the main checkout's
  `test-data-dir/haskell-node`) followed for 4 epochs, 2,497 blocks, with the tip hash matching at every
  checkpoint and at the end (slot delta 0), no Haskell error, invalid or reject line, no Yano `ERROR` line. The
  same run with `engine: amaru` also passes (ADR-057 "Phase C results").
- **Other changes.** `ShadowValidationRunner` counts the per-(engine, rule) disagreement before the total, so the
  metrics never show a total above the sum of the labels (and `EngineAdmissionTest` waits for the label; it was
  flaky under load). `RuntimeNode.getTxSubsystem()` and `getCanonicalStateGate()` (diagnostics and the gates);
  the app forwards `yano.validation.java-engine.experimental` (`YanoPropertyKeys.Validation.JAVA_ENGINE_EXPERIMENTAL`,
  still opt-in) so a packaged node can run the gate.
- **For Phase 7/8.** Phase 7: shadow sync can reuse `BlockRevalidator`'s path (stored block bytes, pre-block
  snapshot from a publication listener, rule `LEDGER`, origin `SYNC`); the Amaru divergence recorded in ADR-057
  Phase C goes to the differential; native parity must cover the block-build path. Phase 8: remove the legacy
  selector, `BlockBuildUtxoOverlay` and the legacy validator overload with the default flip; the ticked treasury
  stays fail-closed unless the dry run computes rewards exactly.

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
- **Include PV9 (Conway bootstrap).** Initially rejected: Amaru, the oracle,
  doesn't support it, and no live network is still at PV9. **Adopted
  2026-09-29** (Phase 5b) with its own gates, so that shadow sync can validate
  every Conway-era transaction; the evidence is the Haskell source, one corpus
  scenario (00203) and a PV9 mutation world.
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

1. **Scope:** Conway **PV9+ (bootstrap included)**, decided 2026-09-29 by
   Satya to allow validating all Conway-era transactions in Phase 7 shadow sync
   (Phase 5b). Accepted 2026-09-28 as PV10+ only.
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
