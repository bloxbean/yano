package org.yanoproject.ledger.rules.conway.tx;

import java.math.BigInteger;
import java.util.Objects;

/**
 * One redeemer of the witness set, as Haskell keys it: purpose tag and index, with its declared execution
 * units.
 *
 * @param tag   0 spend, 1 mint, 2 cert, 3 reward, 4 voting, 5 proposing
 * @param index the index within its purpose
 * @param mem   declared memory units
 * @param steps declared CPU steps
 * @param data  the redeemer data's original encoding in the transaction bytes
 */
public record RawRedeemer(int tag, long index, BigInteger mem, BigInteger steps, CborSlice data) {

    public RawRedeemer {
        Objects.requireNonNull(mem, "mem");
        Objects.requireNonNull(steps, "steps");
        Objects.requireNonNull(data, "data");
    }

    /** @return the redeemer key, {@link #key(int, long)} */
    public long key() {
        return key(tag, index);
    }

    /** @return the redeemer key {@code (tag, index)} packed in one long, ordered as Haskell's redeemer map */
    public static long key(int tag, long index) {
        return ((long) tag << 32) | index;
    }
}
