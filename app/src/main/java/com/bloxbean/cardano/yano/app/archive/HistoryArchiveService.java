package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.yano.api.account.AccountHistoryProvider;
import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import com.bloxbean.cardano.yano.archive.api.ArchiveBackend;
import com.bloxbean.cardano.yano.archive.api.ArchiveBackendProvider;
import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveHealth;
import com.bloxbean.cardano.yano.archive.api.ArchiveIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveReadSession;
import com.bloxbean.cardano.yano.archive.api.ArchiveRecord;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;
import com.bloxbean.cardano.yano.archive.api.SourceKind;
import com.bloxbean.cardano.yano.archive.core.config.ArchiveEngine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/** Read-only facade over the archive maintained by {@link ProjectionHistoryService}. */
@ApplicationScoped
public class HistoryArchiveService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HistoryArchiveService.class);
    private static final String REMOVED_HISTORY_ENABLED = "yano.history.enabled";
    private static final String REMOVED_ACCOUNT_HISTORY_ENABLED = "yano.account-history.enabled";
    private static final String REMOVED_LIVE_ENABLED = "yano.history.live-enabled";
    private static final String SNAPSHOT_PREFIX = "yano.snapshot-export.";

    private static final List<String> REMOVED_WORKER_PREFIXES = List.of(
            "yano.history.worker.",
            "yano.history.hot-store.",
            "yano.history.start-mode",
            "yano.history.datasets.",
            "yano.history.maintenance.",
            "yano.history.archive.sqlite.");

    private final Config config;
    private final AccountHistoryProvider accountHistory = new ArchiveAccountHistoryProvider(this);
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock(true);

    private volatile ArchiveBackend backend;
    private volatile boolean projectionBackedReads;
    private volatile Set<ArchiveDatasetId> projectionDatasets = Set.of();
    private volatile LongSupplier projectionCommittedThrough = () -> -1L;
    private volatile Consumer<ArchiveDatasetId> epochCoverageGuard = ignored -> { };
    private volatile String initializationError;

    @Inject
    public HistoryArchiveService(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /** Validate removed history configuration before projection initialization begins. */
    public synchronized void initialize() {
        try {
            rejectRemovedConfiguration();
            initializationError = null;
        } catch (RuntimeException e) {
            initializationError = message(e);
            throw e;
        }
    }

    /** Open the projection archive through its backend-neutral read facade. */
    public synchronized void initializeProjectionReads(
            ArchiveIdentity identity,
            Set<ArchiveDatasetId> covered,
            LongSupplier committedThrough,
            Consumer<ArchiveDatasetId> epochCoverageGuard) {
        if (backend != null) {
            return;
        }
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(covered, "covered");
        Objects.requireNonNull(committedThrough, "committedThrough");
        Objects.requireNonNull(epochCoverageGuard, "epochCoverageGuard");
        try {
            ArchiveEngine engine = parseEnum(YanoPropertyKeys.History.ENGINE,
                    string(YanoPropertyKeys.History.ENGINE, "ducklake"), ArchiveEngine.class);
            String engineName = engine.name().toLowerCase(Locale.ROOT);
            Path directory = Path.of(string(YanoPropertyKeys.History.DIR, "./history"))
                    .toAbsolutePath().normalize();
            ArchiveBackendProvider provider = ServiceLoader.load(ArchiveBackendProvider.class).stream()
                    .map(ServiceLoader.Provider::get)
                    .filter(candidate -> candidate.engine().equals(engineName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "archive backend provider not packaged for engine " + engineName));

            Map<String, String> properties = new HashMap<>(backendProperties(directory));
            backend = provider.open(identity, directory, Map.copyOf(properties));
            projectionDatasets = Set.copyOf(covered);
            projectionCommittedThrough = committedThrough;
            this.epochCoverageGuard = epochCoverageGuard;
            projectionBackedReads = true;
            initializationError = null;
            log.info("Historical reads routed to projection archive ({} datasets: {})",
                    covered.size(), covered.stream()
                            .map(ArchiveDatasetId::logicalName).sorted().toList());
        } catch (IllegalArgumentException e) {
            initializationError = message(e);
            throw e;
        } catch (Exception e) {
            initializationError = message(e);
            throw new IllegalStateException("could not open projection archive for reading", e);
        }
    }

    public boolean hasInitializationFailure() {
        return initializationError != null;
    }

    public boolean enabled() {
        return projectionBackedReads;
    }

    public boolean available() {
        ArchiveBackend current = backend;
        if (current == null) {
            return false;
        }
        ArchiveHealth.Status status = current.health().status();
        return status != ArchiveHealth.Status.UNHEALTHY && status != ArchiveHealth.Status.CLOSED;
    }

    public Optional<ArchiveBackend> backend() {
        return Optional.ofNullable(backend);
    }

    public AccountHistoryProvider accountHistoryProvider() {
        return accountHistory;
    }

    boolean datasetAvailable(ArchiveDatasetId dataset) {
        return available() && projectionDatasets.contains(dataset)
                && projectionCommittedThrough.getAsLong() >= 0;
    }

    void requireDatasetReady(ArchiveDatasetId dataset) {
        if (!projectionDatasets.contains(dataset)) {
            throw new IllegalStateException(
                    "history dataset is not selected: " + dataset.logicalName());
        }
        if (!available()) {
            throw new IllegalStateException("history archive is unavailable");
        }
        if (projectionCommittedThrough.getAsLong() < 0) {
            throw new IllegalStateException(
                    "history dataset has no committed projection range: " + dataset.logicalName());
        }
    }

    void requireCompleteEpochHistory(ArchiveDatasetId dataset) {
        if (dataset.sourceKind() == SourceKind.EPOCH) {
            epochCoverageGuard.accept(dataset);
        }
    }

    QueryLease openQueryLease() {
        lifecycleLock.readLock().lock();
        return lifecycleLock.readLock()::unlock;
    }

    public Set<ArchiveDatasetId> enabledBlockDatasets() {
        return projectionDatasets.stream()
                .filter(dataset -> dataset.sourceKind() == SourceKind.BLOCK)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Transaction lookup against the current immutable archive generation. */
    public TransactionLookup findTransaction(byte[] txHash) {
        ArchiveBackend current = backend;
        if (current == null || !available()) {
            return TransactionLookup.unavailable("history archive is unavailable");
        }
        if (!datasetAvailable(ArchiveDatasetId.TRANSACTION)) {
            return TransactionLookup.unavailable(
                    "transaction history is not maintained by this archive");
        }
        try (QueryLease ignored = openQueryLease();
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
        if (initializationError != null) {
            status.put("error", initializationError);
        }
        return Map.copyOf(status);
    }

    private Map<String, String> backendProperties(Path directory) {
        Map<String, String> properties = new HashMap<>();
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
        properties.put("row-group-size", Integer.toString(integer(
                YanoPropertyKeys.History.DUCKLAKE_ROW_GROUP_SIZE, 100_000)));
        properties.put("snapshot-retention-hours", Long.toString(longValue(
                YanoPropertyKeys.History.DUCKLAKE_SNAPSHOT_RETENTION_HOURS, 168)));
        properties.put("cleanup-grace-hours", Long.toString(longValue(
                YanoPropertyKeys.History.DUCKLAKE_CLEANUP_GRACE_HOURS, 24)));
        properties.put("duckdb.max-total-memory-bytes", Long.toString(sizeBytes(
                string(YanoPropertyKeys.History.DUCKDB_MAX_TOTAL_MEMORY, "256MB"))));
        properties.put("duckdb.max-concurrent-queries", Integer.toString(integer(
                YanoPropertyKeys.History.DUCKDB_MAX_CONCURRENT_QUERIES, 2)));
        properties.put("duckdb.max-temp-directory-bytes", Long.toString(sizeBytes(
                string(YanoPropertyKeys.History.DUCKDB_MAX_TEMP_SIZE, "2GB"))));
        properties.put("duckdb.steady-memory-bytes", Long.toString(sizeBytes(
                string(YanoPropertyKeys.History.DUCKDB_STEADY_MEMORY, "128MB"))));
        properties.put("duckdb.steady-threads", Integer.toString(integer(
                YanoPropertyKeys.History.DUCKDB_STEADY_THREADS, 1)));
        properties.put("duckdb.bulk-memory-bytes", Long.toString(sizeBytes(
                string(YanoPropertyKeys.History.DUCKDB_BULK_MEMORY, "128MB"))));
        properties.put("duckdb.bulk-threads", Integer.toString(integer(
                YanoPropertyKeys.History.DUCKDB_BULK_THREADS, 1)));
        properties.put("duckdb.max-concurrent-bulk-jobs", Integer.toString(integer(
                YanoPropertyKeys.History.DUCKDB_BULK_JOBS, 1)));
        return Map.copyOf(properties);
    }

    private void rejectRemovedConfiguration() {
        if (config.getOptionalValue(REMOVED_HISTORY_ENABLED, String.class).isPresent()) {
            throw new IllegalArgumentException(REMOVED_HISTORY_ENABLED
                    + " is no longer supported; remove it and set "
                    + "yano.history.projection.enabled=true when history is required");
        }
        if (config.getOptionalValue(REMOVED_ACCOUNT_HISTORY_ENABLED, String.class).isPresent()) {
            throw new IllegalArgumentException(REMOVED_ACCOUNT_HISTORY_ENABLED
                    + " was removed; remove it and set yano.history.projection.enabled=true instead");
        }
        if (config.getOptionalValue(REMOVED_LIVE_ENABLED, String.class).isPresent()) {
            throw new IllegalArgumentException(REMOVED_LIVE_ENABLED
                    + " was removed with the replay-worker archive; remove this setting");
        }
        for (String name : config.getPropertyNames()) {
            if (name.startsWith(SNAPSHOT_PREFIX) && configuredValue(name)) {
                throw new IllegalArgumentException(name
                        + " was removed; use yano.history.projection.enabled=true instead");
            }
            for (String prefix : REMOVED_WORKER_PREFIXES) {
                if (name.startsWith(prefix) && configuredValue(name)) {
                    throw new IllegalArgumentException(name
                            + " was removed with the replay-worker archive; remove this setting");
                }
            }
        }
    }

    private boolean configuredValue(String name) {
        return config.getOptionalValue(name, String.class)
                .filter(value -> !value.isBlank()).isPresent();
    }

    private String string(String name, String fallback) {
        return config.getOptionalValue(name, String.class).orElse(fallback);
    }

    private long longValue(String name, long fallback) {
        return config.getOptionalValue(name, Long.class).orElse(fallback);
    }

    private int integer(String name, int fallback) {
        return config.getOptionalValue(name, Integer.class).orElse(fallback);
    }

    static <T extends Enum<T>> T parseEnum(String name, String value, Class<T> type) {
        String trimmed = value.trim();
        try {
            return Enum.valueOf(type, trimmed.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(name + "=" + trimmed + " is not supported; valid values are "
                    + Arrays.stream(type.getEnumConstants())
                            .map(constant -> constant.name().toLowerCase(Locale.ROOT)
                                    .replace('_', '-'))
                            .sorted().toList());
        }
    }

    static long sizeBytes(String input) {
        String value = input.trim().toUpperCase(Locale.ROOT).replace("IB", "B");
        long multiplier = 1;
        if (value.endsWith("KB")) {
            multiplier = 1024L;
            value = value.substring(0, value.length() - 2);
        } else if (value.endsWith("MB")) {
            multiplier = 1024L * 1024;
            value = value.substring(0, value.length() - 2);
        } else if (value.endsWith("GB")) {
            multiplier = 1024L * 1024 * 1024;
            value = value.substring(0, value.length() - 2);
        } else if (value.endsWith("B")) {
            value = value.substring(0, value.length() - 1);
        }
        return Math.multiplyExact(Long.parseLong(value.trim()), multiplier);
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }

    @Override
    public synchronized void close() {
        lifecycleLock.writeLock().lock();
        try {
            ArchiveBackend current = backend;
            backend = null;
            projectionBackedReads = false;
            projectionDatasets = Set.of();
            projectionCommittedThrough = () -> -1L;
            if (current != null) {
                try {
                    current.close();
                } catch (RuntimeException e) {
                    log.warn("Closing projection archive read backend failed: {}", e.toString());
                }
            }
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    @FunctionalInterface
    interface QueryLease extends AutoCloseable {
        @Override
        void close();
    }

    /** Outcome of a transaction-hash lookup against the archive. */
    public record TransactionLookup(State state, ArchiveRecord row, String detail) {
        public enum State { FOUND, NOT_FOUND, UNAVAILABLE }

        public static TransactionLookup found(ArchiveRecord row) {
            return new TransactionLookup(State.FOUND, row, "");
        }

        public static TransactionLookup notFound() {
            return new TransactionLookup(State.NOT_FOUND, null, "");
        }

        public static TransactionLookup unavailable(String detail) {
            return new TransactionLookup(State.UNAVAILABLE, null, detail);
        }
    }
}
