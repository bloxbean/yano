package com.bloxbean.cardano.yano.ledgerstate;

import com.bloxbean.cardano.yano.ledgerstate.test.TestRocksDBHelper;
import org.cardanofoundation.rewards.calculation.config.NetworkConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EpochBoundaryProcessorTest {

    @TempDir
    Path tempDir;

    @Test
    void rewardCalculationFailsClosedWhenPreviousAdaPotIsMissing() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            var adaPotTracker = new AdaPotTracker(rocks.db(), rocks.cfState(), true, BigInteger.valueOf(45_000_000_000_000_000L));
            var rewardCalculator = new EpochRewardCalculator(rocks.db(), rocks.cfState(), rocks.cfSnapshot(), true);
            var processor = new EpochBoundaryProcessor(adaPotTracker, rewardCalculator, null, null, 764824073L, null);

            Method method = EpochBoundaryProcessor.class.getDeclaredMethod(
                    "calculateAndStoreRewards", int.class, int.class,
                    com.bloxbean.cardano.yano.ledgerstate.governance.epoch.GovernanceEpochProcessor.GovernanceEpochResult.class);
            method.setAccessible(true);

            InvocationTargetException ex = assertThrows(InvocationTargetException.class,
                    () -> method.invoke(processor, 10, 11, null));
            assertThat(ex.getCause())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No AdaPot found for previous epoch 10");
        }
    }

    @Test
    void adaPotBootstrapOnlyRunsAtShelleyStartEpoch() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            var adaPotTracker = new AdaPotTracker(rocks.db(), rocks.cfState(), true, BigInteger.valueOf(45_000_000_000_000_000L));
            var processor = new EpochBoundaryProcessor(adaPotTracker, null, null, null, 764824073L,
                    EpochRewardCalculator.resolveNetworkConfig(764824073L));

            Method method = EpochBoundaryProcessor.class.getDeclaredMethod("bootstrapAdaPotIfNeeded", int.class);
            method.setAccessible(true);

            method.invoke(processor, 209);
            assertThat(adaPotTracker.getLatestAdaPot(209)).isEmpty();

            method.invoke(processor, 208);
            assertThat(adaPotTracker.getAdaPot(208)).isPresent();
        }
    }

    @Test
    void adaPotBootstrapRunsOnFirstBoundaryForShelleyStartNetworks() throws Exception {
        try (var rocks = TestRocksDBHelper.create(tempDir)) {
            var adaPotTracker = new AdaPotTracker(rocks.db(), rocks.cfState(), true, BigInteger.valueOf(45_000_000_000_000_000L));
            var networkConfig = NetworkConfig.builder()
                    .shelleyStartEpoch(0)
                    .shelleyInitialTreasury(BigInteger.valueOf(1_000L))
                    .shelleyInitialReserves(BigInteger.valueOf(2_000L))
                    .build();
            var processor = new EpochBoundaryProcessor(adaPotTracker, null, null, null, 2L,
                    networkConfig);

            Method method = EpochBoundaryProcessor.class.getDeclaredMethod("bootstrapAdaPotIfNeeded", int.class);
            method.setAccessible(true);

            method.invoke(processor, 1);
            assertThat(adaPotTracker.getAdaPot(0)).isPresent();
        }
    }
}
