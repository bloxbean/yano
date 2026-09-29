package org.yanoproject.ledger.rules.conway.failure;

import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.conway.CheckLabel;
import org.yanoproject.ledger.rules.conway.PvRange;

import java.util.Objects;

/**
 * The typed Conway predicate-failure constructors the Java engine reports (ADR-056 §2, §4, §6), each with the
 * Haskell rule that reports it, its protocol-version range, its REAPPLY label and where Haskell checks it
 * (cardano-ledger {@code f649f975}; the pinned table is {@code adr/reports/adr-056-haskell-pinned-revisions.md}
 * §3d/§3e, and {@code ConwayConstructorCatalogueConsistencyTest} checks this enum against it).
 *
 * <p>Phase 3a lists the {@code UTXO} and {@code UTXOS} families, Phase 3b {@code UTXOW}, Phase 4 {@code CERTS},
 * {@code DELEG}, {@code POOL} and {@code GOVCERT} with the two protocol-version-11 {@code LEDGER} withdrawal checks;
 * Phase 5 adds {@code GOV} and the other {@code LEDGER} predicates.
 * Wrapper constructors ({@code UtxosFailure}, {@code UtxoFailure}, …) are not listed: {@link LedgerFailure} names
 * the leaf with its rule.</p>
 */
public enum ConwayPredicate {

