package com.bloxbean.cardano.yano.runtime.migration;

import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import com.bloxbean.cardano.yano.runtime.utxo.StakeBalanceIndexRebuilder;

public final class StakeBalanceIndexStartupMigration implements StartupMigration {
    public static final String ID = "stake-balance-index";

    private final StakeBalanceIndexRebuilder rebuilder;

    public StakeBalanceIndexStartupMigration() {
        this(new StakeBalanceIndexRebuilder());
    }

    StakeBalanceIndexStartupMigration(StakeBalanceIndexRebuilder rebuilder) {
        this.rebuilder = rebuilder;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean shouldRun(StartupMigrationContext context) {
        if (!context.bool(YanoPropertyKeys.Utxo.ENABLED, true)) return false;
        if (!context.bool(YanoPropertyKeys.AccountState.STAKE_BALANCE_INDEX_ENABLED, true)) return false;

        boolean filtersEnabled = context.bool(YanoPropertyKeys.UtxoFilter.ENABLED, false);
        if (filtersEnabled) {
            return rebuilder.isReady(context.rocksDbSupplier());
        }

        return !rebuilder.isReady(context.rocksDbSupplier());
    }

    @Override
    public StartupMigrationResult run(StartupMigrationContext context) {
        boolean filtersEnabled = context.bool(YanoPropertyKeys.UtxoFilter.ENABLED, false);
        if (filtersEnabled) {
            rebuilder.clearReadyMarker(context.rocksDbSupplier());
            return StartupMigrationResult.changed(
                    "cleared complete stake-balance ready marker because UTXO storage filters are enabled");
        }

        var result = rebuilder.rebuild(context.rocksDbSupplier(), false);
        if (!result.rebuilt()) {
            return StartupMigrationResult.unchanged("stake-balance index already ready");
        }

        return StartupMigrationResult.changed(
                "rebuilt stake-balance index: scanned_utxos=" + result.scannedUtxos()
                        + ", skipped_utxos=" + result.skippedUtxos()
                        + ", credentials=" + result.credentialCount()
                        + ", total_lovelace=" + result.totalLovelace());
    }
}
