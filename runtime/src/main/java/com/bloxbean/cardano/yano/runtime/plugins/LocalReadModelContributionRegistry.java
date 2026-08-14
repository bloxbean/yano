package com.bloxbean.cardano.yano.runtime.plugins;

import com.bloxbean.cardano.yano.api.appchain.AppChainGateway;
import com.bloxbean.cardano.yano.api.appchain.AppChainGateways;
import com.bloxbean.cardano.yano.api.appchain.AppQueryPath;
import com.bloxbean.cardano.yano.api.plugin.domain.FinalizedChainView;
import com.bloxbean.cardano.yano.api.plugin.domain.LocalReadModelContext;
import com.bloxbean.cardano.yano.api.plugin.domain.LocalReadModelHost;
import com.bloxbean.cardano.yano.api.plugin.domain.LocalReadModelProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Host-owned construction and shutdown fence for local read-model plugins. */
public final class LocalReadModelContributionRegistry implements AutoCloseable {
    private final PluginProviderRegistry providers;
    private final Function<String, Map<String, Object>> bundleConfig;
    private final String network;
    private final List<FinalizedChainView> chains;
    private final LocalReadModelHost host;
    private final List<AutoCloseable> active = new ArrayList<>();
    private boolean started;
    private boolean closed;

    public LocalReadModelContributionRegistry(
            PluginRuntimeEnvironment environment,
            String network,
            AppChainGateways appChains,
            LocalReadModelHost host
    ) {
        this(Objects.requireNonNull(environment, "environment").providers(),
                environment::bundleConfig, network, appChains, host);
    }

    LocalReadModelContributionRegistry(
            PluginProviderRegistry providers,
            Function<String, Map<String, Object>> bundleConfig,
            String network,
            AppChainGateways appChains,
            LocalReadModelHost host
    ) {
        this.providers = Objects.requireNonNull(providers, "providers");
        this.bundleConfig = Objects.requireNonNull(bundleConfig, "bundleConfig");
        this.network = Objects.requireNonNull(network, "network");
        Objects.requireNonNull(appChains, "appChains");
        this.host = Objects.requireNonNull(host, "host");
        this.chains = appChains.all().stream()
                .map(ReadOnlyFinalizedChainView::new)
                .map(FinalizedChainView.class::cast)
                .toList();
    }

    public synchronized void resume() {
        if (closed) {
            throw new IllegalStateException("local read-model contributions cannot be started");
        }
        if (started) {
            return;
        }
        List<AutoCloseable> created = new ArrayList<>();
        try {
            for (String id : providers.names(LocalReadModelProvider.class)
                    .stream().sorted().toList()) {
                LocalReadModelProvider provider = providers
                        .require(LocalReadModelProvider.class, id);
                if (!id.equals(provider.id())) {
                    throw new IllegalStateException(
                            "Local read-model provider id does not match its manifest contribution");
                }
                String bundleId = providers.contributionOwner(
                                LocalReadModelProvider.class, id)
                        .orElseThrow(() -> new IllegalStateException(
                                "Local read-model contributions must be manifest-owned"));
                AutoCloseable lifecycle = Objects.requireNonNull(provider.start(
                        new LocalReadModelContext(
                                network, bundleConfig.apply(bundleId), chains, host)),
                        "LocalReadModelProvider.start() must not return null");
                created.add(lifecycle);
            }
            active.addAll(created);
            started = true;
        } catch (Throwable failure) {
            closeReverse(created, failure);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        sealAndAwait();
        closed = true;
    }

    public synchronized void sealAndAwait() {
        if (!started) {
            return;
        }
        try {
            closeReverse(active, null);
        } finally {
            active.clear();
            started = false;
        }
    }

    private static void closeReverse(List<AutoCloseable> resources, Throwable failure) {
        Throwable outcome = failure;
        for (int index = resources.size() - 1; index >= 0; index--) {
            try {
                resources.get(index).close();
            } catch (Throwable closeFailure) {
                if (outcome == null) {
                    outcome = closeFailure;
                } else if (outcome != closeFailure) {
                    outcome.addSuppressed(closeFailure);
                }
            }
        }
        if (outcome instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (outcome instanceof Error error) {
            throw error;
        }
        if (outcome != null) {
            throw new IllegalStateException("Local read-model lifecycle failed", outcome);
        }
    }

    private record ReadOnlyFinalizedChainView(AppChainGateway delegate)
            implements FinalizedChainView {
        private ReadOnlyFinalizedChainView {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override public String chainId() { return delegate.chainId(); }
        @Override public long tipHeight() {
            long height = delegate.tipHeight();
            if (height < 0) {
                throw new IllegalStateException("finalized chain tip must not be negative");
            }
            return height;
        }
        @Override public java.util.Optional<com.bloxbean.cardano.yano.api.appchain.AppBlock>
        block(long height) {
            if (height < 0) {
                throw new IllegalArgumentException("height must not be negative");
            }
            return delegate.block(height);
        }
        @Override public com.bloxbean.cardano.yano.api.appchain.AppQueryResult query(
                String path, byte[] request) {
            AppQueryPath.validate(path);
            Objects.requireNonNull(request, "request");
            if (request.length > com.bloxbean.cardano.yano.api.plugin.domain
                    .DomainQueryService.MAX_REQUEST_BYTES) {
                throw new IllegalArgumentException("request exceeds the 65536 byte limit");
            }
            return delegate.query(path, request.clone());
        }
        @Override public java.util.Optional<com.bloxbean.cardano.yano.api.appchain.state
                .StateCommitmentIdentity> stateCommitmentIdentity() {
            return delegate.stateCommitmentIdentity();
        }
        @Override public AutoCloseable subscribe(FinalizedBlockListener listener) {
            Objects.requireNonNull(listener, "listener");
            return delegate.subscribeFinalized((block, hash) ->
                    listener.onFinalized(block, hash.clone()));
        }
    }
}
