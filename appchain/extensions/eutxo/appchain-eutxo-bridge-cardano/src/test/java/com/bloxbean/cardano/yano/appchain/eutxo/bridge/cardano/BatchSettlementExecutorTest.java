package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecution;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectRecord;
import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;
import com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect;
import com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoSettlementBatch;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalClaim;
import com.bloxbean.cardano.yano.appchain.eutxo.testkit.EutxoTestWallet;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-UTXO-009 SP-M3: conformance for the A2 batch settlement executor —
 * the orchestration (resolve → build → co-sign → submit), the L1-status
 * mapping, and idempotency on {@code effect.idHash()}.
 */
class BatchSettlementExecutorTest {
    private static final String CHAIN = "payments";
    private static final String VAULT =
            EutxoTestWallet.fromSeed(fill(32, 0x54)).address();
    private static final String BOUNTY =
            EutxoTestWallet.fromSeed(fill(32, 0x99)).address();

    @Test
    void confirmedSubmissionReturnsConfirmedAndJournalsIt() throws Exception {
        InMemoryJournal journal = new InMemoryJournal();
        RecordingBackend backend = new RecordingBackend(
                CardanoSettlementBackend.Status.CONFIRMED, "ok");
        CountingCosigner cosigner = new CountingCosigner();
        BatchSettlementExecutor executor = new BatchSettlementExecutor(
                "eutxo-settlement", fixedResolver(), cosigner, backend, journal);

        EffectExecution outcome = executor.execute(context(), effect(0, 3));

        assertThat(outcome).isInstanceOf(EffectExecution.Confirmed.class);
        EffectExecution.Confirmed confirmed = (EffectExecution.Confirmed) outcome;
        assertThat(new String(confirmed.externalRef(), StandardCharsets.UTF_8))
                .isEqualTo(backend.lastTxId);
        assertThat(cosigner.calls.get()).isEqualTo(1);
        assertThat(backend.submitCalls.get()).isEqualTo(1);
        assertThat(journal.find(keyOf(effect(0, 3))))
                .get()
                .extracting(BatchSettlementJournal.Entry::stage)
                .isEqualTo(BatchSettlementJournal.Stage.CONFIRMED);
    }

    @Test
    void pendingSubmissionReturnsSubmitted() throws Exception {
        InMemoryJournal journal = new InMemoryJournal();
        RecordingBackend backend = new RecordingBackend(
                CardanoSettlementBackend.Status.PENDING, "in-flight");
        BatchSettlementExecutor executor = new BatchSettlementExecutor(
                "eutxo-settlement", fixedResolver(), new CountingCosigner(),
                backend, journal);

        EffectExecution outcome = executor.execute(context(), effect(0, 3));

        assertThat(outcome).isInstanceOf(EffectExecution.Submitted.class);
        assertThat(journal.find(keyOf(effect(0, 3))))
                .get()
                .extracting(BatchSettlementJournal.Entry::stage)
                .isEqualTo(BatchSettlementJournal.Stage.SUBMITTED);
    }

    @Test
    void rejectedSubmissionReturnsRetryableFailure() throws Exception {
        RecordingBackend backend = new RecordingBackend(
                CardanoSettlementBackend.Status.REJECTED, "utxo already spent");
        BatchSettlementExecutor executor = new BatchSettlementExecutor(
                "eutxo-settlement", fixedResolver(), new CountingCosigner(),
                backend, new InMemoryJournal());

        EffectExecution outcome = executor.execute(context(), effect(0, 3));

        assertThat(outcome).isInstanceOf(EffectExecution.Failed.class);
        EffectExecution.Failed failed = (EffectExecution.Failed) outcome;
        assertThat(failed.retryable()).isTrue();
        assertThat(failed.reason()).contains("utxo already spent");
    }

    @Test
    void rerunAfterSubmitProbesInsteadOfResubmitting() throws Exception {
        InMemoryJournal journal = new InMemoryJournal();
        String key = keyOf(effect(0, 3));
        // A prior attempt already reached the L1: only PENDING was recorded.
        journal.save(new BatchSettlementJournal.Entry(
                key, BatchSettlementJournal.Stage.SUBMITTED, "deadbeef"));
        RecordingBackend backend = new RecordingBackend(
                CardanoSettlementBackend.Status.PENDING, "in-flight");
        backend.statusAnswer = CardanoSettlementBackend.Status.CONFIRMED;
        CountingCosigner cosigner = new CountingCosigner();
        BatchSettlementExecutor executor = new BatchSettlementExecutor(
                "eutxo-settlement", fixedResolver(), cosigner, backend, journal);

        EffectExecution outcome = executor.execute(context(), effect(0, 3));

        // No rebuild, no resubmit — it only polled the recorded transaction.
        assertThat(cosigner.calls.get()).isZero();
        assertThat(backend.submitCalls.get()).isZero();
        assertThat(backend.statusCalls.get()).isEqualTo(1);
        assertThat(outcome).isInstanceOf(EffectExecution.Confirmed.class);
        assertThat(new String(
                ((EffectExecution.Confirmed) outcome).externalRef(),
                StandardCharsets.UTF_8)).isEqualTo("deadbeef");
        assertThat(journal.find(key))
                .get()
                .extracting(BatchSettlementJournal.Entry::stage)
                .isEqualTo(BatchSettlementJournal.Stage.CONFIRMED);
    }

