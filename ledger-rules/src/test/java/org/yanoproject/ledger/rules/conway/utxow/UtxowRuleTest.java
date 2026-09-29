package org.yanoproject.ledger.rules.conway.utxow;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.spec.Script;
import com.bloxbean.cardano.client.transaction.spec.ProtocolParamUpdate;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeCredential;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeDeregistration;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeRegistration;
import com.bloxbean.cardano.client.transaction.spec.governance.Anchor;
import com.bloxbean.cardano.client.transaction.spec.governance.ProposalProcedure;
import com.bloxbean.cardano.client.transaction.spec.governance.Vote;
import com.bloxbean.cardano.client.transaction.spec.governance.Voter;
import com.bloxbean.cardano.client.transaction.spec.governance.VoterType;
import com.bloxbean.cardano.client.transaction.spec.governance.VotingProcedure;
import com.bloxbean.cardano.client.transaction.spec.governance.VotingProcedures;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionId;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.ParameterChangeAction;
import com.bloxbean.cardano.client.util.HexUtil;

import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.ValidatedTx;
import org.yanoproject.ledger.rules.conway.EngineTestSupport;
import org.yanoproject.ledger.rules.conway.EngineTestSupport.StubEvaluator;
import org.yanoproject.ledger.rules.conway.JavaLedgerValidationEngine;
import org.yanoproject.ledger.rules.fixtures.conformance.Covers;
import org.yanoproject.ledger.rules.fixtures.tx.ConwayTxBuilder;
import org.yanoproject.ledger.rules.fixtures.tx.MutationWorld;
import org.yanoproject.ledger.rules.fixtures.tx.TestKey;
import org.yanoproject.ledger.rules.fixtures.tx.TxSpec;
import org.yanoproject.ledger.rules.view.InMemoryLedgerView;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.model.AccountState;
import org.yanoproject.ledger.rules.view.model.CredentialKey;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.yanoproject.ledger.rules.conway.EngineTestSupport.run;

/**
 * The {@code UTXOW} rule ({@code babbageUtxowTransition}, Babbage/Rules/Utxow.hs:328-391): one test per
 * constructor, each asserting Haskell's whole failure list, plus the witness-needs per purpose, the protocol-version
 * gate of the integrity check, the REAPPLY labels and the order of several failures.
 */
class UtxowRuleTest {

    private static final String DEV_AA = TestKey.DEV_AA.keyHash();

    // ------------------------------------------------------------------ native scripts

    @Test
    @Covers("UTXOW.ScriptWitnessNotValidatingUTXOW")
    void aNeededNativeScriptThatDoesNotHoldFails() {
        TxSpec spec = MutationWorld.simpleSpec();
        spec.inputs.add(MutationWorld.TIMELOCK_INPUT);
        spec.nativeScripts.add(MutationWorld.TIMELOCK_SCRIPT);
        assertThat(run(spec)).containsExactly("UTXOW.ScriptWitnessNotValidatingUTXOW");

        TxSpec signed = MutationWorld.simpleSpec();
        signed.inputs.add(MutationWorld.NATIVE_INPUT);
        signed.nativeScripts.add(MutationWorld.NATIVE_SCRIPT);
        assertThat(run(signed)).as("dev-42 signs, so its signature script holds").containsExactly("Valid");

        TxSpec unsigned = signed.copy();
        unsigned.signers.add(0, TestKey.DEV_AA);
        unsigned.signers.remove(TestKey.DEV_42);
        assertThat(run(unsigned)).as("only vkey witnesses count, and the key input still needs dev-42")
                .containsExactly("UTXOW.ScriptWitnessNotValidatingUTXOW", "UTXOW.MissingVKeyWitnessesUTXOW");
    }

    // ------------------------------------------------------------------ script witnesses

