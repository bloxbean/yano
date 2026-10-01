package org.yanoproject.ledger.conformance.engines;

import org.yanoproject.ledger.conformance.runner.Observation;
import org.yanoproject.ledger.conformance.runner.Observation.Failure;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.model.GovActionId;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Map.entry;

/**
 * Names the legacy validators' failures after the Haskell rule and constructor, as well as their text allows.
 *
 * <ul>
 *   <li><b>Copied Java rules</b> ({@code LedgerStateValidator}, and the supplementary rules of the legacy Scalus
 *       path): a rule name such as {@code CertificateValidation} and a free-text message. The message is matched
 *       against the texts the rules produce ({@code ledger-rules/.../conway/rule/*}).</li>
 *   <li><b>Legacy Scalus path</b> ({@code ScalusBasedTransactionValidator}): the Scalus exception's simple class
 *       name without {@code Exception}, and its message. Classes that fold several Haskell constructors are split
 *       by the message where Scalus's text says which part failed.</li>
 * </ul>
 *
 * <p>Anything else is reported as {@link Observation#UNMAPPED} with the engine's own name, so the report shows it
 * instead of guessing.</p>
 */
final class LegacyFailureNames {

    private LegacyFailureNames() {
    }

    private record Rule(Pattern pattern, Function<Context, String> name) {
    }

    /** What a mapping may consult besides the message. */
    record Context(String message, int protocolMajor, LedgerView view) {
        boolean pv11() {
            return protocolMajor >= 11;
        }
    }

    private static Rule rule(String regex, String qualifiedName) {
        return new Rule(Pattern.compile(regex), c -> qualifiedName);
    }

    private static Rule rule(String regex, Function<Context, String> name) {
        return new Rule(Pattern.compile(regex), name);
    }

    private static final Pattern VOTE_TARGET = Pattern.compile("target governance action ([0-9a-fA-F]{64})#(\\d+)");

