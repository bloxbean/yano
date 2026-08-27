package com.bloxbean.cardano.yano.archive.core.source;

import com.bloxbean.cardano.yaci.core.model.*;
import com.bloxbean.cardano.yano.api.CanonicalBlockReference;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import com.bloxbean.cardano.yaci.core.model.certs.*;
import com.bloxbean.cardano.yaci.core.model.governance.Drep;

class YaciBlockArchiveDecoderTest {
    @Test
    void retainsPhaseTwoInvalidLocationAndOnlyDerivesValidAccountEvents() {
        String validHash = "11".repeat(32);
        String invalidHash = "22".repeat(32);
        String reward = "e0" + "33".repeat(28);
        var valid = TransactionBody.builder().txHash(validHash).fee(BigInteger.TEN)
                .withdrawals(Map.of(reward, BigInteger.valueOf(5))).build();
        var invalid = TransactionBody.builder().txHash(invalidHash).fee(BigInteger.valueOf(20))
                .totalCollateral(BigInteger.valueOf(15))
                .withdrawals(Map.of(reward, BigInteger.valueOf(7))).build();
        String blockHash = "44".repeat(32);
        Block block = Block.builder().era(Era.Conway)
                .header(BlockHeader.builder().headerBody(HeaderBody.builder()
                        .blockNumber(7).slot(100).prevHash("55".repeat(32)).blockHash(blockHash).build()).build())
                .transactionBodies(List.of(valid, invalid))
                .transactionWitness(List.of(emptyWitness(), emptyWitness()))
                .auxiliaryDataMap(Map.of()).invalidTransactions(List.of(1)).build();

        var decoder = new YaciBlockArchiveDecoder(slot -> 3, slot -> 1_700_000_000L + slot);
        byte[] hash = java.util.HexFormat.of().parseHex(blockHash);
        var decoded = decoder.decodeBlock(7, new CanonicalBlockReference(7, 100, hash), block);

        assertThat(decoded.block().transactions()).extracting(t -> t.valid())
                .containsExactly(true, false);
        assertThat(decoded.block().transactions()).extracting(t -> t.fee())
                .containsExactly(10L, 15L);
        assertThat(decoded.block().accountEvents()).hasSize(1);
        assertThat(decoded.block().accountEvents().getFirst().eventType()).isEqualTo("withdrawal");
        assertThat(decoded.blockTime().getEpochSecond()).isEqualTo(1_700_000_100L);
    }

    @Test
    void retainsZeroWithdrawalAndNegativeMirDelta() {
        String reward = "e0" + "33".repeat(28);
        var stake = StakeCredential.fromKeyHash(java.util.HexFormat.of().parseHex("33".repeat(28)));
        var tx = TransactionBody.builder().txHash("11".repeat(32)).fee(BigInteger.ONE)
                .withdrawals(Map.of(reward, BigInteger.ZERO))
                .certificates(List.of(MoveInstataneous.builder().treasury(true)
                        .stakeCredentialCoinMap(Map.of(stake, BigInteger.valueOf(-7))).build()))
                .build();
        Block block = Block.builder().era(Era.Alonzo)
                .header(BlockHeader.builder().headerBody(HeaderBody.builder()
                        .blockNumber(1).slot(10).prevHash("44".repeat(32))
                        .blockHash("55".repeat(32)).build()).build())
                .transactionBodies(List.of(tx)).invalidTransactions(List.of()).build();

        var decoded = new YaciBlockArchiveDecoder(slot -> 0, slot -> slot).decodeBlock(1,
                new CanonicalBlockReference(1, 10,
                        java.util.HexFormat.of().parseHex("55".repeat(32))), block);

        assertThat(decoded.block().accountEvents()).extracting(event -> event.eventType())
                .containsExactly("withdrawal", "mir_treasury");
        assertThat(decoded.block().accountEvents()).extracting(event -> event.amount())
                .containsExactly(0L, -7L);
    }

    @Test
    void recordsCanonicalCredentialTypesAndEveryDrepDelegationCertificateShape() {
        var stake = StakeCredential.fromKeyHash(new byte[28]);
        String pool = "77".repeat(28);
        var tx = TransactionBody.builder().txHash("11".repeat(32)).fee(BigInteger.ONE)
                .certificates(List.of(
                        VoteDelegCert.builder().stakeCredential(stake).drep(Drep.abstain()).build(),
                        VoteRegDelegCert.builder().stakeCredential(stake).drep(Drep.noConfidence())
                                .coin(BigInteger.TEN).build(),
                        StakeVoteDelegCert.builder().stakeCredential(stake).poolKeyHash(pool)
                                .drep(Drep.addrKeyHash("22".repeat(28))).build(),
                        StakeVoteRegDelegCert.builder().stakeCredential(stake).poolKeyHash(pool)
                                .drep(Drep.scriptHash("33".repeat(28))).coin(BigInteger.TEN).build()))
                .build();
        Block block = Block.builder().era(Era.Conway)
                .header(BlockHeader.builder().headerBody(HeaderBody.builder()
                        .blockNumber(1).slot(10).prevHash("44".repeat(32))
                        .blockHash("55".repeat(32)).build()).build())
                .transactionBodies(List.of(tx)).invalidTransactions(List.of()).build();

        var decoded = new YaciBlockArchiveDecoder(slot -> 0, slot -> slot).decodeBlock(1,
                new CanonicalBlockReference(1, 10, java.util.HexFormat.of().parseHex("55".repeat(32))), block);

        assertThat(decoded.block().accountEvents()).allMatch(event -> event.credentialType().equals("key"));
        assertThat(decoded.block().accountEvents().stream()
                .filter(event -> event.eventType().equals("drep_delegation"))
                .map(event -> event.drepType())).containsExactly(
                        "always_abstain", "always_no_confidence", "key", "script");
        assertThat(decoded.block().accountEvents().stream()
                .filter(event -> event.eventType().equals("registration"))).hasSize(2);
        assertThat(decoded.block().accountEvents().stream()
                .filter(event -> event.eventType().equals("delegation"))).hasSize(2);
    }

    private static Witnesses emptyWitness() {
        return Witnesses.builder().vkeyWitnesses(List.of()).nativeScripts(List.of())
                .bootstrapWitnesses(List.of()).plutusV1Scripts(List.of()).datums(List.of())
                .redeemers(List.of()).plutusV2Scripts(List.of()).plutusV3Scripts(List.of()).build();
    }
}
