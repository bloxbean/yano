package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.events.api.Event;
import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.EventListener;
import com.bloxbean.cardano.yaci.events.api.EventMetadata;
import com.bloxbean.cardano.yaci.events.api.PublishOptions;
import com.bloxbean.cardano.yaci.events.api.SubscriptionHandle;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainInfo;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateReader;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerContext;
import com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerMode;
import com.bloxbean.cardano.yano.api.appchain.sequencer.SequencerModeProvider;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSink;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSinkFactory;
import com.bloxbean.cardano.yano.runtime.plugins.PluginProviderRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

@Timeout(30)
class AppChainSubsystemGenerationLifecycleTest {

    private static final String CHAIN_ID = "generation-lifecycle";
    private static final byte[] SIGNING_KEY = seed(108);
    private static final String PUBLIC_KEY = HexUtil.encodeHexString(
            KeyGenUtil.getPublicKeyFromPrivateKey(SIGNING_KEY));

    @TempDir
    Path tempDir;

    @Test
    void startCallbackRejectsRecursiveLifecycleButAllowsSafeStateSnapshots() {
        ReentrantInitMachine machine = new ReentrantInitMachine();
        AppChainSubsystem subsystem = subsystem(
                tempDir.resolve("reentrant-start"), machine,
                null, PluginProviderRegistry.empty(), Map.of());
        machine.subsystem.set(subsystem);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(5), subsystem::start);

