package com.bloxbean.cardano.yano.appchain.objectstore.s3.effects;

import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ObjectStoreS3EffectExecutorFactoryTest {

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
        EffectExecution result = executor.execute(ObjectStoreEffectTestSupport.context(1),
                ObjectStoreEffectTestSupport.effect(ObjectStoreEffectTestSupport.command().encode()));
        assertThat(result).isInstanceOf(EffectExecution.Confirmed.class);
        assertThat(((EffectExecution.Confirmed) result).detailHash()).hasSize(32);
        executor.close();
        assertThat(temporaryDirectory.resolve("details")).isDirectory();
    }
}
