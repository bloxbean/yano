package com.bloxbean.cardano.yano.appchain.ipfs.effects;

import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutorFactory;
import com.bloxbean.cardano.yano.appchain.integration.detail.ConnectorDetailArchive;
import com.bloxbean.cardano.yano.appchain.integration.detail.FileConnectorDetailArchive;
import com.bloxbean.cardano.yano.appchain.ipfs.config.IpfsEffectConfig;
import com.bloxbean.cardano.yano.appchain.ipfs.internal.IpfsPinClientFactory;
import com.bloxbean.cardano.yano.appchain.ipfs.internal.kubo.KuboClientConfig;
import com.bloxbean.cardano.yano.appchain.ipfs.internal.kubo.KuboIpfsPinClientFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** ServiceLoader factory for the pin-only {@code ipfs.pin} executor. */
public final class IpfsEffectExecutorFactory implements AppEffectExecutorFactory {
    private final Function<KuboClientConfig, IpfsPinClientFactory> clientFactoryBuilder;

    /** Creates a factory backed by the isolated Kubo RPC adapter. */
    public IpfsEffectExecutorFactory() {
        this(KuboIpfsPinClientFactory::new);
    }

    IpfsEffectExecutorFactory(
            Function<KuboClientConfig, IpfsPinClientFactory> clientFactoryBuilder) {
        this.clientFactoryBuilder = Objects.requireNonNull(
                clientFactoryBuilder, "clientFactoryBuilder");
    }

    @Override
    public String scheme() {
        return "ipfs";
    }

    @Override
    public List<AppEffectExecutor> create(String chainId, Map<String, String> config) {
        Objects.requireNonNull(chainId, "chainId");
        IpfsEffectConfig parsed = IpfsEffectConfig.parse(config);
        if (!parsed.enabled()) {
            return List.of();
        }

        ConnectorDetailArchive archive = null;
        try {
            if (parsed.detailArchivePath().isPresent()) {
                archive = new FileConnectorDetailArchive(
                        parsed.detailArchivePath().orElseThrow());
            }

            Map<String, IpfsPinClientFactory> clients = new LinkedHashMap<>();
            for (IpfsEffectConfig.Target target : parsed.targets().values()) {
                KuboClientConfig clientConfig = new KuboClientConfig(
                        transportEndpoint(target), target.connectTimeout(),
                        target.requestTimeout(), target.closeTimeout(),
                        target.bearerToken());
                IpfsPinClientFactory clientFactory = Objects.requireNonNull(
                        clientFactoryBuilder.apply(clientConfig), "client factory");
                clients.put(target.alias(), clientFactory);
            }
            Map<String, IpfsPinClientFactory> immutableClients = Map.copyOf(clients);
            return List.of(new IpfsPinExecutor(parsed,
                    target -> immutableClients.get(target.alias()), archive));
        } catch (IOException | RuntimeException constructionFailure) {
            closeAfterFailedConstruction(archive, constructionFailure);
            throw new IllegalArgumentException(
                    "ipfs effect executor construction failed", constructionFailure);
        }
    }

    /**
     * Maps exact {@code localhost} to the loopback literal without invoking
     * name service. Other already-canonical targets are preserved verbatim.
     */
    private static URI transportEndpoint(IpfsEffectConfig.Target target) {
        URI configured = target.apiUrl();
        if (!"localhost".equals(configured.getHost())) {
            return configured;
        }
        try {
            return new URI(configured.getScheme(), null, "127.0.0.1",
                    configured.getPort(), null, null, null);
        } catch (URISyntaxException impossible) {
            throw new IllegalArgumentException("invalid canonical IPFS endpoint");
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
