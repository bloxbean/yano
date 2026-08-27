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
import com.bloxbean.cardano.yano.archive.core.hot.HotHistorySnapshot;
import com.bloxbean.cardano.yano.archive.core.source.EpochArchiveJob;
import com.bloxbean.cardano.yano.archive.core.source.YaciBlockArchiveDecoder;
import com.bloxbean.cardano.yano.archive.core.source.YaciBlockDecoder;
import com.bloxbean.cardano.yano.archive.core.source.YaciUtxoHistoryDecoder;
import com.bloxbean.cardano.yano.archive.core.dataset.UtxoHistoryFact;
import com.bloxbean.cardano.yano.archive.core.dataset.BlockSourceContext;
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

    public HistoryArchiveService(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }
    private volatile boolean configuredEnabled;
    private volatile String initializationError;
    private volatile ArchiveBackend backend;
    private volatile String hotStoreEngine = "rocksdb";
    private volatile ExecutorService projectionExecutor;
    // ADR-038 Phase 2c: owned by this service so shutdown is deterministic and no
    // pool is created per batch. Null unless UTXO prefetch is explicitly enabled.
    private volatile String projectionParallelismSetting = "auto";
    private final AtomicLong decodedBlockCount = new AtomicLong();
    private final AtomicLong decodedBlockCacheHits = new AtomicLong();
    private volatile String shutdownError;
    private final Map<String, String> stageErrors = new ConcurrentHashMap<>();
    private volatile ChainQuery chain;
    private volatile LedgerQuery ledger;
    private volatile AddressTransactionDataset catchupAddressDataset;
    private volatile List<SequentialOutpointResolver.Entry> genesisResolverEntries = List.of();
    private volatile long firstCanonicalBlockNumber;
    private final AccountHistoryProvider archiveAccountHistory = new ArchiveAccountHistoryProvider(this);
    private volatile java.util.function.Consumer<ArchiveDatasetId> epochCoverageGuard = ignored -> { };
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


    /**
     * ADR-039 removed the replay-worker archive; this refuses its configuration.
     *
     * <p>{@code yano.history.enabled=true} selected a second archival write path that no longer
     * exists. Ignoring it silently would leave an operator believing history was being written
     * when nothing was. Historical reads come from the projection archive via
     * {@link #initializeProjectionReads}.
     */
    public synchronized void initialize(ChainQuery chain, LedgerQuery ledger, YanoConfig nodeConfig) {
        this.chain = Objects.requireNonNull(chain, "chain");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        rejectRemovedConfiguration();
        if (bool(YanoPropertyKeys.History.ENABLED, false)) {
            throw new IllegalArgumentException("yano.history.enabled is no longer supported:"
                    + " the replay-worker archive was removed in ADR-039 Phase 7a. History is"
                    + " written by the canonical projection outbox instead - set"
                    + " yano.history.projection.enabled=true and resync from genesis.");
        }
    }


    /**
     * Open a read-only backend over an archive the projection wrote.
     *
     * <p>Called only when the legacy writer is disabled. It opens the backend and nothing else -
     * no workers, no staging, no activations, no retention boundaries - because none of those
     * describe how this archive was produced. The row tables are physically the same, so every
     * existing repository query works unchanged once a backend exists to read through.
     *
     * @param covered the datasets the projection maintains; nothing outside this may be served
     * @param committedThrough greatest durably committed block, or -1 before the first batch
     */
    public synchronized void initializeProjectionReads(YanoConfig nodeConfig,
                                                       ArchiveIdentity identity,
                                                       Set<ArchiveDatasetId> covered,
                                                       java.util.function.LongSupplier committedThrough) {
        if (configuredEnabled || backend != null) return;
        try {
            ArchiveEngine engine = enumValue(YanoPropertyKeys.History.ENGINE, "ducklake", ArchiveEngine.class);
            String engineName = engine.name().toLowerCase(Locale.ROOT);
            Path directory = Path.of(string(YanoPropertyKeys.History.DIR, "./history"))
                    .toAbsolutePath().normalize();

            // The caller supplies the identity the sink was opened with. Recomputing it the legacy
            // way - from network, genesis, engine and directory - yields a different archiveId and
            // the backend refuses the archive it is meant to read.
            ArchiveBackendProvider provider = ServiceLoader.load(ArchiveBackendProvider.class).stream()
                    .map(ServiceLoader.Provider::get)
                    .filter(candidate -> candidate.engine().equals(engineName))
                    .findFirst().orElseThrow(() -> new IllegalStateException(
                            "archive backend provider not packaged for engine " + engineName));

            Map<String, String> properties = new HashMap<>(backendProperties(directory, engine));
            properties.put("projection.read-mode", "true");
            backend = provider.open(identity, directory, Map.copyOf(properties));
            projectionDatasets = Set.copyOf(covered);
            projectionCommittedThrough = committedThrough;
            projectionBackedReads = true;
            log.info("ADR-039 historical reads routed to the projection archive ({} datasets: {})",
                    covered.size(), covered.stream().map(ArchiveDatasetId::logicalName).sorted().toList());
        } catch (IllegalArgumentException configuration) {
            // A rejected setting is not an archive that would not open, and saying so sends the
            // operator to the wrong place: they go looking at the archive directory rather than
            // at the line they just changed. Propagated unwrapped so the message that names the
            // offending key stays the message they see.
            throw configuration;
        } catch (Exception e) {
            // Fail loudly rather than silently answering "history disabled" over a full archive.
            throw new IllegalStateException("could not open the projection archive for reading", e);
        }
    }

    public synchronized void initializeProjectionReads(YanoConfig nodeConfig,
                                                       ArchiveIdentity identity,
                                                       Set<ArchiveDatasetId> covered,
                                                       java.util.function.LongSupplier committedThrough,
                                                       java.util.function.Consumer<ArchiveDatasetId> epochCoverageGuard) {
        this.epochCoverageGuard = Objects.requireNonNull(epochCoverageGuard, "epochCoverageGuard");
        initializeProjectionReads(nodeConfig, identity, covered, committedThrough);
    }

    void requireCompleteEpochHistory(ArchiveDatasetId dataset) {
        if (dataset.sourceKind() == SourceKind.EPOCH) epochCoverageGuard.accept(dataset);
    }

    /**
     * Reads are served from an archive the projection wrote, with no legacy writer running.
     *
     * <p>Phase 6 requires historical queries to be routed to the primary archive. The row tables
     * are shared, so the query code needs no change - but the read path's <em>lifecycle</em> was
     * owned by this service, and it returns early when the legacy writer is disabled. That left
     * every address-transaction query answering "history disabled" over an archive holding
     * 39 million of those rows, which is the false absence the ADR forbids.
     */
    private volatile boolean projectionBackedReads;

    /** Datasets the projection actually covers; nothing else may be served. */
    private volatile Set<ArchiveDatasetId> projectionDatasets = Set.of();

    /** Greatest block the sink has committed, or -1 before the first batch. */
    private volatile java.util.function.LongSupplier projectionCommittedThrough = () -> -1L;

    public boolean enabled() {
        return configuredEnabled || projectionBackedReads;
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

    /**
     * Whether a dataset can be answered from this archive.
     *
     * <p>The replay worker's "operational" and "live phase" described backfill progress that no
     * longer exists. What makes a dataset answerable now is that the projection maintains it and
     * has durably committed at least one range.
     */
    boolean datasetAvailable(ArchiveDatasetId dataset) {
        return available() && projectionDatasets.contains(dataset)
                && projectionCommittedThrough.getAsLong() >= 0;
    }


    /** True when a configured dataset is parked by a non-retryable failure. */
    /**
     * Always false: there is no parked-dataset state.
     *
     * <p>A dataset was parked when its replay worker hit a non-retryable failure. The projection
     * has no per-dataset worker - a contributor failure stops the whole drain and surfaces as
     * sink health, rather than one dataset going quietly unavailable.
     */
    public boolean datasetFailed(ArchiveDatasetId dataset) {
        return false;
    }

    /** True while a configured block dataset is intentionally unavailable during catch-up. */
    /**
     * Always false: there is no catch-up phase.
     *
     * <p>Datasets were "building" while a worker backfilled behind the tip. The projection commits
     * every required section for a range in one transaction, so a range is committed or it is not.
     */
    public boolean datasetBuilding(ArchiveDatasetId dataset) {
        return false;
    }

    QueryLease openQueryLease() {
        lifecycleLock.readLock().lock();
        return lifecycleLock.readLock()::unlock;
    }

    /** Block datasets this archive maintains. */
    public Set<ArchiveDatasetId> enabledBlockDatasets() {
        return projectionDatasets.stream()
                .filter(dataset -> dataset.sourceKind() == SourceKind.BLOCK)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
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

    /** No hot store, so there are never rows to merge ahead of the archive. */
    List<ArchiveRecord> hotRecords(ArchiveDatasetId dataset, String table, Map<String, Object> filters) {
        return List.of();
    }


    /**
     * No live track, so nothing is covered beyond the committed archive.
     *
     * <p>Hot-history buffered rows ahead of the durable archive. Rows now become visible exactly
     * when their range commits; anything above that is "not yet", which /history/coverage reports.
     */
    Optional<BlockRange> liveCoverage(ArchiveDatasetId dataset) {
        return Optional.empty();
    }

    /**
     * Look one transaction up by hash.
     *
     * <p>A full scan of the transactions table: the replay worker's SQLite locator is not built
     * for projection archives, which /history/coverage reports honestly as
     * {@code transactionHashLookup.mode = full-scan}. Correct, but not a hot path until a derived
     * index exists.
     */
    public TransactionLookup findTransaction(byte[] txHash) {
        ArchiveBackend current = backend;
        if (current == null || !available()) {
            return TransactionLookup.unavailable("history archive is unavailable");
        }
        if (!datasetAvailable(ArchiveDatasetId.TRANSACTION)) {
            return TransactionLookup.unavailable("transaction history is not maintained by this archive");
        }
        try (QueryLease lease = openQueryLease();
             ArchiveReadSession session = current.openReadSession()) {
            return current.findTransaction(session, txHash)
                    .map(TransactionLookup::found)
                    .orElseGet(TransactionLookup::notFound);
        } catch (ArchiveStoreException e) {
            return TransactionLookup.unavailable(e.getMessage());
        }
    }



    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", enabled());
        status.put("available", available());
        if (projectionBackedReads) {
            status.put("source", "projection");
            status.put("datasets", projectionDatasets.stream()
                    .map(ArchiveDatasetId::logicalName).sorted().toList());
            status.put("committedThroughBlock", projectionCommittedThrough.getAsLong());
        }
        return status;
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







    static OptionalLong handoffBaseline(long activation, long finalized,
                                        ArchiveProgress catchup, ArchiveCoverage coverage) {
        if (activation < 0) throw new IllegalArgumentException("activation must not be negative");
        if (catchup.coordinate() < finalized) return OptionalLong.empty();
        return OptionalLong.of(catchup.coordinate());
    }




    static boolean shouldReanchorLive(long localBlock, long targetBlock, long liveBlock,
                                      long maximumCoreLag, long rollbackRetentionBlocks) {
        if (localBlock < 0 || targetBlock < 0 || liveBlock < -1
                || maximumCoreLag < 0 || rollbackRetentionBlocks < 1) return false;
        long coreLag = Math.max(0, targetBlock - localBlock);
        long liveLag = Math.max(0, localBlock - liveBlock);
        return coreLag <= maximumCoreLag && liveLag > rollbackRetentionBlocks;
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
        if (targetBlock == activation - 1) return LiveRollbackAction.RESET_TO_ACTIVATION;
        return LiveRollbackAction.EXACT_ROLLBACK;
    }
















    private record EpochPending(EpochArchiveStagingService.SourceBinding binding, EpochArchiveJob job) { }










    private List<SequentialOutpointResolver.Entry> genesisOutpoints(
            NetworkGenesisConfig genesis, AddressTransactionDataset dataset) {
        List<SequentialOutpointResolver.Entry> result = new ArrayList<>();
        LinkedHashSet<String> addresses = new LinkedHashSet<>();
        addresses.addAll(genesis.getInitialFunds().keySet());
        addresses.addAll(genesis.getAllByronBalances().keySet());
        return List.copyOf(result);
    }




    static boolean legacyGenesisBaseCanBeNormalized(ArchiveStartMode mode, long firstCanonicalBlock,
                                                     long activation, long baseBlock) {
        return mode != ArchiveStartMode.TIP
                && firstCanonicalBlock == 1
                && activation == firstCanonicalBlock
                && baseBlock == -1;
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
            throw new IllegalArgumentException("yano.snapshot-export.* was removed; set "
                    + "yano.history.projection.enabled=true instead. Epoch artifacts are not "
                    + "selectable - every one of them ships with the projection archive");
        }
        if (config.getOptionalValue(REMOVED_LIVE_ENABLED, String.class).isPresent()) {
            throw new IllegalArgumentException("yano.history.live-enabled was removed; history now has one "
                    + "sequential catching_up-to-live lifecycle");
        }
        if (bool(LEGACY_HISTORY_ENABLED, false)) {
            throw new IllegalArgumentException("yano.account-history.enabled was removed; history is"
                    + " written by the canonical projection outbox - set"
                    + " yano.history.projection.enabled=true");
        }

        // ADR-039 Phase 7a removed the replay-worker pipeline. Its tuning keys no longer do
        // anything, and silently ignoring them would let an operator believe they had configured
        // backfill parallelism, prefetch or a hot store that does not exist.
        for (String name : config.getPropertyNames()) {
            for (String removed : REMOVED_WORKER_PREFIXES) {
                if (name.startsWith(removed)
                        && config.getOptionalValue(name, String.class)
                                .filter(value -> !value.isBlank()).isPresent()) {
                    throw new IllegalArgumentException(name + " was removed in ADR-039 Phase 7a"
                            + " along with the replay-worker archive. The projection outbox has no"
                            + " backfill workers, prefetch or hot store to tune; remove this setting.");
                }
            }
        }
    }

    /**
     * Configuration prefixes that Phase 7a removed.
     *
     * <p>Rejected rather than ignored: a stale {@code worker.projection-parallelism} in a
     * deployment file reads as "this is tuned" when nothing consumes it.
     */
    private static final List<String> REMOVED_WORKER_PREFIXES = List.of(
            "yano.history.worker.",
            "yano.history.hot-store.",
            "yano.history.start-mode",
            "yano.history.datasets.",
            // Read by nothing since Phase 7a, and unguarded until now - which made these the
            // worst of the removed keys rather than the mildest. They read as the knobs that
            // bound archive upkeep, so an operator capping a rewrite budget to keep compaction
            // out of a maintenance window got no effect and no warning. The projection schedules
            // its own maintenance and never consulted them.
            "yano.history.maintenance.",
            // The SQLite archive module is gone; DuckLake is the only engine. This key named a
            // database file for a backend that no longer exists.
            "yano.history.archive.sqlite.");







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
    private Long autoLong(String name) {
        String value = string(name, "auto").trim();
        return value.equalsIgnoreCase("auto") ? null : Long.parseLong(value);
    }
    private <T extends Enum<T>> T enumValue(String name, String fallback, Class<T> type) {
        return parseEnum(name, config.getOptionalValue(name, String.class).orElse(fallback), type);
    }

    /**
     * Parse a configured enum value, naming the alternatives when it is not one.
     *
     * <p>Kept separate from {@link #enumValue} and free of any Config or backend dependency, so
     * the rule can be tested for what it accepts as directly as for what it rejects. Proving
     * acceptance through {@code initializeProjectionReads} would mean opening a real archive and
     * asserting on some later, unrelated failure - a test that passes for the wrong reason and
     * writes a directory into the source tree on the way.
     *
     * <p>{@code Enum.valueOf} alone reports "No enum constant com.bloxbean...ArchiveEngine.SQLITE",
     * which names a Java class and leaves the operator to work out what is allowed. sqlite was a
     * real engine until its store module was removed, so it is a value carried forward from
     * older deployment files rather than a typo.
     */
    static <T extends Enum<T>> T parseEnum(String name, String value, Class<T> type) {
        String trimmed = value.trim();
        try {
            return Enum.valueOf(type, trimmed.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(name + "=" + trimmed + " is not supported;"
                    + " valid values are "
                    + java.util.Arrays.stream(type.getEnumConstants())
                            .map(constant -> constant.name().toLowerCase(Locale.ROOT).replace('_', '-'))
                            .sorted().toList());
        }
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
            ArchiveBackend current = backend;
            backend = null;
            projectionBackedReads = false;
            if (current != null) {
                try { current.close(); } catch (RuntimeException e) {
                    log.warn("closing the projection archive backend failed: {}", e.toString());
                }
            }
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }



    @FunctionalInterface
    private interface DatasetRunner { long run(long start, long finalizedEnd); }

    @FunctionalInterface
    interface QueryLease extends AutoCloseable {
        @Override void close();
    }
    /** Outcome of a transaction-hash lookup against the archive. */
    public record TransactionLookup(State state, ArchiveRecord row, String detail) {
        public enum State { FOUND, NOT_FOUND, INCOMPLETE, UNAVAILABLE }
        public static TransactionLookup found(ArchiveRecord row) { return new TransactionLookup(State.FOUND, row, ""); }
        public static TransactionLookup notFound() { return new TransactionLookup(State.NOT_FOUND, null, ""); }
        public static TransactionLookup incomplete(String detail) { return new TransactionLookup(State.INCOMPLETE, null, detail); }
        public static TransactionLookup unavailable(String detail) { return new TransactionLookup(State.UNAVAILABLE, null, detail); }
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

}