    // ---------------------------------------------------------------- UTXOW (Babbage/Rules/Utxow.hs:328-391)
    SCRIPT_WITNESS_NOT_VALIDATING(LedgerRuleName.UTXOW, "ScriptWitnessNotValidatingUTXOW", PvRange.ALWAYS,
            CheckLabel.DYNAMIC, "Babbage/Rules/Utxow.hs:211-232 (validateFailedBabbageScripts), 349: runTest"),
    EXTRANEOUS_SCRIPT_WITNESSES(LedgerRuleName.UTXOW, "ExtraneousScriptWitnessesUTXOW", PvRange.ALWAYS,
            CheckLabel.DYNAMIC, "Babbage/Rules/Utxow.hs:193-208 (babbageMissingScripts), 354"),
    MISSING_SCRIPT_WITNESSES(LedgerRuleName.UTXOW, "MissingScriptWitnessesUTXOW", PvRange.ALWAYS,
            CheckLabel.DYNAMIC, "Babbage/Rules/Utxow.hs:193-208 (babbageMissingScripts), 354"),
    UNSPENDABLE_UTXO_NO_DATUM_HASH(LedgerRuleName.UTXOW, "UnspendableUTxONoDatumHash", PvRange.ALWAYS,
            CheckLabel.DYNAMIC, "Alonzo/Rules/Utxow.hs (missingRequiredDatums); Alonzo/UTxO.hs:245-275; "
            + "Babbage/Rules/Utxow.hs:357"),
    MISSING_REQUIRED_DATUMS(LedgerRuleName.UTXOW, "MissingRequiredDatums", PvRange.ALWAYS, CheckLabel.DYNAMIC,
            "Alonzo/Rules/Utxow.hs (missingRequiredDatums); Babbage/Rules/Utxow.hs:357"),
    NOT_ALLOWED_SUPPLEMENTAL_DATUMS(LedgerRuleName.UTXOW, "NotAllowedSupplementalDatums", PvRange.ALWAYS,
            CheckLabel.DYNAMIC, "Alonzo/Rules/Utxow.hs (missingRequiredDatums); Babbage/UTxO.hs:74-84; "
            + "Babbage/Rules/Utxow.hs:357"),
    EXTRA_REDEEMERS(LedgerRuleName.UTXOW, "ExtraRedeemers", PvRange.ALWAYS, CheckLabel.DYNAMIC,
            "Alonzo/Rules/Utxow.hs (hasExactSetOfRedeemers); Babbage/Rules/Utxow.hs:361"),
    MISSING_REDEEMERS(LedgerRuleName.UTXOW, "MissingRedeemers", PvRange.ALWAYS, CheckLabel.DYNAMIC,
            "Alonzo/Rules/Utxow.hs (hasExactSetOfRedeemers); Babbage/Rules/Utxow.hs:361"),
    INVALID_WITNESSES(LedgerRuleName.UTXOW, "InvalidWitnessesUTXOW", PvRange.ALWAYS, CheckLabel.STATIC,
            "Shelley/Rules/Utxow.hs:391-408 (validateVerifiedWits); Babbage/Rules/Utxow.hs:366: runTestOnSignal"),
    MISSING_VKEY_WITNESSES(LedgerRuleName.UTXOW, "MissingVKeyWitnessesUTXOW", PvRange.ALWAYS, CheckLabel.DYNAMIC,
            "Shelley/Rules/Utxow.hs:412-424 (validateNeededWitnesses); Conway/UTxO.hs:174-199; "
            + "Babbage/Rules/Utxow.hs:369"),
    MISSING_TX_BODY_METADATA_HASH(LedgerRuleName.UTXOW, "MissingTxBodyMetadataHash", PvRange.ALWAYS,
            CheckLabel.STATIC, "Shelley/Rules/Utxow.hs:427-443 (validateMetadata); Babbage/Rules/Utxow.hs:374"),
    MISSING_TX_METADATA(LedgerRuleName.UTXOW, "MissingTxMetadata", PvRange.ALWAYS, CheckLabel.STATIC,
            "Shelley/Rules/Utxow.hs:427-443 (validateMetadata); Babbage/Rules/Utxow.hs:374"),
    CONFLICTING_METADATA_HASH(LedgerRuleName.UTXOW, "ConflictingMetadataHash", PvRange.ALWAYS, CheckLabel.STATIC,
            "Shelley/Rules/Utxow.hs:427-443 (validateMetadata); Babbage/Rules/Utxow.hs:374"),
    INVALID_METADATA(LedgerRuleName.UTXOW, "InvalidMetadata", PvRange.ALWAYS, CheckLabel.STATIC,
            "Shelley/Rules/Utxow.hs:442; Alonzo/TxAuxData.hs:360-373 (validateAlonzoTxAuxData: Plutus scripts "
            + "well formed); Babbage/Rules/Utxow.hs:374"),
    MALFORMED_SCRIPT_WITNESSES(LedgerRuleName.UTXOW, "MalformedScriptWitnesses", PvRange.ALWAYS, CheckLabel.STATIC,
            "Babbage/Rules/Utxow.hs:234-273 (validateScriptsWellFormed), 379: runTestOnSignal"),
    MALFORMED_REFERENCE_SCRIPTS(LedgerRuleName.UTXOW, "MalformedReferenceScripts", PvRange.ALWAYS,
            CheckLabel.STATIC, "Babbage/Rules/Utxow.hs:234-273 (validateScriptsWellFormed), 379: outputs and "
            + "collateral return"),
    PP_VIEW_HASHES_DONT_MATCH(LedgerRuleName.UTXOW, "PPViewHashesDontMatch", PvRange.between(9, 10),
            CheckLabel.DYNAMIC, "Alonzo/Rules/Utxow.hs (checkScriptIntegrityHash, pvMajor < 11); "
            + "Babbage/Rules/Utxow.hs:387-389"),
    SCRIPT_INTEGRITY_HASH_MISMATCH(LedgerRuleName.UTXOW, "ScriptIntegrityHashMismatch", PvRange.from(11),
            CheckLabel.DYNAMIC, "Alonzo/Rules/Utxow.hs (checkScriptIntegrityHash, pvMajor >= 11); "
            + "Babbage/Rules/Utxow.hs:387-389"),

