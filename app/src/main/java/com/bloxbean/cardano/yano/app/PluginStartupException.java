package com.bloxbean.cardano.yano.app;

import java.util.Optional;

/**
 * Secret-safe application boundary for a startup-fatal plugin failure.
 *
 * <p>The runtime intentionally retains plugin causes while it coordinates
 * rollback and cleanup. The packaged application must not let that cause
 * graph reach Quarkus' startup exception renderer: plugin messages, suppressed
 * cleanup failures, and wrapper messages can contain credentials. This
 * exception therefore never accepts or retains a cause. It exposes only a
 * platform-normalized failure type and, for lifecycle failures, a fixed enum
 * phase.</p>
 */
public final class PluginStartupException extends IllegalStateException {
    static final String DIRECTORY_CAPTURE_FAILURE = "PLUGIN_DIRECTORY_CAPTURE";
    private final String sourceFailureType;
    private final String failurePhase;

    PluginStartupException(String sourceFailureType, String failurePhase) {
        super(message(sourceFailureType, failurePhase));
        this.sourceFailureType = sourceFailureType;
        this.failurePhase = failurePhase;
    }

    static PluginStartupException directoryCaptureFailure() {
        return new PluginStartupException(DIRECTORY_CAPTURE_FAILURE, null);
    }

    /** Platform exception type used to classify the original failure. */
    public String sourceFailureType() {
        return sourceFailureType;
    }

    /** Fixed plugin lifecycle phase when the failure came from that boundary. */
    public Optional<String> failurePhase() {
        return Optional.ofNullable(failurePhase);
    }

    private static String message(String sourceFailureType, String failurePhase) {
        String phase = failurePhase == null ? "" : ", phase=" + failurePhase;
        return "Required plugin discovery or startup failed (errorType="
                + sourceFailureType + phase + ")";
    }
}
