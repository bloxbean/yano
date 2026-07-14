package com.bloxbean.cardano.yano.runtime.plugins;

import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSink;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSinkFactory;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
import com.bloxbean.cardano.yano.api.plugin.PluginActivationException;
import com.bloxbean.cardano.yano.catalog.ContributionKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

@Timeout(15)
class CatalogPluginProviderRegistryConcurrencyTest {

    @Test
    void supplierMayWaitForWorkerReadingRegistryNames() {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        AtomicReference<CatalogPluginProviderRegistry> registryRef = new AtomicReference<>();
        FixedFactory primary = new FixedFactory("primary");
        FixedFactory secondary = new FixedFactory("secondary");
        CatalogPluginProviderRegistry.Entry primaryEntry = entry(
                "primary-bundle", "primary", () -> {
                    List<String> names = await(worker.submit(() -> registryRef.get()
                            .names(FinalizedStreamSinkFactory.class)));
                    if (!names.equals(List.of("primary", "secondary"))) {
                        throw new IllegalStateException("Unexpected provider names " + names);
                    }
                    return primary;
                });
        CatalogPluginProviderRegistry.Entry secondaryEntry = entry(
                "secondary-bundle", "secondary", () -> secondary);
        CatalogPluginProviderRegistry registry = new CatalogPluginProviderRegistry(
                List.of(primaryEntry, secondaryEntry),
                List.of("primary-bundle", "secondary-bundle"), List.of());
        registryRef.set(registry);

        try {
            assertThat(registry.find(FinalizedStreamSinkFactory.class, "primary"))
                    .isPresent();
        } finally {
            registry.close();
            worker.shutdownNow();
        }
    }

    @Test
    void selectorMayWaitForWorkerResolvingAnotherProvider() {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        AtomicReference<CatalogPluginProviderRegistry> registryRef = new AtomicReference<>();
        FixedFactory secondary = new FixedFactory("secondary");
        FinalizedStreamSinkFactory primary = new FinalizedStreamSinkFactory() {
            @Override
            public String scheme() {
                Optional<FinalizedStreamSinkFactory> resolved = await(worker.submit(() ->
                        registryRef.get().find(
                                FinalizedStreamSinkFactory.class, "secondary")));
                if (resolved.isEmpty()) {
                    throw new IllegalStateException("Secondary provider was not resolved");
                }
                return "primary";
            }

            @Override
            public List<FinalizedStreamSink> create(
                    String chainId,
                    Map<String, String> config
            ) {
                return List.of();
            }
        };
        CatalogPluginProviderRegistry registry = new CatalogPluginProviderRegistry(
                List.of(
                        entry("primary-bundle", "primary", () -> primary),
                        entry("secondary-bundle", "secondary", () -> secondary)),
                List.of("primary-bundle", "secondary-bundle"), List.of());
        registryRef.set(registry);

        try {
            assertThat(registry.find(FinalizedStreamSinkFactory.class, "primary"))
                    .isPresent();
            assertThat(registry.find(FinalizedStreamSinkFactory.class, "secondary"))
                    .isPresent();
        } finally {
            registry.close();
            worker.shutdownNow();
        }
    }

    @Test
    void failedCandidateCloseMayWaitForWorkerReadingRegistry() {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        AtomicReference<CatalogPluginProviderRegistry> registryRef = new AtomicReference<>();
        AtomicBoolean closeObservedNames = new AtomicBoolean();
        AtomicInteger closeCalls = new AtomicInteger();
        class MismatchedFactory implements FinalizedStreamSinkFactory, AutoCloseable {
            @Override
            public String scheme() {
                return "actual";
            }

            @Override
            public List<FinalizedStreamSink> create(
                    String chainId,
                    Map<String, String> config
            ) {
                return List.of();
            }

            @Override
            public void close() {
                closeCalls.incrementAndGet();
                closeObservedNames.set(await(worker.submit(() -> registryRef.get()
                        .names(FinalizedStreamSinkFactory.class)))
                        .equals(List.of("declared")));
            }
        }
        MismatchedFactory provider = new MismatchedFactory();
        CatalogPluginProviderRegistry registry = new CatalogPluginProviderRegistry(
                List.of(entry("mismatch-bundle", "declared", () -> provider)),
                List.of("mismatch-bundle"), List.of());
        registryRef.set(registry);

        try {
            assertThatThrownBy(() -> registry.find(
                    FinalizedStreamSinkFactory.class, "declared"))
                    .isInstanceOf(PluginActivationException.class)
                    .hasRootCauseMessage("Provider '" + FinalizedStreamSinkFactory.class.getName()
                            + "' selector mismatch: manifest='declared', provider='actual'");
            assertThat(closeObservedNames).isTrue();
            assertThat(closeCalls).hasValue(1);
        } finally {
            registry.close();
            worker.shutdownNow();
        }
        assertThat(closeCalls).hasValue(1);
    }

