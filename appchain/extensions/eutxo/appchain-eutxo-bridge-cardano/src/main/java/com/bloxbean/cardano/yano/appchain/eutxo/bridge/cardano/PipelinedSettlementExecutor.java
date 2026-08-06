package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecution;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoSettlementBatch;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/**
 * ADR-UTXO-009 SP-M6: the WIRED {@code l1.settlement} executor — journal
 * idempotency around the {@link QuickTxSettlePipeline}. A batch settles as
 * one or more L1 transactions (one per nullifier shard group); completion is
 * judged by the ONLY thing that matters: whether any claim of the range is
 * still pending in committed state. A probe that finds the recorded
 * transaction confirmed but claims still pending records FAILED so the next
 * retry re-resolves and settles the remainder — settled claims drop out of
 * the range, and the on-chain nullifier makes double-settlement impossible.
 */
final class PipelinedSettlementExecutor implements AppEffectExecutor {
    static final String EFFECT_TYPE = "l1.settlement";

    private final String id;
    private final QuickTxSettlePipeline pipeline;
    private final SettlementClaimsView claimsView;
    private final NodeSettlementBackend backend;
    private final BatchSettlementJournal journal;

    PipelinedSettlementExecutor(
            String id,
            QuickTxSettlePipeline pipeline,
            SettlementClaimsView claimsView,
            NodeSettlementBackend backend,
            BatchSettlementJournal journal) {
        this.id = Objects.requireNonNull(id, "id");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.claimsView = Objects.requireNonNull(claimsView, "claimsView");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.journal = Objects.requireNonNull(journal, "journal");
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public Set<String> effectTypes() {
        return Set.of(EFFECT_TYPE);
    }

    @Override
    public boolean supports(String effectType) {
        return EFFECT_TYPE.equals(effectType);
    }

    @Override
    public EffectExecution execute(EffectExecutionContext ctx, PendingEffect effect)
            throws Exception {
        EutxoSettlementBatch batch = EutxoSettlementBatch.decode(effect.payload());
        String journalKey = HexFormat.of().formatHex(effect.idHash());

        BatchSettlementJournal.Entry existing =
                journal.find(journalKey).orElse(null);
        if (existing != null
                && existing.stage() == BatchSettlementJournal.Stage.SUBMITTED) {
            return probe(batch, journalKey, existing.transactionId());
        }
        if (existing != null
                && existing.stage() == BatchSettlementJournal.Stage.CONFIRMED) {
            return new EffectExecution.Confirmed(
                    existing.transactionId().getBytes(StandardCharsets.UTF_8),
                    null);
        }

        String transactionId = pipeline.settle(batch);
        if (transactionId == null) {
            // Every claim of the range already settled.
            journal.save(new BatchSettlementJournal.Entry(
                    journalKey, BatchSettlementJournal.Stage.CONFIRMED, ""));
            return new EffectExecution.Confirmed(new byte[0], null);
        }
        journal.save(new BatchSettlementJournal.Entry(
                journalKey, BatchSettlementJournal.Stage.SUBMITTED, transactionId));
        return probe(batch, journalKey, transactionId);
    }

    private EffectExecution probe(EutxoSettlementBatch batch, String journalKey,
                                  String transactionId) {
        CardanoSettlementBackend.Status status = backend.status(transactionId);
        if (status != CardanoSettlementBackend.Status.CONFIRMED) {
            return new EffectExecution.Submitted(
                    transactionId.getBytes(StandardCharsets.UTF_8));
        }
        if (claimsView.pendingClaimsInRange(batch).isEmpty()) {
            journal.save(new BatchSettlementJournal.Entry(journalKey,
                    BatchSettlementJournal.Stage.CONFIRMED, transactionId));
            return new EffectExecution.Confirmed(
                    transactionId.getBytes(StandardCharsets.UTF_8), null);
        }
        // The recorded tx confirmed but part of the range is still pending
        // (multi-shard batch interrupted, or the confirmation observer has
        // not caught up) — rebuild on the next retry.
        journal.save(new BatchSettlementJournal.Entry(journalKey,
                BatchSettlementJournal.Stage.FAILED, transactionId));
        return new EffectExecution.Failed(
                "settlement range still has pending claims after "
                        + transactionId, true);
    }
}
