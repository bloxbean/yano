package org.yanoproject.runtime.validation;

import lombok.extern.slf4j.Slf4j;
import org.yanoproject.ledger.rules.EngineContext;
import org.yanoproject.ledger.rules.EpochProtocolParamsSupplier;
import org.yanoproject.ledger.rules.LedgerValidationEngine;
import org.yanoproject.ledger.rules.LedgerValidationEngines;
import org.yanoproject.ledger.rules.NetworkParameters;
import org.yanoproject.ledger.rules.SlotConfigSupplier;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseEvaluator;
import org.yanoproject.runtime.validation.shadowsync.ShadowSyncValidator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

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
     * @param shadowSyncHealth health per shadow-sync engine (ADR-056 Phase 7a); reported, never gating readiness
     */
    public record Status(String engine, List<String> shadowEngines, boolean admissionHealthy,
                         Map<String, Boolean> engineHealth, ShadowValidationRunner.Stats shadow,
                         Map<String, Boolean> shadowSyncHealth) {
    }

    /** Engine-context key the shadow-sync engines are created with ({@code java} is observe-only there). */
    static final String JAVA_EXPERIMENTAL = "yano.validation.java-engine.experimental";

    private final ValidationEngineSettings settings;
    private final LedgerValidationEngine admission;
    private final List<LedgerValidationEngine> shadows;
    private final ValidationEnvFactory envFactory;
    private final ShadowValidationRunner shadowRunner;
    private final List<LedgerValidationEngine> shadowSyncEngines;
    private volatile ShadowSyncValidator shadowSync;

    /**
     * @param admission    the admission engine, or {@code null} when admission stays on the legacy validator
     *                     ({@code engine: scalus} with shadow engines)
     * @param shadowRunner the runner for {@code shadows}, or {@code null} when there are none
     */
    public ValidationEngines(ValidationEngineSettings settings, LedgerValidationEngine admission,
                             List<LedgerValidationEngine> shadows, ValidationEnvFactory envFactory,
                             ShadowValidationRunner shadowRunner) {
        this(settings, admission, shadows, envFactory, shadowRunner, List.of());
    }

    /**
     * @param shadowSyncEngines the engines shadow sync runs (ADR-056 Phase 7a), their own instances; empty when
     *                          shadow sync is off
     */
    public ValidationEngines(ValidationEngineSettings settings, LedgerValidationEngine admission,
                             List<LedgerValidationEngine> shadows, ValidationEnvFactory envFactory,
                             ShadowValidationRunner shadowRunner, List<LedgerValidationEngine> shadowSyncEngines) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.admission = admission;
        this.shadowSyncEngines = List.copyOf(shadowSyncEngines);
        if (admission == null && shadows.isEmpty() && this.shadowSyncEngines.isEmpty()) {
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
            // Shadow sync (ADR-056 Phase 7a) gets its own instances. It only observes synced blocks, so the java
            // engine needs no experimental opt-in there (it still needs one for admission and admission shadows).
            List<LedgerValidationEngine> shadowSync = new ArrayList<>();
            if (settings.shadowSync()) {
                EngineContext observeOnly = new ObserveOnlyContext(context);
                for (String name : settings.shadowSyncSettings().engines()) {
                    shadowSync.add(create(registry, name, observeOnly, created));
                }
            }
            ShadowValidationRunner runner = shadows.isEmpty() ? null
                    : new ShadowValidationRunner(shadows, settings.shadowDumpDir(), settings.snapshotMaxAgeMs(),
                    ShadowValidationRunner.DEFAULT_THREADS, ShadowValidationRunner.DEFAULT_QUEUE_CAPACITY);
            log.info("Validation engines: admission={}, shadow={}, shadow-dump-dir={}, snapshot-max-age-ms={}, "
                            + "shadow-sync={}",
                    admission != null ? admission.name() : LEGACY, shadows.stream().map(LedgerValidationEngine::name).toList(),
                    settings.shadowDumpDir(), settings.snapshotMaxAgeMs(),
                    shadowSync.stream().map(LedgerValidationEngine::name).toList());
            return new ValidationEngines(settings, admission, shadows, envFactory, runner, shadowSync);
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

    /** @return the engines shadow sync runs (ADR-056 Phase 7a); empty when shadow sync is off */
    public List<LedgerValidationEngine> shadowSyncEngines() {
        return shadowSyncEngines;
    }

    /** Installs the running shadow-sync validator; {@link #close()} closes it before the engines. */
    public synchronized void attachShadowSync(ShadowSyncValidator validator) {
        ShadowSyncValidator previous = this.shadowSync;
        if (previous != null && previous != validator) {
            previous.close();
        }
        this.shadowSync = validator;
    }

    /** @return the running shadow-sync validator, or {@code null} when shadow sync is off or not started */
    public ShadowSyncValidator shadowSync() {
        return shadowSync;
    }

    /**
     * @return true when these engines take part in admission (an engine-API admission engine or shadow engines);
     *         false when they exist only for shadow sync, which leaves admission on the legacy path
     */
    public boolean affectsAdmission() {
        return admission != null || !shadows.isEmpty();
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
        Map<String, Boolean> syncHealth = new LinkedHashMap<>();
        shadowSyncEngines.forEach(s -> syncHealth.put(s.name(), s.isHealthy()));
        return new Status(admissionEngineName(), shadows.stream().map(LedgerValidationEngine::name).toList(),
                admissionHealthy(), Map.copyOf(health), shadowRunner != null ? shadowRunner.stats() : null,
                Map.copyOf(syncHealth));
    }

    @Override
    public void close() {
        ShadowSyncValidator sync = shadowSync;
        if (sync != null) {
            sync.close();
        }
        if (shadowRunner != null) {
            shadowRunner.close();
        }
        closeQuietly(admission);
        shadows.forEach(ValidationEngines::closeQuietly);
        shadowSyncEngines.forEach(ValidationEngines::closeQuietly);
    }

    /** The node's context with the java engine's experimental opt-in answered (shadow sync only observes). */
    private record ObserveOnlyContext(EngineContext delegate) implements EngineContext {
        @Override
        public Optional<String> config(String key) {
            return JAVA_EXPERIMENTAL.equals(key) ? Optional.of("true") : delegate.config(key);
        }

        @Override
        public Supplier<NetworkParameters> network() {
            return delegate.network();
        }

        @Override
        public EpochProtocolParamsSupplier protocolParams() {
            return delegate.protocolParams();
        }

        @Override
        public SlotConfigSupplier slotConfig() {
            return delegate.slotConfig();
        }

        @Override
        public LongSupplier currentSlot() {
            return delegate.currentSlot();
        }

        @Override
        public ScriptPhaseEvaluator scriptPhaseEvaluator() {
            return delegate.scriptPhaseEvaluator();
        }

        @Override
        public int validationThreads() {
            return delegate.validationThreads();
        }
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
