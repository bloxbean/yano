package org.yanoproject.app.api.validation;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.runtime.validation.ShadowValidationRunner;
import org.yanoproject.runtime.validation.ValidationEngines;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Validation-engine metrics (ADR-056 §7, ADR-057 §2), registered only when the engine API is configured:
 * <ul>
 *   <li>{@code yano_validation_disagreements_total{engine,rule}}: shadow disagreements per shadow engine and
 *       first failing rule ({@code NONE} when admission accepted and the shadow rejected with no rule);</li>
 *   <li>{@code yano_validation_shadow_dropped_total{reason}}: shadow jobs dropped at the live-snapshot cap,
 *       without a snapshot, on a full queue, or cancelled at {@code snapshot-max-age-ms};</li>
 *   <li>{@code yano_validation_engine_healthy{engine}}: 1 while an engine can answer (the Amaru engine turns
 *       0 after {@code max-abandoned} stuck calls).</li>
 * </ul>
 * Tags are bounded: configured engine names and the Haskell rule names.
 */
@ApplicationScoped
public class ValidationEngineMetrics {

    private static final Logger log = Logger.getLogger(ValidationEngineMetrics.class);

    @Inject
    MeterRegistry registry;

    @Inject
    ValidationEnginesSource source;

    void onStart(@Observes StartupEvent ignored) {
        try {
            ValidationEngines engines = engines();
            if (engines != null) {
                register(engines);
            }
        } catch (RuntimeException e) {
            log.warn("Validation engine metrics registration failed");
        }
    }

    private ValidationEngines engines() {
        return source == null ? null : source.engines().orElse(null);
    }

    void register(ValidationEngines engines) {
        List<String> names = new ArrayList<>();
        if (engines.admissionEngine() != null) {
            names.add(engines.admissionEngine().name());
        }
        engines.shadowEngines().forEach(e -> names.add(e.name()));
        for (String name : names) {
            Gauge.builder("yano.validation.engine.healthy", engines,
                            e -> e.status().engineHealth().getOrDefault(name, false) ? 1 : 0)
                    .tag("engine", name)
                    .description("1 while the validation engine can answer, 0 once it failed closed for good")
                    .register(registry);
        }
        ShadowValidationRunner runner = engines.shadowRunner();
        if (runner == null) {
            return;
        }
        List<String> rules = new ArrayList<>();
        for (LedgerRuleName rule : LedgerRuleName.values()) {
            rules.add(rule.name());
        }
        rules.add("NONE");
        for (String engine : runner.engineNames()) {
            for (String rule : rules) {
                FunctionCounter.builder("yano.validation.disagreements.total", runner,
                                r -> r.disagreements(engine, rule))
                        .tag("engine", engine)
                        .tag("rule", rule)
                        .description("Shadow-engine verdicts that differ from the admission engine")
                        .register(registry);
            }
        }
        dropped(runner, "cap", s -> s.droppedCap());
        dropped(runner, "unavailable", s -> s.droppedUnavailable());
        dropped(runner, "queue", s -> s.droppedQueueFull());
        dropped(runner, "expired", s -> s.expired());
    }

    private void dropped(ShadowValidationRunner runner, String reason,
                         ToDoubleFunction<ShadowValidationRunner.Stats> value) {
        FunctionCounter.builder("yano.validation.shadow.dropped.total", runner, r -> value.applyAsDouble(r.stats()))
                .tag("reason", reason)
                .description("Shadow validations not compared, by bounded reason")
                .register(registry);
    }
}
