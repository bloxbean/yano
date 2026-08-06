package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecution;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoSettlementBatch;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalClaim;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A2 batch settlement executor (ADR-UTXO-009 §7.2/SP-M3): the owner node's
 * body for one {@code l1.settlement} effect. It resolves the batched claim
 * range, builds the unsigned Settle transaction
 * ({@link BatchSettlementTransactionBuilder}), runs the federation
 * threshold co-sign round, submits, and maps the L1 status to an
 * {@link EffectExecution} outcome — idempotent on {@code effect.idHash()}
 * through the {@link BatchSettlementJournal} so retries and restarts never
 * double-submit.
 *
 * <p>Node-coupled collaborators are injected: the host (app module) supplies
 * the real resolver (L2 claim + vault-inventory + root/shard selection), the
 * co-signer ({@code SettlementCosignService}), and the backend. This keeps
 * the orchestration unit-testable.
 */
public final class BatchSettlementExecutor implements AppEffectExecutor {
    public static final String EFFECT_TYPE = "l1.settlement";

    private final String id;
    private final BatchResolver resolver;
    private final ThresholdCosigner cosigner;
    private final CardanoSettlementBackend backend;
    private final BatchSettlementJournal journal;

    public BatchSettlementExecutor(
            String id,
            BatchResolver resolver,
            ThresholdCosigner cosigner,
            CardanoSettlementBackend backend,
            BatchSettlementJournal journal) {
        this.id = Objects.requireNonNull(id, "id");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.cosigner = Objects.requireNonNull(cosigner, "cosigner");
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

        // Idempotency: if this batch already reached the L1, poll its status
        // instead of rebuilding — the same batch settles at most once.
        BatchSettlementJournal.Entry existing = journal.find(journalKey).orElse(null);
        if (existing != null
                && (existing.stage() == BatchSettlementJournal.Stage.SUBMITTED
                || existing.stage() == BatchSettlementJournal.Stage.CONFIRMED)) {
            return probe(existing.transactionId(), existing);
        }

        BatchPlan resolved = resolver.resolve(batch);
        if (resolved.claims().isEmpty()) {
            // Every claim in the range was already settled/rolled off — done.
            return new EffectExecution.Confirmed(new byte[0], null);
        }
        BatchSettlementTransactionBuilder.Plan plan =
                BatchSettlementTransactionBuilder.build(
                        resolved.claims(), resolved.vaultInventory(),
                        resolved.vaultAddress(), resolved.bountyAddress(),
                        resolved.fee(), resolved.minimumContinuingLovelace(),
                        resolved.currentSlot(), resolved.ttlSlots(),
                        resolved.executionInputs());
        journal.save(new BatchSettlementJournal.Entry(
                journalKey, BatchSettlementJournal.Stage.BUILT, ""));

        byte[] signedTx = cosigner.cosign(
                plan.unsignedBodyCbor(), plan.orderedClaimIds());
        String txId = TransactionUtil.getTxHash(signedTx);
        journal.save(new BatchSettlementJournal.Entry(
                journalKey, BatchSettlementJournal.Stage.SUBMITTED, txId));

        CardanoSettlementBackend.Submission submission = backend.submit(signedTx);
        return switch (submission.status()) {
            case CONFIRMED -> {
                journal.save(new BatchSettlementJournal.Entry(
                        journalKey, BatchSettlementJournal.Stage.CONFIRMED,
                        submission.transactionId()));
                yield new EffectExecution.Confirmed(
                        submission.transactionId().getBytes(StandardCharsets.UTF_8),
                        null);
            }
            case PENDING, UNKNOWN -> new EffectExecution.Submitted(
                    submission.transactionId().getBytes(StandardCharsets.UTF_8));
            case REJECTED -> new EffectExecution.Failed(
                    "L1 settlement rejected: " + submission.detail(), true);
        };
    }

    private EffectExecution probe(String transactionId,
                                  BatchSettlementJournal.Entry entry) throws Exception {
        CardanoSettlementBackend.Status status = backend.status(transactionId);
        return switch (status) {
            case CONFIRMED -> {
                if (entry.stage() != BatchSettlementJournal.Stage.CONFIRMED) {
                    journal.save(new BatchSettlementJournal.Entry(
                            entry.effectKey(), BatchSettlementJournal.Stage.CONFIRMED,
                            transactionId));
                }
                yield new EffectExecution.Confirmed(
                        transactionId.getBytes(StandardCharsets.UTF_8), null);
            }
            case PENDING, UNKNOWN -> new EffectExecution.Submitted(
                    transactionId.getBytes(StandardCharsets.UTF_8));
            case REJECTED -> new EffectExecution.Failed(
                    "settled transaction was rejected on the L1", true);
        };
    }

    /** Host-supplied resolution of a batch range to a concrete build plan. */
    public interface BatchResolver {
        BatchPlan resolve(EutxoSettlementBatch batch) throws Exception;
    }

    /** Host-supplied federation threshold co-sign of the unsigned batch body. */
    public interface ThresholdCosigner {
        byte[] cosign(byte[] unsignedBodyCbor, List<String> orderedClaimIds)
                throws Exception;
    }

    /** Everything the transaction builder needs, resolved from node state. */
    public record BatchPlan(
            List<EutxoWithdrawalClaim> claims,
            List<BatchSettlementTransactionBuilder.VaultInput> vaultInventory,
            String vaultAddress,
            String bountyAddress,
            java.math.BigInteger fee,
            java.math.BigInteger minimumContinuingLovelace,
            long currentSlot,
            long ttlSlots,
            BatchSettlementTransactionBuilder.ExecutionInputs executionInputs
    ) {
        public BatchPlan {
            claims = List.copyOf(Objects.requireNonNull(claims, "claims"));
            vaultInventory = List.copyOf(
                    Objects.requireNonNull(vaultInventory, "vaultInventory"));
        }
    }
}
