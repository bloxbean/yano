package com.bloxbean.cardano.yano.appchain.kafka.effects;

import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutorFactory;
import com.bloxbean.cardano.yano.appchain.integration.detail.ConnectorDetailArchive;
import com.bloxbean.cardano.yano.appchain.integration.detail.FileConnectorDetailArchive;
import com.bloxbean.cardano.yano.appchain.kafka.config.KafkaEffectConfig;
import com.bloxbean.cardano.yano.appchain.kafka.internal.KafkaEffectProducerFactory;
import com.bloxbean.cardano.yano.appchain.kafka.internal.KafkaProducerClients;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** ServiceLoader factory for the per-action {@code kafka.publish} executor. */
public final class KafkaEffectExecutorFactory implements AppEffectExecutorFactory {
    private final KafkaEffectProducerFactory producerFactory;

    /** Creates a factory whose products own fresh Kafka producers. */
    public KafkaEffectExecutorFactory() {
        this(KafkaProducerClients::open);
    }

    KafkaEffectExecutorFactory(KafkaEffectProducerFactory producerFactory) {
        this.producerFactory = Objects.requireNonNull(producerFactory, "producerFactory");
    }

    @Override
    public String scheme() {
        return "kafka";
    }

    @Override
    public List<AppEffectExecutor> create(String chainId, Map<String, String> config) {
        Objects.requireNonNull(chainId, "chainId");
        KafkaEffectConfig parsed = KafkaEffectConfig.parse(config);
        if (!parsed.enabled()) {
            return List.of();
        }

        ConnectorDetailArchive archive = null;
        try {
            if (parsed.detailArchivePath().isPresent()) {
                archive = new FileConnectorDetailArchive(parsed.detailArchivePath().orElseThrow());
            }
            return List.of(new KafkaPublishExecutor(parsed, producerFactory, archive));
        } catch (IOException | RuntimeException constructionFailure) {
            closeAfterFailedConstruction(archive, constructionFailure);
            throw new IllegalArgumentException("kafka effect executor construction failed",
                    constructionFailure);
        }
    }

    private static void closeAfterFailedConstruction(ConnectorDetailArchive archive,
                                                     Throwable constructionFailure) {
        if (archive == null) {
            return;
        }
        try {
            archive.close();
        } catch (Throwable closeFailure) {
            constructionFailure.addSuppressed(closeFailure);
        }
    }
}
