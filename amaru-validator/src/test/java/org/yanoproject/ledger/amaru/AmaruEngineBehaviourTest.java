package org.yanoproject.ledger.amaru;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.common.model.SlotConfig;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;

import org.junit.jupiter.api.Test;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.amaru.runtime.AmaruInstance;
import org.yanoproject.ledger.amaru.runtime.WasmAmaruInstance;
import org.yanoproject.ledger.amaru.wire.AmaruRequest;
import org.yanoproject.ledger.amaru.wire.AmaruRequestEncoder;
import org.yanoproject.ledger.amaru.wire.CborReader;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.LedgerValidationEngines;
import org.yanoproject.ledger.rules.TxIdentity;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest.Origin;
import org.yanoproject.ledger.rules.TxValidationRequest.Rule;
import org.yanoproject.ledger.rules.effects.LedgerChange;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenarioLoader;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseEvaluator;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseResult;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.model.CredentialKey;
import org.yanoproject.ledger.rules.view.model.PoolId;
import org.yanoproject.ledger.rules.view.model.ProposalState;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import run.endive.runtime.WasmInterruptedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-057 Phase B gates on the engine's behaviour: absence handling, fail-closed unavailable state,
 * traps, timeouts, the abandoned-call cap, Endive interruption, the MEMPOOL step, {@code amaru-scalus}
 * (phase one, then the evaluator) and the origin policy. All of them run the real AOT-compiled module; faults are
 * injected around it.
 */
class AmaruEngineBehaviourTest {

    private static final String DELEGATION = "00005-pass-registered-credential-delegates-to-pool.json";
    private static final String PLUTUS_SPEND = "00019-pass-plutus-spend-fee-at-minimum-including-ex-units-cost.json";

    private static AmaruScenario scenario(String file) {
        return AmaruScenarioLoader.fromEnvironment().load(file);
    }

    /** {@code amaru} without an evaluator, {@code amaru-scalus} with one. */
    private static AmaruTransactionValidator engine(AmaruScenario scenario, FaultyInstance.Shared shared,
                                                    AmaruEngineConfig config, ScriptPhaseEvaluator evaluator) {
        return new AmaruTransactionValidator(evaluator == null ? LedgerValidationEngines.AMARU
                : LedgerValidationEngines.AMARU_SCALUS, config, ScenarioSupport.network(scenario), evaluator,
                ScenarioSupport.constants(scenario), shared.factory());
    }

    private static AmaruEngineConfig config() {
        return AmaruEngineConfig.defaults(1);
    }

    private static LedgerFailure onlyFailure(TxValidationOutcome outcome) {
        assertThat(outcome).isInstanceOf(TxValidationOutcome.Invalid.class);
        List<LedgerFailure> failures = ((TxValidationOutcome.Invalid) outcome).failures();
        assertThat(failures).hasSize(1);
        return failures.getFirst();
    }

    private static List<?> slice(byte[] request, long key) {
        Map<?, ?> document = (Map<?, ?>) CborReader.decode(request);
        return (List<?>) document.get(key);
    }

    // ------------------------------------------------------------------------------ absence

    @Test
    void firstTimeDRepRegistrationIsSentWithoutTheDRepAndPasses() {
        AmaruScenario s = scenario("00003-pass-drep-registration-on-unregistered-key-hash-credential.json");
        FaultyInstance.Shared shared = new FaultyInstance.Shared();
        try (AmaruTransactionValidator engine = engine(s, shared, config(), null)) {
            TxValidationOutcome outcome = ScenarioSupport.validate(engine, s, Rule.LEDGER, Origin.LOCAL);
            assertThat(outcome.isValid()).as(outcome.toString()).isTrue();
            assertThat(slice(shared.requests.getLast(), 16)).as("dreps slice").isEmpty();
            assertThat(((TxValidationOutcome.Valid) outcome).effects().changes())
                    .anyMatch(c -> c instanceof LedgerChange.DRepRegistered);
        }
    }

    @Test
    void deregistrationThenRegistrationInOneTransactionPasses() {
        AmaruScenario s = scenario("00006-pass-deregister-re-register-then-delegate-succeeds.json");
        FaultyInstance.Shared shared = new FaultyInstance.Shared();
        try (AmaruTransactionValidator engine = engine(s, shared, config(), null)) {
            TxValidationOutcome outcome = ScenarioSupport.validate(engine, s, Rule.LEDGER, Origin.LOCAL);
            assertThat(outcome.isValid()).as(outcome.toString()).isTrue();
        }
    }

