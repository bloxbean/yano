package org.yanoproject.runtime.validation;

import lombok.extern.slf4j.Slf4j;
import org.yanoproject.ledger.rules.EngineContext;
import org.yanoproject.ledger.rules.LedgerValidationEngine;
import org.yanoproject.ledger.rules.LedgerValidationEngines;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The configured validation engines (ADR-056 §7): the admission engine, the shadow engines and their
 * runner. Created at startup only when {@link ValidationEngineSettings#usesEngineApi()}; otherwise the legacy
 * {@code TransactionValidator} path runs unchanged.
 */
@Slf4j
public final class ValidationEngines implements AutoCloseable {

    /** Status name of the legacy admission validator. */
    public static final String LEGACY = "scalus (legacy)";

    /**
     * Health and counters for health checks and metrics.
     *
     * @param engine           the admission engine
     * @param shadowEngines    the shadow engines
     * @param admissionHealthy whether the admission engine can answer (false fails every admission closed)
     * @param engineHealth     health per engine name
     * @param shadow           shadow counters, or {@code null} without shadow engines
     */
    public record Status(String engine, List<String> shadowEngines, boolean admissionHealthy,
                         Map<String, Boolean> engineHealth, ShadowValidationRunner.Stats shadow) {
    }

    private final ValidationEngineSettings settings;
    private final LedgerValidationEngine admission;
    private final List<LedgerValidationEngine> shadows;
    private final ValidationEnvFactory envFactory;
    private final ShadowValidationRunner shadowRunner;

    /**
     * @param admission    the admission engine, or {@code null} when admission stays on the legacy validator
     *                     ({@code engine: scalus} with shadow engines)
     * @param shadowRunner the runner for {@code shadows}, or {@code null} when there are none
     */
    public ValidationEngines(ValidationEngineSettings settings, LedgerValidationEngine admission,
                             List<LedgerValidationEngine> shadows, ValidationEnvFactory envFactory,
                             ShadowValidationRunner shadowRunner) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.admission = admission;
        if (admission == null && shadows.isEmpty()) {
            throw new IllegalArgumentException("legacy admission without shadow engines needs no ValidationEngines");
        }
        this.shadows = List.copyOf(shadows);
        this.envFactory = Objects.requireNonNull(envFactory, "envFactory");
        if (!this.shadows.isEmpty() && shadowRunner == null) {
            throw new IllegalArgumentException("shadow engines need a runner");
        }
        this.shadowRunner = shadowRunner;
    }

    /**
     * Creates the configured engines through their factories.
     *
     * @throws ValidationEngineConfigurationException when an engine is unavailable or cannot be created
     */
    public static ValidationEngines create(ValidationEngineSettings settings, LedgerValidationEngines registry,
                                           EngineContext context, ValidationEnvFactory envFactory) {
        List<AutoCloseable> created = new ArrayList<>();
        try {
            // engine: scalus keeps the legacy validator for admission; shadows never change it (ADR-056 §7).
            LedgerValidationEngine admission = settings.engineAdmission()
                    ? create(registry, settings.engine(), context, created) : null;
            List<LedgerValidationEngine> shadows = new ArrayList<>();
            for (String name : settings.shadowEngines()) {
                shadows.add(create(registry, name, context, created));
            }
            ShadowValidationRunner runner = shadows.isEmpty() ? null
                    : new ShadowValidationRunner(shadows, settings.shadowDumpDir(), settings.snapshotMaxAgeMs(),
                    ShadowValidationRunner.DEFAULT_THREADS, ShadowValidationRunner.DEFAULT_QUEUE_CAPACITY);
            if (settings.shadowSync()) {
                log.warn("yano.validation.shadow-sync=true is accepted but not active yet: shadow validation of "
                        + "synced blocks arrives with ADR-056 Phase 7");
            }
            log.info("Validation engines: admission={}, shadow={}, shadow-dump-dir={}, snapshot-max-age-ms={}",
                    admission != null ? admission.name() : LEGACY, shadows.stream().map(LedgerValidationEngine::name).toList(),
                    settings.shadowDumpDir(), settings.snapshotMaxAgeMs());
            return new ValidationEngines(settings, admission, shadows, envFactory, runner);
        } catch (RuntimeException e) {
            created.forEach(ValidationEngines::closeQuietly);
            if (e instanceof ValidationEngineConfigurationException configuration) {
                throw configuration;
            }
            throw new ValidationEngineConfigurationException(e.getMessage(), e);
        }
    }

    private static LedgerValidationEngine create(LedgerValidationEngines registry, String name, EngineContext context,
                                                 List<AutoCloseable> created) {
        LedgerValidationEngine engine;
        try {
            engine = registry.factory(name).create(context);
        } catch (IllegalStateException e) {
            throw new ValidationEngineConfigurationException(e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new ValidationEngineConfigurationException("Cannot create validation engine '" + name + "': "
                    + e.getMessage(), e);
        }
        if (engine instanceof AutoCloseable closeable) {
            created.add(closeable);
        }
        return engine;
    }

    public ValidationEngineSettings settings() {
        return settings;
    }

    /** @return the admission engine, or {@code null} when admission uses the legacy validator */
    public LedgerValidationEngine admissionEngine() {
        return admission;
    }

    /** @return true when admission stays on the legacy validator and only shadow engines run */
    public boolean legacyAdmission() {
        return admission == null;
    }

    /** @return the admission engine's name, {@link #LEGACY} for the legacy validator */
    public String admissionEngineName() {
        return admission != null ? admission.name() : LEGACY;
    }

    public List<LedgerValidationEngine> shadowEngines() {
        return shadows;
    }

    public ValidationEnvFactory envFactory() {
        return envFactory;
    }

    /** @return the shadow runner, or {@code null} without shadow engines */
    public ShadowValidationRunner shadowRunner() {
        return shadowRunner;
    }

    /** @return whether the admission engine reports healthy (the legacy validator always does) */
    public boolean admissionHealthy() {
        return admission == null || admission.isHealthy();
    }

    /** @return true when the admission or a shadow engine is {@code name} */
    public boolean uses(String name) {
        return settings.uses(name);
    }

    public Status status() {
        Map<String, Boolean> health = new LinkedHashMap<>();
        if (admission != null) {
            health.put(admission.name(), admission.isHealthy());
        }
        shadows.forEach(s -> health.put(s.name(), s.isHealthy()));
        return new Status(admissionEngineName(), shadows.stream().map(LedgerValidationEngine::name).toList(),
                admissionHealthy(), Map.copyOf(health), shadowRunner != null ? shadowRunner.stats() : null);
    }

    @Override
    public void close() {
        if (shadowRunner != null) {
            shadowRunner.close();
        }
        closeQuietly(admission);
        shadows.forEach(ValidationEngines::closeQuietly);
    }

    private static void closeQuietly(Object engine) {
        if (engine instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.debug("Closing a validation engine failed: {}", e.toString());
            }
        }
    }
}
