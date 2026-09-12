package org.yanoproject.runtime.migration;

public interface StartupMigration {
    String id();

    boolean shouldRun(StartupMigrationContext context);

    StartupMigrationResult run(StartupMigrationContext context);
}
