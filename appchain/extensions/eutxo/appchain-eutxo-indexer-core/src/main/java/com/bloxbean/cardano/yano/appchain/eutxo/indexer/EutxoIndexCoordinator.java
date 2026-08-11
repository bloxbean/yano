package com.bloxbean.cardano.yano.appchain.eutxo.indexer;

import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.plugin.domain.FinalizedChainView;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoDepositRecord;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoQueryCodec;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoTransactionSummary;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalRecord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Non-blocking finalized-block listener with durable checkpoint catch-up.
 * Notifications are hints; retained blocks and committed records are truth.
 */
public final class EutxoIndexCoordinator implements AutoCloseable {
    private static final Logger log =
            LoggerFactory.getLogger(EutxoIndexCoordinator.class);

    private final FinalizedChainView gateway;
    private final EutxoIndexStore store;
    private final EutxoProjector projector;
    private final EutxoIndexMetrics metrics;
    private final ThreadPoolExecutor worker;
    private final AtomicLong requestedHeight = new AtomicLong();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private volatile AutoCloseable subscription;
    private volatile Throwable failure;
    private volatile String bridgeDiagnostic = "";
    private volatile boolean closed;

    public EutxoIndexCoordinator(
            FinalizedChainView gateway,
            EutxoIndexStore store
    ) {
        this(gateway, store, new EutxoIndexMetrics());
    }

