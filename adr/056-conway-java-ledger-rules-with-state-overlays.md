# ADR-056: Complete Conway Ledger Rules in Java with State Overlays

## Status

Proposed (revised after independent review, 2026-09-28)

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
  - Scalus `1.1.1` enforces about **57 of 88** leaf Conway predicate failures
    of the Haskell ledger.
  - It enforces **none of the 19 `ConwayGovPredFailure` constructors**, and not
    `DelegateeDRepNotRegistered`, `DelegateeStakePoolNotRegistered`,
    `ConwayWdrlNotDelegatedToDRep` or `ConwayTreasuryValueMismatch`.
  - Scalus's ledger `State` has no governance state and no treasury (the bridge
    passes an empty `govState`, `scalus-bridge/.../LedgerBridge.scala:104-109`).
  - Amaru (commit `d72e9b5`, 2026-09-25) ships 276 Haskell-cross-checked JSON
    transaction scenarios and consumes the cardano-blueprint conformance vectors.

  The counts come from bytecode and source inspection. Phase 2 of this ADR
  replaces them with measured numbers.
- Haskell `cardano-ledger` is the source of truth for rule semantics. Amaru and
  Scalus are references and oracles only. Where they disagree with Haskell,
  Haskell wins, and Yano records the divergence.

## Decision summary

1. **One Java rules module.** Rename `ccl-ledger-rules` to `ledger-rules`, fold
   it into the existing `ledger-rules` API module, and move the code from the
   copied `com.bloxbean.cardano.client.ledger` package to
   `org.yanoproject.ledgerrules`. The Aiken and julc script evaluators move to a
   new `script-evaluators` module so `ledger-rules` stays pure Java.
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
7. **One PR, built as a stack.** The work lands as one PR from the integration
   branch `feat/conway-ledger-rules`. Each phase is reviewed as a stacked PR into
   that branch before the final merge (see Implementation plan).

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
2. **Fail closed.** A missing state read, conversion error or unexpected
   exception rejects the transaction as phase-1. It never admits it.
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
| `ccl-ledger-rules` | **Removed.** Its code moves into `ledger-rules` under `org.yanoproject.ledgerrules.conway.*`. Removed from `settings.gradle` and the BOM. No external consumer was found in yano-x, yaci-store or yaci-devkit, so no relocation shim is needed. |
| `script-evaluators` (new) | `AikenTxEvaluator`, `JulcTxEvaluator` and `YaciScriptSupplier`, moved from `ledger-rules` with their tests. They keep the `TransactionEvaluator` interface. |
| `scalus-bridge` | The Scalus engine, the Scalus `TransactionEvaluator`, and the Scalus `ScriptPhaseEvaluator`. |
| `amaru-validator` (ADR-057) | Optional. Depends on `ledger-rules`. |

### 2. Validation API

```java
public interface TransactionValidator {
    TxValidationOutcome validate(TxValidationRequest request);

    /** Legacy adapter over a canonical view. Removed in Phase 6. */
    @Deprecated
    ValidationResult validate(byte[] txCbor, Set<Utxo> inputUtxos);
}

public record TxValidationRequest(byte[] txCbor, LedgerView view, ValidationEnv env,
                                  Mode mode, Origin origin) {
    public enum Mode { FULL, REAPPLY }            // §6
    public enum Origin { LOCAL, PEER, BLOCK_BUILD } // §6, isValid=false policy
}

public record ValidationEnv(long currentSlot, long currentEpoch, int protocolMajor,
                            NetworkId networkId, SlotConfig slotConfig) {}

public sealed interface TxValidationOutcome {
    record Valid(TxEffects effects, boolean phase2Valid) implements TxValidationOutcome {}
    record Invalid(List<LedgerFailure> failures) implements TxValidationOutcome {}
}
```

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
  `GovernanceStateStore` and the epoch parameter tracker. Each read is fail
  closed. The one accessor still to confirm is the candidate payload of pending
  `UpdateCommittee` proposals.
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
  - These values come from `ledger-state`'s epoch processing, run as a pure
    **dry run** over a snapshot, never persisted.
  - If a dry run isn't available for a value, the ticked view fails closed: it
    rejects transactions whose verdict depends on that value, and never guesses.
  - Its result is cached per (tip hash, target epoch).
  - Phase 1 establishes which boundary values `ledger-state` can compute
    without persisting. That is the riskiest dependency in this ADR, so it has
    its own gate.
