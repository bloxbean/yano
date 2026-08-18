package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.ChainQuery;
import com.bloxbean.cardano.yano.api.LedgerQuery;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import com.bloxbean.cardano.yano.api.account.AccountHistoryProvider;
import com.bloxbean.cardano.yano.api.events.RollbackEvent;
import com.bloxbean.cardano.yano.archive.api.*;
import com.bloxbean.cardano.yano.archive.core.config.*;
import com.bloxbean.cardano.yano.archive.core.consistency.ArchiveConsistencyPlanner;
import com.bloxbean.cardano.yano.archive.core.dataset.BlockArchiveDataset;
import com.bloxbean.cardano.yano.archive.core.dataset.ArchiveBlockFacts;
import com.bloxbean.cardano.yano.archive.core.dataset.AddressTransactionDataset;
import com.bloxbean.cardano.yano.archive.core.dataset.AddressTransactionSubjects;
import com.bloxbean.cardano.yano.archive.core.dataset.StandardBlockDatasets;
import com.bloxbean.cardano.yano.archive.core.dataset.UtxoHistoryDataset;
import com.bloxbean.cardano.yano.archive.core.dataset.UtxoHistoryProjection;
import com.bloxbean.cardano.yano.archive.core.address.*;
import com.bloxbean.cardano.yano.archive.core.hot.*;
import com.bloxbean.cardano.yano.archive.core.hot.HotArchiveRows;
import com.bloxbean.cardano.yano.archive.core.hot.HotHistorySnapshot;
import com.bloxbean.cardano.yano.archive.core.source.ChainBlockArchiveSource;
import com.bloxbean.cardano.yano.archive.core.source.BlockArchiveSource;
import com.bloxbean.cardano.yano.archive.core.source.CycleCachingBlockArchiveSource;
import com.bloxbean.cardano.yano.archive.core.source.EpochArchiveJob;
import com.bloxbean.cardano.yano.archive.core.source.YaciBlockArchiveDecoder;
import com.bloxbean.cardano.yano.archive.core.source.YaciBlockDecoder;
import com.bloxbean.cardano.yano.archive.core.source.YaciUtxoHistoryDecoder;
import com.bloxbean.cardano.yano.archive.core.source.MappingBlockArchiveSource;
import com.bloxbean.cardano.yano.archive.core.worker.*;
import com.bloxbean.cardano.yano.runtime.config.NetworkGenesisConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import com.bloxbean.cardano.yaci.events.api.DomainEventListener;
import org.eclipse.microprofile.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thin application composition boundary for ADR-034. Disabled history returns
 * before ServiceLoader, RocksDB, Flyway, SQLite, or DuckDB initialization.
 */
