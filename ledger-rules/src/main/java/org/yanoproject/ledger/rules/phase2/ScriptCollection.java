package org.yanoproject.ledger.rules.phase2;

import com.bloxbean.cardano.client.address.util.AddressUtil;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.cert.Certificate;
import com.bloxbean.cardano.client.transaction.spec.cert.PoolRegistration;
import com.bloxbean.cardano.client.transaction.spec.cert.PoolRetirement;
import com.bloxbean.cardano.client.transaction.spec.cert.RegCert;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeDelegation;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeDeregistration;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeRegistration;
import com.bloxbean.cardano.client.transaction.spec.cert.UnregCert;

import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.conway.utxow.PlutusScriptDecoder;
import org.yanoproject.ledger.rules.view.model.Outpoints;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The engine-neutral part of the phase-2 contract every {@link ScriptPhaseEvaluator} implements (ADR-056 §5, Phase 7c):
 * what Haskell checks while collecting the scripts and their contexts ({@code collectPlutusScriptsWithContext},
 * Alonzo/Plutus/Evaluate.hs), and the parameters a run takes. Each evaluator adds only its interpreter: finding the
 * needed Plutus scripts, building the contexts and running them.
 *
 * <p>Checked against cardano-ledger {@code f649f975}:</p>
 * <ul>
 *   <li>{@code UTXOW.MalformedScriptWitnesses} / {@code MalformedReferenceScripts} ({@link #malformedScripts}).</li>
 *   <li>{@code UTXOS.CollectErrors} ({@link #collectErrors}), all errors accumulated in one failure:
 *       {@code NoRedeemer} for a needed Plutus script without a redeemer ({@code NoWitness} cannot occur, only provided
 *       scripts are collected); {@code NoCostModel}; {@code BadTranslation} when the {@code TxInfo} of a needed
 *       script's language cannot be built ({@link #translationError}) or a V1/V2 script is needed for a vote or a
 *       proposal ({@code PlutusPurposeNotSupported}).</li>
 *   <li>The budget of a script is its redeemer's declared ExUnits ({@link #budget}); the cost model is the protocol
 *       parameters' raw list ({@link #costModel}).</li>
 *   <li>The bootstrap phase ({@link #bootstrapPhase}): PlutusV3 contexts leave out {@code reg_cert} / {@code unreg_cert}
 *       deposits ({@link ScriptPhaseEvaluator#translatesBootstrapPhaseCertificateDeposits()}).</li>
 * </ul>
 */
public final class ScriptCollection {

    /** Haskell {@code ConwayUtxosPredFailure} constructor for collection failures. */
    public static final String COLLECT_ERRORS = "CollectErrors";

    private static final List<BigInteger> PLC_1_0_0 = List.of(BigInteger.ONE, BigInteger.ZERO, BigInteger.ZERO);
    private static final List<BigInteger> PLC_1_1_0 = List.of(BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO);

    /**
     * A Plutus script the transaction needs (Haskell {@code scriptsNeeded} restricted to the scripts provided as Plutus,
     * {@code resolveNeededPlutusScriptsWithPurpose}).
     *
     * @param purpose     the redeemer tag in lowercase ({@code spend}, {@code mint}, {@code cert}, {@code reward},
     *                    {@code voting}, {@code proposing})
     * @param index       the redeemer index within its purpose
     * @param scriptHash  the script hash, hex
     * @param language    1, 2 or 3 for Plutus V1, V2, V3
     * @param hasRedeemer whether the transaction carries a redeemer for it
     * @param script      the script's {@code PlutusBinary}
     */
    public record NeededScript(String purpose, long index, String scriptHash, int language, boolean hasRedeemer,
                               byte[] script) {

        /** @return {@code PlutusV1}, {@code PlutusV2}, … as CCL and Haskell name the language */
        public String languageName() {
            return "PlutusV" + language;
        }
    }

    private ScriptCollection() {
    }

    /** @return the redeemer purpose name of a redeemer tag (0 spend … 5 proposing) */
    public static String purposeName(int tag) {
        return switch (tag) {
            case 0 -> "spend";
            case 1 -> "mint";
            case 2 -> "cert";
            case 3 -> "reward";
            case 4 -> "voting";
            case 5 -> "proposing";
            default -> throw new IllegalArgumentException("redeemer tag " + tag);
        };
    }

    /** Haskell {@code hardforkConwayBootstrapPhase} (Conway/Era.hs:257-258). */
    public static boolean bootstrapPhase(int protocolMajor) {
        return protocolMajor == 9;
    }

    /**
     * {@code mkTermToEvaluate}'s Plutus Core version check (plutus-ledger-api {@code Common/Eval.hs:113-122}), part of
     * every evaluator's preparation: the program's version must be one of {@code plcVersionsAvailableIn ll pv}
     * ({@code Common/Versions.hs:341-357}), {@code 1.0.0} for PlutusV1/V2 and also {@code 1.1.0} from protocol version
     * 11, both for PlutusV3. The ledger decodes a script ({@code deserialiseScript}) without this check, so it is a
     * script failure when the script runs ({@code PlutusCoreLanguageNotAvailableError}, a {@code ScriptFailure} of
     * {@code evalPlutusScripts}): after {@code CollectErrors}, and the scripts fail whatever the others do, so an
     * evaluator returns {@link ScriptPhaseResult.Failed} without running any ({@code ValidationTagMismatch
     * FailedUnexpectedly} for {@code isValid = True}, accepted for {@code isValid = False}).
     *
     * @param needed the needed Plutus scripts, which passed {@link #collectErrors}
     * @return a failed outcome per script whose version its language cannot run; empty when every one can
     */
    public static List<ScriptOutcome> plutusCoreVersionFailures(List<NeededScript> needed, int protocolMajor) {
        List<ScriptOutcome> failures = new ArrayList<>();
        for (NeededScript script : needed) {
            plutusCoreVersionError(script.language(), script.script(), protocolMajor).ifPresent(error ->
                    failures.add(new ScriptOutcome(script.purpose(), (int) script.index(), false, 0, 0, List.of(),
                            "script " + script.scriptHash() + " (" + script.languageName() + ", " + script.purpose()
                                    + "[" + script.index() + "]): " + error)));
        }
        return failures;
    }

    /**
     * @param script the {@code PlutusBinary}
     * @return the {@code PlutusCoreLanguageNotAvailableError} of {@link #plutusCoreVersionFailures}, or empty when the
     *         version is available (or the binary holds no program header, which the script decoder reports)
     */
    public static Optional<String> plutusCoreVersionError(int language, byte[] script, int protocolMajor) {
        return PlutusScriptDecoder.programVersion(script)
                .filter(v -> !(v.equals(PLC_1_0_0) || v.equals(PLC_1_1_0) && (language == 3 || protocolMajor >= 11)))
                .map(v -> "PlutusCoreLanguageNotAvailableError " + v.get(0) + "." + v.get(1) + "." + v.get(2)
                        + " PlutusV" + language + " protocol version " + protocolMajor);
    }

    /** @throws IllegalArgumentException when the parameters carry no protocol major version */
    public static int protocolMajor(ProtocolParams params) {
        return requireVersion(params.getProtocolMajorVer(), "protocol major version");
    }

    /** @throws IllegalArgumentException when the parameters carry no protocol minor version */
    public static int protocolMinor(ProtocolParams params) {
        return requireVersion(params.getProtocolMinorVer(), "protocol minor version");
    }

    private static int requireVersion(Integer value, String what) {
        if (value == null) {
            throw new IllegalArgumentException("protocol parameters carry no " + what);
        }
        return value;
    }

    /**
     * @param validationSlot the slot after the ledger tip; negative when unknown
     * @return the first slot past {@code horizon}, or -1 to skip the {@code TimeTranslationPastHorizon} check
     */
    public static long horizonSlot(ForecastHorizon horizon, long validationSlot) {
        return horizon != null && validationSlot >= 0 ? horizon.exclusiveUpperSlot(validationSlot) : -1;
    }

    /** @return whether {@code params} have a cost model for {@code language} ({@code PlutusV1} …) */
    public static boolean hasCostModel(ProtocolParams params, String language) {
        Map<String, List<Long>> raw = params.getCostModelsRaw();
        if (raw != null && raw.get(language) != null && !raw.get(language).isEmpty()) {
            return true;
        }
        return params.getCostModels() != null && params.getCostModels().get(language) != null
                && !params.getCostModels().get(language).isEmpty();
    }

    /**
     * @return the cost model of Plutus {@code language} in the ledger's parameter order: the raw list (the named map
     *         does not keep that order)
     * @throws IllegalStateException when the parameters carry no raw list for the language
     */
    public static List<Long> costModel(ProtocolParams params, int language) {
        Map<String, List<Long>> raw = params.getCostModelsRaw();
        List<Long> values = raw != null ? raw.get("PlutusV" + language) : null;
        if (values == null || values.isEmpty()) {
            throw new IllegalStateException("the protocol parameters carry no raw PlutusV" + language + " cost model");
        }
        return values;
    }

    /**
     * A declared ExUnits component as a machine budget. {@code UTXO.ExUnitsTooBigUTxO} bounds the declared units by
     * {@code maxTxExUnits} in phase one, so a value above a {@code long} never reaches a script; it saturates.
     */
    public static long budget(BigInteger declared) {
        return declared.bitLength() > 63 ? Long.MAX_VALUE : declared.longValue();
    }

    /**
     * {@code validateScriptsWellFormed} (Babbage/Rules/Utxow.hs:264-273).
     *
     * @param witnesses  the hashes of the Plutus witness scripts that are not well formed
     * @param references the hashes of the transaction's own outputs' Plutus reference scripts (with the collateral
     *                   return) that are not well formed
     * @return the {@code UTXOW} failures, empty when both are empty
     */
    public static List<LedgerFailure> malformedScripts(Collection<String> witnesses, Collection<String> references) {
        List<LedgerFailure> failures = new ArrayList<>();
        if (!witnesses.isEmpty()) {
            failures.add(new LedgerFailure(LedgerRuleName.UTXOW, "MalformedScriptWitnesses",
                    LedgerFailure.Phase.PHASE_1, String.join(", ", witnesses)));
        }
        if (!references.isEmpty()) {
            failures.add(new LedgerFailure(LedgerRuleName.UTXOW, "MalformedReferenceScripts",
                    LedgerFailure.Phase.PHASE_1, String.join(", ", references)));
        }
        return failures;
    }

    /** @return the {@code UTXOS.CollectErrors} failure listing {@code errors} */
    public static LedgerFailure collectErrorsFailure(List<String> errors) {
        return new LedgerFailure(LedgerRuleName.UTXOS, COLLECT_ERRORS, LedgerFailure.Phase.PHASE_1,
                String.join("; ", errors));
    }

    /**
     * {@code collectPlutusScriptsWithContext}'s failures, accumulated: per needed script {@code NoRedeemer}, else
     * {@code NoCostModel}, else a V1/V2 voting or proposing purpose; then, for every language whose {@code TxInfo} is
     * built (a needed script with a redeemer and a cost model), its first translation failure.
     *
     * @param horizonSlot the first slot past the forecast horizon, or negative to skip that check
     * @return the errors, empty when the scripts can run
     */
    public static List<String> collectErrors(Transaction tx, Map<Outpoint, UtxoEntry> resolved, ProtocolParams params,
                                             int protocolMajor, List<NeededScript> needed, long horizonSlot) {
        List<String> errors = new ArrayList<>();
        Set<Integer> languages = new TreeSet<>();
        for (NeededScript script : needed) {
            if (!script.hasRedeemer()) {
                // NoRedeemer (Alonzo/Plutus/Evaluate.hs:151-155): UTXOW reports MissingRedeemers too, UTXOS this.
                errors.add("NoRedeemer " + script.purpose() + "[" + script.index() + "]");
                continue;
            }
            if (!hasCostModel(params, script.languageName())) {
                errors.add("NoCostModel " + script.languageName());
                continue;
            }
            if ((script.language() == 1 || script.language() == 2)
                    && (script.purpose().equals("voting") || script.purpose().equals("proposing"))) {
                errors.add("BadTranslation PlutusPurposeNotSupported " + script.purpose() + "[" + script.index()
                        + "] for " + script.languageName());
            }
            languages.add(script.language());
        }
        for (int language : languages) {
            translationError(tx, resolved, protocolMajor, language, horizonSlot)
                    .ifPresent(e -> errors.add("BadTranslation " + e + " for PlutusV" + language));
        }
        return errors;
    }

    /**
     * Haskell {@code transValidityInterval}: each validity bound is converted to POSIX time with the ledger's
     * {@code EpochInfo}, which fails past the forecast horizon ({@link ForecastHorizon}).
     *
     * @param exclusiveUpperSlot the first slot past the horizon
     * @return {@code TimeTranslationPastHorizon ...} when a bound is at or past it
     */
    public static Optional<String> horizonError(Transaction tx, long exclusiveUpperSlot) {
        TransactionBody body = tx.getBody();
        long lower = body.getValidityStartInterval();
        long upper = body.getTtl();
        if (lower > 0 && lower >= exclusiveUpperSlot) {
            return Optional.of("TimeTranslationPastHorizon validity start " + lower + " is at or past the forecast "
                    + "horizon (slot " + exclusiveUpperSlot + ")");
        }
        if (upper > 0 && upper >= exclusiveUpperSlot) {
            return Optional.of("TimeTranslationPastHorizon ttl " + upper + " is at or past the forecast horizon (slot "
                    + exclusiveUpperSlot + ")");
        }
        return Optional.empty();
    }

    /**
     * The {@code TxInfo} translation failures of {@code language}, checked in the order Conway builds that language's
     * {@code TxInfo} (Conway/TxInfo.hs:399-520): the V1/V2 Conway-feature guard ({@code guardConwayFeaturesForPlutusV1V2},
     * :352-381), the validity interval (with a horizon), spending inputs, reference inputs, from protocol version 11
     * the V3 disjointness of spending and reference inputs ({@code ReferenceInputsNotDisjointFromInputs}, :497),
     * outputs, and the V1/V2 certificates ({@code transTxCertV1V2}, {@code CertificateNotSupported}). An input or
     * output fails with an inline datum under V1 (Conway's {@code transTxOutV1}, :306-320; a reference script is not a
     * failure there, unlike Babbage's, Babbage/TxInfo.hs:119-121: the V1 {@code TxOut} simply omits it) and with a
     * Byron address under every language ({@code ByronTxOutInContext}).
     *
     * @param horizonSlot the first slot past the forecast horizon, or negative to skip that check
     * @return the first failure
     */
    public static Optional<String> translationError(Transaction tx, Map<Outpoint, UtxoEntry> resolved,
                                                    int protocolMajor, int language, long horizonSlot) {
        TransactionBody body = tx.getBody();
        if (language == 1 || language == 2) {
            if (body.getVotingProcedures() != null && body.getVotingProcedures().getVoting() != null
                    && !body.getVotingProcedures().getVoting().isEmpty()) {
                return Optional.of("VotingProceduresFieldNotSupported");
            }
            if (body.getProposalProcedures() != null && !body.getProposalProcedures().isEmpty()) {
                return Optional.of("ProposalProceduresFieldNotSupported");
            }
            if (body.getDonation() != null && body.getDonation().signum() != 0) {
                return Optional.of("TreasuryDonationFieldNotSupported");
            }
            if (body.getCurrentTreasuryValue() != null) {
                return Optional.of("CurrentTreasuryFieldNotSupported");
            }
        }
        // The validity interval (transValidityInterval), after the V1/V2 guard and before the inputs.
        if (horizonSlot >= 0) {
            Optional<String> error = horizonError(tx, horizonSlot);
            if (error.isPresent()) {
                return error;
            }
        }
        // Inputs, then reference inputs, (V3 from PV 11: disjointness), then outputs, as the TxInfo is built.
        for (TransactionInput input : nullToEmpty(body.getInputs())) {
            Optional<String> error = outputError(output(resolved, input), language, "input " + input);
            if (error.isPresent()) {
                return error;
            }
        }
        for (TransactionInput input : nullToEmpty(body.getReferenceInputs())) {
            Optional<String> error = outputError(output(resolved, input), language, "reference input " + input);
            if (error.isPresent()) {
                return error;
            }
        }
        if (language == 3 && protocolMajor >= 11) {
            Set<String> spending = new LinkedHashSet<>();
            nullToEmpty(body.getInputs()).forEach(in -> spending.add(key(in)));
            List<String> common = nullToEmpty(body.getReferenceInputs()).stream().map(ScriptCollection::key)
                    .filter(spending::contains).sorted().toList();
            if (!common.isEmpty()) {
                return Optional.of("ReferenceInputsNotDisjointFromInputs " + common);
            }
        }
        List<TransactionOutput> outputs = nullToEmpty(body.getOutputs());
        for (int i = 0; i < outputs.size(); i++) {
            Optional<String> error = outputError(outputs.get(i), language, "output " + i);
            if (error.isPresent()) {
                return error;
            }
        }
        if (language == 1 || language == 2) {
            for (Certificate cert : nullToEmpty(body.getCerts())) {
                if (!isV1V2Certificate(cert)) {
                    return Optional.of("CertificateNotSupported " + cert.getClass().getSimpleName());
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> outputError(TransactionOutput output, int language, String source) {
        if (output == null) {
            return Optional.empty(); // an unresolved input is phase one's BadInputsUTxO
        }
        if (language == 1 && output.getInlineDatum() != null) {
            return Optional.of("InlineDatumsNotSupported " + source);
        }
        if (isByron(output.getAddress())) {
            return Optional.of("ByronTxOutInContext " + source);
        }
        return Optional.empty();
    }

    /** Certificates {@code transTxCertV1V2} translates (Conway/TxInfo.hs:390-397, Alonzo transTxCertCommon). */
    private static boolean isV1V2Certificate(Certificate cert) {
        return cert instanceof StakeRegistration || cert instanceof StakeDeregistration
                || cert instanceof RegCert || cert instanceof UnregCert || cert instanceof StakeDelegation
                || cert instanceof PoolRegistration || cert instanceof PoolRetirement;
    }

    private static boolean isByron(String address) {
        if (address == null) {
            return false;
        }
        try {
            byte[] bytes = AddressUtil.addressToBytes(address);
            return bytes.length > 0 && (bytes[0] & 0xf0) == 0x80;
        } catch (Exception e) {
            return false;
        }
    }

    private static TransactionOutput output(Map<Outpoint, UtxoEntry> resolved, TransactionInput input) {
        UtxoEntry entry = resolved.get(Outpoints.normalize(new Outpoint(input.getTransactionId(), input.getIndex())));
        return entry != null ? entry.output() : null;
    }

    private static String key(TransactionInput input) {
        return input.getTransactionId() + "#" + input.getIndex();
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list != null ? list : List.of();
    }
}
