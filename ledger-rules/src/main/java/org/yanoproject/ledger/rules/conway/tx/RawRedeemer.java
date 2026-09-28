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
 */
public record RawRedeemer(int tag, long index, BigInteger mem, BigInteger steps) {

    public RawRedeemer {
        Objects.requireNonNull(mem, "mem");
        Objects.requireNonNull(steps, "steps");
    }
}