    @Test
    @Covers("UTXOW.ExtraneousScriptWitnessesUTXOW")
    void aWitnessScriptNothingNeedsIsExtraneous() {
        TxSpec spec = MutationWorld.simpleSpec();
        spec.nativeScripts.add(MutationWorld.NATIVE_SCRIPT);
        assertThat(run(spec)).containsExactly("UTXOW.ExtraneousScriptWitnessesUTXOW");
    }

    @Test
    @Covers("UTXOW.MissingScriptWitnessesUTXOW")
    void aNeededScriptThatIsNotProvidedIsMissing() {
        TxSpec spec = MutationWorld.simpleSpec();
        spec.inputs.add(MutationWorld.NATIVE_INPUT);
        assertThat(run(spec)).containsExactly("UTXOW.MissingScriptWitnessesUTXOW");
    }

    /** A reference input's script satisfies the need; the same script as a witness too is extraneous. */
    @Test
    @Covers("UTXOW.ExtraneousScriptWitnessesUTXOW")
    void referenceScriptsProvideNeededScripts() {
        TransactionInput carrier = new TransactionInput("c".repeat(64), 0);
        TransactionOutput output = MutationWorld.output(TestKey.DEV_42.enterpriseAddress(MutationWorld.NETWORK),
                BigInteger.valueOf(5_000_000));
        output.setScriptRef(scriptRef(MutationWorld.NATIVE_SCRIPT));
        InMemoryLedgerView view = MutationWorld.builder(MutationWorld.protocolParams())
                .utxo(carrier.getTransactionId(), carrier.getIndex(), output).build();
        int refScriptBytes = scriptRef(MutationWorld.NATIVE_SCRIPT).length - 2;

        TxSpec spec = MutationWorld.simpleSpec();
        spec.inputs.add(MutationWorld.NATIVE_INPUT);
        spec.referenceInputs.add(carrier);
        spec.feeAdjust = BigInteger.valueOf(15L * refScriptBytes);
        assertThat(names(spec, view)).containsExactly("Valid");

        TxSpec both = spec.copy();
        both.nativeScripts.add(MutationWorld.NATIVE_SCRIPT);
        assertThat(names(both, view)).containsExactly("UTXOW.ExtraneousScriptWitnessesUTXOW");
    }

    // ------------------------------------------------------------------ datums

    @Test
    @Covers("UTXOW.UnspendableUTxONoDatumHash")
    void aPlutusV2InputWithoutADatumIsUnspendable() {
        TxSpec spec = MutationWorld.scriptSpec();
        spec.inputs.set(1, MutationWorld.V2_SCRIPT_INPUT);
        spec.plutusScripts.clear();
        spec.plutusV2Scripts.add(MutationWorld.ALWAYS_SUCCEEDS_V2);
        assertThat(run(spec)).containsExactly("UTXOW.UnspendableUTxONoDatumHash");

        assertThat(run(MutationWorld.scriptSpec())).as("PlutusV3 needs no datum (CIP-69)").containsExactly("Valid");
    }

    @Test
    @Covers("UTXOW.MissingRequiredDatums")
    void aDatumHashInputNeedsItsDatum() {
        TxSpec spec = MutationWorld.scriptSpec();
        spec.inputs.set(1, MutationWorld.DATUM_SCRIPT_INPUT);
        assertThat(run(spec)).containsExactly("UTXOW.MissingRequiredDatums");

        spec.datums.add(MutationWorld.DATUM);
        assertThat(run(spec)).containsExactly("Valid");
    }

