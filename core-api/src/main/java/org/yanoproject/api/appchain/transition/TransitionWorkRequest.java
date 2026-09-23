package org.yanoproject.api.appchain.transition;

import java.util.Objects;

/**
 * Cheap, deterministic preflight request to reserve work before expensive transition evaluation.
 * An executor must reject an undeclared reference before touching state. Successfully reserved work is
 * not refunded when a later business decision or derived command rejects the enclosing cascade.
 *
 * @param reference one of the requesting kernel's statically declared work references
 * @param units positive number of units required by this command
 */
public record TransitionWorkRequest(TransitionWorkReference reference, int units) {
    /** Validates the reference and positive charge; use an empty request for commands requiring no work. */
    public TransitionWorkRequest {
        Objects.requireNonNull(reference, "reference");
        if (units < 1) throw new IllegalArgumentException("transition work units must be positive");
    }
}
