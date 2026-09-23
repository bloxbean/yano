package org.yanoproject.runtime.appchain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yanoproject.api.appchain.AppSubmissionRejectedException;
import org.yanoproject.runtime.util.LifecycleFailures;

/**
 * Bounded, single-line operator diagnostics for local application admission.
 * WARN excludes exception messages. DEBUG deliberately includes rejection prose
 * and must be treated as potentially application-sensitive operator output.
 */
final class AdmissionDiagnostics {
    static final AdmissionDiagnostics INSTANCE = new AdmissionDiagnostics(LoggerFactory.getLogger(
            "org.yanoproject.runtime.appchain.AppChainSubsystem.admission"));
    private final Logger logger;

    AdmissionDiagnostics(Logger logger) {
        this.logger = logger;
    }

    /** Logs code locations, not throwable messages, application bodies, or authentication material. */
    void failed(long candidateHeight, Throwable failure) {
        try {
            StringBuilder locations = new StringBuilder();
            Throwable current = failure;
            for (int cause = 0; cause < 3 && current != null; cause++) {
                if (cause != 0) locations.append("; cause=");
                locations.append(escaped(current.getClass().getName(), 160));
                StackTraceElement[] frames = current.getStackTrace();
                for (int frame = 0; frame < Math.min(3, frames.length); frame++) {
                    StackTraceElement location = frames[frame];
                    locations.append(" at ").append(escaped(location.getClassName(), 160))
                            .append('.').append(escaped(location.getMethodName(), 96))
                            .append(':').append(location.getLineNumber());
                }
                current = current.getCause();
            }
            logger.warn("Local application admission failed (candidateHeight={}, locations={})",
                    candidateHeight, locations.toString());
        } catch (Throwable diagnosticFailure) {
            // Observability must not change the submission outcome or mask a fatal process failure.
            LifecycleFailures.rethrowIfProcessFatalReachable(diagnosticFailure);
        }
    }

    /** DEBUG is explicitly opt-in: escaped/truncated prose can still contain sensitive application data. */
    void rejected(long candidateHeight, String reason) {
        try {
            if (logger.isDebugEnabled()) {
                logger.debug("Local application admission rejected (candidateHeight={}, code={}, reason={})",
                        candidateHeight, new AppSubmissionRejectedException(reason).code(), escaped(reason, 512));
            }
        } catch (Throwable diagnosticFailure) {
            LifecycleFailures.rethrowIfProcessFatalReachable(diagnosticFailure);
        }
    }

    /** Escapes controls, non-ASCII characters, quotes and backslashes within an output-character bound. */
    static String escaped(String value, int maximum) {
        if (value == null) return "<null>";
        StringBuilder result = new StringBuilder(Math.min(value.length(), maximum));
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            String token;
            if (character == '\\' || character == '"') {
                token = "\\" + character;
            } else if (character < 32 || character > 126) {
                String hex = Integer.toHexString(character);
                token = "\\u" + "0".repeat(4 - hex.length()) + hex;
            } else {
                token = String.valueOf(character);
            }
            if (result.length() + token.length() > maximum - 3) {
                result.append("...");
                break;
            }
            result.append(token);
        }
        return result.toString();
    }
}