    @Test
    @Covers("UTXOW.NotAllowedSupplementalDatums")
    void supplementalDatumsMustBeNamedByAnOutputOrAReferenceInput() {
        TxSpec spec = MutationWorld.simpleSpec();
        spec.datums.add(MutationWorld.DATUM);
        assertThat(run(spec)).containsExactly("UTXOW.NotAllowedSupplementalDatums");

        TxSpec named = spec.copy();
        TransactionOutput output = MutationWorld.output(TestKey.DEV_AA.enterpriseAddress(MutationWorld.NETWORK),
                MutationWorld.PAYMENT);
        output.setDatumHash(MutationWorld.datumHash(MutationWorld.DATUM));
        named.outputs.set(0, output);
        assertThat(run(named)).as("an output's datum hash allows it").containsExactly("Valid");

        TxSpec referenced = spec.copy();
        referenced.referenceInputs.add(MutationWorld.DATUM_SCRIPT_INPUT);
        assertThat(run(referenced)).as("a reference input's datum hash allows it").containsExactly("Valid");
    }

    // ------------------------------------------------------------------ redeemers

    @Test
    @Covers("UTXOW.ExtraRedeemers")
    void aRedeemerForNoNeededPlutusScriptIsExtra() {
        TxSpec spec = MutationWorld.scriptSpec();
        spec.redeemers.add(Redeemer.builder().tag(RedeemerTag.Mint).index(BigInteger.ZERO).data(ConstrPlutusData.of(0))
                .exUnits(ExUnits.builder().mem(BigInteger.ONE).steps(BigInteger.ONE).build()).build());
        assertThat(run(spec)).containsExactly("UTXOW.ExtraRedeemers");
    }

    @Test
    @Covers("UTXOW.MissingRedeemers")
    void aNeededPlutusScriptWithoutARedeemerIsMissingOneAndCannotBeCollected() {
        TxSpec spec = MutationWorld.scriptSpec();
        spec.redeemers.clear();
        StubEvaluator evaluator = new StubEvaluator();
        evaluator.collect = List.of(new LedgerFailure(LedgerRuleName.UTXOS, "CollectErrors",
                LedgerFailure.Phase.PHASE_1, "NoRedeemer spend[1]"));
        TxValidationOutcome outcome = EngineTestSupport.validate(evaluator, EngineTestSupport.build(spec).cbor());
        assertThat(EngineTestSupport.names(outcome)).containsExactly("UTXOW.MissingRedeemers", "UTXOS.CollectErrors");
        assertThat(((TxValidationOutcome.Invalid) outcome).failures().getFirst().detail())
                .contains("ConwaySpending (AsItem " + "2".repeat(64) + "#0)");
    }

    // ------------------------------------------------------------------ vkey and bootstrap witnesses

    @Test
    @Covers("UTXOW.InvalidWitnessesUTXOW")
    void aSignatureThatDoesNotVerifyIsInvalid() {
        TxSpec spec = MutationWorld.simpleSpec();
        spec.corruptFirstSignature = true;
        assertThat(run(spec)).containsExactly("UTXOW.InvalidWitnessesUTXOW");
    }

    @Test
    @Covers("UTXOW.MissingVKeyWitnessesUTXOW")
    void theInputOwnerMustSign() {
        TxSpec spec = MutationWorld.simpleSpec();
        spec.signers = List.of(TestKey.DEV_AA);
        assertThat(run(spec)).containsExactly("UTXOW.MissingVKeyWitnessesUTXOW");
    }

    /** A Byron input is witnessed by a bootstrap witness whose key hash is the address root. */
    @Test
    @Covers("UTXOW.InvalidWitnessesUTXOW")
    @Covers("UTXOW.MissingVKeyWitnessesUTXOW")
    void bootstrapWitnessesAuthoriseByronInputs() {
        TxSpec spec = MutationWorld.simpleSpec();
        spec.inputs.add(MutationWorld.BYRON_INPUT);
        assertThat(run(spec)).containsExactly("UTXOW.MissingVKeyWitnessesUTXOW");

        spec.bootstrapSigners.add(TestKey.DEV_42);
        assertThat(run(spec)).containsExactly("Valid");

        TxSpec wrongKey = MutationWorld.simpleSpec();
        wrongKey.inputs.add(MutationWorld.BYRON_INPUT);
        wrongKey.bootstrapSigners.add(TestKey.DEV_AA);
        assertThat(run(wrongKey)).as("dev-aa's bootstrap key hash is not the address root")
                .containsExactly("UTXOW.MissingVKeyWitnessesUTXOW");
    }

