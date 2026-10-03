package org.yanoproject.runtime.producer;

import org.yanoproject.api.account.AccountStateReadStore;
import org.yanoproject.api.config.YanoConfig;
import org.yanoproject.runtime.blockproducer.FixedStakeDataProvider;
import org.yanoproject.runtime.blockproducer.GenesisStakeDataProvider;
import org.yanoproject.runtime.blockproducer.LedgerStakeDataProvider;
import org.yanoproject.runtime.blockproducer.StakeDataProvider;
import org.yanoproject.runtime.blockproducer.YaciStoreStakeDataProvider;
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

    /**
     * The live slot-leader stake source: a configured yaci-store URL; in dev mode the fixed single-pool stake;
     * otherwise Yano's own account state.
     *
     * @param accountState the account-state read store, or {@code null} when account state is disabled
     * @throws IllegalStateException outside dev mode when neither a URL nor account state is available
     */
    public static StakeDataProvider createLiveSlotLeaderProvider(YanoConfig config,
                                                                 AccountStateReadStore accountState) {
        Objects.requireNonNull(config, "config");
        if (hasStakeDataProviderUrl(config)) {
            log.info("Using yaci-store stake data from {}", config.getStakeDataProviderUrl());
            return new YaciStoreStakeDataProvider(config.getStakeDataProviderUrl());
        }
        if (config.isDevMode()) {
            log.info("Using FixedStakeDataProvider (sigma=1.0) — devnet single-pool mode");
            return new FixedStakeDataProvider();
        }
        if (accountState != null) {
            log.info("Using stake data from the account-state stake snapshots");
            return new LedgerStakeDataProvider(accountState);
        }
        throw new IllegalStateException("Slot-leader mode needs a stake source: enable account state "
                + "(yano.account-state.enabled) or set yano.block-producer.stake-data-provider-url");
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
