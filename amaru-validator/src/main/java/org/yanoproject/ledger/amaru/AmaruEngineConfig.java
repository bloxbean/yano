package org.yanoproject.ledger.amaru;

import java.time.Duration;
import java.util.Objects;

/**
 * Engine settings ({@code yano.validation.amaru.*}, ADR-057 §2).
 *
 * @param phase2          who runs the Plutus scripts ({@code phase2}: {@code scalus} | {@code amaru})
 * @param poolSize        instances (each on its own worker thread); {@code pool-size: 0} means one per
 *                        validation thread, which the caller resolves before building this record
 * @param timeout         how long a caller waits for one module call ({@code timeout-ms}, default 2000)
 * @param maxAbandoned    timed-out calls whose worker did not stop that the engine tolerates before it
 *                        turns unhealthy for good ({@code max-abandoned}, default 2)
 * @param maxMemoryPages  the page limit of each instance's linear memory ({@code max-memory-pages}, default
 *                        2048 = 128 MiB)
 * @param workerStackSize the Java stack of each worker thread, in bytes (AOT-compiled wasm calls are Java
 *                        calls; deep Plutus evaluation needs a large stack)
 */
public record AmaruEngineConfig(Phase2Mode phase2, int poolSize, Duration timeout, int maxAbandoned,
                                int maxMemoryPages, long workerStackSize) {

    public static final int DEFAULT_MAX_MEMORY_PAGES = 2048;
    /** Endive's {@code Memory.RUNTIME_MAX_PAGES} (just under 2 GiB). */
    public static final int MAX_MEMORY_PAGES_LIMIT = 32767;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMillis(2000);
    public static final int DEFAULT_MAX_ABANDONED = 2;
    public static final long DEFAULT_WORKER_STACK_SIZE = 256L * 1024 * 1024;

    public AmaruEngineConfig {
        Objects.requireNonNull(phase2, "phase2");
        Objects.requireNonNull(timeout, "timeout");
        if (poolSize < 1) {
            throw new IllegalArgumentException("poolSize must be >= 1 (resolve pool-size 0 first): " + poolSize);
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (maxAbandoned < 1) {
            throw new IllegalArgumentException("maxAbandoned must be >= 1");
        }
        if (maxMemoryPages < 1 || maxMemoryPages > MAX_MEMORY_PAGES_LIMIT) {
            throw new IllegalArgumentException("maxMemoryPages must be in 1..32767: " + maxMemoryPages);
        }
        if (workerStackSize < 0) {
            throw new IllegalArgumentException("workerStackSize must be >= 0");
        }
    }

    /** The ADR-057 defaults with the given phase-2 mode and pool size. */
    public static AmaruEngineConfig defaults(Phase2Mode phase2, int poolSize) {
        return new AmaruEngineConfig(phase2, poolSize, DEFAULT_TIMEOUT, DEFAULT_MAX_ABANDONED,
                DEFAULT_MAX_MEMORY_PAGES, DEFAULT_WORKER_STACK_SIZE);
    }

    public AmaruEngineConfig withTimeout(Duration newTimeout) {
        return new AmaruEngineConfig(phase2, poolSize, newTimeout, maxAbandoned, maxMemoryPages, workerStackSize);
    }

    public AmaruEngineConfig withMaxAbandoned(int newMaxAbandoned) {
        return new AmaruEngineConfig(phase2, poolSize, timeout, newMaxAbandoned, maxMemoryPages, workerStackSize);
    }

    public AmaruEngineConfig withPoolSize(int newPoolSize) {
        return new AmaruEngineConfig(phase2, newPoolSize, timeout, maxAbandoned, maxMemoryPages, workerStackSize);
    }
}