    /**
     * Bootstrap witnesses are a {@code Set} ordered by key hash only, and {@code Set.fromList} keeps the last of
     * equal ones: of two witnesses for the same key, only the second is verified.
     */
    @Test
    @Covers("UTXOW.InvalidWitnessesUTXOW")
    void ofDuplicateBootstrapWitnessesTheLastIsKept() {
        TxSpec spec = MutationWorld.simpleSpec();
        spec.inputs.add(MutationWorld.BYRON_INPUT);
        spec.bootstrapSigners.add(TestKey.DEV_42);
        spec.bootstrapSigners.add(TestKey.DEV_42);
        spec.corruptBootstrapSignature = 0;
        assertThat(run(spec)).as("the corrupt first one is replaced").containsExactly("Valid");
        spec.corruptBootstrapSignature = 1;
        assertThat(run(spec)).containsExactly("UTXOW.InvalidWitnessesUTXOW");
    }

    /** The witness needs per purpose (getConwayWitsVKeyNeeded): certificates, required signers, voters. */
    @Test
    @Covers("UTXOW.MissingVKeyWitnessesUTXOW")
    void keyWitnessesNeededPerPurpose() {
        TxSpec required = MutationWorld.simpleSpec();
        required.requiredSigners.add(DEV_AA);
        assertThat(run(required)).containsExactly("UTXOW.MissingVKeyWitnessesUTXOW");
        required.signers.add(TestKey.DEV_AA);
        assertThat(run(required)).containsExactly("Valid");

        // A registration without a deposit (tag 0) needs no witness in Conway; a deregistration does.
        TxSpec registration = MutationWorld.simpleSpec();
        registration.certs.add(new StakeRegistration(StakeCredential.fromKeyHash(HexUtil.decodeHexString(DEV_AA))));
        registration.changeAdjust = BigInteger.valueOf(-2_000_000);
        assertThat(run(registration)).containsExactly("Valid");

        TxSpec voting = MutationWorld.simpleSpec();
        voting.votingProcedures = votes(VoterType.DREP_KEY_HASH, DEV_AA);
        assertThat(run(voting)).containsExactly("UTXOW.MissingVKeyWitnessesUTXOW");
        voting.signers.add(TestKey.DEV_AA);
        assertThat(run(voting)).containsExactly("Valid");
    }

    @Test
    void scriptsNeededPerPurpose() throws Exception {
        // A deregistration of a script credential needs its script (getScriptWitnessConwayTxCert).
        String scriptHash = HexUtil.encodeHexString(MutationWorld.NATIVE_SCRIPT.getScriptHash());
        InMemoryLedgerView view = MutationWorld.builder(MutationWorld.protocolParams())
                .account(AccountState.registered(CredentialKey.script(scriptHash), BigInteger.valueOf(2_000_000)))
                .build();
        TxSpec deregistration = MutationWorld.simpleSpec();
        deregistration.certs.add(new StakeDeregistration(StakeCredential.fromScriptHash(
                HexUtil.decodeHexString(scriptHash))));
        deregistration.changeAdjust = BigInteger.valueOf(2_000_000);
        assertThat(names(deregistration, view)).containsExactly("UTXOW.MissingScriptWitnessesUTXOW");
        deregistration.nativeScripts.add(MutationWorld.NATIVE_SCRIPT);
        assertThat(names(deregistration, view)).containsExactly("Valid");

        // A DRep script voter needs its script.
        TxSpec voting = MutationWorld.simpleSpec();
        voting.votingProcedures = votes(VoterType.DREP_SCRIPT_HASH, scriptHash);
        assertThat(run(voting)).containsExactly("UTXOW.MissingScriptWitnessesUTXOW");

        // A parameter change names the guardrails script, which must be provided (proposingScriptsNeeded).
        ProtocolParams params = MutationWorld.protocolParams();
        params.setGovActionDeposit(BigInteger.valueOf(1_000_000));
        InMemoryLedgerView cheap = MutationWorld.builder(params).build();
        TxSpec proposal = MutationWorld.simpleSpec();
        proposal.proposals.add(ProposalProcedure.builder()
                .deposit(BigInteger.valueOf(1_000_000))
                .rewardAccount(AddressProvider.getRewardAddress(Credential.fromKey(TestKey.DEV_42.keyHash()),
                        MutationWorld.NETWORK).toBech32())
                .govAction(ParameterChangeAction.builder()
                        .protocolParamUpdate(ProtocolParamUpdate.builder().minFeeA(BigInteger.valueOf(45)).build())
                        .policyHash(HexUtil.decodeHexString(scriptHash))
                        .build())
                .anchor(new Anchor("https://example.com", new byte[32]))
                .build());
        proposal.changeAdjust = BigInteger.valueOf(-1_000_000);
        assertThat(names(proposal, cheap)).containsExactly("UTXOW.MissingScriptWitnessesUTXOW");
    }

