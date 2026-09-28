package org.yanoproject.app.api.validation;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;
import org.yanoproject.ledger.rules.LedgerValidationEngines;
import org.yanoproject.runtime.validation.ValidationEngines;

/**
 * Readiness of the validation engines (ADR-057 §2): it matters only when {@code amaru} is the admission or a
 * shadow engine, since only that engine can turn unhealthy (after {@code max-abandoned} stuck calls it fails
 * every request closed until restart).
 *
 * <ul>
 *   <li>No Amaru engine configured: UP, with the engine name.</li>
 *   <li>Admission engine unhealthy: DOWN (every admission is rejected).</li>
 *   <li>Only a shadow engine unhealthy: UP with {@code shadowUnhealthy} set; admission is unaffected, and the
 *       {@code yano_validation_engine_healthy} metric alerts.</li>
 * </ul>
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
        ValidationEngines.Status status = engines.status();
        builder.withData("engine", status.engine())
                .withData("shadowEngines", String.join(",", status.shadowEngines()));
        if (!engines.uses(LedgerValidationEngines.AMARU)) {
            return builder.up().build();
        }
        status.engineHealth().forEach((name, healthy) -> builder.withData(name + ".healthy", healthy));
        boolean shadowUnhealthy = status.engineHealth().entrySet().stream()
                .anyMatch(e -> !e.getKey().equals(status.engine()) && !e.getValue());
        builder.withData("shadowUnhealthy", shadowUnhealthy);
        return status.admissionHealthy() ? builder.up().build() : builder.down().build();
    }
}
