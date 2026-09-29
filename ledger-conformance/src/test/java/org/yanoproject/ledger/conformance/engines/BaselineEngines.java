package org.yanoproject.ledger.conformance.engines;

import org.yanoproject.ledger.conformance.runner.ConformanceEngine;

import java.lang.reflect.InvocationTargetException;
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
        } catch (InvocationTargetException e) {
            // A stale module (AmaruReferenceEngine.verifyModule): fail with its message, not a wrapped one.
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new IllegalStateException("cannot create the Amaru reference engine", e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot create the Amaru reference engine", e);
        }
    }

    /**
     * @return scalus-legacy, scalus-legacy+supplementary, scalus-engine, java-legacy, java-julc (julc phase 2),
     *         java-scalus (ADR-056 Phase 7c) and, when built, amaru
     */
    public static List<ConformanceEngine> all() {
        List<ConformanceEngine> engines = new ArrayList<>(List.of(new ScalusLegacyEngine(false),
                new ScalusLegacyEngine(true), new ScalusViewEngine(), new JavaLegacyEngine(), new JavaViewEngine(),
                JavaViewEngine.scalus()));
        amaru().ifPresent(engines::add);
        return engines;
    }
}
