package com.bloxbean.cardano.yano.app;

import com.bloxbean.cardano.yano.api.appchain.AppChainGateway;
import com.bloxbean.cardano.yano.api.appchain.AppChainGateways;
import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.plugin.domain.LocalReadModelHost;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2ParameterSnapshot;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoQueryCodec;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoIndexCoordinator;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoIndexMetrics;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoIndexStore;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoIndexStoreContext;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoLocalReadModel;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoValidityIndexSource;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoValidityIndexSourceProvider;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.IndexHealth;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.IndexIdentity;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.jdbc.SqliteEutxoIndexStoreProvider;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;

/** Application composition for optional per-chain EUTxO index coordinators. */
final class EutxoLifecycleIndexers implements AutoCloseable {
    private final List<ActiveIndex> active;

    private EutxoLifecycleIndexers(List<ActiveIndex> active) {
        this.active = List.copyOf(active);
    }

    static EutxoLifecycleIndexers start(
            AppChainGateways gateways,
            LocalReadModelHost host,
            String network,
            Path nodeData,
            String configuredJdbcUrl,
            String configuredValidityPath,
            MeterRegistry meterRegistry
    ) {
        Objects.requireNonNull(gateways, "gateways");
        Objects.requireNonNull(host, "host");
        List<AppChainGateway> eutxo = gateways.all().stream()
                .filter(EutxoLifecycleIndexers::isEutxo)
                .toList();
        if (!configuredJdbcUrl.isBlank() && eutxo.size() > 1) {
            throw new IllegalArgumentException(
                    "one explicit SQLite URL cannot serve multiple EUTxO chains");
        }
        List<ActiveIndex> active = new ArrayList<>();
        try {
            for (AppChainGateway gateway : eutxo) {
                IndexIdentity identity = identity(gateway, network);
                Path data = nodeData.toAbsolutePath().normalize()
                        .resolve("appchains")
                        .resolve(gateway.chainId())
                        .resolve("indexes");
                Map<String, String> settings = configuredJdbcUrl.isBlank()
                        ? Map.of()
                        : Map.of("jdbc.url", configuredJdbcUrl);
                EutxoIndexStore store =
                        new SqliteEutxoIndexStoreProvider().open(
                                new EutxoIndexStoreContext(
                                        identity, data, settings));
                EutxoIndexMetrics metrics = new EutxoIndexMetrics();
                EutxoValidityIndexSource validity = validitySource(
                        configuredValidityPath, gateway.chainId(), network);
                EutxoIndexCoordinator coordinator =
                        new EutxoIndexCoordinator(gateway, store, metrics);
                AutoCloseable registration = host.register(
                        EutxoLocalReadModel.MODEL_ID,
                        gateway.chainId(),
                        new EutxoLocalReadModel(
                                gateway.chainId(), store,
                                coordinator::health, metrics, validity));
                coordinator.start();
                ActiveIndex index = new ActiveIndex(
                        gateway.chainId(), coordinator, registration,
                        metrics, data.resolve("eutxo-lifecycle.db"));
                active.add(index);
                if (meterRegistry != null) {
                    registerMetrics(meterRegistry, index);
                }
            }
            return new EutxoLifecycleIndexers(active);
        } catch (Throwable failure) {
            close(active);
            throw failure;
        }
    }

    static EutxoLifecycleIndexers disabled() {
        return new EutxoLifecycleIndexers(List.of());
    }

    private static EutxoValidityIndexSource validitySource(
            String configuredPath,
            String chainId,
            String network
    ) {
        if (configuredPath == null || configuredPath.isBlank()) {
            return null;
        }
        Path path = Path.of(configuredPath).toAbsolutePath().normalize();
        List<EutxoValidityIndexSource> sources =
                ServiceLoader.load(EutxoValidityIndexSourceProvider.class)
                        .stream()
                        .map(ServiceLoader.Provider::get)
                        .map(provider -> provider.open(
                                path, chainId, network))
                        .flatMap(java.util.Optional::stream)
                        .toList();
        if (sources.size() != 1) {
            throw new IllegalStateException(
                    "configured validity lifecycle requires exactly one provider");
        }
        return sources.getFirst();
    }

    Map<String, IndexHealth> healthByChain() {
        Map<String, IndexHealth> result = new LinkedHashMap<>();
        active.forEach(index -> result.put(
                index.chainId(), index.coordinator().health()));
        return Map.copyOf(result);
    }

    @Override
    public void close() {
        close(active);
    }

    private static boolean isEutxo(AppChainGateway gateway) {
        try {
            var result = gateway.query(
                    EutxoQueryCodec.PROFILE_PATH, new byte[0]);
            return "eutxo-ledger".equals(result.stateMachineId())
                    && result.payload().length == 64;
        } catch (AppQueryException | IllegalArgumentException failure) {
            return false;
        }
    }

