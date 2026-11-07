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

    /**
     * Scenarios 00148 and 00150: a parameter change whose parent is not the enacted root nor a proposal of its purpose,
     * with a return account the scenario never registered (its state has no accounts). {@code GOV} runs
     * {@code ProposalReturnAccountDoesNotExist} (Conway/Rules/Gov.hs:504-508, from protocol version 10) before the
     * lineage check ({@code InvalidPrevGovActionId}, :561-566); both are {@code GOV} predicates, so {@code LEDGER}'s
     * list holds them in execution order (two reversals).
     */
    public static final List<String> BAD_PARENT_UNREGISTERED_RETURN_ACCOUNT =
            List.of("GOV.ProposalReturnAccountDoesNotExist", "GOV.InvalidPrevGovActionId");

    /**
     * Scenario 00285: the redeemers' execution units sum past 2^64. Haskell sums {@code ExUnits} as {@code Natural}, so
     * the total exceeds {@code ppMaxTxExUnits} ({@code validateExUnitsTooBigUTxO}, Babbage/Rules/Utxo.hs:409) and the
     * minimum fee, which prices it, exceeds the fee ({@code feesOK}, :371); {@code LEDGER} lists the {@code UTXO}
     * checks in reverse execution order.
     */
    public static final List<String> EX_UNITS_OVERFLOW = List.of("UTXO.ExUnitsTooBigUTxO", "UTXO.FeeTooSmallUTxO");

    /**
     * Scenario 00381: a fee of 2^64 - 1 needs more collateral than the inputs hold ({@code InsufficientCollateral},
     * {@code feesOK} part 5 through {@code validateTotalCollateral}, Babbage/Rules/Utxo.hs:216-245), and the spend does
     * not balance ({@code ValueNotConservedUTxO}, :378); {@code LEDGER} lists them reversed.
     */
    public static final List<String> MAXIMUM_FEE_INSUFFICIENT_COLLATERAL =
            List.of("UTXO.ValueNotConservedUTxO", "UTXO.InsufficientCollateral");

    /**
     * Scenario 00292: a DRep deregistration of an unregistered DRep ({@code ConwayDRepNotRegistered},
     * Conway/Rules/GovCert.hs:241). Value conservation consumes the <em>stated</em> refund
     * ({@code conwayDRepRefundsTxCerts}, Conway/TxCert.hs:852-858), and the transaction balances without it, so
     * {@code UTXO} reports {@code ValueNotConservedUTxO} too; {@code LEDGER} lists {@code UTXOW}'s subtree first.
     */
    public static final List<String> UNREGISTERED_DREP_REFUND =
            List.of("UTXO.ValueNotConservedUTxO", "GOVCERT.ConwayDRepNotRegistered");

    /**
     * Scenario 00305: a DRep deregistration stating a refund of 110 lovelace where the recorded deposit is 100
     * ({@code ConwayDRepIncorrectRefund}, Conway/Rules/GovCert.hs:242). The transaction balances with the recorded
     * 100, while value conservation consumes the stated 110 ({@code conwayDRepRefundsTxCerts}, Conway/TxCert.hs:
     * 852-858), so {@code UTXO} reports {@code ValueNotConservedUTxO} by 10 lovelace too; {@code LEDGER} lists
     * {@code UTXOW}'s subtree first.
     */
    public static final List<String> INCORRECT_DREP_REFUND =
            List.of("UTXO.ValueNotConservedUTxO", "GOVCERT.ConwayDRepIncorrectRefund");

    /**
     * Scenario 00322: a spend's redeemer tagged as a mint. {@code hasExactSetOfRedeemers} reports the mint redeemer as
     * extra and the spend's as missing ({@code sequenceA_}, Alonzo/Rules/Utxow.hs:259-262; one {@code UTXOW} predicate,
     * so {@code LEDGER} keeps their order), and the script context collection finds no spend redeemer
     * ({@code UTXOS.CollectErrors [NoRedeemer]}, as {@link #MISSING_REDEEMER}).
     */
    public static final List<String> REDEEMER_WITH_WRONG_PURPOSE =
            List.of("UTXOW.ExtraRedeemers", "UTXOW.MissingRedeemers", "UTXOS.CollectErrors");

    private static final Map<String, List<String>> SCENARIOS = Map.ofEntries(
            Map.entry("00148-fail-parameter-change-proposal-chaining-to-a-non-existent-parent",
                    BAD_PARENT_UNREGISTERED_RETURN_ACCOUNT),
            Map.entry("00150-fail-parameter-change-proposal-with-an-empty-parent-after-a-root-was-enacted",
                    BAD_PARENT_UNREGISTERED_RETURN_ACCOUNT),
            Map.entry("00278-fail-transaction-with-redeemers-but-no-collateral-inputs", NO_COLLATERAL),
            Map.entry("00050-fail-unknown-spent-input", UNKNOWN_SPENT_INPUT),
            Map.entry("00124-fail-plutus-spend-collateral-return-exceeds-collateral-input",
                    NEGATIVE_COLLATERAL_AND_UNBALANCED),
            Map.entry("00102-fail-script-credential-delegation-after-deregistration",
                    SCRIPT_DELEGATION_AFTER_DEREGISTRATION),
            Map.entry("00103-fail-script-credential-delegation-after-conway-unreg",
                    SCRIPT_DELEGATION_AFTER_DEREGISTRATION),
            Map.entry("00072-fail-stake-registration-cert-with-incorrect-deposit", STATED_KEY_DEPOSIT_BALANCED),
            Map.entry("00273-fail-stake-registration-with-a-zero-deposit", STATED_KEY_DEPOSIT_BALANCED),
            Map.entry("00285-fail-plutus-redeemers-whose-summed-ex-units-overflow-64-bits", EX_UNITS_OVERFLOW),
            Map.entry("00292-fail-drep-unregistration-of-an-unregistered-drep", UNREGISTERED_DREP_REFUND),
            Map.entry("00305-fail-drep-deregistration-with-incorrect-refund", INCORRECT_DREP_REFUND),
            Map.entry("00322-fail-redeemer-with-incorrect-purpose", REDEEMER_WITH_WRONG_PURPOSE),
            Map.entry("00381-fail-maximum-fee-with-insufficient-collateral", MAXIMUM_FEE_INSUFFICIENT_COLLATERAL));

    private HaskellFailureLists() {
    }

    /** @return Haskell's ordered failure list for a scenario, or empty when its expected constructor is alone */
    public static List<String> forScenario(String scenarioName) {
        return SCENARIOS.getOrDefault(scenarioName, List.of());
    }
}
