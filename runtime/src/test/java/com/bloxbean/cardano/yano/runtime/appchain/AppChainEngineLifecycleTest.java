package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AuthScheme;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerMode;
import com.bloxbean.cardano.yano.api.appchain.signer.SignerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.WriteBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@Timeout(30)
class AppChainEngineLifecycleTest {

    @Test
    void shutdownNowFailureFallsBackToOwnedGracefulShutdown() {
        AssertionError forceFailure = new AssertionError("force shutdown failed");
        ScriptedShutdownExecutor executor =
                new ScriptedShutdownExecutor(forceFailure, null);

        AppChainEngine.ShutdownRequest request =
                AppChainEngine.requestExecutorShutdown(executor);

        assertThat(request.accepted()).isTrue();
        assertThat(request.failure()).isSameAs(forceFailure);
        assertThat(executor.shutdownNowCalls).hasValue(1);
        assertThat(executor.shutdownCalls).hasValue(1);
    }

    @Test
    void shutdownFailurePromotesLaterProcessFatalAndLeavesOwnershipUnaccepted() {
        AssertionError forceFailure = new AssertionError("force shutdown failed");
        TestVirtualMachineError fatal = new TestVirtualMachineError();
        ScriptedShutdownExecutor executor =
                new ScriptedShutdownExecutor(forceFailure, fatal);

        AppChainEngine.ShutdownRequest request =
                AppChainEngine.requestExecutorShutdown(executor);

        assertThat(request.accepted()).isFalse();
        assertThat(request.failure()).isSameAs(fatal);
        assertThat(fatal.getSuppressed()).containsExactly(forceFailure);
        assertThat(executor.shutdownNowCalls).hasValue(1);
        assertThat(executor.shutdownCalls).hasValue(1);
        executor.forceCleanup();
    }

    @Test
    void fatalBatchCloseWinsOverContainableApplyError(@TempDir Path dir) throws Exception {
        byte[] selfSeed = seed(61);
        byte[] otherSeed = seed(62);
        AppMessageSigner signer = new AppMessageSigner(HexUtil.encodeHexString(selfSeed));
        String selfKey = signer.publicKeyHex();
        String otherKey = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(otherSeed));
        AppChainConfig config = AppChainConfig.builder("engine-fatal-cleanup-chain")
                .signingKeyHex(HexUtil.encodeHexString(selfSeed))
                .memberKeysHex(Set.of(selfKey, otherKey))
                .proposerKeyHex(selfKey)
                .threshold(2)
                .maxBlockMessages(1)
                .poolMaxMessages(1)
                .build();
        AssertionError primary = new AssertionError("sensitive apply assertion");
        TestVirtualMachineError fatalCleanup = new TestVirtualMachineError();
        AppStateMachine machine = new AppStateMachine() {
            @Override public String id() { return "fatal-close-machine"; }

            @Override
            public void apply(AppBlock block, AppStateWriter writer) {
                throw primary;
            }
        };
        Logger logger = mock(Logger.class);
        AppChainEngine engine = null;
        AppLedgerStore ledger = new AppLedgerStore(
                dir.resolve("fatal-cleanup-ledger").toString(),
                LoggerFactory.getLogger("engine-fatal-cleanup-ledger"));
        FatalCloseWriteBatch batch = new FatalCloseWriteBatch(fatalCleanup);
        try {
            AppMsgPool pool = new AppMsgPool(1);
            assertThat(pool.add(signedMessage(signer, config.chainId())))
                    .isEqualTo(AppMsgPool.AddResult.ADDED);
            engine = new AppChainEngine(
                    config,
                    ledger,
                    pool,
                    machine,
                    signer,
                    new MemberGroup(Set.of(selfKey, otherKey), 2),
                    new AlwaysSequencer(),
                    60_000,
                    1,
                    config.blockMaxBytes(),
                    (topic, body) -> null,
                    logger,
                    () -> batch);

            engine.proposeTick();

            // closeCalls is incremented at close() entry, before the worker
            // merges the cleanup failure into the primary failure. Wait for
            // the observable contract instead of racing that bookkeeping.
            awaitCondition(() -> batch.closeCalls.get() == 1
                    && Arrays.asList(fatalCleanup.getSuppressed()).contains(primary));
            assertThat(fatalCleanup.getSuppressed()).containsExactly(primary);
            verify(logger, timeout(5_000)).error(
                    "App-chain propose tick failed (errorType={})",
                    TestVirtualMachineError.class.getName());
        } finally {
            if (engine != null) {
                engine.close();
                engine.closeCompletion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
            ledger.close();
        }
    }

