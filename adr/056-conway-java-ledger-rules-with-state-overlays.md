# ADR-056: Complete Conway Ledger Rules in Java with State Overlays

## Status

Accepted (2026-09-28). The design was reviewed by Fable and in four Codex
review rounds on PR #155; Codex approved it at `40dfd3168`. Satya accepted it
with the decisions recorded at the end of this ADR.

Implementation (2026-09-30): Phases 0–7 are done on PR #155. The Phase 7c
public-network gate passed on preprod, preview and mainnet. Phase 8, the default
switch, waits for the Julc release. See "Acceptance criteria" for the status of each
criterion.

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
4. **Phase 2 behind an SPI.** Plutus execution during validation goes
   through a `ScriptPhaseEvaluator` SPI, first backed by Scalus. Since Phase 7c
   julc is the Java engine's default phase 2 (`java-julc`) and Scalus stays
   selectable (`java-scalus`). Known deviation until the julc release: julc
   0.1.0-pre17 lacks the secp256k1 fix (julc PR #219), so `java-julc` rejects
   the preprod `031e36a7…` case (script `9dd6dd04…`) that the chain accepts
   ("Phase 7c results: phase 2 evaluator: Julc"). ExUnits evaluation
   (`TransactionEvaluator`, `/utils/txs/evaluate`) is unchanged.
5. **Choosable engine, safe rollout.**
   - `yano.validation.engine` selects the admission engine: `scalus` (the
     default until the gates pass), `java-julc` or `java-scalus` (the Java
     rules with julc or Scalus phase 2, Phase 7c), or `amaru` (ADR-057,
     optional). Until Phase 7c the Java engine's only id was `java` (Scalus
     phase 2); results this ADR records for `java` before Phase 7c come from
     that engine, which is `java-scalus` now, and its factory
     `JavaEngineFactory` is split into `JavaJulcEngineFactory` and
     `JavaScalusEngineFactory`. The id `java` no longer exists: startup fails
     with "Unknown validation engine 'java'" and lists the ids.
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
- **julc** (Phase 7c). `script-evaluators` `JulcScriptPhaseEvaluator`: Yano's own
  script contexts on julc's CEK machine: engine `java-julc`, the Java engine
  wherever one is defaulted; `java-scalus` keeps Scalus and can run next to it.
  The engine-neutral part of the contract is `ledger-rules`
  `phase2.ScriptCollection`.
- **Unchanged.** `TransactionEvaluator` and
  `yano.block-producer.script-evaluator` are not affected.

**Scalus 1.1.1 deviations from Haskell, and how the bridge handles them** (Phase 7c). Haskell means cardano-ledger
`f649f975` and plutus 1.65.0.0. Each workaround is isolated in `scalus-bridge` and has a canary in
`ScalusWorkaroundsTest` that fails once Scalus follows Haskell. The governance and withdrawal contexts are also
compared, in `ScalusContextDifferentialTest`, with script-evaluators' `ConwayTxInfoTranslator`.

| Deviation | Haskell | Scalus 1.1.1 | Bridge |
|---|---|---|---|
| `Word64` coins and quantities, and tag-102 `Constr` alternatives ≥ 2^63; metadatum integers | `decodeWord64` (Mary/Value.hs:287-295, Data.hs:298-307); `decodeInteger` (Metadata.hs:161-164) | decoded as `Long`: "OverLong" | `WideIntegers`: narrow, then restore. Refused: slots, rationals, witness datums |
| `serialiseData` of `Constr` ≥ 2^63 | `encodeWord64` (Data.hs:147-160) | `writeLong(constr.toLong)` | `BridgeVM` |
| `equalsData` on `Map`s | derived `Eq`: order- and duplicate-sensitive (Data.hs:42-48, Builtins.hs:1835-1841) | `Data.Map.equals` is set equality | `BridgeVM` |
| `verifyEcdsaSecp256k1Signature` with r or s = 0 | `False` (Secp256k1.hs:48-57, `parse_compact`) | throws | `BridgeVM` |
| V3 `Constitution` | `Constr 0 [Maybe ScriptHash]` (V3/Contexts.hs:266-273) | `Option[ScriptHash]` | `V3Governance` |
| V3 `txInfoVotes`, `Voting` purposes | ledger `Ord Voter`/`GovActionId` (`transMap`, Conway/TxInfo.hs:697-699) | sorted by `toString` (index 10 before 9) | `V3Governance` |
| `TreasuryWithdrawals`, `UpdateCommittee` added/removed | ledger `Ord Credential`, script first (Credential.hs:98-101) | key first, or hash-set order | `V3Governance` |
| Quorum and `ParameterChange` rationals | reduced (Plutus/TxInfo.hs:118-119, ToPlutusData.hs:77-79) | unreduced, as decoded | `V3Governance` |
| Withdrawal order: redeemer index, `txInfoWdrl`, `Rewarding` purposes | ledger order, script first; V1/V2 `txInfoWdrl` key first (V1/Credential.hs:30-37) | hash only | `WithdrawalOrder` |
| V1/V2 binaries with trailing bytes | remainder ignored (SerialisedScript.hs:261-264) | rejected | `PlutusBinaries`, over the shared `PlutusScriptDecoder.leadingItem` |
| `UpdateCommittee` removals with set tag 258 | allowed (Decoder.hs:1081-1085) | rejected | `ScalusTransactions` retry |
| PV 9 V3 `reg`/`unreg` deposits | `Nothing` (Conway/TxInfo.hs:572-581) | always the deposit | `BootstrapPhaseContexts` |
| Plutus Core version of a script run before it is available (1.1.0 in V1/V2 before PV 11) | phase-2 failure at run time (Eval.hs:113-122) | not checked | the evaluators' shared preparation, `ScriptCollection.plutusCoreVersionFailures` (`ledger-rules` `phase2`): `Failed` before any script runs, so for every engine that uses the evaluator (`java`, `java-julc`, `amaru` with `phase2: scalus`) |
| `OutputTooBigUTxO` value size of a map with more than 23 entries | `serialize (pvMajor pv) v` (Alonzo/Rules/Utxo.hs:428): `encodeMap` is indefinite-length above 23 entries (cardano-ledger-binary Encoder.hs:432-443), one byte less than a definite head from 256 entries | `Cbor.encode(value)`, definite-length heads | `YanoOutputValueSizeValidator` replaces `OutputsHaveTooBigValueStorageSizeValidator`, sizing with the java engine's `LedgerValue.serializedSize` |

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
    engine: scalus            # scalus | java-julc | java-scalus | amaru
    shadow-engines: []        # e.g. [java-julc] or [java-julc, amaru]
    shadow-dump-dir: ""       # when set, write a replayable bundle per disagreement
    shadow-sync: false        # validate every applied Conway (PV9+) block (observe only; Phase 7a)
    shadow-sync-engines: java-julc # engines shadow sync runs (no experimental flag needed here);
                              # java-julc,java-scalus runs both phase-2 evaluators side by side (Phase 7c)
    shadow-sync-report: ""    # JSONL, one line per disagreement / engine failure / block finding
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
| **Shadow sync validation** | Every transaction of every synced Conway block (PV9+ since Phase 5b; preprod, preview, mainnet from the Conway hard fork) validates against the pre-block state (captured after the block's epoch boundary, before its own changes; Phase 7a) plus a block-local overlay. `invalidTransactions` must come out phase-2 invalid, all others valid. Catches false rejections and PV drift. It cannot catch false acceptances. | `yano.validation.shadow-sync=true` |
| **Mutation matrix** | From valid base transactions, at least one mutant per constructor is rejected with that constructor. | `conformanceTest` |
| **Haskell differential** (optional) | Mutated invalid transactions submitted over n2c to a local devnet Haskell node (compatibility folder). Compare the `ApplyTxError` constructors. Never submit to a public network. | Manual / CI-optional |
| **Native parity** | The conformance suite runs in the native test image. *Implemented in Phase 7c as a JVM-vs-native differential through the shipped binary (every rule family, each Plutus language, shadow engines and shadow sync); see "Phase 7c results" for why.* | Same pattern as the ADR-051 native gate; `qa/harness/ledger-rules-native-parity.sh` |

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

The stacked steps S1–S5 planned here were not used: the phases landed as
commits of the one PR. Status (2026-09-30):

| Phase | Status | Commits |
|---|---|---|
| 0: module consolidation | done | `a77c49bed` |
| 1: API, views, ticking, effects, engine selection | done | `95978298f`, `f2ce50a88`, `bc7d32243`, `7847f2b29`, `88756fae1` |
| 2: conformance harness | done | `e9341130e` |
| 3: UTXO, UTXOW, UTXOS | done | `06356e23e`, `f9e0c2e37` |
| 4: CERTS, DELEG, POOL, GOVCERT | done | `39696c24b` |
| 5: GOV, LEDGER pre-checks, MEMPOOL; 5b PV 9; 5c versioned rule sets | done | `ea8881f2b`, `55ee52a83`, `76f5343e7` |
| 6: runtime overlays (6a mempool, 6b block production) | done | `d265caf55`, `f54e13a33`, `0e158a26f` |
| 7: blueprint vectors (7b), shadow sync (7a), public networks incl. mainnet, Julc, native parity (7c) | done | `a8b71a20a`, `edcf1627f`, `cce882958`, `507b00927`, `c30b27297`, `64395b758`, `526513cb5`, `b02ba6fe1`, `c84c05ba1`, `943a5b6e0`, `55291b253`, `d7616de51` |
| 8: default switch, clean-up | waits for the Julc release (bloxbean/julc PRs #219, #221, #227, #228) | — |

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
  Phase E). *Phase 7c: they were not registered, and in a Quarkus native image
  the service loader could not construct them; each module now registers its
  factory (see "Phase 7c results").*

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
  pass), `TxEffectsDeriverTest`, `JavaEngineFactoryTest` (now `JavaEngineFactoriesTest`; 9–11 valid; 8 and 12 refused), `BootstrapPhaseContextsTest`
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

#### Phase 7a results: shadow-sync validation (2026-09-29)

Goal (Satya): run every Conway-era transaction through the Java engine during sync and report whether it validates
each one as the chain did. `yano.validation.shadow-sync=true` (off by default) now does that; the real-network runs
(preprod, preview, then mainnet) are the Phase 7 gate and are run separately.

