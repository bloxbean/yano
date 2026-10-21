package org.yanoproject.api.appchain;

import java.util.Objects;

/**
 * A governed membership change (admin add, remove or threshold) refused before
 * its command is submitted. {@link #code()} is a stable machine-readable
 * reason; the message explains it for operators.
 */
public final class MembershipChangeRejectedException extends IllegalArgumentException {

    /**
     * The resulting threshold-of-members pair could not certify blocks under the
     * chain's {@code consensus.max-byzantine-members} fault bound.
     */
    public static final String QUORUM_INVALID = "MEMBERSHIP_QUORUM_INVALID";

    private final String code;

    public MembershipChangeRejectedException(String code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    /** Stable machine-readable reason, such as {@link #QUORUM_INVALID}. */
    public String code() {
        return code;
    }
}
