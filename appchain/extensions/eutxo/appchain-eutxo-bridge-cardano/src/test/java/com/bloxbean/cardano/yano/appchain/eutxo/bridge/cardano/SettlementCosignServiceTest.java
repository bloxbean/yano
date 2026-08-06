package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.client.crypto.api.SigningProvider;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.signer.SignerProvider;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalClaim;
import com.bloxbean.cardano.yano.appchain.eutxo.testkit.EutxoTestWallet;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-UTXO-009 SP-M6: the ~bridge/settlement/* co-sign round — owner
 * broadcasts, members verify-then-sign, the owner assembles a fully
 * witnessed transaction; unverifiable bodies and forged replies never
 * produce signatures.
 */
class SettlementCosignServiceTest {
    private static final SigningProvider SIGNING =
            CryptoConfiguration.INSTANCE.getSigningProvider();
    private static final String VAULT =
            EutxoTestWallet.fromSeed(fill(32, 0x54)).address();
    private static final String BOUNTY =
            EutxoTestWallet.fromSeed(fill(32, 0x99)).address();

    private final Member owner = new Member(fill(32, 0xA1));
    private final Member remote = new Member(fill(32, 0xA2));

    @Test
    void ownerRoundCollectsRemoteSignaturesAndAssemblesTheWitnessedTx()
            throws Exception {
        AtomicInteger remoteVerifications = new AtomicInteger();
        ServicePair pair = wire(body -> {
            remoteVerifications.incrementAndGet();
            return true;
        });

        byte[] body = settleBody(owner, remote);
        String expectedTxId = TransactionUtil.getTxHash(
                Transaction.builder()
                        .body(com.bloxbean.cardano.client.transaction.spec
                                .TransactionBody.deserialize(
                                        (co.nstant.in.cbor.model.Map)
                                                com.bloxbean.cardano.client.common
                                                        .cbor.CborSerializationUtil
                                                        .deserialize(body)))
                        .witnessSet(new com.bloxbean.cardano.client.transaction
                                .spec.TransactionWitnessSet())
                        .build());

        byte[] signed = pair.ownerService.cosign(body, List.of());
        Transaction assembled = Transaction.deserialize(signed);

        assertThat(remoteVerifications.get()).isEqualTo(1);
        assertThat(TransactionUtil.getTxHash(assembled)).isEqualTo(expectedTxId);
        assertThat(assembled.getWitnessSet().getVkeyWitnesses()).hasSize(2);
        byte[] hash = HexUtil.decodeHexString(expectedTxId);
        assertThat(assembled.getWitnessSet().getVkeyWitnesses()).allSatisfy(
                witness -> assertThat(SIGNING.verify(
                        witness.getSignature(), hash, witness.getVkey()))
                        .isTrue());
    }

    @Test
    void memberThatCannotVerifyTheBodyNeverSignsAndTheRoundFailsClosed() {
        ServicePair pair = wire(body -> false);
        byte[] body = settleBody(owner, remote);
        assertThatThrownBy(() -> pair.ownerService.cosign(body, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing a valid witness");
    }

    @Test
    void forgedRepliesAreDroppedAndTheGenuineReplyStillCompletesTheRound()
            throws Exception {
        Member outsider = new Member(fill(32, 0xEE));
        ServicePair pair = wire(body -> true);
        // Inject a forged reply BEFORE the round: unknown round -> dropped.
        pair.ownerService.onBridgeMessage(envelope(
                SettlementCosignService.TOPIC_SIG,
                outsider.pub,
                SettlementCosignService.encodeSignature(
                        new byte[32], new byte[64])));

        byte[] body = settleBody(owner, remote);
        byte[] signed = pair.ownerService.cosign(body, List.of());
        assertThat(Transaction.deserialize(signed)
                .getWitnessSet().getVkeyWitnesses()).hasSize(2);
    }

    @Test
    void nonLeaderRefusesToStartRounds() {
        ServicePair pair = wire(body -> true);
        assertThatThrownBy(() -> pair.remoteService.cosign(
                settleBody(owner, remote), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("owner node");
    }

    // ------------------------------------------------------------------

    private record ServicePair(
            SettlementCosignService ownerService,
            SettlementCosignService remoteService) {
    }

    /**
     * Cross-wire two services the way the subsystem does: a diffused body is
     * wrapped in a signed envelope (sender = the diffusing member) and
     * delivered to the OTHER node's handler.
     */
    private ServicePair wire(java.util.function.Predicate<byte[]> remoteVerifier) {
        SettlementCosignService[] services = new SettlementCosignService[2];
        Set<String> members = Set.of(
                HexUtil.encodeHexString(owner.pub),
                HexUtil.encodeHexString(remote.pub));
        services[0] = new SettlementCosignService(
                (topic, body) -> services[1].onBridgeMessage(
                        envelope(topic, owner.pub, body)),
                owner.signer(),
                () -> members,
                () -> 2,
                body -> true,
                true,
                Duration.ofSeconds(2));
        services[1] = new SettlementCosignService(
                (topic, body) -> services[0].onBridgeMessage(
                        envelope(topic, remote.pub, body)),
                remote.signer(),
                () -> members,
                () -> 2,
                remoteVerifier,
                false,
                Duration.ofSeconds(2));
        return new ServicePair(services[0], services[1]);
    }

    private static AppMessage envelope(String topic, byte[] sender, byte[] body) {
        return AppMessage.builder()
                .version(1)
                .messageId(Blake2bUtil.blake2bHash256(body))
                .chainId("payments")
                .topic(topic)
                .sender(sender)
                .senderSeq(1)
                .expiresAt(Long.MAX_VALUE)
                .body(body)
                .authScheme(0)
                .authProof(new byte[64])
                .build();
    }

    private byte[] settleBody(Member... requiredSigners) {
        List<EutxoWithdrawalClaim> claims = List.of(
                claim(0, 8_000_000L, 2_000_000L));
        List<byte[]> signerHashes = new java.util.ArrayList<>();
        for (Member member : requiredSigners) {
            signerHashes.add(member.keyHash);
        }
        return BatchSettlementTransactionBuilder.build(
                claims,
                List.of(new BatchSettlementTransactionBuilder.VaultInput(
                        outpoint(0x11), BigInteger.valueOf(40_000_000L))),
                VAULT, BOUNTY, BigInteger.valueOf(300_000L),
                BigInteger.valueOf(2_000_000L), 1_000L, 7_200L,
                new BatchSettlementTransactionBuilder.ExecutionInputs(
                        NetworkId.TESTNET, outpoint(0x63), outpoint(0x62),
                        List.of(outpoint(0x70)), signerHashes))
                .unsignedBodyCbor();
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

        Member(byte[] seed) {
            this.seed = seed;
            this.pub = KeyGenUtil.getPublicKeyFromPrivateKey(seed);
            this.keyHash = Blake2bUtil.blake2bHash224(pub);
        }

        SignerProvider signer() {
            return new SignerProvider() {
                @Override public byte[] sign(byte[] message) {
                    return SIGNING.sign(message, seed);
                }

                @Override public byte[] publicKey() {
                    return pub.clone();
                }
            };
        }
    }
}
