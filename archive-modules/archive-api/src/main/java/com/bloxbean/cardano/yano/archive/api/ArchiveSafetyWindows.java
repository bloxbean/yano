package com.bloxbean.cardano.yano.archive.api;

/** Validated block windows derived from the Shelley genesis security parameter. */
public record ArchiveSafetyWindows(long securityParam, long rollbackRetentionBlocks,
                                   long archiveFinalityBlocks) {
    public static ArchiveSafetyWindows resolve(long genesisSecurityParam,
                                               Long configuredRollbackBlocks,
                                               Long configuredFinalityBlocks) {
        if (genesisSecurityParam <= 0) {
            throw new IllegalArgumentException("genesis security parameter k must be positive");
        }
        long defaultWindow;
        try {
            defaultWindow = Math.multiplyExact(genesisSecurityParam, 2);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("genesis security parameter k is too large", e);
        }
        long rollback = configuredRollbackBlocks == null ? defaultWindow : configuredRollbackBlocks;
        long finality = configuredFinalityBlocks == null ? defaultWindow : configuredFinalityBlocks;
        if (rollback < genesisSecurityParam) {
            throw new IllegalArgumentException("rollback retention must be at least genesis k=" + genesisSecurityParam);
        }
        if (finality < genesisSecurityParam) {
            throw new IllegalArgumentException("archive finality must be at least genesis k=" + genesisSecurityParam);
        }
        if (finality < rollback) {
            throw new IllegalArgumentException("archive finality must be greater than or equal to rollback retention");
        }
        return new ArchiveSafetyWindows(genesisSecurityParam, rollback, finality);
    }
}
