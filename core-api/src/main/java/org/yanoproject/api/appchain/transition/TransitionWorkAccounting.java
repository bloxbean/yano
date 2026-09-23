package org.yanoproject.api.appchain.transition;

import org.yanoproject.api.appchain.AppStateWriter;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Shared deterministic accounting for component-owned work, independent of cascade business plans.
 *
 * <p>Counters retain the big-endian twelve-byte encoding {@code [height:int64, used:int32]}. The first
 * successful reservation at a newer height starts a fresh count. Reservations write through the owner's
 * block-transaction writer before expensive work executes; they survive a rejected source cascade but
 * roll back when the enclosing block transaction aborts. All execution routes sharing a budget must use
 * the same key, maximum, and writer namespace. Callers must not pass an uncommitted business overlay.
 */
public final class TransitionWorkAccounting {
    /** Exact persisted counter size, excluding its component-local key. */
    public static final int ENCODED_BYTES = Long.BYTES + Integer.BYTES;

    private TransitionWorkAccounting() { }

    /**
     * Reserves units without overflow, returning false without a write when capacity is exhausted.
     * Zero units are supported for existing callers, although kernel requests must be positive.
     *
     * @param state authoritative owner-scoped writer inside the current block transaction
     * @param budget validated owner declaration, never supplied by an untrusted command
     * @param height positive executing block height
     * @param units nonnegative charge
     * @return true after a successful reservation; false when it would exceed the limit
     * @throws IllegalArgumentException if height or units are invalid
     * @throws IllegalStateException if stored accounting is corrupt or from a future height
     */
    public static boolean reserve(AppStateWriter state, TransitionWorkBudget budget, long height, int units) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(budget, "budget");
        if (height < 1 || units < 0) throw new IllegalArgumentException("invalid transition work reservation");
        byte[] key = budget.key();
        byte[] encoded = state.get(key).orElse(null);
        int used = 0;
        if (encoded != null) {
            if (encoded.length != ENCODED_BYTES) throw new IllegalStateException("corrupt transition work counter");
            ByteBuffer value = ByteBuffer.wrap(encoded);
            long recordedHeight = value.getLong();
            int recordedUnits = value.getInt();
            if (recordedHeight < 1 || recordedUnits < 0 || recordedUnits > budget.maximumUnits()) {
                throw new IllegalStateException("corrupt transition work counter");
            }
            if (recordedHeight == height) used = recordedUnits;
            else if (recordedHeight > height) throw new IllegalStateException("transition work height moved backwards");
        }
        if (units > budget.maximumUnits() - used) return false;
        state.put(key, ByteBuffer.allocate(ENCODED_BYTES).putLong(height).putInt(used + units).array());
        return true;
    }
}
