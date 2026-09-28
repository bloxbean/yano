package org.yanoproject.ledger.amaru;

import org.yanoproject.api.config.YanoPropertyKeys;
import org.yanoproject.ledger.rules.EngineContext;
import org.yanoproject.ledger.rules.LedgerValidationEngine;
import org.yanoproject.ledger.rules.LedgerValidationEngineFactory;
import org.yanoproject.ledger.rules.LedgerValidationEngines;

import java.time.Duration;
import java.util.Locale;

/**
 * {@code yano.validation.engine=amaru} (or {@code shadow-engines: [amaru]}): the
 * {@link AmaruTransactionValidator} with its settings from {@code yano.validation.amaru.*} (ADR-057 §2).
 *
 * <ul>
 *   <li>{@code phase2}: {@code scalus} (default) → {@link Phase2Mode#SCALUS} with the node's
 *       {@code ScriptPhaseEvaluator}; {@code amaru} (or {@code full}) → {@link Phase2Mode#FULL}.</li>
 *   <li>{@code pool-size}: 0 (default) means one instance per validation thread.</li>
 *   <li>{@code timeout-ms} (2000), {@code max-abandoned} (2), {@code max-memory-pages} (2048).</li>
 * </ul>
 *
 * <p>The network facts come from the genesis files through {@link AmaruNetworks}; they are resolved on the
 * first validation, because a devnet knows its system start only after startup. The module itself is loaded
 * here, so a missing or incompatible module stops the node at startup.</p>
 */
public final class AmaruEngineFactory implements LedgerValidationEngineFactory {

    @Override
    public String name() {
        return LedgerValidationEngines.AMARU;
    }

    @Override
    public LedgerValidationEngine create(EngineContext context) {
        AmaruEngineConfig config = config(context);
        if (config.phase2() == Phase2Mode.SCALUS && context.scriptPhaseEvaluator() == null) {
            throw new IllegalStateException("yano.validation.amaru.phase2=scalus needs the Scalus phase-2 evaluator "
                    + "(scalus-bridge), which is not available; set yano.validation.amaru.phase2=amaru");
        }
        return new AmaruTransactionValidator(config, () -> AmaruNetworks.from(context.network().get()),
                config.phase2() == Phase2Mode.SCALUS ? context.scriptPhaseEvaluator() : null);
    }

    /** Maps {@code yano.validation.amaru.*} to the engine settings. */
    public static AmaruEngineConfig config(EngineContext context) {
        String phase2 = context.config(YanoPropertyKeys.Validation.AMARU_PHASE2).map(String::trim)
                .filter(v -> !v.isEmpty()).orElse("scalus").toLowerCase(Locale.ROOT);
        Phase2Mode mode = switch (phase2) {
            case "scalus" -> Phase2Mode.SCALUS;
            case "amaru", "full" -> Phase2Mode.FULL;
            default -> throw new IllegalArgumentException(YanoPropertyKeys.Validation.AMARU_PHASE2
                    + " must be scalus or amaru: " + phase2);
        };
        int poolSize = context.intConfig(YanoPropertyKeys.Validation.AMARU_POOL_SIZE, 0);
        if (poolSize < 0) {
            throw new IllegalArgumentException(YanoPropertyKeys.Validation.AMARU_POOL_SIZE + " must be >= 0");
        }
        if (poolSize == 0) {
            poolSize = Math.max(1, context.validationThreads());
        }
        long timeoutMs = context.longConfig(YanoPropertyKeys.Validation.AMARU_TIMEOUT_MS,
                AmaruEngineConfig.DEFAULT_TIMEOUT.toMillis());
        int maxAbandoned = context.intConfig(YanoPropertyKeys.Validation.AMARU_MAX_ABANDONED,
                AmaruEngineConfig.DEFAULT_MAX_ABANDONED);
        int maxMemoryPages = context.intConfig(YanoPropertyKeys.Validation.AMARU_MAX_MEMORY_PAGES,
                AmaruEngineConfig.DEFAULT_MAX_MEMORY_PAGES);
        return new AmaruEngineConfig(mode, poolSize, Duration.ofMillis(timeoutMs), maxAbandoned, maxMemoryPages,
                AmaruEngineConfig.DEFAULT_WORKER_STACK_SIZE);
    }
}
