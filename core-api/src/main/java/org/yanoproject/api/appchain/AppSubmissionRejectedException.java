package org.yanoproject.api.appchain;

/**
 * Local admission rejection before a message is pooled or relayed.
 *
 * <p>Only a bounded symbolic reason is retained. Plugin diagnostics may contain
 * application data or secrets, so arbitrary text is replaced rather than exposed
 * to remote callers. Rejection is not a finalized application receipt.</p>
 */
public final class AppSubmissionRejectedException extends IllegalArgumentException {
    private final String code;

    /** Creates a rejection, retaining only an uppercase ASCII symbolic code. */
    public AppSubmissionRejectedException(String reason) {
        super(safeCode(reason));
        this.code = safeCode(reason);
    }

    /** Safe machine-readable reason suitable for an HTTP response. */
    public String code() {
        return code;
    }

    private static String safeCode(String reason) {
        return reason != null && reason.matches("[A-Z_]{1,32}")
                ? reason : "APPLICATION_REJECTED";
    }
}
