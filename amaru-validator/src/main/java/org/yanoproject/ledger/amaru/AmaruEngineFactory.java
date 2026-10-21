package org.yanoproject.ledger.amaru;

import org.yanoproject.api.config.YanoPropertyKeys;
import org.yanoproject.ledger.rules.EngineContext;
import org.yanoproject.ledger.rules.LedgerValidationEngine;
import org.yanoproject.ledger.rules.LedgerValidationEngineFactory;
import org.yanoproject.ledger.rules.LedgerValidationEngines;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseEvaluator;

import java.time.Duration;

/**
 * {@code yano.validation.engine=amaru} (or {@code shadow-engines: [amaru]}): the {@link AmaruTransactionValidator}
 * running both phases, Plutus scripts on Amaru's own machine (ADR-057 §2). {@code amaru-scalus}
 * ({@link AmaruScalusEngineFactory}) is Amaru phase one with the node's Scalus {@code ScriptPhaseEvaluator}.
 *
 * <p>Both read their settings from {@code yano.validation.amaru.*} ({@link #config}):</p>
 * <ul>
 *   <li>{@code pool-size}: 0 (default) means one instance per validation thread.</li>
 *   <li>{@code timeout-ms} (2000), {@code max-abandoned} (2), {@code max-memory-pages} (2048).</li>
 * </ul>
 *
 * <p>The network facts come from the genesis files through {@link AmaruNetworks}; they are resolved on the
 * first validation, because a devnet knows its system start only after startup. The module itself is loaded
 * here, so a missing or incompatible module stops the node at startup.</p>
 */
public final class AmaruEngineFactory implements LedgerValidationEngineFactory {

    /** Callers of the admission engine outside the validation threads: the mempool rebuild worker and block selection. */
    static final int EXTRA_CALLERS = 2;

    @Override
    public String name() {
        return LedgerValidationEngines.AMARU;
    }

    @Override
    public LedgerValidationEngine create(EngineContext context) {
        return create(name(), context, null);
    }

    /**
     * @param scripts the phase-2 evaluator, or null for Amaru's full validation (its own UPLC machine)
     */
    static AmaruTransactionValidator create(String name, EngineContext context, ScriptPhaseEvaluator scripts) {
        return new AmaruTransactionValidator(name, config(context),
                () -> AmaruNetworks.from(context.network().get()), scripts);
    }

    /** Maps {@code yano.validation.amaru.*} to the engine settings. */
    public static AmaruEngineConfig config(EngineContext context) {
        int poolSize = context.intConfig(YanoPropertyKeys.Validation.AMARU_POOL_SIZE, 0);
        if (poolSize < 0) {
            throw new IllegalArgumentException(YanoPropertyKeys.Validation.AMARU_POOL_SIZE + " must be >= 0");
        }
        if (poolSize == 0) {
            // One instance per validation thread, plus the two callers outside that pool (ADR-056 Phase 6): the
            // mempool rebuild worker and the block producer's selection, so neither waits for a busy instance.
            poolSize = Math.max(1, context.validationThreads()) + EXTRA_CALLERS;
        }
        long timeoutMs = context.longConfig(YanoPropertyKeys.Validation.AMARU_TIMEOUT_MS,
                AmaruEngineConfig.DEFAULT_TIMEOUT.toMillis());
        int maxAbandoned = context.intConfig(YanoPropertyKeys.Validation.AMARU_MAX_ABANDONED,
                AmaruEngineConfig.DEFAULT_MAX_ABANDONED);
        int maxMemoryPages = context.intConfig(YanoPropertyKeys.Validation.AMARU_MAX_MEMORY_PAGES,
                AmaruEngineConfig.DEFAULT_MAX_MEMORY_PAGES);
        return new AmaruEngineConfig(poolSize, Duration.ofMillis(timeoutMs), maxAbandoned, maxMemoryPages,
                AmaruEngineConfig.DEFAULT_WORKER_STACK_SIZE);
    }
}
