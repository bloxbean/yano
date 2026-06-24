package com.bloxbean.cardano.yano.runtime.producer;

import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.runtime.blockproducer.FixedStakeDataProvider;
import com.bloxbean.cardano.yano.runtime.blockproducer.GenesisStakeDataProvider;
import com.bloxbean.cardano.yano.runtime.blockproducer.StakeDataProvider;
import com.bloxbean.cardano.yano.runtime.blockproducer.YaciStoreStakeDataProvider;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Selects stake-data providers for slot-leader producer strategies.
 */
@Slf4j
public final class StakeDataProviderFactory {
    private StakeDataProviderFactory() {
    }

    public static StakeDataProvider createLiveSlotLeaderProvider(YanoConfig config) {
        Objects.requireNonNull(config, "config");
        if (hasStakeDataProviderUrl(config)) {
            return new YaciStoreStakeDataProvider(config.getStakeDataProviderUrl());
        }

        log.info("Using FixedStakeDataProvider (sigma=1.0) — devnet single-pool mode");
        return new FixedStakeDataProvider();
    }

    public static StakeDataProvider createGenesisTimeTravelProvider(Path shelleyGenesisFile,
                                                                    String poolHash) throws IOException {
        Objects.requireNonNull(shelleyGenesisFile, "shelleyGenesisFile");
        StakeDataProvider stakeDataProvider = new GenesisStakeDataProvider(shelleyGenesisFile);
        BigInteger poolStake = stakeDataProvider.getPoolStake(poolHash, 0);
        BigInteger totalStake = stakeDataProvider.getTotalStake(0);
        if (poolStake == null || poolStake.signum() <= 0
                || totalStake == null || totalStake.signum() <= 0) {
            throw new IllegalStateException("Pool " + poolHash
                    + " has no active genesis stake; check Shelley genesis staking/pool configuration");
        }

        log.info("Using genesis stake data for past-time-travel slot leader: poolStake={}, totalStake={}",
                poolStake, totalStake);
        return stakeDataProvider;
    }

    static boolean hasStakeDataProviderUrl(YanoConfig config) {
        return config.getStakeDataProviderUrl() != null
                && !config.getStakeDataProviderUrl().isBlank();
    }
}
