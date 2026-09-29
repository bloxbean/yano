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
import org.yanoproject.runtime.validation.shadowsync.ShadowSyncReport;
import org.yanoproject.runtime.validation.shadowsync.ShadowSyncValidator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 *   <li>Shadow sync (ADR-056 Phase 7a), when on: {@code yano_validation_shadow_sync_txs_total{engine,pv,outcome}}
 *       (outcome {@code validated|agreed|disagreed|engine_failure}, pv 9-15),
 *       {@code yano_validation_shadow_sync_blocks_total{outcome}} ({@code validated|failed|empty|pre_conway}),
 *       {@code yano_validation_shadow_sync_block_rule_violations_total{check}} ({@code ref_scripts|ex_units|body}),
 *       {@code yano_validation_shadow_sync_backpressure_wait_ms_total}, the
 *       {@code yano_validation_shadow_sync_in_flight} gauge and {@code yano_validation_shadow_sync_engine_healthy{engine}}
 *       (reported only, never gating readiness).</li>
 * </ul>
 * Tags are bounded: configured engine names and the Haskell rule names.
 */
@ApplicationScoped
public class ValidationEngineMetrics {

    private static final Logger log = Logger.getLogger(ValidationEngineMetrics.class);
    /** The highest protocol major with shadow-sync counters registered up front (headroom past the current 11). */
    private static final int LAST_EXPORTED_MAJOR = 15;

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
        ShadowSyncValidator sync = engines.shadowSync();
        if (sync != null) {
            registerShadowSync(engines, sync);
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

    private void registerShadowSync(ValidationEngines engines, ShadowSyncValidator sync) {
        for (String engine : sync.status().engines()) {
            Gauge.builder("yano.validation.shadow.sync.engine.healthy", engines,
                            e -> e.status().shadowSyncHealth().getOrDefault(engine, false) ? 1 : 0)
                    .tag("engine", engine)
                    .description("1 while the shadow-sync engine can answer (never gates readiness)")
                    .register(registry);
        }
        for (String engine : sync.status().engines()) {
            for (int pv = ShadowSyncValidator.FIRST_CONWAY_MAJOR; pv <= LAST_EXPORTED_MAJOR; pv++) {
                int major = pv;
                syncTxs(sync, engine, major, "validated", c -> c.validated());
                syncTxs(sync, engine, major, "agreed", c -> c.agreed());
                syncTxs(sync, engine, major, "disagreed", c -> c.disagreed());
                syncTxs(sync, engine, major, "engine_failure", c -> c.engineFailures());
            }
        }
        syncBlocks(sync, "validated", s -> s.blocksValidated());
        syncBlocks(sync, "failed", s -> s.blockFailures());
        syncBlocks(sync, "empty", s -> s.blocksEmpty());
        syncBlocks(sync, "pre_conway", s -> s.blocksSkippedPreConway());
        blockRule(sync, "ref_scripts", st -> st.refScriptViolations());
        blockRule(sync, "ex_units", st -> st.exUnitsViolations());
        blockRule(sync, "body", st -> st.bodyViolations());
        FunctionCounter.builder("yano.validation.shadow.sync.backpressure.wait.ms.total", sync,
                        v -> v.status().report().backpressureWaitMillis())
                .description("Time block application waited for shadow-sync validation")
                .register(registry);
        Gauge.builder("yano.validation.shadow.sync.in.flight", sync, v -> v.status().inFlight())
                .description("Synced blocks queued or being validated by shadow sync")
                .register(registry);
    }

    private void blockRule(ShadowSyncValidator sync, String check, ToDoubleFunction<ShadowSyncReport.Stats> value) {
        FunctionCounter.builder("yano.validation.shadow.sync.block.rule.violations.total", sync,
                        v -> value.applyAsDouble(v.status().report()))
                .tag("check", check)
                .description("Synced blocks failing a BBODY rule: ref_scripts (BodyRefScriptsSizeTooBig), ex_units "
                        + "(TooManyExUnits), body (WrongBlockBodySizeBBODY / InvalidBodyHashBBODY)")
                .register(registry);
    }

    private void syncTxs(ShadowSyncValidator sync, String engine, int pv, String outcome,
                         ToDoubleFunction<ShadowSyncReport.Counts> value) {
        FunctionCounter.builder("yano.validation.shadow.sync.txs.total", sync, v -> {
                    Map<Integer, ShadowSyncReport.Counts> byPv = v.status().report().byEngine().get(engine);
                    ShadowSyncReport.Counts counts = byPv != null ? byPv.get(pv) : null;
                    return counts != null ? value.applyAsDouble(counts) : 0;
                })
                .tag("engine", engine)
                .tag("pv", Integer.toString(pv))
                .tag("outcome", outcome)
                .description("Synced transactions validated by shadow sync, by engine, protocol version and outcome")
                .register(registry);
    }

    private void syncBlocks(ShadowSyncValidator sync, String outcome, ToDoubleFunction<ShadowSyncReport.Stats> value) {
        FunctionCounter.builder("yano.validation.shadow.sync.blocks.total", sync,
                        v -> value.applyAsDouble(v.status().report()))
                .tag("outcome", outcome)
                .description("Synced blocks seen by shadow sync, by outcome")
                .register(registry);
    }

    private void dropped(ShadowValidationRunner runner, String reason,
                         ToDoubleFunction<ShadowValidationRunner.Stats> value) {
        FunctionCounter.builder("yano.validation.shadow.dropped.total", runner, r -> value.applyAsDouble(r.stats()))
                .tag("reason", reason)
                .description("Shadow validations not compared, by bounded reason")
                .register(registry);
    }
}
