package org.yanoproject.app.api.validation;

import org.yanoproject.runtime.validation.ValidationEngines;

import java.util.Optional;

/** The node's validation engines (ADR-056 §7), produced by the Yano composition boundary. */
@FunctionalInterface
public interface ValidationEnginesSource {

    /** @return the engines, or empty when admission uses the legacy validator */
    Optional<ValidationEngines> engines();
}
