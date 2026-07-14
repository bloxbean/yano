package com.bloxbean.cardano.yano.runtime.plugins;

import com.bloxbean.cardano.yano.api.appchain.AppChainGateway;
import com.bloxbean.cardano.yano.api.appchain.AppChainGateways;
import com.bloxbean.cardano.yano.api.appchain.AppQueryResult;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApi;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiAccess;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiContext;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiException;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiMediaType;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiProvider;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRequest;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiResponse;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRoute;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainHttpMethod;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainApiRegistryTest {

    @Test
    void publishesImmutableRoutesUsesLiteralPrecedenceAndConcealsInternalDispatch() {
        AtomicReference<DomainApiRequest> handled = new AtomicReference<>();
        AtomicInteger closes = new AtomicInteger();
        DomainApi api = api(List.of(
                route("parameter", "items/{id}", DomainApiAccess.READ),
                route("literal", "items/special", DomainApiAccess.PRIVILEGED),
                route("cross-literal-first", "a/{value}", DomainApiAccess.READ),
                route("cross-variable-first", "{name}/b", DomainApiAccess.READ),
                route("internal", "internal", DomainApiAccess.INTERNAL)), request -> {
            handled.set(request);
            return text(request.routeId());
        }, closes);
        RecordingProviders providers = new RecordingProviders(
                provider("com.example.routes", () -> api));

        try (DomainApiRegistry registry = registry(providers, Duration.ofSeconds(1))) {
            registry.resume();

            assertThat(registry.routes()).hasSize(5);
            assertThatThrownBy(() -> registry.routes().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThat(registry.access("com.example.routes", DomainHttpMethod.GET,
                    "items/special")).contains(DomainApiAccess.PRIVILEGED);
            assertThat(registry.dispatch("com.example.routes", DomainHttpMethod.GET,
                    "items/special", Map.of(), new byte[0]).body())
                    .isEqualTo("literal".getBytes(StandardCharsets.UTF_8));
            assertThat(handled.get().pathParameters()).isEmpty();

            assertThat(registry.dispatch("com.example.routes", DomainHttpMethod.GET,
                    "items/42", Map.of(), new byte[0]).body())
                    .isEqualTo("parameter".getBytes(StandardCharsets.UTF_8));
            assertThat(handled.get().pathParameters()).containsExactly(
                    Map.entry("id", "42"));

            assertThat(registry.dispatch("com.example.routes", DomainHttpMethod.GET,
                    "a/b", Map.of(), new byte[0]).body())
                    .isEqualTo("cross-literal-first".getBytes(StandardCharsets.UTF_8));
            assertThat(handled.get().pathParameters()).containsExactly(
                    Map.entry("value", "b"));

            assertThat(registry.access("com.example.routes", DomainHttpMethod.GET,
                    "internal")).contains(DomainApiAccess.INTERNAL);
            assertThatThrownBy(() -> registry.dispatch(
                    "com.example.routes", DomainHttpMethod.GET,
                    "internal", Map.of(), new byte[0]))
                    .isInstanceOfSatisfying(DomainApiException.class, failure ->
                            assertThat(failure.code()).isEqualTo(
                                    DomainApiException.Code.NOT_FOUND));

            registry.sealAndAwait();
            assertThat(closes).hasValue(1);
            assertThat(providers.cleanupSignals).allMatch(CompletableFuture::isDone);
            assertThatThrownBy(() -> registry.dispatch(
                    "com.example.routes", DomainHttpMethod.GET,
                    "items/42", Map.of(), new byte[0]))
                    .isInstanceOfSatisfying(DomainApiException.class, failure ->
                            assertThat(failure.code()).isEqualTo(
                                    DomainApiException.Code.UNAVAILABLE));
        }
        assertThat(closes).hasValue(1);
    }

    @Test
    void rejectsEqualSpecificityStructuralCollisionsBeforePublication() {
        AtomicInteger closes = new AtomicInteger();
        DomainApi api = api(List.of(
                route("left", "a/{value}", DomainApiAccess.READ),
                route("right", "a/{other}", DomainApiAccess.READ)),
                request -> text("unused"), closes);
        RecordingProviders providers = new RecordingProviders(
                provider("com.example.ambiguous", () -> api));

        try (DomainApiRegistry registry = registry(providers, Duration.ofSeconds(1))) {
            assertThatThrownBy(registry::resume)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Invalid domain API routes")
                    .hasMessageContaining("com.example.ambiguous")
                    .hasRootCauseMessage(
                            "structurally duplicate domain API routes: GET a/{value} and a/{other}");
            assertThat(registry.routes()).isEmpty();
            assertThat(closes).hasValue(1);
            assertThat(providers.cleanupSignals).singleElement()
                    .satisfies(signal -> assertThat(signal).isDone());
        }
    }

    @Test
    void preservesTypedHandlerCodesWithCanonicalMessagesAndRedactsUnexpectedFailures() {
        DomainApi api = api(List.of(
                route("oversized", "oversized", DomainApiAccess.READ),
                route("missing", "missing", DomainApiAccess.READ),
                route("unavailable", "unavailable", DomainApiAccess.READ),
                route("unexpected", "unexpected", DomainApiAccess.READ)), request -> {
            return switch (request.routeId()) {
                case "oversized" -> new DomainApiResponse(
                        200, DomainApiMediaType.OCTET_STREAM,
                        new byte[DomainApiResponse.MAX_BODY_BYTES + 1]);
                case "missing" -> throw new DomainApiException(
                        DomainApiException.Code.NOT_FOUND, "plugin missing secret");
                case "unavailable" -> throw new DomainApiException(
                        DomainApiException.Code.UNAVAILABLE, "plugin outage secret");
                default -> throw new IllegalStateException("unexpected plugin secret");
            };
        }, new AtomicInteger());
        RecordingProviders providers = new RecordingProviders(
                provider("com.example.failures", () -> api));

        try (DomainApiRegistry registry = registry(providers, Duration.ofSeconds(1))) {
            registry.resume();

            assertDispatchFailure(registry, "oversized",
                    DomainApiException.Code.RESULT_TOO_LARGE,
                    "Domain API response exceeds the host size limit");
            assertDispatchFailure(registry, "missing",
                    DomainApiException.Code.NOT_FOUND,
                    "Domain API resource was not found");
            assertDispatchFailure(registry, "unavailable",
                    DomainApiException.Code.UNAVAILABLE,
                    "Domain API service is unavailable");
            DomainApiException unexpected = dispatchFailure(registry, "unexpected");
            assertThat(unexpected.code()).isEqualTo(DomainApiException.Code.FAILED);
            assertThat(unexpected).hasMessage("Domain API handler failed");
            assertThat(unexpected.getMessage()).doesNotContain("plugin secret");

            registry.sealAndAwait();
        }
    }

    @Test
    void queuedTimeoutNeverInvokesAndRunningTimeoutRetainsGenerationUntilExit()
            throws Exception {
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        DomainApi api = api(List.of(route("slow", "slow", DomainApiAccess.READ)), request -> {
            invocations.incrementAndGet();
            handlerEntered.countDown();
            boolean released = false;
            while (!released) {
                try {
                    released = releaseHandler.await(1, TimeUnit.DAYS);
                } catch (InterruptedException ignored) {
                    // In-process callbacks may ignore interruption. The host
                    // must retain their generation lease until actual return.
                }
            }
            return text("done");
        }, closes);
        RecordingProviders providers = new RecordingProviders(
                provider("com.example.slow", () -> api));
        ExecutorService callers = Executors.newFixedThreadPool(3);

        try (DomainApiRegistry registry = registry(providers, Duration.ofMillis(100))) {
            registry.resume();
            Future<DomainApiException.Code> first = callers.submit(() -> timeoutCode(registry,
                    "com.example.slow", "slow"));
            assertThat(handlerEntered.await(1, TimeUnit.SECONDS)).isTrue();

            assertThat(timeoutCode(registry, "com.example.slow", "slow"))
                    .isEqualTo(DomainApiException.Code.TIMEOUT);
            assertThat(first.get(1, TimeUnit.SECONDS))
                    .isEqualTo(DomainApiException.Code.TIMEOUT);

            CountDownLatch sealStarted = new CountDownLatch(1);
            Future<?> seal = callers.submit(() -> {
                sealStarted.countDown();
                registry.sealAndAwait();
            });
            assertThat(sealStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(seal.isDone()).isFalse();
            assertThat(providers.cleanupSignals).singleElement()
                    .satisfies(signal -> assertThat(signal).isNotDone());

            releaseHandler.countDown();
            seal.get(1, TimeUnit.SECONDS);
            assertThat(invocations).hasValue(1);
            assertThat(closes).hasValue(1);
            assertThat(providers.cleanupSignals).singleElement()
                    .satisfies(signal -> assertThat(signal).isDone());
        } finally {
            releaseHandler.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void sealingCompletesQueuedInvocationsAsUnavailableAndDrainsOnlyRunningCallbacks()
            throws Exception {
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        DomainApi api = api(List.of(route("work", "work", DomainApiAccess.READ)), request -> {
            invocations.incrementAndGet();
            handlerEntered.countDown();
            releaseHandler.await(5, TimeUnit.SECONDS);
            return text("done");
        }, closes);
        RecordingProviders providers = new RecordingProviders(
                provider("com.example.seal", () -> api));
        ExecutorService callers = Executors.newFixedThreadPool(3);

        try (DomainApiRegistry registry = registry(providers, Duration.ofSeconds(5))) {
            registry.resume();
            Future<DomainApiResponse> running = callers.submit(() -> registry.dispatch(
                    "com.example.seal", DomainHttpMethod.GET,
                    "work", Map.of(), new byte[0]));
            assertThat(handlerEntered.await(1, TimeUnit.SECONDS)).isTrue();

            Future<DomainApiException.Code> queued = callers.submit(() -> {
                try {
                    registry.dispatch("com.example.seal", DomainHttpMethod.GET,
                            "work", Map.of(), new byte[0]);
                    throw new AssertionError("Expected queued dispatch to be cancelled");
                } catch (DomainApiException failure) {
                    return failure.code();
                }
            });
            awaitAdmissions(registry, 2);

            Future<?> sealing = callers.submit(registry::sealAndAwait);
            assertThat(queued.get(1, TimeUnit.SECONDS))
                    .isEqualTo(DomainApiException.Code.UNAVAILABLE);
            assertThat(sealing.isDone()).isFalse();
            assertThat(invocations).hasValue(1);

            releaseHandler.countDown();
            assertThat(running.get(1, TimeUnit.SECONDS).body())
                    .isEqualTo("done".getBytes(StandardCharsets.UTF_8));
            sealing.get(1, TimeUnit.SECONDS);
            assertThat(closes).hasValue(1);
        } finally {
            releaseHandler.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void hostWorkerPoolIsBoundedAcrossIndependentBundles() throws Exception {
        int bundles = 8;
        CountDownLatch fourEntered = new CountDownLatch(
                DomainApiRegistry.DEFAULT_HOST_WORKERS);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        Map<String, DomainApiProvider> selected = new LinkedHashMap<>();
        for (int index = 0; index < bundles; index++) {
            String id = "com.example.host-bound-" + index;
            selected.put(id, provider(id, () -> api(
                    List.of(route("work", "work", DomainApiAccess.READ)), request -> {
                        int now = active.incrementAndGet();
                        maximum.accumulateAndGet(now, Math::max);
                        fourEntered.countDown();
                        try {
                            release.await(1, TimeUnit.SECONDS);
                        } finally {
                            active.decrementAndGet();
                        }
                        return text("ok");
                    }, new AtomicInteger())));
        }
        RecordingProviders providers = new RecordingProviders(selected);
        ExecutorService callers = Executors.newFixedThreadPool(bundles);

        try (DomainApiRegistry registry = registry(providers, Duration.ofSeconds(1))) {
            registry.resume();
            List<Future<DomainApiResponse>> results = new ArrayList<>();
            for (String id : selected.keySet()) {
                results.add(callers.submit(() -> registry.dispatch(
                        id, DomainHttpMethod.GET, "work", Map.of(), new byte[0])));
            }
            assertThat(fourEntered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(maximum).hasValue(DomainApiRegistry.DEFAULT_HOST_WORKERS);
            release.countDown();
            for (Future<DomainApiResponse> result : results) {
                assertThat(result.get(1, TimeUnit.SECONDS).body())
                        .isEqualTo("ok".getBytes(StandardCharsets.UTF_8));
            }
            assertThat(maximum.get()).isLessThanOrEqualTo(
                    DomainApiRegistry.DEFAULT_HOST_WORKERS);
            registry.sealAndAwait();
        } finally {
            release.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void contextIsBundleScopedAndExposesOnlyQueryGateway() {
        AppQueryResult queryResult = new AppQueryResult(
                "chain-a", "machine", 7, new byte[32], new byte[]{9});
        AppChainGateway chainA = (AppChainGateway) Proxy.newProxyInstance(
                AppChainGateway.class.getClassLoader(),
                new Class<?>[]{AppChainGateway.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "chainId" -> "chain-a";
                    case "query" -> queryResult;
                    case "toString" -> "chain-a-test-gateway";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        AppChainGateways chains = gateways(chainA);
        AtomicReference<DomainApiContext> captured = new AtomicReference<>();
        DomainApiProvider provider = provider("com.example.context", () -> {
            throw new AssertionError("context-aware factory required");
        }, captured);
        RecordingProviders providers = new RecordingProviders(provider);

        try (DomainApiRegistry registry = new DomainApiRegistry(
                providers, ignored -> Map.of("endpoint", "public"), chains,
                LoggerFactory.getLogger(DomainApiRegistryTest.class),
                Duration.ofSeconds(1), 16)) {
            registry.resume();

            assertThat(captured.get().bundleConfig())
                    .containsExactly(Map.entry("endpoint", "public"));
            assertThat(captured.get().queryService().chainIds())
                    .containsExactly("chain-a");
            assertThat(captured.get().queryService().query(
                    "chain-a", "lookup", new byte[]{3})).isEqualTo(queryResult);
            registry.sealAndAwait();
        }
    }

    private static DomainApiRegistry registry(
            RecordingProviders providers,
            Duration timeout
    ) {
        return new DomainApiRegistry(
                providers, ignored -> Map.of(), AppChainGateways.empty(),
                LoggerFactory.getLogger(DomainApiRegistryTest.class),
                timeout, 16);
    }

    private static DomainApiRoute route(
            String id,
            String template,
            DomainApiAccess access
    ) {
        return new DomainApiRoute(id, DomainHttpMethod.GET, template, access);
    }

    private static DomainApi api(
            List<DomainApiRoute> routes,
            Handler handler,
            AtomicInteger closes
    ) {
        return new DomainApi() {
            @Override
            public List<DomainApiRoute> routes() {
                return routes;
            }

            @Override
            public DomainApiResponse handle(DomainApiRequest request) throws Exception {
                return handler.handle(request);
            }

            @Override
            public void close() {
                closes.incrementAndGet();
            }
        };
    }

    private static DomainApiProvider provider(
            String id,
            java.util.function.Supplier<DomainApi> factory
    ) {
        return new DomainApiProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public DomainApi create(DomainApiContext context) {
                return factory.get();
            }
        };
    }

    private static DomainApiProvider provider(
            String id,
            java.util.function.Supplier<DomainApi> unused,
            AtomicReference<DomainApiContext> captured
    ) {
        return new DomainApiProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public DomainApi create(DomainApiContext context) {
                captured.set(context);
                return api(List.of(route("query", "query", DomainApiAccess.READ)),
                        request -> text("ok"), new AtomicInteger());
            }
        };
    }

    private static DomainApiResponse text(String value) {
        return new DomainApiResponse(200, DomainApiMediaType.OCTET_STREAM,
                value.getBytes(StandardCharsets.UTF_8));
    }

    private static DomainApiException.Code timeoutCode(
            DomainApiRegistry registry,
            String bundleId,
            String path
    ) {
        try {
            registry.dispatch(bundleId, DomainHttpMethod.GET, path, Map.of(), new byte[0]);
            throw new AssertionError("Expected timeout");
        } catch (DomainApiException failure) {
            return failure.code();
        }
    }

    private static void assertDispatchFailure(
            DomainApiRegistry registry,
            String path,
            DomainApiException.Code code,
            String message
    ) {
        DomainApiException failure = dispatchFailure(registry, path);
        assertThat(failure.code()).isEqualTo(code);
        assertThat(failure).hasMessage(message);
        assertThat(failure.getMessage()).doesNotContain("plugin");
    }

    private static DomainApiException dispatchFailure(
            DomainApiRegistry registry,
            String path
    ) {
        try {
            registry.dispatch("com.example.failures", DomainHttpMethod.GET,
                    path, Map.of(), new byte[0]);
            throw new AssertionError("Expected domain API dispatch failure");
        } catch (DomainApiException failure) {
            return failure;
        }
    }

    private static void awaitAdmissions(
            DomainApiRegistry registry,
            int expected
    ) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (registry.activeAdmissionsForTesting() != expected
                && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertThat(registry.activeAdmissionsForTesting()).isEqualTo(expected);
    }

    private static AppChainGateways gateways(AppChainGateway... gateways) {
        Map<String, AppChainGateway> byId = new LinkedHashMap<>();
        for (AppChainGateway gateway : gateways) {
            byId.put(gateway.chainId(), gateway);
        }
        return new AppChainGateways() {
            @Override
            public Optional<AppChainGateway> byId(String chainId) {
                return Optional.ofNullable(byId.get(chainId));
            }

            @Override
            public Collection<AppChainGateway> all() {
                return byId.values();
            }
        };
    }

    @FunctionalInterface
    private interface Handler {
        DomainApiResponse handle(DomainApiRequest request) throws Exception;
    }

    private static final class RecordingProviders implements PluginProviderRegistry {
        private final Map<String, DomainApiProvider> providers;
        private final List<CompletableFuture<Void>> cleanupSignals = new ArrayList<>();

        private RecordingProviders(DomainApiProvider provider) {
            this(Map.of(provider.id(), provider));
        }

        private RecordingProviders(Map<String, DomainApiProvider> providers) {
            this.providers = Map.copyOf(providers);
        }

        @Override
        public <P> Optional<P> find(Class<P> providerType, String selector) {
            if (providerType != DomainApiProvider.class) {
                return Optional.empty();
            }
            return Optional.ofNullable(providers.get(selector)).map(providerType::cast);
        }

        @Override
        public <P> List<String> names(Class<P> providerType) {
            if (providerType != DomainApiProvider.class) {
                return List.of();
            }
            return providers.keySet().stream().sorted().toList();
        }

        @Override
        public void registerContributionCleanup(CompletableFuture<Void> completion) {
            cleanupSignals.add(completion);
        }
    }
}