            assertLifecycleReentry(machine.recursiveStart.get(), "start");
            assertLifecycleReentry(machine.recursiveStop.get(), "stop");
            assertThat(machine.workerStatus.get())
                    .containsEntry("running", false)
                    .doesNotContainKey("sequencer");
            assertThat(machine.workerStateRoot.get()).containsOnly((byte) 0);
            assertThat(subsystem.status()).containsEntry("running", true);
        } finally {
            subsystem.stop();
        }
    }

    @Test
    void pluginCloseWorkerRejectsRecursiveLifecycleAndCanInspectStoppedState()
            throws Exception {
        ReentrantCloseSink sink = new ReentrantCloseSink();
        PluginProviderRegistry registry = sinkRegistry(sink);
        AppChainSubsystem subsystem = subsystem(
                tempDir.resolve("reentrant-stop"), new NoOpStateMachine(),
                null, registry, Map.of("sinks.reentrant.enabled", "true"));
        sink.subsystem.set(subsystem);
        try {
            subsystem.start();
            subsystem.stop();

            assertThat(sink.closed.await(5, TimeUnit.SECONDS)).isTrue();
            assertLifecycleReentry(sink.recursiveStart.get(), "start");
            assertLifecycleReentry(sink.recursiveStop.get(), "stop");
            assertThat(sink.closeStatus.get()).containsEntry("running", false);
            assertThat(sink.closeStateRoot.get()).containsOnly((byte) 0);
            assertThat(sink.closeCalls).hasValue(1);
        } finally {
            subsystem.stop();
        }
    }

    @Test
    void concurrentHostStartsSerializeWithoutDuplicateGeneration() throws Exception {
        BlockingInitMachine machine = new BlockingInitMachine(false);
        AppChainSubsystem subsystem = subsystem(
                tempDir.resolve("concurrent-start"), machine,
                null, PluginProviderRegistry.empty(), Map.of());
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = callers.submit(subsystem::start);
            assertThat(machine.firstInitEntered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> second = callers.submit(subsystem::start);

            assertThat(second.isDone()).isFalse();
            machine.releaseFirstInit.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);

            assertThat(machine.initCalls).hasValue(1);
            assertThat(subsystem.status()).containsEntry("running", true);
        } finally {
            machine.releaseFirstInit.countDown();
            subsystem.stop();
            callers.shutdownNow();
        }
    }

    @Test
    void fatalStartStillPublishesTransitionAndWakesConcurrentHostStart() throws Exception {
        BlockingInitMachine machine = new BlockingInitMachine(true);
        AppChainSubsystem subsystem = subsystem(
                tempDir.resolve("fatal-concurrent-start"), machine,
                null, PluginProviderRegistry.empty(), Map.of());
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = callers.submit(subsystem::start);
            assertThat(machine.firstInitEntered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> second = callers.submit(subsystem::start);
            machine.releaseFirstInit.countDown();

            Throwable firstFailure = catchThrowable(() -> first.get(5, TimeUnit.SECONDS));
            assertThat(firstFailure).hasCauseInstanceOf(TestVirtualMachineError.class);
            second.get(5, TimeUnit.SECONDS);

            assertThat(machine.initCalls).hasValue(2);
            assertThat(subsystem.status()).containsEntry("running", true);
        } finally {
            machine.releaseFirstInit.countDown();
            subsystem.stop();
            callers.shutdownNow();
        }
    }

    @Test
    void concurrentHostStopsSerializeWhileResourceCallbackReadsState() throws Exception {
        BlockingCloseEventBus eventBus = new BlockingCloseEventBus();
        AppChainSubsystem subsystem = subsystem(
                tempDir.resolve("concurrent-stop"), new NoOpStateMachine(),
                eventBus, PluginProviderRegistry.empty(), Map.of());
        eventBus.subsystem.set(subsystem);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            subsystem.start();
            Future<?> first = callers.submit(subsystem::stop);
            assertThat(eventBus.closeEntered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> second = callers.submit(subsystem::stop);

            assertThat(second.isDone()).isFalse();
            assertThat(eventBus.closeStatus.get()).containsEntry("running", false);
            assertThat(eventBus.closeStateRoot.get()).containsOnly((byte) 0);
            eventBus.releaseClose.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);

            assertThat(eventBus.closeCalls).hasValue(2);
            assertThat(subsystem.status()).containsEntry("running", false);
        } finally {
            eventBus.releaseClose.countDown();
            subsystem.stop();
            callers.shutdownNow();
        }
    }

    @Test
    void stopSealsLateCallsButDefersLedgerAndProviderCleanupUntilBlockingStatusReturns()
            throws Exception {
        BlockingSequencerMode mode = new BlockingSequencerMode();
        TrackingRegistry registry = new TrackingRegistry(mode);
        Path ledgerBase = tempDir.resolve("ledger");
        AppChainSubsystem subsystem = subsystem(ledgerBase, registry);
        ExecutorService callers = Executors.newCachedThreadPool();
        try {
            subsystem.start();
            assertThat(registry.cleanupSignals).hasSizeGreaterThanOrEqualTo(2);

            mode.blockStatus.set(true);
            Future<Map<String, Object>> admittedStatus = callers.submit(subsystem::status);
            assertThat(mode.statusEntered.await(5, TimeUnit.SECONDS)).isTrue();

            assertTimeoutPreemptively(Duration.ofSeconds(5), subsystem::stop);

            CompletableFuture<Void> generationQuiescence = registry.cleanupSignals.getFirst();
            assertThat(generationQuiescence).isNotDone();
            assertThat(admittedStatus).isNotDone();

            // A late root call is rejected immediately and never re-enters the
            // plugin, while the call admitted before the seal can finish.
            assertThat(subsystem.status()).containsEntry("running", false);
            assertThat(mode.statusCalls).hasValue(1);
            assertThatThrownBy(subsystem::start)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("still draining");

            CompletableFuture<Void> providerClose = CompletableFuture.runAsync(
                    registry::closeProviderAfterContributions, callers);
            assertThat(providerClose).isNotDone();
            assertThat(mode.providerClosed).isFalse();

            // The current RocksDB generation must still own the path while the
            // admitted status call is paused inside plugin code.
            assertThatThrownBy(() -> openAndCloseLedger(ledgerBase.resolve(CHAIN_ID)))
                    .isInstanceOf(RuntimeException.class);

            mode.releaseStatus.countDown();
            Map<String, Object> status = admittedStatus.get(5, TimeUnit.SECONDS);
            // The status snapshot captured running=true before entering the
            // plugin. Its post-plugin ledger reads still complete safely.
            assertThat(status).containsEntry("running", true).containsEntry("tipHeight", 0L);
            generationQuiescence.get(5, TimeUnit.SECONDS);
            providerClose.get(5, TimeUnit.SECONDS);
            assertThat(mode.providerClosed).isTrue();

            // Successful completion of the admitted operation releases the
            // ledger lock; no callback used a closed RocksDB handle.
            openAndCloseLedger(ledgerBase.resolve(CHAIN_ID));
        } finally {
            mode.releaseStatus.countDown();
            subsystem.stop();
            callers.shutdownNow();
        }
    }

    @Test
    void restartCriticalCloseStillRunsAfterExceptionalQuiescenceAndPreservesFailures() {
        AppChainSubsystem subsystem = subsystem(
                tempDir.resolve("exceptional-close"),
                new TrackingRegistry(new BlockingSequencerMode()));
        CompletableFuture<Void> quiescence = new CompletableFuture<>();
        AtomicBoolean closeCalled = new AtomicBoolean();
        IllegalStateException engineFailure = new IllegalStateException("engine cleanup");
        AssertionError closeFailure = new AssertionError("ledger close");

        CompletableFuture<Void> completion = subsystem.closeRestartCriticalResourceAfter(
                quiescence, "test ledger", () -> {
                    closeCalled.set(true);
                    throw closeFailure;
                }, null);
        quiescence.completeExceptionally(engineFailure);

        Throwable observed = catchThrowable(completion::join);
        Throwable terminal = observed instanceof java.util.concurrent.CompletionException
                ? observed.getCause() : observed;
        assertThat(closeCalled).isTrue();
        assertThat(terminal).isSameAs(closeFailure);
        assertThat(closeFailure.getSuppressed()).containsExactly(engineFailure);
    }

    @Test
    void restartCriticalFatalCloseWinsOverContainableQuiescenceError() {
        AppChainSubsystem subsystem = subsystem(
                tempDir.resolve("fatal-exceptional-close"),
                new TrackingRegistry(new BlockingSequencerMode()));
        CompletableFuture<Void> quiescence = new CompletableFuture<>();
        AssertionError quiescenceFailure = new AssertionError("engine assertion");
        TestVirtualMachineError fatalClose = new TestVirtualMachineError();

        CompletableFuture<Void> completion = subsystem.closeRestartCriticalResourceAfter(
                quiescence, "test ledger", () -> {
                    throw fatalClose;
                }, null);
        quiescence.completeExceptionally(quiescenceFailure);

        Throwable observed = catchThrowable(completion::join);
        Throwable terminal = observed instanceof java.util.concurrent.CompletionException
                ? observed.getCause() : observed;
        assertThat(terminal).isSameAs(fatalClose);
        assertThat(fatalClose.getSuppressed()).containsExactly(quiescenceFailure);
    }

    @Test
    void restartCriticalOrdinaryCloseCannotMaskEarlierProcessFatal() {
        AppChainSubsystem subsystem = subsystem(
                tempDir.resolve("fatal-primary-close"),
                new TrackingRegistry(new BlockingSequencerMode()));
        TestVirtualMachineError fatalPrimary = new TestVirtualMachineError();
        IllegalStateException closeFailure = new IllegalStateException("ledger close");

        CompletableFuture<Void> completion = subsystem.closeRestartCriticalResourceAfter(
                CompletableFuture.completedFuture(null),
                "test ledger",
                () -> { throw closeFailure; },
                fatalPrimary);

        Throwable observed = catchThrowable(completion::join);
        Throwable terminal = observed instanceof java.util.concurrent.CompletionException
                ? observed.getCause() : observed;
        assertThat(terminal).isSameAs(fatalPrimary);
        assertThat(fatalPrimary.getSuppressed()).containsExactly(closeFailure);
    }

    @Test
    void hostileQuiescenceCauseInspectionCannotSkipRestartCriticalClose() {
        AppChainSubsystem subsystem = subsystem(
                tempDir.resolve("hostile-quiescence-close"),
                new TrackingRegistry(new BlockingSequencerMode()));
        AtomicBoolean closeCalled = new AtomicBoolean();
        HostileCompletionFailure hostile = new HostileCompletionFailure();

        CompletableFuture<Void> completion = subsystem.closeRestartCriticalResourceAfter(
                CompletableFuture.failedFuture(hostile),
                "test ledger",
                () -> closeCalled.set(true),
                null);

        assertThatThrownBy(completion::join)
                .isInstanceOf(java.util.concurrent.CompletionException.class);
        assertThat(closeCalled).isTrue();
    }

    @Test
    void infinitelyFreshCompletionCauseGraphIsBoundedAndStillClosesResource() {
        AppChainSubsystem subsystem = subsystem(
                tempDir.resolve("fresh-cause-quiescence-close"),
                new TrackingRegistry(new BlockingSequencerMode()));
        AtomicBoolean closeCalled = new AtomicBoolean();

        CompletableFuture<Void> completion = subsystem.closeRestartCriticalResourceAfter(
                CompletableFuture.failedFuture(new FreshCompletionFailure()),
                "test ledger",
                () -> closeCalled.set(true),
                null);

        assertThatThrownBy(completion::join)
                .isInstanceOf(java.util.concurrent.CompletionException.class);
        assertThat(closeCalled).isTrue();
    }

    private static AppChainSubsystem subsystem(
            Path ledgerBase, PluginProviderRegistry registry) {
        AppChainConfig config = AppChainConfig.builder(CHAIN_ID)
                .signingKeyHex(HexUtil.encodeHexString(SIGNING_KEY))
                .memberKeysHex(Set.of(PUBLIC_KEY))
                .proposerKeyHex(PUBLIC_KEY)
                .threshold(1)
                .blockIntervalMs(60_000)
                .pluginSettings(Map.of("sequencer.mode", BlockingSequencerMode.ID))
                .build();
        return new AppChainSubsystem(config, 42, null, null,
                ledgerBase.toString(), null, registry,
                LoggerFactory.getLogger(AppChainSubsystemGenerationLifecycleTest.class));
    }

    private static AppChainSubsystem subsystem(
            Path ledgerBase,
            AppStateMachine stateMachine,
            EventBus eventBus,
            PluginProviderRegistry registry,
            Map<String, String> pluginSettings
    ) {
        AppChainConfig config = AppChainConfig.builder(CHAIN_ID)
                .signingKeyHex(HexUtil.encodeHexString(SIGNING_KEY))
                .memberKeysHex(Set.of(PUBLIC_KEY))
                .proposerKeyHex(PUBLIC_KEY)
                .threshold(1)
                .blockIntervalMs(60_000)
                .pluginSettings(pluginSettings)
                .build();
        return new AppChainSubsystem(config, 42, eventBus, stateMachine,
                ledgerBase.toString(), null, registry,
                LoggerFactory.getLogger(AppChainSubsystemGenerationLifecycleTest.class));
    }

    private static PluginProviderRegistry sinkRegistry(ReentrantCloseSink sink) {
        FinalizedStreamSinkFactory factory = new FinalizedStreamSinkFactory() {
            @Override
            public String scheme() {
                return "reentrant";
            }

            @Override
            public List<FinalizedStreamSink> create(
                    String chainId,
                    Map<String, String> config
            ) {
                return List.of(sink);
            }
        };
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

    private static void assertLifecycleReentry(Throwable failure, String action) {
        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot " + action + " app-chain")
                .hasMessageContaining("callback/lifecycle transition");
    }

    private static void openAndCloseLedger(Path ledgerPath) {
        try (AppLedgerStore ignored = new AppLedgerStore(ledgerPath.toString(),
                LoggerFactory.getLogger("generation-ledger-probe"))) {
            // Acquiring and releasing the RocksDB lock is the assertion.
        }
    }

    private static byte[] seed(int fill) {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) fill);
        return key;
    }

    private static final class TrackingRegistry implements PluginProviderRegistry {
        private final SequencerModeProvider provider;
        private final BlockingSequencerMode mode;
        private final List<CompletableFuture<Void>> cleanupSignals = new CopyOnWriteArrayList<>();

        private TrackingRegistry(BlockingSequencerMode mode) {
            this.mode = mode;
            this.provider = new SequencerModeProvider() {
                @Override
                public String id() {
                    return BlockingSequencerMode.ID;
                }

                @Override
                public SequencerMode create(SequencerContext context) {
                    return mode;
                }
            };
        }

        @Override
        public <P> Optional<P> find(Class<P> providerType, String selector) {
            if (providerType == SequencerModeProvider.class
                    && BlockingSequencerMode.ID.equals(selector)) {
                return Optional.of(providerType.cast(provider));
            }
            return Optional.empty();
        }

        @Override
        public <P> List<String> names(Class<P> providerType) {
            return providerType == SequencerModeProvider.class
                    ? List.of(BlockingSequencerMode.ID) : List.of();
        }

        @Override
        public void registerContributionCleanup(CompletableFuture<Void> completion) {
            cleanupSignals.add(completion);
        }

        private void closeProviderAfterContributions() {
            CompletableFuture.allOf(cleanupSignals.toArray(CompletableFuture[]::new)).join();
            mode.providerClosed.set(true);
        }
    }

    private static final class BlockingSequencerMode implements SequencerMode {
        private static final String ID = "blocking-status";

        private final AtomicBoolean blockStatus = new AtomicBoolean();
        private final CountDownLatch statusEntered = new CountDownLatch(1);
        private final CountDownLatch releaseStatus = new CountDownLatch(1);
        private final AtomicInteger statusCalls = new AtomicInteger();
        private final AtomicBoolean providerClosed = new AtomicBoolean();

        @Override
        public String id() {
            return ID;
        }

        @Override
        public void init(SequencerContext context) {
        }

        @Override
        public boolean shouldProposeNow(long height) {
            return false;
        }

        @Override
        public ProposalEligibility checkProposal(byte[] proposerKey, long height) {
            return ProposalEligibility.ACCEPT;
        }

        @Override
        public Map<String, Object> status() {
            statusCalls.incrementAndGet();
            if (blockStatus.get()) {
                statusEntered.countDown();
                awaitUninterruptibly(releaseStatus);
            }
            if (providerClosed.get()) {
                throw new IllegalStateException("provider closed while product callback was active");
            }
            return Map.of("currentProposer", PUBLIC_KEY);
        }

        private static void awaitUninterruptibly(CountDownLatch latch) {
            boolean interrupted = false;
            while (true) {
                try {
                    latch.await();
                    break;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static class NoOpStateMachine implements AppStateMachine {
        @Override
        public String id() {
            return "lifecycle-noop";
        }

        @Override
        public void apply(AppBlock block, AppStateWriter writer) {
        }
    }

    private static final class ReentrantInitMachine extends NoOpStateMachine {
        private final AtomicReference<AppChainSubsystem> subsystem = new AtomicReference<>();
        private final AtomicReference<Throwable> recursiveStart = new AtomicReference<>();
        private final AtomicReference<Throwable> recursiveStop = new AtomicReference<>();
        private final AtomicReference<Map<String, Object>> workerStatus = new AtomicReference<>();
        private final AtomicReference<byte[]> workerStateRoot = new AtomicReference<>();

        @Override
        public void init(AppStateReader state, AppChainInfo info) {
            AppChainSubsystem target = subsystem.get();
            Thread reader = Thread.ofPlatform().daemon(true).start(() -> {
                workerStatus.set(target.status());
                workerStateRoot.set(target.stateRoot());
            });
            try {
                reader.join(5_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted waiting for startup status reader", interrupted);
            }
            if (reader.isAlive()) {
                throw new AssertionError("Startup status reader blocked on lifecycle transition");
            }
            recursiveStart.set(catchThrowable(target::start));
            recursiveStop.set(catchThrowable(target::stop));
        }
    }

    private static final class BlockingInitMachine extends NoOpStateMachine {
        private final boolean failFirst;
        private final AtomicInteger initCalls = new AtomicInteger();
        private final CountDownLatch firstInitEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFirstInit = new CountDownLatch(1);

        private BlockingInitMachine(boolean failFirst) {
            this.failFirst = failFirst;
        }

        @Override
        public void init(AppStateReader state, AppChainInfo info) {
            if (initCalls.incrementAndGet() != 1) {
                return;
            }
            firstInitEntered.countDown();
            BlockingSequencerMode.awaitUninterruptibly(releaseFirstInit);
            if (failFirst) {
                throw new TestVirtualMachineError();
            }
        }
    }

    private static final class ReentrantCloseSink implements FinalizedStreamSink {
        private final AtomicReference<AppChainSubsystem> subsystem = new AtomicReference<>();
        private final AtomicReference<Throwable> recursiveStart = new AtomicReference<>();
        private final AtomicReference<Throwable> recursiveStop = new AtomicReference<>();
        private final AtomicReference<Map<String, Object>> closeStatus = new AtomicReference<>();
        private final AtomicReference<byte[]> closeStateRoot = new AtomicReference<>();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public String id() {
            return "reentrant-close";
        }

        @Override
        public boolean deliver(AppBlock block) {
            return true;
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            AppChainSubsystem target = subsystem.get();
            closeStatus.set(target.status());
            closeStateRoot.set(target.stateRoot());
            recursiveStart.set(catchThrowable(target::start));
            recursiveStop.set(catchThrowable(target::stop));
            closed.countDown();
        }
    }

    private static final class BlockingCloseEventBus implements EventBus {
        private final AtomicReference<AppChainSubsystem> subsystem = new AtomicReference<>();
        private final AtomicInteger subscriptionSequence = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final CountDownLatch closeEntered = new CountDownLatch(1);
        private final CountDownLatch releaseClose = new CountDownLatch(1);
        private final AtomicReference<Map<String, Object>> closeStatus = new AtomicReference<>();
        private final AtomicReference<byte[]> closeStateRoot = new AtomicReference<>();

        @Override
        public <E extends Event> SubscriptionHandle subscribe(
                Class<E> eventType,
                EventListener<E> listener,
                SubscriptionOptions options
        ) {
            int index = subscriptionSequence.getAndIncrement();
            return new SubscriptionHandle() {
                private final AtomicBoolean active = new AtomicBoolean(true);

                @Override
                public void close() {
                    if (!active.compareAndSet(true, false)) {
                        return;
                    }
                    closeCalls.incrementAndGet();
                    if (index == 0) {
                        AppChainSubsystem target = subsystem.get();
                        closeStatus.set(target.status());
                        closeStateRoot.set(target.stateRoot());
                        closeEntered.countDown();
                        BlockingSequencerMode.awaitUninterruptibly(releaseClose);
                    }
                }

                @Override
                public boolean isActive() {
                    return active.get();
                }
            };
        }

        @Override
        public <E extends Event> void publish(
                E event,
                EventMetadata metadata,
                PublishOptions options
        ) {
        }

        @Override
        public void close() {
        }
    }

    private static final class TestVirtualMachineError extends VirtualMachineError { }

    private static final class HostileCompletionFailure
            extends java.util.concurrent.CompletionException {
        @Override
        public synchronized Throwable getCause() {
            throw new AssertionError("hostile cause inspection");
        }
    }

    private static final class FreshCompletionFailure
            extends java.util.concurrent.CompletionException {
        @Override
        public synchronized Throwable getCause() {
            return new FreshCompletionFailure();
        }
    }
}
