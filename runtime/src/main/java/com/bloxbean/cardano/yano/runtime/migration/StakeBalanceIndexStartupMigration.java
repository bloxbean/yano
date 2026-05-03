package com.bloxbean.cardano.yano.runtime.migration;

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
        if (!context.bool("yano.utxo.enabled", true)) return false;
        if (!context.bool("yano.account.stake-balance-index-enabled", true)) return false;

        boolean filtersEnabled = context.bool("yano.filters.utxo.enabled", false);
        if (filtersEnabled) {
            return rebuilder.isReady(context.chainState());
        }

        return !rebuilder.isReady(context.chainState());
    }

    @Override
    public StartupMigrationResult run(StartupMigrationContext context) {
        boolean filtersEnabled = context.bool("yano.filters.utxo.enabled", false);
        if (filtersEnabled) {
            rebuilder.clearReadyMarker(context.chainState());
            return StartupMigrationResult.changed(
                    "cleared complete stake-balance ready marker because UTXO storage filters are enabled");
        }

        var result = rebuilder.rebuild(context.chainState(), false);
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