    @Test
    void concurrentRetrievalOfSameEntryConstructsExactlyOnce() {
        ExecutorService callers = Executors.newFixedThreadPool(2);
        CountDownLatch supplierEntered = new CountDownLatch(1);
        CountDownLatch releaseSupplier = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicInteger constructions = new AtomicInteger();
        FixedFactory provider = new FixedFactory("shared");
        CatalogPluginProviderRegistry registry = new CatalogPluginProviderRegistry(
                List.of(entry("shared-bundle", "shared", () -> {
                    constructions.incrementAndGet();
                    supplierEntered.countDown();
                    await(releaseSupplier);
                    return provider;
                })), List.of("shared-bundle"), List.of());

        try {
            Future<FinalizedStreamSinkFactory> first = callers.submit(() -> registry
                    .find(FinalizedStreamSinkFactory.class, "shared").orElseThrow());
            await(supplierEntered);
            Future<FinalizedStreamSinkFactory> second = callers.submit(() -> {
                secondStarted.countDown();
                return registry.find(
                        FinalizedStreamSinkFactory.class, "shared").orElseThrow();
            });
            await(secondStarted);
            releaseSupplier.countDown();

            assertThat(await(second)).isSameAs(await(first));
            assertThat(constructions).hasValue(1);
        } finally {
            releaseSupplier.countDown();
            registry.close();
            callers.shutdownNow();
        }
    }

    @Test
    void sneakyCheckedSelectorFailureClosesOnceAndWakesEveryWaiterWithCachedSafeFailure() {
        ExecutorService callers = Executors.newFixedThreadPool(2);
        CountDownLatch selectorEntered = new CountDownLatch(1);
        CountDownLatch releaseSelector = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicInteger closeCalls = new AtomicInteger();
        Exception checkedFailure = new Exception("checked-provider-secret");
        class CheckedFailureFactory implements FinalizedStreamSinkFactory, AutoCloseable {
            @Override
            public String scheme() {
                selectorEntered.countDown();
                await(releaseSelector);
                return sneakyThrow(checkedFailure);
            }

            @Override
            public List<FinalizedStreamSink> create(
                    String chainId,
                    Map<String, String> config
            ) {
                return List.of();
            }

            @Override
            public void close() {
                closeCalls.incrementAndGet();
            }
        }
        CheckedFailureFactory provider = new CheckedFailureFactory();
        CatalogPluginProviderRegistry registry = new CatalogPluginProviderRegistry(
                List.of(entry("checked-bundle", "checked", () -> provider)),
                List.of("checked-bundle"), List.of());

        try {
            Future<Throwable> first = callers.submit(() -> captureFailure(() -> registry.find(
                    FinalizedStreamSinkFactory.class, "checked")));
            await(selectorEntered);
            Future<Throwable> second = callers.submit(() -> {
                secondStarted.countDown();
                return captureFailure(() -> registry.find(
                        FinalizedStreamSinkFactory.class, "checked"));
            });
            await(secondStarted);
            releaseSelector.countDown();

            Throwable firstFailure = await(first);
            Throwable secondFailure = await(second);
            assertThat(firstFailure)
                    .isInstanceOf(PluginActivationException.class)
                    .hasMessageNotContaining("checked-provider-secret")
                    .hasCause(checkedFailure);
            assertThat(secondFailure).isSameAs(firstFailure);
            assertThat(closeCalls).hasValue(1);

            Throwable laterFailure = captureFailure(() -> registry.find(
                    FinalizedStreamSinkFactory.class, "checked"));
            assertThat(laterFailure).isSameAs(firstFailure);
            assertThat(closeCalls).hasValue(1);
        } finally {
            releaseSelector.countDown();
            registry.close();
            callers.shutdownNow();
        }
        assertThat(closeCalls).hasValue(1);
    }

    @Test
    void processFatalSelectorFailureStillEscapesAfterExactOnceCandidateClose() {
        AtomicInteger closeCalls = new AtomicInteger();
        TestVirtualMachineError fatal = new TestVirtualMachineError("fatal selector");
        class FatalFactory implements FinalizedStreamSinkFactory, AutoCloseable {
            @Override
            public String scheme() {
                throw fatal;
            }

            @Override
            public List<FinalizedStreamSink> create(
                    String chainId,
                    Map<String, String> config
            ) {
                return List.of();
            }

            @Override
            public void close() {
                closeCalls.incrementAndGet();
            }
        }
        FatalFactory provider = new FatalFactory();
        CatalogPluginProviderRegistry registry = new CatalogPluginProviderRegistry(
                List.of(entry("fatal-bundle", "fatal", () -> provider)),
                List.of("fatal-bundle"), List.of());

        assertThatThrownBy(() -> registry.find(
                FinalizedStreamSinkFactory.class, "fatal")).isSameAs(fatal);
        assertThat(closeCalls).hasValue(1);
        registry.close();
        assertThat(closeCalls).hasValue(1);
    }

