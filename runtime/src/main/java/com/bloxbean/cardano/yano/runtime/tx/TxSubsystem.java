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
import com.bloxbean.cardano.yano.runtime.tx.diffusion.DefaultTxDiffusion;
import com.bloxbean.cardano.yano.runtime.tx.diffusion.TxDiffusion;
import com.bloxbean.cardano.yano.runtime.tx.diffusion.TxDiffusionMode;
import com.bloxbean.cardano.yano.runtime.tx.diffusion.TxDiffusionStats;
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
    private static final int DEFAULT_MEMPOOL_MAX_TXS = 10_000;
    private static final long DEFAULT_MEMPOOL_MAX_BYTES = 128L * 1024L * 1024L;
    private static final long DEFAULT_MEMPOOL_TTL_SECONDS = 10_800L;
    private static final boolean DEFAULT_TX_DIFFUSION_ENABLED = true;
    private static final String DEFAULT_TX_DIFFUSION_MODE = "all-hot";
    private static final int DEFAULT_MAX_IN_FLIGHT_TXS_PER_PEER = 100;
    private static final long DEFAULT_MAX_IN_FLIGHT_BYTES_PER_PEER = 1L * 1024L * 1024L;
    private static final long DEFAULT_PEER_COOLDOWN_MS = 60_000L;

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
    private SubscriptionHandle txDiffusionSubscription;
    private ScheduledFuture<?> mempoolEvictionTask;
    private List<SubscriptionHandle> validatorListenerSubscriptions = List.of();
    private boolean validatorListenerRegistered;
    private volatile boolean accepting;
    private volatile boolean closed;
    private volatile int mempoolMaxTxs = DEFAULT_MEMPOOL_MAX_TXS;
    private volatile long mempoolMaxBytes = DEFAULT_MEMPOOL_MAX_BYTES;
    private volatile long mempoolTtlSeconds = DEFAULT_MEMPOOL_TTL_SECONDS;
    private volatile String txDiffusionMode = DEFAULT_TX_DIFFUSION_MODE;
    private volatile int txDiffusionMaxInFlightTxsPerPeer = DEFAULT_MAX_IN_FLIGHT_TXS_PER_PEER;
    private volatile long txDiffusionMaxInFlightBytesPerPeer = DEFAULT_MAX_IN_FLIGHT_BYTES_PER_PEER;
    private volatile long txDiffusionPeerCooldownMs = DEFAULT_PEER_COOLDOWN_MS;
    private volatile TxDiffusion txDiffusion = TxDiffusion.disabled();

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

        resolveTxConfig();
        txDiffusion = new DefaultTxDiffusion(
                TxDiffusionMode.fromConfig(txDiffusionMode),
                memPool,
                txDiffusionMaxInFlightTxsPerPeer,
                txDiffusionMaxInFlightBytesPerPeer,
                txDiffusionPeerCooldownMs,
                log);
        long maxAgeMillis = TimeUnit.SECONDS.toMillis(mempoolTtlSeconds);
        mempoolEvictionPolicy = new DefaultMempoolEvictionPolicy(
                memPool, maxAgeMillis, mempoolMaxTxs, mempoolMaxBytes);

        mempoolEvictionSubscription = eventBus.subscribe(BlockAppliedEvent.class, ctx ->
                        mempoolEvictionPolicy.onBlockApplied(ctx.event()),
                SubscriptionOptions.builder().build());
        txDiffusionSubscription = eventBus.subscribe(MemPoolTransactionReceivedEvent.class,
                ctx -> txDiffusion.onTransactionAccepted(ctx.event().transaction()),
                SubscriptionOptions.builder().build());

        mempoolEvictionTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                mempoolEvictionPolicy.onPeriodicCheck();
            } catch (Exception e) {
                log.debug("Error in mempool periodic eviction check: {}", e.getMessage());
            }
        }, 30, 30, TimeUnit.SECONDS);

        enableAdmission();
        log.info("Mempool eviction policy initialized (ttl={}s, maxTxs={}, maxBytes={}, txDiffusionMode={})",
                mempoolTtlSeconds, mempoolMaxTxs, mempoolMaxBytes, txDiffusionMode);
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
        if (txDiffusionSubscription != null) {
            txDiffusionSubscription.close();
            txDiffusionSubscription = null;
        }
        txDiffusion.close();
        txDiffusion = TxDiffusion.disabled();
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

    public long mempoolBytes() {
        return memPool.byteSize();
    }

    public int mempoolMaxTxs() {
        return mempoolMaxTxs;
    }

    public long mempoolMaxBytes() {
        return mempoolMaxBytes;
    }

    public long mempoolTtlSeconds() {
        return mempoolTtlSeconds;
    }

    public String txDiffusionMode() {
        return txDiffusionMode;
    }

    public boolean txDiffusionEnabled() {
        return !"disabled".equals(txDiffusionMode);
    }

    public TxDiffusion txDiffusion() {
        return txDiffusion;
    }

    public TxDiffusionStats txDiffusionStats() {
        return txDiffusion.stats();
    }

    public int txDiffusionMaxInFlightTxsPerPeer() {
        return txDiffusionMaxInFlightTxsPerPeer;
    }

    public long txDiffusionMaxInFlightBytesPerPeer() {
        return txDiffusionMaxInFlightBytesPerPeer;
    }

    public long txDiffusionPeerCooldownMs() {
        return txDiffusionPeerCooldownMs;
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
        return new SubsystemHealth(name(), SubsystemHealth.Status.UP, null, Map.ofEntries(
                Map.entry("mempoolSize", memPool.size()),
                Map.entry("mempoolBytes", memPool.byteSize()),
                Map.entry("mempoolMaxTxs", mempoolMaxTxs),
                Map.entry("mempoolMaxBytes", mempoolMaxBytes),
                Map.entry("mempoolTtlSeconds", mempoolTtlSeconds),
                Map.entry("accepting", accepting),
                Map.entry("validationAvailable", transactionValidationService != null),
                Map.entry("evaluationAvailable", transactionEvaluationService != null),
                Map.entry("txDiffusionMode", txDiffusionMode),
                Map.entry("txDiffusionEnabled", txDiffusionEnabled()),
                Map.entry("txDiffusionMaxInFlightTxsPerPeer", txDiffusionMaxInFlightTxsPerPeer),
                Map.entry("txDiffusionMaxInFlightBytesPerPeer", txDiffusionMaxInFlightBytesPerPeer),
                Map.entry("txDiffusionPeerCooldownMs", txDiffusionPeerCooldownMs)));
    }

    private void resolveTxConfig() {
        Map<String, Object> globals = runtimeOptions.globals();
        mempoolMaxTxs = Math.max(1, resolveInt(
                globals, YanoPropertyKeys.Tx.MEMPOOL_MAX_TXS, DEFAULT_MEMPOOL_MAX_TXS));
        mempoolMaxBytes = Math.max(0L, resolveLong(
                globals, YanoPropertyKeys.Tx.MEMPOOL_MAX_BYTES, DEFAULT_MEMPOOL_MAX_BYTES));
        mempoolTtlSeconds = Math.max(0L, resolveLong(
                globals, YanoPropertyKeys.Tx.MEMPOOL_TTL_SECONDS, DEFAULT_MEMPOOL_TTL_SECONDS));
        txDiffusionMode = resolveTxDiffusionMode(globals);
        txDiffusionMaxInFlightTxsPerPeer = Math.max(1, resolveInt(
                globals,
                YanoPropertyKeys.Tx.DIFFUSION_MAX_IN_FLIGHT_TXS_PER_PEER,
                DEFAULT_MAX_IN_FLIGHT_TXS_PER_PEER));
        txDiffusionMaxInFlightBytesPerPeer = Math.max(0L, resolveLong(
                globals,
                YanoPropertyKeys.Tx.DIFFUSION_MAX_IN_FLIGHT_BYTES_PER_PEER,
                DEFAULT_MAX_IN_FLIGHT_BYTES_PER_PEER));
        txDiffusionPeerCooldownMs = Math.max(0L, resolveLong(
                globals,
                YanoPropertyKeys.Tx.DIFFUSION_PEER_COOLDOWN_MS,
                DEFAULT_PEER_COOLDOWN_MS));
    }

    private static boolean resolveBoolean(Map<String, Object> globals, String key, boolean def) {
        Object value = globals != null ? globals.get(key) : null;
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return Boolean.parseBoolean(s);
        return def;
    }

    private static int resolveInt(Map<String, Object> globals, String key, int def) {
        Object value = globals != null ? globals.get(key) : null;
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    private static long resolveLong(Map<String, Object> globals, String key, long def) {
        Object value = globals != null ? globals.get(key) : null;
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    private static String resolveString(Map<String, Object> globals, String key, String def) {
        Object value = globals != null ? globals.get(key) : null;
        if (value instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        return def;
    }

    private static String resolveTxDiffusionMode(Map<String, Object> globals) {
        String configuredMode = resolveString(globals, YanoPropertyKeys.Tx.DIFFUSION_MODE, null);
        if (configuredMode != null && !configuredMode.isBlank()) {
            return normalizeTxDiffusionMode(configuredMode);
        }
        boolean enabled = resolveBoolean(globals,
                YanoPropertyKeys.Tx.DIFFUSION_ENABLED,
                DEFAULT_TX_DIFFUSION_ENABLED);
        return enabled ? TxDiffusionMode.ALL_HOT.configValue() : TxDiffusionMode.DISABLED.configValue();
    }

    private static String normalizeTxDiffusionMode(String mode) {
        String normalized = mode != null ? mode.trim().toLowerCase(java.util.Locale.ROOT) : "";
        return switch (normalized) {
            case "local-submit-only", "trusted-hot", "all-hot" -> normalized;
            default -> TxDiffusionMode.DISABLED.configValue();
        };
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