    @Test
    void freshPoolRegistrationIsSentWithoutThePoolAndPasses() {
        // 00111 registers a pool (absent from the state) whose cost is one lovelace below the minimum.
        AmaruScenario s = scenario("00111-fail-pool-registration-with-cost-one-below-minimum.json");
        FaultyInstance.Shared shared = new FaultyInstance.Shared();
        try (AmaruTransactionValidator engine = engine(s, shared, config(), null)) {
            LedgerFailure failure = onlyFailure(ScenarioSupport.validate(engine, s, Rule.LEDGER, Origin.LOCAL));
            assertThat(failure.qualifiedName()).isEqualTo("POOL.StakePoolCostTooLowPOOL");
            assertThat(slice(shared.requests.getLast(), 15)).as("pools slice").isEmpty();

            // With the minimum lowered to that cost, the same fresh registration is valid.
            ProtocolParams params = s.protocolParams();
            params.setMinPoolCost(String.valueOf(Long.parseLong(params.getMinPoolCost()) - 1));
            TxValidationOutcome outcome = ScenarioSupport.validate(engine, s, Rule.LEDGER, Origin.LOCAL);
            assertThat(outcome.isValid()).as(outcome.toString()).isTrue();
            assertThat(((TxValidationOutcome.Valid) outcome).effects().changes())
                    .anyMatch(c -> c instanceof LedgerChange.PoolRegistered);
            assertThat(slice(shared.requests.getLast(), 15)).as("pools slice").isEmpty();
        }
    }

    @Test
    void confirmedAbsentPoolIsATypedLedgerFailure() {
        AmaruScenario s = scenario(DELEGATION);
        PoolId pool = s.state().pools().getFirst();
        LedgerView view = new OverridingView(s.view()).override(pool, Lookup.absent());
        FaultyInstance.Shared shared = new FaultyInstance.Shared();
        try (AmaruTransactionValidator engine = engine(s, shared, config(), null)) {
            LedgerFailure failure = onlyFailure(ScenarioSupport.validate(engine, s, view, Rule.LEDGER, Origin.LOCAL));
            assertThat(failure.qualifiedName()).isEqualTo("DELEG.DelegateeStakePoolNotRegisteredDELEG");
        }
    }

    @Test
    void unavailableReadRejectsWithoutCallingValidate() {
        AmaruScenario s = scenario(DELEGATION);
        FaultyInstance.Shared shared = new FaultyInstance.Shared();
        try (AmaruTransactionValidator engine = engine(s, shared, config(), null)) {
            for (Object key : List.of(s.state().pools().getFirst(), s.state().utxo().getFirst().outpoint(),
                    s.state().accounts().getFirst().credential())) {
                int validateCalls = shared.validateCalls.get();
                LedgerView view = new OverridingView(s.view()).override(key, Lookup.unavailable("store not ready"));
                LedgerFailure failure = onlyFailure(ScenarioSupport.validate(engine, s, view, Rule.LEDGER,
                        Origin.LOCAL));
                assertThat(failure.qualifiedName()).isEqualTo("ENGINE.LedgerStateUnavailable");
                assertThat(failure.detail()).contains("store not ready");
                assertThat(shared.validateCalls.get()).as("validate calls for " + key).isEqualTo(validateCalls);
            }
            assertThat(shared.requiredKeysCalls.get()).isEqualTo(3);
        }
    }

    @Test
    void securityGroupComesFromTheParameterUpdateKeysAndUnknownKeysFailClosed() {
        // An SPO votes on a parameter change: allowed only when the change touches the security group.
        AmaruScenario s = scenario("00182-pass-vote-stake-pool-parameter-change-security-group.json");
        ProposalState proposal = s.view().activeProposals().require("proposals").getFirst();
        FaultyInstance.Shared shared = new FaultyInstance.Shared();
        try (AmaruTransactionValidator engine = engine(s, shared, config(), null)) {
            // govActionDeposit (key 30) is a Conway key CCL's ProtocolParamUpdate cannot even represent.
            LedgerView deposit = new OverridingView(s.view()).proposals(List.of(proposal.withParamUpdateKeys(Set.of(30))));
            assertThat(ScenarioSupport.validate(engine, s, deposit, Rule.LEDGER, Origin.LOCAL).isValid()).isTrue();

            LedgerView collateral = new OverridingView(s.view())
                    .proposals(List.of(proposal.withParamUpdateKeys(Set.of(23))));
            assertThat(onlyFailure(ScenarioSupport.validate(engine, s, collateral, Rule.LEDGER, Origin.LOCAL))
                    .qualifiedName()).isEqualTo("GOV.DisallowedVoters");

            int validateCalls = shared.validateCalls.get();
            LedgerView unknown = new OverridingView(s.view()).proposals(List.of(proposal.withParamUpdateKeys(null)));
            LedgerFailure failure = onlyFailure(ScenarioSupport.validate(engine, s, unknown, Rule.LEDGER,
                    Origin.LOCAL));
            assertThat(failure.qualifiedName()).isEqualTo("ENGINE.LedgerStateUnavailable");
            assertThat(failure.detail()).contains("protocol_param_update keys are unknown");
            assertThat(shared.validateCalls.get()).isEqualTo(validateCalls);
        }
    }