    private static IndexIdentity identity(
            AppChainGateway gateway,
            String network
    ) {
        var profile = gateway.query(
                EutxoQueryCodec.PROFILE_PATH, new byte[0]);
        String ledgerDigest = new String(
                profile.payload(), StandardCharsets.US_ASCII);
        String validityDigest = "";
        try {
            EutxoL2ParameterSnapshot parameters =
                    EutxoQueryCodec.decodeL2Parameters(gateway.query(
                            EutxoQueryCodec.L2_PARAMETERS_PATH,
                            new byte[0]).payload());
            validityDigest = parameters.validityProfileDigest();
        } catch (AppQueryException unsupported) {
            // Direct EUTxO has no optional validity identity.
        }
        return new IndexIdentity(
                network,
                gateway.chainId(),
                profile.stateMachineId(),
                ledgerDigest,
                1,
                validityDigest);
    }

    private static void close(List<ActiveIndex> indexes) {
        for (int index = indexes.size() - 1; index >= 0; index--) {
            ActiveIndex active = indexes.get(index);
            try {
                active.registration().close();
            } catch (Exception ignored) {
                // Continue closing the owned coordinator/store.
            }
            active.coordinator().close();
        }
    }

    private static void registerMetrics(
            MeterRegistry registry,
            ActiveIndex index
    ) {
        String chain = index.chainId();
        Gauge.builder("yano.appchain.eutxo.indexer.indexed.height", index,
                        value -> value.coordinator().health().checkpoint()
                                .source().appHeight())
                .tag("chain", chain).tag("provider", "jdbc-sqlite")
                .register(registry);
        Gauge.builder("yano.appchain.eutxo.indexer.lag.blocks", index,
                        value -> value.coordinator().health().lagBlocks())
                .tag("chain", chain).tag("provider", "jdbc-sqlite")
                .register(registry);
        Gauge.builder("yano.appchain.eutxo.indexer.queue.depth", index,
                        value -> value.coordinator().queueDepth())
                .tag("chain", chain).tag("provider", "jdbc-sqlite")
                .register(registry);
        Gauge.builder("yano.appchain.eutxo.indexer.rebuild.progress", index,
                        EutxoLifecycleIndexers::rebuildProgress)
                .tag("chain", chain).tag("provider", "jdbc-sqlite")
                .register(registry);
        Gauge.builder("yano.appchain.eutxo.indexer.database.bytes", index,
                        EutxoLifecycleIndexers::databaseBytes)
                .tag("chain", chain).tag("provider", "jdbc-sqlite")
                .register(registry);
        FunctionTimer.builder(
                        "yano.appchain.eutxo.indexer.apply",
                        index.metrics(),
                        EutxoIndexMetrics::applyCount,
                        EutxoIndexMetrics::applyNanos,
                        java.util.concurrent.TimeUnit.NANOSECONDS)
                .tag("chain", chain).tag("provider", "jdbc-sqlite")
                .register(registry);
        FunctionTimer.builder(
                        "yano.appchain.eutxo.indexer.query",
                        index.metrics(),
                        EutxoIndexMetrics::queryCount,
                        EutxoIndexMetrics::queryNanos,
                        java.util.concurrent.TimeUnit.NANOSECONDS)
                .tag("chain", chain).tag("provider", "jdbc-sqlite")
                .register(registry);
        FunctionCounter.builder(
                        "yano.appchain.eutxo.indexer.failures",
                        index.metrics(),
                        value -> value.failures())
                .tag("chain", chain).tag("provider", "jdbc-sqlite")
                .register(registry);
        FunctionCounter.builder(
                        "yano.appchain.eutxo.indexer.rollbacks",
                        index.metrics(),
                        value -> value.rollbacks())
                .tag("chain", chain).tag("provider", "jdbc-sqlite")
                .register(registry);
    }

    private static double rebuildProgress(ActiveIndex index) {
        IndexHealth health = index.coordinator().health();
        if (health.finalizedHeight() == 0) {
            return 1;
        }
        return Math.min(1d, (double) health.checkpoint().source().appHeight()
                / (double) health.finalizedHeight());
    }

    private static double databaseBytes(ActiveIndex index) {
        return size(index.database())
                + size(Path.of(index.database() + "-wal"))
                + size(Path.of(index.database() + "-shm"));
    }

    private static long size(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.size(path) : 0;
        } catch (java.io.IOException ignored) {
            return 0;
        }
    }

    private record ActiveIndex(
            String chainId,
            EutxoIndexCoordinator coordinator,
            AutoCloseable registration,
            EutxoIndexMetrics metrics,
            Path database
    ) {
    }
}