    // ---------------------------------------------------------------- UTXO (Babbage/Rules/Utxo.hs:342-412)
    BABBAGE_NON_DISJOINT_REF_INPUTS(LedgerRuleName.UTXO, "BabbageNonDisjointRefInputs", PvRange.between(9, 10),
            CheckLabel.DYNAMIC, "Babbage/Rules/Utxo.hs:200-214, 356"),
    OUTSIDE_VALIDITY_INTERVAL(LedgerRuleName.UTXO, "OutsideValidityIntervalUTxO", PvRange.ALWAYS,
            CheckLabel.DYNAMIC, "Allegra/Rules/Utxo.hs:230-240; Babbage/Rules/Utxo.hs:359"),
    OUTSIDE_FORECAST(LedgerRuleName.UTXO, "OutsideForecast", PvRange.ALWAYS, CheckLabel.DYNAMIC,
            "Alonzo/Rules/Utxo.hs:366-386; Babbage/Rules/Utxo.hs:365; unreachable at the pin (linear extension)"),
    INPUT_SET_EMPTY(LedgerRuleName.UTXO, "InputSetEmptyUTxO", PvRange.ALWAYS, CheckLabel.STATIC,
            "Shelley/Rules/Utxo.hs:428-435; Babbage/Rules/Utxo.hs:368"),
    FEE_TOO_SMALL(LedgerRuleName.UTXO, "FeeTooSmallUTxO", PvRange.ALWAYS, CheckLabel.DYNAMIC,
            "Babbage/Rules/Utxo.hs:168-194 (feesOK part 1), 371"),
    SCRIPTS_NOT_PAID(LedgerRuleName.UTXO, "ScriptsNotPaidUTxO", PvRange.ALWAYS, CheckLabel.DYNAMIC,
            "Babbage/Rules/Utxo.hs:216-245 (part 3); Alonzo/Rules/Utxo.hs:262-266, 327-333"),
    COLLATERAL_CONTAINS_NON_ADA(LedgerRuleName.UTXO, "CollateralContainsNonADA", PvRange.ALWAYS,
            CheckLabel.DYNAMIC, "Babbage/Rules/Utxo.hs:216-245 (part 4), 253-293"),
    INSUFFICIENT_COLLATERAL(LedgerRuleName.UTXO, "InsufficientCollateral", PvRange.ALWAYS, CheckLabel.DYNAMIC,
            "Babbage/Rules/Utxo.hs:216-245 (part 5); Alonzo/Rules/Utxo.hs:335-351"),
    INCORRECT_TOTAL_COLLATERAL_FIELD(LedgerRuleName.UTXO, "IncorrectTotalCollateralField", PvRange.ALWAYS,
            CheckLabel.DYNAMIC, "Babbage/Rules/Utxo.hs:216-245 (part 6), 295-301"),
    NO_COLLATERAL_INPUTS(LedgerRuleName.UTXO, "NoCollateralInputs", PvRange.ALWAYS, CheckLabel.DYNAMIC,
            "Babbage/Rules/Utxo.hs:216-245 (part 7)"),
    BAD_INPUTS(LedgerRuleName.UTXO, "BadInputsUTxO", PvRange.ALWAYS, CheckLabel.DYNAMIC,
            "Shelley/Rules/Utxo.hs:461-472; Babbage/Rules/Utxo.hs:373-375"),
    VALUE_NOT_CONSERVED(LedgerRuleName.UTXO, "ValueNotConservedUTxO", PvRange.ALWAYS, CheckLabel.DYNAMIC,
            "Shelley/Rules/Utxo.hs:506-522; Babbage/Rules/Utxo.hs:378"),
    BABBAGE_OUTPUT_TOO_SMALL(LedgerRuleName.UTXO, "BabbageOutputTooSmallUTxO", PvRange.ALWAYS,
            CheckLabel.DYNAMIC, "Babbage/Rules/Utxo.hs:303-323, 385; Babbage/TxOut.hs:665-689"),
    OUTPUT_TOO_BIG(LedgerRuleName.UTXO, "OutputTooBigUTxO", PvRange.ALWAYS, CheckLabel.DYNAMIC,
            "Alonzo/Rules/Utxo.hs:412-434; Babbage/Rules/Utxo.hs:389"),
    OUTPUT_BOOT_ADDR_ATTRS_TOO_BIG(LedgerRuleName.UTXO, "OutputBootAddrAttrsTooBig", PvRange.ALWAYS,
            CheckLabel.STATIC, "Shelley/Rules/Utxo.hs:544-561; Babbage/Rules/Utxo.hs:392"),
    WRONG_NETWORK(LedgerRuleName.UTXO, "WrongNetwork", PvRange.ALWAYS, CheckLabel.STATIC,
            "Shelley/Rules/Utxo.hs:474-488; Babbage/Rules/Utxo.hs:397"),
    WRONG_NETWORK_WITHDRAWAL(LedgerRuleName.UTXO, "WrongNetworkWithdrawal", PvRange.ALWAYS, CheckLabel.STATIC,
            "Shelley/Rules/Utxo.hs:490-504; Babbage/Rules/Utxo.hs:400"),
    WRONG_NETWORK_IN_TX_BODY(LedgerRuleName.UTXO, "WrongNetworkInTxBody", PvRange.ALWAYS, CheckLabel.STATIC,
            "Alonzo/Rules/Utxo.hs:436-449; Babbage/Rules/Utxo.hs:403"),
    MAX_TX_SIZE(LedgerRuleName.UTXO, "MaxTxSizeUTxO", PvRange.ALWAYS, CheckLabel.STATIC,
            "Shelley/Rules/Utxo.hs:563-578; Babbage/Rules/Utxo.hs:406"),
    EX_UNITS_TOO_BIG(LedgerRuleName.UTXO, "ExUnitsTooBigUTxO", PvRange.ALWAYS, CheckLabel.DYNAMIC,
            "Alonzo/Rules/Utxo.hs:451-468; Babbage/Rules/Utxo.hs:409"),
    TOO_MANY_COLLATERAL_INPUTS(LedgerRuleName.UTXO, "TooManyCollateralInputs", PvRange.ALWAYS,
            CheckLabel.DYNAMIC, "Alonzo/Rules/Utxo.hs:470-481; Babbage/Rules/Utxo.hs:412"),

