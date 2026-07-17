package com.bloxbean.cardano.yano.appchain.ipfs.effects;

import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutorFactory;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecution;
import com.bloxbean.cardano.yano.appchain.ipfs.internal.kubo.KuboClientConfig;
import com.bloxbean.cardano.yano.appchain.ipfs.internal.PinState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class IpfsEffectExecutorFactoryTest {

    @org.junit.jupiter.api.AfterEach
    void clearConstructionSmokeProperties() {
        System.clearProperty(IpfsClientConstructionTestSeam.TEST_MODE_PROPERTY);
        System.clearProperty(IpfsClientConstructionTestSeam.MODE_PROPERTY);
    }

    @Test
    void inactiveConfigurationDeclinesContribution() {
        IpfsEffectExecutorFactory factory = new IpfsEffectExecutorFactory(config ->
                () -> new IpfsEffectTestSupport.RecordingClient(PinState.ABSENT));

        assertThat(factory.scheme()).isEqualTo("ipfs");
        assertThat(factory.create("chain", Map.of())).isEmpty();
        assertThat(factory.create("chain", Map.of("enabled", "false"))).isEmpty();
    }

    @Test
    void constructionBindsTargetsWithoutOpeningOrContactingProvider() throws Exception {
        AtomicReference<KuboClientConfig> captured = new AtomicReference<>();
        AtomicInteger opens = new AtomicInteger();
        IpfsEffectTestSupport.RecordingClient client =
                new IpfsEffectTestSupport.RecordingClient(PinState.ABSENT);
        IpfsEffectExecutorFactory factory = new IpfsEffectExecutorFactory(config -> {
            captured.set(config);
            return () -> {
                opens.incrementAndGet();
                return client;
            };
        });

        List<AppEffectExecutor> products = factory.create(
                IpfsEffectTestSupport.CHAIN_ID, IpfsEffectTestSupport.settings());

        assertThat(products).hasSize(1);
        assertThat(products.getFirst().id()).isEqualTo("ipfs-pin");
        assertThat(products.getFirst().effectTypes()).containsExactly(IpfsPinExecutor.TYPE);
        assertThat(products.getFirst().supports("ipfs.pin")).isTrue();
        assertThat(captured.get().apiEndpoint())
                .isEqualTo(URI.create("http://127.0.0.1:5001"));
        assertThat(captured.get().connectTimeout()).hasMillis(250);
        assertThat(captured.get().requestTimeout()).hasMillis(1000);
        assertThat(captured.get().closeTimeout()).hasMillis(250);
        assertThat(opens).hasValue(0);

        assertThat(products.getFirst().execute(IpfsEffectTestSupport.context(1),
                IpfsEffectTestSupport.effect(IpfsEffectTestSupport.command().encode())))
                .isInstanceOf(EffectExecution.Confirmed.class);
        assertThat(products.getFirst().operationalSnapshot().readiness().name())
                .isEqualTo("READY");
        assertThat(products.getFirst().operationalSnapshot().attempts()).isEqualTo(1);
        assertThat(opens).hasValue(1);
        products.getFirst().close();
        assertThat(client.closeCalls()).isOne();
    }

    @Test
    void exactLocalhostIsMappedToLiteralLoopbackWithoutNameResolution() {
        Map<String, String> settings = new LinkedHashMap<>(IpfsEffectTestSupport.settings());
        settings.put("targets.evidence.api-url", "http://localhost:5001");
        AtomicReference<KuboClientConfig> captured = new AtomicReference<>();
        IpfsEffectExecutorFactory factory = new IpfsEffectExecutorFactory(config -> {
            captured.set(config);
            return () -> new IpfsEffectTestSupport.RecordingClient(PinState.ABSENT);
        });

        AppEffectExecutor executor = factory.create("chain", settings).getFirst();
        assertThat(captured.get().apiEndpoint())
                .isEqualTo(URI.create("http://127.0.0.1:5001"));
        executor.close();
    }

    @Test
    void everyCreateReturnsFreshExecutorAndLazyClientOwnership() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        List<IpfsEffectTestSupport.RecordingClient> clients =
                Collections.synchronizedList(new java.util.ArrayList<>());
        IpfsEffectExecutorFactory factory = new IpfsEffectExecutorFactory(config -> () -> {
            opens.incrementAndGet();
            IpfsEffectTestSupport.RecordingClient client =
                    new IpfsEffectTestSupport.RecordingClient(PinState.ABSENT);
            clients.add(client);
            return client;
        });

        AppEffectExecutor first = factory.create("chain", IpfsEffectTestSupport.settings())
                .getFirst();
        AppEffectExecutor second = factory.create("chain", IpfsEffectTestSupport.settings())
                .getFirst();
        assertThat(first).isNotSameAs(second);
        assertThat(opens).hasValue(0);

        assertThat(first.execute(IpfsEffectTestSupport.context(1),
                IpfsEffectTestSupport.effect(IpfsEffectTestSupport.command().encode())))
                .isInstanceOf(EffectExecution.Confirmed.class);
        assertThat(second.execute(IpfsEffectTestSupport.context(1),
                IpfsEffectTestSupport.effect(IpfsEffectTestSupport.command().encode())))
                .isInstanceOf(EffectExecution.Confirmed.class);
        first.close();
        second.close();
        assertThat(opens).hasValue(2);
        assertThat(clients).allSatisfy(client -> assertThat(client.closeCalls()).isOne());
    }

    @Test
    void configuredFileDetailArchiveIsOwnedByExecutor(@TempDir Path temporaryDirectory)
            throws Exception {
        Map<String, String> settings = new LinkedHashMap<>(IpfsEffectTestSupport.settings());
        Path details = temporaryDirectory.resolve("details");
        settings.put("detail-archive-path", details.toString());
        IpfsEffectExecutorFactory factory = new IpfsEffectExecutorFactory(config ->
                () -> new IpfsEffectTestSupport.RecordingClient(PinState.ABSENT));

        AppEffectExecutor executor = factory.create("chain", settings).getFirst();
        EffectExecution result = executor.execute(IpfsEffectTestSupport.context(1),
                IpfsEffectTestSupport.effect(IpfsEffectTestSupport.command().encode()));
        assertThat(result).isInstanceOf(EffectExecution.Confirmed.class);
        assertThat(((EffectExecution.Confirmed) result).detailHash()).hasSize(32);
        executor.close();
        assertThat(details).isDirectory();
    }

    @Test
    void failedBoundFactoryConstructionIsSanitized() {
        Map<String, String> settings = new LinkedHashMap<>(IpfsEffectTestSupport.settings());
        IpfsEffectExecutorFactory factory = new IpfsEffectExecutorFactory(config -> {
            throw new IllegalStateException("provider setup failed");
        });

        assertThatThrownBy(() -> factory.create("chain", settings))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ipfs effect executor construction failed")
                .hasMessageNotContaining("127.0.0.1")
                .hasMessageNotContaining(IpfsEffectTestSupport.TARGET_ID);
    }

    @Test
    void serviceLoaderDeclaresExactlyThePublicIpfsFactory() {
        List<AppEffectExecutorFactory> factories = ServiceLoader.load(
                        AppEffectExecutorFactory.class,
                        IpfsEffectExecutorFactory.class.getClassLoader())
                .stream()
                .map(ServiceLoader.Provider::get)
                .filter(factory -> factory.getClass().getName().startsWith(
                        "com.bloxbean.cardano.yano.appchain.ipfs"))
                .toList();

        assertThat(factories).singleElement()
                .isInstanceOf(IpfsEffectExecutorFactory.class);
    }

    @Test
    void constructionSmokeRequiresTheGlobalTestGate() {
        System.setProperty(IpfsClientConstructionTestSeam.MODE_PROPERTY,
                IpfsClientConstructionTestSeam.MODE_V1);
        IpfsEffectExecutorFactory factory = new IpfsEffectExecutorFactory(config ->
                () -> new IpfsEffectTestSupport.RecordingClient(PinState.ABSENT));

        assertThatThrownBy(() -> factory.create("chain", IpfsEffectTestSupport.settings()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ipfs effect executor construction failed")
                .hasRootCauseMessage("connector client construction smoke requires -D"
                        + IpfsClientConstructionTestSeam.TEST_MODE_PROPERTY + "=true");
    }

    @Test
    void constructionSmokeOpensAndClosesOneFreshClient() {
        System.setProperty(IpfsClientConstructionTestSeam.TEST_MODE_PROPERTY, "true");
        System.setProperty(IpfsClientConstructionTestSeam.MODE_PROPERTY,
                IpfsClientConstructionTestSeam.MODE_V1);
        AtomicInteger opens = new AtomicInteger();
        IpfsEffectTestSupport.RecordingClient client =
                new IpfsEffectTestSupport.RecordingClient(PinState.ABSENT);
        IpfsEffectExecutorFactory factory = new IpfsEffectExecutorFactory(config -> () -> {
            opens.incrementAndGet();
            return client;
        });

        AppEffectExecutor executor = factory.create(
                "chain", IpfsEffectTestSupport.settings()).getFirst();

        assertThat(opens).hasValue(1);
        assertThat(client.closeCalls()).isOne();
        executor.close();
    }
}