    /** Message patterns per copied Java rule, in match order. */
    private static final Map<String, List<Rule>> JAVA_RULES = Map.ofEntries(
            entry("InputValidation", List.of(
                    rule("^Transaction has no inputs", "UTXO.InputSetEmptyUTxO"),
                    rule("(Spending|Reference) input not found", "UTXO.BadInputsUTxO"),
                    rule("^Reference inputs overlap", "UTXO.BabbageNonDisjointRefInputs"))),
            entry("TxSizeValidation", List.of(rule("exceeds maxTxSize", "UTXO.MaxTxSizeUTxO"))),
            entry("ValidityInterval", List.of(rule("^Current slot", "UTXO.OutsideValidityIntervalUTxO"))),
            entry("NetworkIdValidation", List.of(
                    rule("^Transaction body network ID", "UTXO.WrongNetworkInTxBody"),
                    rule("^Output\\[", "UTXO.WrongNetwork"),
                    rule("^Withdrawal address", "UTXO.WrongNetworkWithdrawal"))),
            entry("OutputValidation", List.of(
                    rule("is below minUTxO", "UTXO.BabbageOutputTooSmallUTxO"),
                    rule("exceeds maxValueSize", "UTXO.OutputTooBigUTxO"))),
            entry("FeeAndCollateral", List.of(
                    rule("below minimum required fee", "UTXO.FeeTooSmallUTxO"),
                    rule("^No collateral inputs", "UTXO.NoCollateralInputs"),
                    rule("^Collateral input count", "UTXO.TooManyCollateralInputs"),
                    rule("^Collateral input not found", "UTXO.BadInputsUTxO"),
                    rule("is at a script address", "UTXO.ScriptsNotPaidUTxO"),
                    rule("non-ADA", "UTXO.CollateralContainsNonADA"),
                    rule("^Insufficient collateral", "UTXO.InsufficientCollateral"),
                    rule("^Declared totalCollateral", "UTXO.IncorrectTotalCollateralField"),
                    rule("^ExUnits", "UTXO.ExUnitsTooBigUTxO"),
                    rule("^Total reference script size", "LEDGER.ConwayTxRefScriptsSizeTooBig"))),
            entry("ValueConservation", List.of(rule("^Value not conserved", "UTXO.ValueNotConservedUTxO"))),
            entry("WitnessValidation", List.of(
                    rule("^Missing required VKey witnesses", "UTXOW.MissingVKeyWitnessesUTXOW"),
                    rule("^(Invalid Ed25519 signature|Signature verification failed|VkeyWitness\\[)",
                            "UTXOW.InvalidWitnessesUTXOW"),
                    rule("^Missing script witnesses", "UTXOW.MissingScriptWitnessesUTXOW"),
                    rule("^Extraneous script witnesses", "UTXOW.ExtraneousScriptWitnessesUTXOW"),
                    rule("^Native script evaluation failed", "UTXOW.ScriptWitnessNotValidatingUTXOW"),
                    rule("^auxiliaryDataHash is present but no AuxiliaryData", "UTXOW.MissingTxMetadata"),
                    rule("^AuxiliaryData is present but auxiliaryDataHash is missing",
                            "UTXOW.MissingTxBodyMetadataHash"),
                    rule("^auxiliaryDataHash mismatch", "UTXOW.ConflictingMetadataHash"),
                    rule("^scriptDataHash", c -> c.pv11() ? "UTXOW.ScriptIntegrityHashMismatch"
                            : "UTXOW.PPViewHashesDontMatch"),
                    rule("^Missing required datums", "UTXOW.MissingRequiredDatums"),
                    rule("^Extraneous supplemental datums", "UTXOW.NotAllowedSupplementalDatums"),
                    rule("does not correspond to any script purpose", "UTXOW.ExtraRedeemers"))),
            entry("CertificateValidation", List.of(
                    rule("RegDRepCert: DRep .* is already registered", "GOVCERT.ConwayDRepAlreadyRegistered"),
                    rule("is already registered", "DELEG.StakeKeyRegisteredDELEG"),
                    rule("has non-zero reward balance", "DELEG.StakeKeyHasNonZeroAccountBalanceDELEG"),
                    rule("UnregCert: refund", c -> c.pv11() ? "DELEG.RefundIncorrectDELEG"
                            : "DELEG.IncorrectDepositDELEG"),
                    rule("delegatee DRep", "DELEG.DelegateeDRepNotRegisteredDELEG"),
                    rule("PoolRetirement: pool .* is not registered", "POOL.StakePoolNotRegisteredOnKeyPOOL"),
                    rule("PoolRetirement: epoch", "POOL.StakePoolRetirementWrongEpochPOOL"),
                    rule(": pool .* is not registered", "DELEG.DelegateeStakePoolNotRegisteredDELEG"),
                    rule("PoolRegistration: cost", "POOL.StakePoolCostTooLowPOOL"),
                    rule("PoolRegistration: metadata hash size", "POOL.PoolMedataHashTooBig"),
                    rule("RegDRepCert: deposit", "GOVCERT.ConwayDRepIncorrectDeposit"),
                    rule("(UnregDRepCert|UpdateDRepCert): DRep .* is not registered", "GOVCERT.ConwayDRepNotRegistered"),
                    rule("UnregDRepCert: refund", "GOVCERT.ConwayDRepIncorrectRefund"),
                    rule("is not a committee member", "GOVCERT.ConwayCommitteeIsUnknown"),
                    rule("has previously resigned", "GOVCERT.ConwayCommitteeHasPreviouslyResigned"),
                    rule("^Withdrawal: credential .* is not registered", c -> c.pv11()
                            ? "LEDGER.ConwayWithdrawalsMissingAccounts" : "CERTS.WithdrawalsNotInRewardsCERTS"),
                    rule("^Withdrawal: amount", c -> c.pv11()
                            ? "LEDGER.ConwayIncompleteWithdrawals" : "CERTS.WithdrawalsNotInRewardsCERTS"),
                    rule("credential .* is not registered", "DELEG.StakeKeyNotRegisteredDELEG"),
                    rule("does not match pp.keyDeposit", c -> c.pv11() ? "DELEG.DepositIncorrectDELEG"
                            : "DELEG.IncorrectDepositDELEG"),
                    rule("reward account network", "POOL.WrongNetworkPOOL"))),
            entry("GovernanceValidation", List.of(
                    rule("^Proposal\\[\\d+\\]: deposit", "GOV.ProposalDepositIncorrect"),
                    rule("return account credential", "GOV.ProposalReturnAccountDoesNotExist"),
                    rule("prevGovActionId", "GOV.InvalidPrevGovActionId"),
                    rule("(amount must be > 0|aggregate withdrawal sum)", "GOV.ZeroTreasuryWithdrawals"),
                    rule("TreasuryWithdrawal\\[\\d+\\]: address network", "GOV.TreasuryWithdrawalsNetworkIdMismatch"),
                    rule("destination account credential", "GOV.TreasuryWithdrawalReturnAccountsDoNotExist"),
                    rule("expiration epoch", "GOV.ExpirationEpochTooSmall"),
                    rule("in both membersForRemoval", "GOV.ConflictingCommitteeUpdate"),
                    rule("^Vote: target governance action", LegacyFailureNames::inactiveVoteTarget),
                    rule("^Vote: (DRep|pool|committee hot) voter", "GOV.VotersDoNotExist"),
                    rule("not allowed to vote on action type", "GOV.DisallowedVoters"),
                    rule("^Proposal\\[\\d+\\]: reward account network", "GOV.ProposalProcedureNetworkIdMismatch"))));