    // ------------------------------------------------------------------ traps, timeouts, health

    @Test
    void aTrapRejectsTheTransactionAndTheNextCallGetsAFreshInstance() {
        AmaruScenario s = scenario(DELEGATION);
        FaultyInstance.Shared shared = new FaultyInstance.Shared();
        try (AmaruTransactionValidator engine = engine(s, shared, config(), null)) {
            assertThat(ScenarioSupport.validate(engine, s, Rule.LEDGER, Origin.LOCAL).isValid()).isTrue();
            int created = shared.created.get();

            shared.fault.set(FaultyInstance.Fault.TRAP);
            LedgerFailure failure = onlyFailure(ScenarioSupport.validate(engine, s, Rule.LEDGER, Origin.LOCAL));
            assertThat(failure.qualifiedName()).isEqualTo("ENGINE.AmaruEngineFailure");
            assertThat(failure.detail()).contains("trapped");

            assertThat(ScenarioSupport.validate(engine, s, Rule.LEDGER, Origin.LOCAL).isValid()).isTrue();
            assertThat(shared.created.get()).as("instances created").isEqualTo(created + 1);
            assertThat(engine.isHealthy()).isTrue();
        }
    }

    @Test
    void aTimeoutRejectsInterruptsTheWorkerAndRecovers() {
        AmaruScenario s = scenario(DELEGATION);
        FaultyInstance.Shared shared = new FaultyInstance.Shared();
        AmaruEngineConfig config = config().withTimeout(Duration.ofMillis(300));
        try (AmaruTransactionValidator engine = engine(s, shared, config, null)) {
            shared.fault.set(FaultyInstance.Fault.LOOP_INTERRUPTIBLE);
            LedgerFailure failure = onlyFailure(ScenarioSupport.validate(engine, s, Rule.LEDGER, Origin.LOCAL));
            assertThat(failure.qualifiedName()).isEqualTo("ENGINE.AmaruEngineFailure");
            assertThat(failure.detail()).contains("did not answer within 300 ms");

            assertThat(engine.abandonedCount()).as("reclaimed by interrupt").isZero();
            assertThat(engine.isHealthy()).isTrue();
            assertThat(ScenarioSupport.validate(engine, s, Rule.LEDGER, Origin.LOCAL).isValid()).isTrue();
        }
    }

    @Test
    void theAbandonedCallCapTurnsTheEngineUnhealthyAndItFailsClosed() {
        AmaruScenario s = scenario(DELEGATION);
        FaultyInstance.Shared shared = new FaultyInstance.Shared();
        AmaruEngineConfig config = config().withTimeout(Duration.ofMillis(200)).withMaxAbandoned(2);
        try (AmaruTransactionValidator engine = engine(s, shared, config, null)) {
            for (int i = 1; i <= 2; i++) {
                shared.fault.set(FaultyInstance.Fault.STUCK);
                LedgerFailure failure = onlyFailure(ScenarioSupport.validate(engine, s, Rule.LEDGER, Origin.LOCAL));
                assertThat(failure.qualifiedName()).isEqualTo("ENGINE.AmaruEngineFailure");
                assertThat(engine.abandonedCount()).isEqualTo(i);
            }
            assertThat(engine.isHealthy()).isFalse();

            int calls = shared.requiredKeysCalls.get() + shared.validateCalls.get();
            LedgerFailure failure = onlyFailure(ScenarioSupport.validate(engine, s, Rule.LEDGER, Origin.LOCAL));
            assertThat(failure.qualifiedName()).isEqualTo("ENGINE.AmaruEngineUnhealthy");
            assertThat(shared.requiredKeysCalls.get() + shared.validateCalls.get())
                    .as("no module call once unhealthy").isEqualTo(calls);
        } finally {
            shared.release.countDown();
        }
    }

