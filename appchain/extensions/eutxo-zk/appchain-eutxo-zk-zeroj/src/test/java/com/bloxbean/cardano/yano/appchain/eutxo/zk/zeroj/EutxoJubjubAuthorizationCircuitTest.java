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
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2KeyRegistration;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2Transaction;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.testkit.EutxoTestWallet;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkAuthorizationProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProfile;
import com.bloxbean.cardano.zeroj.circuit.lib.jubjub.EdDSAJubjub;
import com.bloxbean.cardano.zeroj.circuit.lib.jubjub.JubjubCurve;
import com.bloxbean.cardano.zeroj.circuit.lib.jubjub.JubjubPoint;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EutxoJubjubAuthorizationCircuitTest {

    @Test
    void developmentProfileVerifiesExactL2SignatureAndRealProof() throws Exception {
        EutxoL2Transaction transaction = transaction(BigInteger.valueOf(101));
        var engine = new ZerojPoseidonValidityEngine(
                "jubjub-test", EutxoProfile.V1);
        String credential = transaction.authorizations()
                .getFirst().paymentCredential();
        var registration = new EutxoL2KeyRegistration(
                credential,
                engine.authorizationProfile(),
                1,
                transaction.authorizations().getFirst().publicKey(),
                EutxoL2KeyRegistration.Status.ACTIVE);

        assertThat(engine.verifyAuthorization(
                transaction, List.of(registration)).accepted()).isTrue();
        var statement =
                EutxoJubjubAuthorizationCircuit.statement(transaction);
        BigInteger[] witness =
                EutxoJubjubAuthorizationCircuit.witness(transaction);
        assertThat(witness).isNotEmpty();

        try (var setup = EutxoGroth16DevelopmentSetup.create(
                EutxoJubjubAuthorizationCircuit.circuit())) {
            var proof = setup.prove(statement.ordered(), witness);
            assertThat(setup.verify(proof)).isTrue();
            assertThat(setup.constraintCount()).isGreaterThan(5_000);
            assertThat(setup.publicInputCount()).isEqualTo(2);
            var tampered = new EutxoGroth16DevelopmentSetup.GenericProofArtifact(
                    List.of(
                            statement.messageCommitment().add(BigInteger.ONE),
                            statement.publicKeyCommitment()),
                    proof.proof(),
                    proof.compressedProof(),
                    proof.proofMillis());
            assertThat(setup.verify(tampered)).isFalse();
        }
    }

    @Test
    void hostGuardRejectsTamperingIdentityAndPublicTestnetWithoutAcknowledgement()
            throws Exception {
        EutxoL2Transaction valid = transaction(BigInteger.valueOf(202));
        var engine = new ZerojPoseidonValidityEngine(
                "jubjub-test", EutxoProfile.V1);
        var authorization = valid.authorizations().getFirst();
        var registration = new EutxoL2KeyRegistration(
                authorization.paymentCredential(),
                engine.authorizationProfile(),
                1,
                authorization.publicKey(),
                EutxoL2KeyRegistration.Status.ACTIVE);

        byte[] tamperedS = authorization.s();
        tamperedS[0] ^= 1;
        EutxoL2Transaction tampered = replaceAuthorization(
                valid,
                new EutxoL2Authorization(
                        authorization.paymentCredential(),
                        1,
                        authorization.publicKey(),
                        authorization.rPoint(),
                        tamperedS,
                        authorization.inputIndexes()));
        assertThat(engine.verifyAuthorization(
                tampered, List.of(registration)).accepted()).isFalse();

        EutxoL2Transaction identity = replaceAuthorization(
                valid,
                new EutxoL2Authorization(
                        authorization.paymentCredential(),
                        1,
                        JubjubPoint.IDENTITY.toBytes(),
                        authorization.rPoint(),
                        authorization.s(),
                        authorization.inputIndexes()));
        assertThat(engine.verifyAuthorization(
                identity, List.of(registration)).accepted()).isFalse();

        var settings = new java.util.LinkedHashMap<>(
                ZerojPoseidonValidityProvider.requiredIdentitySettings());
        settings.put("machines.eutxo.network", "preview");
        assertThatThrownBy(() -> new ZerojPoseidonValidityProvider()
                .create("preview-chain", EutxoProfile.V1, settings))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("acknowledgement");
        settings.put(
                "machines.eutxo.validity.acknowledge-unsafe-jubjub-dev",
                "true");
        assertThat(new ZerojPoseidonValidityProvider()
                .create("preview-chain", EutxoProfile.V1, settings))
                .isNotNull();
    }

    private static EutxoL2Transaction transaction(BigInteger secret)
            throws Exception {
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
        var profile = EutxoZkAuthorizationProfile.JUBJUB_DEVELOPMENT_V1;
        EutxoL2Domain domain = new EutxoL2Domain(
                "jubjub-test",
                "devnet",
                EutxoProfile.V1.digestHex(),
                EutxoZkProfile.Z3_VALIDITY_SETTLEMENT.digestHex(),
                profile.id(),
                profile.digestHex(),
                new byte[32],
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
        return replaceAuthorization(
                template,
                new EutxoL2Authorization(
                        credential, 1, keypair.pk().toBytes(),
                        signature.r().toBytes(),
                        littleEndian32(signature.s()),
                        List.of(0)));
    }

    private static EutxoL2Transaction replaceAuthorization(
            EutxoL2Transaction transaction,
            EutxoL2Authorization authorization
    ) {
        return new EutxoL2Transaction(
                transaction.domain(),
                transaction.transactionBody(),
                List.of(authorization));
    }

    private static byte[] littleEndian32(BigInteger value) {
        if (value.signum() < 0
                || value.compareTo(JubjubCurve.SUBGROUP_ORDER) >= 0) {
            throw new IllegalArgumentException("invalid Jubjub scalar");
        }
        byte[] bigEndian = value.toByteArray();
        int start = bigEndian.length > 1 && bigEndian[0] == 0 ? 1 : 0;
        byte[] result = new byte[32];
        for (int index = start; index < bigEndian.length; index++) {
            result[bigEndian.length - 1 - index] = bigEndian[index];
        }
        return result;
    }
}
