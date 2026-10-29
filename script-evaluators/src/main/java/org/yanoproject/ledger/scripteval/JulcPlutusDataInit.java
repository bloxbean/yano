package org.yanoproject.ledger.scripteval;

import org.julclang.core.PlutusData;

import java.util.Objects;

/**
 * Workaround for <a href="https://github.com/bloxbean/julc/issues/266">julc #266</a>: julc's {@link PlutusData} class
 * initialization deadlocks when two threads use its types for the first time at once. The interface's {@code UNIT}
 * constant is a {@code ConstrData}, and because the interface declares default methods, initializing
 * {@code ConstrData} first initializes {@code PlutusData}. One thread initializing {@code ConstrData} and another
 * initializing {@code PlutusData} then wait on each other forever. Parallel shadow-sync validation hit this after a
 * mainnet restart, and concurrent mempool admission could too.
 *
 * <p>Reading {@code UNIT} on a single thread initializes {@code PlutusData} and {@code ConstrData} together, so no
 * later initialization can form the cycle. The julc evaluator entry points call this from their static initializers.
 * Remove this class and its callers once julc #266 is fixed:
 * {@code JulcPlutusDataInitTest.concurrentFirstUseDeadlocksWithoutTheWorkaround} then fails.</p>
 */
public final class JulcPlutusDataInit {

    private JulcPlutusDataInit() {
    }

    /** Initializes julc's {@link PlutusData} types on the calling thread; a no-op once they are initialized. */
    public static void ensureInitialized() {
        Objects.requireNonNull(PlutusData.UNIT);
    }
}
