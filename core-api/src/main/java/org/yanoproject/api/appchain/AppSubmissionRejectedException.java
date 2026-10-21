package org.yanoproject.api.appchain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Local admission rejection before a message is pooled or relayed.
 *
 * <p>Only a bounded symbolic reason is retained. Plugin diagnostics may contain
 * application data or secrets, so arbitrary text is replaced rather than exposed
 * to remote callers. Rejection is not a finalized application receipt.</p>
 *
 * <p>Structured details (bloxbean/yano#153) are retained only for allowlisted keys whose
 * values match a fixed grammar: {@code rule} ({@code [a-z][a-z0-9-]{0,62}}), {@code deny}
 * ({@code [A-Z][A-Z0-9_]{0,62}}) and {@code write} (an integer 0..65535). Unknown keys and
 * invalid values are dropped, never echoed, and a reason replaced by
 * {@code APPLICATION_REJECTED} keeps no details.</p>
 */
public final class AppSubmissionRejectedException extends IllegalArgumentException {
    /** The detail keys a rejection may carry to remote callers, in the order a client receives them. */
    public static final List<String> DETAIL_KEYS = List.of("rule", "deny", "write");
    /** Largest {@code write} detail retained. */
    public static final int MAX_WRITE_DETAIL = 65_535;

    private static final Pattern CODE = Pattern.compile("[A-Z_]{1,32}");
    private static final Pattern RULE = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    private static final Pattern DENY = Pattern.compile("[A-Z][A-Z0-9_]{0,62}");

    private final String code;
    private final Map<String, Object> details;

    /** Creates a rejection, retaining only an uppercase ASCII symbolic code. */
    public AppSubmissionRejectedException(String reason) {
        this(reason, Map.of());
    }

    /**
     * Creates a rejection with structured details, retaining only the symbolic code and the allowlisted,
     * grammar-checked details.
     *
     * @param reason symbolic reason
     * @param details unsanitized details, for example {@link AppStateMachine.AdmissionResult#details()};
     *                {@code null} means none
     */
    public AppSubmissionRejectedException(String reason, Map<String, ?> details) {
        super(safeCode(reason));
        this.code = safeCode(reason);
        this.details = code.equals(reason) ? safeDetails(details) : Map.of();
    }

    /** Safe machine-readable reason suitable for an HTTP response. */
    public String code() {
        return code;
    }

    /**
     * Safe structured details suitable for an HTTP response: {@code rule} and {@code deny} as text and
     * {@code write} as a {@link Long}, in that order; empty when none survived.
     */
    public Map<String, Object> details() {
        return details;
    }

    private static String safeCode(String reason) {
        return reason != null && CODE.matcher(reason).matches()
                ? reason : "APPLICATION_REJECTED";
    }

    private static Map<String, Object> safeDetails(Map<String, ?> details) {
        if (details == null || details.isEmpty()) return Map.of();
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String key : DETAIL_KEYS) {
            Object value = details.get(key);
            Object checked = switch (key) {
                case "rule" -> value instanceof String rule && RULE.matcher(rule).matches() ? rule : null;
                case "deny" -> value instanceof String deny && DENY.matcher(deny).matches() ? deny : null;
                default -> (value instanceof Long || value instanceof Integer || value instanceof Short
                        || value instanceof Byte) && ((Number) value).longValue() >= 0
                        && ((Number) value).longValue() <= MAX_WRITE_DETAIL ? ((Number) value).longValue() : null;
            };
            if (checked != null) safe.put(key, checked);
        }
        return safe.isEmpty() ? Map.of() : Collections.unmodifiableMap(safe);
    }
}
