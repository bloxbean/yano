package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.api.SigningProvider;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalClaim;
import com.bloxbean.cardano.yano.appchain.eutxo.testkit.EutxoTestWallet;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-UTXO-009 SP-M3: the federation-threshold co-signer verifies and
 * assembles member witnesses for exactly the body's required signers, and
 * fails closed when any required witness is missing, forged, or below
 * threshold.
 */
class SettlementCosignerTest {
    private static final SigningProvider SIGNING =
            CryptoConfiguration.INSTANCE.getSigningProvider();
    private static final String VAULT =
            EutxoTestWallet.fromSeed(fill(32, 0x54)).address();
    private static final String BOUNTY =
            EutxoTestWallet.fromSeed(fill(32, 0x99)).address();

    private final Member m1 = new Member(fill(32, 0xA1));
    private final Member m2 = new Member(fill(32, 0xA2));
    private final Member m3 = new Member(fill(32, 0xA3));
    private final Member outsider = new Member(fill(32, 0xEE));

    @Test
    void assemblesVerifiedWitnessesForEveryRequiredSignerAndPreservesTheBodyHash()
            throws Exception {
        byte[] body = settleBody(m1, m2);
        String expectedTxId = TransactionUtil.getTxHash(wrap(body));

        SettlementCosigner cosigner = new SettlementCosigner(
                () -> members(m1, m2, m3), () -> 2,
                (bodyHash, memberKeys, threshold) ->
                        signatures(bodyHash, m1, m2));

        byte[] signed = cosigner.cosign(body, List.of());
        Transaction assembled = Transaction.deserialize(signed);

        // The body is untouched: the txid still equals the co-signed hash.
        assertThat(TransactionUtil.getTxHash(assembled)).isEqualTo(expectedTxId);
        // One verified witness per required signer.
        assertThat(assembled.getWitnessSet().getVkeyWitnesses()).hasSize(2);
        byte[] hash = HexUtil.decodeHexString(expectedTxId);
        assertThat(assembled.getWitnessSet().getVkeyWitnesses()).allSatisfy(witness ->
                assertThat(SIGNING.verify(
                        witness.getSignature(), hash, witness.getVkey())).isTrue());
    }

    @Test
    void extraNonMemberSignaturesAreIgnored() throws Exception {
        byte[] body = settleBody(m1, m2);
        SettlementCosigner cosigner = new SettlementCosigner(
                () -> members(m1, m2), () -> 2,
                (bodyHash, memberKeys, threshold) ->
                        signatures(bodyHash, m1, m2, outsider));

        byte[] signed = cosigner.cosign(body, List.of());
        assertThat(Transaction.deserialize(signed)
                .getWitnessSet().getVkeyWitnesses()).hasSize(2);
    }

    @Test
    void missingARequiredSignerFailsClosed() throws Exception {
        byte[] body = settleBody(m1, m2);
        SettlementCosigner cosigner = new SettlementCosigner(
                () -> members(m1, m2, m3), () -> 2,
                (bodyHash, memberKeys, threshold) ->
                        signatures(bodyHash, m1)); // m2 never responded

        assertThatThrownBy(() -> cosigner.cosign(body, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing a valid witness for required signer");
    }

    @Test
    void forgedSignatureForARequiredSignerIsRejected() throws Exception {
        byte[] body = settleBody(m1, m2);
        SettlementCosigner cosigner = new SettlementCosigner(
                () -> members(m1, m2), () -> 2,
                (bodyHash, memberKeys, threshold) -> {
                    // m1 signs honestly; m2's "signature" is garbage.
                    Map<String, byte[]> sigs = new java.util.LinkedHashMap<>(
                            signatures(bodyHash, m1));
                    sigs.put(m2.pubHex, fill(64, 0x00));
                    return sigs;
                });

        assertThatThrownBy(() -> cosigner.cosign(body, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing a valid witness for required signer");
    }

    // --- fixtures ---------------------------------------------------------

    private byte[] settleBody(Member... requiredSigners) {
        List<EutxoWithdrawalClaim> claims = List.of(
                claim(0, 8_000_000L, 2_000_000L),
                claim(1, 5_000_000L, 2_000_000L));
        List<BatchSettlementTransactionBuilder.VaultInput> inventory = List.of(
                new BatchSettlementTransactionBuilder.VaultInput(
                        outpoint(0x11), BigInteger.valueOf(40_000_000L)));
        List<byte[]> signerHashes = new java.util.ArrayList<>();
        for (Member member : requiredSigners) {
            signerHashes.add(member.keyHash);
        }
        BatchSettlementTransactionBuilder.ExecutionInputs execution =
                new BatchSettlementTransactionBuilder.ExecutionInputs(
                        NetworkId.TESTNET, outpoint(0x63), outpoint(0x62),
                        List.of(outpoint(0x70)), signerHashes);
        return BatchSettlementTransactionBuilder.build(
                claims, inventory, VAULT, BOUNTY,
                BigInteger.valueOf(300_000L), BigInteger.valueOf(2_000_000L),
                1_000L, 7_200L, execution).unsignedBodyCbor();
    }

    private static Transaction wrap(byte[] bodyCbor) throws Exception {
        return Transaction.builder()
                .body(com.bloxbean.cardano.client.transaction.spec.TransactionBody.deserialize(
                        (co.nstant.in.cbor.model.Map)
                                com.bloxbean.cardano.client.common.cbor.CborSerializationUtil
                                        .deserialize(bodyCbor)))
                .witnessSet(new com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet())
                .build();
    }

    private static Map<String, byte[]> signatures(byte[] bodyHash, Member... signers) {
        Map<String, byte[]> sigs = new java.util.LinkedHashMap<>();
        for (Member signer : signers) {
            sigs.put(signer.pubHex, SIGNING.sign(bodyHash, signer.seed));
        }
        return sigs;
    }

    private static Set<String> members(Member... members) {
        Set<String> set = new java.util.TreeSet<>();
        for (Member member : members) {
            set.add(member.pubHex);
        }
        return set;
    }

    private static EutxoWithdrawalClaim claim(int index, long payout, long bounty) {
        return new EutxoWithdrawalClaim(
                EutxoWithdrawalClaim.ABI_VERSION_V2, "payments", 7,
                outpoint(0x40 + index),
                EutxoTestWallet.fromSeed(fill(32, 0x80 + index)).address(),
                BigInteger.valueOf(payout), fill(32, 0x30 + index),
                index, 42, BigInteger.valueOf(bounty));
    }

    private static EutxoOutpoint outpoint(int value) {
        return new EutxoOutpoint("%02x".formatted(value & 0xFF).repeat(32), 0);
    }

    private static byte[] fill(int size, int value) {
        byte[] bytes = new byte[size];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static final class Member {
        final byte[] seed;
        final byte[] pub;
        final byte[] keyHash;
        final String pubHex;

        Member(byte[] seed) {
            this.seed = seed;
            this.pub = KeyGenUtil.getPublicKeyFromPrivateKey(seed);
            this.keyHash = Blake2bUtil.blake2bHash224(pub);
            this.pubHex = HexUtil.encodeHexString(pub);
        }
    }
}
