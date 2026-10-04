package org.yanoproject.ledgerstate;

import org.yanoproject.api.EpochParamProvider;
import org.yanoproject.ledgerstate.test.TestRocksDBHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultAccountStateStoreRollbackFloorTest {
    /** Epochs of 100 slots: epoch N starts at slot 100 * N. */
    private static final EpochParamProvider EPOCHS_OF_100_SLOTS = new EpochParamProvider() {
        @Override public BigInteger getKeyDeposit(long epoch) { return BigInteger.ZERO; }
        @Override public BigInteger getPoolDeposit(long epoch) { return BigInteger.ZERO; }
        @Override public long getEpochLength() { return 100; }
    };

    @TempDir
    Path tempDir;

    @Test
    void youngChainCanRollBackWithinItsCurrentEpoch() throws Exception {
        // Re-crossing the boundaries of epochs 1..3 is not proven safe (replay floor 399, above the tip),
        // but a rollback inside epoch 1 re-crosses none of them
        assertThat(rollbackFloor(150, 0, 0)).isEqualTo(100);
    }

    @Test
    void matureChainKeepsItsEpochReplayFloor() throws Exception {
        // Reward inputs from epoch 15: the boundary of epoch 17 is the oldest that can be replayed
        assertThat(rollbackFloor(2050, 15, 0)).isEqualTo(1699);
    }

    @Test
    void storeWithoutRewardInputsCanStillRollBackWithinItsCurrentEpoch() throws Exception {
        assertThat(rollbackFloor(250, null, null)).isEqualTo(200);
    }

    private long rollbackFloor(long latestAppliedSlot, Integer rewardInputsFrom, Integer snapshotsFrom)
            throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            var store = new DefaultAccountStateStore(rocks.db(), rocks.cfSupplier(),
                    LoggerFactory.getLogger(getClass()), true, EPOCHS_OF_100_SLOTS);
            rocks.db().put(rocks.cfState(), "meta.last_applied_slot".getBytes(),
                    ByteBuffer.allocate(8).putLong(latestAppliedSlot).array());
            if (rewardInputsFrom != null) {
                long slot = rewardInputsFrom * 100L;
                rocks.db().put(rocks.cfState(), DefaultAccountStateStore.blockIssuerKey(rewardInputsFrom, slot),
                        new byte[28]);
                rocks.db().put(rocks.cfState(), DefaultAccountStateStore.blockFeeKey(rewardInputsFrom, slot),
                        new byte[]{0});
            }
            if (snapshotsFrom != null) {
                rocks.db().put(rocks.cfSnapshot(), ByteBuffer.allocate(5).putInt(snapshotsFrom).array(),
                        new byte[]{0});
            }
            return store.getRollbackFloorSlot();
        }
    }
}
