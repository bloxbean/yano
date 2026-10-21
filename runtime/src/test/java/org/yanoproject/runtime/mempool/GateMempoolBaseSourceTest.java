package org.yanoproject.runtime.mempool;

import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;
import org.yanoproject.runtime.ledger.canonical.CanonicalSnapshotSource;
import org.yanoproject.runtime.ledger.canonical.CanonicalStateGate;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** The mempool's freshness mark and a base's mark agree even when the published tip's epoch was unknown. */
class GateMempoolBaseSourceTest {

    private static final long EPOCH = 432_000;

    @Test
    void theCurrentMarkUsesTheSameEpochComputationAsSnapshotAcquisition() {
        AtomicBoolean calculatorReady = new AtomicBoolean(false);
        CanonicalStateGate gate = new CanonicalStateGate(() -> new ChainTip(EPOCH - 1,
                HexUtil.decodeHexString("cc".repeat(32)), 10));
        gate.configureEpochCalculator(slot -> {
            if (!calculatorReady.get()) {
                throw new IllegalStateException("genesis not loaded yet");
            }
            return (int) (slot / EPOCH);
        });
        gate.configureEpochStartSlot(epoch -> epoch * EPOCH);
        gate.installSnapshotSource(tip -> new CanonicalSnapshotSource.Captured(null, null, null, () -> { }));
        assertThat(gate.tip().tipSlotEpoch()).as("published while the epoch was unknown").isEqualTo(-1);
        calculatorReady.set(true);

        GateMempoolBaseSource source = new GateMempoolBaseSource(() -> gate);
        MempoolBase base = source.acquire();
        try {
            assertThat(base.mark()).isEqualTo(source.current());
            assertThat(base.mark().targetEpoch()).isEqualTo(1);
        } finally {
            base.release();
        }
        assertThat(gate.liveSnapshotCount()).isZero();
    }
}
