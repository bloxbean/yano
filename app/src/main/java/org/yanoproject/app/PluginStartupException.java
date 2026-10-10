package org.yanoproject.app;

import java.util.Optional;

/**
 * Secret-safe application boundary for a startup-fatal plugin failure.
 *
 * <p>The runtime intentionally retains plugin causes while it coordinates
 * rollback and cleanup. The packaged application must not let that cause
 * graph reach Quarkus' startup exception renderer: plugin messages, suppressed
 * cleanup failures, and wrapper messages can contain credentials. This
 * exception therefore never accepts or retains a cause. It exposes only a
 * platform-normalized failure type, for lifecycle failures a fixed enum
 * phase, and the bundle id and contribution kind when the host named them and
 * they match the catalog identity grammar.</p>
 */
public final class PluginStartupException extends IllegalStateException {
    static final String DIRECTORY_CAPTURE_FAILURE = "PLUGIN_DIRECTORY_CAPTURE";
    private final String sourceFailureType;
    private final String failurePhase;
    private final String bundleId;
    private final String contributionKind;

    PluginStartupException(String sourceFailureType, String failurePhase) {
        this(sourceFailureType, failurePhase, null, null);
    }

    PluginStartupException(String sourceFailureType, String failurePhase, String bundleId,
                           String contributionKind) {
        super(message(sourceFailureType, failurePhase, bundleId, contributionKind));
        this.sourceFailureType = sourceFailureType;
        this.failurePhase = failurePhase;
        this.bundleId = bundleId;
        this.contributionKind = contributionKind;
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

    /** Catalog-validated id of the bundle or plugin the host was activating, when it named one. */
    public Optional<String> bundleId() {
        return Optional.ofNullable(bundleId);
    }

    /** Contribution kind the host was activating, when it named one. */
    public Optional<String> contributionKind() {
        return Optional.ofNullable(contributionKind);
    }

    private static String message(String sourceFailureType, String failurePhase, String bundleId,
                                  String contributionKind) {
        String phase = failurePhase == null ? "" : ", phase=" + failurePhase;
        String bundle = bundleId == null ? "" : ", bundle=" + bundleId;
        String kind = contributionKind == null ? "" : ", contribution=" + contributionKind;
        return "Required plugin discovery or startup failed (errorType="
                + sourceFailureType + phase + bundle + kind + ")";
    }
}