- **`OverlayLedgerView(base)`** is an ordered stack of `TxEffects` over a base
  view.
  - `apply(TxEffects)` pushes a layer. Reads resolve newest layer first.
  - `snapshot()` returns an immutable view, used for asynchronous shadow
    engines. The mempool overlay keeps changing after admission.
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

`ConwayLedgerTransition.apply(view, env, tx)` follows Haskell Conway `LEDGER`
(`eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Ledger.hs`):

1. **If `isValid=true`, the LEDGER pre-checks**, against pre-certificate state:
   - treasury value (`ConwayTreasuryValueMismatch`);
   - total reference-script size, from spending inputs **and** reference
     inputs;
   - withdrawals: from PV11, `ConwayWithdrawalsMissingAccounts` /
     `ConwayIncompleteWithdrawals`; before PV11 the check sits in `CERTS` as
     `WithdrawalsNotInRewardsCERTS`;
   - `ConwayWdrlNotDelegatedToDRep`.
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
6. **MEMPOOL** (admission only, `Conway/Rules/Mempool.hs`): `ConwayMempoolFailure`
   for a transaction whose inputs are all already spent, and for the other
   mempool-only checks at the active protocol version.

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

**Handling phase-2-invalid transactions (`isValid=false` outcome).**
- **Local submission** (REST, n2c): reject a transaction whose Plutus fails
  while it claims `isValid=true`. This protects the submitter's collateral,
  which is Haskell's `Intervene` behaviour.
- **Peer submission** (n2n): follows Haskell's `DoNotIntervene` behaviour
  (confirm against ouroboros-consensus in Phase 1). If such a transaction is
  admitted, its effects are collateral-only, consistent with
  `DefaultMempoolEvictionPolicy`'s existing collateral handling.

**Mempool.**
- **Admission.** `DefaultMemPool` owns one `OverlayLedgerView` over
  `TickedLedgerView(canonical, nextSlot)`. Admission validates in `FULL` mode
  against it, then appends the effects under the existing mutation lane. The
  UTxO indexes stay as indexes; `resolve` is served by the overlay.
- **Rebuild without stalling admission.** A new block, rollback, eviction or
  epoch change triggers a rebuild:
  1. Take the ordered transaction list under the lane.
  2. Re-apply it in `REAPPLY` mode **outside the lane**, over a fresh ticked
     base.
  3. Swap the result in under the lane, together with a sequence check.
     Transactions admitted during the rebuild are re-applied on top before the
     swap completes, and any that now fail are dropped.

  This avoids blocking admission for O(N) × validation time.
- **Which rollback event.** A rollback rebuild waits for the **ledger-state**
  rollback (accounts and governance) to complete, not only
  `UtxoStateRolledBackEvent`. The `onCanonicalRollbackApplied` hook
  (`TxSubsystem.java:655`) moves to the ledger-state completion signal.
- **Epoch boundary.** When the next slot crosses into a new epoch, the ticked
  base changes, which triggers a rebuild.

**`REAPPLY` mode** follows Haskell `reapplyTx`, which skips
`lblStatic`-labelled checks (`Shelley/API/Mempool.hs`). Phase-2 is part of
the static set (`Alonzo/Rules/Utxos.hs`, `when2Phase`). Every other check is
re-run against the new environment.

| Skipped in REAPPLY (static) | Re-run in REAPPLY (depend on state or environment) |
|---|---|
| vkey and bootstrap signature verification, metadata hash/validity, native script evaluation, empty inputs, bootstrap address attributes, network ids (tx body, outputs, withdrawals), max tx size, **Plutus execution** | input existence, validity interval and forecast, fees, min-UTxO, value size, ExUnits limits, script integrity hash (cost models may have changed), needed witnesses, reference input disjointness, all CERTS, GOV and LEDGER checks, MEMPOOL |

**Block production.**
- `BlockTransactionSelectors.selectMempool` builds a fresh block-local
  `OverlayLedgerView` over `TickedLedgerView(canonical, forgeSlot)`. It
  deliberately does not reuse the mempool overlay: only selected transactions
  are applied, in selection order.
- It validates each candidate in `REAPPLY` mode, falling back to `FULL` if the
  candidate's admission environment differs.
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

- The admission verdict comes only from `engine`.
- **Shadow engines** receive `TxValidationRequest` with an immutable
  `snapshot()` view and run asynchronously.
  - Disagreements increment
    `yano_validation_disagreements_total{engine,rule}` and are logged once
    with the transaction hash.
  - When `shadow-dump-dir` is set, a self-contained bundle (tx CBOR plus the
    resolved view slice) is written for replay in tests.
