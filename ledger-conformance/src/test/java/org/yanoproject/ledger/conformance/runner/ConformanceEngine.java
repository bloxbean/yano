package org.yanoproject.ledger.conformance.runner;

/**
 * An engine under test: the new engine API ({@code LedgerValidationEngine}) or one of the legacy validation paths,
 * adapted so every engine answers the same {@link ConformanceCase} with an {@link Observation}.
 */
public interface ConformanceEngine {

    /** @return the name used in the report, e.g. {@code scalus-legacy} */
    String name();

    /** @return a one-line description for the report */
    String description();

    /**
     * Validates one case. Exceptions are allowed: the runner records them as {@link Observation#CRASH}.
     */
    Observation validate(ConformanceCase testCase) throws Exception;
}
