package org.yanoproject.ledger.rules.fixtures.amaru;

import org.yanoproject.ledger.rules.LedgerRuleName;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Map.entry;

/**
 * Amaru's corpus predicate names mapped to the Haskell rule and predicate-failure constructor at
 * ADR-056's pinned {@code cardano-ledger} revision ({@code f649f975};
 * {@code adr/reports/adr-056-haskell-pinned-revisions.md}), for the protocol versions the corpus
 * uses (9 to 11).
 *
 * <p>This is the same table as {@code haskell_name} in
 * {@code amaru-validator-wasm/tests/amaru_scenarios.rs}; the two must agree. Most names are already
 * Haskell's; the exceptions (INTERFACE.md, "Amaru's corpus spells some names differently") are the
 * ones whose value differs from the key.</p>
 */
public final class AmaruCorpusNames {

    /** A Haskell rule and constructor. */
    public record HaskellName(LedgerRuleName rule, String constructor) {
    }

    private static final Map<String, HaskellName> NAMES = Map.ofEntries(
            entry("BabbageNonDisjointRefInputs", name(LedgerRuleName.UTXO, "BabbageNonDisjointRefInputs")),
            entry("BabbageOutputTooSmallUTxO", name(LedgerRuleName.UTXO, "BabbageOutputTooSmallUTxO")),
            entry("BadInputsUTxO", name(LedgerRuleName.UTXO, "BadInputsUTxO")),
            // CollectErrors [BadTranslation] of a BabbageContextError (Babbage/TxInfo.hs:253-257), see COLLECT_ERRORS
            entry("ByronTxOutInContext", name(LedgerRuleName.UTXOS, "CollectErrors")),
            entry("CommitteeHasPreviouslyResigned",
                    name(LedgerRuleName.GOVCERT, "ConwayCommitteeHasPreviouslyResigned")),
            entry("CommitteeIsUnknown", name(LedgerRuleName.GOVCERT, "ConwayCommitteeIsUnknown")),
            entry("ConflictingCommitteeUpdate", name(LedgerRuleName.GOV, "ConflictingCommitteeUpdate")),
            entry("ConflictingMetadataHash", name(LedgerRuleName.UTXOW, "ConflictingMetadataHash")),
            entry("ConwayTreasuryValueMismatch", name(LedgerRuleName.LEDGER, "ConwayTreasuryValueMismatch")),
            entry("ConwayTxRefScriptsSizeTooBig", name(LedgerRuleName.LEDGER, "ConwayTxRefScriptsSizeTooBig")),
            entry("ConwayWdrlNotDelegatedToDRep", name(LedgerRuleName.LEDGER, "ConwayWdrlNotDelegatedToDRep")),
            // CollectErrors [BadTranslation] of a ConwayContextError (Conway/TxInfo.hs:180-187), see COLLECT_ERRORS
            entry("CurrentTreasuryFieldNotSupported", name(LedgerRuleName.UTXOS, "CollectErrors")),
            entry("DRepNotRegistered", name(LedgerRuleName.GOVCERT, "ConwayDRepNotRegistered")),
            entry("DRepAlreadyRegistered", name(LedgerRuleName.GOVCERT, "ConwayDRepAlreadyRegistered")),
            entry("DelegateeDRepNotRegistered", name(LedgerRuleName.DELEG, "DelegateeDRepNotRegisteredDELEG")),
            entry("DelegateeStakePoolNotRegistered",
                    name(LedgerRuleName.DELEG, "DelegateeStakePoolNotRegisteredDELEG")),
            entry("DisallowedVoters", name(LedgerRuleName.GOV, "DisallowedVoters")),
            entry("ExUnitsTooBigUTxO", name(LedgerRuleName.UTXO, "ExUnitsTooBigUTxO")),
            entry("ExpirationEpochTooSmall", name(LedgerRuleName.GOV, "ExpirationEpochTooSmall")),
            entry("ExtraRedeemers", name(LedgerRuleName.UTXOW, "ExtraRedeemers")),
            entry("ExtraneousScriptWitnessesUTXOW", name(LedgerRuleName.UTXOW, "ExtraneousScriptWitnessesUTXOW")),
            entry("FeeTooSmallUTxO", name(LedgerRuleName.UTXO, "FeeTooSmallUTxO")),
            entry("GovActionsDoNotExist", name(LedgerRuleName.GOV, "GovActionsDoNotExist")),
            entry("IncorrectDepositDELEG", name(LedgerRuleName.DELEG, "IncorrectDepositDELEG")),
            entry("IncorrectDepositDRep", name(LedgerRuleName.GOVCERT, "ConwayDRepIncorrectDeposit")),
            entry("IncorrectTotalCollateralField", name(LedgerRuleName.UTXO, "IncorrectTotalCollateralField")),
            // CollectErrors [BadTranslation] of a BabbageContextError (Babbage/TxInfo.hs:253-257), see COLLECT_ERRORS
            entry("InlineDatumsNotSupported", name(LedgerRuleName.UTXOS, "CollectErrors")),
            entry("InputSetEmptyUTxO", name(LedgerRuleName.UTXO, "InputSetEmptyUTxO")),
            entry("InsufficientCollateral", name(LedgerRuleName.UTXO, "InsufficientCollateral")),
            entry("InvalidGuardrailsScriptHash", name(LedgerRuleName.GOV, "InvalidGuardrailsScriptHash")),
            entry("InvalidPrevGovActionId", name(LedgerRuleName.GOV, "InvalidPrevGovActionId")),
            entry("InvalidWitnessesUTXOW", name(LedgerRuleName.UTXOW, "InvalidWitnessesUTXOW")),
            entry("MalformedProposal", name(LedgerRuleName.GOV, "MalformedProposal")),
            entry("MalformedReferenceScripts", name(LedgerRuleName.UTXOW, "MalformedReferenceScripts")),
            entry("MalformedScriptWitnesses", name(LedgerRuleName.UTXOW, "MalformedScriptWitnesses")),
            entry("MaxTxSizeUTxO", name(LedgerRuleName.UTXO, "MaxTxSizeUTxO")),
            // CollectErrors [NoCostModel] (Alonzo/Plutus/Context.hs:360), named so by Amaru's checker (Run.hs:499-500)
            entry("MissingCostModel", name(LedgerRuleName.UTXOS, "CollectErrors")),
            entry("MissingRedeemers", name(LedgerRuleName.UTXOW, "MissingRedeemers")),
            entry("MissingRequiredDatums", name(LedgerRuleName.UTXOW, "MissingRequiredDatums")),
            entry("MissingScriptWitnessesUTXOW", name(LedgerRuleName.UTXOW, "MissingScriptWitnessesUTXOW")),
            entry("MissingTxBodyMetadataHash", name(LedgerRuleName.UTXOW, "MissingTxBodyMetadataHash")),
            entry("MissingTxMetadata", name(LedgerRuleName.UTXOW, "MissingTxMetadata")),
            entry("MissingVerificationKeyWitnessesUTXOW",
                    name(LedgerRuleName.UTXOW, "MissingVKeyWitnessesUTXOW")),
            entry("NoCollateralInputs", name(LedgerRuleName.UTXO, "NoCollateralInputs")),
            entry("NotAllowedSupplementalDatums", name(LedgerRuleName.UTXOW, "NotAllowedSupplementalDatums")),
            entry("OutputTooBigUTxO", name(LedgerRuleName.UTXO, "OutputTooBigUTxO")),
            // Amaru's Haskell checker names CollectErrors [BadTranslation TimeTranslationPastHorizon] so
            // (crates/amaru/tests/conformance/validation-rules/.../ValidatePhaseOne/Run.hs:501-502 at 40ba9950); Haskell's own
            // OutsideForecast is unreachable in Conway at f649f975 (ADR-056 Phase 3a results). See COLLECT_ERRORS.
            entry("OutsideForecast", name(LedgerRuleName.UTXOS, "CollectErrors")),
            entry("OutsideValidityIntervalUTxO", name(LedgerRuleName.UTXO, "OutsideValidityIntervalUTxO")),
            entry("PPViewHashesDontMatch", name(LedgerRuleName.UTXOW, "PPViewHashesDontMatch")),
            entry("ProposalCantFollow", name(LedgerRuleName.GOV, "ProposalCantFollow")),
            entry("ProposalDepositIncorrect", name(LedgerRuleName.GOV, "ProposalDepositIncorrect")),
            entry("ProposalProcedureNetworkIdMismatch",
                    name(LedgerRuleName.GOV, "ProposalProcedureNetworkIdMismatch")),
            // CollectErrors [BadTranslation] of a ConwayContextError (Conway/TxInfo.hs:180-187), see COLLECT_ERRORS
            entry("ProposalProceduresFieldNotSupported", name(LedgerRuleName.UTXOS, "CollectErrors")),
            entry("ProposalReturnAccountDoesNotExist",
                    name(LedgerRuleName.GOV, "ProposalReturnAccountDoesNotExist")),
            entry("ScriptWitnessNotValidatingUTXOW", name(LedgerRuleName.UTXOW, "ScriptWitnessNotValidatingUTXOW")),
            entry("ScriptsNotPaidUTxO", name(LedgerRuleName.UTXO, "ScriptsNotPaidUTxO")),
            entry("StakeCredentialInvalidPoolDelegation", name(LedgerRuleName.DELEG, "StakeKeyNotRegisteredDELEG")),
            entry("StakeCredentialInvalidVoteDelegation", name(LedgerRuleName.DELEG, "StakeKeyNotRegisteredDELEG")),
            entry("StakeKeyHasNonZeroAccountBalance",
                    name(LedgerRuleName.DELEG, "StakeKeyHasNonZeroAccountBalanceDELEG")),
            entry("StakeKeyRegistered", name(LedgerRuleName.DELEG, "StakeKeyRegisteredDELEG")),
            entry("StakeKeyNotRegistered", name(LedgerRuleName.DELEG, "StakeKeyNotRegisteredDELEG")),
            entry("StakePoolCostTooLowPOOL", name(LedgerRuleName.POOL, "StakePoolCostTooLowPOOL")),
            entry("StakePoolNotRegisteredOnKeyPOOL", name(LedgerRuleName.POOL, "StakePoolNotRegisteredOnKeyPOOL")),
            entry("StakePoolRetirementWrongEpochPOOL",
                    name(LedgerRuleName.POOL, "StakePoolRetirementWrongEpochPOOL")),
            entry("TooManyCollateralInputs", name(LedgerRuleName.UTXO, "TooManyCollateralInputs")),
            // CollectErrors [BadTranslation] of a ConwayContextError (Conway/TxInfo.hs:180-187), see COLLECT_ERRORS
            entry("TreasuryDonationFieldNotSupported", name(LedgerRuleName.UTXOS, "CollectErrors")),
            entry("TreasuryWithdrawalReturnAccountsDoNotExist",
                    name(LedgerRuleName.GOV, "TreasuryWithdrawalReturnAccountsDoNotExist")),
            entry("TreasuryWithdrawalsAllZeros", name(LedgerRuleName.GOV, "ZeroTreasuryWithdrawals")),
            entry("UnspendableUTxONoDatumHash", name(LedgerRuleName.UTXOW, "UnspendableUTxONoDatumHash")),
            entry("ValidationTagMismatch", name(LedgerRuleName.UTXOS, "ValidationTagMismatch")),
            entry("ValueNotConservedUTxO", name(LedgerRuleName.UTXO, "ValueNotConservedUTxO")),
            entry("VotersDoNotExist", name(LedgerRuleName.GOV, "VotersDoNotExist")),
            entry("VotingOnExpiredGovAction", name(LedgerRuleName.GOV, "VotingOnExpiredGovAction")),
            // CollectErrors [BadTranslation] of a ConwayContextError (Conway/TxInfo.hs:180-187), see COLLECT_ERRORS
            entry("VotingProceduresFieldNotSupported", name(LedgerRuleName.UTXOS, "CollectErrors")),
            entry("WithdrawalsNotInRewardsCERTS", name(LedgerRuleName.CERTS, "WithdrawalsNotInRewardsCERTS")),
            entry("WrongNetworkInTxBody", name(LedgerRuleName.UTXO, "WrongNetworkInTxBody")),
            entry("WrongNetworkInTxOutput", name(LedgerRuleName.UTXO, "WrongNetwork")),
            entry("WrongNetworkPOOL", name(LedgerRuleName.POOL, "WrongNetworkPOOL")),
            entry("WrongNetworkWithdrawal", name(LedgerRuleName.UTXO, "WrongNetworkWithdrawal")));

