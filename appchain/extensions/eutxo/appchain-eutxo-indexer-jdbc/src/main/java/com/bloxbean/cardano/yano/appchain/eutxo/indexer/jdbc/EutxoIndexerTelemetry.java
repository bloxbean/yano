package com.bloxbean.cardano.yano.appchain.eutxo.indexer.jdbc;

import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoIndexCoordinator;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoIndexMetrics;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Bundle-classloader-local handoff from the read-model lifecycle to telemetry products. */
final class EutxoIndexerTelemetry {
    private static final AtomicReference<State> CURRENT = new AtomicReference<>();

    private EutxoIndexerTelemetry() {
    }

    static AutoCloseable install(List<Sample> samples) {
        State state = new State(samples);
        if (!CURRENT.compareAndSet(null, state)) {
            throw new IllegalStateException("EUTxO indexer telemetry is already active");
        }
        return () -> CURRENT.compareAndSet(state, null);
    }

    static List<Sample> samples() {
        State state = CURRENT.get();
        return state == null ? List.of() : state.samples();
    }

    record Sample(
            String chainId,
            EutxoIndexCoordinator coordinator,
            EutxoIndexMetrics metrics,
            Path database
    ) {
        Sample {
            Objects.requireNonNull(chainId, "chainId");
            Objects.requireNonNull(coordinator, "coordinator");
            Objects.requireNonNull(metrics, "metrics");
            Objects.requireNonNull(database, "database");
        }
    }

    private record State(List<Sample> samples) {
        private State {
            samples = List.copyOf(samples);
        }
    }
}
