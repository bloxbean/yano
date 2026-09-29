package org.yanoproject.scalusbridge;

import com.bloxbean.cardano.client.address.util.AddressUtil;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.common.model.SlotConfig;
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
import org.yanoproject.ledger.rules.phase2.ForecastHorizon;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseEvaluator;
import org.yanoproject.ledger.rules.phase2.ScriptPhaseResult;
import org.yanoproject.ledger.rules.view.model.Outpoints;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The Scalus {@link ScriptPhaseEvaluator} (ADR-056 §5, step 1d): runs every needed Plutus script on the
 * Scalus CEK machine with its redeemer's declared ExUnits as budget.
 *
 * <p>Before any script runs it reports, as phase-1 {@link ScriptPhaseResult.Rejected} failures, the checks
 * Haskell makes while preparing script contexts, which Amaru's {@code phase_one} mode does not (ADR-057
 * Phase B deviation 9). Checked against cardano-ledger {@code f649f975}. The Java engine's {@code UTXOW} owns the
 * two malformed-script checks (ADR-056 Phase 3b): it asks {@link #isWellFormed} per script, and {@link #collect}
 * reports only the {@code CollectErrors}; {@link #evaluate} still refuses to run a malformed script.</p>
 * <ol>
 *   <li>{@code UTXOW.MalformedScriptWitnesses}: Plutus witness scripts that are not well-formed at the
 *       protocol version (Babbage/Rules/Utxow.hs:264-273, {@code isValidScript}).</li>
 *   <li>{@code UTXOW.MalformedReferenceScripts}: reference scripts of the transaction's <em>own outputs</em>
 *       and collateral return (the same function). Haskell does not re-check reference scripts of resolved
 *       inputs here: they were checked when their output was created. A malformed one that is needed fails
 *       when it is decoded to run ({@code decodePlutusRunnable}), which is a phase-2 script failure, and is
 *       reported as {@link ScriptPhaseResult.Failed}.</li>
 *   <li>{@code UTXOS.CollectErrors} (Alonzo/Plutus/Evaluate.hs {@code collectPlutusScriptsWithContext},
 *       all errors accumulated, one failure listing them):
 *       <ul>
 *         <li>{@code NoCostModel}: a needed script's language has no cost model;</li>
 *         <li>{@code BadTranslation}: the {@code TxInfo} of a needed script's language cannot be built.
 *             Checked: Plutus V1/V2 with Conway-only fields ({@code guardConwayFeaturesForPlutusV1V2},
 *             Conway/TxInfo.hs:352-381), with Conway-only certificates ({@code transTxCertV1V2},
 *             {@code CertificateNotSupported}), or needed for a vote or proposal
 *             ({@code PlutusPurposeNotSupported}); Plutus V1 with an inline datum or reference script in a
 *             spending or reference input or an output ({@code transTxOutV1}, Babbage/TxInfo.hs:119-126);
 *             a Byron address in a spending or reference input or an output, for every language
 *             ({@code ByronTxOutInContext}); and from protocol version 11, Plutus V3 with spending inputs
 *             that are also reference inputs ({@code ReferenceInputsNotDisjointFromInputs},
 *             Conway/TxInfo.hs:497); and, with a {@link ForecastHorizon}, a validity bound at or past the
 *             forecast horizon ({@code TimeTranslationPastHorizon}, {@code Alonzo/Plutus/TxInfo.hs:252-274}).</li>
 *       </ul></li>
 * </ol>
 * <p>{@code NoRedeemer} is reported for a needed Plutus script without a redeemer (UTXOW's
 * {@code MissingRedeemers} reports the same fault separately); {@code NoWitness} cannot occur, as only provided
 * Plutus scripts are collected ({@code MissingScriptWitnessesUTXOW} covers a missing one).</p>
 *
 * <p>Then every script runs; the first failure (Scalus stops there) makes the result
 * {@link ScriptPhaseResult.Failed}. Comparing with {@code is_valid} is the engine's job. Anything else Scalus
 * throws (a missing datum or script that phase one should have caught, BLS builtins unavailable) propagates,
 * and the engine fails closed. Thread-safe and stateless.</p>
 */
public final class ScalusScriptPhaseEvaluator implements ScriptPhaseEvaluator {

    /** Haskell {@code ConwayUtxosPredFailure} constructor for collection failures. */
    public static final String COLLECT_ERRORS = "CollectErrors";

    private final ForecastHorizon horizon;

    /** An evaluator without a forecast horizon: {@code TimeTranslationPastHorizon} is not checked. */
    public ScalusScriptPhaseEvaluator() {
        this(null);
    }

    /** @param horizon the network's forecast horizon, or {@code null} to skip that check */
    public ScalusScriptPhaseEvaluator(ForecastHorizon horizon) {
        this.horizon = horizon;
    }

    @Override
    public ScriptPhaseResult evaluate(byte[] txCbor, Transaction tx, Map<Outpoint, UtxoEntry> resolvedInputs,
                                      ProtocolParams params, SlotConfig slotConfig) {
        return evaluate(txCbor, tx, resolvedInputs, params, slotConfig, -1);
    }

    /**
     * @param validationSlot the slot after the ledger tip, for the forecast-horizon check; negative skips it
     */
    @Override
    public ScriptPhaseResult evaluate(byte[] txCbor, Transaction tx, Map<Outpoint, UtxoEntry> resolvedInputs,
                                      ProtocolParams params, SlotConfig slotConfig, long validationSlot) {
        Preparation preparation = prepare(txCbor, tx, resolvedInputs, params, slotConfig, validationSlot, true);
        if (!preparation.failures().isEmpty()) {
            return new ScriptPhaseResult.Rejected(preparation.failures());
        }
        if (preparation.needed().isEmpty()) {
            return new ScriptPhaseResult.Passed(List.of());
        }
        ScalusPhaseTwo.Evaluation evaluation = ScalusPhaseTwo.evaluate(txCbor, resolvedInputs.values(), params,
                slotConfig);
        return evaluation.passed()
                ? new ScriptPhaseResult.Passed(evaluation.scripts())
                : new ScriptPhaseResult.Failed(evaluation.scripts());
    }

    /**
     * The {@code CollectErrors} {@link #evaluate} finds before running any script, without running one. The
     * malformed-script checks are left out: the Java engine's {@code UTXOW} makes them itself
     * ({@link #isWellFormed}, ADR-056 Phase 3b).
     */
    @Override
    public List<LedgerFailure> collect(byte[] txCbor, Transaction tx, Map<Outpoint, UtxoEntry> resolvedInputs,
                                       ProtocolParams params, SlotConfig slotConfig, long validationSlot) {
        return prepare(txCbor, tx, resolvedInputs, params, slotConfig, validationSlot, false).failures();
    }

    /** Haskell {@code isValidPlutusScript}, with Scalus's Plutus decoder ({@code PlutusScript.isWellFormed}). */
    @Override
    public boolean isWellFormed(int language, byte[] script, int protocolMajor) {
        return ScalusPhaseTwo.isWellFormed(language, script, protocolMajor);
    }

    /**
     * True: at protocol version 9 a transaction with a {@code reg_cert} or {@code unreg_cert} runs over the
     * bootstrap-phase PlutusV3 context ({@code BootstrapPhaseContexts}, Conway/TxInfo.hs:572-581), not Scalus's.
     */
    @Override
    public boolean translatesBootstrapPhaseCertificateDeposits() {
        return true;
    }

    /** What the preparation found: failures (malformed scripts, or CollectErrors), and the scripts to run. */
    private record Preparation(List<LedgerFailure> failures, List<NeededPlutusScript> needed) {
    }

    private Preparation prepare(byte[] txCbor, Transaction tx, Map<Outpoint, UtxoEntry> resolvedInputs,
                                ProtocolParams params, SlotConfig slotConfig, long validationSlot,
                                boolean checkWellFormed) {
        Objects.requireNonNull(txCbor, "txCbor");
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(resolvedInputs, "resolvedInputs");
        Objects.requireNonNull(params, "params");
        Objects.requireNonNull(slotConfig, "slotConfig");
        int major = requireVersion(params.getProtocolMajorVer(), "protocol major version");
        int minor = requireVersion(params.getProtocolMinorVer(), "protocol minor version");

        List<LedgerFailure> malformed = new ArrayList<>();
        List<String> witnesses = checkWellFormed ? ScalusPhaseTwo.malformedWitnessScripts(txCbor, major, minor)
                : List.of();
        if (!witnesses.isEmpty()) {
            malformed.add(new LedgerFailure(LedgerRuleName.UTXOW, "MalformedScriptWitnesses",
                    LedgerFailure.Phase.PHASE_1, String.join(", ", witnesses)));
        }
        List<String> references = checkWellFormed
                ? ScalusPhaseTwo.malformedOutputReferenceScripts(txCbor, major, minor) : List.of();
        if (!references.isEmpty()) {
            malformed.add(new LedgerFailure(LedgerRuleName.UTXOW, "MalformedReferenceScripts",
                    LedgerFailure.Phase.PHASE_1, String.join(", ", references)));
        }
        if (!malformed.isEmpty()) {
            return new Preparation(malformed, List.of());
        }

        List<NeededPlutusScript> needed = ScalusPhaseTwo.neededPlutusScripts(txCbor, resolvedInputs.values(),
                major, minor);
        List<String> collectErrors = collectErrors(tx, resolvedInputs, params, major, needed);
        if (horizon != null && validationSlot >= 0 && needed.stream().anyMatch(NeededPlutusScript::hasRedeemer)) {
            horizonError(tx, horizon.exclusiveUpperSlot(validationSlot))
                    .ifPresent(e -> collectErrors.add("BadTranslation " + e));
        }
        if (!collectErrors.isEmpty()) {
            return new Preparation(List.of(new LedgerFailure(LedgerRuleName.UTXOS, COLLECT_ERRORS,
                    LedgerFailure.Phase.PHASE_1, String.join("; ", collectErrors))), needed);
        }
        return new Preparation(List.of(), needed);
    }

    // ------------------------------------------------------------------ CollectErrors

    static List<String> collectErrors(Transaction tx, Map<Outpoint, UtxoEntry> resolved, ProtocolParams params,
                                      int protocolMajor, List<NeededPlutusScript> needed) {
        List<String> errors = new ArrayList<>();
        Set<Integer> languages = new TreeSet<>();
        for (NeededPlutusScript script : needed) {
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
            translationError(tx, resolved, protocolMajor, language)
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
    static Optional<String> horizonError(Transaction tx, long exclusiveUpperSlot) {
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

    /** @return the first {@code TxInfo} translation failure for {@code language}, in Haskell's order */
    static Optional<String> translationError(Transaction tx, Map<Outpoint, UtxoEntry> resolved,
                                                         int protocolMajor, int language) {
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
        // Inputs, then reference inputs, then outputs, as the TxInfo is built.
        for (TransactionInput input : nullToEmpty(body.getInputs())) {
            Optional<String> error = outputError(output(resolved, input), language, "input " + input);
            if (error.isPresent()) {
                return error;
            }
        }
        for (TransactionInput input : nullToEmpty(body.getReferenceInputs())) {
            Optional<String> error = outputError(output(resolved, input), language,
                    "reference input " + input);
            if (error.isPresent()) {
                return error;
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
        if (language == 3 && protocolMajor >= 11) {
            Set<String> spending = new LinkedHashSet<>();
            nullToEmpty(body.getInputs()).forEach(in -> spending.add(key(in)));
            List<String> common = nullToEmpty(body.getReferenceInputs()).stream().map(ScalusScriptPhaseEvaluator::key)
                    .filter(spending::contains).sorted().toList();
            if (!common.isEmpty()) {
                return Optional.of("ReferenceInputsNotDisjointFromInputs " + common);
            }
        }
        return Optional.empty();
    }

    private static Optional<String> outputError(TransactionOutput output, int language, String source) {
        if (output == null) {
            return Optional.empty(); // an unresolved input is phase one's BadInputsUTxO
        }
        if (language == 1) {
            if (output.getScriptRef() != null) {
                return Optional.of("ReferenceScriptsNotSupported " + source);
            }
            if (output.getInlineDatum() != null) {
                return Optional.of("InlineDatumsNotSupported " + source);
            }
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

    static boolean hasCostModel(ProtocolParams params, String language) {
        Map<String, List<Long>> raw = params.getCostModelsRaw();
        if (raw != null && raw.get(language) != null && !raw.get(language).isEmpty()) {
            return true;
        }
        return params.getCostModels() != null && params.getCostModels().get(language) != null
                && !params.getCostModels().get(language).isEmpty();
    }

    private static int requireVersion(Integer value, String what) {
        if (value == null) {
            throw new IllegalArgumentException("protocol parameters carry no " + what);
        }
        return value;
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list != null ? list : List.of();
    }
}
