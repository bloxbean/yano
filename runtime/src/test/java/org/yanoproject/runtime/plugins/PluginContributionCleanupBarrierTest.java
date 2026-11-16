package org.yanoproject.runtime.plugins;

import org.yanoproject.runtime.plugins.fixture.FixtureProduct;
import org.yanoproject.runtime.plugins.fixture.FixtureProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
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
                TestPluginKinds.KINDS, List.of(), List.of(), List.of());
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
    void blockedProductCanTerminallyCloseAfterNormalSealBeforeProviderClose()
            throws Exception {
        List<String> closeOrder = new CopyOnWriteArrayList<>();
        BlockingProduct raw = new BlockingProduct(closeOrder);
        CloseableProvider provider = new CloseableProvider(
                "blocked", new AtomicInteger(), raw, closeOrder);
        CatalogPluginProviderRegistry registry = registry("blocked", provider);
        FixtureProduct exposed = registry.require(FixtureProvider.class, "blocked")
                .createAll().getFirst();
        // A terminal close may legitimately call another typed facade. The
        // nested call inherits the admitted cleanup root instead of being
        // rejected by the already-sealed ordinary gate.
        raw.onClose = () -> assertThat(callUnchecked(exposed, "nested-cleanup"))
                .isEqualTo("nested-cleanup");

        verifyDeferredTerminalClose(registry, raw.entered, raw.release,
                () -> exposed.call("block"), exposed::id, exposed::close,
                closeOrder);
    }

    @Test
    void registryCloseWaitsForReturnedProductCleanupBeforeProviderClose() throws Exception {
        AtomicInteger providerCloses = new AtomicInteger();
        CloseableProvider provider = new CloseableProvider(
                "example", providerCloses, null, List.of());
        CatalogPluginProviderRegistry registry = registry("example", provider);
        registry.find(FixtureProvider.class, "example").orElseThrow();

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
    void registryCloseSealsLateCallbacksAndWaitsForAdmittedProductCallback()
            throws Exception {
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        AtomicInteger providerCloses = new AtomicInteger();
        FixtureProduct product = new FixtureProduct() {
            @Override public String call(String input) {
                callbackStarted.countDown();
                await(releaseCallback);
                return input;
            }
        };
        CatalogPluginProviderRegistry registry = registry("blocked",
                new CloseableProvider("blocked", providerCloses, product, List.of()));
        FixtureProduct exposed = registry.require(FixtureProvider.class, "blocked").create();

        Thread callbackThread = new Thread(() -> callUnchecked(exposed, "blocked"),
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
    void contributionCallbackCannotInitiateItsOwnTeardown() {
        AtomicReference<CatalogPluginProviderRegistry> registryRef = new AtomicReference<>();
        AtomicReference<Throwable> rejection = new AtomicReference<>();
        FixtureProduct product = new FixtureProduct() {
            @Override public String call(String input) {
                rejection.set(org.assertj.core.api.Assertions.catchThrowable(() ->
                        registryRef.get().requireContributionTeardownAllowed(
                                "stop the runtime")));
                return input;
            }
        };
        CatalogPluginProviderRegistry registry = registry("teardown-preflight",
                new CloseableProvider("teardown-preflight", new AtomicInteger(), product, List.of()));
        registryRef.set(registry);
        FixtureProduct exposed = registry.require(FixtureProvider.class, "teardown-preflight").create();

        callUnchecked(exposed, "preflight");

        assertThat(rejection.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("plugin contribution callback");
        registry.close();
    }

    @Test
    void retainedProductsWorkAfterResumeAndCompletedCleanupSignalsArePruned() {
        AtomicInteger providerCloses = new AtomicInteger();
        FixtureProduct product = new FixtureProduct() {
            @Override public String id() { return "retained"; }
        };
        CatalogPluginProviderRegistry registry = registry("retained",
                new CloseableProvider("retained", providerCloses, product, List.of()));
        FixtureProduct exposed = registry.require(FixtureProvider.class, "retained").create();

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

    private static CatalogPluginProviderRegistry registry(String selector, Object provider) {
        CatalogPluginProviderRegistry.Entry entry =
                new CatalogPluginProviderRegistry.Entry(
                        "com.example." + selector,
                        TestPluginKinds.FIXTURE,
                        selector,
                        provider.getClass().getName(),
                        null,
                        () -> provider);
        return new CatalogPluginProviderRegistry(TestPluginKinds.KINDS,
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

    private static String callUnchecked(FixtureProduct product, String input) {
        try {
            return product.call(input);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void assertEventuallySealed(FixtureProduct machine)
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

    private static final class CloseableProvider implements FixtureProvider, AutoCloseable {
        private final String id;
        private final AtomicInteger closes;
        private final FixtureProduct product;
        private final List<String> closeOrder;

        private CloseableProvider(
                String id,
                AtomicInteger closes,
                FixtureProduct product,
                List<String> closeOrder
        ) {
            this.id = id;
            this.closes = closes;
            this.product = product;
            this.closeOrder = closeOrder;
        }

        @Override public String id() { return id; }
        @Override public FixtureProduct create() { return product; }
        @Override public List<FixtureProduct> createAll() { return List.of(product); }
        @Override public void close() {
            closes.incrementAndGet();
            if (!closeOrder.isEmpty()) {
                closeOrder.add("provider");
            }
        }
    }

    private static final class BlockingProduct implements FixtureProduct {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final List<String> closeOrder;
        private Runnable onClose = () -> { };

        private BlockingProduct(List<String> closeOrder) {
            this.closeOrder = closeOrder;
        }

        @Override public String id() { return "blocked-product"; }
        @Override public String call(String input) {
            if ("block".equals(input)) {
                entered.countDown();
                await(release);
            }
            return input;
        }
        @Override public void close() {
            onClose.run();
            closeOrder.add("product");
        }
    }
}
