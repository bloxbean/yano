package org.yanoproject.ledger.amaru;

import java.math.BigInteger;

/**
 * <b>Test only.</b> Values Haskell hardcodes instead of storing them in the protocol parameters
 * (INTERFACE.md request key 7): the per-transaction and per-block reference-script size limits and the
 * reference-script fee stride and multiplier. Four of Amaru's scenarios move them.
 *
 * <p>Production engines use {@link #HASKELL}, which sends nothing: the module then applies Haskell's
 * values (204800, 1048576, 25600, 12/10). A null field keeps Haskell's value.</p>
 */
public record AmaruLedgerConstants(Long maxRefScriptSizePerTx, Long maxRefScriptSizePerBlock, Long refScriptCostStride,
                                   BigInteger refScriptCostMultiplierNumerator,
                                   BigInteger refScriptCostMultiplierDenominator) {

    /** Haskell's constants: request key 7 is not sent. */
    public static final AmaruLedgerConstants HASKELL = new AmaruLedgerConstants(null, null, null, null, null);

    public AmaruLedgerConstants {
        if ((refScriptCostMultiplierNumerator == null) != (refScriptCostMultiplierDenominator == null)) {
            throw new IllegalArgumentException("the multiplier needs both a numerator and a denominator");
        }
        if (refScriptCostMultiplierDenominator != null && refScriptCostMultiplierDenominator.signum() == 0) {
            throw new IllegalArgumentException("zero denominator");
        }
    }

    public boolean isHaskell() {
        return equals(HASKELL);
    }
}
