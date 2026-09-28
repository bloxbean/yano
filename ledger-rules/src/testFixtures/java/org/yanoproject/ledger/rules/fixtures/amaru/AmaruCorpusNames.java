package org.yanoproject.ledger.rules.fixtures.amaru;

import org.yanoproject.ledger.rules.LedgerRuleName;

import java.util.Map;
import java.util.Optional;

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
            entry("CommitteeHasPreviouslyResigned",
                    name(LedgerRuleName.GOVCERT, "ConwayCommitteeHasPreviouslyResigned")),
            entry("CommitteeIsUnknown", name(LedgerRuleName.GOVCERT, "ConwayCommitteeIsUnknown")),
            entry("ConflictingMetadataHash", name(LedgerRuleName.UTXOW, "ConflictingMetadataHash")),
            entry("ConwayTreasuryValueMismatch", name(LedgerRuleName.LEDGER, "ConwayTreasuryValueMismatch")),
            entry("ConwayTxRefScriptsSizeTooBig", name(LedgerRuleName.LEDGER, "ConwayTxRefScriptsSizeTooBig")),
            entry("ConwayWdrlNotDelegatedToDRep", name(LedgerRuleName.LEDGER, "ConwayWdrlNotDelegatedToDRep")),
            entry("DRepAlreadyRegistered", name(LedgerRuleName.GOVCERT, "ConwayDRepAlreadyRegistered")),
            entry("DelegateeDRepNotRegistered", name(LedgerRuleName.DELEG, "DelegateeDRepNotRegisteredDELEG")),
            entry("DelegateeStakePoolNotRegistered",
                    name(LedgerRuleName.DELEG, "DelegateeStakePoolNotRegisteredDELEG")),
            entry("DisallowedVoters", name(LedgerRuleName.GOV, "DisallowedVoters")),
            entry("ExtraneousScriptWitnessesUTXOW", name(LedgerRuleName.UTXOW, "ExtraneousScriptWitnessesUTXOW")),
            entry("FeeTooSmallUTxO", name(LedgerRuleName.UTXO, "FeeTooSmallUTxO")),
            entry("GovActionsDoNotExist", name(LedgerRuleName.GOV, "GovActionsDoNotExist")),
            entry("IncorrectDepositDELEG", name(LedgerRuleName.DELEG, "IncorrectDepositDELEG")),
            entry("IncorrectTotalCollateralField", name(LedgerRuleName.UTXO, "IncorrectTotalCollateralField")),
            entry("InputSetEmptyUTxO", name(LedgerRuleName.UTXO, "InputSetEmptyUTxO")),
            entry("InsufficientCollateral", name(LedgerRuleName.UTXO, "InsufficientCollateral")),
            entry("InvalidGuardrailsScriptHash", name(LedgerRuleName.GOV, "InvalidGuardrailsScriptHash")),
            entry("InvalidPrevGovActionId", name(LedgerRuleName.GOV, "InvalidPrevGovActionId")),
            entry("InvalidWitnessesUTXOW", name(LedgerRuleName.UTXOW, "InvalidWitnessesUTXOW")),
            entry("MalformedReferenceScripts", name(LedgerRuleName.UTXOW, "MalformedReferenceScripts")),
            entry("MalformedScriptWitnesses", name(LedgerRuleName.UTXOW, "MalformedScriptWitnesses")),
            entry("MaxTxSizeUTxO", name(LedgerRuleName.UTXO, "MaxTxSizeUTxO")),
            entry("MissingScriptWitnessesUTXOW", name(LedgerRuleName.UTXOW, "MissingScriptWitnessesUTXOW")),
            entry("MissingTxBodyMetadataHash", name(LedgerRuleName.UTXOW, "MissingTxBodyMetadataHash")),
            entry("MissingTxMetadata", name(LedgerRuleName.UTXOW, "MissingTxMetadata")),
            entry("MissingVerificationKeyWitnessesUTXOW",
                    name(LedgerRuleName.UTXOW, "MissingVKeyWitnessesUTXOW")),
            entry("NoCollateralInputs", name(LedgerRuleName.UTXO, "NoCollateralInputs")),
            entry("OutputTooBigUTxO", name(LedgerRuleName.UTXO, "OutputTooBigUTxO")),
            // Amaru's Haskell checker names CollectErrors [BadTranslation TimeTranslationPastHorizon] so
            // (crates/amaru/tests/conformance/validation-rules/.../ValidatePhaseOne/Run.hs:443-445); Haskell's own
            // OutsideForecast is unreachable in Conway at f649f975 (ADR-056 Phase 3a results).
            entry("OutsideForecast", name(LedgerRuleName.UTXOS, "CollectErrors")),
            entry("OutsideValidityIntervalUTxO", name(LedgerRuleName.UTXO, "OutsideValidityIntervalUTxO")),
            entry("ProposalCantFollow", name(LedgerRuleName.GOV, "ProposalCantFollow")),
            entry("ProposalProcedureNetworkIdMismatch",
                    name(LedgerRuleName.GOV, "ProposalProcedureNetworkIdMismatch")),
            entry("ProposalReturnAccountDoesNotExist",
                    name(LedgerRuleName.GOV, "ProposalReturnAccountDoesNotExist")),
            entry("ScriptsNotPaidUTxO", name(LedgerRuleName.UTXO, "ScriptsNotPaidUTxO")),
            entry("StakeCredentialInvalidPoolDelegation", name(LedgerRuleName.DELEG, "StakeKeyNotRegisteredDELEG")),
            entry("StakeCredentialInvalidVoteDelegation", name(LedgerRuleName.DELEG, "StakeKeyNotRegisteredDELEG")),
            entry("StakeKeyHasNonZeroAccountBalance",
                    name(LedgerRuleName.DELEG, "StakeKeyHasNonZeroAccountBalanceDELEG")),
            entry("StakeKeyRegistered", name(LedgerRuleName.DELEG, "StakeKeyRegisteredDELEG")),
            entry("StakePoolCostTooLowPOOL", name(LedgerRuleName.POOL, "StakePoolCostTooLowPOOL")),
            entry("StakePoolNotRegisteredOnKeyPOOL", name(LedgerRuleName.POOL, "StakePoolNotRegisteredOnKeyPOOL")),
            entry("StakePoolRetirementWrongEpochPOOL",
                    name(LedgerRuleName.POOL, "StakePoolRetirementWrongEpochPOOL")),
            entry("TooManyCollateralInputs", name(LedgerRuleName.UTXO, "TooManyCollateralInputs")),
            entry("TreasuryWithdrawalReturnAccountsDoNotExist",
                    name(LedgerRuleName.GOV, "TreasuryWithdrawalReturnAccountsDoNotExist")),
            entry("TreasuryWithdrawalsAllZeros", name(LedgerRuleName.GOV, "ZeroTreasuryWithdrawals")),
            entry("ValidationTagMismatch", name(LedgerRuleName.UTXOS, "ValidationTagMismatch")),
            entry("ValueNotConservedUTxO", name(LedgerRuleName.UTXO, "ValueNotConservedUTxO")),
            entry("VotersDoNotExist", name(LedgerRuleName.GOV, "VotersDoNotExist")),
            entry("VotingOnExpiredGovAction", name(LedgerRuleName.GOV, "VotingOnExpiredGovAction")),
            entry("WithdrawalsNotInRewardsCERTS", name(LedgerRuleName.CERTS, "WithdrawalsNotInRewardsCERTS")),
            entry("WrongNetworkInTxBody", name(LedgerRuleName.UTXO, "WrongNetworkInTxBody")),
            entry("WrongNetworkInTxOutput", name(LedgerRuleName.UTXO, "WrongNetwork")),
            entry("WrongNetworkPOOL", name(LedgerRuleName.POOL, "WrongNetworkPOOL")),
            entry("WrongNetworkWithdrawal", name(LedgerRuleName.UTXO, "WrongNetworkWithdrawal")));

    private AmaruCorpusNames() {
    }

    private static HaskellName name(LedgerRuleName rule, String constructor) {
        return new HaskellName(rule, constructor);
    }

    /** @return the Haskell name for a corpus predicate, or empty when the table has no entry */
    public static Optional<HaskellName> haskellName(String corpusName) {
        return Optional.ofNullable(NAMES.get(corpusName));
    }
}