    /**
     * Corpus names that Amaru's Haskell checker also uses for another Haskell constructor
     * ({@code normalizeGovFailure}, {@code crates/amaru/tests/conformance/validation-rules/src/Command/ValidatePhaseOne/Run.hs}):
     * a scenario expecting the corpus name is satisfied by either. {@code UnelectedCommitteeVoters} (the PV 11 {@code GOV}
     * predicate, Conway/Rules/Gov.hs:478-481) is normalised to {@code "VotersDoNotExist"} (Run.hs:532-533), so scenario
     * 00171 names {@code VotersDoNotExist} although Haskell reports {@code UnelectedCommitteeVoters}. Likewise
     * {@code normalizeGovCertFailure} (Run.hs:549-550 at {@code 40ba9950}) names the {@code GOVCERT} predicate
     * {@code ConwayDRepIncorrectRefund} (Conway/Rules/GovCert.hs:113, :242) {@code "IncorrectDepositDRep"}, so scenario
     * 00305 names it so.
     *
     * <p>Temporary: {@code OutputTooBigUTxO} also covers {@code UTXO.OutputBootAddrAttrsTooBig}. Before cardano-ledger
     * {@code c0f8975c0c} ("Fix shelleyToAllegraUtxoPredFailure mapping", cardano-ledger-allegra 1.10.2.0, first in
     * cardano-node 11.2.0) Haskell injected Shelley's {@code OutputBootAddrAttrsTooBig} as {@code OutputTooBigUTxO}, so
     * cardano-node 11.1.3, both pins ({@code f649f975}, {@code 6176e413}) and scenario 00280 name an oversized Byron
     * attribute so. Yano reports the fixed name. Remove this alias once 00280 expects {@code OutputBootAddrAttrsTooBig}.
     */
    private static final Map<String, Set<String>> ALIASES = Map.of(
            "VotersDoNotExist", Set.of("GOV.UnelectedCommitteeVoters"),
            "IncorrectDepositDRep", Set.of("GOVCERT.ConwayDRepIncorrectRefund"),
            "OutputTooBigUTxO", Set.of("UTXO.OutputBootAddrAttrsTooBig"));