    @Test
    void nestedProcessFatalSelectorResetsActivationAndAllowsFreshRetry() {
        AtomicInteger creations = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        TestVirtualMachineError fatal = new TestVirtualMachineError("nested fatal selector");
        class RetryFactory implements FinalizedStreamSinkFactory, AutoCloseable {
            private final int generation;

            private RetryFactory(int generation) {
                this.generation = generation;
            }

            @Override
            public String scheme() {
                if (generation == 1) {
                    throw new IllegalStateException("wrapper", fatal);
                }
                return "fatal";
            }

            @Override
            public List<FinalizedStreamSink> create(
                    String chainId,
                    Map<String, String> config
            ) {
                return List.of();
            }

            @Override
            public void close() {
                closes.incrementAndGet();
            }
        }
        CatalogPluginProviderRegistry registry = new CatalogPluginProviderRegistry(
                List.of(entry("fatal-bundle", "fatal", () -> {
                    int generation = creations.incrementAndGet();
                    return new RetryFactory(generation);
                })), List.of("fatal-bundle"), List.of());

        assertThatThrownBy(() -> registry.find(
                FinalizedStreamSinkFactory.class, "fatal")).isSameAs(fatal);
        assertThat(closes).hasValue(1);
        assertThat(registry.find(FinalizedStreamSinkFactory.class, "fatal")).isPresent();
        assertThat(creations).hasValue(2);

        registry.close();
        assertThat(closes).hasValue(2);
    }

    @Test
    void recursiveSameThreadRetrievalFailsFastAndCachesFailure() {
        AtomicReference<CatalogPluginProviderRegistry> registryRef = new AtomicReference<>();
        AtomicInteger closeCalls = new AtomicInteger();
        class RecursiveFactory implements FinalizedStreamSinkFactory, AutoCloseable {
            @Override
            public String scheme() {
                registryRef.get().find(FinalizedStreamSinkFactory.class, "recursive");
                return "recursive";
            }

            @Override
            public List<FinalizedStreamSink> create(
                    String chainId,
                    Map<String, String> config
            ) {
                return List.of();
            }

            @Override
            public void close() {
                closeCalls.incrementAndGet();
            }
        }
        RecursiveFactory provider = new RecursiveFactory();
        CatalogPluginProviderRegistry registry = new CatalogPluginProviderRegistry(
                List.of(entry("recursive-bundle", "recursive", () -> provider)),
                List.of("recursive-bundle"), List.of());
        registryRef.set(registry);

        PluginActivationException first = assertThrows(
                PluginActivationException.class,
                () -> registry.find(FinalizedStreamSinkFactory.class, "recursive"));
        PluginActivationException second = assertThrows(
                PluginActivationException.class,
                () -> registry.find(FinalizedStreamSinkFactory.class, "recursive"));

        assertThat(first).hasRootCauseMessage(
                "Recursive activation of provider '"
                        + FinalizedStreamSinkFactory.class.getName() + "'");
        assertThat(second).isSameAs(first);
        assertThat(closeCalls).hasValue(1);
        registry.close();
        assertThat(closeCalls).hasValue(1);
    }

    @Test
    void closeWaitsForInFlightActivationBeforeClosingProvider() {
        ExecutorService callers = Executors.newFixedThreadPool(2);
        CountDownLatch supplierEntered = new CountDownLatch(1);
        CountDownLatch releaseSupplier = new CountDownLatch(1);
        AtomicInteger closeCalls = new AtomicInteger();
        ClosingFactory provider = new ClosingFactory("blocking", closeCalls);
        CatalogPluginProviderRegistry registry = new CatalogPluginProviderRegistry(
                List.of(entry("blocking-bundle", "blocking", () -> {
                    supplierEntered.countDown();
                    await(releaseSupplier);
                    return provider;
                })), List.of("blocking-bundle"), List.of());

        Future<FinalizedStreamSinkFactory> activation = callers.submit(() -> registry
                .find(FinalizedStreamSinkFactory.class, "blocking").orElseThrow());
        await(supplierEntered);
        Future<?> close = callers.submit(registry::close);
        awaitClosing(registry);

        try {
            assertThat(close.isDone()).isFalse();
            assertThat(closeCalls).hasValue(0);
            releaseSupplier.countDown();
            assertThat(await(activation)).isNotNull();
            await(close);
            assertThat(closeCalls).hasValue(1);
            assertThatThrownBy(() -> registry.names(FinalizedStreamSinkFactory.class))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closing or closed");
        } finally {
            releaseSupplier.countDown();
            registry.close();
            callers.shutdownNow();
        }
        assertThat(closeCalls).hasValue(1);
    }