    public EutxoIndexCoordinator(
            FinalizedChainView gateway,
            EutxoIndexStore store,
            EutxoIndexMetrics metrics
    ) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.store = Objects.requireNonNull(store, "store");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        projector = new EutxoProjector(store);
        worker = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "eutxo-index-" + gateway.chainId());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    public synchronized void start() {
        if (closed || subscription != null) {
            throw new IllegalStateException(
                    "EUTxO index coordinator cannot be started");
        }
        subscription = gateway.subscribe((block, hash) -> {
            requestedHeight.accumulateAndGet(block.height(), Math::max);
            schedule();
        });
        requestedHeight.set(gateway.tipHeight());
        schedule();
    }

    public IndexHealth health() {
        IndexCheckpoint checkpoint = store.checkpoint();
        long finalized = Math.max(gateway.tipHeight(), requestedHeight.get());
        if (checkpoint.source().appHeight() > finalized) {
            return new IndexHealth(
                    IndexHealth.Status.FAILED,
                    checkpoint,
                    finalized,
                    0,
                    "index checkpoint is ahead of authoritative app-chain tip");
        }
        long lag = Math.max(0, finalized - checkpoint.source().appHeight());
        Throwable currentFailure = failure;
        if (currentFailure != null) {
            return new IndexHealth(
                    IndexHealth.Status.FAILED,
                    checkpoint,
                    finalized,
                    lag,
                    currentFailure.getClass().getSimpleName());
        }
        IndexHealth.Status status = checkpoint.coverage()
                == IndexCoverage.REBUILDING
                ? IndexHealth.Status.REBUILDING
                : lag == 0
                ? IndexHealth.Status.READY
                : IndexHealth.Status.CATCHING_UP;
        return new IndexHealth(
                status, checkpoint, finalized, lag,
                bridgeDiagnostic);
    }

    public void catchUpNow() {
        drain();
    }

    public EutxoIndexMetrics metrics() {
        return metrics;
    }

    public int queueDepth() {
        return worker.getQueue().size();
    }

    private void schedule() {
        if (closed || !scheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            worker.execute(() -> {
                try {
                    drain();
                } finally {
                    scheduled.set(false);
                    if (!closed && store.checkpoint().source().appHeight()
                            < requestedHeight.get()) {
                        schedule();
                    }
                }
            });
        } catch (RuntimeException rejected) {
            scheduled.set(false);
            // The durable checkpoint remains authoritative; a later
            // notification/status catch-up retries from it.
        }
    }

    private void drain() {
        if (closed) {
            return;
        }
        try {
            long target = Math.max(requestedHeight.get(), gateway.tipHeight());
            long checkpoint = store.checkpoint().source().appHeight();
            if (checkpoint > target) {
                throw new IllegalStateException(
                        "index checkpoint " + checkpoint
                                + " is ahead of authoritative app-chain tip "
                                + target);
            }
            CanonicalRecords records = canonicalRecords();
            for (long height = Math.addExact(
                    store.checkpoint().source().appHeight(), 1);
                 height <= target;
                 height++) {
                long currentHeight = height;
                AppBlock block = gateway.block(currentHeight).orElseThrow(() ->
                        new IllegalStateException(
                                "finalized app block " + currentHeight
                                        + " is not retained"));
                List<EutxoIndexEvent> events =
                        records.atHeight(height, block);
                long started = System.nanoTime();
                try {
                    projector.apply(
                            source(block),
                            events,
                            IndexCoverage.FULL);
                } finally {
                    metrics.recordApply(System.nanoTime() - started);
                }
            }
            failure = null;
        } catch (Throwable caught) {
            metrics.recordFailure();
            // The index is optional, so a projection failure must not stop the
            // node — but swallowing it silently leaves the console reporting
            // "unavailable" with nothing anywhere to explain why.
            if (failure == null
                    || !caught.getClass().equals(failure.getClass())) {
                log.warn("EUTxO lifecycle index projection failed for chain "
                        + "'{}' — the index is now serving INDEX_FAILED",
                        gateway.chainId(), caught);
            }
            failure = caught;
        }
    }

    private CanonicalRecords canonicalRecords() {
        bridgeDiagnostic = EutxoQueryCodec.decodeBridgeHalt(query(
                EutxoQueryCodec.BRIDGE_HALT_PATH, new byte[0]));
        List<SequencedDeposit> deposits = deposits();
        List<EutxoWithdrawalRecord> withdrawals = withdrawals();
        return new CanonicalRecords(deposits, withdrawals);
    }

    private List<SequencedDeposit> deposits() {
        long count = EutxoQueryCodec.decodeCount(query(
                EutxoQueryCodec.DEPOSIT_COUNT_PATH, new byte[0]));
        List<SequencedDeposit> records = new ArrayList<>();
        long before = 0;
        long sequence = count;
        while (sequence > 0) {
            List<EutxoDepositRecord> page =
                    EutxoQueryCodec.decodeDepositRecords(query(
                            EutxoQueryCodec.DEPOSITS_PATH,
                            EutxoQueryCodec.lifecyclePageRequest(before, 50)));
            if (page.isEmpty()) {
                throw new IllegalStateException(
                        "deposit replay ended before its committed count");
            }
            for (EutxoDepositRecord record : page) {
                records.add(new SequencedDeposit(sequence, record));
                before = sequence;
                sequence--;
            }
        }
        return records;
    }

    private List<EutxoWithdrawalRecord> withdrawals() {
        long count = EutxoQueryCodec.decodeCount(query(
                EutxoQueryCodec.WITHDRAWAL_COUNT_PATH, new byte[0]));
        List<EutxoWithdrawalRecord> records = new ArrayList<>();
        long before = 0;
        while (records.size() < count) {
            List<EutxoWithdrawalRecord> page =
                    EutxoQueryCodec.decodeWithdrawalRecords(query(
                            EutxoQueryCodec.WITHDRAWALS_PATH,
                            EutxoQueryCodec.lifecyclePageRequest(before, 50)));
            if (page.isEmpty()) {
                throw new IllegalStateException(
                        "withdrawal replay ended before its committed count");
            }
            records.addAll(page);
            before = page.getLast().claim().settlementSequence() + 1;
        }
        return records;
    }

    private byte[] query(String path, byte[] request) {
        try {
            var result = gateway.query(path, request);
            if (!gateway.chainId().equals(result.chainId())
                    || !"eutxo-ledger".equals(result.stateMachineId())) {
                throw new IllegalStateException(
                        "EUTxO canonical query identity mismatch");
            }
            return result.payload();
        } catch (AppQueryException failure) {
            throw new IllegalStateException(
                    "EUTxO canonical query is unavailable", failure);
        }
    }

    private static SourcePoint source(AppBlock block) {
        return new SourcePoint(
                block.height(),
                HexFormat.of().formatHex(AppBlockCodec.blockHash(block)),
                block.l1Slot(),
                block.l1BlockHash() == null
                        ? ""
                        : HexFormat.of().formatHex(block.l1BlockHash()));
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (subscription != null) {
            try {
                subscription.close();
            } catch (Exception ignored) {
                // Continue deterministic local cleanup.
            }
        }
        worker.shutdownNow();
        store.close();
    }

    private record SequencedDeposit(long sequence, EutxoDepositRecord record) {
    }

    private final class CanonicalRecords {
        private final List<SequencedDeposit> deposits;
        private final List<EutxoWithdrawalRecord> withdrawals;

        private CanonicalRecords(
                List<SequencedDeposit> deposits,
                List<EutxoWithdrawalRecord> withdrawals
        ) {
            this.deposits = deposits;
            this.withdrawals = withdrawals;
        }

        private List<EutxoIndexEvent> atHeight(
                long height,
                AppBlock block
        ) {
            List<EutxoIndexEvent> events = new ArrayList<>();
            for (var message : block.messages()) {
                byte[] payload = query(
                        EutxoQueryCodec.MESSAGE_SUMMARY_PATH,
                        EutxoQueryCodec.attemptRequest(message.getMessageId()));
                if (payload.length > 0) {
                    EutxoTransactionSummary summary =
                            EutxoTransactionSummary.decode(payload);
                    events.add(new EutxoIndexEvent.Transaction(
                            summary.sequence(), summary));
                }
            }
            deposits.stream()
                    .filter(deposit ->
                            deposit.record().creditedHeight() == height)
                    .map(deposit -> new EutxoIndexEvent.Deposit(
                            deposit.sequence(), deposit.record()))
                    .forEach(events::add);
            withdrawals.stream()
                    .filter(withdrawal ->
                            withdrawal.claim().requestedHeight() == height
                                    || withdrawal.updatedHeight() == height)
                    .map(withdrawal -> new EutxoIndexEvent.Withdrawal(
                            withdrawal.claim().settlementSequence() + 1,
                            withdrawal))
                    .forEach(events::add);
            events.sort(Comparator
                    .comparing((EutxoIndexEvent event) ->
                            event.getClass().getSimpleName())
                    .thenComparingLong(EutxoIndexEvent::sequence));
            return events;
        }
    }
}
