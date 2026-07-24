package com.bloxbean.cardano.yano.appchain.eutxo.zk.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.ledger.Address;
import com.bloxbean.cardano.julc.ledger.Credential;
import com.bloxbean.cardano.julc.ledger.OutputDatum;
import com.bloxbean.cardano.julc.ledger.PolicyId;
import com.bloxbean.cardano.julc.ledger.ScriptHash;
import com.bloxbean.cardano.julc.ledger.TokenName;
import com.bloxbean.cardano.julc.ledger.TxId;
import com.bloxbean.cardano.julc.ledger.TxInInfo;
import com.bloxbean.cardano.julc.ledger.TxOut;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import com.bloxbean.cardano.julc.ledger.Value;
import com.bloxbean.cardano.julc.testkit.ContractTest;
import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoKeyPaymentBatch;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchData;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProofArtifact;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkStatement;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkSettlementPublicInputs;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj.EutxoBatchProofEngine;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj.EutxoKeyPaymentBatchCircuit;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj.EutxoKeyPaymentSettlementCircuit;
import com.bloxbean.cardano.zeroj.onchain.julc.groth16.validator.Groth16BLS12381Verifier;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EutxoValidityRootValidatorTest extends ContractTest {
    private static final byte[] THREAD_POLICY = fill(28, 31);
    private static final byte[] SCRIPT = fill(28, 32);
    private static final byte[] MIGRATION_AUTHORITY = fill(28, 33);
    private static final byte[] MIGRATION_TARGET = fill(28, 34);
    private static final byte[] DATA_AVAILABILITY_SCRIPT = fill(28, 35);

    @Test
    void settlementBoundProofVerifiesInStockJulcVerifier() {
        Fixture fixture = fixture();
        try (fixture) {
            var keyParams = EutxoValidityOnChainAbi.verificationKeyParameters(
                    fixture.engine().verificationKey());
            var output = JulcScriptLoader.loadOutput(
                    Groth16BLS12381Verifier.class);
            var program = JulcScriptAdapter.toProgram(output.cborHex())
                    .applyParams(keyParams.toArray(PlutusData[]::new));
            var context = spendingContext(
                            ref(9),
                            EutxoValidityOnChainAbi.publicInputs(
                                    fixture.inputs()))
                    .redeemer(EutxoValidityOnChainAbi.proof(fixture.proof()))
                    .buildPlutusData();
            assertSuccess(evaluate(program, context));
        }
    }

    @Test
    void proofPermissionlesslyAdvancesOnlyItsBoundSettlementContext() {
        Fixture fixture = fixture();
        try (fixture) {
            Program program = program(fixture);
            var success = evaluate(
                    program,
                    advanceContext(
                            fixture,
                            fixture.proof().statement().publicInputs()
                                    .batchDataCommitment(),
                            fixture.proof().statement().publicInputs()
                                    .settlementContext(),
                            fixture.proof().statement().publicInputs()
                                    .nextRoot()));
            assertSuccess(success);
            assertThat(success.budgetConsumed()).isNotNull();

            assertFailure(evaluate(
                    program,
                    advanceContext(
                            fixture,
                            fixture.proof().statement().publicInputs()
                                    .batchDataCommitment().add(BigInteger.ONE),
                            fixture.proof().statement().publicInputs()
                                    .settlementContext(),
                            fixture.proof().statement().publicInputs()
                                    .nextRoot())));
            assertFailure(evaluate(
                    program,
                    advanceContext(
                            fixture,
                            fixture.proof().statement().publicInputs()
                                    .batchDataCommitment(),
                            fixture.proof().statement().publicInputs()
                                    .settlementContext().add(BigInteger.ONE),
                            fixture.proof().statement().publicInputs()
                                    .nextRoot())));
            assertFailure(evaluate(
                    program,
                    advanceContext(
                            fixture,
                            fixture.proof().statement().publicInputs()
                                    .batchDataCommitment(),
                            fixture.proof().statement().publicInputs()
                                    .settlementContext(),
                            fixture.proof().statement().publicInputs()
                                    .nextRoot().add(BigInteger.ONE))));
            assertFailure(evaluate(
                    program,
                    advanceContext(fixture, true)));
            System.out.println(
                    "Z3_VALIDITY_ROOT budget=" + success.budgetConsumed());
        }
    }

    @Test
    void migrationIsExplicitAuthorizedAndRootPreserving() {
        Fixture fixture = fixture();
        try (fixture) {
            Program program = program(fixture);
            assertSuccess(evaluate(
                    program,
                    migrationContext(fixture, true, true)));
            assertFailure(evaluate(
                    program,
                    migrationContext(fixture, false, true)));
            assertFailure(evaluate(
                    program,
                    migrationContext(fixture, true, false)));
        }
    }

    @Test
    void rootAdvanceRequiresOneExactProofBoundDataPublication() {
        Fixture fixture = fixture();
        try (fixture) {
            Program program = program(fixture);
            byte[] corrupt = fixture.batchData().canonicalBytes();
            corrupt[corrupt.length - 1] ^= 1;

            assertFailure(evaluate(program, advanceContext(
                    fixture, false, corrupt, 1)));
            assertFailure(evaluate(program, advanceContext(
                    fixture, false,
                    fixture.batchData().canonicalBytes(), 0)));
            assertFailure(evaluate(program, advanceContext(
                    fixture, false,
                    fixture.batchData().canonicalBytes(), 2)));
        }
    }

    @Test
    void canonicalAbiCarriesOnlyTheProofBoundBatch() {
        Fixture fixture = fixture();
        try (fixture) {
            assertThat(EutxoValidityOnChainAbi.advanceRedeemer(
                    fixture.proof(), fixture.batchData())).isNotNull();
            var other = new EutxoZkBatchData(
                    List.of(payment(100, 59)));
            assertThatThrownBy(() ->
                    EutxoValidityOnChainAbi.advanceRedeemer(
                            fixture.proof(), other))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void releasePinnedJulcCompilesContextBoundValidator() {
        assertThat(compileValidator(
                EutxoValidityRootValidator.class).program()).isNotNull();
    }

    private Program program(Fixture fixture) {
        var key = fixture.engine().verificationKey();
        var keyParams =
                EutxoValidityOnChainAbi.verificationKeyParameters(key);
        return compileValidator(EutxoValidityRootValidator.class)
                .program()
                .applyParams(
                        PlutusData.bytes(THREAD_POLICY),
                        PlutusData.bytes(new byte[0]),
                        PlutusData.integer(
                                fixture.inputs().settlementContext()),
                        keyParams.get(0),
                        keyParams.get(1),
                        keyParams.get(2),
                        keyParams.get(3),
                        keyParams.get(4),
                        PlutusData.bytes(DATA_AVAILABILITY_SCRIPT),
                        PlutusData.bytes(MIGRATION_AUTHORITY),
                        PlutusData.bytes(MIGRATION_TARGET));
    }

    private PlutusData advanceContext(
            Fixture fixture,
            BigInteger batchCommitment,
            BigInteger contextCommitment,
            BigInteger nextRoot
    ) {
        return advanceContext(
                fixture, batchCommitment, contextCommitment,
                nextRoot, false);
    }

    private PlutusData advanceContext(
            Fixture fixture,
            boolean corruptProof
    ) {
        var inputs = fixture.inputs();
        return advanceContext(
                fixture,
                inputs.batchDataCommitment(),
                inputs.settlementContext(),
                inputs.nextRoot(),
                corruptProof);
    }

    private PlutusData advanceContext(
            Fixture fixture,
            BigInteger batchCommitment,
            BigInteger contextCommitment,
            BigInteger nextRoot,
            boolean corruptProof
    ) {
        var inputs = fixture.inputs();
        return advanceContext(
                fixture, batchCommitment, contextCommitment,
                nextRoot, corruptProof,
                fixture.batchData().canonicalBytes(), 1);
    }

    private PlutusData advanceContext(
            Fixture fixture,
            boolean corruptProof,
            byte[] batchData,
            int dataOutputs
    ) {
        var inputs = fixture.inputs();
        return advanceContext(
                fixture,
                inputs.batchDataCommitment(),
                inputs.settlementContext(),
                inputs.nextRoot(),
                corruptProof,
                batchData,
                dataOutputs);
    }

    private PlutusData advanceContext(
            Fixture fixture,
            BigInteger batchCommitment,
            BigInteger contextCommitment,
            BigInteger nextRoot,
            boolean corruptProof,
            byte[] batchData,
            int dataOutputs
    ) {
        var inputs = fixture.inputs();
        PlutusData current = rootDatum(
                0, 6, inputs.previousRoot(),
                inputs.settlementContext(), BigInteger.ZERO,
                BigInteger.ZERO, 0);
        PlutusData next = rootDatum(
                0, 7, nextRoot,
                contextCommitment, batchCommitment,
                inputs.withdrawalCommitment(), 0);
        PlutusData redeemer = advanceRedeemer(
                fixture.proof(), batchCommitment, contextCommitment,
                nextRoot, batchData, corruptProof);
        TxOutRef ownRef = ref(1);
        Address address = scriptAddress(SCRIPT);
        var builder = spendingContext(ownRef, current)
                .redeemer(redeemer)
                .input(new TxInInfo(
                        ownRef,
                        output(address, current)))
                .output(output(address, next));
        for (int index = 0; index < dataOutputs; index++) {
            builder.output(dataOutput(batchData));
        }
        if (corruptProof) {
            builder.signer(MIGRATION_AUTHORITY);
        }
        return builder.buildPlutusData();
    }

    private PlutusData migrationContext(
            Fixture fixture,
            boolean signed,
            boolean rootPreserved
    ) {
        var inputs = fixture.inputs();
        PlutusData current = rootDatum(
                0, 6, inputs.previousRoot(),
                inputs.settlementContext(), BigInteger.ZERO,
                BigInteger.ZERO, 0);
        PlutusData next = rootDatum(
                1, 6,
                rootPreserved
                        ? inputs.previousRoot()
                        : inputs.previousRoot().add(BigInteger.ONE),
                inputs.settlementContext().add(BigInteger.ONE),
                BigInteger.ZERO, BigInteger.ZERO, 1);
        TxOutRef ownRef = ref(2);
        var builder = spendingContext(ownRef, current)
                .redeemer(migrationRedeemer())
                .input(new TxInInfo(
                        ownRef,
                        output(scriptAddress(SCRIPT), current)))
                .output(output(
                        scriptAddress(MIGRATION_TARGET), next));
        if (signed) {
            builder.signer(MIGRATION_AUTHORITY);
        }
        return builder.buildPlutusData();
    }

    private static PlutusData advanceRedeemer(
            EutxoZkProofArtifact proof,
            BigInteger batchCommitment,
            BigInteger contextCommitment,
            BigInteger nextRoot,
            byte[] batchData,
            boolean corruptProof
    ) {
        var inputs = proof.statement().publicInputs();
        byte[] piA = proof.piA();
        if (corruptProof) {
            piA[piA.length - 1] ^= 1;
        }
        return PlutusData.constr(
                0,
                PlutusData.integer(0),
                PlutusData.integer(inputs.previousRoot()),
                PlutusData.integer(nextRoot),
                PlutusData.integer(inputs.transitionDigest()),
                PlutusData.integer(inputs.ownerCommitment()),
                PlutusData.integer(inputs.batchSize()),
                PlutusData.integer(contextCommitment),
                PlutusData.integer(batchCommitment),
                PlutusData.integer(inputs.withdrawalCommitment()),
                PlutusData.bytes(batchData),
                PlutusData.bytes(piA),
                PlutusData.bytes(proof.piB()),
                PlutusData.bytes(proof.piC()));
    }

    private static PlutusData migrationRedeemer() {
        return PlutusData.constr(
                0,
                PlutusData.integer(1),
                PlutusData.integer(0),
                PlutusData.integer(0),
                PlutusData.integer(0),
                PlutusData.integer(0),
                PlutusData.integer(0),
                PlutusData.integer(0),
                PlutusData.integer(0),
                PlutusData.integer(0),
                PlutusData.bytes(new byte[0]),
                PlutusData.bytes(new byte[0]),
                PlutusData.bytes(new byte[0]),
                PlutusData.bytes(new byte[0]));
    }

    private static PlutusData rootDatum(
            long bridgeEpoch,
            long height,
            BigInteger root,
            BigInteger context,
            BigInteger batch,
            BigInteger withdrawal,
            long generation
    ) {
        return PlutusData.constr(
                0,
                PlutusData.integer(1),
                PlutusData.bytes(
                        "payments".getBytes(StandardCharsets.UTF_8)),
                PlutusData.integer(bridgeEpoch),
                PlutusData.integer(height),
                PlutusData.integer(root),
                PlutusData.integer(context),
                PlutusData.integer(batch),
                PlutusData.integer(withdrawal),
                PlutusData.integer(generation));
    }

    private static Fixture fixture() {
        EutxoBatchProofEngine engine =
                EutxoBatchProofEngine.singleParticipantDevelopmentSetup();
        EutxoKeyPaymentBatch witness = new EutxoKeyPaymentBatch(
                List.of(
                        payment(100, 60),
                        payment(60, 25)),
                BigInteger.valueOf(424242));
        var batchInputs = EutxoKeyPaymentBatchCircuit.publicInputs(
                new byte[32], witness);
        var batchData = new EutxoZkBatchData(witness.payments());
        byte[] withdrawalCommitment =
                EutxoKeyPaymentSettlementCircuit
                        .withdrawalCommitment(witness);
        var inputs = EutxoKeyPaymentSettlementCircuit.publicInputs(
                "payments", 0, engine.verificationKey().digestHex(),
                new byte[32], witness,
                batchData.commitment(), withdrawalCommitment);
        var statement = new EutxoZkStatement(
                "payments", 7, 0,
                EutxoZkProfile.Z3_VALIDITY_SETTLEMENT,
                inputs, batchData.commitment());
        var proof = engine.prove(statement, witness, "validator-test");
        return new Fixture(engine, inputs, proof, batchData);
    }

    private static EutxoKeyPaymentBatch.Payment payment(
            long input,
            long first
    ) {
        return new EutxoKeyPaymentBatch.Payment(
                BigInteger.valueOf(input),
                BigInteger.valueOf(first),
                BigInteger.valueOf(input - first));
    }

    private static TxOut output(Address address, PlutusData datum) {
        return new TxOut(
                address,
                Value.lovelace(BigInteger.valueOf(2_000_000))
                        .merge(Value.singleton(
                                new PolicyId(THREAD_POLICY),
                                new TokenName(new byte[0]),
                                BigInteger.ONE)),
                new OutputDatum.OutputDatumInline(datum),
                Optional.empty());
    }

    private static TxOut dataOutput(byte[] batchData) {
        return new TxOut(
                scriptAddress(DATA_AVAILABILITY_SCRIPT),
                Value.lovelace(BigInteger.valueOf(2_000_000)),
                new OutputDatum.OutputDatumInline(
                        PlutusData.bytes(batchData)),
                Optional.empty());
    }

    private static Address scriptAddress(byte[] hash) {
        return new Address(
                new Credential.ScriptCredential(new ScriptHash(hash)),
                Optional.empty());
    }

    private static TxOutRef ref(int value) {
        return new TxOutRef(
                new TxId(fill(32, value)), BigInteger.ZERO);
    }

    private static byte[] fill(int size, int value) {
        byte[] bytes = new byte[size];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private record Fixture(
            EutxoBatchProofEngine engine,
            EutxoZkSettlementPublicInputs inputs,
            EutxoZkProofArtifact proof,
            EutxoZkBatchData batchData
    ) implements AutoCloseable {
        @Override
        public void close() {
            engine.close();
        }
    }
}
