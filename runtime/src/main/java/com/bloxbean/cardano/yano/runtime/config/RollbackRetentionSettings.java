package com.bloxbean.cardano.yano.runtime.config;

import java.util.Optional;

/**
 * Resolved rollback-retention windows consumed by runtime subsystems.
 */
public record RollbackRetentionSettings(
        int utxoRollbackWindow,
        int accountStateEpochBlockDataRetentionLag,
        int accountStateSnapshotRetentionEpochs,
        Optional<Long> accountHistoryRollbackSafetySlots,
        int blockBodyPruneDepth,
        boolean umbrellaEnabled,
        int retentionEpochs,
        long slotWindow) {
}
