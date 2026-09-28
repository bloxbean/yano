package org.yanoproject.ledger.conformance.engines;

import org.yanoproject.ledger.conformance.runner.ConformanceEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The engines of the Phase 2 baseline, in report order. The Amaru reference engine is compiled only in
 * {@code -PwithAmaru=true} builds ({@code src/amaruTest}), so it is looked up by name.
 */
public final class BaselineEngines {

    public static final String AMARU = "amaru";
    private static final String AMARU_ADAPTER = "org.yanoproject.ledger.conformance.engines.AmaruReferenceEngine";

    private BaselineEngines() {
    }

    /** @return the Amaru reference engine, when the build includes it */
    public static Optional<ConformanceEngine> amaru() {
        try {
            Class<?> type = Class.forName(AMARU_ADAPTER);
            return Optional.of((ConformanceEngine) type.getDeclaredConstructor().newInstance());
        } catch (ClassNotFoundException e) {
            return Optional.empty();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot create the Amaru reference engine", e);
        }
    }

    /** @return scalus-legacy, scalus-legacy+supplementary, scalus-engine, java-legacy and, when built, amaru */
    public static List<ConformanceEngine> all() {
        List<ConformanceEngine> engines = new ArrayList<>(List.of(new ScalusLegacyEngine(false),
                new ScalusLegacyEngine(true), new ScalusViewEngine(), new JavaLegacyEngine()));
        amaru().ifPresent(engines::add);
        return engines;
    }
}