    /**
     * The corpus names that share {@code UTXOS.CollectErrors}, with the {@code CollectError} each stands for. Amaru's
     * checker tells them apart by that error's text in the failure ({@code normalizeUtxoFailure}, Run.hs:495-504 at
     * {@code 40ba9950}: the expected name if it occurs, else {@code NoCostModel} or
     * {@code TimeTranslationPastHorizon}); Yano's {@code CollectErrors} detail lists the errors under the same Haskell
     * names ({@code ScriptCollection.collectErrors}).
     */
    private static final Map<String, String> COLLECT_ERRORS = Map.of(
            "ByronTxOutInContext", "ByronTxOutInContext",
            "CurrentTreasuryFieldNotSupported", "CurrentTreasuryFieldNotSupported",
            "InlineDatumsNotSupported", "InlineDatumsNotSupported",
            "MissingCostModel", "NoCostModel",
            "OutsideForecast", "TimeTranslationPastHorizon",
            "ProposalProceduresFieldNotSupported", "ProposalProceduresFieldNotSupported",
            "TreasuryDonationFieldNotSupported", "TreasuryDonationFieldNotSupported",
            "VotingProceduresFieldNotSupported", "VotingProceduresFieldNotSupported");

    private AmaruCorpusNames() {
    }

    /**
     * @return whether a failure's detail fits the corpus name: always, except that a name sharing
     *         {@code UTXOS.CollectErrors} needs its {@code CollectError} in the detail
     */
    public static boolean detailMatches(String corpusName, String detail) {
        String collectError = COLLECT_ERRORS.get(corpusName);
        return collectError == null || detail.contains(collectError);
    }

