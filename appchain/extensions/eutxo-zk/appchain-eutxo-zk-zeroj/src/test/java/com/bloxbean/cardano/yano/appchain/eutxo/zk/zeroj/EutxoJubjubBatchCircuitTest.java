package com.bloxbean.cardano.yano.appchain.eutxo.zk.zeroj;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2Authorization;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2Domain;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2Transaction;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.testkit.EutxoTestWallet;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkAuthorizationProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProfile;
import com.bloxbean.cardano.zeroj.circuit.lib.jubjub.EdDSAJubjub;
import com.bloxbean.cardano.zeroj.circuit.lib.jubjub.JubjubCurve;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EutxoJubjubBatchCircuitTest {

    @Test
    void b16MaximumBatchProducesOneRealConstantSizeProof()
            throws Exception {
        var profile = EutxoZkBatchProfile.CARDANO_PAYMENT_B16;
        List<EutxoL2Transaction> transactions = new ArrayList<>();
        for (int index = 0; index < profile.maximumTransactions(); index++) {
            transactions.add(transaction(
                    BigInteger.valueOf(index + 1L), index));
        }
        byte[] previousRoot = new byte[32];
        var statement = EutxoJubjubBatchCircuit.statement(
                profile, previousRoot, transactions);
        BigInteger[] witness = EutxoJubjubBatchCircuit.witness(
                profile, statement, transactions);

        try (var setup = EutxoGroth16DevelopmentSetup.create(
                EutxoJubjubBatchCircuit.circuit(profile))) {
            var proof = setup.prove(statement.ordered(), witness);
            assertThat(setup.verify(proof)).isTrue();
            assertThat(setup.publicInputCount()).isEqualTo(4);
            assertThat(statement.batchSize()).isEqualTo(
                    BigInteger.valueOf(16));
            assertThat(proof.compressedProof()).isNotNull();
            System.out.printf(
                    "D3_B16 constraints=%d wires=%d setupMillis=%d proofMillis=%d proofBytes=192%n",
                    setup.constraintCount(),
                    setup.wireCount(),
                    setup.setupMillis(),
                    proof.proofMillis());
        }

        assertThatThrownBy(() -> EutxoJubjubBatchCircuit.statement(
                profile,
                previousRoot,
                java.util.Collections.nCopies(17, transactions.getFirst())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("immutable profile");
    }

    @Test
    void partialBatchUsesSameB16CircuitIdentityAndRejectsTampering()
            throws Exception {
        var profile = EutxoZkBatchProfile.CARDANO_PAYMENT_B16;
        List<EutxoL2Transaction> transactions =
                List.of(transaction(BigInteger.valueOf(71), 1));
        var statement = EutxoJubjubBatchCircuit.statement(
                profile, new byte[32], transactions);
        BigInteger[] witness = EutxoJubjubBatchCircuit.witness(
                profile, statement, transactions);
        assertThat(witness).isNotEmpty();
        assertThat(statement.batchSize()).isEqualTo(BigInteger.ONE);

        byte[] s = transactions.getFirst().authorizations()
                .getFirst().s();
        s[0] ^= 1;
        var authorization = transactions.getFirst().authorizations()
                .getFirst();
        var tampered = new EutxoL2Transaction(
                transactions.getFirst().domain(),
                transactions.getFirst().transactionBody(),
                List.of(new EutxoL2Authorization(
                        authorization.paymentCredential(),
                        authorization.keyEpoch(),
                        authorization.publicKey(),
                        authorization.rPoint(),
                        s,
                        authorization.inputIndexes())));
        assertThatThrownBy(() -> EutxoJubjubBatchCircuit.witness(
                profile,
                EutxoJubjubBatchCircuit.statement(
                        profile, new byte[32], List.of(tampered)),
                List.of(tampered)))
                .isInstanceOf(RuntimeException.class);
    }

    private static EutxoL2Transaction transaction(
            BigInteger secret,
            int nonceValue
    ) throws Exception {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) 7);
        EutxoTestWallet wallet = EutxoTestWallet.fromSeed(seed);
        String credential = new Address(wallet.address())
                .getPaymentCredentialHash()
                .map(HexFormat.of()::formatHex)
                .orElseThrow();
        TransactionBody body = TransactionBody.builder()
                .inputs(List.of(TransactionInput.builder()
                        .transactionId("11".repeat(32))
                        .index(0)
                        .build()))
                .outputs(List.of(TransactionOutput.builder()
                        .address(wallet.address())
                        .value(Value.fromCoin(BigInteger.valueOf(100)))
                        .build()))
                .fee(BigInteger.ZERO)
                .ttl(100)
                .networkId(NetworkId.TESTNET)
                .build();
        byte[] bodyCbor = CborSerializationUtil.serialize(body.serialize());
        var authorizationProfile =
                EutxoZkAuthorizationProfile.JUBJUB_DEVELOPMENT_V1;
        byte[] nonce = new byte[32];
        nonce[31] = (byte) nonceValue;
        EutxoL2Domain domain = new EutxoL2Domain(
                "batch-test",
                "devnet",
                EutxoProfile.V1.digestHex(),
                EutxoZkProfile.Z3_VALIDITY_SETTLEMENT.digestHex(),
                authorizationProfile.id(),
                authorizationProfile.digestHex(),
                nonce,
                100);
        var keypair = EdDSAJubjub.keypairFromSecret(secret);
        EutxoL2Authorization unsigned = new EutxoL2Authorization(
                credential, 1, keypair.pk().toBytes(),
                new byte[32], new byte[32], List.of(0));
        EutxoL2Transaction template = new EutxoL2Transaction(
                domain, bodyCbor, List.of(unsigned));
        BigInteger message = new BigInteger(
                1, template.signingCommitment())
                .mod(JubjubCurve.BASE_FIELD_PRIME);
        EdDSAJubjub.Signature signature = EdDSAJubjub.sign(secret, message);
        return new EutxoL2Transaction(
                domain,
                bodyCbor,
                List.of(new EutxoL2Authorization(
                        credential,
                        1,
                        keypair.pk().toBytes(),
                        signature.r().toBytes(),
                        littleEndian32(signature.s()),
                        List.of(0))));
    }

    private static byte[] littleEndian32(BigInteger value) {
        byte[] bigEndian = value.toByteArray();
        int start = bigEndian.length > 1 && bigEndian[0] == 0 ? 1 : 0;
        byte[] result = new byte[32];
        for (int index = start; index < bigEndian.length; index++) {
            result[bigEndian.length - 1 - index] = bigEndian[index];
        }
        return result;
    }
}