    /**
     * The Java rules report a vote on an unknown and on an expired action with the same text; the view tells the
     * two Haskell constructors apart.
     */
    private static String inactiveVoteTarget(Context context) {
        Matcher m = VOTE_TARGET.matcher(context.message());
        if (m.find() && context.view() != null
                && context.view().proposal(new GovActionId(m.group(1), Integer.parseInt(m.group(2)))).isPresent()) {
            return "GOV.VotingOnExpiredGovAction";
        }
        return "GOV.GovActionsDoNotExist";
    }

    /** @return the Haskell-named failure for a copied Java rule's error */
    static Failure javaRule(String ruleName, String message, int protocolMajor, LedgerView view) {
        String text = message == null ? "" : message;
        List<Rule> rules = JAVA_RULES.get(ruleName);
        if (rules != null) {
            Context context = new Context(text, protocolMajor, view);
            for (Rule rule : rules) {
                if (rule.pattern().matcher(text).find()) {
                    return failure(rule.name().apply(context), ruleName + ": " + text);
                }
            }
        }
        return new Failure(Observation.UNMAPPED, ruleName, text);
    }

    /**
     * Scalus exception classes (simple name without {@code Exception}) with a single Haskell counterpart,
     * following {@code ScalusFailureMapping}.
     */
    private static final Map<String, String> SCALUS_CLASSES = Map.ofEntries(
            entry("EmptyInputs", "UTXO.InputSetEmptyUTxO"),
            entry("NonDisjointInputsAndReferenceInputs", "UTXO.BabbageNonDisjointRefInputs"),
            entry("BadAllInputsUTxO", "UTXO.BadInputsUTxO"),
            entry("BadInputsUTxO", "UTXO.BadInputsUTxO"),
            entry("BadCollateralInputsUTxO", "UTXO.BadInputsUTxO"),
            entry("BadReferenceInputsUTxO", "UTXO.BadInputsUTxO"),
            entry("InvalidSignaturesInWitnesses", "UTXOW.InvalidWitnessesUTXOW"),
            entry("MissingKeyHashes", "UTXOW.MissingVKeyWitnessesUTXOW"),
            entry("NativeScripts", "UTXOW.ScriptWitnessNotValidatingUTXOW"),
            entry("InvalidTransactionSize", "UTXO.MaxTxSizeUTxO"),
            entry("OutputsHaveNotEnoughCoins", "UTXO.BabbageOutputTooSmallUTxO"),
            entry("OutputsHaveTooBigValueStorageSize", "UTXO.OutputTooBigUTxO"),
            entry("OutsideValidityInterval", "UTXO.OutsideValidityIntervalUTxO"),
            entry("ValueNotConservedUTxO", "UTXO.ValueNotConservedUTxO"),
            entry("WithdrawalsNotInRewards", "CERTS.WithdrawalsNotInRewardsCERTS"),
            entry("ExUnitsExceedMax", "UTXO.ExUnitsTooBigUTxO"),
            entry("TooManyCollateralInputs", "UTXO.TooManyCollateralInputs"),
            entry("WrongNetworkAddress", "UTXO.WrongNetwork"),
            entry("WrongNetworkWithdrawal", "UTXO.WrongNetworkWithdrawal"),
            entry("WrongNetworkInTxBody", "UTXO.WrongNetworkInTxBody"),
            entry("MissingAuxiliaryData", "UTXOW.MissingTxMetadata"),
            entry("MissingAuxiliaryDataHash", "UTXOW.MissingTxBodyMetadataHash"),
            entry("InvalidAuxiliaryDataHash", "UTXOW.ConflictingMetadataHash"),
            entry("InvalidAuxiliaryData", "UTXOW.InvalidMetadata"),
            entry("OutputBootAddrAttrsTooBig", "UTXO.OutputBootAddrAttrsTooBig"),
            entry("PlutusScriptValidation", "UTXOS.ValidationTagMismatch"));

