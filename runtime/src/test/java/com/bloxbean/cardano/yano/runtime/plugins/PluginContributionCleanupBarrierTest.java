package com.bloxbean.cardano.yano.runtime.plugins;

import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutorFactory;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecution;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSink;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSinkFactory;
import com.bloxbean.cardano.yano.catalog.ContributionKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(10)
class PluginContributionCleanupBarrierTest {

    @Test
    void cleanupAwaitRejectsUnsealedAdmissionWithoutFreezingRegistration() {
        CatalogPluginProviderRegistry registry = new CatalogPluginProviderRegistry(
                List.of(), List.of(), List.of());
        CompletableFuture<Void> first = CompletableFuture.completedFuture(null);
        CompletableFuture<Void> second = CompletableFuture.completedFuture(null);
        registry.registerContributionCleanup(first);

        assertThatThrownBy(registry::awaitContributionCleanup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Seal plugin contribution callbacks");

        // The rejected out-of-order call must be side-effect free.
        registry.registerContributionCleanup(second);
        registry.sealContributionCallbacks();
        registry.awaitContributionCleanup();
        registry.close();
    }

    @Test
    void blockedEffectCanTerminallyCloseAfterNormalSealBeforeProviderClose()
            throws Exception {
        List<String> closeOrder = new CopyOnWriteArrayList<>();
        BlockingEffectExecutor raw = new BlockingEffectExecutor(closeOrder);
        CloseableEffectFactory provider = new CloseableEffectFactory(
                new AtomicInteger(), List.of(raw), closeOrder);
        CatalogPluginProviderRegistry registry = registry(
                ContributionKind.EFFECT_EXECUTOR, "example", provider);
        AppEffectExecutor exposed = registry.require(
                AppEffectExecutorFactory.class, "example")
                .create("chain", Map.of()).getFirst();
        // A terminal close may legitimately call another typed facade. The
        // nested call inherits the admitted cleanup root instead of being
        // rejected by the already-sealed ordinary gate.
        raw.onClose = () -> assertThat(exposed.supports("nested-cleanup")).isTrue();

        verifyDeferredTerminalClose(registry, raw.entered, raw.release,
                () -> exposed.execute(null, null), exposed::id, exposed::close,
                closeOrder);
    }

    @Test
    void blockedSinkCanTerminallyCloseAfterNormalSealBeforeProviderClose()
            throws Exception {
        List<String> closeOrder = new CopyOnWriteArrayList<>();
        BlockingSink raw = new BlockingSink(closeOrder);
        CloseableSinkFactory provider = new CloseableSinkFactory(raw, closeOrder);
        CatalogPluginProviderRegistry registry = registry(
                ContributionKind.FINALIZED_SINK, "blocked-sink", provider);
        FinalizedStreamSink exposed = registry.require(
                FinalizedStreamSinkFactory.class, "blocked-sink")
                .create("chain", Map.of()).getFirst();

        verifyDeferredTerminalClose(registry, raw.entered, raw.release,
                () -> exposed.deliver(null), exposed::id, exposed::close,
                closeOrder);
    }

    @Test
    void registryCloseWaitsForReturnedProductCleanupBeforeProviderClose() throws Exception {
        AtomicInteger providerCloses = new AtomicInteger();
        CloseableEffectFactory provider = new CloseableEffectFactory(providerCloses);
        CatalogPluginProviderRegistry.Entry entry =
                new CatalogPluginProviderRegistry.Entry(
                        "com.example.effects",
                        ContributionKind.EFFECT_EXECUTOR,
                        "example",
                        provider.getClass().getName(),
                        null,
                        () -> provider);
        CatalogPluginProviderRegistry registry = new CatalogPluginProviderRegistry(
                List.of(entry), List.of("com.example.effects"), List.of(),
                Thread.currentThread().getContextClassLoader());
        registry.find(AppEffectExecutorFactory.class, "example").orElseThrow();

        SignallingFuture productCleanup = new SignallingFuture();
        registry.registerContributionCleanup(productCleanup);
        CountDownLatch closeStarted = new CountDownLatch(1);
        Thread closeThread = new Thread(() -> {
            closeStarted.countDown();
            registry.close();
        }, "plugin-registry-close-test");
        closeThread.start();

        assertThat(closeStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(productCleanup.awaitObserved.await(1, TimeUnit.SECONDS)).isTrue();
        closeThread.join(100);
        assertThat(closeThread.isAlive()).isTrue();
        assertThat(providerCloses).hasValue(0);

        CompletableFuture<Void> lateCleanup = new CompletableFuture<>();
        assertThatThrownBy(() -> registry.registerContributionCleanup(lateCleanup))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closing or closed");

        productCleanup.complete(null);
        closeThread.join(2_000);
        assertThat(closeThread.isAlive()).isFalse();
        assertThat(providerCloses).hasValue(1);
    }

    @Test
    void registryCloseSealsLateCallbacksAndWaitsForAdmittedStateMachineCallback()
            throws Exception {
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        AtomicInteger providerCloses = new AtomicInteger();
        AppStateMachine machine = new AppStateMachine() {
            @Override
            public String id() {
                return "blocked";
            }

            @Override
            public AdmissionResult validate(
                    com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage message
            ) {
                callbackStarted.countDown();
                try {
                    if (!releaseCallback.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("callback release timed out");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("callback interrupted", e);
                }
                return AdmissionResult.accept();
            }

            @Override
            public void apply(AppBlock block, AppStateWriter writer) {
            }
        };
        CloseableStateMachineProvider provider =
                new CloseableStateMachineProvider(machine, providerCloses);
        CatalogPluginProviderRegistry registry = registry(
                ContributionKind.APP_STATE_MACHINE, "blocked", provider);
        AppStateMachine exposed = registry.require(
                AppStateMachineProvider.class, "blocked").create();

        Thread callbackThread = new Thread(() -> exposed.validate(null),
                "blocked-plugin-callback");
        callbackThread.start();
        assertThat(callbackStarted.await(1, TimeUnit.SECONDS)).isTrue();

        Thread closeThread = new Thread(registry::close,
                "blocked-plugin-registry-close");
        closeThread.start();
        assertEventuallySealed(exposed);
        closeThread.join(100);
        assertThat(closeThread.isAlive()).isTrue();
        assertThat(providerCloses).hasValue(0);

        releaseCallback.countDown();
        callbackThread.join(2_000);
        closeThread.join(2_000);
        assertThat(callbackThread.isAlive()).isFalse();
        assertThat(closeThread.isAlive()).isFalse();
        assertThat(providerCloses).hasValue(1);
    }

    @Test
    void registryCloseWaitsForAdmittedCommittedQueryCallback() throws Exception {
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        AtomicInteger providerCloses = new AtomicInteger();
        AppStateMachine machine = new AppStateMachine() {
            @Override public String id() { return "blocked-query"; }
            @Override public void apply(AppBlock block, AppStateWriter writer) { }
            @Override public byte[] query(
                    String path, byte[] params, AppQueryContext context
            ) {
                callbackStarted.countDown();
                await(releaseCallback);
                return new byte[]{1};
            }
        };
        CloseableStateMachineProvider provider =
                new CloseableStateMachineProvider(machine, providerCloses);
        CatalogPluginProviderRegistry registry = registry(
                ContributionKind.APP_STATE_MACHINE, "blocked-query", provider);
        AppStateMachine exposed = registry.require(
                AppStateMachineProvider.class, "blocked-query").create();

        Thread callbackThread = new Thread(
                () -> exposed.query("blocked", new byte[0], null),
                "blocked-plugin-query-callback");
        callbackThread.start();
        assertThat(callbackStarted.await(1, TimeUnit.SECONDS)).isTrue();

        Thread closeThread = new Thread(registry::close,
                "blocked-query-registry-close");
        closeThread.start();
        assertEventuallySealed(exposed);
        closeThread.join(100);
        assertThat(closeThread.isAlive()).isTrue();
        assertThat(providerCloses).hasValue(0);

        releaseCallback.countDown();
        callbackThread.join(2_000);
        closeThread.join(2_000);
        assertThat(callbackThread.isAlive()).isFalse();
        assertThat(closeThread.isAlive()).isFalse();
        assertThat(providerCloses).hasValue(1);
    }

    @Test
    void contributionCallbackCannotInitiateItsOwnTeardown() {
        AtomicReference<CatalogPluginProviderRegistry> registryRef = new AtomicReference<>();
        AtomicReference<Throwable> rejection = new AtomicReference<>();
        AppStateMachine machine = new AppStateMachine() {
            @Override public String id() { return "teardown-preflight"; }
            @Override public AdmissionResult validate(
                    com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage message) {
                rejection.set(org.assertj.core.api.Assertions.catchThrowable(() ->
                        registryRef.get().requireContributionTeardownAllowed(
                                "stop the runtime")));
                return AdmissionResult.accept();
            }
            @Override public void apply(AppBlock block, AppStateWriter writer) { }
        };
        CatalogPluginProviderRegistry registry = registry(
                ContributionKind.APP_STATE_MACHINE, "teardown-preflight",
                new CloseableStateMachineProvider(machine, new AtomicInteger()));
        registryRef.set(registry);
        AppStateMachine exposed = registry.require(
                AppStateMachineProvider.class, "teardown-preflight").create();

        exposed.validate(null);

        assertThat(rejection.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("plugin contribution callback");
        registry.close();
    }

    @Test
    void retainedProductsWorkAfterResumeAndCompletedCleanupSignalsArePruned() {
        AtomicInteger providerCloses = new AtomicInteger();
        AppStateMachine machine = new AppStateMachine() {
            @Override public String id() { return "retained"; }
            @Override public void apply(AppBlock block, AppStateWriter writer) { }
        };
        CloseableStateMachineProvider provider =
                new CloseableStateMachineProvider(machine, providerCloses);
        CatalogPluginProviderRegistry registry = registry(
                ContributionKind.APP_STATE_MACHINE, "retained", provider);
        AppStateMachine exposed = registry.require(
                AppStateMachineProvider.class, "retained").create();

        for (int cycle = 0; cycle < 100; cycle++) {
            registry.registerContributionCleanup(CompletableFuture.completedFuture(null));
            registry.sealContributionCallbacks();
            registry.awaitContributionCallbacks();
            registry.awaitContributionCleanup();
            assertThatThrownBy(exposed::id)
                    .isInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("Plugin callback admission is sealed")
                    .hasMessageNotContaining("admission is sealed");
            assertThatThrownBy(() -> registry.registerContributionCleanup(
                    CompletableFuture.completedFuture(null)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cleanup registration is sealed");
            registry.resumeContributionCallbacks();
            assertThat(exposed.id()).isEqualTo("retained");
            assertThat(registry.contributionCleanupCountForTesting()).isZero();
        }

        registry.close();
        assertThat(providerCloses).hasValue(1);
    }

    private static CatalogPluginProviderRegistry registry(
            ContributionKind kind,
            String selector,
            Object provider
    ) {
        CatalogPluginProviderRegistry.Entry entry =
                new CatalogPluginProviderRegistry.Entry(
                        "com.example." + selector,
                        kind,
                        selector,
                        provider.getClass().getName(),
                        null,
                        () -> provider);
        return new CatalogPluginProviderRegistry(
                List.of(entry), List.of("com.example." + selector), List.of(),
                Thread.currentThread().getContextClassLoader());
    }

    private static void verifyDeferredTerminalClose(
            CatalogPluginProviderRegistry registry,
            CountDownLatch callbackEntered,
            CountDownLatch releaseCallback,
            ThrowingAction normalCallback,
            Runnable rejectedNormalCallback,
            Runnable terminalClose,
            List<String> closeOrder
    ) throws Exception {
        SignallingFuture cleanup = new SignallingFuture();
        registry.registerContributionCleanup(cleanup);
        CountDownLatch callbackReturned = new CountDownLatch(1);
        Thread callbackThread = new Thread(() -> {
            try {
                normalCallback.run();
            } catch (Exception e) {
                throw new AssertionError(e);
            } finally {
                callbackReturned.countDown();
            }
        }, "blocked-catalog-product-callback");
        Thread cleanupThread = new Thread(() -> {
            await(callbackReturned);
            try {
                terminalClose.run();
            } finally {
                cleanup.complete(null);
            }
        }, "deferred-catalog-product-close");
        Thread registryClose = new Thread(registry::close,
                "catalog-close-with-deferred-product");

        callbackThread.start();
        assertThat(callbackEntered.await(1, TimeUnit.SECONDS)).isTrue();
        cleanupThread.start();
        registryClose.start();
        assertEventuallyRejected(rejectedNormalCallback);
        assertThat(closeOrder).isEmpty();

        releaseCallback.countDown();
        assertThat(cleanup.awaitObserved.await(1, TimeUnit.SECONDS)).isTrue();
        callbackThread.join(2_000);
        cleanupThread.join(2_000);
        registryClose.join(2_000);
        assertThat(callbackThread.isAlive()).isFalse();
        assertThat(cleanupThread.isAlive()).isFalse();
        assertThat(registryClose.isAlive()).isFalse();
        assertThat(closeOrder).containsExactly("product", "provider");
        assertThatThrownBy(terminalClose::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cleanup callback admission is sealed");
    }

    private static void assertEventuallyRejected(Runnable callback)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            try {
                callback.run();
            } catch (RuntimeException expected) {
                Throwable root = expected;
                while (root.getCause() != null) {
                    root = root.getCause();
                }
                if (root instanceof IllegalStateException
                        && root.getMessage() != null
                        && root.getMessage().contains("admission is sealed")) {
                    return;
                }
                throw expected;
            }
            Thread.sleep(1);
        }
        throw new AssertionError("plugin callback admission was not sealed");
    }

    private static void await(CountDownLatch latch) {
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

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    private static void assertEventuallySealed(AppStateMachine machine)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            try {
                machine.id();
            } catch (IllegalStateException expected) {
                assertThat(expected)
                        .hasRootCauseMessage("Plugin callback admission is sealed")
                        .hasMessageNotContaining("admission is sealed");
                return;
            }
            Thread.sleep(1);
        }
        throw new AssertionError("plugin callback admission was not sealed");
    }

    private static final class SignallingFuture extends CompletableFuture<Void> {
        private final CountDownLatch awaitObserved = new CountDownLatch(1);

        @Override
        public <U> CompletableFuture<U> handle(
                BiFunction<? super Void, Throwable, ? extends U> function
        ) {
            awaitObserved.countDown();
            return super.handle(function);
        }
    }

    private static final class CloseableStateMachineProvider
            implements AppStateMachineProvider, AutoCloseable {
        private final AppStateMachine machine;
        private final AtomicInteger closes;

        private CloseableStateMachineProvider(
                AppStateMachine machine,
                AtomicInteger closes
        ) {
            this.machine = machine;
            this.closes = closes;
        }

        @Override public String id() { return machine.id(); }
        @Override public AppStateMachine create() { return machine; }
        @Override public void close() { closes.incrementAndGet(); }
    }

    private static final class CloseableEffectFactory
            implements AppEffectExecutorFactory, AutoCloseable {
        private final AtomicInteger closes;
        private final List<AppEffectExecutor> products;
        private final List<String> closeOrder;

        private CloseableEffectFactory(AtomicInteger closes) {
            this(closes, List.of(), List.of());
        }

        private CloseableEffectFactory(
                AtomicInteger closes,
                List<AppEffectExecutor> products,
                List<String> closeOrder
        ) {
            this.closes = closes;
            this.products = products;
            this.closeOrder = closeOrder;
        }

        @Override
        public String scheme() {
            return "example";
        }

        @Override
        public List<AppEffectExecutor> create(String chainId, Map<String, String> config) {
            return products;
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            if (!closeOrder.isEmpty()) {
                closeOrder.add("provider");
            }
        }
    }

    private static final class BlockingEffectExecutor implements AppEffectExecutor {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final List<String> closeOrder;
        private Runnable onClose = () -> { };

        private BlockingEffectExecutor(List<String> closeOrder) {
            this.closeOrder = closeOrder;
        }

        @Override public String id() { return "blocked-effect"; }
        @Override public boolean supports(String effectType) { return true; }
        @Override public EffectExecution execute(
                EffectExecutionContext context,
                PendingEffect effect
        ) {
            entered.countDown();
            await(release);
            return EffectExecution.confirmed(new byte[0]);
        }
        @Override public void close() {
            onClose.run();
            closeOrder.add("product");
        }
    }

    private static final class CloseableSinkFactory
            implements FinalizedStreamSinkFactory, AutoCloseable {
        private final FinalizedStreamSink sink;
        private final List<String> closeOrder;

        private CloseableSinkFactory(FinalizedStreamSink sink, List<String> closeOrder) {
            this.sink = sink;
            this.closeOrder = closeOrder;
        }

        @Override public String scheme() { return "blocked-sink"; }
        @Override public List<FinalizedStreamSink> create(
                String chainId,
                Map<String, String> config
        ) { return List.of(sink); }
        @Override public void close() { closeOrder.add("provider"); }
    }

    private static final class BlockingSink implements FinalizedStreamSink {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final List<String> closeOrder;

        private BlockingSink(List<String> closeOrder) {
            this.closeOrder = closeOrder;
        }

        @Override public String id() { return "blocked-sink"; }
        @Override public boolean deliver(AppBlock block) {
            entered.countDown();
            await(release);
            return true;
        }
        @Override public void close() { closeOrder.add("product"); }
    }
}
