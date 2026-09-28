package org.yanoproject.runtime.validation;

import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import lombok.extern.slf4j.Slf4j;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.api.utxo.model.Utxo;
import org.yanoproject.ledger.rules.LedgerFailure;
import org.yanoproject.ledger.rules.LedgerRuleName;
import org.yanoproject.ledger.rules.TxValidationOutcome;
import org.yanoproject.ledger.rules.TxValidationRequest;
import org.yanoproject.ledger.rules.ValidationEnv;
import org.yanoproject.ledger.rules.ValidationResult;
import org.yanoproject.ledger.rules.shadow.LegacyVerdicts;
import org.yanoproject.ledger.rules.shadow.ShadowDumpBundle.RecordedOutcome;
import org.yanoproject.ledger.rules.view.LedgerStateUnavailableException;
import org.yanoproject.ledger.rules.view.LedgerView;
import org.yanoproject.ledger.rules.view.Lookup;
import org.yanoproject.ledger.rules.view.RecordingLedgerView;
import org.yanoproject.ledger.rules.view.model.Outpoints;
import org.yanoproject.ledger.rules.view.model.UtxoEntry;
import org.yanoproject.runtime.ledger.canonical.CanonicalLedgerView;
import org.yanoproject.runtime.ledger.canonical.CanonicalStateGate;
import org.yanoproject.runtime.ledger.canonical.SnapshotPurpose;
import org.yanoproject.runtime.ledger.canonical.TickedLedgerView;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Mempool admission through the {@code LedgerValidationEngine} API (ADR-056 step 1d), used when
 * {@code yano.validation.engine} is not {@code scalus} or shadow engines are configured.
 *
 * <ol>
 *   <li>The {@link AdmissionContext} of this admission (opened by the transaction subsystem before the mempool
 *       lane) supplies one {@code ADMISSION} snapshot ticked to the slot after the tip.</li>
 *   <li>A {@link MempoolUtxoOverlayView} over it answers UTxO reads through the mempool's admission resolver
 *       (chained mempool outputs) and everything else from the ticked view.</li>
 *   <li>The admission engine runs rule {@code MEMPOOL} with origin {@code LOCAL} (REST, n2c) or {@code PEER}
 *       (n2n tx-submission and diffusion), {@code previous = null}.</li>
 *   <li>Its {@link TxValidationOutcome} maps to the legacy {@link ValidationResult}, so the REST, n2n and n2c
 *       rejection paths are unchanged.</li>
 *   <li>Shadow engines get a frozen copy (the transaction's inputs pinned, a view holding its own snapshot
 *       reference) and run asynchronously; they never affect the verdict.</li>
 * </ol>
 * Without a context (a validation event raised outside {@code TxSubsystem.admitTransaction}), the admission
 * acquires its own snapshot here.
 */
@Slf4j
public final class EngineAdmission {

    private final ValidationEngines engines;
    private final Supplier<CanonicalStateGate> gate;

    public EngineAdmission(ValidationEngines engines, Supplier<CanonicalStateGate> gate) {
        this.engines = Objects.requireNonNull(engines, "engines");
        this.gate = Objects.requireNonNull(gate, "gate");
    }

    public ValidationEngines engines() {
        return engines;
    }

    /**
     * @param resolver the mempool admission resolver from the validation event, or {@code null}
     */
    public ValidationResult validate(byte[] txCbor, String txHash, String origin, Function<Outpoint, Utxo> resolver) {
        AdmissionContext context = AdmissionContext.current();
        AdmissionContext own = null;
        if (context == null) {
            own = AdmissionContext.open(gate.get(), SnapshotPurpose.ADMISSION);
            context = own;
        }
        try {
            return validate(txCbor, txHash, origin, resolver, context).toValidationResult();
        } finally {
            if (own != null) {
                own.close();
            }
        }
    }

