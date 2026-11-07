package org.yanoproject.ledger.conformance.engines;

import org.yanoproject.ledger.conformance.runner.ConformanceEngine;

import java.util.List;

/** The engines of the Phase 2 baseline, in report order. */
public final class BaselineEngines {

    private BaselineEngines() {
    }

    /**
     * @return scalus-legacy, scalus-legacy+supplementary, scalus-engine, java-legacy, java-julc (julc phase 2) and
     *         java-scalus (ADR-056 Phase 7c)
     */
    public static List<ConformanceEngine> all() {
        return List.of(new ScalusLegacyEngine(false), new ScalusLegacyEngine(true), new ScalusViewEngine(),
                new JavaLegacyEngine(), new JavaViewEngine(), JavaViewEngine.scalus());
    }
}
