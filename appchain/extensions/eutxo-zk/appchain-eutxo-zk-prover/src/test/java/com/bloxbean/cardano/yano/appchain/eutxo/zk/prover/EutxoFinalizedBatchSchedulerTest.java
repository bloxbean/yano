package com.bloxbean.cardano.yano.appchain.eutxo.zk.prover;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchProfile;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EutxoFinalizedBatchSchedulerTest {

    @Test
    void b16DrainsInOrderAndRejectsBeforeQueueOverflow() {
        var scheduler = new EutxoFinalizedBatchScheduler(
                EutxoZkBatchProfile.CARDANO_PAYMENT_B16, 2);
        for (int index = 0; index < 32; index++) {
            scheduler.offer(item(index));
        }
        assertThat(scheduler.queuedBatches()).isEqualTo(2);
        assertThatThrownBy(() -> scheduler.offer(item(32)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("capacity");

        assertThat(scheduler.drainBatch())
                .hasSize(16)
                .extracting(
                        EutxoFinalizedBatchScheduler.FinalizedItem
                                ::transitionDigest)
                .containsExactly(java.util.stream.IntStream.range(0, 16)
                        .mapToObj(EutxoFinalizedBatchSchedulerTest::item)
                        .map(EutxoFinalizedBatchScheduler.FinalizedItem
                                ::transitionDigest)
                        .toArray(String[]::new));
        assertThat(scheduler.queuedTransactions()).isEqualTo(16);
    }

    @Test
    void finalizedItemRejectsDigestMismatch() {
        assertThatThrownBy(() ->
                new EutxoFinalizedBatchScheduler.FinalizedItem(
                        "00".repeat(32), new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("digest mismatch");
    }

    private static EutxoFinalizedBatchScheduler.FinalizedItem item(
            int value
    ) {
        byte[] witness = new byte[]{(byte) value};
        return new EutxoFinalizedBatchScheduler.FinalizedItem(
                HexFormat.of().formatHex(
                        Blake2bUtil.blake2bHash256(witness)),
                witness);
    }
}
