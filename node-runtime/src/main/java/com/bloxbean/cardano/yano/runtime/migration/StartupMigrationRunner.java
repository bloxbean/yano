package com.bloxbean.cardano.yano.runtime.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public final class StartupMigrationRunner {
    private static final Logger log = LoggerFactory.getLogger(StartupMigrationRunner.class);

    public void run(StartupMigrationContext context, List<StartupMigration> migrations) {
        if (context == null || migrations == null || migrations.isEmpty()) return;

        for (StartupMigration migration : migrations) {
            if (migration == null) continue;
            if (!migration.shouldRun(context)) {
                log.debug("Startup migration '{}' is not required", migration.id());
                continue;
            }

            long start = System.currentTimeMillis();
            log.info("Startup migration '{}' started", migration.id());
            try {
                StartupMigrationResult result = migration.run(context);
                long elapsed = System.currentTimeMillis() - start;
                String message = result != null ? result.message() : "";
                log.info("Startup migration '{}' completed in {} ms: {}", migration.id(), elapsed, message);
            } catch (RuntimeException e) {
                log.error("Startup migration '{}' failed: {}", migration.id(), e.getMessage(), e);
                throw e;
            } catch (Exception e) {
                log.error("Startup migration '{}' failed: {}", migration.id(), e.getMessage(), e);
                throw new IllegalStateException("Startup migration failed: " + migration.id(), e);
            }
        }
    }
}