    // ---------------------------------------------------------------- UTXOS (Conway/Rules/Utxos.hs:218-242)
    COLLECT_ERRORS(LedgerRuleName.UTXOS, "CollectErrors", PvRange.ALWAYS, CheckLabel.DYNAMIC,
            "Babbage/Rules/Utxos.hs:143 (valid), 206 (invalid): ?!: is unlabelled"),
    VALIDATION_TAG_MISMATCH(LedgerRuleName.UTXOS, "ValidationTagMismatch", PvRange.ALWAYS, CheckLabel.STATIC,
            "Babbage/Rules/Utxos.hs:145-157, 208-222: when2Phase (static) $ whenFailureFree"),

    // ---------------------------------------------------------------- LEDGER (Conway/Rules/Ledger.hs:350-440)
    CONWAY_WITHDRAWALS_MISSING_ACCOUNTS(LedgerRuleName.LEDGER, "ConwayWithdrawalsMissingAccounts", PvRange.from(11),
            CheckLabel.DYNAMIC, "Conway/Rules/Ledger.hs:383-386 (hardforkConwayMoveWithdrawalsAndDRepChecksToLedgerRule); "
            + "Shelley/Rules/Ledger.hs:351-358 (testIncompleteAndMissingWithdrawals): failOnNonEmptyMap"),
    CONWAY_INCOMPLETE_WITHDRAWALS(LedgerRuleName.LEDGER, "ConwayIncompleteWithdrawals", PvRange.from(11),
            CheckLabel.DYNAMIC, "Conway/Rules/Ledger.hs:383-386; Shelley/Rules/Ledger.hs:359: failOnNonEmptyMap, after "
            + "the missing accounts"),

    // ---------------------------------------------------------------- CERTS (Conway/Rules/Certs.hs:204-241)
    WITHDRAWALS_NOT_IN_REWARDS(LedgerRuleName.CERTS, "WithdrawalsNotInRewardsCERTS", PvRange.between(9, 10),
            CheckLabel.DYNAMIC, "Conway/Rules/Certs.hs:222-236 (base case, before the first certificate; "
            + "hardforkConwayMoveWithdrawalsAndDRepChecksToLedgerRule moves it to LEDGER from PV 11): failOnJust"),

