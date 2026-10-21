package org.yanoproject.ledger.rules.conway.utxo;

import org.yanoproject.ledger.rules.conway.ConwayLedgerConstants;
import org.yanoproject.ledger.rules.conway.ConwayParams;
import org.yanoproject.ledger.rules.conway.TransitionContext;
import org.yanoproject.ledger.rules.conway.tx.RawRedeemer;
import org.yanoproject.ledger.rules.conway.tx.RawScript;
import org.yanoproject.ledger.rules.conway.tx.RawTransaction;
import org.yanoproject.ledger.rules.conway.tx.TxInRef;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Conway's minimum fee, {@code getConwayMinFeeTxUtxo} (Conway/UTxO.hs:149-157):
 *
 * <pre>
 *   txSize · minFeeA + minFeeB                                    alonzoMinFeeTx (Alonzo/Tx.hs:445-459)
 * + ⌈priceMem · Σmem + priceSteps · Σsteps⌉                       txscriptfee (Plutus/ExUnits.hs:185-190)
 * + tierRefScriptFee 1.2 25600 minFeeRefScriptCostPerByte refSize getConwayMinFeeTx (Conway/Tx.hs:100-142)
 * </pre>
 *
 * <p>{@code txSize} is {@link RawTransaction#size()}; the ExUnits are the sum over the witness set's redeemers
 * ({@code totExUnits}, Alonzo/Tx.hs:460-464); {@code refSize} is {@code txNonDistinctRefScriptsSize}
 * (Conway/UTxO.hs:159-172): the original-bytes size of the reference script of every UTxO entry among the
 * spending ∪ reference inputs, an input in both counted once, missing inputs not at all. All arithmetic is
 * exact rational arithmetic, as Haskell's.</p>
 */
public final class MinFee {

    private MinFee() {
    }

    /** @return the minimum fee of the transaction against the context's UTxO */
    public static BigInteger of(TransitionContext ctx) {
        ConwayParams pp = new ConwayParams(ctx.params());
        RawTransaction raw = ctx.raw();
        return linearFee(pp, raw.size())
                .add(scriptFee(pp.priceMem(), pp.priceSteps(), totalMem(raw), totalSteps(raw)))
                .add(tierRefScriptFee(ctx.constants(), pp.minFeeRefScriptCostPerByte(), refScriptsSize(ctx)));
    }

    /** {@code txSize · minFeeA + minFeeB} */
    public static BigInteger linearFee(ConwayParams pp, long txSize) {
        return BigInteger.valueOf(txSize).multiply(pp.minFeeA()).add(pp.minFeeB());
    }

    public static BigInteger totalMem(RawTransaction raw) {
        return raw.redeemers().stream().map(RawRedeemer::mem).reduce(BigInteger.ZERO, BigInteger::add);
    }

    public static BigInteger totalSteps(RawTransaction raw) {
        return raw.redeemers().stream().map(RawRedeemer::steps).reduce(BigInteger.ZERO, BigInteger::add);
    }

    /** {@code txscriptfee}: {@code ⌈mem · priceMem + steps · priceSteps⌉} */
    public static BigInteger scriptFee(BigDecimal priceMem, BigDecimal priceSteps, BigInteger mem, BigInteger steps) {
        Fraction cost = Fraction.of(priceMem).times(mem).plus(Fraction.of(priceSteps).times(steps));
        return cost.ceiling();
    }

    /**
     * {@code tierRefScriptFee multiplier sizeIncrement baseFee size} (Conway/Tx.hs:122-142): each full
     * {@code sizeIncrement} bytes cost {@code sizeIncrement · price} and multiply the price for the next tier; the
     * remainder costs {@code remainder · price}; the total is floored.
     */
    public static BigInteger tierRefScriptFee(ConwayLedgerConstants constants, BigDecimal baseFee, long size) {
        Fraction multiplier = new Fraction(constants.refScriptCostMultiplierNumerator(),
                constants.refScriptCostMultiplierDenominator());
        long stride = constants.refScriptCostStride();
        Fraction acc = Fraction.ZERO;
        Fraction price = Fraction.of(baseFee);
        long n = size;
        while (n >= stride) {
            acc = acc.plus(price.times(BigInteger.valueOf(stride)));
            price = price.times(multiplier);
            n -= stride;
        }
        return acc.plus(price.times(BigInteger.valueOf(n))).floor();
    }

    /** {@code txNonDistinctRefScriptsSize} over spending ∪ reference inputs. */
    public static long refScriptsSize(TransitionContext ctx) {
        SortedSet<TxInRef> inputs = new TreeSet<>(ctx.raw().referenceSet());
        inputs.addAll(ctx.raw().inputSet());
        long total = 0;
        for (TxInRef in : inputs) {
            Optional<UtxoEntry> entry = ctx.utxo(in);
            if (entry.isPresent() && entry.get().output().getScriptRef() != null) {
                total += scriptOriginalSize(entry.get().output().getScriptRef());
            }
        }
        return total;
    }

    /**
     * {@code originalBytesSize} of a reference script (Alonzo/Scripts.hs:516-518): for a native script the bytes
     * of the timelock, for a Plutus script the length of the script's byte string.
     *
     * @param scriptRef the {@code script} CBOR {@code [type, script]} (optionally still inside its tag-24
     *                  wrapper)
     */
    public static int scriptOriginalSize(byte[] scriptRef) {
        return RawScript.fromScriptRef(scriptRef).bytes().length;
    }

    /** A non-negative exact fraction. */
    private record Fraction(BigInteger num, BigInteger den) {

        static final Fraction ZERO = new Fraction(BigInteger.ZERO, BigInteger.ONE);

        Fraction {
            BigInteger gcd = num.gcd(den);
            if (gcd.signum() != 0 && !gcd.equals(BigInteger.ONE)) {
                num = num.divide(gcd);
                den = den.divide(gcd);
            }
        }

        static Fraction of(BigDecimal decimal) {
            BigDecimal d = decimal.stripTrailingZeros();
            if (d.scale() <= 0) {
                return new Fraction(d.toBigIntegerExact(), BigInteger.ONE);
            }
            return new Fraction(d.unscaledValue(), BigInteger.TEN.pow(d.scale()));
        }

        Fraction plus(Fraction o) {
            return new Fraction(num.multiply(o.den).add(o.num.multiply(den)), den.multiply(o.den));
        }

        Fraction times(Fraction o) {
            return new Fraction(num.multiply(o.num), den.multiply(o.den));
        }

        Fraction times(BigInteger k) {
            return new Fraction(num.multiply(k), den);
        }

        BigInteger floor() {
            return num.divide(den);
        }

        BigInteger ceiling() {
            BigInteger[] qr = num.divideAndRemainder(den);
            return qr[1].signum() == 0 ? qr[0] : qr[0].add(BigInteger.ONE);
        }
    }
}
