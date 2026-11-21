package org.yanoproject.app.api.validation;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;
import org.yanoproject.ledger.rules.LedgerValidationEngine;
import org.yanoproject.runtime.validation.ValidationEngines;
import org.yanoproject.runtime.validation.shadowsync.ShadowSyncReport;
import org.yanoproject.runtime.validation.shadowsync.ShadowSyncValidator;

/**
 * The validation engines in the readiness report (ADR-056 §7, ADR-062 D4): the admission and shadow engine names and,
 * when shadow sync (ADR-056 Phase 7a) runs, its engines and counters. Always UP: no engine can turn unhealthy, and
 * shadow-sync findings are reported, never gating readiness.
 */
@Readiness
@ApplicationScoped
public class ValidationEngineHealthCheck implements HealthCheck {

    static final String NAME = "validation-engine";

    @Inject
    ValidationEnginesSource source;

    @Override
    public HealthCheckResponse call() {
        ValidationEngines engines = source == null ? null : source.engines().orElse(null);
        return check(engines);
    }

    static HealthCheckResponse check(ValidationEngines engines) {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named(NAME);
        if (engines == null) {
            return builder.up().withData("engine", "scalus (legacy)").build();
        }
        builder.withData("engine", engines.admissionEngineName())
                .withData("shadowEngines", String.join(",",
                        engines.shadowEngines().stream().map(LedgerValidationEngine::name).toList()));
        ShadowSyncValidator sync = engines.shadowSync();
        if (sync != null) {
            ShadowSyncValidator.Status syncStatus = sync.status();
            ShadowSyncReport.Stats stats = syncStatus.report();
            builder.withData("shadowSync.engines", String.join(",", syncStatus.engines()))
                    .withData("shadowSync.blocksValidated", stats.blocksValidated())
                    .withData("shadowSync.disagreements", stats.disagreedTotal())
                    .withData("shadowSync.engineFailures", stats.engineFailuresTotal())
                    .withData("shadowSync.blockFailures", stats.blockFailures())
                    .withData("shadowSync.inFlight", syncStatus.inFlight());
        }
        return builder.up().build();
    }
}
