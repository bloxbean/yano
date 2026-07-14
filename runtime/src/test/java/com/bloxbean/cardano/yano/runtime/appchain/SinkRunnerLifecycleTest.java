package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSink;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSinkFactory;
import com.bloxbean.cardano.yano.runtime.plugins.PluginProviderRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.WriteBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@Timeout(30)
class SinkRunnerLifecycleTest {

    private static final Logger LOG = LoggerFactory.getLogger(SinkRunnerLifecycleTest.class);
    private static final byte[] KEY = seed(91);
    private static final String KEY_HEX = HexUtil.encodeHexString(KEY);
    private static final String PUBLIC_KEY = HexUtil.encodeHexString(
            KeyGenUtil.getPublicKeyFromPrivateKey(KEY));

    @TempDir
    Path tempDir;

    @Test
    void asyncFatalCloseCompletesLifetimeBeforeReachingUncaughtHandler() throws Exception {
        TestVirtualMachineError fatal = new TestVirtualMachineError();
        FinalizedStreamSink sink = new FinalizedStreamSink() {
            @Override public String id() { return "fatal-async-close"; }

            @Override public boolean deliver(AppBlock block) { return true; }

            @Override
            public void close() {
                throw fatal;
            }
        };
        CountDownLatch uncaught = new CountDownLatch(1);
        AtomicReference<Throwable> uncaughtFailure = new AtomicReference<>();
        AtomicBoolean lifetimeTerminalAtUncaught = new AtomicBoolean();
        SinkRunner[] runnerRef = new SinkRunner[1];
        ThreadFactory closeThreads = task -> {
            Thread thread = new Thread(task, "test-fatal-sink-close");
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, failure) -> {
                uncaughtFailure.set(failure);
                lifetimeTerminalAtUncaught.set(
                        runnerRef[0].closeCompletion().isCompletedExceptionally());
                uncaught.countDown();
            });
            return thread;
        };
        Logger logger = mock(Logger.class);
        try (AppLedgerStore ledger = new AppLedgerStore(
                tempDir.resolve("fatal-close-ledger").toString(), LOG)) {
            SinkRunner runner = new SinkRunner(
                    sink, sink.id(), ledger, logger, closeThreads);
            runnerRef[0] = runner;

            CompletableFuture<Void> completion = runner.closeAsync();

            assertThat(uncaught.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(uncaughtFailure).hasValue(fatal);
            assertThat(lifetimeTerminalAtUncaught).isTrue();
            Throwable observed = catchThrowable(completion::join);
            assertThat(observed).isInstanceOf(CompletionException.class);
            assertThat(observed.getCause()).isSameAs(fatal);
            verify(logger).warn("Sink '{}' close failed (errorType={})",
                    sink.id(), TestVirtualMachineError.class.getName());
        }
    }

    @Test
    void fatalCloseWorkerStartClosesProductBeforeLifetimeAndRethrow() {
        TestVirtualMachineError fatal = new TestVirtualMachineError();
        AtomicInteger closeCalls = new AtomicInteger();
        AtomicReference<CompletableFuture<Void>> lifetime = new AtomicReference<>();
        AtomicBoolean lifetimeDoneInsideClose = new AtomicBoolean();
        FinalizedStreamSink sink = new FinalizedStreamSink() {
            @Override public String id() { return "fatal-close-start"; }
            @Override public boolean deliver(AppBlock block) { return true; }
            @Override public void close() {
                closeCalls.incrementAndGet();
                lifetimeDoneInsideClose.set(lifetime.get().isDone());
            }
        };
        ThreadFactory failingThreadFactory = task -> {
            throw fatal;
        };
        Logger logger = mock(Logger.class);
        try (AppLedgerStore ledger = new AppLedgerStore(
                tempDir.resolve("fatal-start-ledger").toString(), LOG)) {
            SinkRunner runner = new SinkRunner(
                    sink, sink.id(), ledger, logger, failingThreadFactory);
            lifetime.set(runner.closeCompletion());

            assertThatThrownBy(runner::closeAsync).isSameAs(fatal);
            assertThat(runner.closeCompletion()).isCompletedExceptionally();
            assertThat(closeCalls).hasValue(1);
            assertThat(lifetimeDoneInsideClose).isFalse();
            Throwable observed = catchThrowable(runner.closeCompletion()::join);
            assertThat(observed).isInstanceOf(CompletionException.class);
            assertThat(observed.getCause()).isSameAs(fatal);
            verify(logger).warn("Sink '{}' close task failed to start (errorType={})",
                    sink.id(), TestVirtualMachineError.class.getName());
        }
    }

    @Test
    void ordinaryCloseWorkerStartFailureFallsBackAndKeepsLifetimePendingUntilCloseEnds()
            throws Exception {
        IllegalStateException failure = new IllegalStateException("thread factory failed");
        AtomicInteger closeCalls = new AtomicInteger();
        CountDownLatch deliveryEntered = new CountDownLatch(1);
        CountDownLatch releaseDelivery = new CountDownLatch(1);
        CountDownLatch closeFinished = new CountDownLatch(1);
        FinalizedStreamSink sink = new FinalizedStreamSink() {
            @Override public String id() { return "ordinary-close-start"; }
            @Override public boolean deliver(AppBlock block) {
                deliveryEntered.countDown();
                try {
                    releaseDelivery.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return true;
            }
            @Override public void close() {
                closeCalls.incrementAndGet();
                closeFinished.countDown();
            }
        };
        Logger logger = mock(Logger.class);
        try (AppLedgerStore ledger = new AppLedgerStore(
                tempDir.resolve("ordinary-start-ledger").toString(), LOG)) {
            SinkRunner runner = new SinkRunner(
                    sink, sink.id(), ledger, logger, task -> { throw failure; });
            SinkRunner.initializeCursors(ledger, List.of(runner));
            commitOneBlock(ledger);
            Thread delivery = Thread.ofPlatform().start(runner::deliveryTick);
            assertThat(deliveryEntered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(runner::closeAsync).isSameAs(failure);
            assertThat(runner.closeCompletion()).isNotDone();
            assertThat(closeCalls).hasValue(0);

            releaseDelivery.countDown();
            delivery.join(5_000);
            assertThat(delivery.isAlive()).isFalse();
            assertThat(closeFinished.await(5, TimeUnit.SECONDS)).isTrue();
            // The sink signals from inside close(); the lifetime completes in
            // the worker's finally block immediately afterward. Join the
            // authoritative barrier instead of racing that epilogue.
            Throwable observed = catchThrowable(runner.closeCompletion()::join);
            assertThat(runner.closeCompletion()).isCompletedExceptionally();
            assertThat(observed).isInstanceOf(CompletionException.class);
            assertThat(observed.getCause()).isSameAs(failure);
            assertThat(closeCalls).hasValue(1);
            verify(logger).warn("Sink '{}' close task failed to start (errorType={})",
                    sink.id(), IllegalStateException.class.getName());
        } finally {
            releaseDelivery.countDown();
        }
    }

    @Test
    void fatalStartDiagnosticCannotAbortActualProductClose() {
        IllegalStateException startFailure = new IllegalStateException("close start");
        TestVirtualMachineError diagnosticFatal = new TestVirtualMachineError();
        AtomicInteger closeCalls = new AtomicInteger();
        AtomicBoolean lifetimeDoneInsideClose = new AtomicBoolean();
        AtomicReference<CompletableFuture<Void>> lifetime = new AtomicReference<>();
        FinalizedStreamSink sink = new FinalizedStreamSink() {
            @Override public String id() { return "fatal-start-diagnostic"; }
            @Override public boolean deliver(AppBlock block) { return true; }
            @Override public void close() {
                closeCalls.incrementAndGet();
                lifetimeDoneInsideClose.set(lifetime.get().isDone());
            }
        };
        Logger logger = mock(Logger.class, invocation -> {
            if ("warn".equals(invocation.getMethod().getName())) {
                throw diagnosticFatal;
            }
            return null;
        });
        try (AppLedgerStore ledger = new AppLedgerStore(
                tempDir.resolve("fatal-start-diagnostic-ledger").toString(), LOG)) {
            SinkRunner runner = new SinkRunner(
                    sink, sink.id(), ledger, logger, task -> { throw startFailure; });
            lifetime.set(runner.closeCompletion());

            assertThatThrownBy(runner::closeAsync).isSameAs(diagnosticFatal);
            assertThat(closeCalls).hasValue(1);
            assertThat(lifetimeDoneInsideClose).isFalse();
            assertThat(runner.closeCompletion()).isCompletedExceptionally();
            assertThatThrownBy(runner.closeCompletion()::join).hasCause(diagnosticFatal);
            assertThat(diagnosticFatal.getSuppressed()).containsExactly(startFailure);
        }
    }

    @Test
    void fatalCloseDiagnosticEscapesOnlyAfterActualProductLifetime() throws Exception {
        IllegalStateException closeFailure = new IllegalStateException("sink close");
        TestVirtualMachineError diagnosticFatal = new TestVirtualMachineError();
        FinalizedStreamSink sink = new FinalizedStreamSink() {
            @Override public String id() { return "fatal-close-diagnostic"; }
            @Override public boolean deliver(AppBlock block) { return true; }
            @Override public void close() { throw closeFailure; }
        };
        Logger logger = mock(Logger.class, invocation -> {
            if ("warn".equals(invocation.getMethod().getName())) {
                throw diagnosticFatal;
            }
            return null;
        });
        CountDownLatch fatalEscaped = new CountDownLatch(1);
        AtomicReference<Throwable> escapedFailure = new AtomicReference<>();
        AtomicBoolean lifetimeDoneAtFatalEscape = new AtomicBoolean();
        AtomicReference<SinkRunner> runnerRef = new AtomicReference<>();
        ThreadFactory closeThreads = task -> {
            Thread thread = new Thread(task, "test-fatal-sink-diagnostic");
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, failure) -> {
                escapedFailure.set(failure);
                lifetimeDoneAtFatalEscape.set(runnerRef.get().closeCompletion().isDone());
                fatalEscaped.countDown();
            });
            return thread;
        };
        try (AppLedgerStore ledger = new AppLedgerStore(
                tempDir.resolve("fatal-close-diagnostic-ledger").toString(), LOG)) {
            SinkRunner runner = new SinkRunner(
                    sink, sink.id(), ledger, logger, closeThreads);
            runnerRef.set(runner);
            CompletableFuture<Void> completion = runner.closeAsync();

            assertThat(fatalEscaped.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(escapedFailure).hasValue(diagnosticFatal);
            assertThat(lifetimeDoneAtFatalEscape).isTrue();
            assertThatThrownBy(completion::join).hasCause(diagnosticFatal);
            assertThat(diagnosticFatal.getSuppressed()).containsExactly(closeFailure);
        }
    }

    @Test
    void deliveryErrorIsIsolatedAndLaterTickStillAdvancesCursor() {
        AtomicInteger deliveries = new AtomicInteger();
        FinalizedStreamSink sink = new FinalizedStreamSink() {
            @Override public String id() { return "error-then-healthy"; }

            @Override
            public boolean deliver(AppBlock ignored) {
                if (deliveries.incrementAndGet() == 1) {
                    throw new AssertionError("secret-token-must-not-be-observable");
                }
                return true;
            }
        };
        try (AppLedgerStore ledger = new AppLedgerStore(
                tempDir.resolve("error-ledger").toString(), LOG)) {
            SinkRunner runner = new SinkRunner(sink, sink.id(), ledger, LOG);
            FinalizedStreamSink healthySink = new FinalizedStreamSink() {
                @Override public String id() { return "healthy-neighbor"; }
                @Override public boolean deliver(AppBlock block) { return true; }
            };
            SinkRunner healthy = new SinkRunner(
                    healthySink, healthySink.id(), ledger, LOG);
            SinkRunner.initializeCursors(ledger, List.of(runner, healthy));
            commitOneBlock(ledger);

            assertThatCode(() -> AppChainSubsystem.runSinkDeliveryTicks(List.of(runner, healthy)))
                    .doesNotThrowAnyException();
            assertThat(deliveries).hasValue(1);
            assertThat(runner.failureCount()).isEqualTo(1);
            assertThat(runner.lastErrorType()).isEqualTo(AssertionError.class.getName());
            assertThat(runner.lastErrorType()).doesNotContain("secret-token");
            assertThat(healthy.cursor()).isEqualTo(1);

            assertThatCode(() -> AppChainSubsystem.runSinkDeliveryTicks(List.of(runner, healthy)))
                    .doesNotThrowAnyException();
            assertThat(deliveries).hasValue(2);
            assertThat(runner.deliveredCount()).isEqualTo(1);
            assertThat(runner.cursor()).isEqualTo(1);
            assertThat(runner.lastErrorType()).isNull();
            assertThat(ledger.metaLong(SinkRunner.cursorKeyFor(sink.id()), -1L)).isEqualTo(1L);

            runner.close();
            healthy.close();
            assertThat(runner.closeCompletion()).isCompleted();
            assertThat(healthy.closeCompletion()).isCompleted();
        }
    }

    @Test
    void scheduledTickRecordsAndRethrowsVmFatalError() {
        TestVirtualMachineError fatal = new TestVirtualMachineError();
        FinalizedStreamSink sink = new FinalizedStreamSink() {
            @Override public String id() { return "vm-fatal"; }
            @Override public boolean deliver(AppBlock block) { throw fatal; }
        };
        try (AppLedgerStore ledger = new AppLedgerStore(
                tempDir.resolve("fatal-ledger").toString(), LOG)) {
            SinkRunner runner = new SinkRunner(sink, sink.id(), ledger, LOG);
            SinkRunner.initializeCursors(ledger, List.of(runner));
            commitOneBlock(ledger);

            assertThatThrownBy(() -> AppChainSubsystem.runSinkDeliveryTicks(List.of(runner)))
                    .isSameAs(fatal);
            assertThat(runner.failureCount()).isEqualTo(1);
            assertThat(runner.lastErrorType())
                    .isEqualTo(TestVirtualMachineError.class.getName());
            assertThat(runner.cursor()).isZero();

            runner.close();
            assertThat(runner.closeCompletion()).isCompleted();
        }
    }

    @Test
    void subsystemStopDefersCloseAndLedgerTeardownUntilBlockingDeliveryExits() throws Exception {
        CountDownLatch deliveryEntered = new CountDownLatch(1);
        CountDownLatch releaseDelivery = new CountDownLatch(1);
        CountDownLatch sinkClosed = new CountDownLatch(1);
        AtomicBoolean delivering = new AtomicBoolean();
        AtomicBoolean closeOverlappedDelivery = new AtomicBoolean();
        AtomicInteger closeCalls = new AtomicInteger();
        String sinkId = "blocking-shutdown";
        FinalizedStreamSink sink = new FinalizedStreamSink() {
            @Override public String id() { return sinkId; }

            @Override
            public boolean deliver(AppBlock block) {
                delivering.set(true);
                deliveryEntered.countDown();
                while (releaseDelivery.getCount() != 0) {
                    try {
                        releaseDelivery.await();
                    } catch (InterruptedException ignored) {
                        // Model a non-cooperative third-party client call.
                    }
                }
                delivering.set(false);
                return true;
            }

            @Override
            public void close() {
                closeCalls.incrementAndGet();
                closeOverlappedDelivery.set(delivering.get());
                sinkClosed.countDown();
            }
        };
        FinalizedStreamSinkFactory factory = new FinalizedStreamSinkFactory() {
            @Override public String scheme() { return "blocking"; }
            @Override public List<FinalizedStreamSink> create(
                    String chainId, Map<String, String> config) {
                return List.of(sink);
            }
        };
        PluginProviderRegistry registry = registry(factory);
        Path ledgerPath = tempDir.resolve("blocking-ledger");
        AppChainConfig config = AppChainConfig.builder("blocking-sink-chain")
                .signingKeyHex(KEY_HEX)
                .memberKeysHex(Set.of(PUBLIC_KEY))
                .proposerKeyHex(PUBLIC_KEY)
                .threshold(1)
                .blockIntervalMs(100)
                .pluginSettings(Map.of("sinks.blocking.enabled", "true"))
                .build();
        AppChainSubsystem subsystem = new AppChainSubsystem(
                config, 42, null, null, ledgerPath.toString(), null, registry, LOG);

        try {
            subsystem.start();
            subsystem.submit("t", "one".getBytes(StandardCharsets.UTF_8));
            awaitTrue(() -> subsystem.tipHeight() >= 1, 5_000);
            assertThat(deliveryEntered.await(10, TimeUnit.SECONDS)).isTrue();

            long startedNanos = System.nanoTime();
            subsystem.stop();
            long stopMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);

            assertThat(stopMillis).isLessThan(7_000);
            assertThat(sinkClosed.getCount()).isEqualTo(1);
            assertThat(closeOverlappedDelivery).isFalse();
            assertThatThrownBy(subsystem::start)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("still draining");

            releaseDelivery.countDown();
            assertThat(sinkClosed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(closeCalls).hasValue(1);
            assertThat(closeOverlappedDelivery).isFalse();

            // Reopening proves deferred ledger close has completed. The
            // successful callback returned after shutdown, so its cursor must
            // not have been committed.
            try (AppLedgerStore reopened = reopen(
                    ledgerPath.resolve(config.chainId()), 5_000)) {
                assertThat(reopened.metaLong(SinkRunner.cursorKeyFor(sinkId), -1L))
                        .isEqualTo(0L);
            }
        } finally {
            releaseDelivery.countDown();
            subsystem.stop();
        }
    }

    private static PluginProviderRegistry registry(FinalizedStreamSinkFactory factory) {
        return new PluginProviderRegistry() {
            @Override
            public <P> Optional<P> find(Class<P> providerType, String selector) {
                if (providerType == FinalizedStreamSinkFactory.class
                        && factory.scheme().equals(selector)) {
                    return Optional.of(providerType.cast(factory));
                }
                return Optional.empty();
            }

            @Override
            public <P> List<String> names(Class<P> providerType) {
                return providerType == FinalizedStreamSinkFactory.class
                        ? List.of(factory.scheme()) : List.of();
            }
        };
    }

    private static AppLedgerStore reopen(Path path, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        RuntimeException lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                return new AppLedgerStore(path.toString(), LOG);
            } catch (RuntimeException failure) {
                lastFailure = failure;
                Thread.sleep(25);
            }
        }
        throw new AssertionError("Ledger was not released after sink delivery drained", lastFailure);
    }

    private static void commitOneBlock(AppLedgerStore ledger) {
        byte[] root = new byte[32];
        AppBlock block = new AppBlock(
                AppBlock.BLOCK_VERSION,
                "sink-test",
                1,
                AppBlock.GENESIS_PREV_HASH,
                0,
                new byte[0],
                System.currentTimeMillis(),
                root,
                root,
                List.of(),
                new byte[32],
                FinalityCert.empty());
        try (WriteBatch batch = new WriteBatch()) {
            ledger.commitBlock(block, root, root, batch);
        }
    }

    private static void awaitTrue(Condition condition, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (condition.evaluate()) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Condition was not satisfied within timeout");
    }

    private static byte[] seed(int fill) {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) fill);
        return key;
    }

    @FunctionalInterface
    private interface Condition {
        boolean evaluate() throws Exception;
    }

    private static final class TestVirtualMachineError extends VirtualMachineError { }
}