    @Test
    void emptyResolvedRangeShortCircuitsToConfirmed() throws Exception {
        RecordingBackend backend = new RecordingBackend(
                CardanoSettlementBackend.Status.CONFIRMED, "ok");
        CountingCosigner cosigner = new CountingCosigner();
        BatchSettlementExecutor.BatchResolver empty = batch -> new BatchSettlementExecutor.BatchPlan(
                List.of(), List.of(), VAULT, BOUNTY, BigInteger.valueOf(300_000L),
                BigInteger.valueOf(2_000_000L), 1_000L, 7_200L, execution());
        BatchSettlementExecutor executor = new BatchSettlementExecutor(
                "eutxo-settlement", empty, cosigner, backend, new InMemoryJournal());

        EffectExecution outcome = executor.execute(context(), effect(0, 0 + 0 + 0 + 3));

        assertThat(outcome).isInstanceOf(EffectExecution.Confirmed.class);
        assertThat(cosigner.calls.get()).isZero();
        assertThat(backend.submitCalls.get()).isZero();
    }

    // --- fixtures ---------------------------------------------------------

    private static BatchSettlementExecutor.BatchResolver fixedResolver() {
        return batch -> new BatchSettlementExecutor.BatchPlan(
                List.of(claim(0, 8_000_000L, 2_000_000L),
                        claim(1, 5_000_000L, 2_000_000L),
                        claim(2, 3_000_000L, 2_000_000L)),
                List.of(new BatchSettlementTransactionBuilder.VaultInput(
                                outpoint(0x11), BigInteger.valueOf(20_000_000L)),
                        new BatchSettlementTransactionBuilder.VaultInput(
                                outpoint(0x12), BigInteger.valueOf(20_000_000L))),
                VAULT, BOUNTY, BigInteger.valueOf(300_000L),
                BigInteger.valueOf(2_000_000L), 1_000L, 7_200L, execution());
    }

    private static PendingEffect effect(long fromSeq, long toSeq) {
        byte[] payload = new EutxoSettlementBatch(
                1, CHAIN, 7L, 0L, fromSeq, toSeq).encode();
        EffectRecord record = new EffectRecord(
                1, CHAIN, 17, 3, BatchSettlementExecutor.EFFECT_TYPE, payload,
                "settlement", FinalityGate.APP_FINAL, ResultPolicy.CHAIN, 100, null);
        return PendingEffect.of(record);
    }

    private static String keyOf(PendingEffect effect) {
        return java.util.HexFormat.of().formatHex(effect.idHash());
    }

    private static EffectExecutionContext context() {
        return new EffectExecutionContext() {
            @Override public String chainId() { return CHAIN; }
            @Override public long tipHeight() { return 20; }
            @Override public long anchoredHeight() { return 19; }
            @Override public int attempt() { return 1; }
            @Override public Map<String, String> settings() { return Map.of(); }
        };
    }

    private static BatchSettlementTransactionBuilder.ExecutionInputs execution() {
        return new BatchSettlementTransactionBuilder.ExecutionInputs(
                NetworkId.TESTNET, outpoint(0x63), outpoint(0x62),
                List.of(outpoint(0x70)), List.of(fill(28, 0xaa)));
    }

    private static EutxoWithdrawalClaim claim(int index, long payout, long bounty) {
        return new EutxoWithdrawalClaim(
                EutxoWithdrawalClaim.ABI_VERSION_V2, CHAIN, 7, outpoint(0x40 + index),
                EutxoTestWallet.fromSeed(fill(32, 0x80 + index)).address(),
                BigInteger.valueOf(payout), fill(32, 0x30 + index),
                index, 42, BigInteger.valueOf(bounty));
    }

    private static EutxoOutpoint outpoint(int value) {
        return new EutxoOutpoint("%02x".formatted(value & 0xFF).repeat(32), 0);
    }

    private static byte[] fill(int size, int value) {
        byte[] bytes = new byte[size];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    // --- test doubles -----------------------------------------------------

    private static final class InMemoryJournal implements BatchSettlementJournal {
        private final Map<String, Entry> entries = new ConcurrentHashMap<>();

        @Override public Optional<Entry> find(String effectKey) {
            return Optional.ofNullable(entries.get(effectKey));
        }

        @Override public void save(Entry entry) {
            entries.put(entry.effectKey(), entry);
        }
    }

    /** Wraps the unsigned body in a real (unsigned) transaction so the tx id is honest. */
    private static final class CountingCosigner
            implements BatchSettlementExecutor.ThresholdCosigner {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public byte[] cosign(byte[] unsignedBodyCbor, List<String> orderedClaimIds)
                throws Exception {
            calls.incrementAndGet();
            TransactionBody body = TransactionBody.deserialize(
                    (co.nstant.in.cbor.model.Map) CborSerializationUtil.deserialize(
                            unsignedBodyCbor));
            Transaction tx = Transaction.builder()
                    .body(body)
                    .witnessSet(new TransactionWitnessSet())
                    .build();
            return tx.serialize();
        }
    }

    private static final class RecordingBackend implements CardanoSettlementBackend {
        final AtomicInteger submitCalls = new AtomicInteger();
        final AtomicInteger statusCalls = new AtomicInteger();
        private final Status submitStatus;
        private final String detail;
        Status statusAnswer;
        String lastTxId;

        RecordingBackend(Status submitStatus, String detail) {
            this.submitStatus = submitStatus;
            this.detail = detail;
        }

        @Override
        public Submission submit(byte[] signedTransactionCbor) {
            submitCalls.incrementAndGet();
            lastTxId = TransactionUtil.getTxHash(signedTransactionCbor);
            return new Submission(lastTxId, submitStatus, detail);
        }

        @Override
        public Status status(String transactionId) {
            statusCalls.incrementAndGet();
            return statusAnswer != null ? statusAnswer : submitStatus;
        }
    }
}
