package org.yanoproject.ledger.rules.fixtures.amaru;

import org.yanoproject.ledger.rules.LedgerRuleName;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

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
            entry("ByronTxOutInContext", name(LedgerRuleName.UTXOS, "CollectErrors")),
            entry("CommitteeHasPreviouslyResigned",
                    name(LedgerRuleName.GOVCERT, "ConwayCommitteeHasPreviouslyResigned")),
            entry("CommitteeIsUnknown", name(LedgerRuleName.GOVCERT, "ConwayCommitteeIsUnknown")),
            entry("ConflictingCommitteeUpdate", name(LedgerRuleName.GOV, "ConflictingCommitteeUpdate")),
            entry("ConflictingMetadataHash", name(LedgerRuleName.UTXOW, "ConflictingMetadataHash")),
            entry("ConwayTreasuryValueMismatch", name(LedgerRuleName.LEDGER, "ConwayTreasuryValueMismatch")),
            entry("ConwayTxRefScriptsSizeTooBig", name(LedgerRuleName.LEDGER, "ConwayTxRefScriptsSizeTooBig")),
            entry("ConwayWdrlNotDelegatedToDRep", name(LedgerRuleName.LEDGER, "ConwayWdrlNotDelegatedToDRep")),
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
            entry("IncorrectTotalCollateralField", name(LedgerRuleName.UTXO, "IncorrectTotalCollateralField")),
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
            // (crates/amaru/tests/conformance/validation-rules/.../ValidatePhaseOne/Run.hs:500-502); Haskell's own
            // OutsideForecast is unreachable in Conway at f649f975 (ADR-056 Phase 3a results).
            entry("OutsideForecast", name(LedgerRuleName.UTXOS, "CollectErrors")),
            entry("OutsideValidityIntervalUTxO", name(LedgerRuleName.UTXO, "OutsideValidityIntervalUTxO")),
            entry("PPViewHashesDontMatch", name(LedgerRuleName.UTXOW, "PPViewHashesDontMatch")),
            entry("ProposalCantFollow", name(LedgerRuleName.GOV, "ProposalCantFollow")),
            entry("ProposalDepositIncorrect", name(LedgerRuleName.GOV, "ProposalDepositIncorrect")),
            entry("ProposalProcedureNetworkIdMismatch",
                    name(LedgerRuleName.GOV, "ProposalProcedureNetworkIdMismatch")),
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
            entry("TreasuryDonationFieldNotSupported", name(LedgerRuleName.UTXOS, "CollectErrors")),
            entry("TreasuryWithdrawalReturnAccountsDoNotExist",
                    name(LedgerRuleName.GOV, "TreasuryWithdrawalReturnAccountsDoNotExist")),
            entry("TreasuryWithdrawalsAllZeros", name(LedgerRuleName.GOV, "ZeroTreasuryWithdrawals")),
            entry("UnspendableUTxONoDatumHash", name(LedgerRuleName.UTXOW, "UnspendableUTxONoDatumHash")),
            entry("ValidationTagMismatch", name(LedgerRuleName.UTXOS, "ValidationTagMismatch")),
            entry("ValueNotConservedUTxO", name(LedgerRuleName.UTXO, "ValueNotConservedUTxO")),
            entry("VotersDoNotExist", name(LedgerRuleName.GOV, "VotersDoNotExist")),
            entry("VotingOnExpiredGovAction", name(LedgerRuleName.GOV, "VotingOnExpiredGovAction")),
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
     * predicate, Conway/Rules/Gov.hs:478-481) is normalised to {@code "VotersDoNotExist"} (Run.hs:603-604), so scenario
     * 00171 names {@code VotersDoNotExist} although Haskell reports {@code UnelectedCommitteeVoters}.
     */
    private static final Map<String, Set<String>> ALIASES = Map.of(
            "VotersDoNotExist", Set.of("GOV.UnelectedCommitteeVoters"));

    /** A corpus name resolved for one scenario. */
    private record ScenarioName(String corpusName, HaskellName haskell) {
    }

    /**
     * Corpus names Amaru's Haskell checker gives to distinct Haskell failures, resolved per scenario; a scenario using
     * one that is not listed here fails to load. {@code normalizeGovCertFailure} (Run.hs:547-550) names both
     * {@code ConwayDRepIncorrectDeposit} and {@code ConwayDRepIncorrectRefund} {@code "IncorrectDepositDRep"}, which
     * Haskell tells apart (Conway/Rules/GovCert.hs:209-242). Same table as {@code scenario_haskell_name} in
     * {@code amaru-validator-wasm/tests/amaru_scenarios.rs}. Keyed by the scenario's description, its name without the
     * number: the node-diversity fixtures are copies of Amaru's scenarios numbered differently (fixture
     * {@code 00298-fail-drep-registration-with-an-incorrect-deposit} is Amaru's scenario 00347).
     */
    private static final Set<String> PER_SCENARIO = Set.of("IncorrectDepositDRep");

    private static final Map<String, ScenarioName> SCENARIO_NAMES = Map.of(
            "fail-drep-deregistration-with-incorrect-refund",
            new ScenarioName("IncorrectDepositDRep", name(LedgerRuleName.GOVCERT, "ConwayDRepIncorrectRefund")),
            "fail-drep-registration-with-an-incorrect-deposit",
            new ScenarioName("IncorrectDepositDRep", name(LedgerRuleName.GOVCERT, "ConwayDRepIncorrectDeposit")));

    private AmaruCorpusNames() {
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

    /**
     * @param scenario   the scenario's name (its file name without {@code .json})
     * @param corpusName the scenario's expected corpus predicate
     * @return the Haskell name the predicate stands for in this scenario, or empty when the table has no entry
     * @throws IllegalStateException for a corpus name resolved per scenario ({@link #PER_SCENARIO}) and an unlisted
     *                               scenario
     */
    public static Optional<HaskellName> haskellName(String scenario, String corpusName) {
        if (PER_SCENARIO.contains(corpusName)) {
            ScenarioName resolved = SCENARIO_NAMES.get(scenario.replaceFirst("^\\d+-", ""));
            if (resolved == null || !resolved.corpusName().equals(corpusName)) {
                throw new IllegalStateException(scenario + ": " + corpusName + " names more than one Haskell "
                        + "constructor; resolve it per scenario (AmaruCorpusNames.SCENARIO_NAMES)");
            }
            return Optional.of(resolved.haskell());
        }
        return Optional.ofNullable(NAMES.get(corpusName));
    }

    /**
     * The name Amaru's Haskell checker gives a constructor that several corpus names share, when no expected predicate
     * hints otherwise: {@code normalizeDelegFailure} names {@code StakeKeyNotRegisteredDELEG}
     * {@code StakeCredentialInvalidPoolDelegation} (Run.hs:528-529). It has no such default for {@code UTXOS}
     * ({@code CollectErrors}: by the failure's text, else unsupported; Run.hs:495-502).
     */
    private static final Map<String, String> CHECKER_DEFAULTS = Map.of(
            "DELEG.StakeKeyNotRegisteredDELEG", "StakeCredentialInvalidPoolDelegation");

    /**
     * The corpus name for a Haskell {@code RULE.Constructor}, aliases included. Where several corpus names share one
     * Haskell constructor, the checker's default ({@link #CHECKER_DEFAULTS}), or none when it has none.
     *
     * @return the corpus name, or empty when no corpus name, or no single one, maps to the constructor
     */
    public static Optional<String> corpusName(String qualifiedName) {
        List<String> names = Stream.concat(
                        NAMES.entrySet().stream()
                                .filter(e -> qualified(e.getValue()).equals(qualifiedName)
                                        || aliases(e.getKey()).contains(qualifiedName))
                                .map(Map.Entry::getKey),
                        SCENARIO_NAMES.values().stream()
                                .filter(s -> qualified(s.haskell()).equals(qualifiedName))
                                .map(ScenarioName::corpusName))
                .distinct()
                .toList();
        return names.size() == 1 ? Optional.of(names.getFirst())
                : Optional.ofNullable(CHECKER_DEFAULTS.get(qualifiedName));
    }

    private static String qualified(HaskellName name) {
        return name.rule() + "." + name.constructor();
    }
}