    /** Scalus exception classes whose generic message does not say which of their constructors failed. */
    private static final Set<String> SCALUS_AMBIGUOUS = Set.of("StakeCertificates", "StakePool");

    private static final Pattern NON_EMPTY_MISSING = Pattern.compile("missing [a-z ]*script hashes: Set\\([^)]");
    private static final Pattern NON_EMPTY_WITNESS_SCRIPTS = Pattern.compile("invalid witnesses scripts: Set\\([^)]");

    /**
     * @return the Haskell-named failure for a legacy Scalus error ({@code ValidationError.rule()}, the exception's
     *         simple class name without {@code Exception}, and its message)
     */
    static Failure scalus(String className, String message, int protocolMajor) {
        String text = message == null ? "" : message;
        String raw = className + ": " + text;
        if (text.contains("(input position ")) {
            // A CBOR decoding error: the legacy path decodes with Scalus's strict decoder.
            return new Failure("ENGINE", "DecodingFailure", raw);
        }
        String single = SCALUS_CLASSES.get(className);
        if (single != null) {
            return failure(single, raw);
        }
        if (SCALUS_AMBIGUOUS.contains(className)) {
            // The legacy error keeps only "… validation failed for transactionId …": the constructor is lost.
            return new Failure(Observation.UNMAPPED, className, raw);
        }
        String name = switch (className) {
            case "IllegalArgument" -> text.startsWith("Transaction with invalid flag passed script validation")
                    ? "UTXOS.ValidationTagMismatch" : null;
            case "MissingOrExtraScriptHashes" -> NON_EMPTY_MISSING.matcher(text).find()
                    ? "UTXOW.MissingScriptWitnessesUTXOW" : "UTXOW.ExtraneousScriptWitnessesUTXOW";
            case "FeesOk" -> feesOk(text);
            case "InvalidScriptDataHash" -> protocolMajor >= 11 ? "UTXOW.ScriptIntegrityHashMismatch"
                    : "UTXOW.PPViewHashesDontMatch";
            case "IllFormedScripts" -> NON_EMPTY_WITNESS_SCRIPTS.matcher(text).find()
                    ? "UTXOW.MalformedScriptWitnesses" : "UTXOW.MalformedReferenceScripts";
            case "ExactSetOfRedeemers" -> contains(text, "extra") ? "UTXOW.ExtraRedeemers" : "UTXOW.MissingRedeemers";
            case "Datums" -> datums(text);
            default -> null;
        };
        if (name != null) {
            return failure(name, raw);
        }
        return new Failure(Observation.UNMAPPED, className, raw);
    }

    /** Scalus's {@code FeesOkException} texts, in Haskell's {@code feesOK} order (parts 1, 3–7). */
    private static String feesOk(String text) {
        if (contains(text, "is less than minimum required")) {
            return "UTXO.FeeTooSmallUTxO";
        }
        if (contains(text, "non-VKey addresses")) {
            return "UTXO.ScriptsNotPaidUTxO";
        }
        if (contains(text, "non-ADA") || contains(text, "not only ADA")) {
            return "UTXO.CollateralContainsNonADA";
        }
        if (contains(text, "is insufficient")) {
            return "UTXO.InsufficientCollateral";
        }
        if (contains(text, "does not match expected")) {
            return "UTXO.IncorrectTotalCollateralField";
        }
        if (contains(text, "no collateral inputs")) {
            return "UTXO.NoCollateralInputs";
        }
        return null;
    }

    private static String datums(String text) {
        if (contains(text, "missing datum hashes")) {
            return "UTXOW.UnspendableUTxONoDatumHash";
        }
        if (contains(text, "supplemental")) {
            return "UTXOW.NotAllowedSupplementalDatums";
        }
        return "UTXOW.MissingRequiredDatums";
    }

    private static boolean contains(String text, String needle) {
        return text.toLowerCase().contains(needle.toLowerCase());
    }

    private static Failure failure(String qualifiedName, String raw) {
        int dot = qualifiedName.indexOf('.');
        return new Failure(qualifiedName.substring(0, dot), qualifiedName.substring(dot + 1), raw);
    }
}