    // ---------------------------------------------------------------- DELEG (Conway/Rules/Deleg.hs:187-301)
    INCORRECT_DEPOSIT_DELEG(LedgerRuleName.DELEG, "IncorrectDepositDELEG", PvRange.between(9, 10),
            CheckLabel.DYNAMIC, "Conway/Rules/Deleg.hs:199-211 (checkDepositAgainstPParams), 242-259 (checkInvalidRefund); "
            + "PV ≤ 10: not hardforkConwayDELEGIncorrectDepositsAndRefunds"),
    DEPOSIT_INCORRECT_DELEG(LedgerRuleName.DELEG, "DepositIncorrectDELEG", PvRange.from(11), CheckLabel.DYNAMIC,
            "Conway/Rules/Deleg.hs:199-211 (checkDepositAgainstPParams); PV ≥ 11"),
    REFUND_INCORRECT_DELEG(LedgerRuleName.DELEG, "RefundIncorrectDELEG", PvRange.from(11), CheckLabel.DYNAMIC,
            "Conway/Rules/Deleg.hs:242-259 (checkInvalidRefund, only for a registered credential); PV ≥ 11"),
    STAKE_KEY_REGISTERED_DELEG(LedgerRuleName.DELEG, "StakeKeyRegisteredDELEG", PvRange.ALWAYS, CheckLabel.DYNAMIC,
            "Conway/Rules/Deleg.hs:212-214 (checkStakeKeyNotRegistered)"),
    STAKE_KEY_NOT_REGISTERED_DELEG(LedgerRuleName.DELEG, "StakeKeyNotRegisteredDELEG", PvRange.ALWAYS,
            CheckLabel.DYNAMIC, "Conway/Rules/Deleg.hs:271 (UnReg), 283 (Deleg): failBecause"),
    STAKE_KEY_HAS_NON_ZERO_ACCOUNT_BALANCE_DELEG(LedgerRuleName.DELEG, "StakeKeyHasNonZeroAccountBalanceDELEG",
            PvRange.ALWAYS, CheckLabel.DYNAMIC, "Conway/Rules/Deleg.hs:260-268: failOnJust"),
    DELEGATEE_DREP_NOT_REGISTERED_DELEG(LedgerRuleName.DELEG, "DelegateeDRepNotRegisteredDELEG", PvRange.from(10),
            CheckLabel.DYNAMIC, "Conway/Rules/Deleg.hs:220-226 (skipped while hardforkConwayBootstrapPhase)"),
    DELEGATEE_STAKE_POOL_NOT_REGISTERED_DELEG(LedgerRuleName.DELEG, "DelegateeStakePoolNotRegisteredDELEG",
            PvRange.ALWAYS, CheckLabel.DYNAMIC, "Conway/Rules/Deleg.hs:216-219 (checkPoolRegistered)"),

    // ---------------------------------------------------------------- POOL (Shelley/Rules/Pool.hs:209-323)
    STAKE_POOL_NOT_REGISTERED_ON_KEY(LedgerRuleName.POOL, "StakePoolNotRegisteredOnKeyPOOL", PvRange.ALWAYS,
            CheckLabel.DYNAMIC, "Shelley/Rules/Pool.hs:308 (RetirePool)"),
    STAKE_POOL_RETIREMENT_WRONG_EPOCH(LedgerRuleName.POOL, "StakePoolRetirementWrongEpochPOOL", PvRange.ALWAYS,
            CheckLabel.DYNAMIC, "Shelley/Rules/Pool.hs:309-322: cEpoch < e ≤ cEpoch + eMax"),
    STAKE_POOL_COST_TOO_LOW(LedgerRuleName.POOL, "StakePoolCostTooLowPOOL", PvRange.ALWAYS, CheckLabel.DYNAMIC,
            "Shelley/Rules/Pool.hs:252-261"),
    WRONG_NETWORK_POOL(LedgerRuleName.POOL, "WrongNetworkPOOL", PvRange.ALWAYS, CheckLabel.DYNAMIC,
            "Shelley/Rules/Pool.hs:231-243 (hardforkAlonzoValidatePoolAccountAddressNetID, always in Conway)"),
    POOL_METADATA_HASH_TOO_BIG(LedgerRuleName.POOL, "PoolMedataHashTooBig", PvRange.ALWAYS, CheckLabel.DYNAMIC,
            "Shelley/Rules/Pool.hs:245-250 (restrictPoolMetadataHash, always in Conway): size ≤ 32"),
    VRF_KEY_HASH_ALREADY_REGISTERED(LedgerRuleName.POOL, "VRFKeyHashAlreadyRegistered", PvRange.from(11),
            CheckLabel.DYNAMIC, "Shelley/Rules/Pool.hs:265-267 (new pool), 279-282 (re-registration); "
            + "hardforkConwayDisallowDuplicatedVRFKeys, PV ≥ 11"),