    @Test
    void sharedRawProviderIdentityCannotPublishThroughASecondEntryWhileOwnerFails() {
        ExecutorService callers = Executors.newSingleThreadExecutor();
        CountDownLatch ownerSelectorEntered = new CountDownLatch(1);
        CountDownLatch releaseOwnerSelector = new CountDownLatch(1);
        AtomicInteger closeCalls = new AtomicInteger();
        class SharedProvider implements AppStateMachineProvider,
                FinalizedStreamSinkFactory, AutoCloseable {
            @Override
            public String id() {
                return "machine";
            }

            @Override
            public AppStateMachine create() {
                throw new AssertionError("unused");
            }

            @Override
            public String scheme() {
                ownerSelectorEntered.countDown();
                await(releaseOwnerSelector);
                return "actual-sink";
            }

            @Override
            public List<FinalizedStreamSink> create(
                    String chainId,
                    Map<String, String> config
            ) {
                return List.of();
            }

            @Override
            public void close() {
                closeCalls.incrementAndGet();
            }
        }
        SharedProvider provider = new SharedProvider();
        CatalogPluginProviderRegistry.Entry sinkEntry = new CatalogPluginProviderRegistry.Entry(
                "sink-owner", ContributionKind.FINALIZED_SINK, "declared-sink",
                provider.getClass().getName(), null, () -> provider);
        CatalogPluginProviderRegistry.Entry machineEntry =
                new CatalogPluginProviderRegistry.Entry(
                        "machine-borrower", ContributionKind.APP_STATE_MACHINE, "machine",
                        provider.getClass().getName(), null, () -> provider);
        CatalogPluginProviderRegistry registry = new CatalogPluginProviderRegistry(
                List.of(sinkEntry, machineEntry),
                List.of("sink-owner", "machine-borrower"), List.of());

        Future<?> owner = callers.submit(() -> registry.find(
                FinalizedStreamSinkFactory.class, "declared-sink"));
        await(ownerSelectorEntered);
        try {
            assertThatThrownBy(() -> registry.find(
                    AppStateMachineProvider.class, "machine"))
                    .isInstanceOf(PluginActivationException.class)
                    .hasRootCauseMessage("Plugin provider instance is already owned by another "
                            + "catalog entry or as a factory product");
            assertThat(closeCalls).hasValue(0);

            releaseOwnerSelector.countDown();
            assertThatThrownBy(() -> await(owner))
                    .hasRootCauseMessage("Provider '" + provider.getClass().getName()
                            + "' selector mismatch: manifest='declared-sink', "
                            + "provider='actual-sink'");
            assertThat(closeCalls).hasValue(1);
        } finally {
            releaseOwnerSelector.countDown();
            registry.close();
            callers.shutdownNow();
        }
        assertThat(closeCalls).hasValue(1);
    }

    private static CatalogPluginProviderRegistry.Entry entry(
            String bundleId,
            String selector,
            java.util.function.Supplier<?> supplier
    ) {
        return new CatalogPluginProviderRegistry.Entry(
                bundleId, ContributionKind.FINALIZED_SINK, selector,
                FinalizedStreamSinkFactory.class.getName(), null, supplier);
    }

    private static void awaitClosing(CatalogPluginProviderRegistry registry) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            try {
                registry.names(FinalizedStreamSinkFactory.class);
            } catch (IllegalStateException expected) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("Registry did not enter closing state");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for test latch", e);
        }
    }

    private static <T> T await(Future<T> future) {
        try {
            return future.get(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for test future", e);
        } catch (Exception e) {
            throw new AssertionError("Test future failed", e);
        }
    }

    private static Throwable captureFailure(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T, X extends Throwable> T sneakyThrow(Throwable failure) throws X {
        throw (X) failure;
    }

    private static class FixedFactory implements FinalizedStreamSinkFactory {
        private final String scheme;

        private FixedFactory(String scheme) {
            this.scheme = scheme;
        }

        @Override
        public String scheme() {
            return scheme;
        }

        @Override
        public List<FinalizedStreamSink> create(
                String chainId,
                Map<String, String> config
        ) {
            return List.of();
        }
    }

    private static final class ClosingFactory extends FixedFactory implements AutoCloseable {
        private final AtomicInteger closeCalls;

        private ClosingFactory(String scheme, AtomicInteger closeCalls) {
            super(scheme);
            this.closeCalls = closeCalls;
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }
    }

    private static final class TestVirtualMachineError extends VirtualMachineError {
        private TestVirtualMachineError(String message) {
            super(message);
        }
    }
}
