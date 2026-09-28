package org.yanoproject.ledger.conformance.runner;

import java.util.List;
import java.util.Map;

/**
 * Scenarios whose fault Haskell reports with more than one constructor, and Haskell's failure list for them in its
 * order. Amaru's corpus names one expected predicate per scenario (Amaru stops at its first failure, in its own
 * order); where Haskell necessarily reports others with it, an engine that follows Haskell's order must not count
 * as a mismatch.
 *
 * <p>The order is that of Haskell's {@code LEDGER} failure list, which {@code small-steps} builds by prepending
 * each predicate's failures reversed and each sub-rule's list one failure at a time
 * ({@code Control/State/Transition/Extended.hs:668-731}; {@code org.yanoproject.ledger.rules.conway.RuleFrame}).
 * Rooted at {@code LEDGER}, the {@code UTXO} checks therefore appear in <em>reverse</em> execution order.</p>
 */
public final class HaskellFailureLists {

    /**
     * Babbage {@code feesOK} part 2, {@code validateTotalCollateral} ({@code Babbage/Rules/Utxo.hs:216-245},
     * {@code sequenceA_}): with redeemers and no collateral inputs, the zero collateral balance fails part 5
     * ({@code InsufficientCollateral}) and part 7 ({@code NoCollateralInputs}); {@code LEDGER} lists them
     * reversed.
     */
    public static final List<String> NO_COLLATERAL = List.of("UTXO.NoCollateralInputs", "UTXO.InsufficientCollateral");

    /**
     * An unknown spending input is {@code BadInputsUTxO} (Babbage/Rules/Utxo.hs:375), and because
     * {@code txInsFilter} leaves it out of the consumed value it is also {@code ValueNotConservedUTxO} (:378),
     * unless the rest of the transaction happens to balance without it.
     */
    public static final List<String> UNKNOWN_SPENT_INPUT = List.of("UTXO.ValueNotConservedUTxO", "UTXO.BadInputsUTxO");

    /**
     * No spending input: {@code InputSetEmptyUTxO} (:368), and value conservation fails for a transaction that
     * produces anything (:378).
     */
    public static final List<String> EMPTY_INPUTS = List.of("UTXO.ValueNotConservedUTxO", "UTXO.InputSetEmptyUTxO");

    /**
     * Scenario 00124: the collateral return exceeds the collateral (a negative {@code collAdaBalance},
     * {@code InsufficientCollateral}, feesOK part 5) and the spend itself does not balance
     * ({@code ValueNotConservedUTxO}).
     */
    public static final List<String> NEGATIVE_COLLATERAL_AND_UNBALANCED =
            List.of("UTXO.ValueNotConservedUTxO", "UTXO.InsufficientCollateral");

    private static final Map<String, List<String>> SCENARIOS = Map.of(
            "00278-fail-transaction-with-redeemers-but-no-collateral-inputs", NO_COLLATERAL,
            "00050-fail-unknown-spent-input", UNKNOWN_SPENT_INPUT,
            "00124-fail-plutus-spend-collateral-return-exceeds-collateral-input", NEGATIVE_COLLATERAL_AND_UNBALANCED);

    private HaskellFailureLists() {
    }

    /** @return Haskell's ordered failure list for a scenario, or empty when its expected constructor is alone */
    public static List<String> forScenario(String scenarioName) {
        return SCENARIOS.getOrDefault(scenarioName, List.of());
    }
}
