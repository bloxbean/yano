package com.bloxbean.cardano.yano.appchain.objectstore.s3.effects;

import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ObjectStoreS3EffectExecutorFactoryTest {

    @org.junit.jupiter.api.AfterEach
    void clearConstructionSmokeProperties() {
        System.clearProperty(ObjectStoreClientConstructionTestSeam.TEST_MODE_PROPERTY);
        System.clearProperty(ObjectStoreClientConstructionTestSeam.MODE_PROPERTY);
    }

    @Test
    void inactiveEmptyConfigurationDeclinesContribution() {
        ObjectStoreS3EffectExecutorFactory factory = new ObjectStoreS3EffectExecutorFactory(
                ignored -> new ObjectStoreEffectTestSupport.FakeClient());

        assertThat(factory.scheme()).isEqualTo("objectstore-s3");
        assertThat(factory.create("chain", Map.of())).isEmpty();
        assertThat(factory.create("chain", Map.of("enabled", "false"))).isEmpty();
    }

    @Test
    void eachCreateReturnsFreshLazyExecutorAndOwnedClient() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        ObjectStoreS3EffectExecutorFactory factory = new ObjectStoreS3EffectExecutorFactory(
                ignored -> {
                    opens.incrementAndGet();
                    return new ObjectStoreEffectTestSupport.FakeClient();
                });

        List<AppEffectExecutor> first = factory.create("chain", ObjectStoreEffectTestSupport.settings());
        List<AppEffectExecutor> second = factory.create("chain", ObjectStoreEffectTestSupport.settings());
        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        assertThat(first.getFirst()).isNotSameAs(second.getFirst());
        assertThat(opens).hasValue(0);

        assertThat(first.getFirst().execute(ObjectStoreEffectTestSupport.context(1),
                ObjectStoreEffectTestSupport.effect(ObjectStoreEffectTestSupport.command().encode())))
                .isInstanceOf(EffectExecution.Confirmed.class);
        assertThat(opens).hasValue(1);
        first.getFirst().close();
        second.getFirst().close();
    }

    @Test
    void configuredFileDetailArchiveIsOwnedByExecutor(@TempDir Path temporaryDirectory)
            throws Exception {
        Map<String, String> settings = new LinkedHashMap<>(ObjectStoreEffectTestSupport.settings());
        settings.put("detail-archive-path", temporaryDirectory.resolve("details").toString());
        ObjectStoreS3EffectExecutorFactory factory = new ObjectStoreS3EffectExecutorFactory(
                ignored -> new ObjectStoreEffectTestSupport.FakeClient());

        AppEffectExecutor executor = factory.create("chain", settings).getFirst();
        assertThat(executor.effectTypes()).containsExactly(S3ObjectPutExecutor.TYPE);
        EffectExecution result = executor.execute(ObjectStoreEffectTestSupport.context(1),
                ObjectStoreEffectTestSupport.effect(ObjectStoreEffectTestSupport.command().encode()));
        assertThat(result).isInstanceOf(EffectExecution.Confirmed.class);
        assertThat(((EffectExecution.Confirmed) result).detailHash()).hasSize(32);
        assertThat(executor.operationalSnapshot().readiness().name()).isEqualTo("READY");
        assertThat(executor.operationalSnapshot().attempts()).isEqualTo(1);
        executor.close();
        assertThat(temporaryDirectory.resolve("details")).isDirectory();
    }

    @Test
    void constructionSmokeRequiresTheGlobalTestGate() {
        System.setProperty(ObjectStoreClientConstructionTestSeam.MODE_PROPERTY,
                ObjectStoreClientConstructionTestSeam.MODE_V1);
        ObjectStoreS3EffectExecutorFactory factory = new ObjectStoreS3EffectExecutorFactory(
                ignored -> new ObjectStoreEffectTestSupport.FakeClient());

        assertThatThrownBy(() -> factory.create(
                "chain", ObjectStoreEffectTestSupport.settings()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("objectstore-s3 effect executor construction failed")
                .hasRootCauseMessage("connector client construction smoke requires -D"
                        + ObjectStoreClientConstructionTestSeam.TEST_MODE_PROPERTY + "=true");
    }

    @Test
    void constructionSmokeOpensAndClosesOneFreshClient() {
        System.setProperty(ObjectStoreClientConstructionTestSeam.TEST_MODE_PROPERTY, "true");
        System.setProperty(ObjectStoreClientConstructionTestSeam.MODE_PROPERTY,
                ObjectStoreClientConstructionTestSeam.MODE_V1);
        AtomicInteger opens = new AtomicInteger();
        ObjectStoreEffectTestSupport.FakeClient client =
                new ObjectStoreEffectTestSupport.FakeClient();
        ObjectStoreS3EffectExecutorFactory factory = new ObjectStoreS3EffectExecutorFactory(
                ignored -> {
                    opens.incrementAndGet();
                    return client;
                });

        AppEffectExecutor executor = factory.create(
                "chain", ObjectStoreEffectTestSupport.settings()).getFirst();

        assertThat(opens).hasValue(1);
        assertThat(client.closeCalls()).isOne();
        executor.close();
    }
}