- **Capture point.** A `BlockAppliedEvent` listener registered ahead of every other (priority `Integer.MIN_VALUE`,
  `ShadowSyncValidator`). The event is delivered synchronously on the apply thread inside the block's canonical write
  section: the follower's `BodyFetchManager.applyBlock` stores the block, runs the three epoch-boundary events (each
  commits its RocksDB writes), then publishes `BlockAppliedEvent` (UTxO at order 100, account state and governance
  at 110); a producer applies the boundary in its own section and publishes `BlockAppliedEvent` from its store
  section. When the listener runs, the boundary before the block is committed and none of the block's own changes
  are, which is Haskell's order (TICK, then BBODY/LEDGERS on the ticked state). The new
  `CanonicalStateGate.captureInWriteSection(SHADOW_SYNC)` takes the RocksDB snapshot and the in-memory copies there:
  - only the thread holding the write lock may call it (readers wait; a writer cannot deadlock on itself, it takes
    no lock); `acquireSnapshot` still refuses inside a write section;
  - the tip is the published one (the parent: its slot + 1 is the forecast basis, as `BlockRevalidator` used), the
    ledger epoch is re-read after the boundary, so `TickedLedgerView.of(view, blockSlot)` is `CANONICAL`, and shadow
    sync refuses (reports) anything else rather than dry-running;
  - the snapshot is marked `publishedState() == false`: its state is not its generation's, so the per-generation
    dry-run memo is bypassed; `SHADOW_SYNC` snapshots do not count against `max-live-snapshots` (shadow sync bounds
    them) and cannot be acquired outside a write section.
  - Evidence: `ShadowSyncPreBlockCaptureTest` (real RocksDB stores; a capture after a commit in the section sees it
    and not the section's later commits, keeps them after the section publishes, the cap rule, three blocks through
    `ShadowSyncValidator` with 0 live snapshots after); the devnet gate below validated a first-of-epoch block on
    the follower (boundary and block in one section) and on the producer. Two mutations fail the gate: the listener
    after the UTxO apply (priority 105) gives 16 (producer) / 20 (follower) disagreements, the first transaction of
    each block whose inputs the block itself already spent (`UTXO.ValueNotConservedUTxO` first, as Haskell orders the
    accumulated failures; later ones pass because the chain's effects advance the overlay); after every listener, 21 /
    28, adding `DELEG.StakeKeyRegisteredDELEG` and `GOVCERT.ConwayDRepAlreadyRegistered`.
  - Not reused: the scaffolded `upstream.validation.body-level` / `BodyValidationPipeline` runs before the write
    section, before the boundary, and is fail-closed; shadow sync must observe only.
  - **Ordering guard.** `ShadowSyncValidator.SUBSCRIPTION_PRIORITY` is `Integer.MIN_VALUE`, and
    `ShadowSyncOrderingGuard` (tx-services test fixtures, over the new `PropagatingEventBus.subscribers(type)`) checks
    a live node: the capture listener is first, and every other `BlockAppliedEvent` subscriber below the UTxO apply
    (100) is on a reviewed allow-list (0 `ChronologySubsystem`: era start slot and the one-off Shelley-start UTxO
    total; 1 `LoggingPlugin`; 50 `NonceEvolutionListener`: the epoch nonce). A new low-priority subscriber fails
    `ValidationEngineBootstrapIntegrationTest` and the devnet gate until reviewed. The listeners run after the
    capture in any case; the guard keeps "nothing ledger-changing before 100" true for the other Phase 1 readers.
- **Transactions** come from the block's own bytes (`SyncBlock`, `CborSlice`): each transaction is reassembled as
  `[body, witnesses, is_valid, aux | null]` from the original segment slices (as Haskell's segwit decoder), so
  non-canonical encodings, ids, signatures and hashes are the chain's; `is_valid` is `false` exactly for the indexes
  in `invalid_transactions`. The ids are cross-checked against the applied block (`ID_MISMATCH`).
- **Expected outcomes** (`SyncBlockValidator`, rule `LEDGER`, origin `SYNC`, no `previous`: full validation with
  Plutus; one block-local `OverlayLedgerView` per engine over the pre-block view):
  - not in `invalid_transactions`: `Valid` with a phase-2-valid `ValidatedTx`;
  - in `invalid_transactions`: `Valid` with a phase-2-*invalid* `ValidatedTx` (under `SYNC` the engines run
    `UTXOS` for `IsValid False`: every phase-1 rule, then the scripts must fail; a passing script is
    `UTXOS.ValidationTagMismatch`, a disagreement; the effects are collateral only);
  - anything with a non-`ENGINE` failure, or the wrong phase-2 verdict: **disagreement** (the engine would have
    rejected a chain-valid block); only `ENGINE` failures (state unavailable, decoding, unsupported era or context,
    an engine that threw): **engine failure**.
  - After a finding the chain's effects are still derived (`TxEffectsDeriver`, chain verdict) and applied, so later
    transactions see a correct overlay; when even that fails the rest of the block's findings are engine failures
    marked `overlayTainted`. It cannot catch false acceptances, and it compares verdicts only (the PV 9
    forward-delegation divergence of Phase 5b is not observable here).
- **Block rules** (`BBODY`, cardano-ledger `f649f975`), reported separately (`BLOCK_RULE` lines, own counters):
  - `BodyRefScriptsSizeTooBig` (Conway/Rules/Bbody.hs:342-371): the sum of `txNonDistinctRefScriptsSize` (spending ∪
    reference inputs) ≤ 1 MiB, against the pre-block UTxO at PV ≤ 10 and cumulatively from PV 11 (the outputs of
    earlier transactions, `collOuts` for phase-2-invalid ones; spent entries are not removed);
  - `TooManyExUnits` (Alonzo/Rules/Bbody.hs `validateExUnits`, after `LEDGERS`): the point-wise sum of every
    transaction's redeemer budgets (`totExUnits`, folded over the whole sequence, so phase-2-invalid transactions
    count) ≤ the pre-block `maxBlockExUnits`;
  - `WrongBlockBodySizeBBODY`, `InvalidBodyHashBBODY` (Shelley `validateBlockBodySize` / `validateBlockBodyHash`,
    the first checks of `alonzoBbodyTransition`): the sum of the four stored segments' lengths and
    `blake2b_256(h(bodies) ‖ h(witnesses) ‖ h(aux) ‖ h(invalid))` (`hashAlonzoSegWits`) against the header's
    `block_body_size` / `block_body_hash`. These are ledger (`BBODY`) checks, not consensus ones, and Yano's sync
    path does not otherwise make them: header validation only compares the header's own fields.
  - Out of scope: `HeaderProtVerTooHigh` (a header-version check, enforced by Haskell on mainnet only below PV 12)
    and `incrBlocks` (block-count bookkeeping, canonical state rather than a verdict).
- **Throughput and backpressure.** The apply thread only captures and starts one virtual thread per block once it
  holds a permit; blocks validate in parallel, each block's transactions in order. `shadow-sync-max-in-flight` is the
  only concurrency setting: at most that many blocks, each holding its snapshot, validate at once (default half the
  cores, at least 1). Raise it for faster catch-up on a dedicated machine, but keep it below the core count:
  validation is CPU-bound, not time-sliced, and shares the virtual-thread carriers with the node's own sync pipeline
  (ledger apply, header sync, body fetch, header-applied events, the kernel schedulers), and the rest of the node
  (RocksDB compaction, GC, reward calculation) and other processes on the machine need CPU too; a value at or above
  the core count is accepted with a WARN. When every permit is taken the apply thread waits (sync slows to validation
  speed) up to `shadow-sync-max-wait-ms` (default 30 s), after which the block is skipped and reported. Validation
  never takes the gate. Pre-Conway blocks, Byron blocks and empty blocks are counted without a capture (Byron blocks
  as pre-Conway blocks; their events carry no transactions).
  - **Stopping.** `close()` unsubscribes, then waits up to 120 s for every block already handed over to finish, so a
    restart leaves no coverage gap. Blocks still validating after that are reported as skipped at stop (one JSONL
    `ENGINE_FAILURE` block line each, a WARN with the count) and their results, which may read a closed database, are
    discarded. `RuntimeNode` closes shadow sync after sync has stopped and before the engines and the database.
  - **Coverage.** The summary lines and the JSONL summary count `blocks: submitted, validated,
    pre-Conway-after-capture, failed(before submit), failed(after submit), skippedAtStop`, with
    Conway blocks with transactions = submitted + failed(before submit) and, once nothing is in flight, submitted =
    validated + pre-Conway-after-capture + failed(after submit) (which includes skipped at stop). Failed before
    submit covers outside a write section, no free slot within the maximum wait, a permit wait interrupted when the
    apply worker is stopped, and no pre-block state. **Any failed block is a coverage gap; a clean run has both
    failure counts at 0.**
  - **The wait holds the canonical write lock** (it happens inside the block's write section). Meanwhile every
    snapshot acquisition waits: engine-API mempool admission and rebuilds, block selection, admission shadows and
    other snapshot readers; the published tip does not move and chain sync buffers upstream. That is the point of
    backpressure for block application, but it should not become an outage: 30 s (lowered from the first draft's
    300 s) bounds what a stuck or pathologically slow engine can cost per block, after which it costs coverage (a
    reported, skipped block) rather than availability. A healthy engine frees a slot within one block's validation
    time (the fastest of the in-flight blocks), far below 30 s; Amaru calls are bounded by `amaru.timeout-ms`.
- **Startup guard.** `RuntimeNode` starts shadow sync only when account state and the UTxO store are enabled and UTxO
  apply is synchronous (`ShadowSyncPreconditions`); otherwise it logs a WARN with the reason and the node runs
  without it (never a startup failure, never a block-by-block failure). Installing the same engines again is a
  no-op; replacing them closes the previous validator (`ValidationEngines.attachShadowSync` is idempotent).
- **Engines.** `shadow-sync-engines` (default `java-julc` since Phase 7c, before it `java`; optionally `amaru` in
  `-PwithAmaru` builds) get their own
  instances (Amaru's `pool-size: 0` resolves to `shadow-sync-max-in-flight` for them); the java engine needs no `java-engine.experimental` for shadow sync (it only observes). Shadow sync
  alone leaves admission and the mempool exactly as without engines (`ValidationEngines.affectsAdmission()` false:
  `TxSubsystem` gets no engines). Their health is reported but **never gates readiness** (a failed-closed Amaru
  shadow-sync engine only produces engine failures): `shadowSync.<engine>.healthy` and `shadowSyncUnhealthy` in the
  `validation-engine` health data, and `yano_validation_shadow_sync_engine_healthy{engine}`;
  `ValidationEngineSettings.uses()` (which selects the readiness-gating Amaru check) stays admission and admission
  shadows only.
- **Reporting.** Counters per engine and protocol version (validated, agreed, disagreed, engine failure), blocks
  validated / first-of-epoch / failed / empty / pre-Conway, block-rule checks, backpressure waits; an INFO summary
  every `shadow-sync-summary-seconds` (60) and at stop; `yano_validation_shadow_sync_{txs,blocks}_total`,
  `…_block_rule_violations_total{check}`, `…_backpressure_wait_ms_total`, `…_in_flight`; `shadowSync.*` in the
  `validation-engine` health data; the JSONL `shadow-sync-report` (a line per finding with slot, block, tx index and
  hash, PV, engine, expected, actual and every failure, then a summary line at stop, built field by field so it needs
  no reflection in a native image; closing the report can no longer keep the engines from closing);
  `shadow-sync-dump-dir`: a `ShadowDumpBundle` per finding (the engine re-run over a recording of the same overlay,
  only while the dumper still accepts one; at most `shadow-sync-max-dumps`, 1000), which now records
  `forecastBasisSlot`. `ShadowBundleReplay` (tx-services) replays
  bundles with the java engine: `ShadowBundleReplayTest` with `-Dyano.shadow.bundles=<dir>`, or its `main`.
- **Tests.** `SyncBlockTest` (byte-exact reassembly with a non-canonical body, invalid flags, bare and enveloped
  blocks), `SyncBlockValidatorTest` (overlay chaining, the phase-2-invalid expectations both ways, classification,
  recovery through the chain's effects, tainting, a bundle that replays, the ref-script sum at PV 10 vs 11),
  `ShadowSyncValidatorTest` (the apply thread waits when every slot is taken and resumes; skip after the maximum
  wait; pre-Conway, Byron, empty and outside-a-section blocks are not captured; per engine/PV counters, JSONL,
  bundles and the summary's fields; close releases queued states), `ShadowSyncPreBlockCaptureTest`,
  `ShadowSyncPreconditionsTest`, `ValidationEngineBootstrapIntegrationTest` (shadow sync alone, reinstalling the same
  engines, the ordering guard; not started without account state while the node starts), `ShadowBundleReplayTest`,
  and `ShadowSyncPhase2InvalidTest` (tx-services, the real java engine with the Scalus evaluator on `MutationWorld`'s
  always-failing PlutusV3 script: listed in `invalid_transactions` it agrees as phase-2 invalid; claiming valid it is
  the `UTXOS.ValidationTagMismatch` disagreement). `SyncBlockTest` covers the body size and hash against a
  Conway header, `SyncBlockValidatorTest` the ex-units sum (invalid transactions included, point-wise bound) and a
  full dumper skipping the recording re-run.
- **Devnet gate** `ShadowSyncDevnetGateTest` (tx-services, `-PledgerRulesGate=true`, about 70 s): the Phase 6 matrix
  on a producer with shadow sync, and an in-process follower (`DevnetFollowerNode`: legacy admission, shadow sync,
  pipelined n2n sync from genesis) through the one-block chains, the chain across blocks, the rollback, the epoch
  crossing and 400 chained payments. Producer 423/423 and follower 426/426 to 438/438 transactions agreed over runs (PV 11;
  the follower also validates blocks that are later rolled back, depending on the rollback's timing), 0 disagreements, 0 engine failures, 0 failed blocks, 0 id mismatches,
  every block's ref-script sum, ex-units sum and body size and hash checked with 0 violations, one first-of-epoch
  block each, 0 live snapshots after, the ordering guard on both nodes. Throughput there
  (non-Plutus): about 0.3–0.7 ms per transaction on one thread, no backpressure wait. No phase-2-invalid
  transaction exists on a Yano devnet (decision 6); that path is covered by the unit tests.
- **Running on public networks** (fresh sync from genesis, isolated directory and ports; never two nodes on one
  data directory):
  ```
  ./gradlew :app:yanoDistZip -PskipSigning=true     # builds app/build/yano.jar
  RUN=$HOME/yano-shadow/preprod; mkdir -p $RUN; cd app
  java -Xmx8g -Dquarkus.http.port=7171 -Dyano.server.port=13437 \
    -Dyano.storage.path=$RUN/chainstate -Dyano.history.dir=$RUN/history \
    -Dquarkus.log.file.enable=true -Dquarkus.log.file.path=$RUN/yano.log \
    -Dyano.validation.shadow-sync=true \
    -Dyano.validation.shadow-sync-report=$RUN/shadow-sync.jsonl \
    -Dyano.validation.shadow-sync-dump-dir=$RUN/shadow-dumps \
    -jar build/yano.jar > $RUN/stdout.log 2>&1 &     # preprod is the default profile
  ```
  (`quarkus.log.file.enable` is the Quarkus 3.25 name, `LogRuntimeConfig.FileConfig.enable`; the file handler is off
  by default, so without it only stdout has the log.)
  Preview: add `-Dquarkus.profile=preview` (own `RUN`, ports); mainnet `-Dquarkus.profile=mainnet`. Results:
  `grep 'Shadow sync' $RUN/yano.log` (or `$RUN/stdout.log`), `grep 'shadow sync is not started' ...` for the startup
  guard, `curl -s localhost:7171/q/metrics | grep shadow_sync`, the JSONL file, and
  `./gradlew :tx-services:test --tests '*ShadowBundleReplayTest' -Dyano.shadow.bundles=$RUN/shadow-dumps`.
  Account state, rewards and governance must stay enabled (the defaults).
- **Review.** Two independent reviews. Round 2 checked the block-level checks against cardano-ledger f649f975:
  body size and hash (`alonzoBbodyTransition` → `validateBlockBodySize`/`validateBlockBodyHash`, no PV gate,
  `hashAlonzoSegWits` over the four original segment slices, compared with the header's `block_body_size` only) and
  `TooManyExUnits` (`pointWiseExUnits (<=)` over every transaction's `totExUnits`). The ex-units sum reads each
  transaction's redeemers as Haskell's `Redeemers` map holds them (`RawTransaction#redeemers`), so a duplicate
  `(tag, index)` in the list form counts once. A report line names both body checks when both differ.
- **Open.** Throughput on Plutus-heavy mainnet blocks is unmeasured (validation is serial per block; blocks are
  parallel). A block validated before a rollback stays counted. Amaru as a shadow-sync engine shares its instance
  pool size with admission (`amaru.pool-size`). `ShadowBundleReplay.main` from the uber-jar is not exercised by a test.

#### Phase 7b results: cardano-blueprint conformance vectors (2026-09-29)

- **Pin.** cardano-blueprint `main` (`0f0c17e1`) still carries the 2025 tarball: one JSON file per transaction with
  a `LedgerState`, a different format. The binary `[config, initial NewEpochState, final NewEpochState, [event],
  title]` vectors Amaru reads are those of cardano-blueprint PR #71 (head `d57b8d76` in `KtorZ/cardano-blueprint`),
  `src/ledger/conformance-test-vectors/vectors.tar.gz`, sha256 `5041539c…9b727d`. They are the same files as
  Amaru's `crates/amaru-ledger/tests/data/rules-conformance` at the pinned tag. The pin is in `BlueprintVectorLoader`,
  and a corpus digest over every extracted file is checked whether the files come from the tarball or from an Amaru
  checkout. PR #71 is not merged, and its head commit lives in a fork that GitHub serves through
  `cardano-scaling/cardano-blueprint` only while the PR's ref exists. The extracted vectors (364 files, 3.3 MB,
  Apache-2.0 with the licence and a NOTICE) are therefore vendored under `ledger-conformance/vectors/cardano-blueprint`.
  Its README records the pin, where to look for newer vectors (PR #71, cardano-blueprint `main`, Amaru's copy, the
  generator), and the update steps; `update.sh` replaces the files from a tarball and prints the values to pin.
  320 vectors, 2487 transactions, 886 tick and 567 epoch events, 44 parameter records. Every vector runs in
  the Conway era. Grouped by the Imp spec they come from, they run at PV 9 (Shelley 11, Allegra 1, Mary 2, Alonzo 98,
  Babbage 3: the bootstrap phase) and PV 10 (Conway 205). The vectors record whether Haskell applied a transaction,
  not its predicate failure, so the gate compares verdicts.
- **Decoder** (`ledger-rules` test fixtures, `fixtures.blueprint`). `NewEpochStateDecoder` builds an
  `InMemoryLedgerView` from:
  - the UTxO, with inline datums' original bytes;
  - accounts, pools (with future parameters and retirements), DReps and the committee (terms, hot keys,
    resignations);
  - proposals (payloads, parents, parameter-update keys) and enacted roots;
  - the guardrail script, the treasury and the dormant-epoch count;
  - the current parameters (`ConwayPParamsDecoder`, raw cost models in the ledger's order).

  The vectors come from a ledger before cardano-ledger `38c76760b` (the `Accounts` refactor), so they use the `UMap`
  account layout and the four-map `PState`. The decoder reads that layout and also the later account map. It fails
  closed, skipping the vector with the reason, on:
  - a MemPack-encoded UTxO (the pinned vectors use CBOR `TxIn`/`TxOut`);
  - the later `PState` layout;
  - a missing parameter record;
  - a parameter the rules read (execution prices, `minFeeRefScriptCostPerByte`) that has no exact decimal form.

  A voting threshold of 2/3 is rounded. Only the epoch boundary reads thresholds. All 320 initial states and all 320
  final states decode. The final states exercise every populated part: accounts in 181 vectors, pools 72, DReps 89,
  proposals 113, committee state 72.
- **Runner** (`ledger-conformance`, `blueprint.BlueprintVectorRunner`). Every transaction event goes through the Java
  engine with:
  - rule `LEDGER`, origin `SYNC`;
  - the event's slot, and its epoch `slot / epochSize` (the Imp tests' fixed epoch info; a vector whose config slot
    and epoch disagree is refused);
  - the state's protocol version, and the vector's network id and system start;
  - the Scalus phase-2 evaluator with no forecast horizon, because Imp tests use a fixed epoch info.

  Between transactions the state follows Haskell. An applied transaction's effects go into an `OverlayLedgerView`:
  the engine's effects, or `TxEffectsDeriver`'s for Haskell's verdict when the engine disagrees. PassTick and
  PassEpoch events are skipped, as in Amaru. A disagreement in a transaction whose epoch is later than the initial
  state's `nesEL` is classified `requires epoch`, not `failed`. The boundary comes from the state's epoch, not from
  the events: `conway/fail-gov-expirationepochtoosmall` records a PassEpoch that its initial state (`nesEL` 900, config
  epoch 899) already includes.
- **Final-state check.** 233 vectors stay in one epoch (by `nesEL`) with unchanged parameters. For each of them, the
  state the harness reaches is compared with the vector's final `NewEpochState`: every touched UTxO entry (the whole
  output), account (deposit, reward balance, delegations), pool (deposit, VRF key, pending retirement, staged
  re-registration) and DRep (deposit, expiry), plus the committee state (hot keys, resignations, terms) and the
  proposals (ids in order, epochs, deposit, type, parent). **All 233 match**, which checks `TxEffectsDeriver` and
  `OverlayLedgerView` against Haskell on their 977 transactions.

  This is not full-state equality. The comparison leaves out:
  - votes, proposal payloads beyond type and parent, DRep and constitution anchors, and pool parameter bodies beyond
    deposit, VRF key and retirement;
  - the deposit, fee and donation totals, the stake distribution and the snapshots.

  The rules read none of these. The treasury, the enacted roots and the dormant-epoch count are also left out: the
  rules read them, but only an epoch boundary changes them.

  Seven vectors change the parameters without an event (the Imp test's `modifyPParams`). Three are the Alonzo
  `no-cost-model` vectors. Their failing transaction is judged against the unmodified parameters, and Java rejects it
  with `PPViewHashesDontMatch`, not Haskell's `NoCostModel`. They are excluded from the evidence.

  The gate therefore also validates each failing transaction at the parameters Haskell used: the vector's final
  state, whose parameters hold no cost model at all. There Java reports Haskell's `UTXOS.CollectErrors [NoCostModel]`
  and nothing else, so the script integrity hash matches, as it also does with only the script's language removed.
  `PPViewHashesDontMatch` is an artefact of the missing event, not a Java ordering or logic divergence.

  **Fixed divergence.** At those final parameters the engine used to fail closed with
  `ENGINE.LedgerStateUnavailable`, where Haskell reports `NoCostModel`: `ScriptIntegrity.costModel` read an empty raw
  cost-model map as unavailable. The `LedgerView#protocolParams` contract now distinguishes three cases:
  - a `null` raw map means the raw form was not supplied, so the result is unavailable (fail closed);
  - an empty raw map beside a non-empty named map means the raw form was dropped, so the result is unavailable;
  - an empty raw map otherwise means no cost model for any language, so the result is `NoCostModel`.

  `ScriptIntegrityTest` covers all three. The runtime is unchanged: the canonical view's `ProtocolParamsMapper.fromSnapshot`
  sets the raw map only when it is non-empty, so a network without raw data still fails closed. The other readers
  already agree:
  - `Phase2EnvDigest` falls back to the named map;
  - the Scalus evaluator's `hasCostModel` reports `NoCostModel`;
  - Amaru's `ProtocolParamsEncoder` encodes an empty cost-model map.

  The rule-set manifests are unchanged. The fix is PV-neutral.
- **Gate** (`BlueprintVectorGateTest`, 10 tests). It checks:
  - the pin;
  - that every state decodes;
  - pinned tallies per Imp spec and PV;
  - transaction-level agreement: 2471 matching verdicts, and every mismatching transaction index per vector. A new
    disagreement inside a vector that is already `requires epoch` therefore fails the gate;
  - that every final state matches where comparable;
  - that **every vector Amaru passes, Java passes**, unless recorded.

  Amaru's 27 known failures (its `rules-conformance.failures.toml`) are pinned in `amaru-known-failures.txt` and
  checked against the Amaru checkout when one is configured. Results:

  | | vectors | passed | failed | requires epoch |
  |---|---:|---:|---:|---:|
  | PV 9 (non-Conway Imp specs) | 115 | 115 | 0 | 0 |
  | PV 10 (Conway Imp spec) | 205 | 194 | 1 | 10 |

  Transactions: 2471 of 2487 match Haskell's verdict. Of the 16 that do not, 15 come after a skipped epoch boundary.
  These are the numbers after the two engine fixes below; before them, PV 10 was 192 passed and 3 failed.

  Compared with Amaru, which passes 293:
  - **Java passes 309.** That includes 18 of Amaru's 27 known failures:
    - the metadata decoding case;
    - the three unregistered-credential vectors (two delegations, one withdrawal);
    - expired governance actions;
    - the seven PlutusV1/V2 context checks (inline datums, proposals, votes, treasury donation);
    - the two guardrail-policy vectors;
    - the two pool-retirement vectors, where Amaru's harness uses a default era history;
    - two missing-signature cases.
  - **The other 9 of Amaru's failures are `requires epoch` for Java as well.** They depend on rewards, deposit refunds,
    pool reaping, constitution or committee enactment at a boundary.
  - **Two vectors Amaru passes and Java does not** are recorded (`RECORDED`). Neither is a ledger-rule bug, but the
    Phase 7 gate "at or above Amaru's pass set" is met only with these two recorded exceptions:
    - `conway/fail-certs-withdrawing-the-wrong-amount`. **Harness, not an engine bug:** the verdict needs the skipped
      epoch boundary. The boundaries refund a proposal deposit (`Conway/Rules/Epoch.hs:179-190`), so Haskell rejects
      tx 7's zero withdrawal. Amaru most likely passes because its harness keeps a failed transaction's partial
      effects.
    - `conway/pass-enact-withdrawals-exceeding-maxbound-word64-submitted-in-a-single-proposal`. **Evaluator limit**,
      fix 2 below.
- **Engine fixes** (`scalus-bridge`, `ScalusScriptPhaseEvaluator`, the phase-2 evaluator the Java engine uses).
  1. **PlutusV1 over reference scripts.** `pass-utxos-can-use-reference-scripts` and
     `pass-utxos-can-use-regular-inputs-for-reference` now pass (tx 2 was
     `UTXOS.CollectErrors [BadTranslation ReferenceScriptsNotSupported]`).
     - Conway has its own `transTxOutV1` and `transTxInInfoV1` (`Conway/TxInfo.hs:306-335`; reference inputs go
       through them at `:411`). They reject an inline datum, then a Byron address, but not a reference script.
       Only Babbage's `transTxOutV1` (`Babbage/TxInfo.hs:119-121`) does, and `Babbage/Imp/UtxosSpec.hs:71-95`
       expects "PlutusV1 with references" to succeed after Babbage.
     - A V1 `TxOut` has no reference-script field, so Haskell drops it (`Alonzo.transTxOut`). Scalus's
       `getTxOutV1` drops it the same way. Its V1 pre-check rejects only inline datums and Byron addresses, and it
       leaves reference inputs out of the V1 `TxInfo`.
     - V2 (`Babbage.transTxOutV2`) and V3 carry the reference script's hash, and Scalus's `getTxOutV2` does the same.
       Only a Byron address fails there.

     Re-checked against Conway's `TxInfo`, not Babbage's:
     - the V1/V2 Conway-feature guard (votes, proposals, a non-zero donation, the current treasury value);
     - `CertificateNotSupported` (`transTxCertV1V2` plus `transTxCertCommon`);
     - `PlutusPurposeNotSupported` for V1/V2 voting and proposing;
     - V1 inline datums in spending inputs, reference inputs and outputs;
     - Byron addresses for every language.

     All matched. Two ordering differences were fixed, so the first failure per language follows Conway's build
     order (`Conway/TxInfo.hs:399-520`: V1/V2 guard, validity interval, inputs, reference inputs, V3 disjointness,
     outputs, certificates):
     - The V3 PV 11 `ReferenceInputsNotDisjointFromInputs` check now comes before the outputs.
     - `TimeTranslationPastHorizon` is now checked per language, after the V1/V2 guard and before the inputs, and
       only for a language whose `TxInfo` is built. It is no longer reported next to another translation failure,
       or for a script with `NoCostModel`.
  2. **Coins above 2^63−1.** `SignedLongRange` scans the transaction body for an integer in `[2^63, 2^64)` (Haskell's
     `Coin` and `SlotNo` are `Word64`; Scalus decodes them as a signed long). If it finds one, the evaluator fails
     closed with **`ENGINE.CoinOutOfEvaluatorRange`**, an explicit engine constructor rather than a ledger verdict,
     before any Scalus decoding. This replaces the opaque `ENGINE.JavaEngineFailure` "Expected Long but got OverLong".
     - The body carries no Plutus data (inline datums are byte strings), so only ledger integers are scanned.
     - No real network can hold such a coin: the supply is below 2^56 lovelace.
     - On the vector, the only failure is this one. Every phase-1 check, including value conservation and the GOV
       checks on the oversized withdrawal, passes with `BigInteger` coins.
     - Scope limits, both theoretical and documented in `SignedLongRange`:
       - A rational's numerator or denominator inside a `ParameterChange` is a Haskell `Integer`. One at or above
         2^63 is flagged although Haskell accepts it.
       - Witness-set and auxiliary-data integers are not scanned. One at or above 2^63 that Scalus reads as a long
         still surfaces as the opaque `ENGINE.JavaEngineFailure`, which is never an acceptance.

     The vector stays in `RECORDED` with this reason.
- **Coverage matrix.** `conway-rule-coverage.md` has a new **Blueprint vectors** column. For each constructor it
  counts, per PV, the vector transactions Haskell rejects that Java rejects with that constructor first:
  - only before any skipped boundary;
  - only in vectors whose parameters did not change outside the events.

  This is verdict-level evidence, because the vectors carry no constructor, so it does not change the statuses. 46
  constructors appear. The only `ENGINE` rejection is `allegra/fail-utxow-invalidmetadata`: Haskell's decoder also
  refuses metadata text over 64 bytes from Allegra on (`Metadata.hs:153-185`).
- **CI and local runs.** The build passes the vendored vectors to the tests by default, so the gate runs in every
  `:ledger-conformance:test`, in the main build and in the `amaru-wasm.yml` `conformance` job, with no download.
  `-PblueprintVectors=<directory>` points at another copy (an extracted tarball or an Amaru checkout); the corpus
  digest is checked either way. `conformanceReport` writes `ledger-conformance/docs/blueprint-vectors-2026-09.md`:
  ```
  ./gradlew :ledger-conformance:conformanceReport -PamaruScenariosDir=<amaru>
  ```
- **Tests.**
  - `:ledger-conformance:test` with the Amaru corpus, the Amaru module and the vectors: 131 tests, 0 failures (17
    new: the gate's 10 tests and `NewEpochStateDecoderTest`, which covers the account layouts, the fail-closed paths,
    the rounding rule and the vector parser).
  - Without them: 15 skipped.
  - `:scalus-bridge:test` with the corpus: 94 tests, 1 skipped (3 new: V1 over reference scripts in every
    `TxOut`, Conway's translation order, and the signed-long range).
  - `:ledger-rules:test`: 356 tests, 2 skipped (1 new: `ScriptIntegrityTest`'s raw cost-model states).
  - The Phase 3/4/5 gates and the baseline scenario columns are unchanged.
  - `:amaru-validator:test` with the corpus and the module: 47 tests, 2 skipped, 0 failures. That
    `ProtocolParamsEncoder` encodes an empty raw cost-model map as an empty map was checked by reading the code; no
    test covers it.

#### Phase 7c results: native parity (2026-09-29)

Requirement (Satya): the native image must have the JVM's features, because the Yano wallet targets the native
build. Nothing is disabled in native; dropping a feature there needs his sign-off. The oracle for this phase is
"native verdict and constructor == JVM verdict and constructor on the same input"; correctness against Haskell stays
with the conformance suite on the JVM (Phases 2-7b).

**Gaps found and fixed.**

1. **No engine could be loaded in the native image.** The first native run (`engine: java`, admission shadow
   `scalus`, shadow sync) logged `ServiceConfigurationError: ... Provider
   org.yanoproject.ledger.rules.conway.JavaEngineFactory not found`. The service files were in the image, but the
   provider classes had no reflection registration. Quarkus does not register `META-INF/services` providers for
   reflective construction, and it turns GraalVM's own service-loader support off (`-H:-UseServiceLoaderFeature` in
   the build log). It hit every
   engine-API configuration (a non-`scalus` engine, any shadow engine, shadow sync), not the default path.
   - Fix: a `reflect-config.json` entry with `allDeclaredConstructors` for each factory, in the module that ships
     it: `ledger-rules` (`JavaEngineFactory`, and later `JavaJulcEngineFactory`; new
     `META-INF/native-image/org.yanoproject/yano-ledger-rules/`),
     `scalus-bridge` (`ScalusEngineFactory`) and `amaru-validator` (`AmaruEngineFactory`). Each module's
     `resource-config.json` also names its service file, as `archive-store-ducklake` does.
   - Guard: `ValidationEngineNativeMetadataTest` (app) reads every
     `META-INF/services/org.yanoproject.ledger.rules.LedgerValidationEngineFactory` on the classpath and fails when
     a listed provider has no constructor registration. A mutation check (the `ledger-rules` entry removed) fails
     it.
2. **The discovery failure failed open.** `LedgerValidationEngines.discover` wraps the `ServiceConfigurationError`
   in an `IllegalStateException`. `YanoAssembly.installTransactionServices` lets only
   `ValidationEngineConfigurationException` stop startup and logs anything else as a WARN
   (`Transaction validation/evaluation not initialized`). The native node therefore came up READY, with the
   `validation-engine` health check UP, forging blocks and admitting transactions **without any validation**, not
   even the legacy validator. That contradicts §7 and ADR-057 §2 ("no silent fallback"). It applies to the JVM too,
   for any provider that cannot be loaded (for example a broken plugin jar).
   - Fix: `ValidationEngineBootstrap.create` rethrows a discovery failure as `ValidationEngineConfigurationException`,
     so the node stops with the message.
   - Test: `ValidationEngineBootstrapIntegrationTest.anUnloadableEngineProviderStopsStartup` (a context class
     loader with a service file naming a missing class).
3. **The default path failed open the same way** (found in review). With `engine: scalus` (the default),
   `DefaultTransactionServicesFactory` logged an ERROR when the Scalus validator could not be built, and carried on.
   `TxSubsystem` then registered no validator listener, so admission rejected nothing. The health check still
   reported `scalus (legacy)` UP.
   - Fix: while transaction validation is enabled (`yano.block-producer.tx-evaluation`, default `true`), a validator
     that cannot be built stops startup with `ValidationEngineConfigurationException`
     (`DefaultTransactionServicesFactory.requireValidator`).
   - Unchanged, and deliberate: with `tx-evaluation=false` the factory is never called, and a node without a UTxO
     store installs no validator (`TxSubsystem.setTransactionEvaluator`). The earlier "no protocol-parameter source"
     and genesis-resolution exits are unchanged too; each logs a WARN and creates no services.
   - Test: `DefaultTransactionServicesFactoryIntegrationTest.aValidatorThatCannotBeBuiltStopsStartup`.
4. **`ENGINE.LedgerStateUnavailable` came back as HTTP 400** (on the JVM too). `LedgerMempool` mapped every
   `Invalid` outcome to `LEDGER_REJECTED`, which REST reports as 400 "validation failed", so a wallet treated a
   retryable condition as a rejection. An example is the admission window across the Conway bootstrap boundary
   (decision 6a).
   - Fix: when every engine failure is `ENGINE.LedgerStateUnavailable` and no listener rejected for another
     reason, the mempool returns the existing retryable `CATCHING_UP`. REST answers 503 with `Retry-After`, and
     the n2c path follows unchanged.
   - The legacy admission (`DefaultMemPool`, the Scalus validator) never produces this failure. The engine branch of
     `TxSubsystem.submit` without a `LedgerMempool` cannot be reached: an engine-API admission engine always
     installs one.
   - Test: `LedgerMempoolTest.anUnavailableLedgerStateIsRetryableNotARejection`. The parity workload now retries
     only on 503.

Nothing else was needed by this workload's paths. The rule sets are plain code (Phase 5c units are classes composed in code; no manifest or
resource is read at runtime). `ProtocolParams` already had an `allDeclared*` entry in the app's
`reflect-config.json`, so the shadow bundles' `valueToTree` works. The shadow-sync JSONL is built field by field
(Phase 7a). Scalus, BouncyCastle/Ed25519, `PersistentMap` and the Amaru AOT classes needed no new metadata.

**Choice of the native gate.** The §8 row says "the conformance suite runs in the native test image, same pattern as
the ADR-051 native gate". The implementation is a JVM-vs-native differential through the running node:
`NativeParityWorkloadTest` (tx-services) run by `qa/harness/ledger-rules-native-parity.sh`. The harness is built on
`qa/harness/common.sh` (`start_yano`, ports, PID tracking), and it is registered as release-QA test
`ledger-rules-native` in the opt-in category `ledger-rules`. The workload and the Phase 6b Haskell-follower workload
share the new `RemoteYanoNode` test fixture: REST reads and raw submission against a packaged node.

- ADR-051's native gate starts the release-parity native binary and submits real transactions; no test image
  exists. The repository has no `@QuarkusIntegrationTest` and no GraalVM native-build-tools `nativeTest`.
- The Amaru scenarios, the blueprint vectors and the mutation matrix load their initial state into an in-memory
  `LedgerView` from test fixtures. A running node validates against its own chain state, so the corpus cannot be
  submitted to it.
- Compiling `ledger-conformance` into a separate native JUnit image would exercise a different image from the one
  shipped: no Quarkus build steps and different reachability metadata. It could pass while the node fails, as the
  engine-discovery gap shows.
- Between the JVM and the native image the rule bytecode is the same. What differs is reachability metadata
  (reflection, resources, service providers, JNI), class initialisation and substitutions. The gate therefore has
  to make every metadata-sensitive path run in the shipped binary:
  - engine discovery and creation;
  - failure construction in each rule family;
  - Scalus phase 2 for each Plutus language;
  - Ed25519 witnesses;
  - Jackson `ProtocolParams` in dumps;
  - shadow sync end to end;
  - the Amaru module.

  The harness does exactly that, and compares everything observable with the JVM.

**What the harness does.** For each configuration and protocol version it starts a JVM devnet producer, then a native
one, with the same genesis and settings (2 s blocks, 100-slot epochs of 0.2 s slots, 1,000 ADA action deposit, PV 9
and 10 from the `pv10` genesis set, PV 11 from the default one). It runs the same workload against each. Every
transaction is built offline from the genesis funds, so for the same genesis the transactions are byte-identical
between the two runs. The workload writes one observation line per step, and the harness diffs the two files:

- payments, and three payments chained while pending;
- one phase-1 failure per family:
  - `MEMPOOL`: all inputs spent, and a double spend of a pending input;
  - `UTXO`: `BadInputsUTxO`, `ValueNotConservedUTxO`, `FeeTooSmallUTxO`, `OutsideValidityIntervalUTxO`,
    `WrongNetwork`, `BabbageOutputTooSmallUTxO`;
  - `UTXOW`: `MissingVKeyWitnessesUTXOW`, and `InvalidWitnessesUTXOW` (a body changed after signing);
  - `DELEG`: `StakeKeyNotRegisteredDELEG`, `StakeKeyRegisteredDELEG`;
  - `GOVCERT`: `ConwayDRepAlreadyRegistered`;
  - `GOV`: `GovActionsDoNotExist`;
  - `LEDGER`: `ConwayIncompleteWithdrawals` (PV 10+; `CERTS.WithdrawalsNotInRewardsCERTS` at PV 9),
    `ConwayTreasuryValueMismatch`;
  - `POOL`: `StakePoolNotRegisteredOnKeyPOOL`;
- a stake register → delegate → DRep register → vote delegation → proposal → DRep vote chain, admitted while the
  parents are pending;
- Plutus: an always-succeeding PlutusV1 (datum hash), V2 (inline datum), V3 and a V2 reference-script spend, all
  evaluated by the node (phase 2 of the leg's engine: Julc for `java-julc`, Scalus otherwise), and an
  always-failing V3 script (`UTXOS.ValidationTagMismatch ... FailedUnexpectedly`). The V3 spend also goes through
  the node's evaluate endpoint, and the response is compared. In the `java-julc` leg that endpoint uses the Julc
  evaluator
  (`yano.block-producer.script-evaluator=julc`, Julc 0.1.0-pre17 with its VM provider loaded through
  `ServiceLoader`); in the default configuration it uses Scalus;
- a devnet rollback that removes a pending child's parent: the mempool rebuild drops the child, and the independent
  transaction is forged;
- after an epoch boundary: a payment, a correct `currentTreasuryValue` and a zero withdrawal (ticked views).

The harness also compares, and checks:

- the admission-shadow disagreement counters and the number of dump bundles (`scalus` as a shadow of `java-julc` and
  `java-scalus`, `java-julc` as a shadow of `amaru`);
- shadow sync on the producer and on a follower of the same kind (pipelined n2n sync from the producer): the
  per-engine and per-PV counters, zero findings in the JSONL, the block-rule counters, and the summary line written
  at stop;
- engine health metrics;
- a clean SIGTERM shutdown (exit 143, the shadow-sync summary at stop, JSONL summary line);
- no native-image failure in any log (`NATIVE_IMAGE_ERRORS` in `qa/harness/common.sh`, shared with the
  epoch-crossing, Haskell-sync and past-time-travel harnesses: missing reflection, resource or JNI registration,
  `NoClassDefFoundError`, `ClassNotFoundException`, `ServiceConfigurationError`, an unsupported feature, or a Jackson
  `InvalidDefinitionException`). Since gap 3, an uninitialized validator stops startup instead of logging;
- that the bundles the native node wrote replay on the JVM (`ShadowBundleReplayTest`), including the `ProtocolParams`
  JSON.

The workload resubmits after the next block what the node says to retry (503). That covers a catching-up
mempool and, since gap 4, an unavailable ledger state. When either happens depends only on timing.

The observations mask only what depends on timing, not on the engine: the current slot in a validity-interval
message, the ticked treasury, and the hashes of the treasury and pool-retirement transactions (their contents follow
the current epoch). The legacy path (`engine: scalus`) cannot see pending certificates, so for it the workload
confirms each certificate before its child (`-Dyano.parity.chain-certificates=false`).

**Results on the merge candidate** (2026-09-29).

- **Source.** Commit `b02ba6fe1`, a clean detached worktree with no local changes. It includes gaps 1 to 4, the
  `java-julc`/`java-scalus` engine ids, the Plutus-version check in the evaluators, the datum-bytes, value-size and
  CCL-retry fixes, and the ledger-state same-block fix.
- **Toolchain.** Oracle GraalVM 25.3.4.1 for JDK 25.0.4.1, G1, `-march=compatibility`, macOS arm64. Julc is the
  released 0.1.0-pre17 from the version catalog, with no override.
- **Default build.** Native `824d1212…`, jar `d043fee4…`.
- **`-PwithAmaru=true` build.** Built with `-PamaruWasm=<module c43eeb37…>`. Native `9482c3f8…`, jar `f29e2b7d…`.
- **Runs.** Every leg ran sequentially: the default matrix from 23:16 to 23:34, the `amaru` leg from 23:34 to 23:38.
  Ports were 7291/13591 (producer) and 7292/13592 (follower).

| Build | Leg | PV | Verdicts, accepted / rejected (each PV, JVM and native) | JVM vs native | Admission-shadow disagreements (bundles; all replay on the JVM) | Shadow sync, producer and follower |
|---|---|---|---|---|---|---|
| default | `java-julc` (evaluate endpoint on Julc) | 9, 10, 11 | 22 / 18 | identical | `scalus`: `GOV` 1, `LEDGER` 1 at PV 9 and 10; `GOV` 1, `LEDGER` 2 at PV 11 (2, 2, 3 bundles) | `java-julc` and `java-scalus`: 21 of 21 agreed each, 0 findings |
| default | `java-scalus` | 9, 10, 11 | 22 / 18 | identical | `scalus`: as for `java-julc` (2, 2, 3 bundles) | `java-scalus`: 21 of 21 agreed, 0 findings |
| default | `scalus` (default path) | 9, 10, 11 | 24 / 16 | identical | none configured | off |
| `-PwithAmaru` | `amaru` | 10, 11 | 22 / 18 | identical | `java-julc`: `ENGINE` 1, the Phase C divergence (1 bundle each) | `amaru` and `java-julc`: 21 of 21 agreed each, 0 findings |

- **Every leg passed** (harness `VERDICT: PASS` for both runs):
  - the 40 observation lines are identical between the JVM and native runs;
  - producer and follower exit 143 on SIGTERM, with the shadow-sync summary line in the JSONL;
  - no log matches `NATIVE_IMAGE_ERRORS`;
  - the block-rule, block-failure and id-mismatch counters are 0;
  - every follower reached the producer's tip.
- **PV 9 for `amaru`** is skipped by design: Amaru validates Conway from PV 10.
- **Native gaps.** None found on the merge candidate. Gaps 1 to 4 above were the only ones.
- **Follower counts.** They match the producer's, except in three JVM runs (`java-julc` PV 10 and 11, `java-scalus`
  PV 9). There the follower validated 37 transactions, because it also validated blocks that the rollback later
  removed (Phase 7a). These counts are not compared.
- **Legacy path.** Its 16 rejections are one fewer each for the vote on a missing action and the wrong
  `currentTreasuryValue`: it has no `GOV` rules and no treasury check unless `supplementary-rules-enabled`. The
  workload records these as its two problems, identically on the JVM and in native.
- **`amaru` leg.** It shows the Phase C divergence the same way on the JVM and in native: a delegation from an
  unregistered credential gives `ENGINE.AmaruEngineFailure` against `DELEG.StakeKeyNotRegisteredDELEG`.
- **Logs.** In the session scratchpad (`native-final/runs`, `native-final/logs`).
- **Earlier runs.** The same workload passed on earlier states too, on the old `java` id with the `julc` key of the
  time: `507b00927` with gaps 1 and 2, and the uncommitted Julc snapshot. Those runs were superseded by the run
  above.

Commands:

```
qa/release-qa.sh --only ledger-rules-native        # builds the jar and the native binary, then the default matrix
# or by hand, with any build:
./gradlew :app:yanoNativeDistZip -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false -PskipSigning=true
./gradlew :app:yanoDistZip -PskipSigning=true
JAR=app/build/yano.jar NATIVE=<unzipped>/yano qa/harness/ledger-rules-native-parity.sh   # "java-julc java-scalus scalus" "11 10 9"
# Amaru artifacts (both builds with -PwithAmaru=true -PamaruWasm=<module>):
JAR=… NATIVE=… qa/harness/ledger-rules-native-parity.sh amaru "11 10"
# SP=<dir> puts the runs elsewhere; HTTP_A/N2N_A/HTTP_B/N2N_B move the ports (the runs above used 7291/13591/7292/13592)
```

**Not verified here.**

- The full corpus in native: the 276 Amaru scenarios, the blueprint vectors and the mutation matrix run on the JVM
  only (see the gate choice above). The native gate covers every rule family and each Plutus language once. It does
  not cover every constructor.
- Real networks in native: preprod, preview and mainnet shadow sync, and the Haskell-follower gate (Phase 6b), ran
  on the JVM only.
- Native throughput and latency were not measured; only function was.
- Linux and amd64 native images were not built; only macOS arm64 was.
- In two earlier JVM runs of this phase (not the final runs above, where every follower reached the producer's
  tip), a JVM follower stopped following after the producer's devnet rollback. It applied one block after the
  `REAL_REORG` rollback, then nothing for 80 s. No native follower stalled. This is chain sync, not validation. The
  harness logs a WARNING for it and still checks the follower's shadow-sync findings (0), but it does not fail the
  gate. It matches the known follower-sync wedge from the pre17 regression run (a follower stops applying blocks
  after a rollback), which is tracked there, not in this phase.

#### Phase 7c results: public-network shadow sync (2026-09-29)

Two JVM nodes synced from genesis with `yano.validation.shadow-sync=true`: preprod (`~/yano-shadow/preprod`) and
preview (`~/yano-shadow/preview`). The findings below were triaged against the chain history (Koios `account_updates`,
`drep_updates`, `tx_info`, `tx_cbor`), the recorded reads in the `shadow-dumps/` bundles, and cardano-ledger
`f649f975`.

**Gate result (2026-09-30): passed.** A fresh sync from genesis of both networks with every fix below, built from
`943a5b6e0` with the combined local Julc `0.1.0-pre18-yano-local2` (bloxbean/julc PRs #219, #221, #227, #228) and
shadow sync on `java-julc,java-scalus`, reached the tip with:

| | Conway transactions (PV 9, 10, 11) | `java-julc` | `java-scalus` | Findings |
|---|---|---|---|---|
| preprod, tip epoch 316 | 4,240,097 | all agreed | all agreed | 0 |
| preview, tip epoch 1435 | 2,117,011 | all agreed | all agreed | 0 |

- The built-in AdaPot verification (treasury and reserves against `expected_ada_pots_{preprod,preview}.json`) passed at
  every epoch boundary on both networks.
- A read-only comparison with Koios matched:
  - treasury, reserves and fees for every epoch (preprod 312, preview 1,435);
  - the deposit residue for every epoch, meaning no phantom key deposits are left;
  - the registered DRep set (292 and 9,164);
  - the DRep distribution totals of the last two epochs, to the lovelace;
  - every governance proposal's state at the tip (124 and 1,552).

  The comparison is `qa/tools/koios_compare.py`; the whole routine (shadow sync, report and dumps, bundle replay,
  Koios) is "Ledger compatibility check" in [qa/README.md](../qa/README.md#ledger-compatibility-check-shadow-sync--koios).
- The one remaining difference is preview's exact deposit figure at the tip: Yano is 14,500 ADA (29 × 500 ADA) above a
  Koios-derived value. Koios `pool_list` still reports 29 pools as registered whose registration and retirement are in
  the same transaction. POOLREAP retired those pools and refunded their deposits, as Yano did: Yano has 727 pools,
  Koios reports 756 registered or retiring.
- The first sync (before the fixes) had 465 disagreements and 18 engine failures across the two networks. Its reports
  are kept beside the new run.

**Mainnet (2026-09-30): passed.** A sync from genesis followed a local Haskell node (cardano-node on `localhost:3002`)
with shadow sync on `java-julc,java-scalus`.
- **Duration:** 13 h 51 min to the tip (epoch 658, block ≈ 14,008,000), including a one-minute restart at epoch 615
  onto `eb06dcbcd` (virtual-thread shadow sync). The restart left no gap: the first run stopped with `failed=0`, and
  the second run's counters were 0 for failed before submit, failed after submit and skipped at stop.

| Conway | Blocks | Transactions (PV 9 / 10 / 11) | `java-julc` | `java-scalus` | Findings |
|---|---|---|---|---|---|
| mainnet, tip epoch 658 | 2,885,170 | 28,420,354 (9,690,266 / 17,064,085 / 1,666,003) | all agreed | all agreed | 0 |

- **Block rules:** the reference-script size, execution-unit sum and body size and hash checks showed 0 violations on
  every block.
- **Reorg:** a real reorganisation at the tip was handled.
- **AdaPot:** verification against `expected_ada_pots_mainnet.json` passed at every boundary.
- **Koios, epochs 209–658:** 0 mismatches for treasury, reserves, fees and the deposit residue (450 epochs each); the
  registered DRep set (1,050); the DRep distribution totals for epochs 657 and 658 (exact to the lovelace:
  5,234,352,595.808375 ADA over 859 DReps at 658); and every proposal's state (158).
- **Deposits:** the exact figure at the tip differs by the same Koios artefact as on preview (4 pools registered and
  retired in one transaction).
- **Throughput:** mainnet Conway catch-up was limited by shadow sync's former fixed pool of 4 platform threads. Block
  application waited on validation 59% of the time; the machine had 16 cores, 4.7 of them busy, validating 1,591
  transactions per second per engine at about 153 blocks per second. With virtual threads and
  `max-in-flight = cores / 2 = 8` (`eb06dcbcd`), the wait fell to 8%, with 5.2 cores busy, 1,762 transactions per
  second per engine and about 243 blocks per second.

**Findings**

- **`DELEG.StakeKeyRegisteredDELEG` and `GOVCERT.ConwayDRepAlreadyRegistered`: Yano's canonical ledger state was wrong
  (not the rule, not the shadow-sync view).**
  - Reports at triage time (the nodes were still syncing):
    - preprod: 170 `StakeKeyRegisteredDELEG` reports over 5 keys:
      - `5064b671…`: 158 at PV 9, 4 at PV 10;
      - `94dfe104…`: 1 at PV 9, 3 at PV 10;
      - `acab7e49…`: 2 at PV 10;
      - `6bc0fb7b…` and `c3892366…`: 1 each at PV 10;
      - plus 1 `ConwayDRepAlreadyRegistered` for DRep `739701e4…`.
    - preview: 195 reports over 54 keys:
      - `5064b671…`: 112 at PV 9;
      - `267a02cf…`: 27 at PV 9;
      - `22cf2817…` and `5da571c9…`: 3 each at PV 9;
      - 50 keys with 1 report each at PV 10.
    - Every report has `overlayTainted: false`. Its dump records the credential as `present` in the pre-block state
      (the account with a 2 ADA deposit, the DRep with a 500 ADA deposit).
  - Cause: all 170 + 195 stake reports were checked against the chain's certificate order. In each one, the
    credential's last deregistration before the report is in the same block as the registration it cancels. The
    credential was **registered and then deregistered in one block**, with no registration between that block and
    the report. The DRep report has the same shape.
    - In one transaction: preprod `a13523c8…` (block 2620906) holds `[StakeRegistration, UnRegCert(2 ADA)]`, and the
      reported registration `00586b27…` follows in block 2620909. Preview `85384095…` (block 3086527) holds
      `[RegCert(2 ADA), UnRegCert(2 ADA)]` at PV 10, followed by the reported `996ef51d…`. All the other preview keys
      follow the same pattern.
    - In two transactions of one block: preprod block 3105726, where tx 2 `5841d782…` registers and delegates and
      tx 3 `6d583a34…` deregisters, followed by the reported `dbaa50a1…`.
    - DRep: preprod block 2659850, where tx 1 `54825923…` (`ConwayRegDRep`) is followed by tx 2 `fec4cefc…`
      (`ConwayUnRegDRep`), then the reported `478e03ed…` in block 2659855.
    - The keys that recur are wallet test cycles of the form register, deregister, register-and-deregister in one
      transaction, register again. Each cycle leaves one phantom registration.
  - `DefaultAccountStateStore.applyBlock` applies a block through one uncommitted `WriteBatch`. `deregisterStake` and
    `UnregDrepCert` read the account or DRep with `db.get()`, which does not see the batch. Because the registration
    earlier in the same block was invisible to them, they skipped the delete. The account (or DRep, plus its
    governance `DRepStateRecord`, which was never tombstoned) stayed registered. Its deposit was counted in
    `total_dep` and never refunded. This does not depend on the era: pre-Conway `StakeRegistration`/`StakeDeregistration`
    in one block take the same path. The pool certificates already read through a per-block `BatchStateOverlay`.
  - The Java rules match Haskell, so they are unchanged.
    - `ConwayRegCert` always fails on a registered credential, with no protocol-version or bootstrap gate
      (`Conway/Rules/Deleg.hs:212-214` `checkStakeKeyNotRegistered`, `:233-239`). The Shelley-form `RegTxCert` is
      `ConwayRegCert c SNothing` (`Conway/TxCert.hs:150`), so there is no idempotent re-registration in either form.
    - `ConwayRegDRep` fails with `ConwayDRepAlreadyRegistered` (`Conway/Rules/GovCert.hs:210-212`).
    - `CERTS` folds a transaction's certificates in order (`Conway/Rules/Certs.hs:242-245`), and `LEDGERS` threads
      the state from transaction to transaction. On the chain's state, therefore, the reported registrations were
      of unregistered credentials.
    - The per-PV manifests and fingerprints are unchanged.
  - Fix (ledger-state). `applyBlock`'s per-block `BatchStateOverlay`, now called `blockStateOverlay`, previously
    held only the pool lifecycle keys. Every other value that a later certificate, withdrawal or vote of the block
    reads back now goes through it too. Both stores use the same two helpers on `BatchStateOverlay`: `readThrough`
    (the overlay's value, else the committed one) and `putThrough` (journal the value read, write, record in the
    overlay). In the account store each key is read once per write (`putStateWithDelta`/`deleteStateWithDelta`
    overloads take `prev`). The governance store re-reads a record's bytes for the journal, one extra point read per
    DRep or committee certificate and per DRep vote.
    - Stake accounts: `registerStake`, `deregisterStake` and `processWithdrawal`.
    - DRep registrations: `RegDrepCert` and `UnregDrepCert`. `UpdateDrepCert` no longer rewrites the unchanged
      registration entry (it was a no-op write with a journal entry).
    - MIR: the per-credential `PREFIX_MIR_REWARD` and `reward_rest` accumulators, and the pot-transfer totals. Two MIR
      certificates for one credential, or two pot transfers, in one block no longer lose the first.
    - Governance records. `GovernanceStateStore` has overlay-aware overloads of `getDRepState`/`storeDRepState` and
      `getCommitteeMember`/`storeCommitteeMember`, used for DRep registration, retirement and update, votes, committee
      hot-key authorisation and resignation. Before, an authorisation followed by a resignation in one block left a
      not-yet-enrolled member's placeholder un-resigned with a live hot key, although Haskell accepts both
      certificates (`GovCert.hs:197-208`). For an enrolled member, the resignation was written over the committed hot
      key instead of the block's.
    - DRep retirement is an explicit flag. With the overlay, a same-block retirement followed by a re-registration
      stored `registeredAtSlot == previousDeregistrationSlot`. Every reader then treated the live DRep as retired,
      because it tested `registeredAtSlot > previousDeregistrationSlot`:
      - the DRep distribution dropped its delegated stake;
      - the dormant flush and `getRegisteredDRepIds` skipped it;
      - votes did not refresh it, and `UpdateDRep` was ignored.
      Slots cannot order two certificates in one block. Haskell deletes the DRep and then inserts it, so it is simply
      live. `DRepStateRecord.deregistered` (CBOR key 10) is set by `processDRepDeregistration` and cleared by a
      registration, and all five readers use it, and so does the REST view (`AccountStateReadStore.DRepInfo`
      carries the flag, and `GovernanceResource.isRegistered` returns `!deregistered`). A record written before the
      flag existed derives it from the slots as before. `DRepDistributionCalculator.resolveDRepKey` drops a
      delegation only when its slot is before the DRep's last retirement (`<`, was `<=`). The exact
      (slot, transaction, certificate) cleanup at the retirement already removes older delegations. The old check
      also dropped a delegation made in the same block after a retirement and re-registration.
    - Committee resignation of a future member with no record now stores a resigned placeholder
      (`CommitteeMemberRecord.noHotKey(0).asResigned()`). Haskell inserts `CommitteeMemberResigned`
      (`GovCert.hs:208`). Before, the resignation was dropped, and a later `AuthCommitteeHot` passed where Haskell
      fails `ConwayCommitteeHasPreviouslyResigned`.
    - Committee-state pruning (closes #156). At every Conway boundary, after the enactments, Haskell keeps the
      committee state (hot keys, resignations) of the committee's members only (`updateCommitteeState`,
      `Epoch.hs:343`, `:419-423`, a `Map.intersection` with the members). Yano kept pre-enrollment placeholders
      forever, so a potential future member whose proposal expired and who was enrolled later came back with a
      stale hot key or resignation. The resignation then excluded it from tallies, and `GovCertChecks` failed
      `ConwayCommitteeHasPreviouslyResigned` where Haskell accepts.
      - `CommitteeStatePruning` (ledger-state) holds the rule. In Yano a member is a governance committee record with
        a term: the genesis and UpdateCommittee enactments always write the term epoch, and removals and
        NoConfidence delete the record. A record with term 0 is the placeholder that a non-member's authorisation
        or resignation creates.
      - At the start of governance Phase 2, over the committed Phase 1 result, the boundary deletes every
        placeholder record. It also deletes the certificate-path hot-key (0x30) and resignation (0x31) entries of
        every cold credential that is not a member. Each delete is journalled in the Phase 2 boundary delta, which a
        rollback of the boundary undoes.
      - `EpochBoundaryPreview` applies the same rule to its post-enactment committee and reports the pruned
        credentials (`GovernanceEffects.prunedCommitteeColds`). The ticked view (`TickedLedgerView`, through
        `CanonicalLedgerView.CommitteeRecords.certificatePathDropped`) then ignores their pre-boundary
        certificate-path entries.
    - `EnactmentProcessor.enactedMemberRecord`, shared by the enactment and `EpochBoundaryPreview`, keeps
      `resigned` and updates only the term. Before, a resigned member re-elected by `UpdateCommittee` came back
      un-resigned with its stale hot key. Haskell keeps a member's entry while the member stays in the committee.
    - Votes run per transaction, before its certificates (`GovernanceBlockProcessor.processVotes`), instead of after
      all the certificates of the block. Haskell refreshes the voting DReps' expiry before the transaction's
      certificates: in `CERTS` at PV 9 (`Certs.hs:240`) and in `LEDGER` from PV 10 (`Ledger.hs:383-395`). With the old
      order, a DRep registered and voting in one transaction at PV 9 got `currentEpoch + drepActivity − numDormant`
      instead of the registration's `currentEpoch + drepActivity` (`GovCert.hs:286-292`). From PV 10 the two orders
      agree. A vote now also sees a DRep registered or retired earlier in the block, and cannot overwrite a same-block
      retirement.
    - Rollback: the journal records the overlay's value as the previous value, and rollback undoes a block's
      operations in reverse, so it restores the pre-block bytes. The overlay is local to one `applyBlock` call, so no
      reader on another thread sees it.
    - Compatibility: on a chain without these same-block patterns, every decision and value is identical to before
      the fix, but the stored bytes are not.
      - DRep records written after the upgrade carry CBOR key 10.
      - The order of the delta journal entries changes, although the result of a rollback is the same.
    - A Conway genesis committee member with a term ≤ 0 is now a configuration error (`ConwayGenesisBootstrap`).
      Haskell would keep such an already-expired member, but the "term > 0 = member" rule would prune it. On-chain
      terms are always ≥ 1 (`Gov.hs:555-556`).
  - Tests: `SameBlockStateTest` (ledger-state, real RocksDB), 20 tests, plus:
    - `EnactmentProcessorTest`: a re-elected resigned member stays resigned;
    - `GovernanceStateStoreTest`: a record without key 10 derives the flag from its slots;
    - `GovernanceResourceTest` (app): a DRep retired and re-registered in one block is active; a retired one is
      inactive;
    - `CommitteeStatePruningBoundaryTest` (runtime, `TickingTestNode`, real boundaries):
      - the non-members' placeholders and certificate-path entries are pruned, and the ticked view equals the real
        boundary (`TickingGate`);
      - a resigned future member whose proposal expired is enrolled later without the resignation;
      - a member removed by an enacted UpdateCommittee loses its certificate-path hot key (a legacy entry without a
        record), in the ticked view too;
      - a rollback of the boundary restores the pruned state.
    - `ConwayGenesisBootstrapTest`: a genesis member with term 0 is rejected.
    - Five replay the real transactions above. `src/test/resources/shadow-sync/same-block-registration-txs.txt` holds
      the full transaction CBOR from Koios `tx_cbor`, fetched 2026-09-29; only the transaction body is used. They
      cover:
      - the same-transaction cases on preprod (PV 9, Shelley-form registration) and preview (PV 10, Conway
        `RegCert`/`UnRegCert`);
      - the two-transaction case, with its delegations;
      - the DRep case, with its retirement record and re-registration;
      - `total_dep`, and a rollback over all of them that restores the column byte for byte.
    - Eleven are synthetic:
      - a withdrawal then a deregistration in one transaction;
      - registration → deregistration → registration in one block (one deposit);
      - DRep registration → update → retirement;
      - DRep retirement → re-registration: `previousDeregistrationSlot` is read from the overlay, and the DRep is
        not `deregistered`. A vote in a later block refreshes it, and a delegation to it, in a later block or later in
        the same block, counts in the DRep distribution;
      - a vote by a DRep retired, or registered, earlier in the block (with 3 dormant epochs: the vote's expiry
        `currentEpoch + drepActivity − 3`, `Certs.hs:278-292`);
      - a vote in the DRep's registering transaction at PV 9 with 3 dormant epochs;
      - committee authorisation → resignation, for a future member and for an enrolled one;
      - a future member's resignation without a prior record, kept by the enrollment;
      - two MIR certificates and two pot transfers in one block;
      - a rollback of a committed account that is written three times in one block.
    - Mutation checks, each fix reverted in turn:
      - all fixes (HEAD): 13 of 16 fail;
      - stake overlay: 5 fail;
      - DRep governance overlay: 3 fail;
      - vote overlay: 2 fail;
      - committee overlay: 2 fail;
      - MIR overlay: 1 fails;
      - votes after the certificates: the PV 9 vote test fails;
      - rollback undone in forward order: both rollback tests fail;
      - the flag ignored (readers back on the slots): the 3 re-registration tests fail;
      - legacy records not derived from the slots: the codec test fails;
      - a resignation without a record dropped: the future-member test fails;
      - `enactedMemberRecord` dropping `resigned`: the two re-election tests fail;
      - REST `isRegistered` back on the slots: the same-block REST test fails;
      - `resolveDRepKey` back on `<=`: the same-block delegation test fails. (The check itself was removed later,
        with its test; see the DRep distribution finding below.)
      - no boundary pruning: the 3 pruning tests fail;
      - pruning without the 0x30/0x31 journal: the rollback test fails;
      - pruning only credentials that have a record: the removed-member test fails;
      - no genesis term check, or the error swallowed by the bootstrap: the genesis test fails;
      - the preview not pruning, or the ticked view keeping the certificate-path entries: the equivalence test
        fails.
  - Impact on a chainstate synced before the fix. The pattern exists in every era since Shelley, so this holds for
    every network, mainnet included.
    - Deposits. `total_dep` is inflated by one key deposit per phantom stake registration and one DRep deposit per
      phantom DRep, and it stays inflated. It feeds the AdaPot `deposits` field and every `getTotalDeposited()` read
      (`EpochBoundaryProcessor.java:691, 871`).
    - Treasury. Several credits reach any credential that has a `PREFIX_ACCT` entry, so a phantom receives them:
      - proposal-deposit refunds (`DefaultAccountStateStore.storeRewardRest`, `:2139-2141`);
      - enacted treasury withdrawals (`:2435-2441`);
      - MIR credits (`:2329`).
      Haskell keeps these in the treasury (`Conway/Rules/Epoch.hs:184-190` unclaimed refunds, `:223-236`
      withdrawals to unregistered accounts, `:350`). The credential's next registration then rewrites the account
      with reward 0 (`registerStake`), so that ADA disappears from Yano's accounting. The four preprod epochs whose
      treasury and reserves matched Koios (162, 163, 165, 170) do not rule this out elsewhere.
    - DReps and ratification. A phantom DRep is never marked retired.
      - At PV 9, the bootstrap phase allows delegating to an unregistered DRep (`Conway/Rules/Deleg.hs:225-226`).
        Stake delegated to a phantom DRep therefore counts in Yano's ratification, where Haskell ignores a DRep that
        is not registered (`Ratify.hs:265`).
      - From PV 10 it is visible only in the deposits pot and to the `getAllDRepStates` readers
        (`DRepDistributionCalculator:134`, `GovernanceEpochProcessor:757, 960, 1077`,
        `DefaultAccountStateReadStore:207`).
    - Member stake rewards and the stake distribution are not affected. `deregisterStake` deletes the pool and DRep
      delegations unconditionally, and never returns early on the missed account. Reward forfeiture is decided from
      the registration and deregistration events, which were written correctly.
    - Pool reward accounts. `EpochRewardCalculator.creditReward` credits any credential with a `PREFIX_ACCT` entry
      (`:1755`). So a phantom that is a pool's reward account received the operator rewards that Haskell does not
      pay to an unregistered reward account. After the fix it no longer does, which moves toward Haskell.
    - Remedy: a full resync from genesis for every pre-fix chainstate, mainnet included. A snapshot from before the
      first phantom is effectively genesis, because the pattern occurs from Shelley on. The first boundary on an
      upgraded chainstate that is not resynced also prunes every committee placeholder accumulated before the
      upgrade (#156). The resync covers this too.
    - Detection without a resync. No repair tool was built.
      - Current phantoms: a credential is a phantom when its `PREFIX_ACCT` entry exists but its latest stake event is
        a deregistration. The events were written unconditionally, so they are correct. The latest one is a
        `seekForPrev` to the end of the credential's range in `PREFIX_STAKE_EVENT_BY_CREDENTIAL` (key = prefix, type,
        hash, slot (8 bytes BE), tx (2), cert (2); `credentialStakeEventKey`). A slot-only comparison of
        `acctLastDeregCoordKey` against `acctRegSlotKey` is not enough, because a same-block deregistration followed
        by a legitimate re-registration has equal slots.
      - Past phantoms: most cycles end registered legitimately (`5064b671…`), while `total_dep` still carries the
        phantom deposits. Recompute Σ `PREFIX_ACCT` deposits + Σ `PREFIX_DREP_REG` deposits + Σ pool deposits and
        compare it with `total_dep`.
      - DReps have no per-credential event log, so a current phantom DRep can be found only by comparing the
        `PREFIX_DREP_REG` entries with the chain's DRep history (for example Koios `drep_updates`).
    - Not changed here: Yano never removes a retiring DRep's votes, whereas Haskell's GOV rule removes the votes of
      every DRep that the transaction retires from every proposal (`Gov.hs:613-629`, `cleanupProposalVotes`).

- **DRep distribution: a PV 9 delegation made before its DRep registered was cleared when that DRep retired.** A
  Koios comparison of the preview node found `drep1ygdsvk24…` (`1b065955…`) missing from Yano's DRep distribution.
  Koios gives it 519.649682 ADA from its only delegator, `stake_test1urx7qr0l…` (`cde00dff…`).
  - Chain (preview, PV 9, epoch 734). Tx `99e03aae…` (block 2619755) holds `[VoteDeleg → A, RegDRep A]`, where A is
    `drep1ytx7qr0l…` (the same hash as the delegator). Tx `0c9775f6…` (block 2619757) holds
    `[VoteDeleg → drep1ygdsvk24…, UnRegDRep A]`.
  - Haskell. `ConwayUnRegDRep` clears every account in the retiring DRep's `drepDelegs`, whatever that account's
    current target (`GovCert.hs:246-254`). The set grows by `Map.adjust` (`Deleg.hs:363-365` at PV 9,
    `Conway/State/VState.hs:137-142` from PV 10). That is a no-op for an unregistered DRep, which the bootstrap
    allows as a target (`Deleg.hs:225-226`), and `ConwayRegDRep` starts the set empty (`GovCert.hs:229`). A's set
    was therefore empty, and the retirement cleared nothing.
    - PV 9 keeps a stale member after a redelegation from one credential DRep to another (#4772). A retirement
      therefore does clear a delegator that has moved on, provided it joined while the DRep was registered. The PV 10
      `HARDFORK` rebuild (`HardFork.hs:82-105`) ends this. Yano already reproduces it, and it is unchanged.
  - Cause: `delegateToDRep` added the `PREFIX_DREP_DELEG_REVERSE` entry for any credential DRep, registered or not.
    - Not batch visibility: the delegations are in earlier blocks, so `c30b27297` does not fix it.
    - Not the (slot, transaction, certificate) guard in `clearDelegationIfNotAfter`. It never fires, because a
      forward delegation read at a retirement is always earlier than it.
  - Fix: the reverse entry is written only if the target's `PREFIX_DREP_REG` entry exists, read through the block
    overlay. The fix adds no writes, so the rollback journal is unchanged.
  - Scope: only PV 9 delegations to an unregistered credential DRep. From PV 10, `DELEG` rejects them
    (`Deleg.hs:225-226`), so the check always passes. Every change moves toward Haskell:
    - Such a delegator keeps its delegation when that DRep later retires. The new DRep's distribution includes the
      delegator's stake. So do the DRep tallies, whose denominator counts the stake of active non-voting DReps.
    - `activeDRepKeys` is built from the distribution's keys (`GovernanceEpochProcessor.buildActiveDRepKeys`). A DRep
      whose only stake was wrongly cleared was missing from it, and now appears while unexpired.
    - SPO default votes are unchanged, because only AlwaysAbstain and AlwaysNoConfidence targets matter
      (`VoteTallyCalculator:166`).
    - Rewards, deposits and AdaPots have no direct input from DRep delegations. They could change only through a
      changed ratification outcome.
  - Tests. Each of the following fails without the fix:
    - `SameBlockStateTest`: a replay of the real preview transactions above, with the delegator's registration
      `769eddf5…` and B's registration `a5ac5309…` (CBOR from Koios `tx_cbor`, fetched 2026-09-29), plus a rollback
      that restores the column byte for byte;
    - `DefaultAccountStateStoreDRepDelegationTest`: the redelegation and the retirement in one transaction, in one
      block and in two blocks, and a retirement that keeps the delegator's (dangling) delegation.
  - Two related fixes in the same area (review of the fix above):
    - The PV 10 rebuild ran one boundary late. Haskell runs `HARDFORK` at the boundary that enacts PV 10, after
      enactment and before `setFreshDRepPulsingState` (`Epoch.hs:367-372`). Yano gated the rebuild on the new
      epoch's protocol version before governance (`EpochBoundaryProcessor` step 4c), when the tracker still held the
      carried-forward PV 9; the hard fork's version is applied to the new epoch in governance Phase 1. The preprod
      resync logged the rebuild at the boundary into epoch 182, and preprod's first PV 10 epoch is 181 (Koios
      `epoch_params`). During that first PV 10 epoch, a retirement of a DRep with stale PV 9 members over-cleared,
      and a PV 9 delegation to a DRep that was never registered survived the fork (`HardFork.hs:97-98`), so it
      counted once that DRep registered.
      - Fix: `GovernanceEpochProcessor` runs the rebuild (`rebuildDRepDelegReverseIndexIfNeeded`, now through a
        `HardForkDRepDelegationRebuilder` hook) inside the Phase 1 batch, after enactment, when the new epoch is at
        PV 10, so the Phase 2 DRep distribution already sees it. The rebuild writes only the entries that differ and
        journals each one (and its marker) in the Phase 1 boundary delta, so a rollback of the boundary restores the
        PV 9 sets and the removed delegations, and the replayed boundary rebuilds again. Before, it committed its own
        unjournalled batch. The DRep records are scanned only when the rebuild runs (marker absent).
      - Boundary journal (`DefaultAccountStateStore.commitBoundaryDelta`, used by MIR, spendable reward_rest and both
        governance phases): entries are appended after the phase's committed ones at that slot, never over them, so
        a phase re-run by crash recovery (governance Phase 1 after a crash before Phase 2) keeps its first run's
        journal; and a journal larger than 4 MiB is split over consecutive sequences in the same batch (mainnet's
        fork boundary can remove many stale entries and dangling delegations). Rollback already undoes a phase's
        sequences from the highest down. Tests: `BoundaryDeltaV1ChunkTest` (append; split and exact rollback; both
        fail with the old single sequence-0 write).
      - The ticked view is unaffected: `TickedLedgerView` answers nothing across a boundary that enacts a
        HardForkInitiation (`pendingHardForkMakesTheWholeTickedViewUnavailable`).
      - Test: `DRepDelegationHardForkBoundaryTest` (runtime, `TickingTestNode`, real boundaries; the setup has a
        stale PV 9 member and a dangling delegation): at the boundary that enacts PV 10 both are removed, and the
        DRep registering in the first PV 10 epoch gets no distribution at the next boundary; a rollback of the
        boundary restores the reverse-index and forward-delegation ranges byte for byte, and the replayed boundary
        rebuilds again; a crash between the Phase 1 and Phase 2 commits keeps the Phase 1 journal through recovery,
        and the rollback is still exact. With the rebuild gated on the pre-enactment version (the old timing) the
        first two fail (the distribution assertion alone also fails); with the rebuild's ops not journalled, both
        rollback tests fail.
    - `DRepDistributionCalculator.resolveDRepKey` no longer drops a delegation older than its DRep's last
      retirement. Haskell counts every delegation to a registered DRep (`DRepPulser.hs:236-241`); the delegations a
      retirement clears are already gone from the account state, so the check could only drop what Haskell counts: a
      PV 9 delegation made before its DRep registered, after the DRep retires and registers again.
      `previousDeregistrationSlot` stays in the record (REST, CBOR).
      - Test: `DRepDistributionCalculatorTest.pv9DelegationBeforeRegistration_countedAfterRetirementAndReRegistration`
        replaces `delegationBeforeReRegistration_filteredByTimingGuard`, which asserted the non-Haskell drop; it fails
        with the check restored.
    - Preprod and preview: no instance of either pattern (Koios `drep_updates`, `drep_delegators`,
      `account_updates` and the baseline nodes, 2026-09-30). The DReps retired in the first PV 10 epoch (3 on
      preprod, 2 on preview) were all unregistered at the fork, so they had no PV 9 members; no DRep registering in
      that epoch had a delegator from before the fork; and no current delegation is older than its registered
      DRep's last retirement.
  - A chainstate synced before these fixes needs a full resync.

- **Phase-2 and decoding findings: 228 chain-valid transactions, none a Java-rule bug.** At triage time, preprod
  had 174 `UTXOS.ValidationTagMismatch` (13 script hashes, PV 9 and 10), 15 `ENGINE.CoinOutOfEvaluatorRange`,
  5 `ENGINE.JavaEngineFailure` "Expected Long but got OverLong", 6 `UTXOS.CollectErrors [NoRedeemer reward[i]]` and
  2 `UTXOW.MalformedReferenceScripts`. Preview had 21 `ValidationTagMismatch`, 1 `ENGINE.DecodingFailure` and 1
  `JavaEngineFailure` (set tag). Method: every bundle was rewritten with the chain's exact bytes for each recorded
  UTxO read (the output and its inline datum, sliced from the producing transaction's Koios `tx_cbor`), then replayed
  through the java engine. After the fixes below, **227 of the 228 replay as valid**. The remaining one, preview
  `1c09afd8…`, has no recorded reads because it failed before reading; it now decodes. Aiken 1.1.23
  (`aiken tx simulate`, and `aiken uplc eval` on Scalus's applied programs) was the independent evaluator.
  - **Same-block inline datums (133 of the `ValidationTagMismatch`s: every preprod PV 9 one, the preview PV 10
    ones).** All are PlutusV2 spends of an output produced earlier in the same block. `TxEffectsDeriver` built that
    output's `UtxoEntry` without the inline datum's bytes, so the Scalus bridge re-encoded CCL's `PlutusData`.
    CCL's default CBOR is canonical and sorts map keys, shorter first. A Plutus `Map` is an ordered list, and
    Haskell keeps the datum's bytes (`BinaryData`, Plutus/Data.hs:220-239), so the script saw a different `Data`.
    Example: preprod `4cad6278…` spends `1fc4d810…#0` (same block 2634522), whose datum keys `endDate, price,
    startDate` became `price, endDate, startDate`.
    - Evidence: Scalus passes with the chain's UTxO bytes and fails with the re-encoded ones. Aiken passes both
      scripts at the declared budget.
    - Two producers of unconfirmed outputs re-encoded the datum:
      - `TxEffectsDeriver`, which builds the engines' overlays: shadow sync, the ledger mempool's chains
        (`engine: java`/`amaru`) and the block-production overlay;
      - `TransactionOutputProjector`, which builds the `Utxo`s of the default mempool (`DefaultMemPool.project`,
        `TxProjection`) and of the block-build overlay (`BlockBuildUtxoOverlay`). These reach every admission engine
        and the legacy validator through `EngineAdmission`/`UtxoConversions.toEntry`, as a non-null
        `inlineDatumCbor`.

      So any Plutus transaction that spends an unconfirmed output with a non-canonical datum map was falsely
      rejected, in shadow sync, admission and block selection (found by review).
    - Fix, from the original bytes: `RawOutput` now keeps the inline datum's bytes (`inlineDatum()`, read by the
      strict decoder that already validated them).
      - `TxEffectsDeriver` takes each produced output's and the collateral return's datum bytes from
        `RawTransaction.parse(txCbor, tx)`.
      - `TransactionOutputProjector.projectOutputs(txHash, txBytes, tx)` does the same for the three runtime
        projections, and falls back to CCL's re-encoding only when the strict parse rejects the bytes.
      - The canonical UTxO store already kept them, from yaci's raw output (`DefaultUtxoStore`).
    - Other fields: a reference script is kept as its raw bytes; the address is re-encoded from its own bytes; value
      order does not matter to Scalus or to Haskell, whose `MultiAsset` is a `Map`. No other `serializeToBytes()` of
      a datum feeds a ledger view (runtime, ledger-state and tx-services were searched).
    - Consequence in shadow sync: `TxEffectsDeriver` now parses the transaction strictly, so a `TxDecodingException`
      in `SyncBlockValidator.chainEffects` (`:314-323`) returns no effects and taints the rest of the block, where it
      used to derive them from CCL's decoding.
  - **Integers in `[2^63, 2^64)` (22 transactions; `WideIntegers`, scalus-bridge).** Haskell reads these as
    `Word64`:
    - output coins and quantities (`decodeMaryValue`, Mary/Value.hs:287-295; preprod quantities of 1.5·10^19);
    - other body `Coin`s (the blueprint vector's treasury withdrawal);
    - tag-102 `Constr` alternatives (`decodeConstrExtended`, plutus-core Data.hs:298-307; `2edd684f…` has
      `Constr (2^64-1) []` in two output datums);
    - metadatum integers (`decodeInteger`, Metadata.hs:161-164; `c0c3e628…` has a CIP-25 price of 10^19).

    Scalus 1.1.1 reads them as a signed `Long`: `MultiAsset`/`Coin` in its `Transaction`, `DataApi`'s tag-102
    decoder, `Metadatum`. A script sees them as `Integer`s.
    - The bridge overwrites each such integer in place with a placeholder of the same 9-byte head. Placeholders are
      at least 2^62, above every other integer of the transaction and its resolved outputs, and preserve order.
      The bridge decodes the result, keeps the original body bytes (so the id is unchanged), and restores the true
      value in every script argument: `I` for ledger integers, the alternative for `Constr`s. Metadata never
      reaches a context, so it is only narrowed.
    - Refused, with the explicit `ENGINE.IntegerOutOfEvaluatorRange` (renamed from `CoinOutOfEvaluatorRange`,
      replacing `SignedLongRange`):
      - a wide validity slot (a script sees a POSIX time);
      - a wide part of a rational (`#6.30`), which a script sees reduced (found by review);
      - a wide negative body integer;
      - a wide `Constr` in a witness datum (its hash is computed from its bytes).
    - Blueprint vector `pass-enact-withdrawals-exceeding-maxbound-word64-…` now passes, as in Haskell: 310 passed
      (was 309), 2472 of 2487 transactions matched, no `RECORDED` evaluator limit. `BlueprintVectorGateTest` pins
      are updated.
  - **Scalus machine: three builtins (`BridgeVM`, the bridge's CEK machine, which is Scalus's with three builtins
    replaced).**
    - `serialiseData` of `Constr (2^64-1)`. Scalus writes `constr.toLong`, giving `d866822080`. Haskell writes
      `encodeWord64`, giving `d866821bffffffffffffffff80` (Data.hs:147-160). The bridge encodes such a `Data` itself,
      and uses Scalus's encoder for everything else. `2edd684f…` passes, matching Aiken's budget.
    - `verifyEcdsaSecp256k1Signature` with a zero component (61 preprod PV 10 spends of script `9dd6dd04…` with an
      all-zero signature).
      - Plutus 1.65.0.0 `PlutusCore/Crypto/Secp256k1.hs:48-57` fails for a key that
        `rawDeserialiseVerKeyDSIGN` rejects (not 33 bytes, or not a valid point). It fails for a signature that
        `rawDeserialiseSigDSIGN` rejects (not 64 bytes, or `secp256k1_ecdsa_signature_parse_compact` reporting
        `r` or `s` ≥ the group order, cardano-crypto-class `EcdsaSecp256k1.hs` `rawDeserialiseSigDSIGN`). It also
        fails for a message hash that is not 32 bytes.
      - Otherwise the result is `verifyDSIGN`'s. That is `False` for `r = 0` or `s = 0` (parsed, then rejected by
        `secp256k1_ecdsa_verify`), for a high-`s` or a wrong signature, and `True` only for a valid one.
      - Scalus requires `0 < r, s < n` and throws. The bridge returns `False` for a zero component after the key
        and message checks, and delegates every other input.
    - `equalsData`. Scalus's is `==`, and `Data.Map.equals` compares pairs as sets (order- and
      duplicate-insensitive). Haskell compares them as lists (derived `Eq`, Data.hs:42-48, Builtins.hs:1835-1841),
      so `equalsData` of two maps that differ only in order is `False` on chain and `True` in Scalus: a potential
      false acceptance. No finding depended on it. The bridge compares structurally and in order, with Scalus's
      costing (review).
  - **Scalus translation and decoding (`ContextEvaluation`).** Scripts now always run in the bridge's loop, which
    mirrors Scalus's validate mode. A failure names its redeemer, so `[-1]` becomes `spend[0]`, and the script hash
    is in the message.
    - `V3Governance`: the governance fields of a V3 context.
      - Constitution. plutus-ledger-api's `newtype Constitution` is `Constr 0 [Maybe ScriptHash]`
        (V3/Contexts.hs:266-273, :692; Conway/TxInfo.hs:682-693). Scalus's `type Constitution = Option[ScriptHash]`
        drops the constructor. Preview PV 11 `89d3a627…` proposes a new constitution next to PlutusV3 spends, and it
        passes at Aiken's budgets.
      - Found by review, same class as the withdrawal order, in fields the guardrail and vote scripts read. Haskell
        builds these with `transMap`/`Set.toList` over the ledger's maps and sets, in the ledger's `Ord`, and
        reduces rationals (Conway/TxInfo.hs:671-681, :697-699; Plutus/TxInfo.hs:118-119; ToPlutusData.hs:77-79).
        Scalus instead:
        - sorts `txInfoVotes` by `toString`, so action index 10 comes before 9;
        - looks a `Voting` redeemer's voter up in that `toString` order (`getScriptPurposeV3`), so a script voting
          as a DRep could see another voter as its purpose;
        - puts key credentials first in `TreasuryWithdrawals` and in the committee members `UpdateCommittee` adds;
        - lists the removed members in hash-set order;
        - keeps the quorum and the `ParameterChange` rationals unreduced (`6/2000`).
      - `V3Governance` rewrites all of these. `ScalusContextDifferentialTest` compares the bridge's contexts, as
        encoded `Data`, with script-evaluators' `ConwayTxInfoTranslator`, over the mutation world. It covers key and
        script voters, action ids 9, 10 and another transaction's, a mixed treasury withdrawal, a committee update
        removing six members and adding three, an unreduced quorum and parameters, and a new constitution. It
        passes, and fails on every one of these items without the fix.
    - `WithdrawalOrder`. Haskell orders withdrawals by network, then `ScriptHashObj` before `KeyHashObj`
      (Credential.hs:98-101, Address.hs:183-190). Scalus orders by hash only (RewardAccount.scala:24-30). With a
      key and a script withdrawal, a `Rewarding` redeemer named the wrong one: 6 preprod PV 10
      `NoRedeemer reward[i]`s. Fix: the redeemers are renumbered to Scalus's order, and the contexts are put back
      in Haskell's order, which depends on the language:
      - `txInfoRedeemers` follows the ledger's `(tag, index)` order (Babbage/TxInfo.hs:222-226), so script
        credentials come first;
      - V3 `txInfoWdrl` is the ledger map (`transMap`, Conway/TxInfo.hs:549-551, :697-699), so script credentials
        come first;
      - V1/V2 `txInfoWdrl` is rebuilt as a `Map PV1.StakingCredential` (`transWithdrawals`, Alonzo/Plutus/TxInfo.hs:
        301-309), whose derived `Ord` puts `PubKeyCredential` first (PlutusLedgerApi/V1/Credential.hs:30-37).

      The Julc agent's context builder found that V1/V2 case: the first version sorted every language script-first.
      `aPlutusV2ScriptSeesKeyWithdrawalsFirst` runs a PlutusV2 script that checks the order.
    - `PlutusBinaries`. For PlutusV1/V2, `deserialiseScript` ignores the bytes after the CBOR-wrapped program
      (SerialisedScript.hs:261-264), and the Plutus Core version is checked only when a script runs (Eval.hs:113-122).
      Scalus reads the whole binary as one item, so the reference scripts of preprod `aee75c1c…` and `c3cc23d4…` were
      reported `UTXOW.MalformedReferenceScripts`. V1/V2 binaries are now read from their leading item, for the
      well-formedness check and for evaluation.
    - Set tag. Scalus's `UpdateCommittee` decoder reads the removals as a plain array (GovAction.scala:199-203).
      Haskell allows the set tag (Decoder.hs:1081-1085). `ScalusTransactions` retries with the body's set tags
      dropped, keeping the original body bytes. This also fixes preview PV 11 `2c3657d0…` and Amaru scenario 00031
      under the `scalus` engine, now valid as in Haskell (`ScalusEngineCorpusFixesTest`).
  - **CCL decoding (`CclTransactions`, ledger-rules).** Preview `1c09afd8…` registers a pool whose owners and relays
    are indefinite-length arrays. Haskell decodes both (`decodeSet`, Decoder.hs:952-962, :1081-1085;
    `decodeStrictSeq`, :1156-1157; StakePool.hs:574-590). cbor-java keeps the `break` marker as the last item, which
    CCL's `PoolRegistration.deserialize` casts to a byte string. Every structural CCL decode on the validation path now
    retries on a copy with every item definite-length (`DefiniteLengthCbor.normalizeTransaction`, moved from
    scalus-bridge to ledger-rules; Scalus keeps the body-only `normalizeBody`). Those decodes are:
    - the java engine and shadow sync's block decoder;
    - `DefaultMemPool.project`, `TxProjection.of`, `EngineAdmission.pinInputs`, `TransactionValidationService` and
      `BlockBuildUtxoOverlay`;
    - `ScalusLedgerValidationEngine`.

    Before, a transaction the java engine accepted was rejected by the mempool projection. The id and hashes still
    come from the original bytes.
  - Each Scalus workaround has a canary in `ScalusWorkaroundsTest` that asserts Scalus's current behaviour and fails
    once upstream follows Haskell. The deviations are summarised in the table under §5. The upstream issue draft
    lists twelve items with reproductions, including the run-time Plutus Core version check that Scalus lacks.
  - Tests. The real transactions are vendored:
    - `PublicNetworkPhase2Test` (scalus-bridge): 11 corrected bundles, one per cause, shared with the Julc
      evaluator's tests as `PublicNetworkTransactions.PHASE2_CASES` (ledger-rules test fixtures);
    - `ProducedInlineDatumTest` (ledger-rules) and `DefaultMemPoolTest`/`BlockBuildUtxoOverlayTest` (runtime): preprod
      `1fc4d810…` from Koios, shared as `PublicNetworkTransactions` (ledger-rules test fixtures);
    - `CclTransactionsTest` (ledger-rules) and `DefaultMemPoolTest`: preview `1c09afd8…`;
    - `WideIntegersTest` and `ScalusWorkaroundsTest`.
  - Suites:
    - `:scalus-bridge:test` with the Amaru corpus: 125 tests, 1 skipped;
    - `:ledger-rules:test`: 370 tests, 2 skipped;
    - `:ledger-conformance:test` with the corpus: 132 tests, 0 failures. The Phase 3/4/5 gates and the mutation matrix are
      unchanged, and the blueprint gate is re-pinned as above;
    - `:runtime:test` (mempool, block producer, validation, shadow sync): 327 tests, 1 skipped;
    - `:tx-services:test` (shadow sync, bundle replay, engine bootstrap, admission): 16 tests, 2 skipped.
  - Open:
    - Amaru's `UtxoOutputEncoder` and `TransactionOutputProjector` keep the canonical fallback for an output
      without datum bytes. It is reached only by an entry built from a CCL output alone (test fixtures), or when the
      strict parse rejects a transaction's bytes.
    - `CollectErrors` order: Java reports `BadTranslation` once per language after the loop over the needed
      scripts, while Haskell reports it per script, interleaved with the other collect errors
      (Alonzo/Plutus/Evaluate.hs:130-175). The verdict is the same, and this predates PR #155.
    - Shadow bundles recorded before this fix hold re-encoded datums for same-block outputs, so they do not replay
      as the chain's state.

- **`UTXO.OutputTooBigUTxO`: the java engine and the Scalus engine measured a value's size with definite-length
  maps.** Preprod `96ae78f7…` (block 4990228, slot 129586448, PV 11) was reported `(5001, 5000, output 1 …)`, and the
  chain accepted it. Output 1's value has six policies, one of them (`588a22e1…`) with 324 assets.
  - Haskell: `validateOutputTooBigUTxO` measures `BSL.length (serialize (pvMajor protVer) v)` (Alonzo/Rules/Utxo.hs:
    412-431, :428), the ledger's own encoding of the value, not the transaction's bytes. `EncCBOR MaryValue`
    (Mary/Value.hs:342-349) writes `[coin, multiAsset]`, and both maps go through `encodeMap` (cardano-ledger-binary
    Encoder.hs:397-408). From encoding version 2 `encodeMap` is `variableMapLenEncoding` (:432-443,
    `lengthThreshold = 23`): definite-length up to 23 entries, indefinite-length (`bf … ff`) above. Heads and
    integers are minimal, so the encoding is canonical except for these map lengths. From 256 entries it is one byte
    smaller than a definite-length encoding (whose `b9 NNNN` head is 3 bytes); up to 255 entries the sizes are equal.
  - This value is 5001 bytes in the transaction (a definite `b9 0144` head), 5001 as a definite-length encoding and
    5000 as Haskell measures it, exactly `maxValSize`.
  - Fix: `LedgerValue.serializedSize` (ledger-rules `tx`) frames each map as `encodeMap` does (`mapHeadSize`). The
    helper is outside the unit digests, so the manifests are unchanged; the fix holds at every Conway protocol
    version.
  - Scalus 1.1.1 has the same fault: `OutputsHaveTooBigValueStorageSizeValidator` measures `Cbor.encode(value)`,
    with definite-length heads, and rejected the transaction with 5001. `YanoOutputValueSizeValidator` (scalus-bridge)
    replaces it in `YanoCardanoMutator` and sizes with `LedgerValue.serializedSize` (row in the §5 table). Amaru
    counts as Haskell does (`inherent_value.rs`) and accepts the transaction.
  - Size-rule audit, against `f649f975`. The other size rules use the original bytes in Haskell, and so does Yano:
    - `BabbageOutputTooSmallUTxO`: `sizedSize` of the decoded `Sized` output (Babbage/Rules/Utxo.hs:303-323,
      `babbageMinUTxOValue` Babbage/TxOut.hs:665-689; outputs and collateral return, Conway/TxBody.hs:127-128) —
      `RawOutput.size()`, the output's slice.
    - `MaxTxSizeUTxO`: `sizeAlonzoTxF` over `toCBORForSizeComputation` with the memoized body, witnesses and
      auxiliary data (Alonzo/Tx.hs:324-331, :432-443) — `RawTransaction.size()`, and `YanoTransactionSizeValidator`
      for Scalus.
    - `ConwayTxRefScriptsSizeTooBig` and the reference-script fee: `originalBytesSize` of each reference script
      (`txNonDistinctRefScriptsSize`, Conway/UTxO.hs:166-170) — `MinFee.scriptOriginalSize` over the stored script
      bytes.

    The copied CCL `OutputValidationRule` (the `JavaLegacyEngine` conformance baseline only) still measures with CCL's
    definite-length encoding; it is left as the baseline.
  - Tests. The dump is vendored unchanged (its transaction and both UTxOs are byte-identical to Koios `tx_cbor`) as
    `PublicNetworkTransactions.PREPROD_INDEFINITE_ASSET_MAP_OUTPUT`:
    - `UtxoRuleTest` (ledger-rules), `ScalusOutputValueSizeTest` (scalus-bridge) and `AmaruOutputValueSizeTest`
      (amaru-validator): valid at `maxValSize` 5000, `OutputTooBigUTxO` at 4999;
    - `LedgerValueTest`: 23, 24, 255 and 256 entries in the asset map and in the policy map, against CCL's
      definite-length encoding (equal, equal, equal, one byte less), and a 5000/5001 boundary;
    - `ScalusWorkaroundsTest`: a canary on Scalus's definite-length size, and the real value's 5000 and 5001.

#### Phase 7c results: phase 2 evaluator: Julc (2026-09-29)

Goal (Satya): a second phase-2 evaluator, as an alternative if Scalus blocks us and as an independent cross-check of
the shadow-sync findings. Julc is upgraded to `0.1.0-pre17` (group `org.julclang`, packages `org.julclang.*`,
`507b00927`).

- **Evaluator.** `script-evaluators` `phase2.JulcScriptPhaseEvaluator`. It uses julc only for the CEK machine
  (`JavaVmProvider`, one per language, protocol version and cost model, configured once, so it is thread-safe), the
  cost models and the script decoder. The contexts come from `ConwayTxInfoTranslator`, which builds V1, V2 and V3
  contexts from the original bytes (`RawTransaction`) as Conway/TxInfo.hs does. Integers are `BigInteger` throughout,
  so Word64 values need no special case. A PlutusV3 script must return `()` (`InvalidReturnValue`). The budget is the
  declared ExUnits. The PV 9 V3 context leaves out the `reg_cert`/`unreg_cert` deposits.
- **Shared contract.** The engine-neutral part of `ScalusScriptPhaseEvaluator` moved unchanged to `ledger-rules`
  `phase2.ScriptCollection`, which `ScalusScriptPhaseEvaluator` now delegates to (its tests moved to
  `ScriptCollectionTest`), and the shared `NeededScript` replaces `NeededPlutusScript`: `CollectErrors` accumulation and
  order, the Conway `TxInfo` translation checks (the V1/V2
  guard, V1 inline datums with reference scripts accepted, Byron addresses, V3 disjointness from PV 11,
  `TimeTranslationPastHorizon`), malformed-script failures, cost models, budgets and the bootstrap predicate. Julc
  reuses UTXOW's `WitnessNeeds.scriptsNeeded` and `UtxowSubject.scriptsProvided` (new overloads that take a UTxO
  lookup), and reads everything else from the engine's `RawTransaction` (redeemer data slices, vote values, the
  proposals' parameter-update slice, quorum and constitution script, outputs' inline datums), which the engine hands
  down (`ScriptPhaseEvaluator.evaluate/collect(RawTransaction, …)`, `TxEffectsDeriver.derive(RawTransaction, …)`,
  also in shadow sync) instead of a second parse.
- **Plutus Core version, once for both evaluators.** `deserialiseScript` does not check the program's Plutus Core
  version; `mkTermToEvaluate` does when the script runs (plutus-ledger-api Common/Eval.hs:113-122,
  `plcVersionsAvailableIn`, Versions.hs:341-357: 1.0.0 for V1/V2 and also 1.1.0 from PV 11; both for V3), and the
  ledger reports its `EvaluationError` as a script failure (`evaluatePlutusWithContext` → `Fails`, so
  `ValidationTagMismatch FailedUnexpectedly` for `isValid = True`). It is part of the evaluator contract: after the
  `CollectErrors` and before running any script, both evaluators check every needed Plutus script with
  `ScriptCollection.plutusCoreVersionFailures` (over `PlutusScriptDecoder.programVersion`) and return
  `ScriptPhaseResult.Failed` if one is not available. So every engine gets it, including `amaru` with
  `phase2: scalus`, and the rules have no hook for it. Julc's `isWellFormed` now only decodes (it had also applied
  julc's version check, a false `MalformedReferenceScripts` for preprod `aee75c1c…`, whose output carries a V2
  reference script of version 1.1.0 at PV 10). V1/V2 scripts decode from their leading CBOR item
  (`PlutusScriptDecoder.leadingItem`, bytes after it allowed, SerialisedScript.hs:261-264). Interpreter-specific context workarounds stay with each evaluator. The Scalus bridge's (withdrawal order, the
  V3 `Constitution` wrapper, V1/V2 trailing bytes, Word64 placeholders) are needed there because of Scalus's own
  translation. Julc's translator follows Haskell directly and needs none of them. One exception: `WithdrawalOrder` also
  reorders the V1/V2 `txInfoWdrl` with script credentials first, but Haskell builds it as a
  `Map PV1.StakingCredential Integer` (Alonzo/Plutus/TxInfo.hs `transWithdrawals`), so plutus-ledger-api's derived
  `Ord` puts `PubKeyCredential` first there. Only V3's `txInfoWdrl` and the redeemer indices follow the ledger's
  `AccountAddress` order.
- **Configuration (decision: julc is the Java engine's default phase 2).** Two engine ids, no new key:
  `java-julc` is the Java rules with julc phase 2 (`JavaJulcEngineFactory`, `EngineContext#julcScriptPhaseEvaluator`)
  and is the Java engine wherever one is defaulted (`shadow-sync-engines` defaults to it); `java-scalus` is the same
  rules with Scalus phase 2 (`JavaScalusEngineFactory`, `EngineContext#scriptPhaseEvaluator`), for users who need it.
  The former id `java` (Scalus phase 2) is removed without an alias: a configuration that still names it stops startup
  with `Unknown validation engine 'java'`, which lists the available ids. Both ids need
  `yano.validation.java-engine.experimental=true` for admission until Phase 8; shadow sync answers it.
  `shadow-sync-engines: java-julc,java-scalus` gives every Conway transaction both verdicts. Counters, health
  (`shadowSync.java-julc.healthy`), report lines and bundles (`<tx>-java-julc.json`) carry the engine id.
  `ShadowBundleReplay` replays bundles with `java-julc`, `--scalus` with `java-scalus`. The default admission path
  (`engine: scalus`, the legacy validator), the evaluate endpoint and ExUnits estimation are unchanged.
- **Known deviation until the julc release.** The released julc 0.1.0-pre17 lacks the `verifyEcdsaSecp256k1Signature`
  fix (julc PR #219, below). Until the catalog moves to a julc release with it, `java-julc` rejects the preprod
  transactions of script `9dd6dd04…` that call the builtin with r or s = 0 (for example `031e36a7…`), which the chain
  accepts; `java-scalus` accepts them (`BridgeVM`). `JulcPublicNetworkTest.knownJulcDeviationsStillFail` pins it and
  fails once the fix is in.
- **Preview resync (2026-09-30), two more julc bugs.** A fresh preview resync (`b02ba6fe1` with the local julc
  `0.1.0-pre18-yano-local`, julc PRs #219 and #221; shadow sync `java-julc,java-scalus`): preprod 3.1M Conway
  transactions and preview 1.24M agree under `java-scalus`; on preview `java-julc` disagrees on 363. Both causes are in
  julc, not in Yano's translator: the 138 script contexts are identical to the Scalus bridge's (compared with the
  `ScalusContextDifferentialTest` method on the real transactions), and Yano's own `PlutusScriptDecoder` accepts the
  225 scripts.
  - 225 `UTXOW.MalformedScriptWitnesses` (PV 10, 12 PlutusV3 scripts, e.g. `4f8c8e22…` in `b0e24e31…`): the script
    holds a 2048-bit integer constant. julc's `FlatReader.decodeVli7` refuses more than 128 vli7 groups (896 bits).
    plutus-core decodes any size (`dInteger = zagZig <$> dUnsigned`, unbounded for `Integer`: plutus-core/flat
    Decoder/Strict.hs:111-112, 240-249).
  - 138 `UTXOS.ValidationTagMismatch FailedUnexpectedly` (125 at PV 10, script `0298c2c0…`, e.g. `aec876ad…`; 13 at
    PV 9, script `b39623ec…`, e.g. `511fb350…`): PlutusV2 withdrawal scripts that check `verifyEd25519Signature` over
    `serialiseData` of a redeemer holding integers above 2^512. julc's `DataSerializer` writes such a bignum
    (tag 2/3) as one byte string; plutus-core chunks it into 64-byte pieces (`encodeInteger` with `encodeBs`,
    PlutusCore/Data.hs:180-190), so the message differs and the signature fails.
  - With both fixed in a scratch julc build, all 363 dump bundles replay VALID under `java-julc`. The three
    transactions are `PHASE2_CASES` (the dumps unchanged; transaction and UTxOs byte-identical to Koios `tx_cbor`),
    valid under Scalus, and pinned as known julc deviations in `JulcPublicNetworkTest` until julc has the fixes.
- **Why not julc's `JulcTransactionEvaluator`.** It estimates ExUnits (budget `maxTxExUnits`), and its context
  builder (`CclTxConverter`, `V1V2ScriptContextBuilder`) differs from Haskell:
  - the tx id and witness-datum hashes are taken from CCL re-serialisations;
  - `JulcAssocMap.insert` prepends, so the datum, redeemer, withdrawal and vote maps and values come out in reverse
    encounter order, not key order;
  - signatories are unsorted and not deduplicated;
  - withdrawals are ordered by bech32 string, voters by `toString`;
  - V1 withdrawals and datums are encoded as maps instead of lists of pairs;
  - every governance action becomes `InfoAction`;
  - PV 9 deposits are always present;
  - a validity bound of 0 counts as absent;
  - reference scripts of resolved inputs are not resolved;
  - a V3 script's return value is not checked, and a missing V1/V2 datum becomes `Constr 0 []`.
  Yano's ExUnits estimation (`JulcTxEvaluator`, script anchors) inherits these.
- **Baseline** (`ledger-conformance/docs/baseline-2026-09.md`, rows `java-julc` and `java-scalus`; the conformance
  gates, mutation matrices and blueprint gate now run `java-julc`): 276/276 verdicts, 275/276 constructors, 94/94
  mutants for both, identical case by case (the one miss is scenario 00280, a Byron-address fault shared by both).
  **Blueprint vectors** (`BlueprintVectorScalusTest`): every one of the 2487 transactions gets the same verdict and
  first failure under `java-scalus` as under the gate's `java-julc` run, with the pinned tallies (PV 9 115/115, PV 10
  195 passed and 10 requiring an epoch), including the Word64 treasury-withdrawal vector.
- **Shadow-sync dump replay** (309 preprod and 198 preview bundles, snapshot of 2026-09-29):
  - 344 `StakeKeyRegisteredDELEG` and 1 `ConwayDRepAlreadyRegistered`: identical under Scalus and Julc (the
    ledger-state bug above); the 1 preview `DecodingFailure` bundle recorded no reads and cannot be replayed;
  - 15 `CoinOutOfEvaluatorRange` and 2 `JavaEngineFailure` (Word64 quantities above 2^63): VALID under Julc, as on
    chain;
  - 144 `ValidationTagMismatch FailedUnexpectedly`: Julc fails the same 144, so neither the machine nor the context
    builder is the common cause:
    - 133 spend an output created earlier in the same block whose recorded read has no original inline-datum bytes.
      CCL's re-encoding orders the datum's map keys canonically (length first); the chain's order differs (checked
      with the node's `/txs/{hash}/utxos`). With the chain's bytes patched into the bundles (the node's API, and Koios `tx_cbor`
      for one spent output), all 133 are VALID under both evaluators. The cause is the overlay's
      `UtxoEntry.inlineDatumCbor` for same-block outputs, which the scalus-bridge triage fixes at the source
      (`RawOutput#inlineDatum()`).
    - 11 (preprod, PV 10, script `9dd6dd04…`) call `verifyEcdsaSecp256k1Signature` with r or s equal to 0.
      libsecp256k1's `parse_compact` accepts 0 and verification returns `False` (cardano-crypto-class
      `rawDeserialiseSigDSIGN`, plutus `Secp256k1.hs`). Julc (`CryptoBuiltins`: r, s in [1, n-1]) and Scalus both
      fail the builtin: a machine bug in both. The scalus-bridge fixes Scalus's (`BridgeVM`); julc's is fixed upstream
      (PR #219). With that julc build (`0.1.0-pre18-yano-secp`, not yet in the catalog) and the chain's datum bytes,
      **Julc agrees with the chain on all 144**.
      `JulcPublicNetworkTest.knownJulcDeviationsStillFail` fails once the catalog moves to a fixed julc; then the case
      joins the others.
- **Tests.**
  - `script-evaluators`: `JulcScriptPhaseEvaluatorTest` (mutation world: V3 pass and fail, budgets, `NoRedeemer`,
    horizon, well-formedness), `ConwayTxInfoTranslatorTest` (PV 9/10 `reg_cert`, V1 lists vs V2 maps, values, fee and
    mint per language, validity interval, `ChangedParameters`), `JulcPublicNetworkTest` (the 14 shared chain-valid
    bundles, `PublicNetworkTransactions.PHASE2_CASES` in the ledger-rules test fixtures: 10 valid, the four known
    julc deviations, the V2 1.1.0 reference script, Word64 quantities carried exactly).
  - `ScriptCollectionTest` (the version matrix, `plutusCoreVersionFailures`); the version check per evaluator:
    `JulcScriptPhaseEvaluatorTest`, `ScalusWorkaroundsTest` (with a canary for Scalus's missing check) and
    `AmaruScalusPhaseTwoTest` (`amaru` with `phase2: scalus`); `JavaEngineFactoriesTest` (`java-julc`, `java-scalus`,
    the experimental flag, the removed `java`).
  - `ShadowSyncBothEvaluatorsTest` (tx-services: `java-julc,java-scalus` through `ValidationEngines` and
    `SyncBlockValidator`); `ValidationEngineBootstrapIntegrationTest` (startup refuses `java` and names the ids).
  - `BlueprintVectorScalusTest`.
- **Open.** A validity bound at or above 2^63 is checked for the horizon through CCL's `long` (shared with Scalus).
  Julc is about as fast as Scalus on the corpus (0.89 ms vs 0.85 ms per scenario, one pass).

### Phase 8 — Switch the default, clean up

- Set `engine: java-julc`. Keep `scalus` selectable, and as a default shadow engine
  for one release.
- Remove the deprecated keys and profile overrides. Migrate or delete the
  Scalus-specific tests that no longer apply (`ScalusBasedTransactionValidatorTest`,
  `CertStateBridgeTest`, `YanoValueNotConservedUTxOValidatorTest` stay while
  `scalus` stays selectable).
- Update docs, the release notes and the testkit end-to-end profiles
  (`DRepValidationTestProfile`).
- **Status (2026-09-30): waiting.** Phase 8 starts once Julc is released with bloxbean/julc
  PRs #219, #221, #227 and #228. On the released Julc 0.1.0-pre17, `java-julc` still gets 4
  public-network transactions wrong. They are pinned as canaries
  (`JulcPublicNetworkTest.knownJulcDeviationsStillFail`), which fail once the catalog moves to
  a fixed Julc. Until then the Java engines stay behind
  `yano.validation.java-engine.experimental`.

## Acceptance criteria

Status on 2026-09-30.

| Criterion | Status | Evidence |
|---|---|---|
| The coverage matrix is 100% for the Conway PV10–11 leaf constructors, and every PV gate is tested on both sides. | **Met; scope changed to PV 9–11** (Phase 5b, decision 1) | `ledger-rules/docs/conway-rule-coverage.md`: 86/86 constructors (56 test + scenario, 30 test, 0 gaps), strict by default since Phase 5. Per version: PV 9 75/75, PV 10 77/77, PV 11 80/80, with a mutation world for each version (Phase 5c). |
| The Amaru scenarios, blueprint vectors, mutation matrix, shadow sync and native parity gates all pass. | **Met** | Amaru scenarios: `java-julc` and `java-scalus` 276/276 verdicts, 275/276 constructors; 00280 matches Haskell where Amaru diverges (`ledger-conformance/docs/baseline-2026-09.md`). Blueprint vectors: 310/320, 2,472/2,487 transactions; the other 10 vectors need an epoch transition (Phase 7b, Phase 7c Julc). Mutation matrix: 94/94 mutants, and 96/96/95 cases in the PV 9/10/11 worlds. Shadow sync: preprod 4,240,097, preview 2,117,011 and mainnet 28,420,354 Conway transactions, 0 findings under both engines (Phase 7c gate). Native parity: JVM = native for `java-julc`, `java-scalus`, `scalus` and `amaru` at PV 9–11 (`943a5b6e0`). |
| Dependent chains in the mempool and in one block, and across an epoch boundary, validate identically under `java-julc` and `amaru`, and under `scalus` for the state it models. A Haskell follower accepts every Yano-produced block in the Phase 6 matrix. | **Met** | The devnet matrix (`JavaEngineDevnetGateTest`; 423 transactions re-validated in `SYNC` mode) and `AmaruDevnetParityTest` give identical observations. The one Amaru divergence is recorded and rejected under both engines (ADR-057 Phase C). The native-parity workload runs the same chains under all four engines (legacy `scalus` with each certificate confirmed first). Haskell follower in lock-step: 2,497 blocks with the Java rules (engine id `java` then, `java-scalus` now; `java-julc` runs the same rules) and 2,362 blocks with `amaru` (Phase 6b). |
| With `engine: java-julc`, the submit path does not call Scalus (Julc runs phase 2); with `engine: java-scalus`, only for phase-2 script execution. | **Met; engine ids changed** (Phase 7c: `java` split into `java-julc` and `java-scalus`) | `JavaJulcEngineFactory` wires `JulcScriptPhaseEvaluator` and stops startup without it. `java-scalus` reaches Scalus only through the `ScriptPhaseEvaluator` SPI (`JavaEngineFactoriesTest`). |
| Canonical ledger-state, reward, AdaPot and ratification outputs are unchanged. The existing verification suites stay green. | **Changed** | The rules engines do not write canonical state. Phase 7c found ledger-state bugs and fixed them to match Haskell, which changes deposits, treasury credits and the DRep distribution: same-block registration state, the DRep `deregistered` flag and committee-state pruning (#156) in `c30b27297`; DRep delegator sets and the PV 10 rebuild at the enacting boundary in `c84c05ba1`. Existing chainstates need a full resync from genesis ("Impact on a chainstate synced before the fix"). After the fixes, AdaPot verification passed every epoch on preprod and preview, and Koios matched (Phase 7c gate). Mainnet: AdaPot verification passed every epoch, and Koios matched for epochs 209–658 (Phase 7c gate). CI build, commit-build and integration jobs are green. |

## Follow-ups

Out of scope for PR #155:

- yano #158: remove an unregistering DRep's votes from proposals (Haskell `cleanupProposalVotes`).
- yano #159: the genesis committee bootstrap overwrites a hot key authorised in epoch 0.
- yano #160: a validation mode for sync (observe, log, enforce) for blocks from untrusted peers.
- Align the AdaPot `deposits` (`total_dep`) with Haskell's `utxosDeposited`, in a separate PR.
- Julc #223 (`NewConstitution` data encoding), #224 (V1/V2 translation errors in
  `JulcTransactionEvaluator`), #229 (one Plutus Data CBOR encoder).

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
| The scope makes the final PR hard to review | One commit or more per phase, each reviewed by independent sub-agents and gated before it is pushed |

## Rollback plan

- **Behaviour.** Until Phase 8, `engine: scalus` is the default, so a
  regression in the Java engine is contained by configuration. After Phase 8,
  set `yano.validation.engine=scalus` to roll back without a redeploy of code.
- **Module rename.** Phase 0 is mechanical and isolated in `a77c49bed`.
  Reverting it restores `ccl-ledger-rules`. The BOM entry for `yano-ccl-ledger-rules` is
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
   | Admission latency, `engine: java-julc`, transaction without Plutus | p99 ≤ 20 ms |
   | Admission latency, `engine: java-julc`, transaction with Plutus | p99 ≤ 20 ms plus script evaluation time |
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