    // ------------------------------------------------------------------ metadata

    @Test
    @Covers("UTXOW.MissingTxMetadata")
    @Covers("UTXOW.MissingTxBodyMetadataHash")
    @Covers("UTXOW.ConflictingMetadataHash")
    void theAuxiliaryDataHashMustMatchTheAuxiliaryData() {
        TxSpec dropped = metadataSpec();
        dropped.dropAuxData = true;
        assertThat(run(dropped)).containsExactly("UTXOW.MissingTxMetadata");

        TxSpec omitted = metadataSpec();
        omitted.auxDataHash = TxSpec.AuxDataHash.OMITTED;
        assertThat(run(omitted)).containsExactly("UTXOW.MissingTxBodyMetadataHash");

        TxSpec wrong = metadataSpec();
        wrong.auxDataHash = TxSpec.AuxDataHash.WRONG;
        assertThat(run(wrong)).containsExactly("UTXOW.ConflictingMetadataHash");

        assertThat(run(metadataSpec())).containsExactly("Valid");
    }

    /** {@code validateAlonzoTxAuxData}: the Plutus scripts in the auxiliary data must be well formed. */
    @Test
    @Covers("UTXOW.InvalidMetadata")
    void malformedPlutusScriptsInTheAuxiliaryDataAreInvalidMetadata() {
        TxSpec spec = MutationWorld.simpleSpec();
        spec.auxPlutusScripts.add(MutationWorld.MALFORMED_SCRIPT);
        StubEvaluator evaluator = malformedJudge();
        assertThat(names(evaluator, spec)).containsExactly("UTXOW.InvalidMetadata");
        assertThat(names(new StubEvaluator(), spec)).as("the Java decoder alone rejects it too")
                .containsExactly("UTXOW.InvalidMetadata");
        TxSpec wellFormed = MutationWorld.simpleSpec();
        wellFormed.auxPlutusScripts.add(MutationWorld.ALWAYS_SUCCEEDS);
        assertThat(names(new StubEvaluator(), wellFormed)).containsExactly("Valid");

        // (no metadata here: CCL writes metadata with only PlutusV3 scripts in the Shelley form, dropping them)
        TxSpec conflicting = spec.copy();
        conflicting.auxDataHash = TxSpec.AuxDataHash.WRONG;
        assertThat(names(evaluator, conflicting)).as("sequenceA_ keeps both, conflict first")
                .containsExactly("UTXOW.ConflictingMetadataHash", "UTXOW.InvalidMetadata");
    }

    // ------------------------------------------------------------------ well-formedness