    @Test
    void proposalErrorsCloseBatchesBeforeAndAfterPendingRoundPublication(@TempDir Path dir)
            throws Exception {
        byte[] selfSeed = seed(51);
        byte[] otherSeed = seed(52);
        AppMessageSigner messageSigner = new AppMessageSigner(HexUtil.encodeHexString(selfSeed));
        FailingSigner signer = new FailingSigner(messageSigner);
        String selfKey = signer.publicKeyHex();
        String otherKey = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(otherSeed));
        AppChainConfig config = AppChainConfig.builder("engine-error-chain")
                .signingKeyHex(HexUtil.encodeHexString(selfSeed))
                .memberKeysHex(Set.of(selfKey, otherKey))
                .proposerKeyHex(selfKey)
                .threshold(2)
                .maxBlockMessages(1)
                .poolMaxMessages(1)
                .build();

        ThrowFirstApplyMachine machine = new ThrowFirstApplyMachine();
        List<TrackingWriteBatch> batches = new CopyOnWriteArrayList<>();
        Logger logger = mock(Logger.class);
        AppChainEngine engine = null;
        AppLedgerStore ledger = new AppLedgerStore(
                dir.resolve("ledger").toString(), LoggerFactory.getLogger("engine-error-ledger"));
        try {
            AppMsgPool pool = new AppMsgPool(1);
            assertThat(pool.add(signedMessage(messageSigner, config.chainId())))
                    .isEqualTo(AppMsgPool.AddResult.ADDED);

            engine = new AppChainEngine(
                    config,
                    ledger,
                    pool,
                    machine,
                    signer,
                    new MemberGroup(Set.of(selfKey, otherKey), 2),
                    new AlwaysSequencer(),
                    60_000,
                    1,
                    config.blockMaxBytes(),
                    (topic, body) -> null,
                    logger,
                    () -> {
                        TrackingWriteBatch batch = new TrackingWriteBatch();
                        batches.add(batch);
                        return batch;
                    });

            engine.proposeTick();
            assertThat(machine.firstFailure.await(5, TimeUnit.SECONDS)).isTrue();
            awaitCondition(() -> batches.size() == 1 && batches.get(0).closeCalls.get() == 1);

            // The ordinary Error was contained and diagnosed, so the serial
            // event loop must remain usable for the next proposal attempt.
            engine.proposeTick();
            assertThat(machine.secondApply.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(signer.failure.await(5, TimeUnit.SECONDS)).isTrue();
            awaitCondition(() -> batches.size() == 2 && batches.get(1).closeCalls.get() == 1);

            engine.close();
            engine.closeCompletion().toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertThat(ledger.tipHeight()).isZero();
            assertThat(batches).allSatisfy(batch -> assertThat(batch.closeCalls).hasValue(1));
            verify(logger, times(2)).error(
                    "App-chain propose tick failed (errorType={})", AssertionError.class.getName());
        } finally {
            if (engine != null) {
                engine.close();
                try {
                    engine.closeCompletion().toCompletableFuture().get(5, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // Preserve the primary test failure.
                }
            }
            ledger.close();
        }
    }

    @Test
    void scheduledTaskFailureExtractionPreservesFatalCause() {
        TestVirtualMachineError fatal = new TestVirtualMachineError();
        FutureTask<Void> task = new FutureTask<>(() -> {
            throw fatal;
        });
        task.run();

        assertSame(fatal, AppChainEngine.completedTaskFailure(task, null));
    }

    @Test
    void boundedClose_defersCleanupUntilInterruptResistantApplyQuiesces(@TempDir Path dir)
            throws Exception {
        byte[] selfSeed = seed(41);
        byte[] otherSeed = seed(42);
        AppMessageSigner signer = new AppMessageSigner(HexUtil.encodeHexString(selfSeed));
        String selfKey = signer.publicKeyHex();
        String otherKey = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(otherSeed));
        AppChainConfig config = AppChainConfig.builder("engine-close-chain")
                .signingKeyHex(HexUtil.encodeHexString(selfSeed))
                .memberKeysHex(Set.of(selfKey, otherKey))
                .proposerKeyHex(selfKey)
                .threshold(2)
                .maxBlockMessages(1)
                .poolMaxMessages(1)
                .build();

        InterruptResistantMachine machine = new InterruptResistantMachine();
        ExecutorService closeCallers = Executors.newFixedThreadPool(4);
        AppChainEngine engine = null;
        CompletableFuture<Void> closeCompletion = null;
        AppLedgerStore ledger = new AppLedgerStore(
                dir.resolve("ledger").toString(), LoggerFactory.getLogger("engine-close-test"));
        try {
            AppMsgPool pool = new AppMsgPool(1);
            assertThat(pool.add(signedMessage(signer))).isEqualTo(AppMsgPool.AddResult.ADDED);

            engine = new AppChainEngine(
                    config,
                    ledger,
                    pool,
                    machine,
                    signer,
                    new MemberGroup(Set.of(selfKey, otherKey), 2),
                    new AlwaysSequencer(),
                    60_000,
                    1,
                    config.blockMaxBytes(),
                    (topic, body) -> null,
                    LoggerFactory.getLogger("engine-close-test"));
            closeCompletion = engine.closeCompletion().toCompletableFuture();
            AtomicBoolean cleanupCompletionOverlappedApply = new AtomicBoolean();
            closeCompletion.whenComplete((ignored, failure) -> {
                if (machine.applyActive.get()) {
                    cleanupCompletionOverlappedApply.set(true);
                }
            });

            engine.proposeTick();
            assertThat(machine.entered.await(5, TimeUnit.SECONDS)).isTrue();

            AppChainEngine target = engine;
            CountDownLatch startClose = new CountDownLatch(1);
            Future<?>[] closes = new Future<?>[4];
            for (int i = 0; i < closes.length; i++) {
                closes[i] = closeCallers.submit(() -> {
                    awaitUninterruptibly(startClose);
                    target.close();
                });
            }
            startClose.countDown();
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                for (Future<?> close : closes) {
                    close.get(3, TimeUnit.SECONDS);
                }
            });

