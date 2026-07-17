package com.bloxbean.cardano.yano.appchain.objectstore.s3.effects;

import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutorFactory;
import com.bloxbean.cardano.yano.appchain.integration.detail.ConnectorDetailArchive;
import com.bloxbean.cardano.yano.appchain.integration.detail.FileConnectorDetailArchive;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.config.ObjectStoreS3EffectConfig;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.ObjectStoreClient;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.ObjectStoreClientFactory;
import com.bloxbean.cardano.yano.appchain.objectstore.s3.internal.aws.AwsS3ObjectStoreClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** ServiceLoader factory for the immutable {@code object.put} executor. */
public final class ObjectStoreS3EffectExecutorFactory implements AppEffectExecutorFactory {
    private static final Logger LOG = LoggerFactory.getLogger(
            ObjectStoreS3EffectExecutorFactory.class);

    private final ObjectStoreClientFactory clientFactory;

    /** Creates a factory backed by the isolated AWS S3-compatible adapter. */
    public ObjectStoreS3EffectExecutorFactory() {
        this(AwsS3ObjectStoreClientFactory.INSTANCE);
    }

    ObjectStoreS3EffectExecutorFactory(ObjectStoreClientFactory clientFactory) {
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
    }

    @Override
    public String scheme() {
        return "objectstore-s3";
    }

    @Override
    public List<AppEffectExecutor> create(String chainId, Map<String, String> config) {
        Objects.requireNonNull(chainId, "chainId");
        ObjectStoreS3EffectConfig parsed = ObjectStoreS3EffectConfig.parse(config);
        if (!parsed.enabled()) {
            return List.of();
        }

        ConnectorDetailArchive archive = null;
        try {
            if (parsed.detailArchivePath().isPresent()) {
                archive = new FileConnectorDetailArchive(parsed.detailArchivePath().orElseThrow());
            }
            constructTestClients(parsed);
            return List.of(new S3ObjectPutExecutor(parsed, clientFactory, archive));
        } catch (IOException | RuntimeException constructionFailure) {
            closeAfterFailedConstruction(archive, constructionFailure);
            throw new IllegalArgumentException(
                    "objectstore-s3 effect executor construction failed", constructionFailure);
        }
    }

    private void constructTestClients(ObjectStoreS3EffectConfig parsed) {
        if (!ObjectStoreClientConstructionTestSeam.armed()) {
            return;
        }
        for (ObjectStoreS3EffectConfig.Target target : parsed.targets().values()) {
            try (ObjectStoreClient client = Objects.requireNonNull(
                    clientFactory.open(target), "client factory product")) {
                // Construction and bounded close are the native-linkage probe.
            }
        }
        LOG.warn("TEST-ONLY native connector client construction passed: objectstore-s3");
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
