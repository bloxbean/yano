package org.yanoproject.runtime.config;

/**
 * Resolved rollback-retention windows consumed by runtime subsystems.
 */
public record RollbackRetentionSettings(
        int utxoRollbackWindow,
        int accountStateEpochBlockDataRetentionLag,
        int accountStateSnapshotRetentionEpochs,
        int blockBodyPruneDepth,
        boolean umbrellaEnabled,
        int retentionEpochs,
        long slotWindow) {
}
