package com.bloxbean.cardano.yano.runtime.tx;

import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import com.bloxbean.cardano.yaci.events.api.SubscriptionHandle;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yano.api.config.RuntimeOptions;
import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.events.MemPoolTransactionReceivedEvent;
import com.bloxbean.cardano.yano.api.events.TransactionValidateEvent;
import com.bloxbean.cardano.yano.api.model.TxEvaluationResult;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.ledgerrules.TransactionEvaluator;
import com.bloxbean.cardano.yano.ledgerrules.TransactionValidator;
import com.bloxbean.cardano.yano.runtime.blockproducer.TransactionEvaluationService;
import com.bloxbean.cardano.yano.runtime.blockproducer.TransactionValidationException;
import com.bloxbean.cardano.yano.runtime.blockproducer.TransactionValidationService;
import com.bloxbean.cardano.yano.runtime.chain.DefaultMemPool;
import com.bloxbean.cardano.yano.runtime.chain.DefaultMempoolEvictionPolicy;
import com.bloxbean.cardano.yano.runtime.chain.MemPool;
import com.bloxbean.cardano.yano.runtime.chain.MempoolEvictionPolicy;
import com.bloxbean.cardano.yano.runtime.kernel.Subsystem;
import com.bloxbean.cardano.yano.runtime.kernel.SubsystemHealth;
import com.bloxbean.cardano.yaci.events.api.support.AnnotationListenerRegistrar;
import com.bloxbean.cardano.yano.runtime.validation.DefaultTransactionValidatorListener;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Owns mempool state, transaction validation/evaluation services, and transaction
 * admission event flow.
 */
public final class TxSubsystem implements Subsystem, TransactionAdmission, BlockTransactionSelector {
    private final EventBus eventBus;
    private final ScheduledExecutorService scheduler;
    private final RuntimeOptions runtimeOptions;
    private final Supplier<UtxoState> utxoStateSupplier;
    private final Logger log;
    private final MemPool memPool = new DefaultMemPool();
    private final BlockTransactionSelector blockTransactionSelector;
    private final ReentrantReadWriteLock admissionGate = new ReentrantReadWriteLock(true);

    private volatile TransactionValidationService transactionValidationService;
    private volatile TransactionEvaluationService transactionEvaluationService;
    private MempoolEvictionPolicy mempoolEvictionPolicy;
    private SubscriptionHandle mempoolEvictionSubscription;
    private ScheduledFuture<?> mempoolEvictionTask;
    private List<SubscriptionHandle> validatorListenerSubscriptions = List.of();
    private boolean validatorListenerRegistered;
    private volatile boolean accepting;
    private volatile boolean closed;

