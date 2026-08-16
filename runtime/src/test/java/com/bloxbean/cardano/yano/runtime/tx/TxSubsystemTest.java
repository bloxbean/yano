package com.bloxbean.cardano.yano.runtime.tx;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yano.api.config.RuntimeOptions;
import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import com.bloxbean.cardano.yano.api.events.MemPoolTransactionReceivedEvent;
import com.bloxbean.cardano.yano.api.events.TransactionValidateEvent;
import com.bloxbean.cardano.yano.api.events.UtxoStateAppliedEvent;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yano.runtime.blockproducer.DevnetBlockBuilder;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.api.utxo.model.Utxo;
import com.bloxbean.cardano.yano.ledgerrules.ValidationError;
import com.bloxbean.cardano.yano.ledgerrules.ValidationResult;
import com.bloxbean.cardano.yano.ledgerrules.ScriptReferenceResolverScope;
import com.bloxbean.cardano.yano.ledgerrules.TransactionEvaluator;
import com.bloxbean.cardano.yano.runtime.blockproducer.TransactionValidationException;
import com.bloxbean.cardano.yano.runtime.blockproducer.TransactionValidationService;
import com.bloxbean.cardano.yano.runtime.chain.DefaultMemPool;
import com.bloxbean.cardano.yano.runtime.chain.DefaultMempoolEvictionPolicy;
import com.bloxbean.cardano.yano.runtime.chain.MempoolAdmissionException;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yano.runtime.events.PropagatingEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TxSubsystemTest {
    private final PropagatingEventBus eventBus = new PropagatingEventBus();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void tearDown() {
        eventBus.close();
        scheduler.shutdownNow();
    }

    @Test
    void submitTransactionPublishesValidationAndMempoolEventsThenRelaysAcceptedTx() {
        TxSubsystem subsystem = new TxSubsystem(
                eventBus, scheduler, RuntimeOptions.defaults(), () -> null,
                LoggerFactory.getLogger(TxSubsystemTest.class));
        subsystem.start();
        AtomicInteger validationEvents = new AtomicInteger();
        AtomicInteger mempoolEvents = new AtomicInteger();
        AtomicReference<String> relayedHash = new AtomicReference<>();
        AtomicReference<byte[]> relayedTx = new AtomicReference<>();
        byte[] txCbor = sampleTxCbor();

        eventBus.subscribe(TransactionValidateEvent.class, ctx -> validationEvents.incrementAndGet(),
                SubscriptionOptions.builder().build());
        eventBus.subscribe(MemPoolTransactionReceivedEvent.class, ctx -> mempoolEvents.incrementAndGet(),
                SubscriptionOptions.builder().build());

        String txHash = subsystem.submitTransaction(txCbor, (hash, tx) -> {
            relayedHash.set(hash);
            relayedTx.set(tx);
        });

        assertThat(validationEvents.get()).isEqualTo(1);
        assertThat(mempoolEvents.get()).isEqualTo(1);
        assertThat(subsystem.memPool().size()).isEqualTo(1);
        assertThat(relayedHash.get()).isEqualTo(txHash);
        assertThat(relayedTx.get()).isSameAs(txCbor);
    }

    @Test
    void submitTransactionRejectsBeforeMempoolAdmissionAndRelay() {
        TxSubsystem subsystem = new TxSubsystem(
                eventBus, scheduler, RuntimeOptions.defaults(), () -> null,
                LoggerFactory.getLogger(TxSubsystemTest.class));
        subsystem.start();
        AtomicBoolean relayed = new AtomicBoolean(false);
        byte[] txCbor = sampleTxCbor();

        eventBus.subscribe(TransactionValidateEvent.class,
                ctx -> ctx.event().reject("test", "blocked"),
                SubscriptionOptions.builder().build());

        assertThatThrownBy(() -> subsystem.submitTransaction(txCbor, (hash, tx) -> relayed.set(true)))
                .isInstanceOf(TransactionValidationException.class);

        assertThat(subsystem.memPool().size()).isZero();
        assertThat(relayed).isFalse();
    }

    @Test
    void admitTransactionPropagatesOriginToValidationAndMempoolEvents() {
        TxSubsystem subsystem = new TxSubsystem(
                eventBus, scheduler, RuntimeOptions.defaults(), () -> null,
                LoggerFactory.getLogger(TxSubsystemTest.class));
        subsystem.start();
        AtomicReference<String> validationEventOrigin = new AtomicReference<>();
        AtomicReference<String> validationMetadataOrigin = new AtomicReference<>();
        AtomicReference<String> mempoolMetadataOrigin = new AtomicReference<>();
        byte[] txCbor = sampleTxCbor();

        eventBus.subscribe(TransactionValidateEvent.class, ctx -> {
                    validationEventOrigin.set(ctx.event().origin());
                    validationMetadataOrigin.set(ctx.metadata().origin());
                },
                SubscriptionOptions.builder().build());
        eventBus.subscribe(MemPoolTransactionReceivedEvent.class,
                ctx -> mempoolMetadataOrigin.set(ctx.metadata().origin()),
                SubscriptionOptions.builder().build());

        subsystem.admitTransaction(txCbor, "txsubmission");

        assertThat(validationEventOrigin.get()).isEqualTo("txsubmission");
        assertThat(validationMetadataOrigin.get()).isEqualTo("txsubmission");
        assertThat(mempoolMetadataOrigin.get()).isEqualTo("txsubmission");
    }

    @Test
    void admissionRejectsWhenStoppedOrClosed() {
        TxSubsystem subsystem = new TxSubsystem(
                eventBus, scheduler, RuntimeOptions.defaults(), () -> null,
                LoggerFactory.getLogger(TxSubsystemTest.class));
        subsystem.start();
        subsystem.stop();

        assertThatThrownBy(() -> subsystem.admitTransaction(sampleTxCbor(), "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Transaction admission is not active");

        subsystem.start();
        subsystem.close();

        assertThatThrownBy(() -> subsystem.admitTransaction(sampleTxCbor(), "test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Transaction subsystem is closed");
    }

    @Test
    void pauseAdmissionAndAwaitWaitsForInFlightAdmission() throws Exception {
        TxSubsystem subsystem = new TxSubsystem(
                eventBus, scheduler, RuntimeOptions.defaults(), () -> null,
                LoggerFactory.getLogger(TxSubsystemTest.class));
        subsystem.start();
        CountDownLatch validationStarted = new CountDownLatch(1);
        CountDownLatch releaseValidation = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<String> admission = null;
        Future<?> pause = null;

        eventBus.subscribe(TransactionValidateEvent.class, ctx -> {
                    validationStarted.countDown();
                    try {
                        if (!releaseValidation.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("validation callback was not released");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                },
                SubscriptionOptions.builder().build());

        try {
            admission = executor.submit(() -> subsystem.admitTransaction(sampleTxCbor(), "test"));
            assertThat(validationStarted.await(5, TimeUnit.SECONDS)).isTrue();

            pause = executor.submit(subsystem::pauseAdmissionAndAwait);
            Thread.sleep(100);

            assertThat(pause.isDone()).isFalse();

            releaseValidation.countDown();

            assertThat(admission.get(5, TimeUnit.SECONDS)).isNotBlank();
            pause.get(5, TimeUnit.SECONDS);
            assertThat(subsystem.isAccepting()).isFalse();
            assertThatThrownBy(() -> subsystem.admitTransaction(sampleTxCbor(), "test"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Transaction admission is not active");
        } finally {
            releaseValidation.countDown();
            if (admission != null) {
                admission.cancel(true);
            }
            if (pause != null) {
                pause.cancel(true);
            }
            executor.shutdownNow();
        }
    }

    @Test
    void clearPendingTransactionsWaitsForInFlightAdmission() throws Exception {
        TxSubsystem subsystem = new TxSubsystem(
                eventBus, scheduler, RuntimeOptions.defaults(), () -> null,
                LoggerFactory.getLogger(TxSubsystemTest.class));
        subsystem.start();
        CountDownLatch validationStarted = new CountDownLatch(1);
        CountDownLatch releaseValidation = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<String> admission = null;
        Future<?> clear = null;

        eventBus.subscribe(TransactionValidateEvent.class, ctx -> {
                    validationStarted.countDown();
                    try {
                        if (!releaseValidation.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("validation callback was not released");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                },
                SubscriptionOptions.builder().build());

        try {
            admission = executor.submit(() -> subsystem.admitTransaction(sampleTxCbor(), "test"));
            assertThat(validationStarted.await(5, TimeUnit.SECONDS)).isTrue();

            clear = executor.submit(subsystem::clearPendingTransactions);
            Thread.sleep(100);

            assertThat(clear.isDone()).isFalse();

            releaseValidation.countDown();

            assertThat(admission.get(5, TimeUnit.SECONDS)).isNotBlank();
            clear.get(5, TimeUnit.SECONDS);
            assertThat(subsystem.memPool().size()).isZero();
        } finally {
            releaseValidation.countDown();
            if (admission != null) {
                admission.cancel(true);
            }
            if (clear != null) {
                clear.cancel(true);
            }
            executor.shutdownNow();
        }
    }

    @Test
    void mempoolEventFailureRollsBackNewAdmission() {
        TxSubsystem subsystem = new TxSubsystem(
                eventBus, scheduler, RuntimeOptions.defaults(), () -> null,
                LoggerFactory.getLogger(TxSubsystemTest.class));
        subsystem.start();

        eventBus.subscribe(MemPoolTransactionReceivedEvent.class,
                ctx -> { throw new IllegalStateException("event failed"); },
                SubscriptionOptions.builder().build());

        assertThatThrownBy(() -> subsystem.admitTransaction(sampleTxCbor(), "test"))
                .isInstanceOf(RuntimeException.class);

        assertThat(subsystem.memPool().size()).isZero();
    }

    @Test
    void duplicateAdmissionDoesNotRepublishMempoolEvent() {
        TxSubsystem subsystem = new TxSubsystem(
                eventBus, scheduler, RuntimeOptions.defaults(), () -> null,
                LoggerFactory.getLogger(TxSubsystemTest.class));
        subsystem.start();
        byte[] txCbor = sampleTxCbor();

        subsystem.admitTransaction(txCbor, "test");
        eventBus.subscribe(MemPoolTransactionReceivedEvent.class,
                ctx -> { throw new IllegalStateException("event failed"); },
                SubscriptionOptions.builder().build());

        assertThat(subsystem.admitTransaction(txCbor, "test")).isNotBlank();

        assertThat(subsystem.memPool().size()).isEqualTo(1);
    }

    @Test
    void closeUnsubscribesDefaultValidatorListener() {
        TxSubsystem subsystem = new TxSubsystem(
                eventBus, scheduler, RuntimeOptions.defaults(), TxSubsystemTest::enabledUtxoState,
                LoggerFactory.getLogger(TxSubsystemTest.class));
        subsystem.setTransactionEvaluator((txCbor, inputUtxos) -> ValidationResult.success());

        TransactionValidateEvent beforeClose = new TransactionValidateEvent(new byte[] {1}, "hash1", "test");
        eventBus.publish(beforeClose,
                EventMetadata.builder().origin("test").build(),
                PublishOptions.builder().build());
        assertThat(beforeClose.isRejected()).isTrue();

        subsystem.close();

        TransactionValidateEvent afterClose = new TransactionValidateEvent(new byte[] {1}, "hash2", "test");
        eventBus.publish(afterClose,
                EventMetadata.builder().origin("test").build(),
                PublishOptions.builder().build());
        assertThat(afterClose.isRejected()).isFalse();
    }

    @Test
    void evaluationUnavailableThrowsStableException() {
        TxSubsystem subsystem = new TxSubsystem(
                eventBus, scheduler, RuntimeOptions.defaults(), () -> null,
                LoggerFactory.getLogger(TxSubsystemTest.class));

        assertThat(subsystem.isTransactionEvaluationAvailable()).isFalse();
        assertThatThrownBy(() -> subsystem.evaluateTransaction(sampleTxCbor()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("Transaction evaluation is not available");
    }

    @Test
    void evaluationResolvesUnconfirmedReferenceScriptOutputWithoutHoldingMempoolLane() throws Exception {
        java.util.Map<Outpoint, Utxo> canonical = new java.util.HashMap<>();
        Outpoint parentSeed = new Outpoint("61".repeat(32), 0);
        Outpoint childSeed = new Outpoint("62".repeat(32), 0);
        Outpoint concurrentSeed = new Outpoint("63".repeat(32), 0);
        canonical.put(parentSeed, testUtxo(parentSeed));
        canonical.put(childSeed, testUtxo(childSeed));
        canonical.put(concurrentSeed, testUtxo(concurrentSeed));
        UtxoState state = mapUtxoState(canonical);
        TxSubsystem subsystem = new TxSubsystem(
                eventBus, scheduler, RuntimeOptions.defaults(), () -> state,
                LoggerFactory.getLogger(TxSubsystemTest.class));
        subsystem.setTransactionEvaluator((txCbor, inputUtxos) -> ValidationResult.success());
        subsystem.start();

        var script = com.bloxbean.cardano.client.plutus.spec.PlutusV2Script.builder()
                .cborHex("49480100002221200101")
                .build();
        String scriptHash = java.util.HexFormat.of().formatHex(script.getScriptHash());
        var referenceOutput = com.bloxbean.cardano.client.transaction.spec.TransactionOutput.builder()
                .address("addr_test1qz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3jcu5d8ps7zex2k2xt3uqxgjqnnj83ws8lhrn648jjxtwq2ytjqp")
                .value(new com.bloxbean.cardano.client.transaction.spec.Value(
                        java.math.BigInteger.valueOf(1_000_000), null))
                .scriptRef(script)
                .build();
        var spendOutput = com.bloxbean.cardano.client.transaction.spec.TransactionOutput.builder()
                .address(referenceOutput.getAddress())
                .value(referenceOutput.getValue())
                .build();
        var parentBody = com.bloxbean.cardano.client.transaction.spec.TransactionBody.builder()
                .inputs(List.of(cclInput(parentSeed)))
                .outputs(List.of(spendOutput, referenceOutput))
                .fee(java.math.BigInteger.valueOf(200_001))
                .build();
        byte[] parent = com.bloxbean.cardano.client.transaction.spec.Transaction.builder()
                .body(parentBody).build().serialize();
        Outpoint parentSpendOutput = new Outpoint(TransactionUtil.getTxHash(parent), 0);
        Outpoint parentReferenceOutput = new Outpoint(TransactionUtil.getTxHash(parent), 1);
        subsystem.admitTransaction(parent, "test");
        assertThat(subsystem.resolveUtxo(parentReferenceOutput)).isPresent();
        assertThat(subsystem.getScriptRefBytesByHash(scriptHash).orElseThrow())
                .containsExactly(referenceOutput.getScriptRef());

        var childBody = com.bloxbean.cardano.client.transaction.spec.TransactionBody.builder()
                .inputs(List.of(cclInput(parentSpendOutput)))
                .referenceInputs(List.of(cclInput(parentReferenceOutput)))
                .collateral(List.of(cclInput(childSeed)))
                .outputs(List.of(spendOutput))
                .fee(java.math.BigInteger.valueOf(200_002))
                .build();
        byte[] child = com.bloxbean.cardano.client.transaction.spec.Transaction.builder()
                .body(childBody).build().serialize();
        byte[] concurrent = cclTransaction(concurrentSeed, 200_003);
        AtomicBoolean referenceScriptVisible = new AtomicBoolean();
        AtomicBoolean concurrentAdmissionSucceeded = new AtomicBoolean();
        AtomicReference<java.util.Set<com.bloxbean.cardano.client.api.model.Utxo>> evaluatedInputs =
                new AtomicReference<>();

        subsystem.setScriptEvaluator((txCbor, inputUtxos) -> {
            evaluatedInputs.set(java.util.Set.copyOf(inputUtxos));
            referenceScriptVisible.set(ScriptReferenceResolverScope.resolve(scriptHash).isPresent());
            concurrentAdmissionSucceeded.set(
                    subsystem.admitTransaction(concurrent, "evaluation-test") != null);
            return List.of(new TransactionEvaluator.EvaluationResult("spend", 0, 10, 20));
        });

        var results = subsystem.evaluateTransaction(child);

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.tag()).isEqualTo("spend");
            assertThat(result.memory()).isEqualTo(10);
            assertThat(result.steps()).isEqualTo(20);
        });
        assertThat(evaluatedInputs.get())
                .extracting(com.bloxbean.cardano.client.api.model.Utxo::getTxHash)
                .containsExactlyInAnyOrder(
                        parentSpendOutput.txHash(), parentReferenceOutput.txHash(), childSeed.txHash());
        assertThat(referenceScriptVisible).isTrue();
        assertThat(ScriptReferenceResolverScope.resolve(scriptHash)).isEmpty();
        assertThat(concurrentAdmissionSucceeded).isTrue();
        assertThat(subsystem.memPool().size()).isEqualTo(2);
    }

    @Test
    void startIsIdempotentAcrossRestartableLifecycle() {
        TxSubsystem subsystem = new TxSubsystem(
                eventBus, scheduler, RuntimeOptions.defaults(), () -> null,
                LoggerFactory.getLogger(TxSubsystemTest.class));

        subsystem.start();
        subsystem.start();
        subsystem.stop();
        subsystem.start();

        assertThat(subsystem.health().details()).containsEntry("mempoolSize", 0);
        subsystem.close();
    }

    @Test
    void startUsesConfiguredMempoolAndDiffusionSettings() {
        RuntimeOptions options = new RuntimeOptions(null, null, java.util.Map.of(
                YanoPropertyKeys.Tx.MEMPOOL_MAX_TXS, 2,
                YanoPropertyKeys.Tx.MEMPOOL_MAX_BYTES, 4096L,
                YanoPropertyKeys.Tx.MEMPOOL_TTL_SECONDS, 45L,
                YanoPropertyKeys.Tx.MEMPOOL_MAX_UTXO_INDEX_ENTRIES, 123,
                YanoPropertyKeys.Tx.DIFFUSION_MODE, "local-submit-only",
                YanoPropertyKeys.Tx.DIFFUSION_MAX_IN_FLIGHT_TXS_PER_PEER, 7,
                YanoPropertyKeys.Tx.DIFFUSION_MAX_IN_FLIGHT_BYTES_PER_PEER, 8192L,
                YanoPropertyKeys.Tx.DIFFUSION_PEER_COOLDOWN_MS, 12_345L));
        TxSubsystem subsystem = new TxSubsystem(
                eventBus, scheduler, options, () -> null,
                LoggerFactory.getLogger(TxSubsystemTest.class));

        subsystem.start();

        assertThat(subsystem.mempoolMaxTxs()).isEqualTo(2);
        assertThat(subsystem.mempoolMaxBytes()).isEqualTo(4096L);
        assertThat(subsystem.mempoolTtlSeconds()).isEqualTo(45L);
        assertThat(subsystem.mempoolMaxUtxoIndexEntries()).isEqualTo(123);
        assertThat(subsystem.txDiffusionMode()).isEqualTo("local-submit-only");
        assertThat(subsystem.txDiffusionEnabled()).isTrue();
        assertThat(subsystem.txDiffusionMaxInFlightTxsPerPeer()).isEqualTo(7);
        assertThat(subsystem.txDiffusionMaxInFlightBytesPerPeer()).isEqualTo(8192L);
        assertThat(subsystem.txDiffusionPeerCooldownMs()).isEqualTo(12_345L);
        assertThat(subsystem.health().details())
                .containsEntry("mempoolMaxTxs", 2)
                .containsEntry("mempoolMaxBytes", 4096L)
                .containsEntry("mempoolMaxUtxoIndexEntries", 123)
                .containsEntry("txDiffusionMode", "local-submit-only")
                .containsEntry("txDiffusionEnabled", true);
    }

    @Test
    void mempoolTracksBytesWithoutDuplicatingExistingTransactions() {
        var memPool = new DefaultMemPool();
        byte[] txCbor = sampleTxCbor();

        memPool.addTransaction(txCbor);
        memPool.addTransaction(txCbor);

        assertThat(memPool.size()).isEqualTo(1);
        assertThat(memPool.byteSize()).isEqualTo(txCbor.length);
    }

    @Test
    void txDiffusionObservesAcceptedMempoolEvents() {
        RuntimeOptions options = new RuntimeOptions(null, null, java.util.Map.of(
                YanoPropertyKeys.Tx.DIFFUSION_MODE, "local-submit-only"));
        TxSubsystem subsystem = new TxSubsystem(
                eventBus, scheduler, options, () -> null,
                LoggerFactory.getLogger(TxSubsystemTest.class));
        subsystem.start();

        subsystem.admitTransaction(sampleTxCbor(), "test");

        assertThat(subsystem.txDiffusionStats().enabled()).isTrue();
        assertThat(subsystem.txDiffusionStats().acceptedMempoolEvents()).isEqualTo(1L);
    }

    @Test
    void mempoolEvictsOldestTransactionsToByteCap() {
        var memPool = new DefaultMemPool();
        byte[] firstTx = sampleTxCbor(200_000, (byte) 1);
        byte[] secondTx = sampleTxCbor(200_001, (byte) 2);
        memPool.addTransaction(firstTx);
        memPool.addTransaction(secondTx);

        int evicted = memPool.evictOldestUntilBytesAtMost(secondTx.length);

        assertThat(evicted).isEqualTo(1);
        assertThat(memPool.size()).isEqualTo(1);
        assertThat(memPool.byteSize()).isEqualTo(secondTx.length);
        assertThat(memPool.getNextTransaction().txBytes()).containsExactly(secondTx);
    }

    @Test
    void evictionPolicyAppliesByteCap() {
        var memPool = new DefaultMemPool();
        byte[] firstTx = sampleTxCbor(200_000, (byte) 1);
        byte[] secondTx = sampleTxCbor(200_001, (byte) 2);
        memPool.addTransaction(firstTx);
        memPool.addTransaction(secondTx);
        var policy = new DefaultMempoolEvictionPolicy(memPool, 0, 100, secondTx.length);

        policy.onPeriodicCheck();

        assertThat(memPool.size()).isEqualTo(1);
        assertThat(memPool.byteSize()).isEqualTo(secondTx.length);
        assertThat(memPool.getNextTransaction().txBytes()).containsExactly(secondTx);
    }

    @Test
    void drainForBlockReturnsSnapshotAndRetainsMempoolUntilCanonicalApply() {
        TxSubsystem subsystem = new TxSubsystem(
                eventBus, scheduler, RuntimeOptions.defaults(), () -> null,
                LoggerFactory.getLogger(TxSubsystemTest.class));
        subsystem.start();
        byte[] txCbor = sampleTxCbor();

        subsystem.admitTransaction(txCbor, "test");

        assertThat(subsystem.hasPendingTransactions()).isTrue();
        assertThat(subsystem.drainForBlock()).containsExactly(txCbor);
        assertThat(subsystem.hasPendingTransactions()).isTrue();
        assertThat(subsystem.memPool().size()).isEqualTo(1);

        subsystem.blockSelectionCompleted();
        assertThat(subsystem.drainForBlock()).containsExactly(txCbor);
    }

    @Test
    void blockTransactionSelectorUsesCurrentValidationServiceWhenDraining() {
        var memPool = new DefaultMemPool();
        byte[] txCbor = sampleTxCbor();
        memPool.addTransaction(txCbor);
        AtomicReference<TransactionValidationService> validationService =
                new AtomicReference<>(validationService(rejection()));
        BlockTransactionSelector selector = BlockTransactionSelectors.fromMemPool(
                memPool,
                validationService::get,
                TxSubsystemTest::enabledUtxoState,
                LoggerFactory.getLogger(TxSubsystemTest.class));

        validationService.set(validationService(ValidationResult.success()));

        assertThat(selector.drainForBlock()).containsExactly(txCbor);
        assertThat(memPool.isEmpty()).isFalse();
    }

    @Test
    void blockTransactionSelectorDropsRejectedTransactionsWhenDraining() {
        var memPool = new DefaultMemPool();
        memPool.addTransaction(sampleTxCbor());
        BlockTransactionSelector selector = BlockTransactionSelectors.fromMemPool(
                memPool,
                () -> validationService(rejection()),
                TxSubsystemTest::enabledUtxoState,
                LoggerFactory.getLogger(TxSubsystemTest.class));

        assertThat(selector.drainForBlock()).isEmpty();
        assertThat(memPool.isEmpty()).isTrue();
    }

    @Test
    void mempoolOverlaySupportsSequentialParentChildAdmissionAndBlockSelection() {
        java.util.Map<Outpoint, Utxo> canonical = new java.util.HashMap<>();
        Outpoint seed = new Outpoint("11".repeat(32), 0);
        canonical.put(seed, testUtxo(seed));
        UtxoState state = mapUtxoState(canonical);
        TxSubsystem subsystem = new TxSubsystem(
                eventBus, scheduler, RuntimeOptions.defaults(), () -> state,
                LoggerFactory.getLogger(TxSubsystemTest.class));
        subsystem.setTransactionEvaluator((txCbor, inputUtxos) -> ValidationResult.success());
        subsystem.start();

        byte[] parent = cclTransaction(seed, 200_001);
        Outpoint parentOutput = new Outpoint(TransactionUtil.getTxHash(parent), 0);
        byte[] child = cclTransaction(parentOutput, 200_002);

        assertThat(subsystem.admitTransaction(parent, "test"))
                .isEqualTo(parentOutput.txHash());
        assertThat(subsystem.admitTransaction(child, "test")).isNotBlank();
        assertThat(subsystem.memPool().stats().dependencyEdges()).isEqualTo(1);

        assertThat(subsystem.drainForBlock()).containsExactly(parent, child);
        assertThat(subsystem.memPool().size()).isEqualTo(2);
        assertThatThrownBy(subsystem::drainForBlock)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("selection is already in flight");

        subsystem.blockSelectionFailed();
        assertThat(subsystem.drainForBlock()).containsExactly(parent, child);
    }

    @Test
    void childSubmittedBeforeParentIsRejectedAndCanBeRetriedAfterParentCommits() {
        java.util.Map<Outpoint, Utxo> canonical = new java.util.HashMap<>();
        Outpoint seed = new Outpoint("22".repeat(32), 0);
        canonical.put(seed, testUtxo(seed));
        UtxoState state = mapUtxoState(canonical);
        TxSubsystem subsystem = new TxSubsystem(
                eventBus, scheduler, RuntimeOptions.defaults(), () -> state,
                LoggerFactory.getLogger(TxSubsystemTest.class));
        subsystem.setTransactionEvaluator((txCbor, inputUtxos) -> ValidationResult.success());
        subsystem.start();

        byte[] parent = cclTransaction(seed, 200_001);
        byte[] child = cclTransaction(
                new Outpoint(TransactionUtil.getTxHash(parent), 0), 200_002);

        assertThatThrownBy(() -> subsystem.admitTransaction(child, "test"))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessageContaining("UTXO not found");
        subsystem.admitTransaction(parent, "test");
        assertThat(subsystem.admitTransaction(child, "test")).isNotBlank();
        assertThat(subsystem.memPool().size()).isEqualTo(2);
    }

    @Test
    void concurrentDoubleSpendCommitsExactlyOneTransaction() throws Exception {
        java.util.Map<Outpoint, Utxo> canonical = new java.util.HashMap<>();
        Outpoint seed = new Outpoint("33".repeat(32), 0);
        canonical.put(seed, testUtxo(seed));
        UtxoState state = mapUtxoState(canonical);
        TxSubsystem subsystem = new TxSubsystem(
                eventBus, scheduler, RuntimeOptions.defaults(), () -> state,
                LoggerFactory.getLogger(TxSubsystemTest.class));
        subsystem.setTransactionEvaluator((txCbor, inputUtxos) -> ValidationResult.success());
        subsystem.start();
        byte[] first = cclTransaction(seed, 200_001);
        byte[] second = cclTransaction(seed, 200_002);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstResult = executor.submit(() -> admitAfterStart(
                    subsystem, first, start, accepted, conflicted));
            Future<?> secondResult = executor.submit(() -> admitAfterStart(
                    subsystem, second, start, accepted, conflicted));
            start.countDown();
            firstResult.get(5, TimeUnit.SECONDS);
            secondResult.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(accepted).hasValue(1);
        assertThat(conflicted).hasValue(1);
        assertThat(subsystem.memPool().size()).isEqualTo(1);
    }

    @Test
    void canonicalUtxoAcknowledgementRemovesConfirmedParentWithoutCascadingChild() throws Exception {
        java.util.Map<Outpoint, Utxo> canonical = new java.util.HashMap<>();
        Outpoint seed = new Outpoint("44".repeat(32), 0);
        canonical.put(seed, testUtxo(seed));
        UtxoState state = mapUtxoState(canonical);
        TxSubsystem subsystem = new TxSubsystem(
                eventBus, scheduler, RuntimeOptions.defaults(), () -> state,
                LoggerFactory.getLogger(TxSubsystemTest.class));
        subsystem.setTransactionEvaluator((txCbor, inputUtxos) -> ValidationResult.success());
        subsystem.start();
        byte[] parent = cclTransaction(seed, 200_001);
        String parentHash = TransactionUtil.getTxHash(parent);
        Outpoint parentOutput = new Outpoint(parentHash, 0);
        byte[] child = cclTransaction(parentOutput, 200_002);
        String childHash = TransactionUtil.getTxHash(child);
        subsystem.admitTransaction(parent, "test");
        subsystem.admitTransaction(child, "test");

        canonical.put(parentOutput, testUtxo(parentOutput));
        var built = new DevnetBlockBuilder().buildBlock(1, 1, null, List.of(parent));
        var block = BlockSerializer.INSTANCE.deserialize(built.blockCbor());
        var applied = new BlockAppliedEvent(Era.Conway, 1, 1, "block", block);
        eventBus.publish(new UtxoStateAppliedEvent(applied),
                EventMetadata.builder().origin("test").build(),
                PublishOptions.builder().build());

        assertThat(subsystem.memPool().contains(parentHash)).isFalse();
        assertThat(subsystem.memPool().contains(childHash)).isTrue();
        assertThat(subsystem.memPool().stats().dependencyEdges()).isZero();
    }

    @Test
    void recursiveAdmissionFromValidationListenerFailsFastWithoutPartialCommit() {
        TxSubsystem subsystem = new TxSubsystem(
                eventBus, scheduler, RuntimeOptions.defaults(), () -> null,
                LoggerFactory.getLogger(TxSubsystemTest.class));
        subsystem.start();
        byte[] nested = sampleTxCbor(200_002, (byte) 2);
        AtomicBoolean recurse = new AtomicBoolean(true);
        eventBus.subscribe(TransactionValidateEvent.class, ctx -> {
                    if (recurse.getAndSet(false)) subsystem.admitTransaction(nested, "recursive");
                }, SubscriptionOptions.builder().build());

        assertThatThrownBy(() -> subsystem.admitTransaction(
                sampleTxCbor(200_001, (byte) 1), "test"))
                .isInstanceOf(RuntimeException.class);
        assertThat(subsystem.memPool().isEmpty()).isTrue();
    }

    private static byte[] sampleTxCbor() {
        return sampleTxCbor(200_000);
    }

    private static void admitAfterStart(TxSubsystem subsystem, byte[] transaction,
                                        CountDownLatch start, AtomicInteger accepted,
                                        AtomicInteger conflicted) {
        try {
            start.await(5, TimeUnit.SECONDS);
            subsystem.admitTransaction(transaction, "test");
            accepted.incrementAndGet();
        } catch (MempoolAdmissionException e) {
            conflicted.incrementAndGet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static byte[] cclTransaction(Outpoint input, long fee) {
        try {
            var txInput = com.bloxbean.cardano.client.transaction.spec.TransactionInput.builder()
                    .transactionId(input.txHash()).index(input.index()).build();
            var output = com.bloxbean.cardano.client.transaction.spec.TransactionOutput.builder()
                    .address("addr_test1qz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3jcu5d8ps7zex2k2xt3uqxgjqnnj83ws8lhrn648jjxtwq2ytjqp")
                    .value(new com.bloxbean.cardano.client.transaction.spec.Value(
                            java.math.BigInteger.valueOf(1_000_000), null))
                    .build();
            var body = com.bloxbean.cardano.client.transaction.spec.TransactionBody.builder()
                    .inputs(List.of(txInput)).outputs(List.of(output))
                    .fee(java.math.BigInteger.valueOf(fee)).build();
            return com.bloxbean.cardano.client.transaction.spec.Transaction.builder()
                    .body(body).build().serialize();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static com.bloxbean.cardano.client.transaction.spec.TransactionInput cclInput(
            Outpoint input) {
        return com.bloxbean.cardano.client.transaction.spec.TransactionInput.builder()
                .transactionId(input.txHash()).index(input.index()).build();
    }

    private static Utxo testUtxo(Outpoint outpoint) {
        return new Utxo(outpoint,
                "addr_test1qz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3jcu5d8ps7zex2k2xt3uqxgjqnnj83ws8lhrn648jjxtwq2ytjqp",
                java.math.BigInteger.valueOf(2_000_000), List.of(), null, null,
                null, null, false, 1, 1, "block");
    }

    private static UtxoState mapUtxoState(java.util.Map<Outpoint, Utxo> utxos) {
        return new UtxoState() {
            @Override
            public List<Utxo> getUtxosByAddress(String address, int page, int pageSize) {
                return List.of();
            }

            @Override
            public List<Utxo> getUtxosByPaymentCredential(String credential, int page, int pageSize) {
                return List.of();
            }

            @Override
            public Optional<Utxo> getUtxo(Outpoint outpoint) {
                return Optional.ofNullable(utxos.get(outpoint));
            }

            @Override
            public boolean isEnabled() {
                return true;
            }
        };
    }

    private static byte[] sampleTxCbor(long fee) {
        return sampleTxCbor(fee, (byte) 0);
    }

    private static byte[] sampleTxCbor(long fee, byte inputDiscriminator) {
        Map txBody = new Map();
        Array inputs = new Array();
        Array input = new Array();
        byte[] inputHash = new byte[32];
        inputHash[31] = inputDiscriminator;
        input.add(new ByteString(inputHash));
        input.add(new UnsignedInteger(0));
        inputs.add(input);
        txBody.put(new UnsignedInteger(0), inputs);

        Array outputs = new Array();
        Map output = new Map();
        output.put(new UnsignedInteger(0), new ByteString(new byte[28]));
        output.put(new UnsignedInteger(1), new UnsignedInteger(1_000_000));
        outputs.add(output);
        txBody.put(new UnsignedInteger(1), outputs);
        txBody.put(new UnsignedInteger(2), new UnsignedInteger(fee));

        Map witnesses = new Map();

        Array tx = new Array();
        tx.add(txBody);
        tx.add(witnesses);
        tx.add(SimpleValue.TRUE);
        tx.add(SimpleValue.NULL);

        return CborSerializationUtil.serialize(tx);
    }

    private static TransactionValidationService validationService(ValidationResult result) {
        return new TransactionValidationService(null, null) {
            @Override
            public ValidationResult validate(byte[] txCbor, Function<Outpoint, Utxo> resolver) {
                return result;
            }
        };
    }

    private static ValidationResult rejection() {
        return ValidationResult.failure(new ValidationError(
                "test",
                "blocked",
                ValidationError.Phase.PHASE_1));
    }

    private static UtxoState enabledUtxoState() {
        return new UtxoState() {
            @Override
            public List<Utxo> getUtxosByAddress(String bech32OrHexAddress, int page, int pageSize) {
                return List.of();
            }

            @Override
            public List<Utxo> getUtxosByPaymentCredential(String credentialHexOrAddress, int page, int pageSize) {
                return List.of();
            }

            @Override
            public Optional<Utxo> getUtxo(Outpoint outpoint) {
                return Optional.empty();
            }

            @Override
            public boolean isEnabled() {
                return true;
            }
        };
    }
}
