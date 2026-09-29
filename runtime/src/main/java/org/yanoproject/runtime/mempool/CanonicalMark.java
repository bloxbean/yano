package org.yanoproject.runtime.mempool;

/**
 * What a mempool state's canonical base is fresh for (ADR-056 §6, rebuild step 1): the canonical generation
 * {@code Gs} and the target epoch {@code Es}, the epoch of the slot the ticked view is built for.
 *
 * @param generation  the canonical generation, or -1 when unknown
 * @param targetEpoch the epoch of the admission slot, or -1 when unknown
 */
public record CanonicalMark(long generation, int targetEpoch) {

    /** The mark before anything is known. */
    public static final CanonicalMark UNKNOWN = new CanonicalMark(-1, -1);
}