    TxValidationOutcome validate(byte[] txCbor, String txHash, String origin, Function<Outpoint, Utxo> resolver,
                                 AdmissionContext context) {
        if (context.unavailableReason() != null) {
            return TxValidationOutcome.Invalid.of(LedgerFailure.ledgerStateUnavailable(
                    "no canonical snapshot for admission: " + context.unavailableReason()));
        }
        TickedLedgerView base = context.view();
        Function<Outpoint, Utxo> utxoResolver = resolver != null ? resolver : context.canonicalResolver();
        LedgerView view = new MempoolUtxoOverlayView(base, utxoResolver, context.canonicalResolver());
        ValidationEnv env;
        try {
            env = engines.envFactory().create(context.targetSlot(), view);
        } catch (LedgerStateUnavailableException e) {
            return TxValidationOutcome.Invalid.of(LedgerFailure.ledgerStateUnavailable(e.getMessage()));
        } catch (RuntimeException e) {
            return TxValidationOutcome.Invalid.of(LedgerFailure.ledgerStateUnavailable(
                    "validation environment: " + e.getMessage()));
        }
        ShadowValidationRunner shadows = engines.shadowRunner();
        RecordingLedgerView recording = shadows != null && shadows.dumpsEnabled() ? new RecordingLedgerView(view) : null;
        TxValidationRequest.Origin requestOrigin = origin(origin);
        TxValidationRequest request = new TxValidationRequest(txCbor, recording != null ? recording : view, env,
                TxValidationRequest.Rule.MEMPOOL, requestOrigin, null);
        TxValidationOutcome outcome;
        try {
            outcome = engines.admissionEngine().validate(request);
        } catch (RuntimeException e) {
            // The SPI forbids throwing; fail closed if an engine does anyway.
            log.warn("Validation engine {} threw on tx {}", engines.admissionEngine().name(), txHash, e);
            outcome = TxValidationOutcome.Invalid.of(new LedgerFailure(
                    LedgerRuleName.ENGINE, "EngineFailure", LedgerFailure.Phase.PHASE_1,
                    e.toString()));
        }
        if (shadows != null) {
            submitShadow(shadows, txCbor, txHash, view, base, env, requestOrigin,
                    RecordedOutcome.of(engines.admissionEngine().name(), outcome), recording, false);
        }
        return outcome;
    }

    private void submitShadow(ShadowValidationRunner shadows, byte[] txCbor, String txHash, LedgerView admissionView,
                              TickedLedgerView base, ValidationEnv env, TxValidationRequest.Origin origin,
                              RecordedOutcome admission, RecordingLedgerView recording, boolean capChecked) {
        CanonicalStateGate g = gate.get();
        if (!capChecked && g != null && !g.admitShadow()) {
            shadows.recordCapRefusal();
            return;
        }
        TickedLedgerView frozenBase;
        try (CanonicalLedgerView canonical = CanonicalLedgerView.over(base.snapshot())) {
            frozenBase = TickedLedgerView.of(canonical, env.currentSlot());
        } catch (IllegalStateException e) {
            // The snapshot was freed (the database is closing): not a cap refusal.
            shadows.recordSnapshotUnavailable();
            return;
        }
        LedgerView frozen = new PinnedUtxoLedgerView(frozenBase, pinInputs(txCbor, admissionView));
        TxValidationRequest request = new TxValidationRequest(txCbor, frozen, env, TxValidationRequest.Rule.MEMPOOL,
                origin, null);
        shadows.submit(new ShadowValidationRunner.ShadowJob(txHash != null ? txHash : "unknown", request, frozenBase,
                base.snapshot().ageMillis(), admission, recording != null ? recording.reads() : List.of()));
    }