@ApplicationScoped
public class HistoryArchiveService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(HistoryArchiveService.class);
    private static final String SNAPSHOT_PREFIX = "yano.snapshot-export.";
    private static final String LEGACY_HISTORY_ENABLED = "yano.account-history.enabled";
    private static final String REMOVED_LIVE_ENABLED = "yano.history.live-enabled";

    private final Config config;
    private volatile boolean configuredEnabled;
    private volatile String initializationError;
    private volatile ArchiveConfiguration archiveConfig;
    private volatile ArchiveBackend backend;
    private volatile HotHistoryStore controlStore;
    private volatile String hotStoreEngine = "rocksdb";
    private volatile ArchiveSubsystem subsystem;
    private volatile ExecutorService projectionExecutor;
    private volatile CycleCachingBlockArchiveSource<Block> sharedBlockSource;
    private volatile CycleCachingBlockArchiveSource<ArchiveBlockFacts> sharedFactSource;
    private volatile String projectionParallelismSetting = "auto";
    private final AtomicLong decodedBlockCount = new AtomicLong();
    private final AtomicLong decodedBlockCacheHits = new AtomicLong();
    private volatile ArchiveWorkerMetrics metrics = new ArchiveWorkerMetrics();
    private volatile CycleScopedCoverageCache coverageCache;
    private volatile String shutdownError;
    private volatile String cycleError;
    private final Map<String, String> stageErrors = new ConcurrentHashMap<>();
    private volatile ChainQuery chain;
    private volatile ActivationStore activations;
    private volatile EpochArchiveStagingService epochStaging;
    private volatile LedgerQuery ledger;
    private volatile AddressTransactionDataset catchupAddressDataset;
    private volatile List<SequentialOutpointResolver.Entry> genesisResolverEntries = List.of();
    private volatile long firstCanonicalBlockNumber;
    private final AccountHistoryProvider archiveAccountHistory = new ArchiveAccountHistoryProvider(this);
    private final EnumMap<ArchiveDatasetId, DatasetRunner> catchupWorkers = new EnumMap<>(ArchiveDatasetId.class);
    private final EnumMap<ArchiveDatasetId, DatasetRunner> liveWorkers = new EnumMap<>(ArchiveDatasetId.class);
    private final EnumMap<ArchiveDatasetId, Long> appliedRetention = new EnumMap<>(ArchiveDatasetId.class);
    private final EnumMap<ArchiveDatasetId, Integer> promotionBatchBlocks = new EnumMap<>(ArchiveDatasetId.class);
    private final AtomicLong pendingRollbackEpoch = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong pendingRollbackSlot = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong pendingEpochRollbackSlot = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong nextMaintenanceNanos = new AtomicLong(Long.MAX_VALUE);
    private volatile Duration maintenanceInterval = Duration.ofMinutes(5);
    private volatile ArchiveMaintenanceBudget maintenanceBudget =
            new ArchiveMaintenanceBudget(Duration.ofSeconds(5), 512L * 1024 * 1024);
    private volatile Instant lastMaintenanceAt;
    private volatile String maintenanceError;
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock(true);

    @Inject
    public HistoryArchiveService(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public synchronized void initialize(ChainQuery chain, LedgerQuery ledger, YanoConfig nodeConfig) {
        if (archiveConfig != null || subsystem != null) return;
        this.chain = Objects.requireNonNull(chain, "chain");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(nodeConfig, "nodeConfig");
        configuredEnabled = bool(YanoPropertyKeys.History.ENABLED, false);
        rejectRemovedConfiguration();
        if (!configuredEnabled) {
            log.info("Optional history archive is disabled");
            return;
        }
        if (isNativeImage()) {
            throw new IllegalArgumentException("yano.history.enabled=true is not supported in a native-image build");
        }

        try {
            // History is composed before RuntimeNode.start() so that epoch staging and
            // retention boundaries are installed before sync can apply another block.
            // LedgerQuery's runtime genesis view is populated by start(), therefore use
            // the same already-resolved immutable genesis files as the runtime here.
            NetworkGenesisConfig genesis = NetworkGenesisConfig.load(nodeConfig.getShelleyGenesisFile(),
                    nodeConfig.getByronGenesisFile(), nodeConfig.getAlonzoGenesisFile(),
                    nodeConfig.getConwayGenesisFile());
            firstCanonicalBlockNumber = firstCanonicalBlockNumber(genesis.hasByronGenesis());
            String genesisHash = genesisHash(nodeConfig);
            int magic = Math.toIntExact(genesis.getNetworkMagic());
            ArchiveSafetyWindows safety = ArchiveSafetyWindows.resolve(genesis.getSecurityParam(),
                    autoLong(YanoPropertyKeys.History.ROLLBACK_RETENTION_BLOCKS),
                    autoLong(YanoPropertyKeys.History.FINALITY_BLOCKS));
            Path directory = Path.of(string(YanoPropertyKeys.History.DIR, "./history"))
                    .toAbsolutePath().normalize();
            ArchiveEngine engine = enumValue(YanoPropertyKeys.History.ENGINE, "ducklake", ArchiveEngine.class);
            ArchiveStartMode defaultStart = startMode(string(YanoPropertyKeys.History.START_MODE, "full-required"));
            Map<ArchiveDatasetId, DatasetArchiveConfig> datasets = datasetConfig(defaultStart);
            validateEpochPrerequisites(datasets);
            int enabledBlockProjections = (int) datasets.entrySet().stream()
                    .filter(entry -> entry.getKey().sourceKind() == SourceKind.BLOCK && entry.getValue().enabled())
                    .count();
            projectionParallelismSetting = string(
                    YanoPropertyKeys.History.WORKER_PROJECTION_PARALLELISM, "auto").trim();
            int projectionParallelism = resolveProjectionParallelism(projectionParallelismSetting,
                    Runtime.getRuntime().availableProcessors(), Math.max(1, enabledBlockProjections));
            ArchiveWorkerConfig workerConfig = new ArchiveWorkerConfig(
                    Duration.ofMillis(longValue(YanoPropertyKeys.History.WORKER_POLL_MILLIS, 1_000)),
                    intValue(YanoPropertyKeys.History.WORKER_MAX_BLOCKS, 1_000),
                    intValue(YanoPropertyKeys.History.WORKER_MAX_ROWS, 250_000),
                    bool(YanoPropertyKeys.History.WORKER_PAUSE_DURING_CORE_CATCHUP, false),
                    longValue(YanoPropertyKeys.History.WORKER_CORE_LAG, 100), projectionParallelism);
            maintenanceInterval = Duration.ofSeconds(longValue(
                    YanoPropertyKeys.History.MAINTENANCE_INTERVAL_SECONDS, 300));
            if (maintenanceInterval.isNegative() || maintenanceInterval.isZero()) {
                throw new IllegalArgumentException("history maintenance interval must be positive");
            }
            maintenanceBudget = new ArchiveMaintenanceBudget(Duration.ofSeconds(longValue(
                    YanoPropertyKeys.History.MAINTENANCE_TIME_LIMIT_SECONDS, 5)), sizeBytes(string(
                    YanoPropertyKeys.History.MAINTENANCE_MAX_REWRITE, "512MB")));
            nextMaintenanceNanos.set(nextMaintenanceDeadline(System.nanoTime(), maintenanceInterval));
            archiveConfig = new ArchiveConfiguration(true, directory, engine, defaultStart,
                    workerConfig, safety, datasets);

            hotStoreEngine = string(YanoPropertyKeys.History.HOT_STORE_ENGINE, "sqlite")
                    .trim().toLowerCase(Locale.ROOT);
            if (!hotStoreEngine.equals("rocksdb") && !hotStoreEngine.equals("sqlite")) {
                throw new IllegalArgumentException("unsupported history hot-store engine: " + hotStoreEngine);
            }
            Path hot = hotStoreEngine.equals("sqlite")
                    ? Path.of(string(YanoPropertyKeys.History.HOT_STORE_SQLITE_PATH,
                            directory.resolve("hot-history.sqlite").toString()))
                    : directory.resolve("hot-rocksdb");
            Path temp = Path.of(string("yano.history.duckdb.temp-directory", directory.resolve("tmp").toString()));
            Map<String, Path> paths = new LinkedHashMap<>();
            paths.put("core", Path.of(nodeConfig.getRocksDBPath()));
            paths.put("hot", hot);
            paths.put("temp", temp);
            if (engine == ArchiveEngine.SQLITE) {
                paths.put("sqlite", Path.of(string("yano.history.archive.sqlite.path",
                        directory.resolve("history.sqlite").toString())));
            } else {
                Path catalog = Path.of(string("yano.history.archive.ducklake.catalog.path",
                        directory.resolve("ducklake-catalog.sqlite").toString()));
                paths.put("ducklake-catalog", catalog);
                paths.put("ducklake-tx-locator", catalog.resolveSibling(
                        catalog.getFileName() + ".tx-locator.sqlite"));
                paths.put("ducklake-data", Path.of(string("yano.history.archive.ducklake.data-path",
                        directory.resolve("ducklake-data").toString())));
            }
            ArchivePathValidator.requireDisjoint(paths);
            Files.createDirectories(directory);
            activations = new ActivationStore(directory.resolve("control/activation.properties"));
            controlStore = openHotStore(hotStoreEngine, hot);
            long persistedTip = chain.getLocalTip() == null ? -1 : chain.getLocalTip().getBlockNumber();
            UtxoHistoryProjection utxoProjection = datasetEnabled(ArchiveDatasetId.UTXO_HISTORY)
                    ? resolveUtxoProjection(persistedTip) : UtxoHistoryProjection.all();
            AddressTransactionSubjects addressSubjects = datasetEnabled(ArchiveDatasetId.ADDRESS_TRANSACTION)
                    ? AddressTransactionSubjects.fromConfig(archiveConfig.datasets()
                            .get(ArchiveDatasetId.ADDRESS_TRANSACTION).subjects())
                    : AddressTransactionSubjects.all();
            if (datasetEnabled(ArchiveDatasetId.ADDRESS_TRANSACTION)) {
                activations.configureAddressSubjects(addressSubjects.fingerprint(),
                        activations.start(ArchiveDatasetId.ADDRESS_TRANSACTION).isPresent());
            }

            String engineName = engine.name().toLowerCase(Locale.ROOT);
            ArchiveIdentity identity = new ArchiveIdentity(stableArchiveId(magic, genesisHash, engineName, directory),
                    engineName, 1, magic, genesisHash);
            ArchiveBackendProvider provider = ServiceLoader.load(ArchiveBackendProvider.class).stream()
                    .map(ServiceLoader.Provider::get)
                    .filter(candidate -> candidate.engine().equals(engineName))
                    .findFirst().orElseThrow(() -> new IllegalStateException(
                            "archive backend provider not packaged for engine " + engineName));
            backend = provider.open(identity, directory, backendProperties(directory, engine));
            coverageCache = new CycleScopedCoverageCache(backend);
            EnumSet<com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink.Dataset> epochDatasets =
                    EnumSet.noneOf(com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink.Dataset.class);
            if (datasetEnabled(ArchiveDatasetId.EPOCH_STAKE)) epochDatasets.add(
                    com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink.Dataset.EPOCH_STAKE);
            if (datasetEnabled(ArchiveDatasetId.DREP_DISTRIBUTION)) epochDatasets.add(
                    com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink.Dataset.DREP_DISTRIBUTION);
            if (datasetEnabled(ArchiveDatasetId.ADA_POT)) epochDatasets.add(
                    com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink.Dataset.ADA_POT);
            if (datasetEnabled(ArchiveDatasetId.GOVERNANCE_PROPOSAL_STATUS)) epochDatasets.add(
                    com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink.Dataset.GOVERNANCE_PROPOSAL_STATUS);
            if (datasetEnabled(ArchiveDatasetId.REWARD)) epochDatasets.add(
                    com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink.Dataset.REWARD);
            epochStaging = new EpochArchiveStagingService(chain, ledger, identity.networkIdentity(),
                    directory.resolve("epoch-source"), epochDatasets);
            initializeEpochActivations();
            ledger.setEpochArchiveStagingSink(epochStaging);
            // Install the retention boundary only after every external archive
            // resource has opened successfully. A failed optional subsystem
            // must never leave core pruning pointed at a closed control store.
            chain.setBlockBodyRetentionBoundary(controlStore);

            var blockDecoder = new YaciBlockDecoder(ledger::slotToEpoch, ledger::slotToUnixTime,
                    chain::getBlockEra);
            sharedBlockSource = new CycleCachingBlockArchiveSource<>(
                    new ChainBlockArchiveSource<>(chain, blockDecoder, controlStore),
                    Math.multiplyExact(workerConfig.maxBlocksPerBatch(), 2));
            var factDecoder = new YaciBlockArchiveDecoder(ledger::slotToEpoch, ledger::slotToUnixTime,
                    chain::getBlockEra);
            sharedFactSource = new CycleCachingBlockArchiveSource<>(
                    new MappingBlockArchiveSource<>(sharedBlockSource, factDecoder::project),
                    Math.multiplyExact(workerConfig.maxBlocksPerBatch(), 2));
            var genesisHistoryOutputs = genesisUtxoOutputs(genesis);
            CoreSyncView syncView = new CoreSyncView() {
                public long localBlock() {
                    return chain.getLocalTip() == null ? 0 : chain.getLocalTip().getBlockNumber();
                }
                public long targetBlock() { return chain.getSyncTargetBlockNumber().orElseGet(this::localBlock); }
            };
            registerBlockWorker(ArchiveDatasetId.TRANSACTION, StandardBlockDatasets.transactions(), sharedFactSource,
                    identity.networkIdentity(), workerConfig, syncView);
            registerBlockWorker(ArchiveDatasetId.ACCOUNT_EVENT, StandardBlockDatasets.accountEvents(), sharedFactSource,
                    identity.networkIdentity(), workerConfig, syncView);
            var utxoDecoder = new YaciUtxoHistoryDecoder(ledger::slotToEpoch, ledger::slotToUnixTime,
                    chain::getBlockEra, genesisHistoryOutputs, firstCanonicalBlockNumber, utxoProjection);
            registerBlockWorker(ArchiveDatasetId.UTXO_HISTORY,
                    new UtxoHistoryDataset(controlStore, ArchiveTrack.BACKFILL),
                    new MappingBlockArchiveSource<>(sharedBlockSource, utxoDecoder::project),
                    identity.networkIdentity(), workerConfig, syncView);
            registerLiveWorker(ArchiveDatasetId.TRANSACTION, StandardBlockDatasets.transactions(), sharedFactSource,
                    identity.networkIdentity(), workerConfig);
            registerLiveWorker(ArchiveDatasetId.ACCOUNT_EVENT, StandardBlockDatasets.accountEvents(), sharedFactSource,
                    identity.networkIdentity(), workerConfig);
            registerLiveWorker(ArchiveDatasetId.UTXO_HISTORY,
                    new UtxoHistoryDataset(controlStore, ArchiveTrack.LIVE),
                    new MappingBlockArchiveSource<>(sharedBlockSource, utxoDecoder::project),
                    identity.networkIdentity(), workerConfig);
            if (datasetEnabled(ArchiveDatasetId.ADDRESS_TRANSACTION)) {
                var liveAddress = new AddressTransactionDataset(controlStore, new AddressKeyCodec(),
                        ArchiveTrack.LIVE, addressSubjects);
                registerLiveWorker(ArchiveDatasetId.ADDRESS_TRANSACTION, liveAddress,
                        sharedBlockSource, identity.networkIdentity(), workerConfig);
            }
            if (datasetEnabled(ArchiveDatasetId.ADDRESS_TRANSACTION)) {
                var addressDataset = new AddressTransactionDataset(controlStore, new AddressKeyCodec(),
                        ArchiveTrack.BACKFILL, addressSubjects);
                catchupAddressDataset = addressDataset;
                genesisResolverEntries = genesisOutpoints(genesis, addressDataset);
                initializeAddressResolver(addressDataset);
                registerBlockWorker(ArchiveDatasetId.ADDRESS_TRANSACTION, addressDataset, sharedBlockSource,
                        identity.networkIdentity(), workerConfig, syncView);
            }
            for (ArchiveDatasetId dataset : ArchiveDatasetId.values()) {
                if (dataset.sourceKind() == SourceKind.BLOCK && !catchupWorkers.containsKey(dataset)) {
                    controlStore.releaseBlockBodyRequirement(dataset);
                }
            }
            for (ArchiveDatasetId dataset : catchupWorkers.keySet()) {
                OptionalLong persistedStart = activations.start(dataset);
                long start = persistedStart.orElseGet(() -> activationStart(dataset, persistedTip));
                if (start < firstCanonicalBlockNumber) {
                    start = firstCanonicalBlockNumber;
                    activations.replace(dataset, ArchiveTrack.BACKFILL, start);
                }
                if (start >= 0) controlStore.requireBlockBodiesFrom(dataset, start);
            }
            int effectiveParallelism = Math.min(workerConfig.projectionParallelism(),
                    Math.max(1, catchupWorkers.size()));
            projectionExecutor = Executors.newFixedThreadPool(effectiveParallelism,
                    Thread.ofPlatform().name("yano-archive-projection-", 0).factory());
            subsystem = new ArchiveSubsystem(true, workerConfig.pollInterval(), this::runBoundedWork,
                    this::recordCycleFailure);
            chain.registerListeners(this);
            log.info("History archive initialized: engine={}, dir={}, finalityBlocks={}, rollbackBlocks={}, "
                            + "projectionParallelism={} (requested={}), pauseBackfillDuringCoreCatchup={}",
                    engineName, directory, safety.archiveFinalityBlocks(), safety.rollbackRetentionBlocks(),
                    effectiveParallelism, projectionParallelismSetting,
                    workerConfig.pauseBackfillDuringCoreCatchup());
        } catch (IllegalArgumentException e) {
            closePartial();
            throw e;
        } catch (Exception e) {
            initializationError = e.getMessage();
            log.error("History archive failed closed; core node may continue: {}", e.toString(), e);
            closePartial();
        }
    }

    public synchronized void start() {
        if (subsystem != null) subsystem.start();
    }

    public boolean enabled() {
        return configuredEnabled;
    }

    public boolean available() {
        return backend != null && backend.health().status() != ArchiveHealth.Status.UNHEALTHY
                && backend.health().status() != ArchiveHealth.Status.CLOSED;
    }

    public Optional<ArchiveBackend> backend() {
        return Optional.ofNullable(backend);
    }

    void requireDatasetReady(ArchiveDatasetId dataset) {
        if (!datasetAvailable(dataset)) {
            throw new IllegalStateException("history dataset is still building: " + dataset.logicalName());
        }
    }

    public AccountHistoryProvider accountHistoryProvider() { return archiveAccountHistory; }

    boolean datasetAvailable(ArchiveDatasetId dataset) {
        lifecycleLock.readLock().lock();
        try {
            return available() && datasetEnabled(dataset) && datasetOperational(dataset)
                    && (dataset.sourceKind() == SourceKind.EPOCH || isLivePhase(dataset));
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    /**
     * False once the worker that owns this dataset is parked non-retryably.
     *
     * <p>A parked dataset keeps its durable LIVE phase marker — that marker is
     * crash-safe handoff state and must not be cleared — but its cursor can never
     * advance again. Serving it would return a silently truncated history as
     * though it were complete, which the archive contract forbids. The phase
     * marker therefore stops being sufficient for readiness.
     */
    boolean datasetOperational(ArchiveDatasetId dataset) {
        Map<ArchiveTrack, ArchiveWorkerStatus> statuses = metrics.dataset(dataset);
        ArchiveTrack owning = dataset.sourceKind() == SourceKind.EPOCH
                ? ArchiveTrack.BACKFILL
                : (isLivePhase(dataset) ? ArchiveTrack.LIVE : ArchiveTrack.BACKFILL);
        return !nonRetryablyFailed(statuses, owning);
    }

    /** True when a configured dataset is parked by a non-retryable failure. */
    public boolean datasetFailed(ArchiveDatasetId dataset) {
        lifecycleLock.readLock().lock();
        try {
            return available() && datasetEnabled(dataset) && !datasetOperational(dataset);
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    /** True while a configured block dataset is intentionally unavailable during catch-up. */
    public boolean datasetBuilding(ArchiveDatasetId dataset) {
        lifecycleLock.readLock().lock();
        try {
            return available() && datasetEnabled(dataset) && datasetOperational(dataset)
                    && dataset.sourceKind() == SourceKind.BLOCK && !isLivePhase(dataset);
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    QueryLease openQueryLease() {
        lifecycleLock.readLock().lock();
        return lifecycleLock.readLock()::unlock;
    }

    public Set<ArchiveDatasetId> enabledBlockDatasets() {
        lifecycleLock.readLock().lock();
        try {
            if (archiveConfig == null) return Set.of();
            EnumSet<ArchiveDatasetId> enabled = EnumSet.noneOf(ArchiveDatasetId.class);
            for (ArchiveDatasetId dataset : ArchiveDatasetId.values()) {
                if (dataset.sourceKind() == SourceKind.BLOCK && datasetEnabled(dataset)) enabled.add(dataset);
            }
            return Set.copyOf(enabled);
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    public long firstCanonicalHistoryBlock() {
        return firstCanonicalBlockNumber;
    }

    public ArchiveConsistentRead openFinalizedRead(Set<ArchiveDatasetId> datasets, long fromBlock,
                                                   OptionalLong atOrBeforeBlock,
                                                   OptionalLong atOrBeforeSlot) {
        QueryLease lease = openQueryLease();
        ArchiveReadSession session = null;
        try {
            ArchiveBackend current = backend;
            if (current == null || !available()) throw new ArchiveStoreException("history archive is unavailable");
            for (ArchiveDatasetId dataset : datasets) {
                if (!datasetEnabled(dataset)) {
                    throw new IllegalArgumentException("history dataset is disabled: " + dataset.logicalName());
                }
                if (!datasetOperational(dataset)) {
                    throw new ArchiveStoreException("history dataset is unavailable after a "
                            + "non-retryable failure: " + dataset.logicalName());
                }
                if (dataset.sourceKind() == SourceKind.BLOCK && !isLivePhase(dataset)) {
                    throw new ArchiveStoreException("history dataset is still building: "
                            + dataset.logicalName());
                }
            }
            session = current.openReadSession();
            ArchiveConsistencyPoint point = ArchiveConsistencyPlanner.plan(current, session, datasets,
                    fromBlock, atOrBeforeBlock, atOrBeforeSlot);
            return new ArchiveConsistentRead(session, point, lease);
        } catch (RuntimeException | Error e) {
            if (session != null) session.close();
            lease.close();
            throw e;
        }
    }

    public Map<String, Object> finalizedWatermark(Set<ArchiveDatasetId> datasets, long fromBlock,
                                                   OptionalLong atOrBeforeBlock,
                                                   OptionalLong atOrBeforeSlot) {
        try (ArchiveConsistentRead read = openFinalizedRead(
                datasets, fromBlock, atOrBeforeBlock, atOrBeforeSlot)) {
            return consistencyStatus(read.point());
        }
    }

    List<ArchiveRecord> hotRecords(ArchiveDatasetId dataset, String table, Map<String, Object> filters) {
        if (controlStore == null || !isLivePhase(dataset)) return List.of();
        try (var snapshot = controlStore.snapshot()) {
            return HotArchiveRows.read(snapshot, dataset, table, filters);
        }
    }

    com.bloxbean.cardano.yano.archive.core.hot.HotHistorySnapshot openHotSnapshot() {
        return controlStore == null ? null : controlStore.snapshot();
    }

    Optional<BlockRange> liveCoverage(ArchiveDatasetId dataset) {
        if (dataset.sourceKind() != SourceKind.BLOCK || controlStore == null) {
            return Optional.empty();
        }
        ArchiveProgress progress = controlStore.load(dataset, ArchiveTrack.LIVE).orElse(null);
        if (progress == null) {
            return Optional.empty();
        }
        long start = activations.hotStart(dataset).orElseGet(() ->
                activations.start(dataset).orElse(progress.coordinate() + 1));
        if (start > progress.coordinate()) return Optional.empty();
        return Optional.of(new BlockRange(start, progress.coordinate()));
    }

    public TransactionLookup findTransaction(byte[] txHash) {
        lifecycleLock.readLock().lock();
        try {
            ArchiveBackend current = backend;
            if (current == null || !datasetEnabled(ArchiveDatasetId.TRANSACTION)) {
                return TransactionLookup.unavailable("transaction history is disabled or unavailable");
            }
            if (!datasetOperational(ArchiveDatasetId.TRANSACTION)) {
                // Unavailable, not incomplete: the cursor can never advance, so a
                // "not found" here would be a silently wrong answer forever.
                return TransactionLookup.unavailable(
                        "transaction history is unavailable after a non-retryable failure");
            }
            if (!isLivePhase(ArchiveDatasetId.TRANSACTION)) {
                return TransactionLookup.incomplete("transaction history is still building");
            }
            if (controlStore != null) {
                try (var snapshot = controlStore.snapshot()) {
                    var live = HotArchiveRows.read(snapshot, ArchiveDatasetId.TRANSACTION, "chain_transaction",
                            Map.of("tx_hash", txHash));
                    if (!live.isEmpty()) return TransactionLookup.found(live.getFirst());
                }
            }
            ArchiveCoverage coverage;
            try (ArchiveReadSession read = current.openReadSession()) {
                coverage = current.coverage(read, ArchiveDatasetId.TRANSACTION);
                if (coverage.completeRanges().isEmpty()) {
                    return TransactionLookup.incomplete("transaction history has no coverage");
                }
                Optional<ArchiveRecord> found = current.findTransaction(read, txHash);
                if (found.isPresent()) return TransactionLookup.found(found.orElseThrow());
            }
            long tip = chain != null && chain.getLocalTip() != null ? chain.getLocalTip().getBlockNumber() : -1;
            boolean coversTip = tip < 0 || coverage.covers(tip) || liveCoverageBridgesToTip(ArchiveDatasetId.TRANSACTION, coverage, tip);
            return coversTip ? TransactionLookup.notFound() : TransactionLookup.incomplete("transaction history is catching up");
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    private boolean liveCoverageBridgesToTip(ArchiveDatasetId dataset, ArchiveCoverage cold, long tip) {
        if (controlStore == null) return false;
        ArchiveProgress live = controlStore.load(dataset, ArchiveTrack.LIVE).orElse(null);
        if (live == null || live.coordinate() < tip) return false;
        Optional<BlockRange> hot = liveCoverage(dataset);
        if (hot.isEmpty()) return cold.covers(tip);
        long hotStart = hot.orElseThrow().startInclusive();
        long activation = activations.start(dataset).orElse(hotStart);
        return hotStart <= activation || cold.covers(hotStart - 1);
    }

    private boolean isLivePhase(ArchiveDatasetId dataset) {
        return controlStore != null && controlStore.load(dataset, ArchiveTrack.LIVE).isPresent();
    }

    public Map<String, Object> status() {
        lifecycleLock.readLock().lock();
        try {
            return buildStatus();
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    private Map<String, Object> buildStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", configuredEnabled);
        result.put("available", available());
        if (initializationError != null) result.put("error", initializationError);
        if (archiveConfig == null) return result;
        result.put("engine", archiveConfig.engine().name().toLowerCase(Locale.ROOT));
        result.put("hotStoreEngine", hotStoreEngine);
        result.put("directory", archiveConfig.historyDirectory().toString());
        result.put("finalityBlocks", archiveConfig.safetyWindows().archiveFinalityBlocks());
        result.put("rollbackRetentionBlocks", archiveConfig.safetyWindows().rollbackRetentionBlocks());
        Map<String, Object> worker = new LinkedHashMap<>();
        worker.put("projectionParallelismRequested", projectionParallelismSetting);
        worker.put("projectionParallelismEffective", archiveConfig.worker().projectionParallelism());
        worker.put("pauseBackfillDuringCoreCatchup",
                archiveConfig.worker().pauseBackfillDuringCoreCatchup());
        worker.put("bulkPauseCoreLagBlocks", archiveConfig.worker().bulkPauseCoreLagBlocks());
        worker.put("maxBlocksPerBatch", archiveConfig.worker().maxBlocksPerBatch());
        worker.put("maxRowsPerBatch", archiveConfig.worker().maxRowsPerBatch());
        worker.put("decodedBlocks", decodedBlockCount.get());
        worker.put("decodedBlockCacheHits", decodedBlockCacheHits.get());
        result.put("worker", worker);
        if (backend != null) {
            result.put("health", backend.health());
            // Contention diagnostics are collected before the snapshot read.
            // Saturation is exactly what they explain, so they must not be lost
            // when the bounded request read cannot obtain a permit.
            // One snapshot for both sections: two calls would describe two
            // different instants and rebuild the whole gate view twice.
            ArchiveResourceDiagnostics diagnostics = backend.resourceDiagnostics();
            result.put("resources", resourceStatus(diagnostics));
            Map<String, Object> maintenance = new LinkedHashMap<>();
            maintenance.put("intervalSeconds", maintenanceInterval.toSeconds());
            maintenance.put("timeLimitSeconds", maintenanceBudget.timeLimit().toSeconds());
            maintenance.put("maxBytesToRewrite", maintenanceBudget.maxBytesToRewrite());
            maintenance.put("lastCompletedAt", lastMaintenanceAt);
            maintenance.put("error", maintenanceError);
            diagnostics.lastMaintenanceDeferral()
                    .ifPresent(reason -> maintenance.put("deferredReason", reason));
            result.put("maintenance", maintenance);
            try (ArchiveReadSession read = backend.openReadSession()) {
                result.put("generation", read.generation());
                Map<String, Object> datasets = new LinkedHashMap<>();
                EnumSet<ArchiveDatasetId> enabledBlocks = EnumSet.noneOf(ArchiveDatasetId.class);
                for (ArchiveDatasetId id : ArchiveDatasetId.values()) {
                    DatasetArchiveConfig selected = archiveConfig.datasets().get(id);
                    Map<String, Object> dataset = new LinkedHashMap<>();
                    dataset.put("enabled", selected.enabled());
                    dataset.put("startMode", selected.startMode().name().toLowerCase(Locale.ROOT));
                    dataset.put("retentionEpochs", selected.retentionEpochs());
                    if (id == ArchiveDatasetId.ADDRESS_TRANSACTION) {
                        dataset.put("subjects", selected.subjects());
                    }
                    if (selected.enabled()) {
                        if (id.sourceKind() == SourceKind.BLOCK) {
                            dataset.put("phase", isLivePhase(id) ? "live" : "catching_up");
                            // Matches the read paths: a parked dataset is not ready,
                            // whatever its durable phase marker still says.
                            dataset.put("ready", isLivePhase(id) && datasetOperational(id));
                        }
                        dataset.put("coverage", backend.coverage(read, id));
                        dataset.put("workers", metrics.dataset(id));
                        if (id.sourceKind() == SourceKind.BLOCK) enabledBlocks.add(id);
                    } else {
                        dataset.put("state", ArchiveWorkerStatus.State.DISABLED.name());
                    }
                    datasets.put(id.logicalName(), dataset);
                }
                result.put("datasets", datasets);
                if (!enabledBlocks.isEmpty()) {
                    try {
                        result.put("finalizedConsistency", consistencyStatus(ArchiveConsistencyPlanner.plan(
                                backend, read, enabledBlocks, firstCanonicalBlockNumber,
                                OptionalLong.empty(), OptionalLong.empty())));
                    } catch (ArchiveStoreException unavailable) {
                        result.put("finalizedConsistency", Map.of(
                                "available", false,
                                "detail", unavailable.getMessage()));
                    }
                }
            } catch (RuntimeException unavailable) {
                // A status endpoint must still answer under saturation; the
                // dataset section degrades instead of failing the whole payload.
                result.put("datasetsUnavailable", unavailable.getMessage() == null
                        ? unavailable.toString() : unavailable.getMessage());
            }
        }
        if (epochStaging != null) epochStaging.error().ifPresent(value -> result.put("epochStagingError", value));
        if (!stageErrors.isEmpty()) result.put("stageErrors", Map.copyOf(stageErrors));
        if (cycleError != null) result.put("cycleError", cycleError);
        if (shutdownError != null) result.put("shutdownError", shutdownError);
        return result;
    }

    /**
     * Scheduling-only contention view: gate occupancy, holder operation names and
     * durations, waiter counts, the last wait warning, and the last real failure.
     * It deliberately carries no row, address, or transaction data.
     */
    private static Map<String, Object> resourceStatus(ArchiveResourceDiagnostics diagnostics) {
        Map<String, Object> value = new LinkedHashMap<>();
        List<Map<String, Object>> gates = new ArrayList<>();
        for (ArchiveResourceDiagnostics.GateUsage gate : diagnostics.gates()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", gate.name());
            entry.put("inUse", gate.inUse());
            entry.put("totalPermits", gate.totalPermits());
            entry.put("waiters", gate.waiters());
            entry.put("holder", gate.holder());
            entry.put("holderSeconds", gate.holderDuration().toSeconds());
            gates.add(entry);
        }
        value.put("gates", gates);
        diagnostics.lastWaitWarning().ifPresent(wait -> value.put("lastWaitWarning", Map.of(
                "gate", wait.gate(), "operation", wait.operation(),
                "waitedSeconds", wait.waited().toSeconds(),
                "holder", wait.holderDetail(), "at", wait.at().toString())));
        diagnostics.lastMutationFailure().ifPresent(failure -> value.put("lastMutationFailure", Map.of(
                "operation", failure.operation(), "detail", failure.detail(),
                "at", failure.at().toString())));
        return value;
    }

    private static Map<String, Object> consistencyStatus(ArchiveConsistencyPoint point) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("available", true);
        value.put("generation", point.generation());
        value.put("fromBlock", point.completeRange().startInclusive());
        value.put("toBlock", point.completeRange().endInclusive());
        value.put("asOf", Map.of(
                "blockNumber", point.asOf().blockNumber(),
                "slot", point.asOf().slot(),
                "blockHash", HexUtil.encodeHexString(point.asOf().blockHash())));
        Map<String, Integer> versions = new TreeMap<>();
        point.projectionVersions().forEach((dataset, version) -> versions.put(dataset.logicalName(), version));
        value.put("projectionVersions", versions);
        return value;
    }

    void runBoundedWork() {
        runCycle();
        // Reached only when the whole cycle returned normally, so a recovered
        // transient failure stops being reported as an active one.
        cycleError = null;
    }

    private void runCycle() {
        if (backend == null || chain == null) return;
        // One durable coverage read per dataset per cycle, reused by catch-up,
        // handoff, promotion, and rollback invalidation within that cycle.
        CycleScopedCoverageCache cache = coverageCache;
        if (cache != null) cache.beginCycle();
        var tip = chain.getLocalTip();
        if (tip == null) return;
        processPendingEpochRollback(tip.getBlockNumber());
        processPendingLiveRollback(tip.getBlockNumber());
        long finalized = tip.getBlockNumber() - archiveConfig.safetyWindows().archiveFinalityBlocks();
        if (finalized < 0) return;
        runStage("block-projection", () -> runBlockProjectionCycle(tip.getBlockNumber(), finalized));
        runStage("catchup-handoff", () -> transitionCaughtUpDatasets(finalized));
        beginSharedSourceCycle();
        try {
            for (var entry : liveWorkers.entrySet()) {
                ArchiveDatasetId dataset = entry.getKey();
                if (!isLivePhase(dataset)) continue;
                if (nonRetryablyFailed(metrics.dataset(dataset), ArchiveTrack.LIVE)) continue;
                try {
                    promoteLiveRows(dataset, finalized);
                    reanchorStaleLiveTrack(dataset, tip.getBlockNumber());
                    long activation = activations.start(dataset).orElseThrow(() ->
                            new ArchiveStoreException("missing history activation for "
                                    + dataset.logicalName()));
                    entry.getValue().run(activation, tip.getBlockNumber());
                    long undoCutoff = tip.getBlockNumber()
                            - archiveConfig.safetyWindows().rollbackRetentionBlocks();
                    if (undoCutoff >= activation) {
                        controlStore.pruneResolverThrough(dataset, undoCutoff);
                        controlStore.pruneUndoThrough(dataset, ArchiveTrack.LIVE, undoCutoff);
                    }
                } catch (LiveActivationInvalidatedException e) {
                    try {
                        recoverLiveCanonicalMismatch(dataset, e.activation(), tip.getBlockNumber());
                    } catch (Exception recoveryFailure) {
                        metrics.update(dataset, ArchiveTrack.LIVE, ArchiveWorkerStatus.State.DEGRADED,
                                -1, 0, recoveryFailure.getMessage());
                        log.warn("Live history worker {} reactivation paused: {}",
                                dataset.logicalName(), recoveryFailure.toString());
                    }
                } catch (Exception e) {
                    ArchiveWorkerStatus.State state = classifyFailure(e);
                    metrics.update(dataset, ArchiveTrack.LIVE, state, -1, 0, failureDetail(e));
                    if (state == ArchiveWorkerStatus.State.FAILED) {
                        parkFailedDataset(dataset, e);
                    } else {
                        log.warn("Live history worker {} paused", dataset.logicalName(), e);
                    }
                }
            }
        } finally {
            endSharedSourceCycle();
        }
        runStage("epoch-archive", () -> runEpochWork(finalized));
        runStage("retention", () -> applyRetention(tip.getBlockNumber(), tip.getSlot()));
        runMaintenanceIfDue();
    }

    private void runBlockProjectionCycle(long tip, long finalized) {
        if (catchupWorkers.isEmpty()) return;
        beginSharedSourceCycle();
        try {
            List<Future<?>> futures = new ArrayList<>(catchupWorkers.size());
            for (var entry : catchupWorkers.entrySet()) {
                if (isLivePhase(entry.getKey())) continue;
                futures.add(projectionExecutor.submit(() -> runCatchupDataset(entry.getKey(), entry.getValue(),
                        tip, finalized)));
            }
            boolean interrupted = false;
            for (Future<?> future : futures) {
                boolean complete = false;
                while (!complete) {
                    try {
                        future.get();
                        complete = true;
                    } catch (InterruptedException e) {
                        // Do not release decoded blocks or close native stores
                        // while a projection still owns them. Restore the flag
                        // after every submitted projection has joined.
                        interrupted = true;
                    } catch (java.util.concurrent.ExecutionException e) {
                        // Dataset tasks contain their own fail-closed status path.
                        log.warn("Unexpected archive projection task failure", e.getCause());
                        complete = true;
                    }
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        } finally {
            endSharedSourceCycle();
        }
    }

    private void runCatchupDataset(ArchiveDatasetId dataset, DatasetRunner runner, long tip, long finalized) {
        // A non-retryable error is reported once and then left alone. Re-deriving
        // the same batch every poll would only reproduce the same failure and the
        // same log flood.
        if (nonRetryablyFailed(metrics.dataset(dataset), ArchiveTrack.BACKFILL)) return;
        try {
            long start = activations.start(dataset).orElseGet(() -> activationStart(dataset, tip));
            if (start >= 0) {
                runner.run(start, finalized);
                long undoCutoff = tip - archiveConfig.safetyWindows().rollbackRetentionBlocks();
                if (undoCutoff >= start) {
                    controlStore.pruneResolverThrough(dataset, undoCutoff);
                    controlStore.pruneUndoThrough(dataset, ArchiveTrack.BACKFILL, undoCutoff);
                }
            }
        } catch (BackfillActivationInvalidatedException e) {
            try {
                reactivateCatchupDataset(dataset, e.activation());
            } catch (Exception recoveryFailure) {
                metrics.update(dataset, ArchiveTrack.BACKFILL, ArchiveWorkerStatus.State.DEGRADED,
                        -1, 0, recoveryFailure.getMessage());
                log.warn("History worker {} reactivation paused: {}",
                        dataset.logicalName(), recoveryFailure.toString());
            }
        } catch (Exception e) {
            ArchiveWorkerStatus.State state = classifyFailure(e);
            metrics.update(dataset, ArchiveTrack.BACKFILL, state, -1, 0, failureDetail(e));
            if (state == ArchiveWorkerStatus.State.FAILED) {
                parkFailedDataset(dataset, e);
            } else {
                log.warn("History worker {} paused", dataset.logicalName(), e);
            }
        }
    }

    /**
     * Crash-safe, one-way handoff. The live cursor is persisted first; its
     * presence is the durable phase marker. Catch-up state is then discarded.
     * Resolver rows have one identity and are not copied.
     */
    private void transitionCaughtUpDatasets(long finalized) {
        if (finalized < firstCanonicalBlockNumber - 1) return;
        for (ArchiveDatasetId dataset : catchupWorkers.keySet()) {
            if (isLivePhase(dataset) || !liveWorkers.containsKey(dataset)) continue;
            // Without this a parked catch-up dataset is promoted to LIVE, and the
            // promotion itself clears the failed backfill state that parked it.
            if (nonRetryablyFailed(metrics.dataset(dataset), ArchiveTrack.BACKFILL)) continue;
            long activation = activations.start(dataset).orElse(-1);
            if (activation < 0) continue;
            ArchiveProgress catchup = controlStore.load(dataset, ArchiveTrack.BACKFILL).orElse(null);
            OptionalLong selectedBaseline = handoffBaseline(
                    activation, finalized, catchup, datasetCoverage(dataset));
            if (selectedBaseline.isEmpty()) continue;
            long baseline = selectedBaseline.getAsLong();
            var canonical = chain.getCanonicalBlockReference(baseline).orElse(null);
            if (canonical == null) continue;
            long generation;
            try (ArchiveReadSession read = backend.openReadSession()) { generation = read.generation(); }
            ArchiveProgress liveProgress = new ArchiveProgress(dataset, ArchiveTrack.LIVE, baseline,
                    canonical.slot(), canonical.blockHash(), generation);
            controlStore.applyBlock(dataset, new HotBlockCheckpoint(baseline, canonical.slot(),
                    canonical.blockHash(), new byte[0]), List.of(), liveProgress);
            // Development archives may contain the removed dual-track anchor.
            activations.setHotStart(dataset, Math.addExact(baseline, 1));
            controlStore.clearTrack(dataset, ArchiveTrack.BACKFILL);
            metrics.update(dataset, ArchiveTrack.LIVE, ArchiveWorkerStatus.State.IDLE,
                    baseline, 0, "catch-up complete; single resolver handed to live processing");
            log.info("History dataset {} completed catch-up at block {} and entered live mode",
                    dataset.logicalName(), baseline);
        }
    }

    private static boolean coversRange(ArchiveCoverage coverage, long start, long end) {
        long next = start;
        for (ArchiveRange range : coverage.completeRanges()) {
            if (range.endInclusive() < next) continue;
            if (range.startInclusive() > next) return false;
            next = Math.addExact(range.endInclusive(), 1);
            if (next > end) return true;
        }
        return next > end;
    }

    static OptionalLong handoffBaseline(long activation, long finalized,
                                        ArchiveProgress catchup, ArchiveCoverage coverage) {
        if (activation < 0) throw new IllegalArgumentException("activation must not be negative");
        if (catchup == null) {
            // tip mode intentionally starts after the persisted core tip.
            return activation > finalized ? OptionalLong.of(activation - 1) : OptionalLong.empty();
        }
        if (catchup.coordinate() < finalized) return OptionalLong.empty();
        if (activation <= finalized && !coversRange(coverage, activation, finalized)) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(catchup.coordinate());
    }

    private void beginSharedSourceCycle() {
        if (sharedBlockSource == null || sharedFactSource == null) return;
        sharedBlockSource.beginCycle();
        try {
            sharedFactSource.beginCycle();
        } catch (RuntimeException e) {
            sharedBlockSource.endCycle();
            throw e;
        }
    }

    private void endSharedSourceCycle() {
        if (sharedBlockSource == null || sharedFactSource == null) return;
        sharedFactSource.endCycle();
        CycleCachingBlockArchiveSource.CycleStats stats = sharedBlockSource.endCycle();
        decodedBlockCount.addAndGet(stats.decodedBlocks());
        decodedBlockCacheHits.addAndGet(stats.cacheHits());
    }

    private void reanchorStaleLiveTrack(ArchiveDatasetId dataset, long currentTip) {
        OptionalLong target = chain.getSyncTargetBlockNumber();
        if (target.isEmpty()) return;
        long activation = activations.hotStart(dataset)
                .orElseGet(() -> activations.start(dataset).orElse(-1));
        if (activation < 0) return;
        long liveCoordinate = controlStore.load(dataset, ArchiveTrack.LIVE)
                .map(ArchiveProgress::coordinate).orElse(activation - 1);
        if (!shouldReanchorLive(currentTip, target.getAsLong(), liveCoordinate,
                archiveConfig.worker().bulkPauseCoreLagBlocks(),
                archiveConfig.safetyWindows().rollbackRetentionBlocks())) return;
        reactivateLiveDataset(dataset, activation, currentTip, currentTip,
                "live lag exceeded rollback retention after core reached its upstream target");
    }

    static boolean shouldReanchorLive(long localBlock, long targetBlock, long liveBlock,
                                      long maximumCoreLag, long rollbackRetentionBlocks) {
        if (localBlock < 0 || targetBlock < 0 || liveBlock < -1
                || maximumCoreLag < 0 || rollbackRetentionBlocks < 1) return false;
        long coreLag = Math.max(0, targetBlock - localBlock);
        long liveLag = Math.max(0, localBlock - liveBlock);
        return coreLag <= maximumCoreLag && liveLag > rollbackRetentionBlocks;
    }

    void runMaintenanceIfDue() {
        ArchiveBackend selected = backend;
        if (selected == null) return;
        long now = System.nanoTime();
        long due = nextMaintenanceNanos.get();
        if (now < due || !nextMaintenanceNanos.compareAndSet(
                due, nextMaintenanceDeadline(now, maintenanceInterval))) return;
        try {
            selected.maintain(maintenanceBudget);
            maintenanceError = null;
            // maintain() returns normally when it defers, so a deferral reason
            // means upkeep did not actually run and must not stamp a completion.
            // One snapshot, and tolerant of a backend that reports none.
            ArchiveResourceDiagnostics diagnostics = selected.resourceDiagnostics();
            Optional<String> deferred = diagnostics == null
                    ? Optional.empty() : diagnostics.lastMaintenanceDeferral();
            if (deferred.isPresent()) {
                log.debug("History archive maintenance deferred: {}", deferred.orElseThrow());
            } else {
                lastMaintenanceAt = Instant.now();
                log.info("History archive maintenance completed within {}s / {} bytes rewrite budget",
                        maintenanceBudget.timeLimit().toSeconds(), maintenanceBudget.maxBytesToRewrite());
            }
        } catch (Exception e) {
            maintenanceError = e.getMessage();
            log.warn("History archive maintenance deferred after bounded failure: {}", e.toString());
        }
    }

    static long nextMaintenanceDeadline(long nowNanos, Duration interval) {
        try {
            return Math.addExact(nowNanos, interval.toNanos());
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Runs one cycle stage in isolation.
     *
     * <p>An unguarded stage failure used to skip every later stage on every
     * cycle, so a single broken dataset silently stopped promotion, retention,
     * and maintenance for good. Each stage now fails on its own and is reported
     * by name.
     */
    void runStage(String stage, Runnable work) {
        try {
            work.run();
            stageErrors.remove(stage);
        } catch (Exception e) {
            stageErrors.put(stage, failureDetail(e));
            log.warn("Archive cycle stage {} failed; continuing with the remaining stages", stage, e);
        }
    }

    /** Coverage for one dataset, reusing this cycle's durable read when present. */
    private ArchiveCoverage datasetCoverage(ArchiveDatasetId dataset) {
        CycleScopedCoverageCache cache = coverageCache;
        return cache == null ? backend.coverage(dataset) : cache.coverage(dataset);
    }

    private void invalidateCoverageCache(ArchiveDatasetId dataset) {
        CycleScopedCoverageCache cache = coverageCache;
        if (cache != null) cache.invalidate(dataset);
    }

    /**
     * A cycle failure thrown before any dataset could record its own status would
     * otherwise be invisible, since the subsystem must not let one bad cycle kill
     * the schedule.
     */
    /** Last top-level cycle failure, cleared once a whole cycle completes. */
    String cycleError() {
        return cycleError;
    }

    /** Per-stage cycle failures by stage name; an entry clears when that stage succeeds. */
    Map<String, String> stageErrors() {
        return Map.copyOf(stageErrors);
    }

    void recordCycleFailure(Throwable failure) {
        String detail = failureDetail(failure);
        // A cycle failure repeats every poll interval; log the stack once per
        // distinct failure instead of flooding, which is the same policy the
        // per-dataset paths follow.
        if (!detail.equals(cycleError)) {
            log.warn("Archive cycle failed; retrying on the next poll", failure);
        }
        cycleError = detail;
    }

    /**
     * Parks a dataset that failed non-retryably and releases the core resources it
     * was holding on behalf of future work it will never do.
     *
     * <p>A parked dataset stops advancing its cursor, so its block-body floor
     * would otherwise pin core pruning forever and grow the chain database without
     * bound. Its coverage stays exactly as committed; only the claim on future
     * source bodies is dropped. Recovering the dataset needs a restart with
     * corrected configuration and may then require a rebuild if bodies have since
     * been pruned — which is the documented trade against unbounded core disk use.
     */
    private void parkFailedDataset(ArchiveDatasetId dataset, Exception failure) {
        log.error("History dataset {} failed non-retryably and is parked until restart. Releasing its "
                        + "block-body retention hold so core pruning is not pinned; a later rebuild may be "
                        + "required if those bodies are pruned meanwhile.",
                dataset.logicalName(), failure);
        try {
            if (controlStore != null) controlStore.releaseBlockBodyRequirement(dataset);
        } catch (Exception release) {
            log.warn("Could not release the block-body requirement for parked dataset {}",
                    dataset.logicalName(), release);
        }
    }

    /**
     * A dataset whose last outcome was non-retryable stays parked until the node
     * restarts with corrected configuration, schema, or projection state.
     */
    static boolean nonRetryablyFailed(Map<ArchiveTrack, ArchiveWorkerStatus> statuses, ArchiveTrack track) {
        ArchiveWorkerStatus status = statuses.get(track);
        return status != null && status.state() == ArchiveWorkerStatus.State.FAILED;
    }

    /**
     * Waiting is not a failure, and a non-retryable error is not the same as a
     * transient one. Keeping the three apart is what makes archive status
     * actionable.
     */
    static ArchiveWorkerStatus.State classifyFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof ArchiveFatalException) return ArchiveWorkerStatus.State.FAILED;
            if (current instanceof ArchiveStuckOperationException) return ArchiveWorkerStatus.State.DEGRADED;
        }
        return ArchiveWorkerStatus.State.DEGRADED;
    }

    private static String failureDetail(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.toString() : message;
    }

    /** Event-thread work is deliberately constant-time; archive I/O stays on the optional worker. */
    @DomainEventListener(order = 1_000)
    public void onRollback(RollbackEvent event) {
        if (!event.realReorg() || event.target() == null || ledger == null) return;
        long targetEpoch = ledger.slotToEpoch(event.target().getSlot());
        pendingRollbackEpoch.accumulateAndGet(targetEpoch, Math::min);
        pendingRollbackSlot.accumulateAndGet(event.target().getSlot(), Math::min);
        pendingEpochRollbackSlot.accumulateAndGet(event.target().getSlot(), Math::min);
    }

    private void processPendingLiveRollback(long currentTip) {
        long targetSlot = pendingRollbackSlot.getAndSet(Long.MAX_VALUE);
        if (targetSlot == Long.MAX_VALUE || controlStore == null) return;
        boolean retry = false;
        try {
            long targetBlock = blockAtOrBeforeSlot(targetSlot, currentTip);
            Set<ArchiveDatasetId> finalizedInvalidated = invalidateBlockArchivesAfterRollback(targetBlock);
            for (ArchiveDatasetId dataset : liveWorkers.keySet()) {
                ArchiveProgress progress = controlStore.load(dataset, ArchiveTrack.LIVE).orElse(null);
                if (progress == null || progress.coordinate() <= targetBlock) continue;
                long activation = activations.hotStart(dataset)
                        .orElseGet(() -> activations.start(dataset).orElse(targetBlock + 1));
                // Cold coverage above was invalidated for every dataset, parked
                // or not. Only reactivation and the status update are withheld
                // from a parked dataset: reactivating would re-arm catch-up and
                // the body-retention floor for a worker that cannot advance, and
                // overwriting FAILED would silently unpark it.
                boolean parked = !datasetOperational(dataset);
                try {
                    switch (liveRollbackAction(parked, finalizedInvalidated.contains(dataset),
                            targetBlock, activation)) {
                        case CLEAR_STALE_PARKED -> clearStaleParkedLiveTrack(dataset, targetBlock);
                        case REACTIVATE -> reactivateLiveDataset(dataset, activation, currentTip, targetBlock);
                        case RESET_TO_ACTIVATION ->
                                controlStore.resetTrackFrom(dataset, ArchiveTrack.LIVE, activation);
                        case EXACT_ROLLBACK -> controlStore.rollbackTo(dataset, ArchiveTrack.LIVE, targetBlock);
                    }
                    if (!parked) {
                        metrics.update(dataset, ArchiveTrack.LIVE, ArchiveWorkerStatus.State.IDLE,
                                targetBlock, currentTip - targetBlock, "exact rollback applied");
                    }
                } catch (Exception rollbackFailure) {
                    if (parked) {
                        // Stays parked, but the rollback is requeued: leaving the
                        // stale cursor and orphaned hot rows in place would let a
                        // restart promote them against new canonical anchors.
                        retry = true;
                        log.warn("Rollback of parked history dataset {} failed and is pending retry",
                                dataset.logicalName(), rollbackFailure);
                        continue;
                    }
                    try {
                        reactivateLiveDataset(dataset, activation, currentTip, targetBlock);
                    } catch (Exception recoveryFailure) {
                        retry = true;
                        recoveryFailure.addSuppressed(rollbackFailure);
                        metrics.update(dataset, ArchiveTrack.LIVE, ArchiveWorkerStatus.State.DEGRADED,
                                targetBlock, currentTip - targetBlock, recoveryFailure.getMessage());
                        log.warn("Live history rollback for {} is pending retry: {}",
                                dataset.logicalName(), recoveryFailure.toString());
                    }
                }
            }
        } catch (Exception e) {
            retry = true;
            log.warn("Live archive rollback is pending retry through slot {}: {}", targetSlot, e.toString());
        } finally {
            if (retry) pendingRollbackSlot.accumulateAndGet(targetSlot, Math::min);
        }
    }

    /** What a live rollback may do to one dataset. */
    enum LiveRollbackAction { EXACT_ROLLBACK, RESET_TO_ACTIVATION, REACTIVATE, CLEAR_STALE_PARKED }

    /**
     * Chooses the rollback action for one dataset.
     *
     * <p>A parked dataset never reactivates: that would re-arm catch-up and the
     * block-body retention floor that {@code parkFailedDataset} deliberately
     * released, resuming a worker whose fatal condition is unchanged. Its stale
     * live track is still cleared, because the parked state lives only in memory
     * and a surviving cursor would let a restarted worker resume on an orphaned
     * branch. Exact hot rollback still applies otherwise, so private hot state is
     * never left beyond the common block.
     */
    static LiveRollbackAction liveRollbackAction(boolean parked, boolean coldInvalidated,
                                                 long targetBlock, long activation) {
        if (coldInvalidated || targetBlock < activation - 1) {
            return parked ? LiveRollbackAction.CLEAR_STALE_PARKED : LiveRollbackAction.REACTIVATE;
        }
        if (targetBlock == activation - 1) return LiveRollbackAction.RESET_TO_ACTIVATION;
        return LiveRollbackAction.EXACT_ROLLBACK;
    }

    /**
     * Drops the orphaned live cursor and hot rows of a parked dataset.
     *
     * <p>Parking is in-memory only, so leaving the durable cursor in place would
     * let the next process resume above an invalidated range. Clearing it returns
     * the dataset to catch-up on restart, and the failed state is mirrored onto
     * the backfill track so it stays parked for the rest of this process.
     */
    private void clearStaleParkedLiveTrack(ArchiveDatasetId dataset, long targetBlock) {
        log.warn("Parked history dataset {} had its live track invalidated by a rollback to block {}; "
                        + "clearing the stale cursor so a restart rebuilds instead of resuming an orphan branch",
                dataset.logicalName(), targetBlock);
        // Deliberately not swallowed: the caller requeues the rollback so the
        // orphaned cursor cannot survive to the next process.
        controlStore.clearTrack(dataset, ArchiveTrack.LIVE);
        metrics.update(dataset, ArchiveTrack.BACKFILL, ArchiveWorkerStatus.State.FAILED, -1, 0,
                "parked; live track invalidated by rollback to block " + targetBlock);
    }

    private Set<ArchiveDatasetId> invalidateBlockArchivesAfterRollback(long targetBlock) {
        EnumSet<ArchiveDatasetId> invalidated = EnumSet.noneOf(ArchiveDatasetId.class);
        for (ArchiveDatasetId dataset : catchupWorkers.keySet()) {
            if (invalidateFinalizedBlockDataset(dataset, targetBlock)) invalidated.add(dataset);
        }
        return Set.copyOf(invalidated);
    }

    private boolean invalidateFinalizedBlockDataset(ArchiveDatasetId dataset, long commonBlock) {
        ArchiveCoverage coverage = datasetCoverage(dataset);
        long last = coverage.completeRanges().stream().mapToLong(ArchiveRange::endInclusive)
                .max().orElse(commonBlock);
        if (last <= commonBlock) return false;
        backend.invalidate(dataset, new BlockRange(Math.addExact(commonBlock, 1), last));
        invalidateCoverageCache(dataset);
        log.warn("Invalidated finalized {} archive after canonical rollback to block {}",
                dataset.logicalName(), commonBlock);
        return true;
    }

    private void recoverLiveCanonicalMismatch(ArchiveDatasetId dataset, long activation, long currentTip) {
        ArchiveProgress progress = controlStore.load(dataset, ArchiveTrack.LIVE).orElse(null);
        long common = activation - 1;
        if (progress != null) {
            for (long block = Math.min(progress.coordinate(), currentTip); block >= activation; block--) {
                var checkpoint = controlStore.checkpoint(dataset, ArchiveTrack.LIVE, block);
                var canonical = chain.getCanonicalBlockReference(block);
                if (checkpoint.isEmpty() || canonical.isEmpty()) break;
                if (checkpoint.isPresent() && canonical.isPresent()
                        && Arrays.equals(checkpoint.orElseThrow().blockHash(), canonical.orElseThrow().blockHash())) {
                    common = block;
                    break;
                }
            }
        }
        invalidateFinalizedBlockDataset(dataset, common);
        reactivateLiveDataset(dataset, activation, currentTip, common);
    }

    private long blockAtOrBeforeSlot(long targetSlot, long currentTip) {
        long low = 0, high = currentTip, answer = -1;
        while (low <= high) {
            long middle = low + ((high - low) >>> 1);
            var reference = chain.getCanonicalBlockReference(middle);
            if (reference.isEmpty()) {
                high = middle - 1;
                continue;
            }
            if (reference.orElseThrow().slot() <= targetSlot) {
                answer = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return answer;
    }

    private void processPendingEpochRollback(long currentTip) {
        long targetEpoch = pendingRollbackEpoch.getAndSet(Long.MAX_VALUE);
        long targetSlot = pendingEpochRollbackSlot.getAndSet(Long.MAX_VALUE);
        if (targetEpoch == Long.MAX_VALUE || targetSlot == Long.MAX_VALUE) return;
        long targetBlock = blockAtOrBeforeSlot(targetSlot, currentTip);
        try {
            int discarded = epochStaging == null ? 0 : epochStaging.discardAfterBlock(targetBlock);
            for (ArchiveDatasetId dataset : ArchiveDatasetId.values()) {
                if (dataset.sourceKind() != SourceKind.EPOCH || !datasetEnabled(dataset)) continue;
                if (backend.invalidateEpochJobsAfterSlot(dataset, targetSlot) > 0) {
                    controlStore.clearTrack(dataset, ArchiveTrack.BACKFILL);
                }
            }
            log.info("Applied archive epoch rollback through epoch {}, slot {}, block {}; discarded {} staged source jobs",
                    targetEpoch, targetSlot, targetBlock, discarded);
        } catch (Exception e) {
            pendingRollbackEpoch.accumulateAndGet(targetEpoch, Math::min);
            pendingEpochRollbackSlot.accumulateAndGet(targetSlot, Math::min);
            log.warn("Archive epoch rollback is pending retry through epoch {}: {}", targetEpoch, e.toString());
        }
    }

    private void applyRetention(long tipBlock, long tipSlot) {
        long currentEpoch = ledger.slotToEpoch(tipSlot);
        for (var entry : archiveConfig.datasets().entrySet()) {
            long epochs = entry.getValue().retentionEpochs();
            if (!entry.getValue().enabled() || epochs == 0 || currentEpoch < epochs) continue;
            ArchiveDatasetId dataset = entry.getKey();
            long cutoffEpoch = currentEpoch - epochs;
            long cutoff = dataset.sourceKind() == SourceKind.EPOCH
                    ? cutoffEpoch : firstBlockAtOrAfterEpoch(cutoffEpoch, tipBlock);
            if (cutoff <= 0 || appliedRetention.getOrDefault(dataset, -1L) >= cutoff) continue;
            backend.applyRetention(dataset, new ArchiveRetentionCutoff(dataset.sourceKind(), cutoff));
            invalidateCoverageCache(dataset);
            appliedRetention.put(dataset, cutoff);
        }
    }

    private long firstBlockAtOrAfterEpoch(long epoch, long tipBlock) {
        long low = 0, high = tipBlock, answer = tipBlock;
        while (low <= high) {
            long middle = low + ((high - low) >>> 1);
            var reference = chain.getCanonicalBlockReference(middle);
            if (reference.isEmpty()) { low = middle + 1; continue; }
            long selectedEpoch = ledger.slotToEpoch(reference.orElseThrow().slot());
            if (selectedEpoch >= epoch) { answer = middle; high = middle - 1; }
            else low = middle + 1;
        }
        return answer;
    }

    private void reactivateLiveDataset(ArchiveDatasetId dataset, long oldActivation, long currentTip,
                                       long replayAfterBlock) {
        reactivateLiveDataset(dataset, oldActivation, currentTip, replayAfterBlock,
                "canonical rollback invalidated the live anchor");
    }

    private void reactivateLiveDataset(ArchiveDatasetId dataset, long oldActivation, long currentTip,
                                       long replayAfterBlock, String reason) {
        controlStore.clearTrack(dataset, ArchiveTrack.LIVE);
        long historyActivation = activations.start(dataset).orElse(oldActivation);
        reactivateCatchupDataset(dataset, historyActivation);
        metrics.update(dataset, ArchiveTrack.BACKFILL, ArchiveWorkerStatus.State.IDLE,
                historyActivation - 1, currentTip - replayAfterBlock, "catch-up restarted: " + reason);
        log.warn("Returned {} history to catch-up at block {}; previous live anchor {} ({})",
                dataset.logicalName(), historyActivation, oldActivation, reason);
    }

    private void reactivateCatchupDataset(ArchiveDatasetId dataset, long activation) {
        invalidateCoverageCache(dataset);
        controlStore.clearTrack(dataset, ArchiveTrack.BACKFILL);
        long replacement = activation;
        if (dataset == ArchiveDatasetId.ADDRESS_TRANSACTION && catchupAddressDataset != null) {
            catchupAddressDataset.resetResolver();
            ArchiveStartMode mode = archiveConfig.datasets().get(dataset).startMode();
            if (mode == ArchiveStartMode.TIP) {
                if (ledger == null || ledger.getUtxoState() == null || !ledger.getUtxoState().isEnabled()) {
                    throw new ArchiveStoreException("address history tip reactivation requires core UTXO state");
                }
                replacement = Math.addExact(
                        seedResolverSnapshot(ledger.getUtxoState(), catchupAddressDataset), 1);
                activations.replace(dataset, ArchiveTrack.BACKFILL, replacement);
            } else {
                seedGenesisResolver(catchupAddressDataset, replacement);
            }
        } else if (dataset == ArchiveDatasetId.UTXO_HISTORY) {
            controlStore.resetResolver(dataset);
        }
        controlStore.requireBlockBodiesFrom(dataset, replacement);
        metrics.update(dataset, ArchiveTrack.BACKFILL, ArchiveWorkerStatus.State.IDLE,
                replacement - 1, 0, "rebuilding after rollback crossed retained undo");
        log.warn("Reset {} catch-up to activation {} after rollback crossed retained undo",
                dataset.logicalName(), replacement);
    }

    private void runEpochWork(long finalized) {
        EpochArchiveStagingService staging = epochStaging;
        if (staging == null) return;
        record Key(ArchiveDatasetId dataset, long epoch, long block) { }
        Map<Key, List<EpochPending>> groups = new TreeMap<>(Comparator
                .comparingLong(Key::block).thenComparing(key -> key.dataset().name()).thenComparingLong(Key::epoch));
        for (var binding : staging.sources()) {
            // A parked epoch dataset can never commit, so scanning its pending
            // set every poll only re-materializes a set that keeps growing.
            if (nonRetryablyFailed(metrics.dataset(binding.dataset()), ArchiveTrack.BACKFILL)) continue;
            for (var job : staging.pending(binding, 16)) {
                long activation = activations.start(job.dataset()).orElseThrow(() ->
                        new ArchiveStoreException("missing epoch activation for " + job.dataset().logicalName()));
                if (job.epoch() >= activation && job.boundaryBlockNumber() <= finalized) {
                    groups.computeIfAbsent(new Key(job.dataset(), job.epoch(), job.boundaryBlockNumber()),
                            ignored -> new ArrayList<>()).add(new EpochPending(binding, job));
                }
            }
        }
        int completed = 0;
        for (var group : groups.values()) {
            group.sort(Comparator.comparing(item -> item.job().sourceReference()));
            ArchiveDatasetId dataset = group.getFirst().job().dataset();
            // Epoch datasets get the same non-retryable handling as block and
            // live projections; without it a fatal group is re-derived forever
            // and its failure escapes to the cycle instead of parking one dataset.
            if (nonRetryablyFailed(metrics.dataset(dataset), ArchiveTrack.BACKFILL)) continue;
            try {
                commitEpochGroup(group);
                metrics.update(dataset, ArchiveTrack.BACKFILL, ArchiveWorkerStatus.State.IDLE,
                        group.getFirst().job().epoch(), 0, "epoch archive committed");
            } catch (Exception e) {
                ArchiveWorkerStatus.State state = classifyFailure(e);
                metrics.update(dataset, ArchiveTrack.BACKFILL, state, -1, 0, failureDetail(e));
                if (state == ArchiveWorkerStatus.State.FAILED) {
                    parkFailedDataset(dataset, e);
                } else {
                    log.warn("Epoch history worker {} paused", dataset.logicalName(), e);
                }
            }
            if (++completed == 4) break;
        }
    }

    private void commitEpochGroup(List<EpochPending> group) {
        EpochArchiveJob first = group.getFirst().job();
        for (EpochPending item : group) {
            EpochArchiveJob job = item.job();
            if (job.dataset() != first.dataset() || job.epoch() != first.epoch()
                    || job.boundaryBlockNumber() != first.boundaryBlockNumber()
                    || !Arrays.equals(job.boundaryBlockHash(), first.boundaryBlockHash())) {
                throw new ArchiveStoreException("epoch source parts do not share a canonical boundary");
            }
        }
        ArchiveJob job = ArchiveJob.deterministic(first.networkIdentity(), first.dataset(),
                first.projectionVersion(), new EpochRange(first.epoch(), first.epoch()),
                new ArchiveRangeAnchor(first.boundarySlot(), first.boundaryBlockHash(),
                        first.boundarySlot(), first.boundaryBlockHash()), "ledger-boundary-v1");
        try (ArchiveWriteSession write = backend.begin(job)) {
            for (EpochPending item : group) appendEpochPart(item, job, write);
            ArchiveReceipt receipt = write.commit();
            controlStore.save(new ArchiveProgress(first.dataset(), ArchiveTrack.BACKFILL, first.epoch(),
                    first.boundarySlot(), first.boundaryBlockHash(), receipt.backendGeneration()), receipt);
            invalidateCoverageCache(first.dataset());
            for (EpochPending item : group) acknowledgeEpochPart(item);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void appendEpochPart(EpochPending item, ArchiveJob archiveJob, ArchiveWriteSession write) {
        var binding = item.binding();
        var source = binding.source();
        int pageSize = Math.min(10_000, archiveConfig.worker().maxRowsPerBatch());
        try (var lease = source.acquire(item.job(), java.time.Instant.now().plusSeconds(300))) {
            Optional<String> cursor = Optional.empty();
            do {
                var page = source.read(item.job(), cursor, pageSize, lease);
                List<ArchiveRow> derived = new ArrayList<>();
                binding.projection().derive(archiveJob, page,
                        (java.util.function.Consumer) (value -> derived.add((ArchiveRow) value)));
                if (derived.size() > archiveConfig.worker().maxRowsPerBatch()) {
                    throw new ArchiveStoreException("epoch archive row bound exceeded");
                }
                derived.forEach(write::append);
                cursor = page.nextCursor();
            } while (cursor.isPresent());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void acknowledgeEpochPart(EpochPending item) {
        epochStaging.acknowledge(item.binding(), item.job());
    }

    private record EpochPending(EpochArchiveStagingService.SourceBinding binding, EpochArchiveJob job) { }

    private void initializeEpochActivations() {
        var tip = chain.getLocalTip();
        long currentEpoch = tip == null ? 0 : ledger.slotToEpoch(tip.getSlot());
        for (var entry : archiveConfig.datasets().entrySet()) {
            ArchiveDatasetId dataset = entry.getKey();
            DatasetArchiveConfig selected = entry.getValue();
            if (!selected.enabled() || dataset.sourceKind() != SourceKind.EPOCH) continue;
            OptionalLong existing = activations.start(dataset);
            if (selected.startMode() == ArchiveStartMode.FULL_REQUIRED) {
                if ((existing.isPresent() && existing.getAsLong() != 0)
                        || (existing.isEmpty() && currentEpoch > 0)) {
                    throw new IllegalArgumentException(dataset.logicalName()
                            + " full-required archive must be enabled from epoch 0; current epoch is "
                            + currentEpoch);
                }
                activations.putIfAbsent(dataset, 0);
            } else if (existing.isEmpty()) {
                long start = selected.startMode() == ArchiveStartMode.TIP
                        ? Math.addExact(currentEpoch, 1) : currentEpoch;
                activations.putIfAbsent(dataset, start);
            }
        }
    }

    void promoteLiveRows(ArchiveDatasetId dataset, long finalized) {
        String table = switch (dataset) {
            case TRANSACTION -> "chain_transaction";
            case ACCOUNT_EVENT -> "account_events";
            case ADDRESS_TRANSACTION -> "address_transactions";
            case UTXO_HISTORY -> null; // multiple-table cleanup is handled below
            default -> null;
        };
        List<String> tables = dataset == ArchiveDatasetId.UTXO_HISTORY
                ? com.bloxbean.cardano.yano.archive.api.schema.ArchiveSchemas.schema(dataset).tables().stream()
                        .map(com.bloxbean.cardano.yano.archive.api.schema.ArchiveTableSchema::physicalName).toList()
                : table == null ? List.of() : List.of(table);
        if (tables.isEmpty()) return;
        ArchiveProgress live = controlStore.load(dataset, ArchiveTrack.LIVE).orElse(null);
        if (live == null) return;
        long activation = activations.hotStart(dataset)
                .orElseGet(() -> activations.start(dataset).orElse(live.coordinate() + 1));
        long promotableEnd = Math.min(finalized, live.coordinate());
        if (promotableEnd < activation) return;
        long candidateStart = activation;
        List<ArchiveRange> coverage = datasetCoverage(dataset).completeRanges();
        for (ArchiveRange range : coverage) {
            if (range.endInclusive() < candidateStart) continue;
            if (range.startInclusive() > candidateStart) break;
            candidateStart = Math.addExact(range.endInclusive(), 1);
        }
        if (candidateStart > promotableEnd) {
            cleanupPromotedRows(dataset, tables, coverage, promotableEnd);
            return;
        }
        long promotionStart = candidateStart;
        long nextCovered = coverage.stream().filter(range -> range.startInclusive() > promotionStart)
                .mapToLong(ArchiveRange::startInclusive).min().orElse(Long.MAX_VALUE);
        int defaultPromotionBlocks = Math.min(100, archiveConfig.worker().maxBlocksPerBatch());
        int selectedPromotionBlocks = promotionBatchBlocks.getOrDefault(dataset, defaultPromotionBlocks);
        long candidateEnd = Math.min(promotableEnd, promotionStart + selectedPromotionBlocks - 1L);
        if (nextCovered != Long.MAX_VALUE) candidateEnd = Math.min(candidateEnd, nextCovered - 1);
        long promotionEnd = candidateEnd;
        var first = chain.getCanonicalBlockReference(promotionStart).orElseThrow(() ->
                new ArchiveStoreException("live promotion start is no longer canonical: " + promotionStart));
        var last = chain.getCanonicalBlockReference(promotionEnd).orElseThrow(() ->
                new ArchiveStoreException("live promotion end is no longer canonical: " + promotionEnd));
        ArchiveJob job = ArchiveJob.deterministic(backend.identity().networkIdentity(), dataset,
                com.bloxbean.cardano.yano.archive.api.schema.ArchiveSchemas.schema(dataset).projectionVersion(),
                new BlockRange(promotionStart, promotionEnd),
                new ArchiveRangeAnchor(first.slot(), first.blockHash(), last.slot(), last.blockHash()),
                "hot-promotion-v1");
        try (var snapshot = controlStore.snapshot()) {
            List<byte[]> keys = new ArrayList<>();
            int[] rowCount = {0};
            try (var write = backend.begin(job)) {
                for (String selected : tables) {
                    List<ArchiveRecord> rows = HotArchiveRows.rowsInRange(snapshot, dataset, selected,
                            promotionStart, promotionEnd);
                    keys.addAll(HotArchiveRows.keysInRange(snapshot, dataset, selected,
                            promotionStart, promotionEnd));
                    for (ArchiveRecord row : rows) {
                        if (++rowCount[0] > archiveConfig.worker().maxRowsPerBatch()) {
                            promotionBatchBlocks.put(dataset, Math.max(1, selectedPromotionBlocks / 2));
                            throw new ArchiveStoreException("live promotion row bound exceeded for block "
                                    + promotionStart + ".." + promotionEnd);
                        }
                        write.append(promotionRow(row, job.jobId()));
                    }
                }
                write.commit();
            }
            invalidateCoverageCache(dataset);
            if (!keys.isEmpty()) controlStore.deleteFacts(dataset, keys);
        }
        if (selectedPromotionBlocks < defaultPromotionBlocks) {
            promotionBatchBlocks.put(dataset, Math.min(defaultPromotionBlocks, selectedPromotionBlocks * 2));
        }
        log.debug("Promoted {} live history blocks {}..{} directly from pinned hot rows",
                dataset.logicalName(), promotionStart, promotionEnd);
    }

    private void cleanupPromotedRows(ArchiveDatasetId dataset, List<String> tables,
                                     List<ArchiveRange> coverage, long finalized) {
        try (var snapshot = controlStore.snapshot()) {
            List<byte[]> keys = new ArrayList<>();
            for (String selected : tables) {
                for (ArchiveRange range : coverage) {
                    if (range.startInclusive() > finalized) break;
                    keys.addAll(HotArchiveRows.keysInRange(snapshot, dataset, selected,
                            range.startInclusive(), Math.min(finalized, range.endInclusive())));
                }
            }
            if (!keys.isEmpty()) controlStore.deleteFacts(dataset, keys);
        }
    }

    private static ArchiveRow promotionRow(ArchiveRecord record, UUID jobId) {
        Map<String, Object> values = new LinkedHashMap<>(record.values());
        if (values.containsKey("archive_job_id")) values.put("archive_job_id", jobId);
        return new ArchiveRow(record.table(), new ArrayList<>(values.values()));
    }

    private long activationStart(ArchiveDatasetId dataset, long tip) {
        ArchiveStartMode mode = archiveConfig.datasets().get(dataset).startMode();
        long start = resolveBlockActivationStart(mode, firstCanonicalBlockNumber, tip,
                chain.getEarliestRetainedBodyBlockNumber());
        if (start >= 0) activations.putIfAbsent(dataset, start);
        return start;
    }

    static long resolveBlockActivationStart(ArchiveStartMode mode, long firstCanonicalBlock,
                                            long tip, OptionalLong earliestRetainedBody) {
        if (firstCanonicalBlock < 0) throw new IllegalArgumentException("first canonical block must be non-negative");
        long earliest = earliestRetainedBody.orElse(Long.MAX_VALUE);
        return switch (mode) {
            case FULL_REQUIRED -> {
                if (earliest == Long.MAX_VALUE && tip >= firstCanonicalBlock) {
                    throw new ArchiveStoreException("full-required history cannot start: no retained block bodies "
                            + "at persisted tip " + tip);
                }
                if (earliest != Long.MAX_VALUE && earliest > firstCanonicalBlock) {
                    throw new ArchiveStoreException("full-required history cannot start: earliest retained body is "
                            + earliest + ", expected first canonical block " + firstCanonicalBlock);
                }
                yield firstCanonicalBlock;
            }
            case EARLIEST_AVAILABLE -> earliest == Long.MAX_VALUE
                    ? (tip < 0 ? firstCanonicalBlock : -1) : earliest;
            case TIP -> Math.addExact(tip, 1);
        };
    }

    static long firstCanonicalBlockNumber(boolean hasByronGenesis) {
        // Cardano Byron networks number the first canonical chain block as 1;
        // Shelley-only/devnet chains produced by Yano begin at block 0.
        return hasByronGenesis ? 1 : 0;
    }

    private <B> void registerBlockWorker(ArchiveDatasetId id, BlockArchiveDataset<B> dataset,
                                         BlockArchiveSource<B> source, ArchiveNetworkIdentity network,
                                         ArchiveWorkerConfig workerConfig, CoreSyncView syncView) {
        if (!datasetEnabled(id)) return;
        var worker = new BlockArchiveWorker<>(network, source, backend, controlStore,
                workerConfig, syncView, metrics, Duration.ofMinutes(5), coverageCache);
        catchupWorkers.put(id, (start, end) -> worker.runBatch(dataset, start, end));
    }

    private <B> void registerLiveWorker(ArchiveDatasetId id, BlockArchiveDataset<B> dataset,
                                        BlockArchiveSource<B> source, ArchiveNetworkIdentity network,
                                        ArchiveWorkerConfig workerConfig) {
        if (!datasetEnabled(id)) return;
        var worker = new LiveBlockArchiveWorker<>(network, source, controlStore, workerConfig, metrics);
        liveWorkers.put(id, (start, end) -> worker.runBatch(dataset, start, end));
    }

    private List<SequentialOutpointResolver.Entry> genesisOutpoints(
            NetworkGenesisConfig genesis, AddressTransactionDataset dataset) {
        List<SequentialOutpointResolver.Entry> result = new ArrayList<>();
        LinkedHashSet<String> addresses = new LinkedHashSet<>();
        addresses.addAll(genesis.getInitialFunds().keySet());
        addresses.addAll(genesis.getAllByronBalances().keySet());
        for (String address : addresses) {
            AddressTransactionDataset.AddressParts parts = dataset.address(address);
            byte[] txHash = Blake2bUtil.blake2bHash256(parts.raw());
            result.add(new SequentialOutpointResolver.Entry(new Outpoint(txHash, 0),
                    new ResolvedOutput(parts.addressKey(), parts.displayAddress(), parts.paymentCredential(),
                            parts.stakeCredentialType(), parts.stakeCredential())));
        }
        return List.copyOf(result);
    }

    private List<YaciUtxoHistoryDecoder.GenesisOutput> genesisUtxoOutputs(NetworkGenesisConfig genesis) {
        List<YaciUtxoHistoryDecoder.GenesisOutput> result = new ArrayList<>(
                genesis.getInitialFunds().size() + genesis.getAllByronBalances().size());
        genesis.getInitialFunds().forEach((address, amount) -> result.add(
                new YaciUtxoHistoryDecoder.GenesisOutput(address, amount, "genesis_shelley")));
        genesis.getAllByronBalances().forEach((address, amount) -> result.add(
                new YaciUtxoHistoryDecoder.GenesisOutput(address, amount, "genesis_byron")));
        return List.copyOf(result);
    }

    private void initializeAddressResolver(AddressTransactionDataset dataset) {
        OptionalLong existingActivation = activations.start(ArchiveDatasetId.ADDRESS_TRANSACTION,
                ArchiveTrack.BACKFILL);
        OptionalLong existingBase = dataset.resolverBaseBlock();
        if (existingActivation.isPresent() && dataset.resolverSeeded() && existingBase.isPresent()
                && existingActivation.getAsLong() >= firstCanonicalBlockNumber) {
            long activation = existingActivation.getAsLong();
            long expectedBase = activation - 1;
            if (existingBase.getAsLong() == expectedBase) return;
            if (legacyGenesisBaseCanBeNormalized(
                    archiveConfig.datasets().get(ArchiveDatasetId.ADDRESS_TRANSACTION).startMode(),
                    firstCanonicalBlockNumber, activation, existingBase.getAsLong())) {
                dataset.completeResolverSeed(expectedBase);
                log.info("Normalized address resolver genesis base from {} to {} for activation {}",
                        existingBase.getAsLong(), expectedBase, activation);
                return;
            }
            // Resolver and progress form one logical cursor. If the base is not
            // a known legacy marker, rewind the private progress before
            // rebuilding so a restart can never resume with genesis-only state
            // at a later block.
            controlStore.clearTrack(ArchiveDatasetId.ADDRESS_TRANSACTION, ArchiveTrack.BACKFILL);
            log.warn("Address resolver base {} does not match activation {}; rebuilding private resolver state",
                    existingBase.getAsLong(), activation);
        } else if (existingActivation.isPresent()) {
            controlStore.clearTrack(ArchiveDatasetId.ADDRESS_TRANSACTION, ArchiveTrack.BACKFILL);
        }

        dataset.resetResolver();
        ArchiveStartMode mode = archiveConfig.datasets().get(ArchiveDatasetId.ADDRESS_TRANSACTION).startMode();
        long activation;
        if (mode == ArchiveStartMode.TIP) {
            long currentTip = chain.getLocalTip() == null ? -1 : chain.getLocalTip().getBlockNumber();
            if (tipStartsAtGenesis(currentTip, firstCanonicalBlockNumber)) {
                seedGenesisResolver(dataset, genesisResolverEntries);
                activation = firstCanonicalBlockNumber;
            } else if (ledger.getUtxoState() == null || !ledger.getUtxoState().isEnabled()) {
                throw new ArchiveStoreException("address history start-mode=tip requires core UTXO state");
            } else {
                activation = Math.addExact(seedResolverSnapshot(ledger.getUtxoState(), dataset), 1);
            }
        } else {
            long earliest = chain.getEarliestRetainedBodyBlockNumber().orElse(0);
            if (mode == ArchiveStartMode.EARLIEST_AVAILABLE && earliest > firstCanonicalBlockNumber) {
                throw new ArchiveStoreException("address history earliest-available requires retained bodies "
                        + "from genesis; earliest retained block is " + earliest
                        + ". Use start-mode=tip for an activation-point UTXO snapshot");
            }
            seedGenesisResolver(dataset, genesisResolverEntries);
            activation = firstCanonicalBlockNumber;
        }
        if (existingActivation.isPresent()) {
            activations.replace(ArchiveDatasetId.ADDRESS_TRANSACTION, ArchiveTrack.BACKFILL, activation);
        } else {
            activations.putIfAbsent(ArchiveDatasetId.ADDRESS_TRANSACTION, ArchiveTrack.BACKFILL, activation);
        }
    }

    static boolean tipStartsAtGenesis(long currentTip, long firstCanonicalBlock) {
        return currentTip < firstCanonicalBlock;
    }

    static boolean legacyGenesisBaseCanBeNormalized(ArchiveStartMode mode, long firstCanonicalBlock,
                                                     long activation, long baseBlock) {
        return mode != ArchiveStartMode.TIP
                && firstCanonicalBlock == 1
                && activation == firstCanonicalBlock
                && baseBlock == -1;
    }

    private void seedGenesisResolver(AddressTransactionDataset dataset,
                                     List<SequentialOutpointResolver.Entry> entries) {
        seedGenesisResolver(dataset, entries, firstCanonicalBlockNumber);
    }

    private void seedGenesisResolver(AddressTransactionDataset dataset, long activation) {
        seedGenesisResolver(dataset, genesisResolverEntries, activation);
    }

    private void seedGenesisResolver(AddressTransactionDataset dataset,
                                     List<SequentialOutpointResolver.Entry> entries,
                                     long activation) {
        dataset.seedResolver(entries, false);
        dataset.completeResolverSeed(activation - 1);
    }

    private long seedResolverSnapshot(com.bloxbean.cardano.yano.api.utxo.UtxoState utxos,
                                      AddressTransactionDataset dataset) {
        Path staging = null;
        try {
            Path stagingDirectory = archiveConfig.historyDirectory().resolve("control/resolver-snapshots");
            Files.createDirectories(stagingDirectory);
            staging = Files.createTempFile(stagingDirectory, "live-utxo-", ".snapshot");
            long snapshotBlock;
            try (var output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(staging)))) {
                snapshotBlock = utxos.forEachUtxoRecord(utxo -> {
                    try {
                        output.writeUTF(utxo.outpoint().txHash());
                        output.writeInt(utxo.outpoint().index());
                        output.writeUTF(utxo.address());
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
            if (snapshotBlock < 0) throw new ArchiveStoreException("complete UTXO snapshot point is unavailable");
            var snapshotEra = chain.getBlockEra(snapshotBlock);
            if (snapshotEra == null) {
                throw new ArchiveStoreException("UTXO snapshot era is unavailable at block " + snapshotBlock);
            }

            // Release the core RocksDB snapshot before writing resolver pages
            // to the optional archive RocksDB.
            List<SequentialOutpointResolver.Entry> page = new ArrayList<>(10_000);
            try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(staging)))) {
                while (true) {
                    try {
                        String txHash = input.readUTF();
                        int index = input.readInt();
                        var parts = dataset.address(input.readUTF(), snapshotEra.getValue());
                        page.add(new SequentialOutpointResolver.Entry(
                                new Outpoint(HexUtil.decodeHexString(txHash), index),
                                new ResolvedOutput(parts.addressKey(), parts.displayAddress(),
                                        parts.paymentCredential(), parts.stakeCredentialType(),
                                        parts.stakeCredential())));
                        if (page.size() == 10_000) {
                            dataset.seedResolver(page, false);
                            page.clear();
                        }
                    } catch (EOFException complete) {
                        break;
                    }
                }
            }
            if (!page.isEmpty()) dataset.seedResolver(page, false);
            dataset.completeResolverSeed(snapshotBlock);
            return snapshotBlock;
        } catch (UncheckedIOException e) {
            throw new ArchiveStoreException("cannot materialize the live UTXO resolver snapshot", e.getCause());
        } catch (IOException e) {
            throw new ArchiveStoreException("cannot seed the live UTXO resolver", e);
        } finally {
            if (staging != null) {
                try {
                    Files.deleteIfExists(staging);
                } catch (IOException e) {
                    log.warn("Could not delete resolver snapshot {}: {}", staging, e.toString());
                }
            }
        }
    }

    private Map<ArchiveDatasetId, DatasetArchiveConfig> datasetConfig(ArchiveStartMode defaultStart) {
        EnumMap<ArchiveDatasetId, DatasetArchiveConfig> result = new EnumMap<>(ArchiveDatasetId.class);
        for (ArchiveDatasetId id : ArchiveDatasetId.values()) {
            String name = configName(id);
            boolean defaultEnabled = id == ArchiveDatasetId.ACCOUNT_EVENT;
            boolean enabled = bool("yano.history.datasets." + name + ".enabled", defaultEnabled);
            ArchiveStartMode mode = startMode(string("yano.history.datasets." + name + ".start-mode",
                    defaultStart.name().toLowerCase(Locale.ROOT).replace('_', '-')));
            long retention = longValue("yano.history.datasets." + name + ".retention-epochs", 0);
            Map<String, Boolean> tables = Map.of();
            Map<String, Boolean> subjects = Map.of();
            if (id == ArchiveDatasetId.UTXO_HISTORY) {
                Map<String, Boolean> selected = new LinkedHashMap<>();
                for (UtxoHistoryProjection.Table table : UtxoHistoryProjection.Table.values()) {
                    selected.put(table.physicalName(), bool("yano.history.datasets.utxo-history.tables."
                            + table.configName() + ".enabled", true));
                }
                tables = Map.copyOf(selected);
            }
            if (id == ArchiveDatasetId.ADDRESS_TRANSACTION) {
                Map<String, Boolean> selected = new LinkedHashMap<>();
                selected.put("address", bool(YanoPropertyKeys.History.ADDRESS_SUBJECT_ADDRESS, true));
                selected.put("payment-credential", bool(
                        YanoPropertyKeys.History.ADDRESS_SUBJECT_PAYMENT_CREDENTIAL, true));
                selected.put("stake-credential", bool(
                        YanoPropertyKeys.History.ADDRESS_SUBJECT_STAKE_CREDENTIAL, true));
                subjects = Map.copyOf(selected);
                if (enabled) AddressTransactionSubjects.fromConfig(subjects);
            }
            result.put(id, new DatasetArchiveConfig(enabled, mode, retention, tables, subjects));
        }
        return result;
    }

    private UtxoHistoryProjection resolveUtxoProjection(long persistedTip) {
        DatasetArchiveConfig selected = archiveConfig.datasets().get(ArchiveDatasetId.UTXO_HISTORY);
        OptionalLong existingDatasetStart = activations.start(ArchiveDatasetId.UTXO_HISTORY);
        long datasetStart = existingDatasetStart.orElseGet(() ->
                activationStart(ArchiveDatasetId.UTXO_HISTORY, persistedTip));
        EnumMap<UtxoHistoryProjection.Table, Long> starts =
                new EnumMap<>(UtxoHistoryProjection.Table.class);
        for (UtxoHistoryProjection.Table table : UtxoHistoryProjection.Table.values()) {
            activations.configureTable(ArchiveDatasetId.UTXO_HISTORY, table.physicalName(),
                            selected.tableEnabled(table.physicalName()), existingDatasetStart.isPresent(),
                            datasetStart, persistedTip, firstCanonicalBlockNumber)
                    .ifPresent(start -> starts.put(table, start));
        }
        var projection = new UtxoHistoryProjection(starts);
        log.info("UTXO archive tables: {}", starts.entrySet().stream()
                .map(entry -> entry.getKey().physicalName() + "@" + entry.getValue()).toList());
        return projection;
    }

    private void validateEpochPrerequisites(Map<ArchiveDatasetId, DatasetArchiveConfig> datasets) {
        boolean anyEpoch = datasets.entrySet().stream()
                .anyMatch(entry -> entry.getKey().sourceKind() == SourceKind.EPOCH && entry.getValue().enabled());
        if (anyEpoch && !bool(YanoPropertyKeys.AccountState.ENABLED, false)) {
            throw new IllegalArgumentException("epoch archive datasets require yano.account-state.enabled=true");
        }
        requireDatasetFeature(datasets, ArchiveDatasetId.EPOCH_STAKE,
                YanoPropertyKeys.EpochSnapshot.AMOUNTS_ENABLED);
        requireDatasetFeature(datasets, ArchiveDatasetId.DREP_DISTRIBUTION,
                YanoPropertyKeys.Ledger.GOVERNANCE_ENABLED);
        requireDatasetFeature(datasets, ArchiveDatasetId.ADA_POT,
                YanoPropertyKeys.Ledger.ADAPOT_ENABLED);
        requireDatasetFeature(datasets, ArchiveDatasetId.GOVERNANCE_PROPOSAL_STATUS,
                YanoPropertyKeys.Ledger.GOVERNANCE_ENABLED);
        requireDatasetFeature(datasets, ArchiveDatasetId.REWARD,
                YanoPropertyKeys.Ledger.REWARDS_ENABLED);
    }

    private void requireDatasetFeature(Map<ArchiveDatasetId, DatasetArchiveConfig> datasets,
                                       ArchiveDatasetId dataset, String property) {
        if (datasets.get(dataset).enabled() && !bool(property, false)) {
            throw new IllegalArgumentException(dataset.logicalName() + " archive requires " + property + "=true");
        }
    }

    private Map<String, String> backendProperties(Path directory, ArchiveEngine engine) {
        Map<String, String> properties = new HashMap<>();
        // Ordinary contention is warned about; only the stuck threshold fails a
        // mutation, and it never advances a cursor when it does.
        // Emitted raw: ArchiveWaitPolicy.fromProperties is the one place that
        // parses and validates these, so one operator mistake yields one message.
        properties.put("wait-warn-seconds", string(
                YanoPropertyKeys.History.ARCHIVE_WAIT_WARN_SECONDS, "30"));
        properties.put("stuck-operation-seconds", string(
                YanoPropertyKeys.History.ARCHIVE_STUCK_OPERATION_SECONDS, "300"));
        if (engine == ArchiveEngine.SQLITE) {
            properties.put("database.path", string("yano.history.archive.sqlite.path",
                    directory.resolve("history.sqlite").toString()));
        } else {
            properties.put("catalog.path", string("yano.history.archive.ducklake.catalog.path",
                    directory.resolve("ducklake-catalog.sqlite").toString()));
            properties.put("data.path", string("yano.history.archive.ducklake.data-path",
                    directory.resolve("ducklake-data").toString()));
            properties.put("temp.path", string("yano.history.duckdb.temp-directory",
                    directory.resolve("tmp").toString()));
            properties.put("extensions.path", string("yano.history.archive.ducklake.extensions-path",
                    directory.resolve("extensions").toString()));
            properties.put("target-file-size-bytes", Long.toString(sizeBytes(string(
                    YanoPropertyKeys.History.DUCKLAKE_TARGET_FILE_SIZE, "4MB"))));
            properties.put("row-group-size", Integer.toString(intValue(
                    YanoPropertyKeys.History.DUCKLAKE_ROW_GROUP_SIZE, 100_000)));
            properties.put("snapshot-retention-hours", Long.toString(longValue(
                    YanoPropertyKeys.History.DUCKLAKE_SNAPSHOT_RETENTION_HOURS, 168)));
            properties.put("cleanup-grace-hours", Long.toString(longValue(
                    YanoPropertyKeys.History.DUCKLAKE_CLEANUP_GRACE_HOURS, 24)));
            properties.put("duckdb.max-total-memory-bytes", Long.toString(sizeBytes(
                    string(YanoPropertyKeys.History.DUCKDB_MAX_TOTAL_MEMORY, "256MB"))));
            properties.put("duckdb.max-concurrent-queries", Integer.toString(intValue(
                    YanoPropertyKeys.History.DUCKDB_MAX_CONCURRENT_QUERIES, 2)));
            properties.put("duckdb.max-temp-directory-bytes", Long.toString(sizeBytes(
                    string(YanoPropertyKeys.History.DUCKDB_MAX_TEMP_SIZE, "2GB"))));
            properties.put("duckdb.steady-memory-bytes", Long.toString(sizeBytes(
                    string(YanoPropertyKeys.History.DUCKDB_STEADY_MEMORY, "128MB"))));
            properties.put("duckdb.steady-threads", Integer.toString(intValue(
                    YanoPropertyKeys.History.DUCKDB_STEADY_THREADS, 1)));
            properties.put("duckdb.bulk-memory-bytes", Long.toString(sizeBytes(
                    string(YanoPropertyKeys.History.DUCKDB_BULK_MEMORY, "128MB"))));
            properties.put("duckdb.bulk-threads", Integer.toString(intValue(
                    YanoPropertyKeys.History.DUCKDB_BULK_THREADS, 1)));
            properties.put("duckdb.max-concurrent-bulk-jobs", Integer.toString(intValue(
                    YanoPropertyKeys.History.DUCKDB_BULK_JOBS, 1)));
        }
        return Map.copyOf(properties);
    }

    private String genesisHash(YanoConfig nodeConfig) throws Exception {
        if (nodeConfig.getShelleyGenesisHash() != null && !nodeConfig.getShelleyGenesisHash().isBlank()) {
            return nodeConfig.getShelleyGenesisHash().toLowerCase(Locale.ROOT);
        }
        if (nodeConfig.getShelleyGenesisFile() == null || nodeConfig.getShelleyGenesisFile().isBlank()) {
            throw new IllegalArgumentException("Shelley genesis hash/file is required for archive identity");
        }
        return HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(
                Files.readAllBytes(Path.of(nodeConfig.getShelleyGenesisFile()))));
    }

    private void rejectRemovedConfiguration() {
        boolean removedSnapshot = false;
        for (String name : config.getPropertyNames()) {
            if (name.startsWith(SNAPSHOT_PREFIX)
                    && config.getOptionalValue(name, String.class).filter(value -> !value.isBlank()).isPresent()) {
                removedSnapshot = true;
            }
        }
        if (removedSnapshot) {
            throw new IllegalArgumentException("yano.snapshot-export.* was removed; enable the equivalent "
                    + "yano.history.datasets.* dataset instead");
        }
        if (config.getOptionalValue(REMOVED_LIVE_ENABLED, String.class).isPresent()) {
            throw new IllegalArgumentException("yano.history.live-enabled was removed; history now has one "
                    + "sequential catching_up-to-live lifecycle");
        }
        if (configuredEnabled && bool(LEGACY_HISTORY_ENABLED, false)) {
            throw new IllegalArgumentException("yano.account-history.enabled was removed; use yano.history.enabled "
                    + "and per-dataset yano.history.datasets.* settings");
        }
    }

    private boolean datasetEnabled(ArchiveDatasetId dataset) {
        return archiveConfig != null && archiveConfig.datasets().get(dataset).enabled();
    }

    private static String configName(ArchiveDatasetId id) {
        return switch (id) {
            case ACCOUNT_EVENT -> "account-events";
            case ADDRESS_TRANSACTION -> "address-transactions";
            case TRANSACTION -> "transactions";
            case UTXO_HISTORY -> "utxo-history";
            case REWARD -> "rewards";
            case EPOCH_STAKE -> "epoch-stake";
            case DREP_DISTRIBUTION -> "drep-distribution";
            case ADA_POT -> "ada-pots";
            case GOVERNANCE_PROPOSAL_STATUS -> "governance-proposal-status";
        };
    }

    private boolean bool(String name, boolean fallback) {
        return config.getOptionalValue(name, Boolean.class).orElse(fallback);
    }
    private String string(String name, String fallback) {
        return config.getOptionalValue(name, String.class).orElse(fallback);
    }
    private long longValue(String name, long fallback) {
        return config.getOptionalValue(name, Long.class).orElse(fallback);
    }
    private int intValue(String name, int fallback) {
        return config.getOptionalValue(name, Integer.class).orElse(fallback);
    }
    static int resolveProjectionParallelism(String configured, int processors, int enabledProjections) {
        Objects.requireNonNull(configured, "configured");
        if (processors < 1 || enabledProjections < 1) {
            throw new IllegalArgumentException("processor and projection counts must be positive");
        }
        String value = configured.trim();
        if (value.equalsIgnoreCase("auto")) {
            return ArchiveWorkerConfig.automaticProjectionParallelism(processors, enabledProjections);
        }
        int requested;
        try {
            requested = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("history projection parallelism must be auto or a positive integer", e);
        }
        if (requested < 1) {
            throw new IllegalArgumentException("history projection parallelism must be positive");
        }
        return Math.min(requested, enabledProjections);
    }
    private Long autoLong(String name) {
        String value = string(name, "auto").trim();
        return value.equalsIgnoreCase("auto") ? null : Long.parseLong(value);
    }
    private static ArchiveStartMode startMode(String value) {
        return ArchiveStartMode.valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    }
    private <T extends Enum<T>> T enumValue(String name, String fallback, Class<T> type) {
        String value = config.getOptionalValue(name, String.class).orElse(fallback);
        return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    }
    static long sizeBytes(String input) {
        String value = input.trim().toUpperCase(Locale.ROOT).replace("IB", "B");
        long multiplier = 1;
        if (value.endsWith("KB")) { multiplier = 1024L; value = value.substring(0, value.length() - 2); }
        else if (value.endsWith("MB")) { multiplier = 1024L * 1024; value = value.substring(0, value.length() - 2); }
        else if (value.endsWith("GB")) { multiplier = 1024L * 1024 * 1024; value = value.substring(0, value.length() - 2); }
        else if (value.endsWith("B")) value = value.substring(0, value.length() - 1);
        return Math.multiplyExact(Long.parseLong(value.trim()), multiplier);
    }
    private HotHistoryStore openHotStore(String engine, Path path) {
        if (engine.equals("rocksdb")) return new RocksDbHotHistoryStore(path);
        HotHistoryStoreProvider provider = ServiceLoader.load(HotHistoryStoreProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(candidate -> candidate.engine().equals(engine))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "history hot-store provider not packaged for engine " + engine));
        return provider.open(path, Map.of());
    }
    private static UUID stableArchiveId(int magic, String genesis, String engine, Path directory) {
        return UUID.nameUUIDFromBytes((magic + "|" + genesis + "|" + engine + "|" + directory)
                .getBytes(StandardCharsets.UTF_8));
    }
    private static boolean isNativeImage() {
        return System.getProperty("org.graalvm.nativeimage.imagecode") != null;
    }

    @Override
    public synchronized void close() {
        lifecycleLock.writeLock().lock();
        try {
            closePartial();
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    private void closePartial() {
        // Join the optional worker before destroying anything it touches.
        // shutdownNow alone permits in-flight RocksDB/DuckDB calls to race native
        // handle destruction during graceful application shutdown.
        // Projections first. The cycle thread joins their futures and ignores its
        // own interrupt while doing so, so joining the scheduler first would time
        // out behind a projection parked on a long resource wait -- and leave the
        // backend, hot store, and process lock open for the single close call.
        boolean projectionsStopped = true;
        ExecutorService selectedProjectionExecutor = projectionExecutor;
        if (selectedProjectionExecutor != null) {
            selectedProjectionExecutor.shutdownNow();
            try {
                if (selectedProjectionExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    projectionExecutor = null;
                } else {
                    projectionsStopped = false;
                    log.warn("Archive projection executor did not stop within 30 seconds");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                projectionsStopped = false;
                log.warn("Interrupted while stopping archive projection executor");
            }
        }
        boolean workerStopped = (subsystem == null || subsystem.stop()) && projectionsStopped;
        if (subsystem != null && subsystem.terminated()) subsystem = null;

        // Detaching core from the archive is safe either way, and it is exactly
        // what we want when the subsystem is being abandoned: core pruning and
        // epoch staging must stop depending on stores we may not be able to close.
        if (ledger != null) try { ledger.setEpochArchiveStagingSink(
                com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink.NOOP); } catch (Exception ignored) { }
        if (chain != null) try { chain.setBlockBodyRetentionBoundary(
                com.bloxbean.cardano.yano.api.BlockBodyRetentionBoundary.NONE); } catch (Exception ignored) { }
        epochStaging = null;
        // Closing the archive backend is deferred by design: it fails new
        // mutations cleanly and destroys native handles only once every session
        // is idle, so it is safe even while a worker is still running.
        if (workerStopped) {
            // Closing the backend is deferred-safe, but nulling the field while a
            // live cycle still dereferences it turns a graceful stop into a burst
            // of NPE-driven dataset failures.
            if (backend != null) try { backend.close(); } catch (Exception ignored) { }
            backend = null;
        }

        if (!workerStopped) {
            // The hot store closes its native handles immediately, and the
            // worker still owns the decoded-block sources and dataset maps.
            // Leaking them is recoverable; destroying them underneath a live JNI
            // call is not. Leave them referenced so a later close can retry.
            shutdownError = "archive worker did not terminate; hot store and worker state left open "
                    + "to avoid closing native handles under a running task";
            log.error("Archive worker did not terminate within the shutdown budget; leaving the hot store "
                    + "open rather than racing native handle destruction. Retry close once it exits.");
            return;
        }

        catchupAddressDataset = null;
        genesisResolverEntries = List.of();
        sharedFactSource = null;
        sharedBlockSource = null;
        coverageCache = null;
        if (controlStore != null) try { controlStore.close(); } catch (Exception ignored) { }
        controlStore = null;
        catchupWorkers.clear();
        liveWorkers.clear();
        appliedRetention.clear();
        shutdownError = null;
        cycleError = null;
        stageErrors.clear();
        nextMaintenanceNanos.set(Long.MAX_VALUE);
    }

    public record TransactionLookup(State state, ArchiveRecord row, String detail) {
        public enum State { FOUND, NOT_FOUND, INCOMPLETE, UNAVAILABLE }
        public static TransactionLookup found(ArchiveRecord row) { return new TransactionLookup(State.FOUND, row, ""); }
        public static TransactionLookup notFound() { return new TransactionLookup(State.NOT_FOUND, null, ""); }
        public static TransactionLookup incomplete(String detail) { return new TransactionLookup(State.INCOMPLETE, null, detail); }
        public static TransactionLookup unavailable(String detail) { return new TransactionLookup(State.UNAVAILABLE, null, detail); }
    }

    @FunctionalInterface
    private interface DatasetRunner { long run(long start, long finalizedEnd); }

    @FunctionalInterface
    interface QueryLease extends AutoCloseable {
        @Override void close();
    }
}
