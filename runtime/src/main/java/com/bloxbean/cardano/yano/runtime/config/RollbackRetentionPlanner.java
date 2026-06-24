package com.bloxbean.cardano.yano.runtime.config;

import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;

import java.util.Optional;

/**
 * Applies the high-level rollback-retention setting to the lower-level storage,
 * ledger, and pruning windows.
 */
public final class RollbackRetentionPlanner {
    public static final String ROLLBACK_RETENTION_EPOCHS = YanoPropertyKeys.RollbackRetention.EPOCHS;
    public static final String UTXO_ROLLBACK_WINDOW = YanoPropertyKeys.Utxo.ROLLBACK_WINDOW;
    public static final String ACCOUNT_STATE_EPOCH_BLOCK_DATA_RETENTION_LAG =
            YanoPropertyKeys.AccountState.EPOCH_BLOCK_DATA_RETENTION_LAG;
    public static final String ACCOUNT_STATE_SNAPSHOT_RETENTION_EPOCHS =
            YanoPropertyKeys.AccountState.SNAPSHOT_RETENTION_EPOCHS;
    public static final String ACCOUNT_HISTORY_ROLLBACK_SAFETY_SLOTS =
            YanoPropertyKeys.AccountHistory.ROLLBACK_SAFETY_SLOTS;
    public static final String BLOCK_BODY_PRUNE_DEPTH =
            YanoPropertyKeys.Chain.BLOCK_BODY_PRUNE_DEPTH;

    private RollbackRetentionPlanner() {
    }

    public static RollbackRetentionSettings resolve(
            Optional<Integer> rollbackRetentionEpochs,
            long epochLength,
            double activeSlotsCoeff,
            int utxoRollbackWindow,
            boolean utxoRollbackWindowConfigured,
            int accountStateEpochBlockDataRetentionLag,
            boolean accountStateEpochBlockDataRetentionLagConfigured,
            int accountStateSnapshotRetentionEpochs,
            boolean accountStateSnapshotRetentionEpochsConfigured,
            Optional<Long> accountHistoryRollbackSafetySlots,
            boolean accountHistoryRollbackSafetySlotsConfigured,
            int blockBodyPruneDepth) {

        var unchanged = new RollbackRetentionSettings(
                utxoRollbackWindow,
                accountStateEpochBlockDataRetentionLag,
                accountStateSnapshotRetentionEpochs,
                accountHistoryRollbackSafetySlots,
                blockBodyPruneDepth,
                false,
                0,
                0);

        if (rollbackRetentionEpochs == null || rollbackRetentionEpochs.isEmpty()) {
            return unchanged;
        }

        int retentionEpochs = rollbackRetentionEpochs.get();
        if (retentionEpochs < 0) {
            throw new IllegalArgumentException(ROLLBACK_RETENTION_EPOCHS + " must be >= 0");
        }
        if (retentionEpochs == 0) {
            return unchanged;
        }
        if (epochLength <= 0) {
            throw new IllegalArgumentException("Cannot apply " + ROLLBACK_RETENTION_EPOCHS
                    + " without a positive Shelley epoch length");
        }

        long slotWindow = Math.multiplyExact((long) retentionEpochs, epochLength);
        int slotWindowInt = requireIntRange(slotWindow, UTXO_ROLLBACK_WINDOW);
        int rewardInputLag = requireIntRange((long) retentionEpochs + 1,
                ACCOUNT_STATE_EPOCH_BLOCK_DATA_RETENTION_LAG);
        int snapshotRetention = requireIntRange((long) retentionEpochs + 4,
                ACCOUNT_STATE_SNAPSHOT_RETENTION_EPOCHS);

        int resolvedUtxoRollbackWindow = utxoRollbackWindowConfigured
                ? utxoRollbackWindow
                : Math.max(utxoRollbackWindow, slotWindowInt);
        int resolvedAccountStateLag = accountStateEpochBlockDataRetentionLagConfigured
                ? accountStateEpochBlockDataRetentionLag
                : Math.max(accountStateEpochBlockDataRetentionLag, rewardInputLag);
        int resolvedSnapshotRetention = accountStateSnapshotRetentionEpochsConfigured
                ? accountStateSnapshotRetentionEpochs
                : Math.max(accountStateSnapshotRetentionEpochs, snapshotRetention);
        Optional<Long> resolvedAccountHistorySafety = accountHistoryRollbackSafetySlotsConfigured
                ? accountHistoryRollbackSafetySlots
                : Optional.of(Math.max(accountHistoryRollbackSafetySlots.orElse(0L), slotWindow));

        int resolvedBlockBodyPruneDepth = blockBodyPruneDepth;
        if (blockBodyPruneDepth > 0) {
            int minimumPruneDepth = computeMinimumBlockBodyPruneDepth(
                    retentionEpochs, epochLength, activeSlotsCoeff);
            resolvedBlockBodyPruneDepth = Math.max(blockBodyPruneDepth, minimumPruneDepth);
        }

        return new RollbackRetentionSettings(
                resolvedUtxoRollbackWindow,
                resolvedAccountStateLag,
                resolvedSnapshotRetention,
                resolvedAccountHistorySafety,
                resolvedBlockBodyPruneDepth,
                true,
                retentionEpochs,
                slotWindow);
    }

    public static int computeMinimumBlockBodyPruneDepth(int retentionEpochs,
                                                        long epochLength,
                                                        double activeSlotsCoeff) {
        if (retentionEpochs <= 0) return 0;
        if (epochLength <= 0) {
            throw new IllegalArgumentException("epochLength must be > 0");
        }
        double effectiveActiveSlotsCoeff = activeSlotsCoeff > 0 ? activeSlotsCoeff : 1.0;
        long estimatedBlocksPerEpoch = (long) Math.ceil(epochLength * effectiveActiveSlotsCoeff);
        long safeBlocksPerEpoch = Math.multiplyExact(estimatedBlocksPerEpoch, 2L);
        return requireIntRange(Math.multiplyExact((long) retentionEpochs, safeBlocksPerEpoch),
                BLOCK_BODY_PRUNE_DEPTH);
    }

    private static int requireIntRange(long value, String propertyName) {
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(propertyName + " exceeds integer range: " + value);
        }
        return (int) value;
    }
}
