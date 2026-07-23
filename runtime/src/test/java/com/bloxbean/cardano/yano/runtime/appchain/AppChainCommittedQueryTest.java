package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.vds.core.api.NodeStore;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.appchain.AppQueryResult;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateProofSnapshot;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.runtime.plugins.PluginProviderRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@Timeout(60)
class AppChainCommittedQueryTest {

    private static final byte[] SIGNING_KEY = filled(109);
    private static final String PUBLIC_KEY = HexUtil.encodeHexString(
            KeyGenUtil.getPublicKeyFromPrivateKey(SIGNING_KEY));

    @TempDir
    Path tempDir;

    private final List<AppChainSubsystem> nodes = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (AppChainSubsystem node : nodes) {
            node.stop();
        }
    }

    @Test
    void contextualHookResultIdentityBoundsAndCopiesAreEnforced() {
        LegacyQueryMachine legacy = new LegacyQueryMachine();
        assertThat(legacy.query("echo", new byte[0])).containsExactly(4, 5, 6);
        AppChainSubsystem legacyNode = create("legacy-query", legacy,
                PluginProviderRegistry.empty(), 60_000);
        legacyNode.start();
        assertCode(AppQueryException.Code.UNSUPPORTED,
                () -> legacyNode.query("echo", new byte[0]));

        ContextualQueryMachine machine = new ContextualQueryMachine();
        AppChainSubsystem node = create("contextual-query", machine,
                PluginProviderRegistry.empty(), 60_000);

        assertCode(AppQueryException.Code.UNAVAILABLE,
                () -> node.query("echo", new byte[0]));
        node.start();

        byte[] request = new byte[]{1, 2, 3};
        AppQueryResult result = node.query("echo", request);

        assertThat(request).containsExactly(1, 2, 3);
        assertThat(machine.received.get()).isNotSameAs(request);
        assertThat(result.chainId()).isEqualTo("contextual-query");
        assertThat(result.stateMachineId()).isEqualTo("contextual-machine");
        assertThat(result.committedHeight()).isZero();
        assertThat(result.stateRoot()).containsOnly((byte) 0);
        assertThat(result.payload()).containsExactly(4, 5, 6);

        machine.response[0] = 99;
        byte[] exposedRoot = result.stateRoot();
        byte[] exposedPayload = result.payload();
        exposedRoot[0] = 99;
        exposedPayload[1] = 99;
        assertThat(result.stateRoot()).containsOnly((byte) 0);
        assertThat(result.payload()).containsExactly(4, 5, 6);

        assertCode(AppQueryException.Code.INVALID_REQUEST,
                () -> node.query(null, new byte[0]));
        assertCode(AppQueryException.Code.INVALID_REQUEST,
                () -> node.query(" ", new byte[0]));
        assertCode(AppQueryException.Code.INVALID_REQUEST,
                () -> node.query("/echo", new byte[0]));
        assertCode(AppQueryException.Code.INVALID_REQUEST,
                () -> node.query("echo/", new byte[0]));
        assertCode(AppQueryException.Code.INVALID_REQUEST,
                () -> node.query("echo//detail", new byte[0]));
        assertCode(AppQueryException.Code.INVALID_REQUEST,
                () -> node.query("echo/../detail", new byte[0]));
        assertCode(AppQueryException.Code.INVALID_REQUEST,
                () -> node.query("echo%2Fdetail", new byte[0]));
        assertCode(AppQueryException.Code.INVALID_REQUEST,
                () -> node.query("_echo", new byte[0]));
        assertCode(AppQueryException.Code.REQUEST_TOO_LARGE,
                () -> node.query("x".repeat(257), new byte[0]));
        assertCode(AppQueryException.Code.REQUEST_TOO_LARGE,
                () -> node.query("echo", new byte[64 * 1024 + 1]));
        AppQueryException unsupported = assertFailure(
                AppQueryException.Code.UNSUPPORTED,
                () -> node.query("typed-unsupported", new byte[0]));
        assertThat(unsupported).hasMessage("App-chain query path is unsupported");
        assertThat(unsupported.getMessage()).doesNotContain("plugin secret");
        AppQueryException invalid = assertFailure(
                AppQueryException.Code.INVALID_REQUEST,
                () -> node.query("typed-invalid", new byte[0]));
        assertThat(invalid).hasMessage("App-chain query parameters are invalid");
        assertThat(invalid.getMessage()).doesNotContain("plugin secret");
        assertCode(AppQueryException.Code.FAILED,
                () -> node.query("unsupported", new byte[0]));
        assertCode(AppQueryException.Code.FAILED,
                () -> node.query("host-owned-code", new byte[0]));
        assertCode(AppQueryException.Code.RESULT_TOO_LARGE,
                () -> node.query("large", new byte[0]));
        assertCode(AppQueryException.Code.FAILED,
                () -> node.query("null", new byte[0]));
        assertCode(AppQueryException.Code.FAILED,
                () -> node.query("failure", new byte[0]));
        assertThatThrownBy(() -> node.query("typed-fatal", new byte[0]))
                .isSameAs(machine.fatal);

        node.stop();
        assertCode(AppQueryException.Code.UNAVAILABLE,
                () -> node.query("echo", new byte[0]));
    }

    @Test
    void queryUsesOneRootFixedSnapshotWhileLaterBlockCommitsConcurrently()
            throws Exception {
        SnapshotQueryMachine machine = new SnapshotQueryMachine();
        AppChainSubsystem node = create(
                "root-fixed-query", machine, PluginProviderRegistry.empty(), 75);
        node.start();

        node.submit("kv", "color=blue".getBytes(StandardCharsets.UTF_8));
        awaitTrue("first state block", () -> node.tipHeight() >= 1);
        byte[] firstRoot = node.stateRoot();

        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<AppQueryResult> pending = caller.submit(
                    () -> node.query("color", new byte[]{7}));
            assertThat(machine.queryEntered.await(5, TimeUnit.SECONDS)).isTrue();

            node.submit("kv", "color=green".getBytes(StandardCharsets.UTF_8));
            awaitTrue("second state block while query callback is paused",
                    () -> node.tipHeight() >= 2);
            machine.releaseQuery.countDown();

            AppQueryResult result = pending.get(5, TimeUnit.SECONDS);
            assertThat(result.committedHeight()).isEqualTo(1);
            assertThat(result.stateRoot()).containsExactly(firstRoot);
            assertThat(node.block(1).orElseThrow().stateRoot()).containsExactly(firstRoot);
            assertThat(new String(result.payload(), StandardCharsets.UTF_8))
                    .isEqualTo("blue|blue");
            assertThat(machine.contextHeight).isEqualTo(1);
            assertThat(machine.contextDefensiveCopies).isTrue();
            assertThat(machine.queryThread).startsWith("app-chain-query-");
            assertThat(machine.queryTccl).isSameAs(AppChainSubsystem.class.getClassLoader());
            assertThat(machine.applyThreads)
                    .allMatch(name -> name.startsWith("app-chain-engine-"));

            AppQueryContext expired = machine.retainedContext.get();
            assertCode(AppQueryException.Code.UNAVAILABLE, expired::committedHeight);
            assertCode(AppQueryException.Code.UNAVAILABLE, expired::stateRoot);
            assertCode(AppQueryException.Code.UNAVAILABLE,
                    () -> expired.get("color".getBytes(StandardCharsets.UTF_8)));
        } finally {
            machine.releaseQuery.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    void proofSnapshotsBindValueProofRootAndHeightAcrossLaterCommits()
            throws Exception {
        SnapshotQueryMachine machine = new SnapshotQueryMachine();
        AppChainSubsystem node = create(
                "proof-snapshot", machine, PluginProviderRegistry.empty(), 75);
        byte[] key = "color".getBytes(StandardCharsets.UTF_8);
        byte[] missingKey = "missing".getBytes(StandardCharsets.UTF_8);

        assertThat(node.stateProofSnapshot(key)).isEmpty();
        node.start();
        assertThat(node.stateProofSnapshot(key)).isEmpty();

        node.submit("kv", "color=blue".getBytes(StandardCharsets.UTF_8));
        awaitTrue("first proof state block", () -> node.tipHeight() >= 1);

        AppStateProofSnapshot first = node.stateProofSnapshot(key).orElseThrow();
        assertThat(first.key()).containsExactly(key);
        assertThat(new String(first.value(), StandardCharsets.UTF_8)).isEqualTo("blue");
        assertThat(first.stateRoot()).containsExactly(
                node.block(first.committedHeight()).orElseThrow().stateRoot());
        assertThat(verifies(first, true)).isTrue();

        AppStateProofSnapshot exclusion = node.stateProofSnapshot(missingKey).orElseThrow();
        assertThat(exclusion.key()).containsExactly(missingKey);
        assertThat(exclusion.value()).isNull();
        assertThat(exclusion.committedHeight()).isEqualTo(first.committedHeight());
        assertThat(exclusion.stateRoot()).containsExactly(first.stateRoot());
        assertThat(verifies(exclusion, false)).isTrue();

        node.submit("kv", "color=green".getBytes(StandardCharsets.UTF_8));
        awaitTrue("second proof state block", () -> node.tipHeight() >= 2);
        AppStateProofSnapshot second = node.stateProofSnapshot(key).orElseThrow();

        assertThat(second.committedHeight()).isGreaterThan(first.committedHeight());
        assertThat(new String(second.value(), StandardCharsets.UTF_8)).isEqualTo("green");
        assertThat(second.stateRoot()).containsExactly(
                node.block(second.committedHeight()).orElseThrow().stateRoot());
        assertThat(verifies(second, true)).isTrue();
        assertThat(verifies(first, true)).isTrue();

        byte[] exposedValue = first.value();
        byte[] exposedWire = first.proofWire();
        byte[] exposedRoot = first.stateRoot();
        exposedValue[0] ^= 1;
        exposedWire[0] ^= 1;
        exposedRoot[0] ^= 1;
        assertThat(new String(first.value(), StandardCharsets.UTF_8)).isEqualTo("blue");
        assertThat(verifies(first, true)).isTrue();

        node.stop();
        assertThat(node.stateProofSnapshot(key)).isEmpty();
    }

    @Test
    void timedOutCallbackRetainsGenerationAndContextUntilItActuallyExits()
            throws Exception {
        BlockingQueryMachine machine = new BlockingQueryMachine();
        TrackingRegistry registry = new TrackingRegistry();
        AppChainSubsystem node = create("timeout-query", machine, registry, 60_000);
        node.start();

        long startedAt = System.nanoTime();
        assertCode(AppQueryException.Code.TIMEOUT,
                () -> node.query("block", new byte[0]));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt))
                .isLessThan(5_000);
        assertThat(machine.entered.getCount()).isZero();
        assertThat(machine.exited.getCount()).isOne();

        node.stop();
        assertThat(registry.cleanupSignals).isNotEmpty();
        CompletableFuture<Void> generationLifetime = registry.cleanupSignals.getFirst();
        assertThat(generationLifetime).isNotDone();
        assertCode(AppQueryException.Code.UNAVAILABLE,
                () -> node.query("block", new byte[0]));
        assertThat(catchThrowableOfType(node::start, IllegalStateException.class))
                .hasMessageContaining("still draining");

        // The caller has timed out, but the callback still owns its context and
        // generation until plugin code returns.
        assertThat(machine.retainedContext.get().committedHeight()).isZero();
        machine.release.countDown();
        assertThat(machine.exited.await(5, TimeUnit.SECONDS)).isTrue();
        generationLifetime.get(5, TimeUnit.SECONDS);

        AppQueryContext expired = machine.retainedContext.get();
        assertCode(AppQueryException.Code.UNAVAILABLE, expired::committedHeight);
        assertCode(AppQueryException.Code.UNAVAILABLE, expired::stateRoot);
        awaitRestart(node);
    }

    @Test
    void seventeenthQueuedQueryIsRejectedBusyWithoutBlockingConsensusLane()
            throws Exception {
        QueueQueryMachine machine = new QueueQueryMachine();
        AppChainSubsystem node = create(
                "busy-query", machine, PluginProviderRegistry.empty(), 60_000);
        node.start();

        ExecutorService callers = Executors.newFixedThreadPool(20);
        try {
            Future<AppQueryResult> active = callers.submit(
                    () -> node.query("block", new byte[0]));
            assertThat(machine.entered.await(5, TimeUnit.SECONDS)).isTrue();

            List<Future<AppQueryException.Code>> queued = new ArrayList<>();
            for (int i = 0; i < 17; i++) {
                queued.add(callers.submit(() -> {
                    try {
                        node.query("fast", new byte[0]);
                        return null;
                    } catch (AppQueryException e) {
                        return e.code();
                    }
                }));
            }
            awaitTrue("one query rejected by the full queue", () -> queued.stream()
                    .filter(Future::isDone)
                    .anyMatch(future -> completedCode(future) == AppQueryException.Code.BUSY));

            machine.release.countDown();
            assertThat(active.get(5, TimeUnit.SECONDS).payload()).containsExactly(1);
            List<AppQueryException.Code> outcomes = new ArrayList<>();
            for (Future<AppQueryException.Code> future : queued) {
                outcomes.add(future.get(5, TimeUnit.SECONDS));
            }
            assertThat(outcomes).containsOnlyOnce(AppQueryException.Code.BUSY);
            assertThat(outcomes.stream().filter(code -> code == null)).hasSize(16);
        } finally {
            machine.release.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void queuedRequestThatTimesOutNeverInvokesPluginAfterWorkerUnblocks()
            throws Exception {
        CancelQueuedMachine machine = new CancelQueuedMachine();
        AppChainSubsystem node = create(
                "cancel-queued-query", machine, PluginProviderRegistry.empty(), 60_000);
        node.start();

        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<AppQueryException.Code> active = caller.submit(() -> {
                try {
                    node.query("block", new byte[0]);
                    return null;
                } catch (AppQueryException e) {
                    return e.code();
                }
            });
            assertThat(machine.entered.await(5, TimeUnit.SECONDS)).isTrue();

            assertCode(AppQueryException.Code.TIMEOUT,
                    () -> node.query("must-not-run", new byte[0]));
            assertThat(machine.queuedCalls).hasValue(0);

            assertThat(active.get(5, TimeUnit.SECONDS))
                    .isEqualTo(AppQueryException.Code.TIMEOUT);
            machine.release.countDown();
            Thread.sleep(100);
            assertThat(machine.queuedCalls).hasValue(0);
        } finally {
            machine.release.countDown();
            caller.shutdownNow();
        }
    }

    private AppChainSubsystem create(
            String chainId,
            AppStateMachine machine,
            PluginProviderRegistry registry,
            long blockIntervalMs
    ) {
        AppChainConfig config = AppChainConfig.builder(chainId)
                .signingKeyHex(HexUtil.encodeHexString(SIGNING_KEY))
                .memberKeysHex(Set.of(PUBLIC_KEY))
                .proposerKeyHex(PUBLIC_KEY)
                .threshold(1)
                .blockIntervalMs(blockIntervalMs)
                .build();
        AppChainSubsystem node = new AppChainSubsystem(
                config, 42, null, machine, tempDir.resolve(chainId).toString(),
                null, registry, LoggerFactory.getLogger(AppChainCommittedQueryTest.class));
        nodes.add(node);
        return node;
    }

    private static void awaitRestart(AppChainSubsystem node) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        IllegalStateException lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                node.start();
                return;
            } catch (IllegalStateException e) {
                lastFailure = e;
                Thread.sleep(10);
            }
        }
        throw new AssertionError("App-chain did not restart after query callback exited", lastFailure);
    }

    private static void assertCode(AppQueryException.Code code, ThrowingAction action) {
        assertFailure(code, action);
    }

    private static AppQueryException assertFailure(
            AppQueryException.Code code,
            ThrowingAction action
    ) {
        AppQueryException failure = catchThrowableOfType(action::run, AppQueryException.class);
        assertThat(failure).isNotNull();
        assertThat(failure.code()).isEqualTo(code);
        return failure;
    }

    private static AppQueryException.Code completedCode(
            Future<AppQueryException.Code> future
    ) {
        try {
            return future.get(1, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    private static void awaitTrue(String description, BooleanSupplier condition)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Timed out waiting for " + description);
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static boolean verifies(AppStateProofSnapshot snapshot, boolean inclusion) {
        MpfTrie verifier = new MpfTrie(new NodeStore() {
            @Override
            public byte[] get(byte[] hash) {
                return null;
            }

            @Override
            public void put(byte[] hash, byte[] nodeBytes) {
            }

            @Override
            public void delete(byte[] hash) {
            }
        });
        return verifier.verifyProofWire(
                snapshot.stateRoot(), snapshot.key(), snapshot.value(), inclusion,
                snapshot.proofWire());
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    private static class LegacyQueryMachine implements AppStateMachine {
        protected final byte[] response = new byte[]{4, 5, 6};
        protected final AtomicReference<byte[]> received = new AtomicReference<>();

        @Override
        public String id() {
            return "legacy-machine";
        }

        @Override
        public void apply(AppBlock block, AppStateWriter writer) {
        }

        @Override
        public byte[] query(String path, byte[] params) {
            received.set(params);
            return switch (path) {
                case "echo" -> {
                    if (params.length != 0) {
                        params[0] = 99;
                    }
                    yield response;
                }
                case "large" -> new byte[1024 * 1024 + 1];
                case "null" -> null;
                case "unsupported" -> throw new UnsupportedOperationException("not here");
                case "failure" -> throw new IllegalStateException("plugin secret");
                default -> new byte[0];
            };
        }
    }

    private static final class ContextualQueryMachine extends LegacyQueryMachine {
        private final TestVirtualMachineError fatal = new TestVirtualMachineError();

        @Override
        public String id() {
            return "contextual-machine";
        }

        @Override
        public byte[] query(String path, byte[] params, AppQueryContext context) {
            if (path.equals("typed-fatal")) {
                throw new AppQueryException(AppQueryException.Code.UNSUPPORTED,
                        "typed wrapper must not hide process-fatal cause", fatal);
            }
            if (path.equals("typed-unsupported")) {
                throw new AppQueryException(AppQueryException.Code.UNSUPPORTED,
                        "plugin secret unsupported detail");
            }
            if (path.equals("typed-invalid")) {
                throw new AppQueryException(AppQueryException.Code.INVALID_REQUEST,
                        "plugin secret invalid detail");
            }
            if (path.equals("host-owned-code")) {
                throw new AppQueryException(AppQueryException.Code.UNAVAILABLE,
                        "plugin secret unavailable detail");
            }
            return super.query(path, params);
        }
    }

    private static final class TestVirtualMachineError extends VirtualMachineError {
    }

    private static final class SnapshotQueryMachine implements AppStateMachine {
        private final CountDownLatch queryEntered = new CountDownLatch(1);
        private final CountDownLatch releaseQuery = new CountDownLatch(1);
        private final AtomicReference<AppQueryContext> retainedContext = new AtomicReference<>();
        private final List<String> applyThreads = new CopyOnWriteArrayList<>();
        private volatile long contextHeight;
        private volatile boolean contextDefensiveCopies;
        private volatile String queryThread;
        private volatile ClassLoader queryTccl;

        @Override
        public String id() {
            return "snapshot-machine";
        }

        @Override
        public void apply(AppBlock block, AppStateWriter writer) {
            applyThreads.add(Thread.currentThread().getName());
            for (AppMessage message : block.messages()) {
                String body = new String(message.getBody(), StandardCharsets.UTF_8);
                int separator = body.indexOf('=');
                writer.put(body.substring(0, separator).getBytes(StandardCharsets.UTF_8),
                        body.substring(separator + 1).getBytes(StandardCharsets.UTF_8));
            }
        }

        @Override
        public byte[] query(String path, byte[] params, AppQueryContext context) {
            queryThread = Thread.currentThread().getName();
            queryTccl = Thread.currentThread().getContextClassLoader();
            retainedContext.set(context);
            contextHeight = context.committedHeight();
            byte[] firstRoot = context.stateRoot();
            firstRoot[0] ^= 1;
            byte[] secondRoot = context.stateRoot();
            byte[] firstValue = context.get(path.getBytes(StandardCharsets.UTF_8)).orElseThrow();
            String before = new String(firstValue, StandardCharsets.UTF_8);
            firstValue[0] ^= 1;
            byte[] secondValue = context.get(path.getBytes(StandardCharsets.UTF_8)).orElseThrow();
            contextDefensiveCopies = !Arrays.equals(firstRoot, secondRoot)
                    && new String(secondValue, StandardCharsets.UTF_8).equals(before);
            params[0] = 99;
            queryEntered.countDown();
            awaitUninterruptibly(releaseQuery);
            String after = new String(context.get(path.getBytes(StandardCharsets.UTF_8))
                    .orElseThrow(), StandardCharsets.UTF_8);
            return (before + "|" + after).getBytes(StandardCharsets.UTF_8);
        }
    }

    private static final class BlockingQueryMachine implements AppStateMachine {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch exited = new CountDownLatch(1);
        private final AtomicReference<AppQueryContext> retainedContext = new AtomicReference<>();

        @Override
        public String id() {
            return "blocking-machine";
        }

        @Override
        public void apply(AppBlock block, AppStateWriter writer) {
        }

        @Override
        public byte[] query(String path, byte[] params, AppQueryContext context) {
            retainedContext.set(context);
            entered.countDown();
            awaitUninterruptibly(release);
            exited.countDown();
            return new byte[]{1};
        }
    }

    private static final class QueueQueryMachine implements AppStateMachine {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public String id() {
            return "queue-machine";
        }

        @Override
        public void apply(AppBlock block, AppStateWriter writer) {
        }

        @Override
        public byte[] query(String path, byte[] params, AppQueryContext context) {
            if (path.equals("block")) {
                entered.countDown();
                awaitUninterruptibly(release);
            }
            return new byte[]{1};
        }
    }

    private static final class CancelQueuedMachine implements AppStateMachine {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger queuedCalls = new AtomicInteger();

        @Override public String id() { return "cancel-queued-machine"; }
        @Override public void apply(AppBlock block, AppStateWriter writer) { }

        @Override
        public byte[] query(String path, byte[] params, AppQueryContext context) {
            if (path.equals("block")) {
                entered.countDown();
                awaitUninterruptibly(release);
            } else if (path.equals("must-not-run")) {
                queuedCalls.incrementAndGet();
            }
            return new byte[]{1};
        }
    }

    private static final class TrackingRegistry implements PluginProviderRegistry {
        private final List<CompletableFuture<Void>> cleanupSignals = new CopyOnWriteArrayList<>();

        @Override
        public <P> java.util.Optional<P> find(Class<P> providerType, String selector) {
            return java.util.Optional.empty();
        }

        @Override
        public <P> List<String> names(Class<P> providerType) {
            return List.of();
        }

        @Override
        public void registerContributionCleanup(CompletableFuture<Void> completion) {
            cleanupSignals.add(completion);
        }
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
