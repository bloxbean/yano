package com.bloxbean.cardano.yano.runtime.migration;

public record StartupMigrationResult(boolean changed, String message) {
    public static StartupMigrationResult changed(String message) {
        return new StartupMigrationResult(true, message);
    }

    public static StartupMigrationResult unchanged(String message) {
        return new StartupMigrationResult(false, message);
    }
}