            assertThat(machine.applyActive).isTrue();
            assertThat(machine.interruptObserved.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(closeCompletion).isNotDone();

            machine.release.countDown();
            closeCompletion.get(5, TimeUnit.SECONDS);

            assertThat(machine.applyActive).isFalse();
            assertThat(cleanupCompletionOverlappedApply).isFalse();
            // Two-member threshold leaves the applied proposal staged. Engine
            // termination must discard it rather than commit it.
            assertThat(ledger.tipHeight()).isZero();

            // Idempotence also holds after terminal cleanup.
            engine.close();
            assertThat(closeCompletion).isCompleted();
        } finally {
            machine.release.countDown();
            if (engine != null) {
                engine.close();
            }
            if (closeCompletion != null) {
                try {
                    closeCompletion.get(5, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // Preserve the primary assertion failure; the test timeout
                    // still prevents an unbounded callback leak.
                }
            }
            closeCallers.shutdownNow();
            ledger.close();
        }
    }

    private static AppMessage signedMessage(AppMessageSigner signer) {
        return signedMessage(signer, "engine-close-chain");
    }

    private static AppMessage signedMessage(AppMessageSigner signer, String chainId) {
        String topic = "test";
        byte[] body = "blocked apply".getBytes(StandardCharsets.UTF_8);
        long senderSeq = 1;
        long expiresAt = System.currentTimeMillis() / 1000 + 600;
        byte[] signedBody = AppMessage.signedBodyBytes(
                chainId, topic, signer.publicKey(), senderSeq, expiresAt, body);
        return AppMessage.builder()
                .messageId(AppMessage.computeMessageId(
                        chainId, topic, signer.publicKey(), senderSeq, expiresAt, body))
                .chainId(chainId)
                .topic(topic)
                .sender(signer.publicKey())
                .senderSeq(senderSeq)
                .expiresAt(expiresAt)
                .body(body)
                .authScheme(AuthScheme.ED25519.getValue())
                .authProof(signer.sign(signedBody))
                .build();
    }