    @Test
    @Covers("UTXOW.MalformedScriptWitnesses")
    void malformedPlutusWitnessScripts() {
        TxSpec spec = MutationWorld.scriptSpec();
        spec.inputs.set(1, MutationWorld.MALFORMED_SCRIPT_INPUT);
        spec.plutusScripts.set(0, MutationWorld.MALFORMED_SCRIPT);
        StubEvaluator evaluator = malformedJudge();
        assertThat(names(evaluator, spec)).containsExactly("UTXOW.MalformedScriptWitnesses");
        assertThat(evaluator.evaluations).as("a failing transaction runs no script").isZero();
    }

    @Test
    @Covers("UTXOW.MalformedReferenceScripts")
    void malformedReferenceScriptsOfOwnOutputs() {
        TxSpec spec = MutationWorld.simpleSpec();
        TransactionOutput output = MutationWorld.output(TestKey.DEV_AA.enterpriseAddress(MutationWorld.NETWORK),
                MutationWorld.PAYMENT);
        output.setScriptRef(scriptRef(MutationWorld.MALFORMED_SCRIPT));
        spec.outputs.set(0, output);
        assertThat(names(malformedJudge(), spec)).containsExactly("UTXOW.MalformedReferenceScripts");
        assertThat(EngineTestSupport.names(EngineTestSupport.validate(null, EngineTestSupport.build(spec).cbor())))
                .as("the Java decoder judges it without an evaluator")
                .containsExactly("UTXOW.MalformedReferenceScripts");
    }

    // ------------------------------------------------------------------ script integrity

    @Test
    @Covers("UTXOW.PPViewHashesDontMatch")
    @Covers("UTXOW.ScriptIntegrityHashMismatch")
    void theScriptIntegrityHashConstructorDependsOnTheProtocolVersion() {
        TxSpec spec = MutationWorld.scriptSpec();
        spec.corruptScriptDataHash = true;
        byte[] cbor = EngineTestSupport.build(spec).cbor();
        assertThat(EngineTestSupport.names(EngineTestSupport.validate(new StubEvaluator(), cbor)))
                .containsExactly("UTXOW.PPViewHashesDontMatch");

        LedgerView pv11 = MutationWorld.builder(EngineTestSupport.params(11)).build();
        TxValidationOutcome outcome = EngineTestSupport.validate(new StubEvaluator(), cbor, pv11,
                EngineTestSupport.env(11), null);
        assertThat(EngineTestSupport.names(outcome)).containsExactly("UTXOW.ScriptIntegrityHashMismatch");
        assertThat(((TxValidationOutcome.Invalid) outcome).failures().getFirst().detail())
                .as("PV11 carries the expected preimage").contains("SJust a1");
    }

    /** A view without raw cost models cannot compute the integrity hash of a Plutus transaction: fail closed. */
    @Test
    void aViewWithoutRawCostModelsFailsClosed() {
        ProtocolParams params = MutationWorld.protocolParams();
        LinkedHashMap<String, LinkedHashMap<String, Long>> named = new LinkedHashMap<>();
        LinkedHashMap<String, Long> v3 = new LinkedHashMap<>();
        v3.put("000", 1L);
        named.put("PlutusV3", v3);
        params.setCostModels(named);
        params.setCostModelsRaw(null);
        LedgerView view = MutationWorld.builder(params).build();
        byte[] cbor = EngineTestSupport.build(MutationWorld.scriptSpec()).cbor();
        assertThat(EngineTestSupport.names(EngineTestSupport.validate(new StubEvaluator(), cbor, view,
                MutationWorld.env(), null))).containsExactly("ENGINE.LedgerStateUnavailable");
    }

