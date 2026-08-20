package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yano.api.ChainQuery;
import com.bloxbean.cardano.yano.api.LedgerQuery;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.runtime.config.NetworkGenesisConfig;
import com.bloxbean.cardano.yano.api.archive.ProjectionCfNames;
import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import com.bloxbean.cardano.yano.api.events.ByronBlockProjectionEvent;
import com.bloxbean.cardano.yano.api.events.RollbackEvent;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveSafetyWindows;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionCoordinate;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveIdentity;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSectionType;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSink;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSinkProvider;
import com.bloxbean.cardano.yano.archive.core.projection.ProjectionConsumerBounds;
import com.bloxbean.cardano.yano.archive.core.projection.ProjectionFinalityGate;
import com.bloxbean.cardano.yano.archive.core.projection.ProjectionOutboxConsumer;
import com.bloxbean.cardano.yano.archive.core.projection.ProjectionConsumerResult;
import com.bloxbean.cardano.yano.archive.core.projection.ArchiveDiskLimits;
import com.bloxbean.cardano.yano.archive.core.projection.ArchiveIngestGate;
import com.bloxbean.cardano.yano.archive.core.projection.ArchiveRetainedFootprint;
import com.bloxbean.cardano.yano.archive.core.projection.CanonicalProjectionCollector;
import com.bloxbean.cardano.yano.archive.core.projection.ProjectionChunking;
import com.bloxbean.cardano.yano.archive.core.projection.ProjectionContributorHealth;
import com.bloxbean.cardano.yano.archive.core.projection.ProjectionMaintenanceSchedule;
import com.bloxbean.cardano.yano.archive.core.projection.ProjectionOutboxStats;
import com.bloxbean.cardano.yano.archive.core.projection.ProjectionOutboxStore;
import com.bloxbean.cardano.yano.archive.core.projection.ProjectionSinkLifecycle;
import com.bloxbean.cardano.yano.archive.core.projection.ProjectionStartupGuard;
import com.bloxbean.cardano.yano.runtime.assembly.Yano;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.RocksDB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Composes ADR-039 projection history for the node.
 *
 * <p>Kept separate from {@link HistoryArchiveService} deliberately: that service
 * orchestrates the replay-worker architecture ADR-039 replaces, and the two must be able
 * to run side by side while the old path serves as the differential oracle. Mixing them
 * would make the eventual removal commit far harder to review.
 *
 * <p>When {@code yano.history.projection.enabled} is false this initialises nothing and
 * installs no contributor, so a history-disabled node keeps its current behaviour.
 */
