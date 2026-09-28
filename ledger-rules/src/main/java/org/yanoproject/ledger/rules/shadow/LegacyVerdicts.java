package org.yanoproject.ledger.rules.shadow;

import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.ValidationError;
import org.yanoproject.ledger.rules.ValidationResult;
import org.yanoproject.ledger.rules.shadow.ShadowDumpBundle.RecordedOutcome;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns a verdict of the legacy {@code TransactionValidator} (the default Scalus admission path) into a
 * {@link RecordedOutcome} shadow engines can be compared with (ADR-056 §7, step 1d M4).
 *
 * <p>The legacy validator names a failure after the Scalus exception class ({@code BadInputsUTxO},
 * {@code ValueNotConservedUTxO}, …). Names with one unambiguous Haskell constructor are mapped to it; any
 * other legacy failure becomes a wildcard ({@link #isWildcard}): only the valid/invalid verdict is compared
 * for it.</p>
 */
public final class LegacyVerdicts {

    /** Engine name recorded for the legacy admission path. */
    public static final String LEGACY_ENGINE = "scalus-legacy";
    /** Constructor prefix of a legacy failure without a Haskell mapping. */
    public static final String WILDCARD_PREFIX = "Legacy:";

    private record Name(LedgerRuleName rule, String constructor) {
    }

    private static final Map<String, Name> MAPPED = Map.ofEntries(
            Map.entry("EmptyInputs", new Name(LedgerRuleName.UTXO, "InputSetEmptyUTxO")),
            Map.entry("NonDisjointInputsAndReferenceInputs",
                    new Name(LedgerRuleName.UTXO, "BabbageNonDisjointRefInputs")),
            Map.entry("BadAllInputsUTxO", new Name(LedgerRuleName.UTXO, "BadInputsUTxO")),
            Map.entry("BadInputsUTxO", new Name(LedgerRuleName.UTXO, "BadInputsUTxO")),
            Map.entry("BadCollateralInputsUTxO", new Name(LedgerRuleName.UTXO, "BadInputsUTxO")),
            Map.entry("BadReferenceInputsUTxO", new Name(LedgerRuleName.UTXO, "BadInputsUTxO")),
            Map.entry("UtxoNotFound", new Name(LedgerRuleName.UTXO, "BadInputsUTxO")),
            Map.entry("InvalidSignaturesInWitnesses", new Name(LedgerRuleName.UTXOW, "InvalidWitnessesUTXOW")),
            Map.entry("MissingKeyHashes", new Name(LedgerRuleName.UTXOW, "MissingVKeyWitnessesUTXOW")),
            Map.entry("NativeScripts", new Name(LedgerRuleName.UTXOW, "ScriptWitnessNotValidatingUTXOW")),
            Map.entry("InvalidTransactionSize", new Name(LedgerRuleName.UTXO, "MaxTxSizeUTxO")),
            Map.entry("OutputsHaveNotEnoughCoins", new Name(LedgerRuleName.UTXO, "BabbageOutputTooSmallUTxO")),
            Map.entry("OutputsHaveTooBigValueStorageSize", new Name(LedgerRuleName.UTXO, "OutputTooBigUTxO")),
            Map.entry("OutsideValidityInterval", new Name(LedgerRuleName.UTXO, "OutsideValidityIntervalUTxO")),
            Map.entry("ValueNotConservedUTxO", new Name(LedgerRuleName.UTXO, "ValueNotConservedUTxO")),
            Map.entry("WithdrawalsNotInRewards", new Name(LedgerRuleName.CERTS, "WithdrawalsNotInRewardsCERTS")),
            Map.entry("ExUnitsExceedMax", new Name(LedgerRuleName.UTXO, "ExUnitsTooBigUTxO")),
            Map.entry("TooManyCollateralInputs", new Name(LedgerRuleName.UTXO, "TooManyCollateralInputs")),
            Map.entry("WrongNetworkAddress", new Name(LedgerRuleName.UTXO, "WrongNetwork")),
            Map.entry("WrongNetworkWithdrawal", new Name(LedgerRuleName.UTXO, "WrongNetworkWithdrawal")),
            Map.entry("WrongNetworkInTxBody", new Name(LedgerRuleName.UTXO, "WrongNetworkInTxBody")),
            Map.entry("OutputBootAddrAttrsTooBig", new Name(LedgerRuleName.UTXO, "OutputBootAddrAttrsTooBig")),
            Map.entry("PlutusScriptValidation", new Name(LedgerRuleName.UTXOS, "ValidationTagMismatch")));

    private LegacyVerdicts() {
    }

    /** @return the legacy verdict as a recorded outcome, failures mapped where unambiguous */
    public static RecordedOutcome recorded(ValidationResult result) {
        if (result.valid()) {
            return new RecordedOutcome(LEGACY_ENGINE, true, List.of());
        }
        List<LedgerFailure> failures = new ArrayList<>();
        for (ValidationError error : result.errors()) {
            failures.add(toFailure(error));
        }
        if (failures.isEmpty()) {
            failures.add(new LedgerFailure(LedgerRuleName.ENGINE, WILDCARD_PREFIX + "Unknown",
                    LedgerFailure.Phase.PHASE_1, "rejected without errors"));
        }
        return new RecordedOutcome(LEGACY_ENGINE, false, failures);
    }

    static LedgerFailure toFailure(ValidationError error) {
        LedgerFailure.Phase phase = error.phase() == ValidationError.Phase.PHASE_2 ? LedgerFailure.Phase.PHASE_2
                : LedgerFailure.Phase.PHASE_1;
        String rule = error.rule() == null ? "Unknown" : error.rule();
        Name mapped = MAPPED.get(rule);
        String detail = error.message() == null ? "" : error.message();
        return mapped != null ? new LedgerFailure(mapped.rule(), mapped.constructor(), phase, detail)
                : new LedgerFailure(LedgerRuleName.ENGINE, WILDCARD_PREFIX + rule, phase, detail);
    }

    /** @return true for a legacy failure without a Haskell mapping */
    public static boolean isWildcard(LedgerFailure failure) {
        return failure != null && failure.rule() == LedgerRuleName.ENGINE
                && failure.constructor().startsWith(WILDCARD_PREFIX);
    }
}
