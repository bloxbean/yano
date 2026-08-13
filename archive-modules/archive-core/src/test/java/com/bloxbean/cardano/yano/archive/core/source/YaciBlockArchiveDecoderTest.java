package com.bloxbean.cardano.yano.archive.core.source;

import com.bloxbean.cardano.yaci.core.model.*;
import com.bloxbean.cardano.yano.api.CanonicalBlockReference;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class YaciBlockArchiveDecoderTest {
    @Test
    void retainsPhaseTwoInvalidLocationAndOnlyDerivesValidAccountEvents() {
        String validHash = "11".repeat(32);
        String invalidHash = "22".repeat(32);
        String reward = "e0" + "33".repeat(28);
        var valid = TransactionBody.builder().txHash(validHash).fee(BigInteger.TEN)
                .withdrawals(Map.of(reward, BigInteger.valueOf(5))).build();
        var invalid = TransactionBody.builder().txHash(invalidHash).fee(BigInteger.valueOf(20))
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
        assertThat(decoded.block().accountEvents()).hasSize(1);
        assertThat(decoded.block().accountEvents().getFirst().eventType()).isEqualTo("withdrawal");
        assertThat(decoded.blockTime().getEpochSecond()).isEqualTo(1_700_000_100L);
    }

    private static Witnesses emptyWitness() {
        return Witnesses.builder().vkeyWitnesses(List.of()).nativeScripts(List.of())
                .bootstrapWitnesses(List.of()).plutusV1Scripts(List.of()).datums(List.of())
                .redeemers(List.of()).plutusV2Scripts(List.of()).plutusV3Scripts(List.of()).build();
    }
}
