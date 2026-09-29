package org.yanoproject.ledger.conformance.runner;

import java.util.List;
import java.util.Map;

/**
 * Scenarios whose fault Haskell reports with more than one constructor, and Haskell's failure list for them in its
 * order. Amaru's corpus names one expected predicate per scenario (Amaru stops at its first failure, in its own
 * order, and its Haskell checker only requires the predicate to be among Haskell's failures); where Haskell
 * necessarily reports others with it, an engine that follows Haskell's order must not count as a mismatch.
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

    /**
     * A needed Plutus script without a redeemer: {@code UTXOW.MissingRedeemers} ({@code hasExactSetOfRedeemers})
     * and {@code UTXOS.CollectErrors [NoRedeemer]} (the script context collection, Alonzo/Plutus/Evaluate.hs:151-155);
     * {@code UTXOW} lists before {@code UTXOS} rooted at {@code LEDGER}.
     */
    public static final List<String> MISSING_REDEEMER = List.of("UTXOW.MissingRedeemers", "UTXOS.CollectErrors");

    /**
     * Scenarios 00102 and 00103: a script credential is deregistered and then delegated, with no script witness.
     * {@code CERTS} reports the delegation of an unregistered credential ({@code StakeKeyNotRegisteredDELEG}, the
     * corpus's {@code StakeCredentialInvalidPoolDelegation}), and {@code UTXOW}, which runs whatever {@code CERTS}
     * found, reports the credential's script as missing: both certificates need it
     * ({@code getScriptWitnessConwayTxCert}). {@code LEDGER} runs {@code CERTS} before {@code UTXOW}, so its list
     * holds {@code UTXOW}'s failure first ({@code small-steps} prepends each sub-rule's failures). Amaru's Haskell
     * checker accepts an expected predicate anywhere in the list ({@code ValidatePhaseOne/Run.hs:263-270}).
     */
    public static final List<String> SCRIPT_DELEGATION_AFTER_DEREGISTRATION =
            List.of("UTXOW.MissingScriptWitnessesUTXOW", "DELEG.StakeKeyNotRegisteredDELEG");

    /**
     * Scenarios 00072 and 00273: a registration certificate states a deposit other than {@code ppKeyDeposit} and the
     * transaction balances with the stated amount. {@code DELEG} reports the certificate
     * ({@code checkDepositAgainstPParams}, Conway/Rules/Deleg.hs:199-211: {@code IncorrectDepositDELEG} before
     * protocol version 11), and value conservation charges {@code ppKeyDeposit}, not the stated amount
     * ({@code shelleyTotalDepositsTxCerts} via {@code conwayTotalDepositsTxCerts}, Conway/TxCert.hs:817-826), so
     * {@code UTXO} reports {@code ValueNotConservedUTxO} too; {@code LEDGER} lists {@code UTXOW}'s subtree first.
     */
    public static final List<String> STATED_KEY_DEPOSIT_BALANCED =
            List.of("UTXO.ValueNotConservedUTxO", "DELEG.IncorrectDepositDELEG");

    private static final Map<String, List<String>> SCENARIOS = Map.of(
            "00278-fail-transaction-with-redeemers-but-no-collateral-inputs", NO_COLLATERAL,
            "00050-fail-unknown-spent-input", UNKNOWN_SPENT_INPUT,
            "00124-fail-plutus-spend-collateral-return-exceeds-collateral-input", NEGATIVE_COLLATERAL_AND_UNBALANCED,
            "00102-fail-script-credential-delegation-after-deregistration", SCRIPT_DELEGATION_AFTER_DEREGISTRATION,
            "00103-fail-script-credential-delegation-after-conway-unreg", SCRIPT_DELEGATION_AFTER_DEREGISTRATION,
            "00072-fail-stake-registration-cert-with-incorrect-deposit", STATED_KEY_DEPOSIT_BALANCED,
            "00273-fail-stake-registration-with-a-zero-deposit", STATED_KEY_DEPOSIT_BALANCED);

    private HaskellFailureLists() {
    }

    /** @return Haskell's ordered failure list for a scenario, or empty when its expected constructor is alone */
    public static List<String> forScenario(String scenarioName) {
        return SCENARIOS.getOrDefault(scenarioName, List.of());
    }
}
