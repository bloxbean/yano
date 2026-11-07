package org.yanoproject.runtime.validation;

/**
 * A validation-engine configuration the node must not start with (ADR-056 §7): an unknown or unavailable engine
 * (an unknown id such as the removed {@code java} or {@code amaru}), bad engine settings, or an engine that cannot be
 * created. Runtime assembly lets it through instead of degrading to
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