@ApplicationScoped
public class ProjectionHistoryService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ProjectionHistoryService.class);

    private final Config config;

    private volatile boolean enabled;
    private volatile ProjectionOutboxStore outbox;
    private volatile CanonicalProjectionCollector collector;
    private volatile ProjectionIdentity identity;
    private volatile ArchiveSafetyWindows safetyWindows;
    private volatile ProjectionContributorHealth.Monitor contributorMonitor;
    private volatile ArchiveIngestGate ingestGate;
    private volatile double diskAmplification = 1.4;
    private volatile java.nio.file.Path historyDirectory;
    private volatile ProjectionSink projectionSink;
    private volatile ProjectionSinkLifecycle sinkLifecycle;
    private volatile ProjectionOutboxConsumer consumer;
    private volatile Thread drainThread;
    private volatile boolean draining;
    private final java.util.concurrent.atomic.LongAdder drainedBatches = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder drainedBlocks = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder drainFailures = new java.util.concurrent.atomic.LongAdder();
    private volatile String lastDrainFailure;
    private volatile String sink = "none";
    private volatile ProjectionMaintenanceSchedule maintenanceSchedule = ProjectionMaintenanceSchedule.defaults();
    private volatile java.time.Duration maintenanceHousekeepingBudget = java.time.Duration.ofSeconds(30);
    private volatile java.time.Duration maintenanceCompactionBudget = java.time.Duration.ofMinutes(5);
    private volatile long maintenanceRewriteBytes = 8L << 30;
    private final java.util.concurrent.atomic.LongAdder maintenancePasses =
            new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder compactionPasses =
            new java.util.concurrent.atomic.LongAdder();
    private volatile String lastMaintenanceOutcome;
    private volatile boolean diskBackpressureInstalled;

    /**
     * How long shutdown waits for an in-flight sink commit. Bounded rather than unbounded: a
     * wedged sink must not prevent the node from stopping, and the receipt protocol makes an
     * abandoned commit safe to replay.
     */
    private static final Duration SHUTDOWN_DRAIN_WAIT = Duration.ofSeconds(30);

    @Inject
    public ProjectionHistoryService(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public synchronized void initialize(Yano yano, ChainQuery chain, LedgerQuery ledger,
                                       YanoConfig nodeConfig) {
        if (outbox != null) return;
        enabled = config.getOptionalValue(YanoPropertyKeys.History.PROJECTION_ENABLED, Boolean.class).orElse(false);
        if (!enabled) {
            log.info("ADR-039 projection history is disabled");
            return;
        }

        var access = yano.chainstateRocksAccess().orElseThrow(() -> new IllegalStateException(
                "projection history requires a RocksDB-backed chain state; the outbox must share the"
                        + " chainstate database so contributor writes are atomic with their source state"));

        RocksDB db = (RocksDB) access.getDb();
        outbox = new ProjectionOutboxStore(db,
                handle(access, ProjectionCfNames.PROJ_HEADER),
                handle(access, ProjectionCfNames.PROJ_SECTION),
                handle(access, ProjectionCfNames.PROJ_META),
                handle(access, ProjectionCfNames.PROJ_ARTIFACT));

        sink = config.getOptionalValue(YanoPropertyKeys.History.PROJECTION_SINK, String.class)
                .orElse("none").trim().toLowerCase();

        NetworkGenesisConfig genesis;
        try {
            genesis = NetworkGenesisConfig.load(nodeConfig.getShelleyGenesisFile(),
                    nodeConfig.getByronGenesisFile(), nodeConfig.getAlonzoGenesisFile(),
                    nodeConfig.getConwayGenesisFile());
        } catch (Exception e) {
            throw new IllegalStateException("projection history requires readable genesis configuration", e);
        }
        var network = new ArchiveNetworkIdentity(Math.toIntExact(genesis.getNetworkMagic()),
                genesisHash(nodeConfig));
        // Which datasets this node projects. The default is every shipped block dataset:
        // a fresh sync that silently omitted one would produce an archive that looks healthy
        // while a whole dataset is missing, and the omission is undiscoverable later because
        // the sections cannot be added without resyncing.
        Set<ProjectionSectionType> required = configuredSections();
        identity = new ProjectionIdentity(network, sink, 1, required);

        long tipBlock = chain.getLocalTip() == null ? 0 : chain.getLocalTip().getBlockNumber();
        Optional<ProjectionIdentity> storedIdentity = outbox.identityFingerprint()
                .filter(fingerprint -> fingerprint.equals(identity.fingerprint()))
                .map(ignored -> identity);
        // A fingerprint that does not match ours is reported through the guard rather than
        // silently discarded; the guard is the single place that decides a start is unsupported.
        boolean hasStoredIdentity = outbox.identityFingerprint().isPresent();

        ProjectionStartupGuard.verify(identity, new ProjectionStartupGuard.Observed(
                tipBlock, hasStoredIdentity, storedIdentity,
                true, Optional.empty(), ProjectionCoordinate.NONE, required));

        outbox.putIdentity(identity);

        safetyWindows = ArchiveSafetyWindows.resolve(genesis.getSecurityParam(),
                autoLong(YanoPropertyKeys.History.ROLLBACK_RETENTION_BLOCKS),
                autoLong(YanoPropertyKeys.History.FINALITY_BLOCKS));

        int chunkBytes = config.getOptionalValue(YanoPropertyKeys.History.PROJECTION_CHUNK_BYTES, Integer.class)
                .orElse(ProjectionChunking.DEFAULT_CHUNK_BYTES);
        // Pointer resolution is owned by the authoritative account-state contributor, so the
        // sink never needs resolver state and never fails on a pointer address.
        var pointerSource = yano.pointerCredentialSource();

        // Fail closed on an index that was not maintained from genesis. A partially populated
        // index would silently resolve pre-Conway pointers whose deregistration predates the
        // upgrade, producing rows that look correct and are not. ADR-039 history is
        // fresh-sync only, so the only supported state here is COMPLETE.
        // On a genuinely fresh chainstate, establish the marker first. The store refuses this
        // once the chain has advanced, so this cannot paper over a mid-chain activation.
        if (tipBlock <= 0
                && pointerSource.completeness() == com.bloxbean.cardano.yano.api.archive
                        .PointerCredentialSource.IndexCompleteness.INCOMPLETE) {
            yano.markPointerIndexFromGenesis();
        }

        var completeness = pointerSource.completeness();
        if (completeness != com.bloxbean.cardano.yano.api.archive.PointerCredentialSource
                .IndexCompleteness.COMPLETE) {
            throw new com.bloxbean.cardano.yano.archive.core.projection.ProjectionActivationException(
                    "projection history requires an as-of pointer index maintained from genesis, but the"
                            + " account-state store reports " + completeness + ". Start a fresh sync;"
                            + " an existing chainstate cannot be adopted because pre-Conway pointer"
                            + " deregistrations applied before the upgrade were never indexed.");
        }

        collector = new CanonicalProjectionCollector(outbox, identity,
                ledger::slotToEpoch, ledger::slotToUnixTime, chunkBytes, true, pointerSource);
        contributorMonitor = new ProjectionContributorHealth.Monitor(Duration.ofMinutes(5));

        // Disk backpressure. Canonical sync runs ahead of the sink by design; these bounds
        // exist so a lagging sink cannot exhaust the disk, not to pace core to sink speed.
        var defaults = ArchiveDiskLimits.defaults();
        var diskLimits = new ArchiveDiskLimits(
                bytes(YanoPropertyKeys.History.PROJECTION_DISK_SOFT_BYTES, defaults.softBytes()),
                bytes(YanoPropertyKeys.History.PROJECTION_DISK_HARD_BYTES, defaults.hardBytes()),
                bytes(YanoPropertyKeys.History.PROJECTION_DISK_LOW_WATER_BYTES, defaults.lowWaterBytes()),
                bytes(YanoPropertyKeys.History.PROJECTION_DISK_FREE_RESERVE_BYTES,
                        defaults.freeSpaceReserveBytes()));
        ingestGate = new ArchiveIngestGate(diskLimits);
        diskAmplification = config.getOptionalValue(
                YanoPropertyKeys.History.PROJECTION_DISK_AMPLIFICATION, Double.class).orElse(1.4);
        historyDirectory = java.nio.file.Path.of(
                config.getOptionalValue(YanoPropertyKeys.History.DIR, String.class).orElse("./history"))
                .toAbsolutePath().normalize();

        openSinkAndConsumer(chain, ledger, genesis, nodeConfig);

        installShelleyPlusContributor(yano);
        // Hold canonical ingestion only when the archive's aggregate disk budget or the
        // filesystem reserve is reached. Core otherwise runs ahead of the sink freely.
        boolean holdInstalled = yano.installArchiveIngestHold(
                () -> ingestDecision().pausesIngest(), "adr-039 archive-retained disk limit");
        diskBackpressureInstalled = holdInstalled;
        if (holdInstalled) {
            log.info("ADR-039 disk backpressure installed (soft={} B, hard={} B, freeReserve={} B,"
                            + " amplification={})",
                    diskLimits.softBytes(), diskLimits.hardBytes(), diskLimits.freeSpaceReserveBytes(),
                    diskAmplification);
        }
        if (!holdInstalled) {
            log.warn("ADR-039 disk backpressure could not be installed; canonical ingestion will not"
                    + " pause on archive disk pressure. Monitor retainedPhysicalBytes in /status.");
        }
        subscribeByronCarrier(yano);

        log.info("ADR-039 projection history enabled (sink={}, sections={}, chunkBytes={}, finalityBlocks={}, pointerSource={})",
                sink, required.stream().map(ProjectionSectionType::wireName).sorted().toList(),
                chunkBytes, safetyWindows.archiveFinalityBlocks(),
                pointerSource == com.bloxbean.cardano.yano.api.archive.PointerCredentialSource.NONE
                        ? "none" : "account-state(" + completeness + ")");
    }

    /**
     * Open the configured primary sink and start the ordered drain loop.
     *
     * <p>{@code sink=none} is a supported measurement mode: the producer runs and the outbox
     * accumulates, which is how producer cost and total projection volume are measured without
     * a sink in the loop. Any other engine must resolve to a provider, or startup fails —
     * silently running with no sink while claiming one is configured would look like a healthy
     * archive that never receives anything.
     */
    private void openSinkAndConsumer(ChainQuery chain, LedgerQuery ledger,
                                     NetworkGenesisConfig genesis, YanoConfig nodeConfig) {
        if ("none".equals(sink)) {
            log.info("ADR-039 projection sink is 'none'; the outbox will accumulate for measurement");
            return;
        }
        var provider = java.util.ServiceLoader.load(ProjectionSinkProvider.class).stream()
                .map(java.util.ServiceLoader.Provider::get)
                .filter(candidate -> candidate.engine().equals(sink))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no projection sink provider packaged for engine " + sink));

        var archiveIdentity = new ArchiveIdentity(
                java.util.UUID.nameUUIDFromBytes(identity.fingerprint()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                sink, 1, identity.networkIdentity().networkMagic(),
                identity.networkIdentity().genesisHash());

        projectionSink = provider.openProjectionSink(archiveIdentity, historyDirectory, sinkProperties());
        projectionSink.initialize(identity);
        // Every later use of the sink goes through this, so shutdown can guarantee it is never
        // closed while a commit or a maintenance pass is in flight.
        sinkLifecycle = new ProjectionSinkLifecycle(projectionSink);

        consumer = new ProjectionOutboxConsumer(outbox, projectionSink, identity,
                new ProjectionFinalityGate(safetyWindows), consumerBounds(),
                new com.bloxbean.cardano.yano.app.archive.NoArtifactReader(),
                () -> chain.getLocalTip() == null ? -1 : chain.getLocalTip().getBlockNumber(),
                () -> 0L);

        configureMaintenance();

        long interval = config.getOptionalValue(
                YanoPropertyKeys.History.PROJECTION_DRAIN_INTERVAL_MILLIS, Long.class).orElse(250L);
        draining = true;
        drainThread = Thread.ofVirtual().name("adr039-projection-drain").start(() -> drainLoop(interval));
        log.info("ADR-039 projection sink '{}' opened; drain loop started ({} ms idle interval)",
                sink, interval);
    }

    /**
     * Sections to project, defaulting to <strong>every shipped block dataset</strong>.
     *
     * <p>Narrowing this is a legacy/testing configuration, not a tuning knob. The required set
     * is part of the projection identity, so a section omitted at fresh sync cannot be added
     * later without resyncing from genesis — an archive that silently shipped without a dataset
     * would be discovered only when a query returned nothing, and by then the fix is a full
     * resync. Choosing less must therefore be explicit.
     *
     * <p>Fails on an unknown name rather than projecting less than asked for.
     */
    private Set<ProjectionSectionType> configuredSections() {
        String configured = config.getOptionalValue(
                YanoPropertyKeys.History.PROJECTION_SECTIONS, String.class).orElse("").trim();
        if (configured.isEmpty()) {
            return java.util.Set.of(ProjectionSectionType.values());
        }
        var byWireName = java.util.Arrays.stream(ProjectionSectionType.values())
                .collect(java.util.stream.Collectors.toMap(ProjectionSectionType::wireName, t -> t));
        var selected = new java.util.LinkedHashSet<ProjectionSectionType>();
        for (String name : configured.split(",")) {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) continue;
            ProjectionSectionType type = byWireName.get(trimmed);
            if (type == null) {
                throw new IllegalArgumentException("unknown projection section '" + trimmed
                        + "'; known sections are " + byWireName.keySet().stream().sorted().toList());
            }
            selected.add(type);
        }
        if (selected.isEmpty()) {
            throw new IllegalArgumentException(
                    YanoPropertyKeys.History.PROJECTION_SECTIONS + " was set but selected no sections");
        }
        return Set.copyOf(selected);
    }

    /**
     * Sink-engine settings the projection path owns independently of the legacy archive.
     *
     * <p>Only keys that were explicitly configured are passed through, so an unset key keeps
     * the engine's own default rather than this method's idea of one.
     */
    private java.util.Map<String, String> sinkProperties() {
        var properties = new LinkedHashMap<String, String>();
        putIfPresent(properties, "target-file-size-bytes",
                YanoPropertyKeys.History.PROJECTION_SINK_TARGET_FILE_SIZE_BYTES);
        putIfPresent(properties, "row-group-size",
                YanoPropertyKeys.History.PROJECTION_SINK_ROW_GROUP_SIZE);
        putIfPresent(properties, "snapshot-retention-hours",
                YanoPropertyKeys.History.PROJECTION_SINK_SNAPSHOT_RETENTION_HOURS);
        putIfPresent(properties, "cleanup-grace-hours",
                YanoPropertyKeys.History.PROJECTION_SINK_CLEANUP_GRACE_HOURS);
        return java.util.Map.copyOf(properties);
    }

    private void putIfPresent(java.util.Map<String, String> target, String engineKey, String configKey) {
        config.getOptionalValue(configKey, String.class)
                .filter(value -> !value.isBlank())
                .ifPresent(value -> target.put(engineKey, value.trim()));
    }

    /**
     * Maintenance cadence and budgets. Housekeeping is frequent and cheap; compaction is rare
     * and bounded, so neither can starve the drain thread they share.
     */
    private void configureMaintenance() {
        long housekeepingInterval = config.getOptionalValue(
                YanoPropertyKeys.History.PROJECTION_HOUSEKEEPING_INTERVAL_MINUTES, Long.class).orElse(30L);
        long compactionInterval = config.getOptionalValue(
                YanoPropertyKeys.History.PROJECTION_COMPACTION_INTERVAL_MINUTES, Long.class).orElse(360L);
        maintenanceSchedule = new ProjectionMaintenanceSchedule(
                java.time.Duration.ofMinutes(housekeepingInterval),
                java.time.Duration.ofMinutes(compactionInterval));
        maintenanceHousekeepingBudget = java.time.Duration.ofSeconds(config.getOptionalValue(
                YanoPropertyKeys.History.PROJECTION_HOUSEKEEPING_BUDGET_SECONDS, Long.class).orElse(30L));
        maintenanceCompactionBudget = java.time.Duration.ofSeconds(config.getOptionalValue(
                YanoPropertyKeys.History.PROJECTION_COMPACTION_BUDGET_SECONDS, Long.class).orElse(300L));
        maintenanceRewriteBytes = config.getOptionalValue(
                YanoPropertyKeys.History.PROJECTION_COMPACTION_REWRITE_BYTES, Long.class).orElse(8L << 30);
    }

    private ProjectionConsumerBounds consumerBounds() {
        var defaults = ProjectionConsumerBounds.defaults();
        return new ProjectionConsumerBounds(
                Math.toIntExact(config.getOptionalValue(
                        YanoPropertyKeys.History.PROJECTION_MAX_BLOCKS_PER_BATCH, Long.class)
                        .orElse((long) defaults.maxBlocksPerBatch())),
                config.getOptionalValue(YanoPropertyKeys.History.PROJECTION_MAX_BYTES_PER_BATCH, Long.class)
                        .orElse(defaults.maxBytesPerBatch()),
                config.getOptionalValue(YanoPropertyKeys.History.PROJECTION_SOFT_BACKLOG_BLOCKS, Long.class)
                        .orElse(defaults.softBacklogBlocks()),
                config.getOptionalValue(YanoPropertyKeys.History.PROJECTION_HARD_BACKLOG_BLOCKS, Long.class)
                        .orElse(defaults.hardBacklogBlocks()),
                config.getOptionalValue(YanoPropertyKeys.History.PROJECTION_SOFT_BACKLOG_BYTES, Long.class)
                        .orElse(defaults.softBacklogBytes()),
                config.getOptionalValue(YanoPropertyKeys.History.PROJECTION_HARD_BACKLOG_BYTES, Long.class)
                        .orElse(defaults.hardBacklogBytes()));
    }

    /**
     * Drains continuously while work is available and backs off only when idle, so a large
     * bootstrap backlog is consumed as fast as the sink allows. A failure is logged and
     * retried after a pause: the outbox is durable, so nothing is lost by retrying, and
     * stopping the loop would silently freeze the archive.
     */
    private void drainLoop(long idleIntervalMillis) {
        while (draining) {
            try {
                ProjectionSinkLifecycle lifecycle = sinkLifecycle;
                if (lifecycle == null || lifecycle.isClosed()) {
                    Thread.sleep(idleIntervalMillis);
                    continue;
                }
                // Every sink touch happens under the lifecycle lock, so shutdown can prove
                // nothing is in flight before it closes anything.
                ProjectionConsumerResult result = lifecycle.use(ignored -> consumer.drainOnce());
                if (result.madeProgress()) {
                    drainedBatches.increment();
                    drainedBlocks.add(result.lastBlock() - result.firstBlock() + 1);
                }
                // Keep draining while envelopes are still waiting, even when this pass only
                // accumulated. Backing off mid-bootstrap because nothing was committed yet
                // would idle the loop through the whole backlog.
                if (result.workPending()) continue;

                if (result.outcome() == ProjectionConsumerResult.Outcome.PAUSED) {
                    log.warn("ADR-039 projection drain paused: {}", result.detail().orElse("unknown"));
                } else {
                    runMaintenanceIfDue();
                }
                Thread.sleep(idleIntervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                drainFailures.increment();
                lastDrainFailure = t.toString();
                log.error("ADR-039 projection drain failed; retrying: {}", t.toString());
                try {
                    Thread.sleep(Math.max(idleIntervalMillis, 1_000L));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * Maintenance runs on the drain thread, never on a worker of its own, and only on a pass
     * that had nothing to commit. Housekeeping becomes due on an interval and may run during
     * bootstrap; compaction additionally requires the sink to be caught up.
     */
    private void runMaintenanceIfDue() {
        ProjectionOutboxConsumer active = consumer;
        if (active == null) return;
        Instant now = Instant.now();
        var action = maintenanceSchedule.decide(false, active.nearTip(), active.hasDrainBacklog(), now);
        if (action == ProjectionMaintenanceSchedule.Action.NONE) return;

        ProjectionSinkLifecycle lifecycle = sinkLifecycle;
        if (lifecycle == null || lifecycle.isClosed()) return;
        var pass = lifecycle.use(ignored -> active.maintain(maintenanceHousekeepingBudget,
                maintenanceCompactionBudget, maintenanceRewriteBytes));
        var result = pass.result();

        // Record only what actually ran. The consumer withholds compaction when the sink is
        // busy, and recording it anyway would defer the next attempt by a full interval.
        var ran = pass.compactionOffered()
                ? action
                : ProjectionMaintenanceSchedule.Action.HOUSEKEEPING;
        maintenanceSchedule.recordRun(ran, now);
        if (action == ProjectionMaintenanceSchedule.Action.HOUSEKEEPING_AND_COMPACTION
                && !pass.compactionOffered()) {
            maintenanceSchedule.recordWithheldCompaction(now);
        }
        maintenancePasses.increment();
        if (pass.compactionOffered()) compactionPasses.increment();

        lastMaintenanceOutcome = result.outcome().name()
                + result.detail().map(detail -> " (" + detail + ")").orElse("")
                + (pass.compactionOffered() ? "" : " [compaction withheld: sink busy]");
        log.debug("ADR-039 projection maintenance {} -> {}", ran, lastMaintenanceOutcome);
    }

    private void installShelleyPlusContributor(Yano yano) {
        boolean installed = yano.installProjectionContributor(collector);
        if (!installed) {
            // Failing closed matters here: a silently uninstalled contributor would produce
            // an archive that looks healthy while missing every Shelley+ block.
            throw new IllegalStateException("projection history could not install its Shelley+ contributor;"
                    + " the UTXO subsystem is absent or does not support contribution");
        }
    }

    private void subscribeByronCarrier(Yano yano) {
        EventBus bus = yano.kernel()
                .map(kernel -> kernel.context().eventBus())
                .orElseThrow(() -> new IllegalStateException("projection history requires the node event bus"));

        // Byron has no other subsystem batch to join: the live UTXO path never applies a
        // Byron transaction. The section and its cursor still commit atomically with each
        // other, and the retained canonical body plus that cursor remain the durable
        // replay intent.
        bus.subscribe(ByronBlockProjectionEvent.class,
                ctx -> outbox.commit(writer -> collector.contributeByronBlock(ctx.event(), writer)),
                SubscriptionOptions.builder().build());

        // Pending envelopes newer than the rollback point must go. The cutoff comes from
        // the rollback event's own slot, not from a live tip read: listener order relative
        // to chain-state rollback is unspecified, and a tip read too early would leave
        // stale envelopes above the surviving tip.
        bus.subscribe(RollbackEvent.class, ctx -> {
            var target = ctx.event().target();
            if (target == null) return;
            long removed = collector.rollbackToSlot(target.getSlot());
            // Whatever the drain thread has buffered may describe the discarded fork. The
            // flag is observed at its next safe point; taking a lock here would stall the
            // event bus behind an in-flight sink commit.
            ProjectionOutboxConsumer active = consumer;
            if (active != null) active.discardPendingBatch();
            if (removed > 0) {
                log.info("ADR-039 rollback to slot {} removed {} pending projection envelope(s)",
                        target.getSlot(), removed);
            }
        }, SubscriptionOptions.builder().build());
    }

    private static String genesisHash(YanoConfig nodeConfig) {
        if (nodeConfig.getShelleyGenesisHash() != null && !nodeConfig.getShelleyGenesisHash().isBlank()) {
            return nodeConfig.getShelleyGenesisHash().toLowerCase(java.util.Locale.ROOT);
        }
        if (nodeConfig.getShelleyGenesisFile() == null || nodeConfig.getShelleyGenesisFile().isBlank()) {
            throw new IllegalArgumentException("Shelley genesis hash or file is required for projection identity");
        }
        try {
            return com.bloxbean.cardano.yaci.core.util.HexUtil.encodeHexString(
                    com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash256(
                            java.nio.file.Files.readAllBytes(
                                    java.nio.file.Path.of(nodeConfig.getShelleyGenesisFile()))));
        } catch (Exception e) {
            throw new IllegalStateException("cannot compute Shelley genesis hash for projection identity", e);
        }
    }

    /**
     * Reads a window override that ships with the literal default {@code "auto"}, meaning
     * "derive it from genesis". Parsing it as a number would fail on the bundled default.
     */
    private long bytes(String name, long fallback) {
        return config.getOptionalValue(name, Long.class).orElse(fallback);
    }

    /**
     * Current aggregate archive-retained footprint. Staged-artifact and pinned-generation
     * bytes are zero until Phase 5 produces artifacts; they are part of the budget from the
     * start so enabling artifacts cannot silently exceed a limit sized without them.
     */
    public ArchiveRetainedFootprint footprint() {
        long outboxBytes = outbox == null ? 0 : outbox.stats(identity.requiredSections()).pendingBytes();
        long free = 0;
        try {
            java.io.File root = historyDirectory == null ? null : historyDirectory.toFile();
            if (root != null) free = root.getUsableSpace();
        } catch (Exception ignored) {
            // An unreadable free-space probe must not itself stop ingestion.
        }
        return new ArchiveRetainedFootprint(outboxBytes, 0, 0, diskAmplification, free);
    }

    /** Whether canonical ingestion may continue. Re-evaluated against the live footprint. */
    public ArchiveIngestGate.Decision ingestDecision() {
        return ingestGate == null ? new ArchiveIngestGate.Decision(
                ArchiveIngestGate.Decision.State.RUNNING, Optional.empty(), 0, 0, 0)
                : ingestGate.evaluate(footprint());
    }

    private Long autoLong(String name) {
        String value = config.getOptionalValue(name, String.class).orElse("auto").trim();
        return value.isEmpty() || value.equalsIgnoreCase("auto") ? null : Long.parseLong(value);
    }

    private static ColumnFamilyHandle handle(com.bloxbean.cardano.yano.api.db.RocksDbAccess access, String name) {
        Object handle = access.getColumnFamilyHandle(name);
        if (handle == null) {
            throw new IllegalStateException("projection column family " + name + " is not open; "
                    + "the chain state must declare it at open time");
        }
        return (ColumnFamilyHandle) handle;
    }

    // ------------------------------------------------------------------- status

    public boolean isEnabled() {
        return enabled;
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", enabled);
        if (!enabled || outbox == null) return status;
        status.put("sink", sink);
        status.put("drainedBatches", drainedBatches.sum());
        status.put("drainedBlocks", drainedBlocks.sum());
        status.put("drainFailures", drainFailures.sum());
        if (lastDrainFailure != null) status.put("lastDrainFailure", lastDrainFailure);
        if (projectionSink != null) {
            var coordinate = projectionSink.coordinate();
            status.put("sinkCoordinate", coordinate.isPresent() ? coordinate.blockNumber() : -1);
            status.put("sinkHealth", projectionSink.health().state().name());
        }
        status.put("identity", identity.fingerprint());
        ProjectionOutboxStats stats = outbox.stats(identity.requiredSections());
        status.put("pendingBlocks", stats.pendingBlocks());
        status.put("pendingBytes", stats.pendingBytes());
        status.put("pendingRows", stats.pendingRows());
        status.put("oldestPendingBlock", stats.oldestPendingBlock());
        status.put("completeThroughBlock", stats.completeThroughBlock());
        status.put("acknowledgedThroughBlock", stats.acknowledgedThroughBlock());
        status.put("bytesPerBlock", stats.pendingBlocks() == 0 ? 0
                : stats.pendingBytes() / stats.pendingBlocks());
        var decision = ingestDecision();
        status.put("ingestState", decision.state().name());
        decision.reason().ifPresent(reason -> status.put("ingestPauseReason", reason));
        status.put("retainedPhysicalBytes", decision.observedBytes());
        status.put("retainedLimitBytes", decision.limitBytes());
        status.put("filesystemFreeBytes", decision.freeBytes());
        ProjectionOutboxConsumer active = consumer;
        if (active != null) {
            var policy = active.batchPolicy();
            status.put("batchRegime", active.nearTip() ? "NEAR_TIP" : "BOOTSTRAP");
            status.put("batchTargetBlocksNearTip", policy.minBlocks(true));
            status.put("batchMaxLingerNearTip", policy.maxLinger(true).toString());
            status.put("batchTargetBlocksBootstrap", policy.minBlocks(false));
            status.put("batchMaxLingerBootstrap", policy.maxLinger(false).toString());
            status.put("pendingBatchBlocks", active.pendingBatchBlocks());
            status.put("pendingBatchOldestBlock", active.pendingBatchOldestBlock());
            status.put("pendingBatchAgeSeconds", active.pendingBatchAgeSeconds(Instant.now()));
            var batchStats = active.batchStats();
            status.put("flushReasons", batchStats.flushReasons());
            status.put("largestEnvelopeBytes", batchStats.largestEnvelopeEncodedBytes());
            status.put("largestEnvelopeRows", batchStats.largestEnvelopeRows());
            status.put("oversizedSingletons", batchStats.oversizedSingletons());
            status.put("singletonHighWatermarkBytes", batchStats.singletonHighWatermarkBytes());
            status.put("largestRejectedSingletonBytes", batchStats.largestRejectedSingletonBytes());
            var last = active.lastBatchDecision();
            if (last != null) {
                status.put("lastFlushReason", last.reason().name());
                status.put("lastFlushBlocks", last.blocks());
                status.put("lastFlushRows", last.rows());
                status.put("lastFlushEncodedBytes", last.encodedBytes());
            }
        }
        status.put("diskBackpressureInstalled", diskBackpressureInstalled);
        status.put("maintenancePasses", maintenancePasses.sum());
        status.put("compactionPasses", compactionPasses.sum());
        if (lastMaintenanceOutcome != null) status.put("lastMaintenance", lastMaintenanceOutcome);
        var health = contributorMonitor.evaluate(outbox, identity.requiredSections(), Instant.now());
        status.put("contributorStatus", health.status().name());
        status.put("contributorCursors", health.contributorCursors());
        health.detail().ifPresent(detail -> status.put("contributorDetail", detail));
        return status;
    }

    @Override
    public void close() {
        // Stop new sink work, let an in-flight commit finish, and preserve the outbox.
        // Shutdown never deletes pending projection data.
        draining = false;
        Thread thread = drainThread;
        if (thread != null) {
            // The interrupt breaks the idle sleep; the join is what actually matters. Closing
            // the sink while a commit is in flight would tear down the connection mid
            // transaction. Correctness would survive it - an unacknowledged commit is
            // recognised by its receipt on replay - but it would turn every clean shutdown
            // into a crash-recovery path for no reason.
            thread.interrupt();
            try {
                thread.join(SHUTDOWN_DRAIN_WAIT.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        ProjectionSinkLifecycle lifecycle = sinkLifecycle;
        if (lifecycle != null) {
            try {
                // Closes only if nothing is using the sink. A bounded maintenance pass can
                // legitimately outlast this wait - housekeeping plus compaction is 30 s + 300 s
                // at the defaults - so declining to close is an expected outcome, not a failure
                // path. The sink is then left open for the process to take with it: an
                // incomplete shutdown replays from the durable outbox, whereas closing a
                // connection mid transaction is undefined behaviour in the driver.
                if (!lifecycle.closeWhenIdle(SHUTDOWN_DRAIN_WAIT)) {
                    log.warn("ADR-039 projection sink is still in use after {}; leaving it open."
                                    + " Shutdown is incomplete and the in-flight commit replays on"
                                    + " restart.", SHUTDOWN_DRAIN_WAIT);
                }
            } catch (Exception e) {
                log.warn("closing the projection sink failed: {}", e.toString());
            }
        }
        // The outbox lives in the chainstate database, which the runtime owns and closes.
        collector = null;
    }
}