    /**
     * @return the other Haskell {@code RULE.Constructor}s Amaru's checker reports under this corpus name (empty for
     *         most names)
     */
    public static Set<String> aliases(String corpusName) {
        return ALIASES.getOrDefault(corpusName, Set.of());
    }

    private static HaskellName name(LedgerRuleName rule, String constructor) {
        return new HaskellName(rule, constructor);
    }

    /** @return the Haskell name for a corpus predicate, or empty when the table has no entry */
    public static Optional<HaskellName> haskellName(String corpusName) {
        return Optional.ofNullable(NAMES.get(corpusName));
    }

    /**
     * The corpus name for a Haskell {@code RULE.Constructor}, aliases included, whose detail fits it
     * ({@link #detailMatches}). Where several corpus names still share one Haskell constructor
     * ({@code StakeKeyNotRegisteredDELEG}), the first in alphabetical order, which is also the default of Amaru's
     * Haskell checker ({@code normalizeDelegFailure}). For a {@code CollectErrors} listing several errors, none of them
     * expected, the checker prefers {@code MissingCostModel}, then {@code OutsideForecast} (Run.hs:499-502); the
     * alphabetical choice here differs only in how such a mismatch is named.
     *
     * @param detail the failure's detail
     * @return the corpus name, or empty when no corpus name maps to the failure
     */
    public static Optional<String> corpusName(String qualifiedName, String detail) {
        return NAMES.entrySet().stream()
                .filter(e -> (e.getValue().rule() + "." + e.getValue().constructor()).equals(qualifiedName)
                        || aliases(e.getKey()).contains(qualifiedName))
                .filter(e -> detailMatches(e.getKey(), detail))
                .map(Map.Entry::getKey)
                .sorted()
                .findFirst();
    }
}