    // ---------------------------------------------------------------- GOVCERT (Conway/Rules/GovCert.hs:180-276)
    CONWAY_DREP_ALREADY_REGISTERED(LedgerRuleName.GOVCERT, "ConwayDRepAlreadyRegistered", PvRange.ALWAYS,
            CheckLabel.DYNAMIC, "Conway/Rules/GovCert.hs:210-212"),
    CONWAY_DREP_NOT_REGISTERED(LedgerRuleName.GOVCERT, "ConwayDRepNotRegistered", PvRange.ALWAYS,
            CheckLabel.DYNAMIC, "Conway/Rules/GovCert.hs:241 (UnRegDRep), 257-258 (UpdateDRep)"),
    CONWAY_DREP_INCORRECT_DEPOSIT(LedgerRuleName.GOVCERT, "ConwayDRepIncorrectDeposit", PvRange.ALWAYS,
            CheckLabel.DYNAMIC, "Conway/Rules/GovCert.hs:213-219"),
    CONWAY_DREP_INCORRECT_REFUND(LedgerRuleName.GOVCERT, "ConwayDRepIncorrectRefund", PvRange.ALWAYS,
            CheckLabel.DYNAMIC, "Conway/Rules/GovCert.hs:236-242: failOnJust"),
    CONWAY_COMMITTEE_HAS_PREVIOUSLY_RESIGNED(LedgerRuleName.GOVCERT, "ConwayCommitteeHasPreviouslyResigned",
            PvRange.ALWAYS, CheckLabel.DYNAMIC, "Conway/Rules/GovCert.hs:190-196: failOnJust"),
    CONWAY_COMMITTEE_IS_UNKNOWN(LedgerRuleName.GOVCERT, "ConwayCommitteeIsUnknown", PvRange.ALWAYS,
            CheckLabel.DYNAMIC, "Conway/Rules/GovCert.hs:197-205: current committee or a pending UpdateCommittee "
            + "proposal (Ledger.hs:367-370, the pre-transaction proposals)");

    private final LedgerRuleName rule;
    private final String constructor;
    private final PvRange pvRange;
    private final CheckLabel label;
    private final String haskellRef;

    ConwayPredicate(LedgerRuleName rule, String constructor, PvRange pvRange, CheckLabel label, String haskellRef) {
        this.rule = rule;
        this.constructor = constructor;
        this.pvRange = pvRange;
        this.label = label;
        this.haskellRef = haskellRef;
    }

    public LedgerRuleName rule() {
        return rule;
    }

    /** @return the Haskell constructor name */
    public String constructor() {
        return constructor;
    }

    /** @return the protocol versions at which Haskell can report it */
    public PvRange pvRange() {
        return pvRange;
    }

    public CheckLabel label() {
        return label;
    }

    /** @return where the check is in cardano-ledger {@code f649f975} */
    public String haskellRef() {
        return haskellRef;
    }

    /** @return {@code RULE.Constructor} */
    public String qualifiedName() {
        return rule.name() + "." + constructor;
    }

    /** @return the phase: only {@code ValidationTagMismatch} is a phase-2 verdict */
    public LedgerFailure.Phase phase() {
        return this == VALIDATION_TAG_MISMATCH ? LedgerFailure.Phase.PHASE_2 : LedgerFailure.Phase.PHASE_1;
    }

    /** @return the failure with a detail rendering of the constructor's fields */
    public LedgerFailure failure(String detail) {
        return new LedgerFailure(rule, constructor, phase(), Objects.requireNonNullElse(detail, ""));
    }
}
