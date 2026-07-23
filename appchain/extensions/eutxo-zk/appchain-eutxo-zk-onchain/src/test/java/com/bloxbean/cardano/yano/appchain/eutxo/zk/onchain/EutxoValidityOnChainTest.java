package com.bloxbean.cardano.yano.appchain.eutxo.zk.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.clientlib.JulcScriptAdapter;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.julc.testkit.ContractTest;
import com.bloxbean.cardano.julc.testkit.TestDataBuilder;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkPublicInputs;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj.EutxoGroth16DevelopmentSetup;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj.EutxoKeyPaymentCircuit;
import com.bloxbean.cardano.zeroj.onchain.julc.groth16.validator.Groth16BLS12381Verifier;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.assertj.core.api.Assertions.assertThat;

class EutxoValidityOnChainTest extends ContractTest {

    @Test
    void developmentProofVerifiesInJulcVmAndTamperingFails() throws Exception {
        BigInteger ownerSecret = BigInteger.valueOf(987654321);
        EutxoZkPublicInputs inputs = EutxoKeyPaymentCircuit.publicInputs(
                sha256("old-root"), sha256("transition"), ownerSecret);

        try (var setup = EutxoGroth16DevelopmentSetup.create()) {
            var proof = setup.prove(inputs, ownerSecret);
            var parameters = EutxoValidityOnChainAbi.verificationKeyParameters(
                    setup.compressedVerificationKey());
            var output = JulcScriptLoader.loadOutput(
                    Groth16BLS12381Verifier.class);
            var program = JulcScriptAdapter.toProgram(output.cborHex()).applyParams(
                    parameters.toArray(PlutusData[]::new));
            var context = spendingContext(
                            TestDataBuilder.randomTxOutRef_typed(),
                            EutxoValidityOnChainAbi.publicInputs(inputs))
                    .redeemer(EutxoValidityOnChainAbi.proof(
                            proof.compressedProof()))
                    .buildPlutusData();

            var result = evaluate(program, context);
            assertSuccess(result);
            assertThat(result.budgetConsumed()).isNotNull();

            EutxoZkPublicInputs tampered = new EutxoZkPublicInputs(
                    inputs.previousRoot(),
                    inputs.nextRoot().add(BigInteger.ONE),
                    inputs.transitionDigest(),
                    inputs.ownerCommitment(),
                    BigInteger.ONE);
            var badContext = spendingContext(
                            TestDataBuilder.randomTxOutRef_typed(),
                            EutxoValidityOnChainAbi.publicInputs(tampered))
                    .redeemer(EutxoValidityOnChainAbi.proof(
                            proof.compressedProof()))
                    .buildPlutusData();
            assertFailure(evaluate(program, badContext));
        }
    }

    private static byte[] sha256(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
    }
}