    @Test
    void endiveInterruptsAotCompiledCode() {
        // ADR-057 open question 3: Endive checks Thread.isInterrupted() on every call and backward branch of
        // AOT-compiled code (and in the interpreter) and throws WasmInterruptedException.
        AmaruScenario s = scenario(PLUTUS_SPEND);
        byte[] request = AmaruRequestEncoder.encode(ScenarioSupport.referenceRequest(s,
                AmaruRequest.Mode.FULL));
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread worker = Thread.ofPlatform().stackSize(64L * 1024 * 1024).start(() -> {
            try (WasmAmaruInstance instance = new WasmAmaruInstance(ScenarioSupport.MAX_MEMORY_PAGES)) {
                Thread.currentThread().interrupt();
                instance.validate(request);
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        assertThat(join(worker)).isTrue();
        assertThat(thrown.get()).isInstanceOf(WasmInterruptedException.class);
    }

    private static boolean join(Thread thread) {
        try {
            return thread.join(Duration.ofSeconds(30));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Test
    void anUnsupportedAbiVersionFailsConstruction() {
        AmaruScenario s = scenario(DELEGATION);
        AmaruInstance wrongAbi = new AmaruInstance() {
            @Override
            public int abiVersion() {
                return 2;
            }

            @Override
            public String amaruVersion() {
                return "test";
            }

            @Override
            public byte[] requiredKeys(byte[] transaction, byte[] env) {
                throw new UnsupportedOperationException();
            }

            @Override
            public byte[] validate(byte[] request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void close() {
            }
        };
        assertThatThrownBy(() -> new AmaruTransactionValidator(LedgerValidationEngines.AMARU, config(),
                ScenarioSupport.network(s), null, AmaruLedgerConstants.HASKELL, () -> wrongAbi))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("interface version 2");
    }

    // --------------------------------------------------------------------------------- MEMPOOL

    @Test
    void mempoolRejectsAnAllSpentDuplicateBeforeCallingTheModule() {
        AmaruScenario s = scenario(DELEGATION);
        Outpoint input = s.state().utxo().getFirst().outpoint();
        LedgerView spent = new OverridingView(s.view()).override(input, Lookup.absent());
        FaultyInstance.Shared shared = new FaultyInstance.Shared();
        try (AmaruTransactionValidator engine = engine(s, shared, config(), null)) {
            LedgerFailure mempool = onlyFailure(ScenarioSupport.validate(engine, s, spent, Rule.MEMPOOL, Origin.PEER));
            assertThat(mempool.qualifiedName()).isEqualTo("LEDGER.ConwayMempoolFailure");
            assertThat(mempool.detail()).isEqualTo(
                    "All inputs are spent. Transaction has probably already been included");
            assertThat(shared.requiredKeysCalls.get()).isZero();

            // Rule LEDGER (block selection, shadow sync) never reports a MEMPOOL failure.
            LedgerFailure ledger = onlyFailure(ScenarioSupport.validate(engine, s, spent, Rule.LEDGER, Origin.SYNC));
            assertThat(ledger.qualifiedName()).isEqualTo("UTXO.BadInputsUTxO");
        }
    }

    @Test
    void mempoolJudgesUnelectedCommitteeVotersOnTheIncomingStateUpToPv10() {
        AmaruScenario unelected = scenario(
                "00154-pass-vote-cast-by-an-unelected-committee-member-with-an-authorized-hot-key-v10.json");
        AmaruScenario authorizedInTx = scenario(
                "00167-pass-vote-cast-by-a-committee-hot-key-authorized-earlier-in-the-same-transaction.json");
        AmaruScenario pv11 = scenario(
                "00171-fail-vote-cast-by-an-unelected-committee-member-with-an-authorized-hot-key-v11.json");
        FaultyInstance.Shared shared = new FaultyInstance.Shared();
        try (AmaruTransactionValidator engine = engine(unelected, shared, config(), null)) {
            LedgerFailure failure = onlyFailure(ScenarioSupport.validate(engine, unelected, Rule.MEMPOOL,
                    Origin.LOCAL));
            assertThat(failure.qualifiedName()).isEqualTo("LEDGER.ConwayMempoolFailure");
            assertThat(failure.detail()).startsWith("Unelected committee members are not allowed to cast votes: "
                    + "[KeyHashObj (KeyHash {unKeyHash = \"");
            assertThat(ScenarioSupport.validate(engine, unelected, Rule.LEDGER, Origin.LOCAL).isValid()).isTrue();

            // The hot key is authorised by this transaction's own certificate: LEDGER accepts it, MEMPOOL
            // judges the incoming committee state and rejects it.
            assertThat(ScenarioSupport.validate(engine, authorizedInTx, Rule.LEDGER, Origin.LOCAL).isValid())
                    .isTrue();
            assertThat(onlyFailure(ScenarioSupport.validate(engine, authorizedInTx, Rule.MEMPOOL, Origin.LOCAL))
                    .qualifiedName()).isEqualTo("LEDGER.ConwayMempoolFailure");

            // From PV11 the check lives in GOV, so MEMPOOL adds nothing.
            assertThat(onlyFailure(ScenarioSupport.validate(engine, pv11, Rule.MEMPOOL, Origin.LOCAL))
                    .qualifiedName()).isEqualTo("GOV.VotersDoNotExist");
        }
    }

    // --------------------------------------------------------------------- phase 2 and origins

    @Test
    void amaruScalusRunsAmaruPhaseOneThenTheEvaluator() {
        AmaruScenario plutus = scenario(PLUTUS_SPEND);
        AmaruScenario plain = scenario(DELEGATION);
        AtomicReference<ScriptPhaseResult> next = new AtomicReference<>(new ScriptPhaseResult.Passed(List.of()));
        AtomicReference<Map<Outpoint, UtxoEntry>> seenInputs = new AtomicReference<>();
        AtomicReference<Integer> calls = new AtomicReference<>(0);
        ScriptPhaseEvaluator evaluator = (byte[] txCbor, Transaction tx, Map<Outpoint, UtxoEntry> inputs,
                                          ProtocolParams params, SlotConfig slotConfig) -> {
            calls.set(calls.get() + 1);
            seenInputs.set(inputs);
            return next.get();
        };
        FaultyInstance.Shared shared = new FaultyInstance.Shared();
        try (AmaruTransactionValidator engine = engine(plutus, shared, config(), evaluator)) {
            assertThat(ScenarioSupport.validate(engine, plain, Rule.LEDGER, Origin.LOCAL).isValid()).isTrue();
            assertThat(calls.get()).as("no redeemers, no evaluator call").isZero();
            // phase_one mode was sent.
            assertThat(((Map<?, ?>) CborReader.decode(shared.requests.getLast())).get(1L)).isEqualTo(0L);

            assertThat(ScenarioSupport.validate(engine, plutus, Rule.LEDGER, Origin.LOCAL).isValid()).isTrue();
            assertThat(seenInputs.get()).containsKeys(plutus.state().utxo().stream()
                    .map(AmaruScenario.RawUtxo::outpoint).toArray(Outpoint[]::new));

            next.set(new ScriptPhaseResult.Failed(List.of()));
            LedgerFailure mismatch = onlyFailure(ScenarioSupport.validate(engine, plutus, Rule.LEDGER, Origin.LOCAL));
            assertThat(mismatch.qualifiedName()).isEqualTo("UTXOS.ValidationTagMismatch");
            assertThat(mismatch.phase()).isEqualTo(LedgerFailure.Phase.PHASE_2);
            assertThat(mismatch.detail()).startsWith("FailedUnexpectedly: ");

            LedgerFailure malformed = new LedgerFailure(LedgerRuleName.UTXOW, "MalformedScriptWitnesses",
                    LedgerFailure.Phase.PHASE_1, "");
            next.set(new ScriptPhaseResult.Rejected(List.of(malformed)));
            assertThat(onlyFailure(ScenarioSupport.validate(engine, plutus, Rule.LEDGER, Origin.LOCAL)))
                    .isEqualTo(malformed);
        }
    }

    @Test
    void amaruScalusSendsMalformedPlutusWitnessesToTheEvaluator() {
        // Amaru's phase-one mode does not decode Plutus witness scripts (INTERFACE.md, "Modes").
        AmaruScenario s = scenario("00256-fail-plutus-witness-script-that-cannot-be-flat-decoded.json");
        LedgerFailure malformed = new LedgerFailure(LedgerRuleName.UTXOW, "MalformedScriptWitnesses",
                LedgerFailure.Phase.PHASE_1, "");
        AtomicReference<Integer> calls = new AtomicReference<>(0);
        ScriptPhaseEvaluator evaluator = (txCbor, tx, inputs, params, slotConfig) -> {
            calls.set(calls.get() + 1);
            return new ScriptPhaseResult.Rejected(List.of(malformed));
        };
        try (AmaruTransactionValidator engine = engine(s, new FaultyInstance.Shared(), config(), evaluator)) {
            assertThat(onlyFailure(ScenarioSupport.validate(engine, s, Rule.LEDGER, Origin.LOCAL)))
                    .isEqualTo(malformed);
            assertThat(calls.get()).isEqualTo(1);
        }
    }

    @Test
    void theEvaluatorIsNeededForRedeemersPlutusWitnessesOrReferenceScripts() throws Exception {
        Transaction plain = new Transaction();
        plain.setWitnessSet(new TransactionWitnessSet());
        assertThat(AmaruTransactionValidator.needsScriptPhase(plain, Map.of())).isFalse();

        // A Plutus witness script without any redeemer.
        Transaction witnessOnly = new Transaction();
        TransactionWitnessSet witnesses = new TransactionWitnessSet();
        witnesses.setPlutusV3Scripts(List.of(PlutusV3Script.builder().cborHex("4401000000").build()));
        witnessOnly.setWitnessSet(witnesses);
        assertThat(AmaruTransactionValidator.needsScriptPhase(witnessOnly, Map.of())).isTrue();

        // A resolved (reference or spending) input carrying a reference script.
        AmaruScenario refScript = scenario("00034-pass-reference-scripts-size-exactly-at-per-tx-limit.json");
        Map<Outpoint, UtxoEntry> inputs = new HashMap<>();
        for (AmaruScenario.RawUtxo utxo : refScript.state().utxo()) {
            inputs.put(utxo.outpoint(), refScript.view().utxo(utxo.outpoint()).require("utxo"));
        }
        assertThat(inputs.values()).anyMatch(e -> e.output().getScriptRef() != null);
        assertThat(AmaruTransactionValidator.needsScriptPhase(plain, inputs)).isTrue();
    }

    @Test
    void isValidFalseIsAdmittedOnlyFromShadowSync() {
        AmaruScenario s = scenario("00017-pass-invalid-transaction-collects-fee-from-collateral.json");
        FaultyInstance.Shared shared = new FaultyInstance.Shared();
        try (AmaruTransactionValidator engine = engine(s, shared, config(), null)) {
            assertThat(onlyFailure(ScenarioSupport.validate(engine, s, Rule.MEMPOOL, Origin.LOCAL)).qualifiedName())
                    .isEqualTo("ENGINE.Phase2InvalidTxNotSupported");
            TxValidationOutcome sync = ScenarioSupport.validate(engine, s, Rule.LEDGER, Origin.SYNC);
            assertThat(sync.isValid()).isTrue();
            TxValidationOutcome.Valid valid = (TxValidationOutcome.Valid) sync;
            assertThat(valid.effects().phase2Valid()).isFalse();
            assertThat(valid.validated().phase2Valid()).isFalse();
        }
    }

    @Test
    void validOutcomeCarriesEffectsAndProvenance() {
        AmaruScenario s = scenario(DELEGATION);
        FaultyInstance.Shared shared = new FaultyInstance.Shared();
        try (AmaruTransactionValidator engine = engine(s, shared, config(), null)) {
            TxValidationOutcome outcome = ScenarioSupport.validate(engine, s, Rule.MEMPOOL, Origin.LOCAL);
            assertThat(outcome.isValid()).isTrue();
            TxValidationOutcome.Valid valid = (TxValidationOutcome.Valid) outcome;
            assertThat(valid.reapplied()).isFalse();
            assertThat(valid.validated().txIdHex()).isEqualTo(TxIdentity.txIdHex(s.txCbor()));
            assertThat(valid.validated().origin()).isEqualTo(Origin.LOCAL);
            CredentialKey account = s.state().accounts().getFirst().credential();
            assertThat(valid.effects().changes()).contains(
                    new LedgerChange.StakeDelegated(account, s.state().pools().getFirst()));
            assertThat(valid.effects().consumed()).containsExactly(s.state().utxo().getFirst().outpoint());
            assertThat(engine.amaruVersion()).contains("tag=v10.11.20260925");
            assertThat(engine.name()).isEqualTo(LedgerValidationEngines.AMARU);
        }
    }
}
