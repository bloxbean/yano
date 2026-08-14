package com.bloxbean.cardano.yano.runtime.plugins;

import com.bloxbean.cardano.yano.api.plugin.PluginActivationException;

/**
 * Signals that the immutable plugin catalog could not be discovered, validated,
 * selected, or activated during runtime assembly.
 *
 * <p>This exception is startup-fatal. It gives application composition layers
 * a stable boundary without requiring them to classify every catalog parser,
 * policy, compatibility, or provider-registry failure independently.</p>
 */
public final class PluginCatalogActivationException extends PluginActivationException {

    private static final int MAX_DIAGNOSTIC_LENGTH = 512;

    public PluginCatalogActivationException(String message, Throwable cause) {
        super(message, cause);
    }

    static PluginCatalogActivationException from(Throwable failure) {
        if (failure instanceof PluginCatalogActivationException activationFailure) {
            return activationFailure;
        }
        String detail = null;
        try {
            detail = boundedDiagnostic(failure.getMessage());
        } catch (Throwable diagnosticFailure) {
            com.bloxbean.cardano.yano.runtime.util.LifecycleFailures
                    .rethrowIfProcessFatalReachable(diagnosticFailure);
            // Hostile Throwable diagnostics are optional. Cleanup has already
            // completed before this method is called, so use the fixed form.
        }
        String message = detail == null || detail.isBlank()
                ? "Plugin catalog activation failed"
                : "Plugin catalog activation failed: " + detail;
        return new PluginCatalogActivationException(message, failure);
    }

    /**
     * Platform catalog diagnostics contain only bounded identities and rule
     * names. Plugin callbacks are first replaced with a platform-generated
     * diagnostic by {@link PluginCatalogBuilder}; this final boundary also
     * prevents control characters or an unbounded nested error from entering
     * an application startup log.
     */
    private static String boundedDiagnostic(String detail) {
        if (detail == null) {
            return null;
        }
        StringBuilder sanitized = new StringBuilder(
                Math.min(detail.length(), MAX_DIAGNOSTIC_LENGTH + 1));
        for (int i = 0; i < detail.length()
                && sanitized.length() <= MAX_DIAGNOSTIC_LENGTH; i++) {
            char character = detail.charAt(i);
            sanitized.append(Character.isISOControl(character) ? ' ' : character);
        }
        String singleLine = sanitized.toString();
        return singleLine.length() <= MAX_DIAGNOSTIC_LENGTH
                ? singleLine
                : singleLine.substring(0, MAX_DIAGNOSTIC_LENGTH) + "...";
    }
}
