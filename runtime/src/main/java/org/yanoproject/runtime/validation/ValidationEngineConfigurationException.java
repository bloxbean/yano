package org.yanoproject.runtime.validation;

/**
 * A validation-engine configuration the node must not start with (ADR-056 §7, ADR-057 §2): an unknown or
 * unavailable engine ({@code java} before Phases 3-5, {@code amaru} without its module), bad engine
 * settings, or an engine that cannot be created. Runtime assembly lets it through instead of degrading to
 * "no validation".
 */
public class ValidationEngineConfigurationException extends IllegalStateException {

    public ValidationEngineConfigurationException(String message) {
        super(message);
    }

    public ValidationEngineConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