    public TxSubsystem(EventBus eventBus,
                       ScheduledExecutorService scheduler,
                       RuntimeOptions runtimeOptions,
                       Supplier<UtxoState> utxoStateSupplier,
                       Logger log) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.runtimeOptions = runtimeOptions != null ? runtimeOptions : RuntimeOptions.defaults();
        this.utxoStateSupplier = Objects.requireNonNull(utxoStateSupplier, "utxoStateSupplier");
        this.log = Objects.requireNonNull(log, "log");
        this.blockTransactionSelector = BlockTransactionSelectors.fromMemPool(
                memPool,
                this::transactionValidationService,
                this.utxoStateSupplier,
                this.log);
    }

    @Override
    public String name() {
        return "tx";
    }

    MemPool memPool() {
        return memPool;
    }

    public TransactionValidationService transactionValidationService() {
        return transactionValidationService;
    }

    @Override
    public synchronized void start() {
        if (closed) {
            throw new IllegalStateException("Transaction subsystem is closed");
        }
        if (mempoolEvictionPolicy != null) {
            enableAdmission();
            return;
        }

        long maxAgeMillis = 30 * 60 * 1000L;
        int maxSize = 10_000;
        mempoolEvictionPolicy = new DefaultMempoolEvictionPolicy(memPool, maxAgeMillis, maxSize);

        mempoolEvictionSubscription = eventBus.subscribe(BlockAppliedEvent.class, ctx ->
                        mempoolEvictionPolicy.onBlockApplied(ctx.event()),
                SubscriptionOptions.builder().build());

        mempoolEvictionTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                mempoolEvictionPolicy.onPeriodicCheck();
            } catch (Exception e) {
                log.debug("Error in mempool periodic eviction check: {}", e.getMessage());
            }
        }, 30, 30, TimeUnit.SECONDS);

        enableAdmission();
        log.info("Mempool eviction policy initialized (maxAge=30min, maxSize={})", maxSize);
    }

    @Override
    public void stop() {
        pauseAdmissionAndAwait();
    }

    /**
     * Disable new transaction admission and wait for any in-flight admission to
     * finish before returning.
     */
    public void pauseAdmissionAndAwait() {
        // The mempool and eviction task intentionally remain active across a
        // restartable stop/start cycle, matching the legacy runtime behavior.
        var writeLock = admissionGate.writeLock();
        writeLock.lock();
        try {
            accepting = false;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public synchronized void close() {
        closeAdmissionAndAwait();
        if (mempoolEvictionTask != null) {
            mempoolEvictionTask.cancel(false);
            mempoolEvictionTask = null;
        }
        if (mempoolEvictionSubscription != null) {
            mempoolEvictionSubscription.close();
            mempoolEvictionSubscription = null;
        }
        for (SubscriptionHandle subscription : validatorListenerSubscriptions) {
            subscription.close();
        }
        validatorListenerSubscriptions = List.of();
        validatorListenerRegistered = false;
        mempoolEvictionPolicy = null;
    }

    public synchronized void setTransactionEvaluator(TransactionValidator evaluator) {
        UtxoState utxoState = utxoStateSupplier.get();
        if (evaluator == null || utxoState == null) {
            log.info("Transaction evaluation not available: evaluator={}, utxoState={}",
                    evaluator != null ? "provided" : "null",
                    utxoState != null ? "available" : "null");
            return;
        }

        this.transactionValidationService = new TransactionValidationService(evaluator, utxoState);

        boolean defaultValidatorEnabled = resolveBoolean(
                runtimeOptions.globals(), YanoPropertyKeys.Validation.DEFAULT_VALIDATOR_ENABLED, true);

        if (defaultValidatorEnabled && !validatorListenerRegistered) {
            var validatorListener = new DefaultTransactionValidatorListener(this::transactionValidationService);
            validatorListenerSubscriptions = AnnotationListenerRegistrar.register(eventBus, validatorListener,
                    SubscriptionOptions.builder().build());
            validatorListenerRegistered = true;
            log.info("Default transaction validator listener registered (order=100)");
        } else if (!defaultValidatorEnabled) {
            log.info("Default transaction validator listener DISABLED by config");
        }

        log.info("Transaction evaluator set");
    }

    public synchronized void setScriptEvaluator(TransactionEvaluator scriptEvaluator) {
        UtxoState utxoState = utxoStateSupplier.get();
        if (scriptEvaluator == null || utxoState == null) {
            log.info("Script evaluation not available");
            return;
        }
        this.transactionEvaluationService = new TransactionEvaluationService(scriptEvaluator, utxoState);
        log.info("Script evaluator set for /utils/txs/evaluate endpoint");
    }

    public synchronized boolean isTransactionEvaluationAvailable() {
        return transactionEvaluationService != null;
    }

    public synchronized List<TxEvaluationResult> evaluateTransaction(byte[] txCbor) throws Exception {
        if (transactionEvaluationService == null) {
            throw new UnsupportedOperationException("Transaction evaluation is not available");
        }
        return transactionEvaluationService.evaluate(txCbor).stream()
                .map(result -> new TxEvaluationResult(
                        result.tag(), result.index(), result.memory(), result.steps()))
                .toList();
    }

    public String submitTransaction(byte[] txCbor, BiConsumer<String, byte[]> acceptedSubmitter) {
        String txHash = admitTransaction(txCbor, "rest-api");

        if (acceptedSubmitter != null) {
            acceptedSubmitter.accept(txHash, txCbor);
        }

        return txHash;
    }

    @Override
    public String admitTransaction(byte[] txCbor, String origin) {
        var readLock = admissionGate.readLock();
        readLock.lock();
        try {
            ensureAccepting();
            String txHash = TransactionUtil.getTxHash(txCbor);
            String eventOrigin = normalizeOrigin(origin);

            var validateEvent = new TransactionValidateEvent(txCbor, txHash, eventOrigin);
            eventBus.publish(validateEvent,
                    EventMetadata.builder().origin(eventOrigin).build(),
                    PublishOptions.builder().build());

            if (validateEvent.isRejected()) {
                throw new TransactionValidationException(validateEvent.rejections());
            }

            ensureAccepting();
            boolean alreadyPresent = memPool.contains(txHash);
            var memPoolTransaction = memPool.addTransaction(txCbor);
            if (memPoolTransaction != null) {
                try {
                    eventBus.publish(new MemPoolTransactionReceivedEvent(memPoolTransaction),
                            EventMetadata.builder().origin(eventOrigin).build(),
                            PublishOptions.builder().build());
                } catch (RuntimeException | Error e) {
                    if (!alreadyPresent) {
                        memPool.removeByTxHashes(Set.of(txHash));
                    }
                    throw e;
                }
            }

            return txHash;
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public int mempoolSize() {
        return memPool.size();
    }

    @Override
    public boolean hasPendingTransactions() {
        return blockTransactionSelector.hasPendingTransactions();
    }

    @Override
    public List<byte[]> drainForBlock() {
        return blockTransactionSelector.drainForBlock();
    }

    public void clearPendingTransactions() {
        var writeLock = admissionGate.writeLock();
        writeLock.lock();
        try {
            memPool.clear();
        } finally {
            writeLock.unlock();
        }
    }

    public boolean isAccepting() {
        return accepting;
    }

    @Override
    public synchronized SubsystemHealth health() {
        return new SubsystemHealth(name(), SubsystemHealth.Status.UP, null, Map.of(
                "mempoolSize", memPool.size(),
                "accepting", accepting,
                "validationAvailable", transactionValidationService != null,
                "evaluationAvailable", transactionEvaluationService != null));
    }

    private static boolean resolveBoolean(Map<String, Object> globals, String key, boolean def) {
        Object value = globals != null ? globals.get(key) : null;
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return Boolean.parseBoolean(s);
        return def;
    }

    private static String normalizeOrigin(String origin) {
        return origin != null && !origin.isBlank() ? origin : "unknown";
    }

    private void ensureAccepting() {
        if (closed) {
            throw new IllegalStateException("Transaction subsystem is closed");
        }
        if (!accepting) {
            throw new IllegalStateException("Transaction admission is not active");
        }
    }

    private void enableAdmission() {
        var writeLock = admissionGate.writeLock();
        writeLock.lock();
        try {
            accepting = true;
        } finally {
            writeLock.unlock();
        }
    }

    private void closeAdmissionAndAwait() {
        var writeLock = admissionGate.writeLock();
        writeLock.lock();
        try {
            accepting = false;
            closed = true;
        } finally {
            writeLock.unlock();
        }
    }

}
