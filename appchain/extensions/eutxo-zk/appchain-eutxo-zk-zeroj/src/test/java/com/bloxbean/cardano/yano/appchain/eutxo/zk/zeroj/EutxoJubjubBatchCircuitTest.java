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
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchSettlement;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProfile;
import com.bloxbean.cardano.zeroj.circuit.lib.jubjub.EdDSAJubjub;
import com.bloxbean.cardano.zeroj.circuit.lib.jubjub.JubjubCurve;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EutxoJubjubBatchCircuitTest {

    @Test
    void rejectsBatchLargerThanCanonicalB16Manifest() throws Exception {
        var profile = EutxoZkBatchProfile.CARDANO_PAYMENT_B16;
        var transaction = transaction(BigInteger.ONE, 0);

        assertThatThrownBy(() -> EutxoJubjubBatchCircuit.statement(
                profile,
                new byte[32],
                java.util.Collections.nCopies(17, transaction)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1-16 transaction ids");
    }

    @Test
    void trustedProverBoundaryRejectsMismatchedSettlementMetadata()
            throws Exception {
        var profile = EutxoZkBatchProfile.CARDANO_PAYMENT_B16;
        var transaction = transaction(BigInteger.ONE, 0);
        String verificationKeyDigest = "42".repeat(32);
        var settlement = EutxoZkBatchSettlement.forTransactions(
                profile,
                verificationKeyDigest,
                List.of(transaction),
                BigInteger.ZERO);
        settlement.requireMatches(
                profile, verificationKeyDigest, List.of(transaction));
        var mismatched = new EutxoZkBatchSettlement(
                settlement.settlementContext().add(BigInteger.ONE),
                settlement.batchDataCommitment(),
                settlement.withdrawalLovelace());

        assertThatThrownBy(() -> mismatched.requireMatches(
                profile, verificationKeyDigest, List.of(transaction)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
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

    static EutxoL2Transaction transaction(
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
