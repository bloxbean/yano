package org.yanoproject.ledger.conformance.runner;

import java.util.List;
import java.util.Map;

/**
 * Scenarios whose fault Haskell reports with more than one constructor, and Haskell's failure list for them in its
 * order. Amaru's corpus names one expected predicate per scenario; where Haskell necessarily reports others with it,
 * an engine that follows Haskell's order must not count as a mismatch.
 */
public final class HaskellFailureLists {

    /**
     * Babbage {@code feesOK} part 2, {@code validateTotalCollateral} ({@code Babbage/Rules/Utxo.hs:226-239},
     * {@code sequenceA_}): with redeemers and no collateral inputs, the zero collateral balance fails part 5
     * ({@code InsufficientCollateral}) before part 7 ({@code NoCollateralInputs}).
     */
    public static final List<String> NO_COLLATERAL = List.of("UTXO.InsufficientCollateral", "UTXO.NoCollateralInputs");

    private static final Map<String, List<String>> SCENARIOS = Map.of(
            "00278-fail-transaction-with-redeemers-but-no-collateral-inputs", NO_COLLATERAL);

    private HaskellFailureLists() {
    }

    /** @return Haskell's ordered failure list for a scenario, or empty when its expected constructor is alone */
    public static List<String> forScenario(String scenarioName) {
        return SCENARIOS.getOrDefault(scenarioName, List.of());
    }
}