    /**
     * Shadow validation next to the <em>legacy</em> admission path (ADR-056 §7, step 1d M4): with
     * {@code engine: scalus} admission stays on the legacy validator, unchanged; the shadows validate a
     * frozen copy against their own {@link SnapshotPurpose#SHADOW} snapshot (acquired by the transaction
     * subsystem before the mempool lane) and are compared with the legacy verdict.
     *
     * <p>The transaction's inputs are pinned to what the legacy validator saw (the mempool resolver over the
     * live UTxO store), taking the snapshot's exact entry when it has the output; everything else comes from
     * the shadow snapshot.</p>
     */
    public void shadowLegacy(byte[] txCbor, String txHash, String origin, Function<Outpoint, Utxo> resolver,
                             ValidationResult legacy) {
        ShadowValidationRunner shadows = engines.shadowRunner();
        if (shadows == null) {
            return;
        }
        AdmissionContext context = AdmissionContext.current();
        AdmissionContext own = null;
        if (context == null) {
            own = AdmissionContext.open(gate.get(), SnapshotPurpose.SHADOW);
            context = own;
        }
        try {
            if (context.unavailableReason() != null) {
                if (context.unavailableReason().contains("cap reached")) {
                    shadows.recordCapRefusal();
                } else {
                    shadows.recordSnapshotUnavailable();
                }
                return;
            }
            TickedLedgerView base = context.view();
            ValidationEnv env;
            try {
                env = engines.envFactory().create(context.targetSlot(), base);
            } catch (RuntimeException e) {
                shadows.recordSnapshotUnavailable();
                return;
            }
            LedgerView pinningView = new LegacyInputsView(base, resolver);
            submitShadow(shadows, txCbor, txHash, pinningView, base, env, origin(origin),
                    LegacyVerdicts.recorded(legacy), null, true);
        } finally {
            if (own != null) {
                own.close();
            }
        }
    }

    /** UTxO reads as the legacy validator saw them: the mempool resolver, with the snapshot's exact entries. */
    private static final class LegacyInputsView extends ForwardingLedgerView {
        private final Function<Outpoint, Utxo> resolver;

        LegacyInputsView(LedgerView base, Function<Outpoint, Utxo> resolver) {
            super(base);
            this.resolver = resolver;
        }

        @Override
        public Lookup<UtxoEntry> utxo(Outpoint outpoint) {
            if (resolver == null) {
                return base.utxo(outpoint);
            }
            Utxo seen;
            try {
                seen = resolver.apply(outpoint);
            } catch (RuntimeException e) {
                return Lookup.unavailable("mempool UTxO resolver: " + e.getMessage());
            }
            if (seen == null) {
                return Lookup.absent();
            }
            Lookup<UtxoEntry> snapshot = base.utxo(outpoint);
            if (snapshot instanceof Lookup.Present<UtxoEntry>) {
                return snapshot;
            }
            try {
                return Lookup.present(UtxoConversions.toEntry(Outpoints.normalize(outpoint), seen));
            } catch (RuntimeException e) {
                return Lookup.unavailable("cannot convert output " + outpoint + ": " + e.getMessage());
            }
        }
    }

    /**
     * Resolves the transaction's spending, reference and collateral inputs through the admission view while
     * the mempool resolver is still valid, so the shadow sees the same chained outputs.
     */
    static Map<Outpoint, Lookup<UtxoEntry>> pinInputs(byte[] txCbor, LedgerView admissionView) {
        Map<Outpoint, Lookup<UtxoEntry>> pinned = new LinkedHashMap<>();
        TransactionBody body;
        try {
            body = Transaction.deserialize(txCbor).getBody();
        } catch (Exception e) {
            return pinned; // the engines report the decoding failure themselves
        }
        for (List<TransactionInput> inputs : Arrays.asList(body.getInputs(), body.getReferenceInputs(),
                body.getCollateral())) {
            if (inputs == null) {
                continue;
            }
            for (TransactionInput input : inputs) {
                Outpoint outpoint = Outpoints.normalize(new Outpoint(input.getTransactionId(), input.getIndex()));
                pinned.computeIfAbsent(outpoint, admissionView::utxo);
            }
        }
        return pinned;
    }

    /**
     * Maps a submission path to the request origin: n2n tx-submission ({@code txsubmission}) and diffusion
     * ({@code tx-diffusion:<peer>}) are {@code PEER}; REST and n2c local submission are {@code LOCAL}.
     */
    public static TxValidationRequest.Origin origin(String origin) {
        String o = origin == null ? "" : origin.toLowerCase(Locale.ROOT);
        return o.startsWith("txsubmission") || o.startsWith("tx-diffusion") ? TxValidationRequest.Origin.PEER
                : TxValidationRequest.Origin.LOCAL;
    }
}
