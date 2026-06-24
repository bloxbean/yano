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
import com.bloxbean.cardano.yano.api.events.MemPoolTransactionReceivedEvent;
import com.bloxbean.cardano.yano.api.events.TransactionValidateEvent;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.api.utxo.model.Utxo;
import com.bloxbean.cardano.yano.ledgerrules.ValidationError;
import com.bloxbean.cardano.yano.ledgerrules.ValidationResult;
import com.bloxbean.cardano.yano.runtime.blockproducer.TransactionValidationException;
import com.bloxbean.cardano.yano.runtime.blockproducer.TransactionValidationService;
import com.bloxbean.cardano.yano.runtime.chain.DefaultMemPool;
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
    void mempoolEventFailureDoesNotRemovePreviouslyAcceptedDuplicate() {
        TxSubsystem subsystem = new TxSubsystem(
                eventBus, scheduler, RuntimeOptions.defaults(), () -> null,
                LoggerFactory.getLogger(TxSubsystemTest.class));
        subsystem.start();
        byte[] txCbor = sampleTxCbor();

        subsystem.admitTransaction(txCbor, "test");
        eventBus.subscribe(MemPoolTransactionReceivedEvent.class,
                ctx -> { throw new IllegalStateException("event failed"); },
                SubscriptionOptions.builder().build());

        assertThatThrownBy(() -> subsystem.admitTransaction(txCbor, "test"))
                .isInstanceOf(RuntimeException.class);

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
    void drainForBlockReturnsPendingTransactionsAndClearsMempool() {
        TxSubsystem subsystem = new TxSubsystem(
                eventBus, scheduler, RuntimeOptions.defaults(), () -> null,
                LoggerFactory.getLogger(TxSubsystemTest.class));
        subsystem.start();
        byte[] txCbor = sampleTxCbor();

        subsystem.admitTransaction(txCbor, "test");

        assertThat(subsystem.hasPendingTransactions()).isTrue();
        assertThat(subsystem.drainForBlock()).containsExactly(txCbor);
        assertThat(subsystem.hasPendingTransactions()).isFalse();
        assertThat(subsystem.memPool().size()).isZero();
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
        assertThat(memPool.isEmpty()).isTrue();
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

    private static byte[] sampleTxCbor() {
        Map txBody = new Map();
        Array inputs = new Array();
        Array input = new Array();
        input.add(new ByteString(new byte[32]));
        input.add(new UnsignedInteger(0));
        inputs.add(input);
        txBody.put(new UnsignedInteger(0), inputs);

        Array outputs = new Array();
        Map output = new Map();
        output.put(new UnsignedInteger(0), new ByteString(new byte[28]));
        output.put(new UnsignedInteger(1), new UnsignedInteger(1_000_000));
        outputs.add(output);
        txBody.put(new UnsignedInteger(1), outputs);
        txBody.put(new UnsignedInteger(2), new UnsignedInteger(200_000));

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
