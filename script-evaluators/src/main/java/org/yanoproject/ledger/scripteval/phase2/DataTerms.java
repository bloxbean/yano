package org.yanoproject.ledger.scripteval.phase2;

import org.julclang.core.PlutusData;

import java.math.BigInteger;
import java.util.List;

/**
 * Plutus {@code Data} constructors for the script-context encodings of plutus-ledger-api 1.65
 * ({@code makeIsDataIndexed}: a constructor is {@code Constr i fields}, a newtype is its field, a Haskell tuple is
 * {@code Constr 0 [a, b]}, {@code Maybe} is {@code Just = Constr 0 [x]} / {@code Nothing = Constr 1 []},
 * {@code Bool} is {@code False = Constr 0 []} / {@code True = Constr 1 []}).
 */
final class DataTerms {

    private static final PlutusData NOTHING = new PlutusData.ConstrData(1, List.of());
    private static final PlutusData FALSE = new PlutusData.ConstrData(0, List.of());
    private static final PlutusData TRUE = new PlutusData.ConstrData(1, List.of());

    private DataTerms() {
    }

    static PlutusData constr(int tag, PlutusData... fields) {
        return new PlutusData.ConstrData(tag, List.of(fields));
    }

    static PlutusData integer(BigInteger value) {
        return new PlutusData.IntData(value);
    }

    static PlutusData integer(long value) {
        return new PlutusData.IntData(BigInteger.valueOf(value));
    }

    static PlutusData bytes(byte[] value) {
        return new PlutusData.BytesData(value.clone());
    }

    static PlutusData list(List<PlutusData> items) {
        return new PlutusData.ListData(List.copyOf(items));
    }

    static PlutusData map(List<PlutusData.Pair> entries) {
        return new PlutusData.MapData(List.copyOf(entries));
    }

    static PlutusData.Pair pair(PlutusData key, PlutusData value) {
        return new PlutusData.Pair(key, value);
    }

    /** A Haskell 2-tuple. */
    static PlutusData tuple(PlutusData first, PlutusData second) {
        return constr(0, first, second);
    }

    static PlutusData just(PlutusData value) {
        return constr(0, value);
    }

    static PlutusData nothing() {
        return NOTHING;
    }

    static PlutusData maybe(PlutusData valueOrNull) {
        return valueOrNull != null ? just(valueOrNull) : NOTHING;
    }

    static PlutusData bool(boolean value) {
        return value ? TRUE : FALSE;
    }

    /** {@code Credential}: {@code PubKeyCredential = Constr 0 [hash]}, {@code ScriptCredential = Constr 1 [hash]}. */
    static PlutusData credential(boolean script, byte[] hash) {
        return constr(script ? 1 : 0, bytes(hash));
    }

    /** {@code StakingCredential}: {@code StakingHash = Constr 0 [credential]}. */
    static PlutusData stakingHash(PlutusData credential) {
        return constr(0, credential);
    }

    /**
     * A Plutus {@code Rational} ({@code PlutusTx.Ratio}): the tuple {@code (numerator, denominator)} of the reduced
     * fraction, as {@code fromHaskellRatio} builds it from a GHC {@code Ratio} (always reduced, positive
     * denominator).
     */
    static PlutusData rational(BigInteger numerator, BigInteger denominator) {
        BigInteger[] reduced = reduce(numerator, denominator);
        return tuple(integer(reduced[0]), integer(reduced[1]));
    }

    /** @return {@code [numerator, denominator]} reduced, with a positive denominator */
    static BigInteger[] reduce(BigInteger numerator, BigInteger denominator) {
        if (denominator.signum() == 0) {
            throw new IllegalArgumentException("a rational with denominator 0");
        }
        BigInteger gcd = numerator.gcd(denominator);
        BigInteger n = numerator.divide(gcd);
        BigInteger d = denominator.divide(gcd);
        if (d.signum() < 0) {
            n = n.negate();
            d = d.negate();
        }
        return new BigInteger[]{n, d};
    }
}
