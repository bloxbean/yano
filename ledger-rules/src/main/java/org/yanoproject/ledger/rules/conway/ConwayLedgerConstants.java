package org.yanoproject.ledger.rules.conway;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Values Haskell hardcodes for Conway instead of reading them from the protocol parameters. Production always
 * uses {@link #HASKELL}; conformance fixtures that move a value (Amaru's scenarios can) pass their own.
 *
 * @param maxRefScriptSizePerTx            {@code maxRefScriptSizePerTx} (Conway/Rules/Ledger.hs:456-471): 200 KiB
 * @param maxRefScriptSizePerBlock         {@code maxRefScriptSizePerBlock} (Conway/Rules/Bbody.hs): 1 MiB
 * @param refScriptCostStride              {@code ppRefScriptCostStrideG} (Conway/PParams.hs:984): 25600
 * @param refScriptCostMultiplierNumerator   {@code ppRefScriptCostMultiplierG} (Conway/PParams.hs:983): 1.2
 * @param refScriptCostMultiplierDenominator see the numerator
 */
public record ConwayLedgerConstants(long maxRefScriptSizePerTx, long maxRefScriptSizePerBlock,
                                    long refScriptCostStride, BigInteger refScriptCostMultiplierNumerator,
                                    BigInteger refScriptCostMultiplierDenominator) {

    /** Haskell's values at cardano-ledger {@code f649f975}. */
    public static final ConwayLedgerConstants HASKELL = new ConwayLedgerConstants(204_800, 1_048_576, 25_600,
            BigInteger.valueOf(12), BigInteger.TEN);

    public ConwayLedgerConstants {
        Objects.requireNonNull(refScriptCostMultiplierNumerator, "refScriptCostMultiplierNumerator");
        Objects.requireNonNull(refScriptCostMultiplierDenominator, "refScriptCostMultiplierDenominator");
        if (refScriptCostStride <= 0 || refScriptCostMultiplierNumerator.signum() <= 0
                || refScriptCostMultiplierDenominator.signum() <= 0) {
            throw new IllegalArgumentException("the reference-script cost stride and multiplier must be positive");
        }
    }

    /**
     * @return these constants with every non-null argument replacing the corresponding value (the Amaru
     *         fixture form, where null keeps Haskell's value)
     */
    public ConwayLedgerConstants with(Long perTx, Long perBlock, Long stride, BigInteger numerator,
                                      BigInteger denominator) {
        return new ConwayLedgerConstants(perTx != null ? perTx : maxRefScriptSizePerTx,
                perBlock != null ? perBlock : maxRefScriptSizePerBlock, stride != null ? stride : refScriptCostStride,
                numerator != null ? numerator : refScriptCostMultiplierNumerator,
                denominator != null ? denominator : refScriptCostMultiplierDenominator);
    }
}