    /** Datums alone make the integrity hash required (redeemers {@code a0}, no language view). */
    @Test
    @Covers("UTXOW.PPViewHashesDontMatch")
    void datumsAloneNeedAnIntegrityHash() {
        TxSpec spec = MutationWorld.simpleSpec();
        spec.datums.add(MutationWorld.DATUM);
        TransactionOutput output = MutationWorld.output(TestKey.DEV_AA.enterpriseAddress(MutationWorld.NETWORK),
                MutationWorld.PAYMENT);
        output.setDatumHash(MutationWorld.datumHash(MutationWorld.DATUM));
        spec.outputs.set(0, output);
        assertThat(run(spec)).containsExactly("Valid");
        spec.corruptScriptDataHash = true;
        assertThat(run(spec)).containsExactly("UTXOW.PPViewHashesDontMatch");
    }

    // ------------------------------------------------------------------ order and labels

    /** Several faults: UTXOW's checks in execution order, then UTXO's (rooted at LEDGER, RuleFrame). */
    @Test
    void failuresAccumulateInHaskellsOrder() {
        TxSpec spec = MutationWorld.simpleSpec();
        spec.corruptFirstSignature = true;
        spec.metadata = MutationWorld.smallMetadata();
        spec.auxDataHash = TxSpec.AuxDataHash.WRONG;
        spec.nativeScripts.add(MutationWorld.NATIVE_SCRIPT);
        spec.changeAdjust = BigInteger.ONE;
        assertThat(run(spec)).containsExactly("UTXOW.ExtraneousScriptWitnessesUTXOW",
                "UTXOW.InvalidWitnessesUTXOW", "UTXOW.ConflictingMetadataHash", "UTXO.ValueNotConservedUTxO");
    }

    /** Re-application skips the static checks (signatures, metadata) and keeps the dynamic ones. */
    @Test
    void staticWitnessChecksAreSkippedOnReApplication() {
        TxSpec good = MutationWorld.simpleSpec();
        ValidatedTx previous = ((TxValidationOutcome.Valid) EngineTestSupport.validate(new StubEvaluator(),
                EngineTestSupport.build(good).cbor(), MutationWorld.view(), MutationWorld.env(), null)).validated();

        TxSpec corrupt = good.copy();
        corrupt.corruptFirstSignature = true;
        byte[] cbor = EngineTestSupport.build(corrupt).cbor();
        TxValidationOutcome reapplied = new JavaLedgerValidationEngine(new StubEvaluator()).validate(
                new TxValidationRequest(cbor, MutationWorld.view(), MutationWorld.env(),
                        TxValidationRequest.Rule.LEDGER, TxValidationRequest.Origin.SYNC, previous));
        assertThat(EngineTestSupport.names(reapplied)).as("same body, so same transaction id: re-applied")
                .containsExactly("Valid");
        assertThat(((TxValidationOutcome.Valid) reapplied).reapplied()).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private static TxSpec metadataSpec() {
        TxSpec spec = MutationWorld.simpleSpec();
        spec.metadata = MutationWorld.smallMetadata();
        return spec;
    }

    private static StubEvaluator malformedJudge() {
        StubEvaluator evaluator = new StubEvaluator();
        evaluator.malformed.add("01020304");
        return evaluator;
    }

    private static List<String> names(StubEvaluator evaluator, TxSpec spec) {
        return EngineTestSupport.names(EngineTestSupport.validate(evaluator, EngineTestSupport.build(spec).cbor()));
    }

    private static List<String> names(TxSpec spec, InMemoryLedgerView view) {
        return EngineTestSupport.names(EngineTestSupport.validate(new StubEvaluator(),
                ConwayTxBuilder.build(spec, view).cbor(), view, MutationWorld.env(), null));
    }

    private static byte[] scriptRef(Script script) {
        try {
            return script.scriptRefBytes();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static VotingProcedures votes(VoterType type, String hash) {
        Credential credential = type == VoterType.DREP_SCRIPT_HASH ? Credential.fromScript(hash)
                : Credential.fromKey(hash);
        VotingProcedures procedures = new VotingProcedures();
        procedures.add(new Voter(type, credential), new GovActionId("0".repeat(64), 0),
                new VotingProcedure(Vote.YES, null));
        return procedures;
    }
}