    private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] seed(int value) {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) value);
        return seed;
    }

    private static final class InterruptResistantMachine implements AppStateMachine {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch interruptObserved = new CountDownLatch(1);
        private final AtomicBoolean applyActive = new AtomicBoolean();

        @Override
        public String id() {
            return "interrupt-resistant";
        }

        @Override
        public void apply(AppBlock block, AppStateWriter writer) {
            applyActive.set(true);
            entered.countDown();
            try {
                while (true) {
                    try {
                        release.await();
                        return;
                    } catch (InterruptedException e) {
                        interruptObserved.countDown();
                    }
                }
            } finally {
                applyActive.set(false);
            }
        }
    }

    private static final class ThrowFirstApplyMachine implements AppStateMachine {
        private final AtomicInteger applies = new AtomicInteger();
        private final CountDownLatch firstFailure = new CountDownLatch(1);
        private final CountDownLatch secondApply = new CountDownLatch(1);

        @Override
        public String id() {
            return "throw-first-apply";
        }

        @Override
        public void apply(AppBlock block, AppStateWriter writer) {
            if (applies.incrementAndGet() == 1) {
                firstFailure.countDown();
                throw new AssertionError("sensitive apply failure");
            }
            secondApply.countDown();
        }
    }

    private static final class FailingSigner implements SignerProvider {
        private final SignerProvider delegate;
        private final CountDownLatch failure = new CountDownLatch(1);

        private FailingSigner(SignerProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public byte[] sign(byte[] message) {
            failure.countDown();
            throw new AssertionError("sensitive signer failure");
        }

        @Override
        public byte[] publicKey() {
            return delegate.publicKey();
        }
    }

    private static final class TrackingWriteBatch extends WriteBatch {
        private final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public void close() {
            if (closeCalls.incrementAndGet() == 1) {
                super.close();
            }
        }
    }

    private static final class FatalCloseWriteBatch extends WriteBatch {
        private final TestVirtualMachineError fatal;
        private final AtomicInteger closeCalls = new AtomicInteger();

        private FatalCloseWriteBatch(TestVirtualMachineError fatal) {
            this.fatal = fatal;
        }

        @Override
        public void close() {
            if (closeCalls.incrementAndGet() == 1) {
                super.close();
                throw fatal;
            }
        }
    }

    private static final class TestVirtualMachineError extends VirtualMachineError {
    }

    private static final class ScriptedShutdownExecutor extends ScheduledThreadPoolExecutor {
        private final Throwable shutdownNowFailure;
        private final Throwable shutdownFailure;
        private final AtomicInteger shutdownNowCalls = new AtomicInteger();
        private final AtomicInteger shutdownCalls = new AtomicInteger();

        private ScriptedShutdownExecutor(
                Throwable shutdownNowFailure,
                Throwable shutdownFailure
        ) {
            super(1);
            this.shutdownNowFailure = shutdownNowFailure;
            this.shutdownFailure = shutdownFailure;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdownNowCalls.incrementAndGet();
            throwUnchecked(shutdownNowFailure);
            return super.shutdownNow();
        }

        @Override
        public void shutdown() {
            shutdownCalls.incrementAndGet();
            throwUnchecked(shutdownFailure);
            super.shutdown();
        }

        private void forceCleanup() {
            super.shutdownNow();
        }

        private static void throwUnchecked(Throwable failure) {
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }
    }

    private static final class AlwaysSequencer implements SequencerMode {
        @Override
        public String id() {
            return "test-always";
        }

        @Override
        public void init(com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerContext context) {
        }

        @Override
        public boolean shouldProposeNow(long height) {
            return true;
        }

        @Override
        public ProposalEligibility checkProposal(byte[] proposerKey, long height) {
            return ProposalEligibility.ACCEPT;
        }
    }
}