- **Deprecations.** `supplementary-rules-enabled` and
  `default-validator-enabled` (`application.yml:336-341`, and the profile
  overrides) are deprecated.
  - `supplementary-rules-enabled=true` maps to `engine: scalus` plus the Java
    GOV and GOVCERT families as a post-filter until Phase 8.
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

## Implementation plan

**Branch model.** `feat/conway-ledger-rules` is the integration branch and the
single PR into `main`. Each numbered step below is a stacked PR into the
integration branch, reviewed on its own, with the Fable review pass. Order
across both ADRs:

| Step | Contents |
|---|---|
| S1 | 056-P0 (mechanical rename) |
| S2 | 056-P1, P2 + 057-A, B |
| S3 | 056-P3, P4, P5 |
| S4 | 056-P6 + 057-C |
| S5 | 056-P7, P8 + 057-D, E |

The final PR merges once S5's gates are green.

### Phase 0 — Module consolidation (no behaviour change)

- Move the `ccl-ledger-rules` sources into `ledger-rules` and change packages.
- Move the Aiken and julc evaluators to `script-evaluators`.
- Update `settings.gradle`, the BOM, `scalus-bridge`, `runtime` and
  `tx-services`, native-image metadata, and docs.
- Move tests: `TxBalanceCalculatorTest`, and the evaluator tests to
  `script-evaluators`.
- Gate: the full JVM suite is green; a native smoke test passes.

### Phase 1 — API, views, ticking, effects, engine selection

- Add `TxValidationRequest`/`Outcome`, `LedgerView`, `CanonicalLedgerView`,
  `TickedLedgerView`, `OverlayLedgerView` and `TxEffectsDeriver`.
- Add the Scalus engine adapter and the overlay-aware `LedgerStateProvider`.
- Add the `engine`/`shadow-engines` config. The default stays `scalus`.
- Confirm the Haskell `WhetherToIntervene` policy against ouroboros-consensus.
- Gates:
  - **Ticking gate:** for the last N preprod and preview epoch boundaries, the
    `TickedLedgerView` dry run equals the state `ledger-state` persists after
    the real boundary, for every validation-visible value in §3.
  - **Overlay tests:** register→delegate across two transactions,
    register→deregister refund, proposal→vote in one block, UTXOW sees
    pre-certificate refunds, snapshot isolation.
  - **Effects cross-check:** `TxEffectsDeriver` agrees with `ledger-state`
    block application for a sample of synced blocks.

### Phase 2 — Conformance harness first

- Add the `conformanceTest` source set, the Amaru scenario loader and
  (constructor, PV) mapping, the coverage-matrix tooling and the mutation
  framework.
- Publish a **baseline** report: the measured pass rate for Scalus, Scalus plus
  supplementary rules, and the current Java rules.

### Phase 3 — UTXO, UTXOW, UTXOS

- Implement the transition skeleton, typed failures, PV gates, the three
  families, the Scalus `ScriptPhaseEvaluator`, and the REAPPLY static/dynamic
  labelling.
- Gate: all UTXO-family scenarios pass, and their matrix rows are complete.

### Phase 4 — CERTS, DELEG, POOL, GOVCERT

- Gate: the certificate scenarios pass, and their matrix rows are complete.

### Phase 5 — GOV, LEDGER pre-checks, MEMPOOL

- Gate: all Amaru scenarios pass, or each remaining one has a recorded,
  Haskell-backed divergence. The coverage matrix is 100%.

### Phase 6 — Runtime overlays

- Add the mempool overlay with off-lane rebuild and swap, the ledger-state
  rollback signal, rebuild at the epoch boundary, the origin-dependent
  `isValid=false` policy, and the block-production overlay.
- Remove `BlockBuildUtxoOverlay` and the legacy validator overload.
- Migrate the mempool and selector tests.
- Gates:
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

## Review decisions requested

1. Approve Conway **PV10+ only** (no PV9 bootstrap).
2. Approve the **ticked base view** approach: an in-memory dry run of the
   boundary effects that are visible to validation.
3. Set the **mempool budget**: admission p99 and maximum rebuild time at the
   configured maximum mempool size.
4. Approve the **integration branch plus stacked PRs** model for the single
   final PR.
5. Decide whether `ledger-rules` should later move to CCL for reuse by Yaci
   DevKit and yaci-store.
