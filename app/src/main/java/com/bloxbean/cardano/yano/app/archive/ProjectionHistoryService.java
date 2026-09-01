package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.yaci.events.api.EventBus;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.ChainQuery;
import com.bloxbean.cardano.yano.api.LedgerQuery;
import com.bloxbean.cardano.yano.api.archive.CanonicalProjectionContributor;
import com.bloxbean.cardano.yano.api.archive.ProjectionStagingWriter;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.api.util.EpochSlotCalc;
import com.bloxbean.cardano.yano.runtime.config.DefaultEpochParamProvider;
import com.bloxbean.cardano.yano.runtime.config.NetworkGenesisConfig;
import com.bloxbean.cardano.yano.api.archive.ProjectionCfNames;
import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import com.bloxbean.cardano.yano.api.events.ByronBlockProjectionEvent;
import com.bloxbean.cardano.yano.api.events.RollbackEvent;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveSafetyWindows;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionCoordinate;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionIdentity;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRepresentation;
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
import com.bloxbean.cardano.yano.archive.core.projection.ProjectionActivationException;
import com.bloxbean.cardano.yano.archive.core.projection.ProjectionOutboxStats;
import com.bloxbean.cardano.yano.archive.core.projection.ProjectionOutboxStore;
import com.bloxbean.cardano.yano.archive.core.projection.ProjectionSinkLifecycle;
import com.bloxbean.cardano.yano.archive.core.projection.ProjectionStartupGuard;
import com.bloxbean.cardano.yano.archive.core.projection.ProjectionRestartReconciler;
import com.bloxbean.cardano.yano.archive.core.source.DurableEpochFileSource;
import com.bloxbean.cardano.yano.archive.core.source.EpochArchiveJob;
import com.bloxbean.cardano.yano.runtime.assembly.Yano;
import com.bloxbean.cardano.yano.runtime.maintenance.RuntimeMaintenanceGate;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Composes projection history for the node - the only path that writes an archive.
 *
 * <p>Separate from {@link HistoryArchiveService}, which is now only the read facade over the
 * archive this service writes. Writing and reading stay apart because they fail
 * differently: a sink that cannot commit must pause ingestion, while a reader that cannot
 * open must not.
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
    private volatile com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactIdentity
            selectedArtifacts = com.bloxbean.cardano.yano.archive.api.projection
                    .ProjectionArtifactIdentity.NONE;
    private volatile com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactEnrollments
            artifactEnrollments = com.bloxbean.cardano.yano.archive.api.projection
                    .ProjectionArtifactEnrollments.NONE;

    /**
     * Why initialisation stopped, when it did.
     *
     * <p>{@link #initialize} can fail after the outbox is open - a configured section that no
     * longer exists, for instance - leaving this service half-built with a null identity. The
     * node keeps running, so the status endpoints must be able to say what happened instead of
     * dereferencing an identity that was never assigned.
     */
    private volatile String initializationError;

    /**
     * Whether initialisation ran to completion.
     *
     * <p>Set as the last statement of a successful {@link #initialize}, and the only thing the
     * status surfaces are allowed to test. Guarding on individual fields is what broke before:
     * identity is assigned early and contributorMonitor eighty lines later, so any failure
     * between them left a service that passed an {@code identity != null} check and then
     * dereferenced a null monitor. One flag cannot drift out of step with the fields it stands
     * for; a list of null checks silently can, every time a field is added.
     */
    private volatile boolean initialized;

    /**
     * Cached staged-evidence measurement, and when it was taken.
     *
     * <p>The disk gate is polled once per header, so measuring on every call put a full walk of
     * the staging tree on the per-block path. Staged evidence changes at epoch boundaries, not
     * per block, so the walk is cached and invalidated when evidence is actually staged or
     * released - with a short ceiling so an unnoticed change cannot go unmeasured indefinitely.
     */
    private volatile long stagedBytesCached;

    private volatile long stagedBytesProbedAt;

    private volatile boolean stagedBytesProbeHealthy;

    private final java.util.concurrent.atomic.AtomicLong stagedBytesVersion =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.ConcurrentMap<String, String> drainedGapVersions =
            new java.util.concurrent.ConcurrentHashMap<>();
    private volatile String drainedIntervalSetVersion;

    private static final long STAGED_BYTES_TTL_NANOS = java.time.Duration.ofSeconds(5).toNanos();
    private volatile ArchiveSafetyWindows safetyWindows;
    private volatile ProjectionContributorHealth.Monitor contributorMonitor;
    private volatile ArchiveIngestGate ingestGate;
    private volatile double diskAmplification = 1.4;
    private volatile java.nio.file.Path historyDirectory;
    private volatile ProjectionSink projectionSink;
    private volatile ProjectionSinkLifecycle sinkLifecycle;
    private volatile ProjectionOutboxConsumer consumer;
    private volatile Thread drainThread;
    private final DrainControl drainControl = new DrainControl();
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
    /** Live chain tip, for reporting how far behind tip the archive's coverage is. */
    private volatile java.util.function.LongSupplier chainTip;

    /** Held so published coordinates can be resolved canonically rather than reported as stored. */
    private volatile ChainQuery chainQuery;

    /** Held for the genesis coordinate's block time. */
    private volatile LedgerQuery ledgerQuery;

    /**
     * Stops the drain cooperatively, without interrupting a JDBC commit.
     *
     * <p>An interrupt is useful for waking {@link Thread#sleep(long)}, but it is also observable
     * by a driver while the thread is inside native database work. DuckDB can then abort an
     * otherwise clean commit during application shutdown. A monitor gives us the same prompt
     * wake-up while leaving an in-flight sink operation alone.
     */
    static final class DrainControl {
        private final Object wakeup = new Object();
        private volatile boolean running;

        void start() {
            running = true;
        }

        boolean isRunning() {
            return running;
        }

        void stop() {
            running = false;
            synchronized (wakeup) {
                wakeup.notifyAll();
            }
        }

        void await(long millis) throws InterruptedException {
            synchronized (wakeup) {
                if (running) {
                    wakeup.wait(millis);
                }
            }
        }
    }

    /**
     * The identity the sink was opened with.
     *
     * <p>Published because anything else reading this archive must present the same one. The
     * projection derives it from the projection fingerprint, whereas the legacy path derives it
     * from network, genesis, engine and directory - so a reader that recomputed it the legacy way
     * is refused for an identity mismatch over an archive it should be able to read.
     */
    private volatile ArchiveIdentity sinkArchiveIdentity;

    public java.util.Optional<ArchiveIdentity> archiveIdentity() {
        return java.util.Optional.ofNullable(sinkArchiveIdentity);
    }

    /** Held so the retention check can ask the runtime for its live common rollback floor. */
    private volatile Yano runtimeYano;
    /**
     * Epoch staging for artifacts the projection has not yet migrated.
     *
     * <p>Owned here, not by the legacy service: staged files are the projection's own write path
     * for REWARD, DREP_DISTRIBUTION and GOVERNANCE_PROPOSAL_STATUS, whose evidence is
     * irreproducible once the boundary has passed. Staging captures it, the projection references
     * it, the sink commits it, and only an acknowledged receipt releases it.
     */
    private volatile EpochArchiveStagingService epochStaging;

    /**
     * Epoch datasets whose artifact evidence is a staged file.
     *
     * <p>Derived from the shipped contracts rather than hard-coded, so it cannot drift: change a
     * dataset's representation away from STAGED_FILE and it leaves this set automatically, and
     * when the set empties the staging machinery has no remaining caller.
     *
     * <p>Not "unmigrated". These datasets ARE migrated - a staged file is their artifact
     * representation, chosen because rewards, DRep boundary state and governance decisions are
     * irreproducible once the boundary has passed. Staging is part of the projection's write
     * path for them, not a legacy remnant.
     */
    private static java.util.Set<com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink.Dataset>
            stagedFileDatasets(
                    com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactIdentity selected) {
        var staged = java.util.EnumSet.noneOf(
                com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink.Dataset.class);
        selected
                .contracts().values().stream()
                .filter(contract -> contract.representation()
                        == com.bloxbean.cardano.yano.archive.api.projection
                                .ProjectionArtifactRepresentation.STAGED_FILE)
                .forEach(contract -> {
                    try {
                        staged.add(com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink.Dataset
                                .valueOf(contract.dataset().name()));
                    } catch (IllegalArgumentException noStagingCounterpart) {
                        throw new IllegalStateException(contract.dataset() + " declares STAGED_FILE"
                                + " evidence but has no staging counterpart to produce it");
                    }
                });
        return staged;
    }

    /** Captures the genesis distribution once; block sections never produce it. */
    private volatile ProjectionGenesisBootstrap genesisBootstrap;

    /** Whether this archive has durably recorded its genesis distribution. */
    private volatile boolean genesisComplete;

    /**
     * First block of the canonical chain, which genesis funds are attributed to.
     *
     * <p>Byron networks number the first canonical chain block 1; block 0 is the epoch boundary
     * block, which is not a canonical chain block. Shelley-only and devnet chains begin at 0.
     * This is the one canonical attribution rule used by projection genesis capture.
     */
    private volatile long firstCanonicalBlock;

    /** Shelley funds the trigger carried, held until the canonical block is available. */
    private volatile java.util.Map<String, java.math.BigInteger> pendingGenesisEventFunds;

    /** Set once the trigger has fired, so the capture can be retried until it can complete. */
    private volatile boolean genesisPending;

    /** Serves epoch artifacts to the sink; block sections never touch it. */
    private volatile com.bloxbean.cardano.yano.archive.api.projection.ArchiveArtifactReader artifactReader =
            new NoArtifactReader();

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
        if (initialized) return;
        if (initializationError != null) {
            throw new IllegalStateException("projection history initialization already failed; "
                    + "restart the process before retrying: " + initializationError);
        }
        // Any failure anywhere in initialisation must leave a reportable reason. Scoping this to
        // one call was the earlier mistake: sink opening, the startup guard, artifact installation
        // and contributor installation can all fail after identity is assigned, and each left the
        // status endpoints to dereference fields that were never set.
        try {
            initializeInternal(yano, chain, ledger, nodeConfig);
            initialized = true;
            initializationError = null;
        } catch (RuntimeException e) {
            // Fail closed exactly as before; only the reporting changes.
            initialized = false;
            initializationError = e.getMessage() == null ? e.toString() : e.getMessage();
            throw e;
        }
    }

    private void initializeInternal(Yano yano, ChainQuery chain, LedgerQuery ledger,
                                    YanoConfig nodeConfig) {
        enabled = config.getOptionalValue(YanoPropertyKeys.History.PROJECTION_ENABLED, Boolean.class).orElse(false);
        if (!enabled) {
            log.info("ADR-039 projection history is disabled");
            return;
        }

        // An explicit section selection needs no durable state to resolve. Reject an
        // incompatible filter before requiring/opening RocksDB, so plugin and built-in filter
        // preflight remains an initialization check rather than a storage-side failure.
        if (config.getOptionalValue(YanoPropertyKeys.History.PROJECTION_SECTIONS, String.class)
                .isPresent()) {
            verifyCompleteUtxoRequirement(configuredSections(Optional.empty()), yano);
        }

        var access = yano.chainstateRocksAccess().orElseThrow(() -> new IllegalStateException(
                "projection history requires a RocksDB-backed chain state; the outbox must share the"
                        + " chainstate database so contributor writes are atomic with their source state"));

        outbox = new ProjectionOutboxStore(
                () -> (RocksDB) access.getDb(),
                name -> handle(access, name));

        sink = config.getOptionalValue(YanoPropertyKeys.History.PROJECTION_SINK, String.class)
                .orElse("none").trim().toLowerCase();

        Set<ProjectionSectionType> required = configuredSections(outbox.identityFingerprint());
        verifyCompleteUtxoRequirement(required, yano);

        NetworkGenesisConfig genesis;
        try {
            genesis = NetworkGenesisConfig.load(nodeConfig.getShelleyGenesisFile(),
                    nodeConfig.getByronGenesisFile(), nodeConfig.getAlonzoGenesisFile(),
                    nodeConfig.getConwayGenesisFile());
        } catch (Exception e) {
            throw new IllegalStateException("projection history requires readable genesis configuration", e);
        }
        var network = new ArchiveNetworkIdentity(Math.toIntExact(genesis.getNetworkMagic()),
                ProjectionGenesisIdentity.resolve(nodeConfig));
        firstCanonicalBlock = genesis.hasByronGenesis() ? 1L : 0L;
        int firstPostByronEpoch = resolveFirstPostByronEpoch(genesis, nodeConfig);
        ArtifactSelectionPlan artifactPlan = resolveArtifactSelection(
                chain, ledger, firstPostByronEpoch);
        selectedArtifacts = artifactPlan.identity();
        artifactEnrollments = artifactPlan.enrollments();
        // Which datasets this node projects. The default is every shipped block dataset:
        // a fresh sync that silently omitted one would produce an archive that looks healthy
        // while a whole dataset is missing, and the omission is undiscoverable later because
        // the sections cannot be added without resyncing.
        identity = new ProjectionIdentity(network, sink, 1, required);

        var localTip = chain.getLocalTip();
        boolean chainHasTip = localTip != null;
        long tipBlock = chainHasTip ? localTip.getBlockNumber() : 0;
        Optional<ProjectionIdentity> storedIdentity = outbox.identityFingerprint()
                .filter(fingerprint -> fingerprint.equals(identity.fingerprint()))
                .map(ignored -> identity);
        // A fingerprint that does not match ours is reported through the guard rather than
        // silently discarded; the guard is the single place that decides a start is unsupported.
        boolean hasStoredIdentity = outbox.identityFingerprint().isPresent();

        // The sink is opened and inspected BEFORE the guard runs. Passing synthetic state here -
        // sinkEmpty=true, no identity, coordinate NONE - made the most dangerous case
        // unreachable: an outbox that has acknowledged and pruned five million blocks against a
        // history directory that was deleted or repointed would have been accepted, a fresh
        // empty archive created, and every block below the acknowledgement lost with the archive
        // reporting itself healthy.
        runtimeYano = yano;
        chainTip = () -> chain.getLocalTip() == null ? -1 : chain.getLocalTip().getBlockNumber();
        chainQuery = chain;
        ledgerQuery = ledger;
        // Must be resolved before openSink(): the sink is opened against this directory, and the
        // guard reordering that moved openSink() ahead of the rest of initialisation left this
        // assignment behind it.
        historyDirectory = java.nio.file.Path.of(
                config.getOptionalValue(YanoPropertyKeys.History.DIR, String.class).orElse("./history"))
                .toAbsolutePath().normalize();
        openSink();

        // Real observed sink state, not an assumption about it.
        long acknowledgedThrough = outbox.acknowledgedThrough();
        ProjectionCoordinate sinkCoordinate = projectionSink == null
                ? ProjectionCoordinate.NONE : projectionSink.coordinate();
        boolean sinkEmpty = !sinkCoordinate.isPresent();
        Optional<ProjectionIdentity> sinkIdentity = projectionSink == null || sinkEmpty
                ? Optional.empty() : Optional.of(identity);
        ProjectionStartupGuard.verify(identity, new ProjectionStartupGuard.Observed(
                tipBlock, hasStoredIdentity, storedIdentity,
                sinkEmpty, sinkIdentity, sinkCoordinate, required, acknowledgedThrough));

        validateArtifactSelection(artifactPlan,
                sinkEmpty && acknowledgedThrough < 0 && !chainHasTip, yano);
        outbox.putProjectionSelection(identity, artifactPlan.identity(), artifactPlan.enrollments());
        if (projectionSink != null) {
            projectionSink.initializeArtifacts(selectedArtifacts, artifactEnrollments);
            reconcileEpochCoverageToCanonicalTip(chain);
        }
        installEpochArtifacts(yano, chain, ledger, network.networkMagic());
        installSelectedEpochStaging(chain, ledger, network, firstPostByronEpoch);

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

        startConsumerAndDrain(chain);

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
     * Resolve the staging era threshold without depending on RuntimeNode.start() having already
     * propagated genesis values back into the mutable node configuration. Projection history is
     * initialized before that lifecycle step by the packaged application.
     */
    static int resolveFirstPostByronEpoch(NetworkGenesisConfig genesis, YanoConfig nodeConfig) {
        Long configuredStart = nodeConfig.getConfiguredFirstNonByronSlot();
        if (configuredStart != null && configuredStart < 0) {
            throw new IllegalArgumentException("firstNonByronSlot must be non-negative");
        }
        long firstNonByronSlot = configuredStart != null
                ? configuredStart
                : DefaultEpochParamProvider.resolveFirstNonByronSlot(
                        genesis.getNetworkMagic(), genesis.hasByronGenesis());
        return new EpochSlotCalc(
                genesis.getEpochLength(),
                genesis.getByronSlotsPerEpoch(),
                firstNonByronSlot)
                .firstNonByronEpoch();
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
    /**
     * Open and initialise the sink, without starting to drain.
     *
     * <p>Separate from starting the drain loop on purpose: the startup guard must see the real
     * sink - its identity and its coordinate - before anything is written, and it cannot do that
     * if the sink is opened as part of starting up.
     */
    private void openSink() {
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

        sinkArchiveIdentity = archiveIdentity;
        projectionSink = provider.openProjectionSink(archiveIdentity, historyDirectory, sinkProperties());
        projectionSink.initialize(identity);
        // Every later use of the sink goes through this, so shutdown can guarantee it is never
        // closed while a commit or a maintenance pass is in flight.
        sinkLifecycle = new ProjectionSinkLifecycle(projectionSink);
    }



    private record ArtifactSelectionPlan(
            com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactIdentity identity,
            com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactEnrollments enrollments,
            java.util.List<com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactEnrollment>
                    additions,
            boolean storedMarkerPresent) { }

    /** Resolve configuration against the durable contract before any contributor or drain starts. */
    private ArtifactSelectionPlan resolveArtifactSelection(ChainQuery chain, LedgerQuery ledger,
                                                           int firstPostByronEpoch) {
        var shipped = com.bloxbean.cardano.yano.archive.api.projection
                .ProjectionArtifactContracts.shipped();
        Optional<String> storedWire = outbox.artifactIdentityWire();
        var stored = storedWire
                .map(com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactIdentity::parse)
                .orElse(com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactIdentity.NONE);

        Optional<String> configured = config.getOptionalValue(
                YanoPropertyKeys.History.PROJECTION_EPOCH_ARTIFACTS, String.class);
        var requested = configured.isEmpty()
                ? (storedWire.isPresent() ? stored : shipped)
                : parseArtifactSelection(configured.orElseThrow(), shipped);

        java.util.List<String> removed = new java.util.ArrayList<>();
        java.util.List<String> changed = new java.util.ArrayList<>();
        for (var entry : stored.contracts().entrySet()) {
            var mine = requested.contractFor(entry.getKey());
            if (mine.isEmpty()) removed.add(entry.getValue().selector());
            else if (!mine.get().equals(entry.getValue())) changed.add(entry.getValue().selector());
        }
        if (!removed.isEmpty() || !changed.isEmpty()) {
            throw new IllegalStateException("projection artifact selection differs from this archive: removed="
                    + removed + ", contractChanged=" + changed
                    + "; removal and representation/codec changes require a fresh archive");
        }

        var enrollmentValues = new java.util.EnumMap<com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId,
                com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactEnrollment>(
                com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.class);
        Optional<String> storedEnrollmentsWire = outbox.artifactEnrollmentsWire();
        if (storedEnrollmentsWire.isPresent()) {
            var persisted = com.bloxbean.cardano.yano.archive.api.projection
                    .ProjectionArtifactEnrollments.parse(storedEnrollmentsWire.orElseThrow());
            persisted.requireMatches(stored);
            enrollmentValues.putAll(persisted.values());
        } else if (storedWire.isPresent()) {
            stored.contracts().keySet().forEach(dataset -> enrollmentValues.put(dataset,
                    new com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactEnrollment(
                            dataset, java.util.OptionalInt.empty(),
                            com.bloxbean.cardano.yano.archive.api.projection
                                    .ProjectionArtifactEnrollmentOrigin.LEGACY_UNKNOWN)));
        }

        int currentEpoch = localEpoch(chain, ledger);
        var additions = new java.util.ArrayList<com.bloxbean.cardano.yano.archive.api.projection
                .ProjectionArtifactEnrollment>();
        for (var contract : requested.contracts().values()) {
            if (enrollmentValues.containsKey(contract.dataset())) continue;
            var origin = storedWire.isPresent()
                    ? com.bloxbean.cardano.yano.archive.api.projection
                            .ProjectionArtifactEnrollmentOrigin.PROSPECTIVE_JOIN
                    : com.bloxbean.cardano.yano.archive.api.projection
                            .ProjectionArtifactEnrollmentOrigin.FRESH;
            int projectedFrom = firstEligibleArtifactEpoch(
                    contract, currentEpoch, firstPostByronEpoch);
            var enrollment = new com.bloxbean.cardano.yano.archive.api.projection
                    .ProjectionArtifactEnrollment(contract.dataset(),
                    java.util.OptionalInt.of(projectedFrom), origin);
            enrollmentValues.put(contract.dataset(), enrollment);
            if (origin == com.bloxbean.cardano.yano.archive.api.projection
                    .ProjectionArtifactEnrollmentOrigin.PROSPECTIVE_JOIN) {
                additions.add(enrollment);
            }
        }

        var enrollments = new com.bloxbean.cardano.yano.archive.api.projection
                .ProjectionArtifactEnrollments(enrollmentValues);
        enrollments.requireMatches(requested);
        additions.sort(java.util.Comparator.comparing(
                com.bloxbean.cardano.yano.archive.api.projection
                        .ProjectionArtifactEnrollment::wireName));
        return new ArtifactSelectionPlan(requested, enrollments, List.copyOf(additions),
                storedWire.isPresent());
    }

    private com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactIdentity
            parseArtifactSelection(String configured,
                    com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactIdentity shipped) {
        try {
            return EpochArtifactSelectionParser.parse(configured, shipped);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    YanoPropertyKeys.History.PROJECTION_EPOCH_ARTIFACTS + ": " + e.getMessage(), e);
        }
    }

    private int localEpoch(ChainQuery chain, LedgerQuery ledger) {
        var tip = chain.getLocalTip();
        if (tip == null) return -1;
        try {
            return Math.toIntExact(ledger.slotToEpoch(tip.getSlot()));
        } catch (RuntimeException e) {
            throw new IllegalStateException("cannot resolve the current semantic epoch for artifact enrollment", e);
        }
    }

    static int firstEligibleArtifactEpoch(
            com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactContract contract,
            int currentEpoch, int firstPostByronEpoch) {
        Objects.requireNonNull(contract, "contract");
        if (currentEpoch < -1 || firstPostByronEpoch < 0) {
            throw new IllegalArgumentException("invalid epoch enrollment coordinates");
        }

        // The boundary pipeline does not assign one universal semantic epoch. The delegation
        // snapshot produced at E -> E+1 describes E, while rewards, governance and the final pot
        // describe E+1. Deriving this from the actual producer contract is what prevents a join
        // during epoch 520 from silently skipping the still-capturable epoch-stake snapshot 520.
        if (contract.dataset()
                == com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.EPOCH_STAKE) {
            return Math.max(currentEpoch, firstPostByronEpoch);
        }

        int eraFloor = Math.addExact(firstPostByronEpoch, 1);
        if (contract.dataset()
                == com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.ADA_POT) {
            // EpochBoundaryProcessor publishes the final pot only from epoch 2. This matters on
            // Shelley-only/devnet networks whose first post-Byron epoch is zero.
            eraFloor = Math.max(2, eraFloor);
        }
        return Math.max(Math.addExact(currentEpoch, 1), eraFloor);
    }

    private void validateArtifactSelection(ArtifactSelectionPlan plan, boolean freshArchive, Yano yano) {
        if (!plan.storedMarkerPresent() && !freshArchive) {
            throw new IllegalStateException("this populated archive has no epoch-artifact identity; "
                    + "a fresh sync is required before selecting " + plan.identity().wireForm());
        }
        boolean joiningStaged = plan.additions().stream().anyMatch(addition ->
                plan.identity().contractFor(addition.dataset())
                        .map(contract -> contract.representation()
                                == com.bloxbean.cardano.yano.archive.api.projection
                                        .ProjectionArtifactRepresentation.STAGED_FILE)
                        .orElse(false));
        if (joiningStaged && legacyStagingFailure().isPresent()) {
            throw new IllegalStateException("cannot join a staged epoch artifact while "
                    + "epoch-source/FAILED exists; rebuild or explicitly audit and acknowledge "
                    + "the legacy failure first");
        }
        validateArtifactProducers(plan.identity(), yano);
        for (var addition : plan.additions()) {
            log.warn("ADR-044 joining epoch artifact {} from epoch {}; earlier epochs are "
                            + "NOT_PROJECTED. Inspect /history/coverage; use a fresh archive for "
                            + "genesis-complete history.",
                    addition.wireName(), addition.projectedFromEpoch().orElseThrow());
        }
    }

    private void validateArtifactProducers(
            com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactIdentity selected,
            Yano yano) {
        if (selected.isEmpty()) return;
        if (yano.accountStateStoreForArtifacts().isEmpty()) {
            throw new IllegalStateException("selected epoch artifacts require yano.account-state.enabled=true");
        }
        requireProducer(selected, com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.EPOCH_STAKE,
                YanoPropertyKeys.EpochSnapshot.AMOUNTS_ENABLED);
        requireProducer(selected, com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.ADA_POT,
                YanoPropertyKeys.Ledger.ADAPOT_ENABLED);
        requireProducer(selected, com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.REWARD,
                YanoPropertyKeys.Ledger.REWARDS_ENABLED);
        requireProducer(selected, com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.DREP_DISTRIBUTION,
                YanoPropertyKeys.Ledger.GOVERNANCE_ENABLED);
        requireProducer(selected,
                com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.GOVERNANCE_PROPOSAL_STATUS,
                YanoPropertyKeys.Ledger.GOVERNANCE_ENABLED);
    }

    private void requireProducer(
            com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactIdentity selected,
            com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId dataset, String property) {
        if (selected.contractFor(dataset).isPresent()
                && !config.getOptionalValue(property, Boolean.class).orElse(false)) {
            throw new IllegalStateException(dataset.logicalName() + " is selected but producer "
                    + property + " is disabled");
        }
    }

    /** Install only readers and contributors covered by the persisted ADR-044 enrollment. */
    private void installEpochArtifacts(Yano yano, ChainQuery chain, LedgerQuery ledger, long networkMagic) {
        var clamp = yano.snapshotRetentionClamp();

        int pageSize = config.getOptionalValue(
                YanoPropertyKeys.History.PROJECTION_ARTIFACT_PAGE_ROWS, Integer.class).orElse(50_000);

        var boundaryFacts = new ArtifactBoundaryFacts() {
            @Override
            public java.util.Optional<byte[]> blockHash(long blockNumber) {
                return chain.getCanonicalBlockReference(blockNumber)
                        .map(com.bloxbean.cardano.yano.api.CanonicalBlockReference::blockHash);
            }

            @Override
            public long blockTimeSeconds(long slot) {
                return ledger.slotToUnixTime(slot);
            }
        };

        // Genesis is captured by an explicit bootstrap, not by a block section: it belongs to no
        // block, and forcing it into block 0 would collide with that block's own sections.
        genesisBootstrap = new ProjectionGenesisBootstrap(identity, yano.genesisUtxoProvider(),
                new com.bloxbean.cardano.yano.archive.core.source.YaciUtxoHistoryDecoder(
                        ledger::slotToEpoch, ledger::slotToUnixTime));

        var readers = new java.util.LinkedHashMap<com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId,
                com.bloxbean.cardano.yano.archive.api.projection.ArchiveArtifactReader>();
        if (selectedArtifacts.contractFor(
                com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.EPOCH_STAKE).isPresent()) {
            var store = yano.accountStateStoreForArtifacts().orElseThrow(() ->
                    new IllegalStateException("epoch-stake is selected but the account-state store is absent"));
            readers.put(com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.EPOCH_STAKE,
                    new EpochSnapshotArtifactReader(store, clamp, pageSize, networkMagic, boundaryFacts));
        }
        if (selectedArtifacts.contractFor(
                com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.ADA_POT).isPresent()) {
            readers.put(com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.ADA_POT,
                    new AdaPotArtifactReader(networkMagic, boundaryFacts));
        }

        // Datasets whose evidence is a staged file share one reader; each is bound to its own
        // source so a reference can only ever be served from the dataset that produced it.
        var stagedSources = new java.util.LinkedHashMap<
                com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId,
                StagedEpochArtifactReader.StagedEvidenceSource>();
        for (var dataset : java.util.List.of(
                com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.REWARD,
                com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.DREP_DISTRIBUTION,
                com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.GOVERNANCE_PROPOSAL_STATUS)) {
            if (selectedArtifacts.contractFor(dataset).isPresent()) {
                stagedSources.put(dataset, stagedEvidenceSource(dataset));
            }
        }
        if (!stagedSources.isEmpty()) {
            var stagedReader = new StagedEpochArtifactReader(stagedSources);
            stagedSources.keySet().forEach(dataset -> readers.put(dataset, stagedReader));
        }

        artifactReader = new RoutingArtifactReader(readers);

        var direct = selectedArtifacts.contracts().keySet().stream()
                .filter(dataset -> dataset == com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.EPOCH_STAKE
                        || dataset == com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.ADA_POT)
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        dataset -> dataset, this::projectedFromOrLegacyZero));
        if (!direct.isEmpty()) {
            // The state version is inherited from the replay worker's format exactly.
            var directCollector = new com.bloxbean.cardano.yano.archive.core.projection
                    .EpochArtifactCollector(outbox, clamp, direct,
                    com.bloxbean.cardano.yano.archive.api.schema.ArchiveSchemas
                            .schema(com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.EPOCH_STAKE)
                            .projectionVersion(), "ledger-boundary-v1");
            if (!yano.installEpochArtifactContributor(directCollector)) {
                throw new IllegalStateException("could not install the selected direct epoch artifact contributor");
            }
        }
        log.info("ADR-044 epoch artifacts selected ({}, {} rows per artifact page)",
                selectedArtifacts.wireForm().isEmpty() ? "none" : selectedArtifacts.wireForm(), pageSize);
    }

    private int projectedFromOrLegacyZero(
            com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId dataset) {
        return artifactEnrollments.enrollmentFor(dataset)
                .flatMap(enrollment -> enrollment.projectedFromEpoch().isPresent()
                        ? Optional.of(enrollment.projectedFromEpoch().getAsInt()) : Optional.empty())
                .orElse(0);
    }


    /**
     * Complete an interrupted genesis bootstrap before ordinary draining begins.
     *
     * <p>The genesis-block event fires once, for the first block of a fresh chain. A crash after
     * that block is stored but before the projection recorded genesis would otherwise leave the
     * archive permanently short with no second trigger, so relying on event delivery alone is not
     * enough - startup has to check.
     *
     * <p>A populated archive with no genesis marker fails closed. The distribution could be
     * re-derived, but the blocks already committed were projected against an archive missing it,
     * and quietly appending genesis now would produce an archive that never existed as a whole.
     */
    private void reconcileGenesis(ChainQuery chain) {
        if (projectionSink == null || genesisBootstrap == null) return;

        var receipt = projectionSink.genesisReceipt();
        if (receipt.isPresent()) {
            genesisComplete = true;
            log.info("ADR-039 genesis already captured: {} rows, {} lovelace, identity {}",
                    receipt.get().rowCount(), receipt.get().totalLovelace(), receipt.get().identity());
            return;
        }

        var coordinate = projectionSink.coordinate();
        if (coordinate.isPresent()) {
            throw new IllegalStateException("this projection archive holds blocks through "
                    + coordinate.blockNumber() + " but never recorded a genesis distribution;"
                    + " it cannot be completed in place and requires a fresh sync");
        }

        // Nothing committed yet. If the chain already has its first block, the trigger may have
        // been missed or interrupted; complete it now. Otherwise the event will do it.
        var tip = chain.getLocalTip();
        if (tip == null) return;
        var canonical = chain.getCanonicalBlockReference(0).or(() ->
                chain.getCanonicalBlockReference(tip.getBlockNumber()));
        if (canonical.isEmpty()) return;

        log.info("ADR-039 genesis was not recorded; completing the interrupted bootstrap before draining");
        genesisPending = true;
        captureGenesisIfPossible();
    }

    /**
     * Capture genesis once the canonical first block is resolvable.
     *
     * <p>Retried rather than done once, because the trigger can fire on the epoch boundary block
     * before the canonical block exists. Cheap to call repeatedly: it returns immediately once
     * complete, and the first drain is thousands of blocks away.
     */
    private synchronized void captureGenesisIfPossible() {
        if (genesisComplete || !genesisPending || projectionSink == null || chainQuery == null) return;
        var canonical = chainQuery.getCanonicalBlockReference(firstCanonicalBlock);
        if (canonical.isEmpty()) return;
        captureGenesis(canonical.get().blockNumber(), canonical.get().slot(),
                canonical.get().blockHash(), null,
                pendingGenesisEventFunds == null ? java.util.Map.of() : pendingGenesisEventFunds);
        genesisPending = false;
    }

    /** Capture genesis, cross-checking any Shelley funds the trigger carried. */
    private synchronized void captureGenesis(long blockNumber, long slot, byte[] blockHash,
                                             byte[] parentHash,
                                             java.util.Map<String, java.math.BigInteger> eventFunds) {
        if (genesisComplete || projectionSink == null || genesisBootstrap == null) return;
        String blockHashHex = blockHash == null ? "00".repeat(32)
                : java.util.HexFormat.of().formatHex(blockHash);

        var distribution = genesisBootstrap.distribution(blockNumber, slot, blockHashHex);
        // The event is the trigger, not the source. Where it also carries Shelley funds, a
        // disagreement means the archive would be built from a different distribution than the
        // ledger initialised from.
        genesisBootstrap.verifyEventAgreesWithProvider(eventFunds, distribution,
                identity.networkIdentity().networkMagic());

        long blockTime = 0;
        try {
            blockTime = ledgerQuery == null ? 0 : ledgerQuery.slotToUnixTime(slot);
        } catch (RuntimeException ignored) {
            // A genesis coordinate outside the slot schedule still gets a deterministic row.
        }
        var receipt = genesisBootstrap.bootstrap(projectionSink, blockNumber, slot,
                0, blockTime, blockHash == null ? new byte[32] : blockHash,
                parentHash == null ? new byte[32] : parentHash, blockHashHex);
        genesisComplete = true;
        log.info("ADR-039 genesis captured: {} rows, {} lovelace, digest {}",
                receipt.rowCount(), receipt.totalLovelace(), receipt.rowDigest());
    }


    /** Install durable staged-file capture for the selected reward/governance datasets. */
    private void installSelectedEpochStaging(ChainQuery chain, LedgerQuery ledger,
                                             ArchiveNetworkIdentity network,
                                             int firstPostByronEpoch) {
        var pending = stagedFileDatasets(selectedArtifacts);
        if (pending.isEmpty()) {
            log.info("ADR-039 no epoch dataset uses staged-file evidence; staging is not installed");
            return;
        }
        var floors = pending.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                dataset -> dataset,
                dataset -> projectedFromOrLegacyZero(
                        com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.valueOf(dataset.name()))));
        epochStaging = new EpochArchiveStagingService(chain, ledger, network,
                historyDirectory.resolve("epoch-source"), pending, firstPostByronEpoch, floors);

        // Durable evidence must become an outbox reference, be committed by the sink, and only
        // then be released. This callback fires strictly after the evidence is fsynced, so a
        // reference always implies durability.
        epochStaging.setStagedArtifactListener((job, evidence) -> {
            try {
                stageStagedFileArtifact(job, evidence);
            } catch (RuntimeException e) {
                // Never swallow: an unreferenced staged file is invisible to the archive, which
                // is exactly the failure mode the genesis omission had.
                drainFailures.increment();
                lastDrainFailure = "staged artifact reference failed: " + e;
                log.error("ADR-039 could not reference staged evidence for {} epoch {}",
                        job.dataset(), job.epoch(), e);
                throw e;
            }
        });

        // ADR-045: a dataset-specific failure becomes a canonical durable GAP before ledger
        // synchronization is allowed to continue. Other staged datasets remain active.
        epochStaging.setDatasetFailureListener(new EpochArchiveStagingService.DatasetFailureListener() {
            @Override
            public void failed(
                    com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink.Dataset dataset,
                    int semanticEpoch,
                    com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink.Boundary boundary,
                    Exception failure) {
                var canonical = chain.getCanonicalBlockReference(boundary.blockNumber())
                        .orElseThrow(() -> new IllegalStateException(
                                "canonical boundary is unavailable while recording epoch gap at block "
                                        + boundary.blockNumber()));
                if (canonical.slot() != boundary.slot()) {
                    throw new IllegalStateException("canonical boundary slot changed while recording epoch gap");
                }
                var archiveDataset = com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId
                        .valueOf(dataset.name());
                outbox.recordEpochArtifactGap(new com.bloxbean.cardano.yano.archive.api.projection
                        .EpochArtifactGap(archiveDataset, semanticEpoch, boundary.blockNumber(),
                        boundary.slot(), canonical.blockHash(), failureClass(failure),
                        failure.getMessage(), Instant.now()));
            }

            @Override
            public void missed(
                    com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink.Dataset dataset,
                    com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink.Boundary boundary) {
                var canonical = chain.getCanonicalBlockReference(boundary.blockNumber())
                        .orElseThrow(() -> new IllegalStateException(
                                "canonical paused boundary is unavailable at block "
                                        + boundary.blockNumber()));
                outbox.recordPausedEpoch(
                        com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.valueOf(dataset.name()),
                        boundary.newEpoch(), boundary.slot(), canonical.blockHash());
            }
        });
        for (var dataset : pending) {
            var archiveDataset = com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId
                    .valueOf(dataset.name());
            if (outbox.epochArtifactCaptureState(archiveDataset)
                    == com.bloxbean.cardano.yano.archive.api.projection.EpochArtifactCaptureState.PAUSED) {
                String detail = outbox.epochArtifactGaps().stream()
                        .filter(gap -> gap.dataset() == archiveDataset)
                        .reduce((first, second) -> second)
                        .map(com.bloxbean.cardano.yano.archive.api.projection.EpochArtifactGap::detail)
                        .orElse("paused after an epoch-artifact gap");
                epochStaging.restorePaused(dataset, detail);
            }
        }

        ledger.setEpochArchiveStagingSink(epochStaging);
        log.info("ADR-039 staged-file evidence enabled for {} dataset(s): {}", pending.size(), pending);
    }

    private static String failureClass(Throwable failure) {
        if (failure instanceof java.nio.file.FileSystemException) return "filesystem";
        if (failure instanceof java.io.IOException) return "io";
        if (failure instanceof com.bloxbean.cardano.yano.archive.api.ArchiveBatchCapacityException) {
            return "capacity";
        }
        return "capture";
    }


    /** Bind one dataset's staged evidence to the reader, through the staging service. */
    private StagedEpochArtifactReader.StagedEvidenceSource stagedEvidenceSource(
            com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId dataset) {
        return new StagedEpochArtifactReader.StagedEvidenceSource() {
            @Override
            public StagedEpochArtifactReader.EvidencePage rows(
                    com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef ref,
                    java.util.Optional<String> cursor, int limit) {
                var staging = requireStaging(dataset);
                var page = staging.materialisePage(dataset,
                        java.util.UUID.fromString(ref.sourceGeneration()), cursor, limit);
                return new StagedEpochArtifactReader.EvidencePage(page.rows(), page.nextCursor());
            }

            @Override
            public void release(com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef ref) {
                var staging = epochStaging;
                if (staging != null) {
                    staging.release(dataset, java.util.UUID.fromString(ref.sourceGeneration()));
                    stagedBytesChanged();
                }
            }

            @Override
            public boolean present(com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef ref) {
                var staging = epochStaging;
                return staging != null
                        && staging.present(dataset, java.util.UUID.fromString(ref.sourceGeneration()));
            }
        };
    }

    private EpochArchiveStagingService requireStaging(
            com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId dataset) {
        var staging = epochStaging;
        if (staging == null) {
            throw new IllegalStateException("staged evidence for " + dataset + " is referenced but"
                    + " epoch staging is not installed; the archive cannot serve it");
        }
        return staging;
    }

    /**
     * Record a reference to durable staged evidence, in its own synced write.
     *
     * <p>Ordering carries the safety argument: the rows were fsynced and published before this
     * runs, so a reference that exists implies evidence that exists. A crash in the gap leaves an
     * orphaned staged file, which cleanup removes — never a reference to evidence that is gone.
     *
     * <p>The reference carries the file's own checksum, so the sink is bound to those exact
     * bytes, and the row count, so a truncated read cannot commit as a complete epoch.
     */
    private void stageStagedFileArtifact(EpochArchiveJob job,
                                         DurableEpochFileSource.StagedEvidence evidence) {
        if (outbox == null) return;
        var ref = stagedFileReference(job, evidence);
        // The reference must reach the outbox while its boundary block is still pending, or the
        // batch that carries it has already been committed and this becomes an artifact the sink
        // will never receive. Binding artifacts into the receipt digest turns that into a loud
        // mismatch instead of the silent deletion it used to be - but loud-and-stuck is not the
        // outcome to aim for, so the race is refused here, before it can be created.
        //
        // The staged file is deliberately left on disk. It is irreproducible once the boundary
        // has passed, so an operator needs it to still be there.
        var sinkNow = projectionSink == null ? null : projectionSink.coordinate();
        if (sinkNow != null && sinkNow.isPresent() && job.boundaryBlockNumber() <= sinkNow.blockNumber()) {
            throw new IllegalStateException("staged evidence for " + job.dataset().logicalName()
                    + " epoch " + job.epoch() + " became durable only after its boundary block "
                    + job.boundaryBlockNumber() + " was committed to the sink (committed through "
                    + sinkNow.blockNumber() + "); the archive cannot reference it without"
                    + " contradicting a receipt. The staged file is preserved at generation "
                    + job.jobId() + " and must be replayed into a fresh archive rather than dropped");
        }
        outbox.putArtifactDirect(job.boundaryBlockNumber(), ref);
        stagedBytesChanged();
        log.info("ADR-039 staged evidence referenced: {} epoch {} ({} rows, digest {})",
                job.dataset().logicalName(), job.epoch(), evidence.rowCount(),
                evidence.checksum().substring(0, 16));
    }

    /** Stage a locally produced artifact in the same write batch as its canonical block. */
    private void stageStagedFileArtifact(
            ProjectionStagingWriter writer,
            EpochArchiveStagingService.BoundArtifact artifact) {
        if (outbox == null) return;
        var job = artifact.job();
        outbox.putArtifact(writer, job.boundaryBlockNumber(),
                stagedFileReference(job, artifact.evidence()));
        stagedBytesChanged();
        log.info("ADR-039 local staged evidence bound: {} epoch {} ({} rows, digest {})",
                job.dataset().logicalName(), job.epoch(), artifact.evidence().rowCount(),
                artifact.evidence().checksum().substring(0, 16));
    }

    private ProjectionArtifactRef stagedFileReference(
            EpochArchiveJob job, DurableEpochFileSource.StagedEvidence evidence) {
        return new ProjectionArtifactRef(
                job.dataset(), Math.toIntExact(job.epoch()), job.boundaryBlockNumber(), job.boundarySlot(),
                ProjectionArtifactRepresentation.STAGED_FILE,
                job.jobId().toString(), 1, job.sourceStateVersion(),
                OptionalLong.of(evidence.rowCount()), evidence.checksum(),
                // A staged file is independent of chain retention: it is its own copy, so nothing
                // about block-body pruning can take it away.
                -1L);
    }

    /** Start the ordered drain, once the guard has accepted the sink. */
    private void startConsumerAndDrain(ChainQuery chain) {
        if (projectionSink == null) return;
        consumer = new ProjectionOutboxConsumer(outbox, projectionSink, identity,
                new ProjectionFinalityGate(safetyWindows), consumerBounds(),
                artifactReader,
                () -> chain.getLocalTip() == null ? -1 : chain.getLocalTip().getBlockNumber(),
                this::commonRollbackFloorSlot);

        reconcileGenesis(chain);
        reconcileOutboxToCanonicalTipBeforeDrain(chain);

        // Durable lease reconciliation, before any drain or prune can run. Leases live in memory;
        // the outbox's surviving artifact references are what actually survives a crash.
        var pending = outbox.pendingArtifacts();
        artifactReader.reconcileAfterRestart(pending);
        if (!pending.isEmpty()) {
            log.info("ADR-039 re-established source protection for {} pending epoch artifact(s)",
                    pending.size());
        }

        configureMaintenance();

        long interval = config.getOptionalValue(
                YanoPropertyKeys.History.PROJECTION_DRAIN_INTERVAL_MILLIS, Long.class).orElse(250L);
        drainControl.start();
        drainThread = Thread.ofVirtual().name("adr039-projection-drain").start(() -> drainLoop(interval));
        log.info("ADR-039 projection sink '{}' opened; drain loop started ({} ms idle interval)",
                sink, interval);
    }

    private void reconcileOutboxToCanonicalTipBeforeDrain(ChainQuery chain) {
        var tip = chain.getLocalTip();
        com.bloxbean.cardano.yano.api.CanonicalBlockReference bodyTip = tip == null ? null
                : new com.bloxbean.cardano.yano.api.CanonicalBlockReference(
                        tip.getBlockNumber(), tip.getSlot(), tip.getBlockHash());
        long removed = ProjectionRestartReconciler.reconcile(outbox, projectionSink.coordinate(),
                bodyTip, runtimeYano::canonicalBlockReference, identity.requiredSections());
        if (removed > 0) {
            log.info("ADR-042 pre-drain reconciliation removed {} non-canonical pending envelope(s)",
                    removed);
        }
    }

    private void reconcileEpochCoverageToCanonicalTip(ChainQuery chain) {
        var tip = chain.getLocalTip();
        if (tip == null) {
            projectionSink.rollbackEpochArtifactCoverage(0, null, true);
            outbox.rollbackEpochArtifactGaps(0, null, true);
            return;
        }
        projectionSink.rollbackEpochArtifactCoverage(tip.getSlot(), tip.getBlockHash(), false);
        outbox.rollbackEpochArtifactGaps(tip.getSlot(), tip.getBlockHash(), false);
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
    private Set<ProjectionSectionType> configuredSections(Optional<String> storedFingerprint) {
        Optional<String> configuredValue = config.getOptionalValue(
                YanoPropertyKeys.History.PROJECTION_SECTIONS, String.class);
        if (configuredValue.isEmpty()) {
            if (storedFingerprint.isPresent()) {
                try {
                    return ProjectionIdentity.parseFingerprint(storedFingerprint.orElseThrow())
                            .requiredSections();
                } catch (RuntimeException e) {
                    throw new IllegalStateException("stored projection identity is malformed; "
                            + "the archive cannot preserve its omitted section selection and must be rebuilt", e);
                }
            }
            return java.util.Set.of(ProjectionSectionType.values());
        }
        String configured = configuredValue.orElseThrow().trim();
        if (configured.isEmpty()) {
            throw new IllegalArgumentException(
                    YanoPropertyKeys.History.PROJECTION_SECTIONS
                            + " was set but blank; name at least one versioned section");
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

    private void verifyCompleteUtxoRequirement(Set<ProjectionSectionType> required, Yano yano) {
        List<String> configuredFilters = yano.configuredUtxoStorageFilters();
        if (required.contains(ProjectionSectionType.ADDRESS_TRANSACTION)
                && !configuredFilters.isEmpty()) {
            throw new ProjectionActivationException(
                    "address-transaction projection requires a complete UTXO store, but canonical "
                            + "UTXO filtering is configured: " + configuredFilters);
        }
    }

    /**
     * Sink-engine settings the projection writer owns independently of the read facade.
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
        while (drainControl.isRunning()) {
            try {
                RuntimeMaintenanceGate.ReadLease readLease = runtimeYano.maintenanceGate()
                        .map(gate -> gate.enterRead("projection outbox drain"))
                        .orElse(null);
                boolean workPending = false;
                // Snapshot restore replaces RocksDB and every native CF handle. Holding the
                // shared runtime read lease makes the restore wait for this pass to finish;
                // DefaultUtxoStore then refreshes the contributor/outbox before the exclusive
                // maintenance lease is released.
                try (readLease) {
                    // Genesis must be durable before any block range is committed, or the archive
                    // would hold blocks against a distribution it never captured.
                    captureGenesisIfPossible();

                    ProjectionSinkLifecycle lifecycle = sinkLifecycle;
                    if (lifecycle != null && !lifecycle.isClosed()) {
                        drainEpochArtifactGaps(lifecycle);
                        // Every sink touch happens under the lifecycle lock, so shutdown can prove
                        // nothing is in flight before it closes anything.
                        ProjectionConsumerResult result = lifecycle.use(ignored -> consumer.drainOnce());
                        if (result.madeProgress()) {
                            drainedBatches.increment();
                            drainedBlocks.add(result.lastBlock() - result.firstBlock() + 1);
                            // A corrected replay may have atomically replaced GAP with COMPLETE.
                            drainedGapVersions.clear();
                        }
                        workPending = result.workPending();
                        if (!workPending) {
                            if (result.outcome() == ProjectionConsumerResult.Outcome.PAUSED) {
                                log.warn("ADR-039 projection drain paused: {}",
                                        result.detail().orElse("unknown"));
                            } else {
                                runMaintenanceIfDue();
                            }
                        }
                    }
                }
                // Keep draining while envelopes are still waiting, even when this pass only
                // accumulated. Backing off mid-bootstrap because nothing was committed yet
                // would idle the loop through the whole backlog.
                if (workPending) continue;
                drainControl.await(idleIntervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                drainFailures.increment();
                lastDrainFailure = t.toString();
                log.error("ADR-039 projection drain failed; retrying: {}", t.toString());
                try {
                    drainControl.await(Math.max(idleIntervalMillis, 1_000L));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Copy durable outbox GAP intent into the sink before ordinary artifact drain. */
    private void drainEpochArtifactGaps(ProjectionSinkLifecycle lifecycle) {
        // A crash after the sink atomically repaired rows/coverage but before the RocksDB
        // acknowledgement leaves stale outbox intent. Remove it before synchronizing intervals,
        // otherwise startup would re-expand the just-repaired sink range.
        int repaired = lifecycle.use(sink -> outbox.acknowledgeRepairsAlreadyComplete(
                sink::hasCompleteEpochArtifact));
        if (repaired > 0) drainedGapVersions.clear();
        var outboxGaps = outbox.epochArtifactGaps();
        var outboxIntervals = outbox.epochArtifactGapIntervals();

        for (var gap : outboxGaps) {
            String key = gap.dataset().name() + '/' + gap.semanticEpoch();
            String version = gapVersion(gap);
            if (version.equals(drainedGapVersions.get(key))) continue;
            lifecycle.use(sink -> {
                sink.recordEpochArtifactGap(gap);
                return null;
            });
            drainedGapVersions.put(key, version);
        }
        String intervalSetVersion = outboxIntervals.stream().map(interval ->
                        interval.dataset().name() + '/' + interval.causedByEpoch() + '/'
                                + intervalVersion(interval))
                .sorted().collect(java.util.stream.Collectors.joining("|"));
        if (!intervalSetVersion.equals(drainedIntervalSetVersion)) {
            var authoritative = outboxIntervals;
            lifecycle.use(sink -> {
                sink.replaceEpochArtifactGapIntervals(authoritative);
                return null;
            });
            drainedIntervalSetVersion = intervalSetVersion;
        }
    }

    private static String gapVersion(
            com.bloxbean.cardano.yano.archive.api.projection.EpochArtifactGap gap) {
        return gap.boundarySlot() + ":" + java.util.HexFormat.of().formatHex(gap.boundaryBlockHash())
                + ':' + gap.failureClass();
    }

    private static String intervalVersion(
            com.bloxbean.cardano.yano.archive.api.projection.EpochArtifactGapInterval interval) {
        return interval.fromEpoch() + ":" + interval.throughEpoch() + ':' + interval.open()
                + ':' + java.util.HexFormat.of().formatHex(interval.throughBoundaryHash());
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
        CanonicalProjectionContributor contributor = collector;
        if (epochStaging != null) {
            contributor = new EpochBindingProjectionContributor(
                    collector, epochStaging, this::stageStagedFileArtifact);
        }
        boolean installed = yano.installProjectionContributor(contributor);
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
        // The genesis-block event is the lifecycle TRIGGER and the source of the first-block
        // coordinate. The distribution itself always comes from the provider - see
        // ProjectionGenesisBootstrap - so the two cannot drift.
        bus.subscribe(com.bloxbean.cardano.yano.api.events.GenesisBlockEvent.class, ctx -> {
            var event = ctx.event();
            try {
                // The event fires for the first block applied, which on a Byron network is the
                // epoch boundary block - not the coordinate genesis is attributed to. Record the
                // trigger and complete the capture once the canonical block is available.
                pendingGenesisEventFunds =
                        event.bootstrapData() == null || event.bootstrapData().shelley() == null
                                ? java.util.Map.of()
                                : event.bootstrapData().shelley().initialFunds();
                genesisPending = true;
                captureGenesisIfPossible();
            } catch (RuntimeException e) {
                // Never swallow: an archive that missed genesis reports itself complete, and the
                // coverage gate below is the only other thing standing between that and a query
                // returning a wrong answer.
                drainFailures.increment();
                lastDrainFailure = "genesis bootstrap failed: " + e;
                log.error("ADR-039 genesis bootstrap failed", e);
                throw e;
            }
        }, SubscriptionOptions.builder().build());

        bus.subscribe(RollbackEvent.class, ctx -> {
            var target = ctx.event().target();
            if (target == null) return;
            boolean origin = target.getHash() == null;
            byte[] targetHash = origin ? null : decodeRollbackHash(target.getHash());
            long removed = collector.rollbackToPoint(target.getSlot(), targetHash, origin);
            int removedGaps = outbox.rollbackEpochArtifactGaps(target.getSlot(), targetHash, origin);
            drainedGapVersions.clear();
            drainedIntervalSetVersion = null;
            synchronizeStagingCaptureState();
            ProjectionSinkLifecycle lifecycleForRollback = sinkLifecycle;
            if (lifecycleForRollback != null && !lifecycleForRollback.isClosed()) {
                lifecycleForRollback.use(sink -> {
                    sink.rollbackEpochArtifactCoverage(target.getSlot(), targetHash, origin);
                    return null;
                });
            }
            // Whatever the drain thread has buffered may describe the discarded fork. The
            // flag is observed at its next safe point; taking a lock here would stall the
            // event bus behind an in-flight sink commit.
            ProjectionOutboxConsumer active = consumer;
            if (active != null) active.discardPendingBatch();
            if (removed > 0 || removedGaps > 0) {
                // The rollback deleted artifact references along with their envelopes. Re-derive
                // protection from what actually survives, or the reader would keep a source pinned
                // for an artifact that no longer exists and pruning would never resume.
                artifactReader.reconcileAfterRestart(outbox.pendingArtifacts());
                // Staged epoch files above the rollback point describe a discarded fork. The
                // cutoff is derived from the surviving canonical tip.
                var staging = epochStaging;
                if (staging != null) {
                    var tip = chainQuery == null ? null : chainQuery.getLocalTip();
                    if (tip != null) staging.discardAfterBlock(tip.getBlockNumber());
                }
                log.info("ADR-039 rollback to point slot={}, hash={} removed {} pending projection "
                                + "envelope(s) and {} epoch gap(s)",
                        target.getSlot(), target.getHash(), removed, removedGaps);
            }
        }, SubscriptionOptions.builder().build());
    }

    private void synchronizeStagingCaptureState() {
        var staging = epochStaging;
        if (staging == null || outbox == null) return;
        for (var dataset : stagedFileDatasets(selectedArtifacts)) {
            var archiveDataset = com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId
                    .valueOf(dataset.name());
            if (outbox.epochArtifactCaptureState(archiveDataset)
                    == com.bloxbean.cardano.yano.archive.api.projection.EpochArtifactCaptureState.PAUSED) {
                String detail = outbox.epochArtifactGaps().stream()
                        .filter(gap -> gap.dataset() == archiveDataset).reduce((a, b) -> b)
                        .map(com.bloxbean.cardano.yano.archive.api.projection.EpochArtifactGap::detail)
                        .orElse("paused after rollback");
                staging.restorePaused(dataset, detail);
            } else {
                staging.restoreActive(dataset);
            }
        }
    }

    private static byte[] decodeRollbackHash(String hash) {
        try {
            byte[] decoded = HexUtil.decodeHexString(hash);
            if (decoded.length != 32) {
                throw new IllegalArgumentException("Rollback hash must be exactly 32 bytes");
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid rollback hash: " + hash, e);
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
     * Current aggregate archive-retained footprint.
     *
     * <p>Staged-artifact bytes are measured, not assumed. They were a literal zero while Phase 5
     * produced no artifacts; leaving that in once it did meant the soft and hard archive limits
     * under-counted by exactly the evidence the phase added, and on mainnet a reward epoch is the
     * largest single thing the archive retains.
     *
     * <p>Pinned-generation bytes are explicitly marked unmeasured. The only
     * IMMUTABLE_GENERATION artifact shipped is epoch stake, whose generations live in ledger
     * state that this service does not own and cannot size without walking column families it has
     * no handle to. Reporting a fabricated number would be worse than reporting none.
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
        return new ArchiveRetainedFootprint(outboxBytes, stagedArtifactBytes(), 0,
                diskAmplification, free, false);
    }

    /**
     * Bytes currently held by staged epoch evidence.
     *
     * <p>Measured by walking the staging tree rather than tracked incrementally: evidence is
     * released by a different path than it is written, and a counter that drifts would silently
     * mis-size the budget in whichever direction it drifted. A failed or internally inconsistent
     * probe keeps the last known value and pauses ingestion until a complete probe succeeds.
     */
    private long stagedArtifactBytes() {
        long now = System.nanoTime();
        long probedAt = stagedBytesProbedAt;
        if (probedAt != 0 && now - probedAt < STAGED_BYTES_TTL_NANOS) return stagedBytesCached;

        long version = stagedBytesVersion.get();
        long measured = measureStagedArtifactBytes();
        if (version != stagedBytesVersion.get()) {
            // A stage or release overlapped the walk. Do not publish a mixed snapshot; the next
            // header retries immediately against the new tree.
            stagedBytesProbeHealthy = false;
            return stagedBytesCached;
        }
        if (measured >= 0) {
            stagedBytesCached = measured;
            stagedBytesProbeHealthy = true;
        } else {
            stagedBytesProbeHealthy = false;
        }
        // Publish the TTL timestamp last. A concurrent caller that observes a fresh probe must
        // also observe the value and health state produced by that probe.
        stagedBytesProbedAt = now;
        // The last known value remains useful for status. ingestDecision() separately pauses on
        // an unhealthy probe, including the first probe when no valid cached value exists.
        return stagedBytesCached;
    }

    /** Invalidate the cached measurement, so the next probe reflects a stage or release. */
    private void stagedBytesChanged() {
        stagedBytesVersion.incrementAndGet();
        stagedBytesProbedAt = 0;
    }

    /** Walk the staging tree. Returns -1 when the measurement could not be taken. */
    private long measureStagedArtifactBytes() {
        if (historyDirectory == null) return 0;
        java.nio.file.Path staged = historyDirectory.resolve("epoch-source");
        if (!java.nio.file.Files.isDirectory(staged)) return 0;
        try (var paths = java.nio.file.Files.walk(staged)) {
            long total = 0;
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                java.nio.file.Path path = iterator.next();
                java.nio.file.attribute.BasicFileAttributes attributes;
                try {
                    attributes = java.nio.file.Files.readAttributes(path,
                            java.nio.file.attribute.BasicFileAttributes.class);
                } catch (java.io.IOException unreadable) {
                    return -1;
                }
                if (attributes.isRegularFile()) total = Math.addExact(total, attributes.size());
            }
            return total;
        } catch (java.io.IOException | RuntimeException e) {
            return -1;
        }
    }

    /**
     * The runtime's common rollback floor, or {@link Long#MAX_VALUE} when it cannot be determined.
     *
     * <p>An unknown floor is deliberately mapped to the most restrictive value rather than to
     * zero. Zero asserts that rollback all the way to genesis is possible, which would let the
     * retention check pass for any artifact; {@code MAX_VALUE} makes it fail, which is what "we
     * cannot prove this is safe" should do. The placeholder this replaces returned zero.
     */
    private long commonRollbackFloorSlot() {
        Yano node = runtimeYano;
        if (node == null) return Long.MAX_VALUE;
        long floor = node.commonRollbackFloorSlot();
        return floor < 0 ? Long.MAX_VALUE : floor;
    }

    /** Whether canonical ingestion may continue. Re-evaluated against the live footprint. */
    public ArchiveIngestGate.Decision ingestDecision() {
        if (ingestGate == null) return new ArchiveIngestGate.Decision(
                ArchiveIngestGate.Decision.State.RUNNING, Optional.empty(), 0, 0, 0);
        ArchiveRetainedFootprint current = footprint();
        if (!stagedBytesProbeHealthy) {
            return new ArchiveIngestGate.Decision(ArchiveIngestGate.Decision.State.PAUSED,
                    Optional.of("staged-artifact disk usage could not be measured; canonical ingestion"
                            + " is paused until the probe succeeds"),
                    current.estimatedPhysicalBytes(), ingestGate.limits().hardBytes(),
                    current.filesystemFreeBytes());
        }
        return ingestGate.evaluate(current);
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

    /** A refused projection initialization keeps diagnostics live but makes the node unready. */
    public boolean hasInitializationFailure() {
        return enabled && initializationError != null;
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", enabled);
        if (!enabled || !initialized) {
            // Half-initialised is a state this must be able to describe, not one it dies on.
            if (enabled && initializationError != null) status.put("error", initializationError);
            return status;
        }
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
        status.put("genesisCaptured", genesisComplete);
        status.putAll(artifactStatus());
        return status;
    }

    /**
     * Epoch-artifact identity and the pruning protection currently outstanding.
     *
     * <p>Exposed because the artifact contracts are not part of the section fingerprint, so
     * without this an operator has no way to see which epoch datasets an archive is actually
     * being maintained under - and no way to see a lease that is holding retention open.
     */
    private Map<String, Object> artifactStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("artifactContracts", selectedArtifacts.wireForm());
        var pending = outbox.pendingArtifacts();
        status.put("pendingArtifacts", pending.size());
        // A shared/legacy staging failure disables every staged-file dataset and persists across
        // restarts. Unreported, it is indistinguishable from an archive that has not reached a
        // boundary yet.
        legacyStagingFailure().ifPresent(detail -> {
            status.put("epochStagingError", detail);
            status.put("epochStagingFailureActive",
                    !stagedFileDatasets(selectedArtifacts).isEmpty());
        });
        status.put("epochArtifacts", artifactCoverageEntries(pending));
        // Named rather than implied: the disk budget excludes pinned generations, and a bare
        // zero in the footprint would read as "nothing pinned" when it means "not measured".
        status.put("pinnedGenerationBytesMeasured", footprint().pinnedGenerationsMeasured());
        status.put("oldestPendingArtifactEpoch", pending.stream()
                .mapToInt(com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef::semanticEpoch)
                .min().orElse(-1));
        // What retention is actually being held open on this node's behalf.
        Yano node = runtimeYano;
        status.put("protectedSnapshotFloorEpoch", node == null ? -1
                : node.snapshotRetentionClamp().protectedSnapshotFloorEpoch());
        return status;
    }

    private java.util.List<Map<String, Object>> artifactCoverageEntries(
            java.util.List<com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef> pending) {
        Map<com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId,
                List<com.bloxbean.cardano.yano.archive.api.ArchiveRange>> complete =
                projectionSink == null ? Map.of() : projectionSink.epochArtifactCoverage();
        var entries = new java.util.ArrayList<Map<String, Object>>();
        var gaps = new java.util.LinkedHashMap<String,
                com.bloxbean.cardano.yano.archive.api.projection.EpochArtifactGap>();
        if (outbox != null) {
            outbox.epochArtifactGaps().forEach(gap -> gaps.put(
                    gap.dataset().name() + '/' + gap.semanticEpoch(), gap));
        }
        if (projectionSink != null) {
            projectionSink.epochArtifactGaps().forEach(gap -> gaps.putIfAbsent(
                    gap.dataset().name() + '/' + gap.semanticEpoch(), gap));
        }
        var intervals = outbox == null
                ? List.<com.bloxbean.cardano.yano.archive.api.projection.EpochArtifactGapInterval>of()
                : outbox.epochArtifactGapIntervals();
        var shipped = com.bloxbean.cardano.yano.archive.api.projection
                .ProjectionArtifactContracts.shipped();
        shipped.contracts().values().stream()
                .sorted(java.util.Comparator.comparing(
                        com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactContract::selector))
                .forEach(contract -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("dataset", contract.dataset().logicalName());
                    value.put("selector", contract.selector());
                    var enrollment = artifactEnrollments.enrollmentFor(contract.dataset());
                    value.put("selected", enrollment.isPresent());
                    if (enrollment.isEmpty()) {
                        value.put("coverageState", "NOT_SELECTED");
                    } else {
                        var enrolled = enrollment.orElseThrow();
                        value.put("origin", enrolled.origin().name());
                        if (enrolled.projectedFromEpoch().isPresent()) {
                            value.put("projectedFromEpoch", enrolled.projectedFromEpoch().getAsInt());
                            var captureState = outbox.epochArtifactCaptureState(contract.dataset());
                            value.put("captureState", captureState.name());
                            value.put("coverageState", captureState.name());
                        } else {
                            value.put("coverageState", "UNKNOWN_LEGACY_COVERAGE");
                        }
                        var completeRanges = complete.getOrDefault(contract.dataset(), List.of());
                        value.put("completeRanges", completeRanges.stream().map(range -> Map.of(
                                        "from", range.startInclusive(),
                                        "through", range.endInclusive())).toList());
                        var pendingEpochs = pending.stream()
                                .filter(ref -> ref.dataset() == contract.dataset())
                                .map(com.bloxbean.cardano.yano.archive.api.projection
                                        .ProjectionArtifactRef::semanticEpoch)
                                .distinct().sorted().toList();
                        value.put("pendingEpochs", pendingEpochs);
                        var datasetGaps = gaps.values().stream()
                                .filter(gap -> gap.dataset() == contract.dataset())
                                .sorted(java.util.Comparator.comparingInt(
                                        com.bloxbean.cardano.yano.archive.api.projection
                                                .EpochArtifactGap::semanticEpoch))
                                .toList();
                        value.put("gapCount", datasetGaps.size());
                        var datasetIntervals = intervals.stream()
                                .filter(interval -> interval.dataset() == contract.dataset()).toList();
                        value.put("gapRangeCount", datasetIntervals.size());
                        value.put("gapRanges", datasetIntervals.stream().map(interval -> Map.of(
                                "from", interval.fromEpoch(), "through", interval.throughEpoch(),
                                "open", interval.open(), "causedByEpoch", interval.causedByEpoch(),
                                "failureClass", interval.failureClass())).toList());
                        long observed = java.util.stream.LongStream.concat(
                                        completeRanges.stream().mapToLong(
                                                com.bloxbean.cardano.yano.archive.api.ArchiveRange::endInclusive),
                                        java.util.stream.LongStream.concat(
                                                datasetGaps.stream().mapToLong(gap -> gap.semanticEpoch()),
                                                datasetIntervals.stream().mapToLong(
                                                        interval -> interval.throughEpoch())))
                                .max().orElse(-1);
                        if (!pendingEpochs.isEmpty()) observed = Math.max(observed, pendingEpochs.getLast());
                        value.put("observedThroughEpoch", observed);
                        if (enrolled.projectedFromEpoch().isPresent()) {
                            long contiguous = contiguousCompleteThrough(
                                    enrolled.projectedFromEpoch().getAsInt(), completeRanges);
                            if (contiguous >= 0) value.put("contiguousCompleteThroughEpoch", contiguous);
                        }
                        if (!datasetGaps.isEmpty() || !datasetIntervals.isEmpty()) {
                            int firstGap = Math.min(
                                    datasetGaps.stream().mapToInt(gap -> gap.semanticEpoch())
                                            .min().orElse(Integer.MAX_VALUE),
                                    datasetIntervals.stream().mapToInt(interval -> interval.fromEpoch())
                                            .min().orElse(Integer.MAX_VALUE));
                            int lastGap = Math.max(
                                    datasetGaps.stream().mapToInt(gap -> gap.semanticEpoch())
                                            .max().orElse(-1),
                                    datasetIntervals.stream().mapToInt(interval -> interval.throughEpoch())
                                            .max().orElse(-1));
                            value.put("firstGapEpoch", firstGap);
                            value.put("lastGapEpoch", lastGap);
                        }
                        value.put("resumeApplicable",
                                outbox.epochArtifactCaptureState(contract.dataset())
                                        == com.bloxbean.cardano.yano.archive.api.projection
                                                .EpochArtifactCaptureState.PAUSED
                                && stagedFileDatasets(selectedArtifacts).stream()
                                        .anyMatch(dataset -> dataset.name().equals(contract.dataset().name()))
                                && legacyStagingFailure().isEmpty());
                    }
                    entries.add(Map.copyOf(value));
                });
        return List.copyOf(entries);
    }

    private static long contiguousCompleteThrough(long start,
            List<com.bloxbean.cardano.yano.archive.api.ArchiveRange> ranges) {
        long through = start - 1;
        for (var range : ranges.stream()
                .sorted(java.util.Comparator.comparingLong(
                        com.bloxbean.cardano.yano.archive.api.ArchiveRange::startInclusive)).toList()) {
            if (range.endInclusive() < start) continue;
            if (range.startInclusive() > through + 1) break;
            through = Math.max(through, range.endInclusive());
        }
        return through >= start ? through : -1;
    }

    /** Resume future capture for a paused selected staged dataset; existing gaps remain. */
    public synchronized Map<String, Object> resumeEpochArtifact(
            com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId dataset) {
        Objects.requireNonNull(dataset, "dataset");
        if (!initialized || !enabled) throw new IllegalStateException("projection history is not initialized");
        if (artifactEnrollments.enrollmentFor(dataset).isEmpty()) {
            throw new IllegalArgumentException(dataset.logicalName() + " is not selected in this archive");
        }
        if (outbox.epochArtifactCaptureState(dataset)
                != com.bloxbean.cardano.yano.archive.api.projection.EpochArtifactCaptureState.PAUSED) {
            throw new IllegalStateException(dataset.logicalName() + " is not paused");
        }
        com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink.Dataset stagingDataset;
        try {
            stagingDataset = com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink.Dataset
                    .valueOf(dataset.name());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(dataset.logicalName()
                    + " does not use resumable staged-file capture", e);
        }
        if (!stagedFileDatasets(selectedArtifacts).contains(stagingDataset) || epochStaging == null) {
            throw new IllegalArgumentException(dataset.logicalName()
                    + " does not use resumable staged-file capture");
        }
        var tip = chainQuery == null ? null : chainQuery.getLocalTip();
        if (tip == null || tip.getBlockHash() == null) {
            throw new IllegalStateException("cannot resume without a canonical full-point tip");
        }
        epochStaging.resumeDurably(stagingDataset, () -> outbox.resumeEpochArtifact(
                dataset, tip.getSlot(), tip.getBlockHash()));
        log.warn("ADR-045 resumed future {} capture; {} existing gap(s) remain",
                dataset.logicalName(), outbox.epochArtifactGaps().stream()
                        .filter(gap -> gap.dataset() == dataset).count());
        return artifactCoverageEntries(outbox.pendingArtifacts()).stream()
                .filter(entry -> dataset.logicalName().equals(entry.get("dataset")))
                .findFirst().orElseThrow();
    }

    /** Audited operator transition for the pre-ADR-045 unstructured FAILED marker. */
    public synchronized Map<String, Object> acknowledgeLegacyStagingFailure() {
        if (historyDirectory == null) throw new IllegalStateException("projection history directory is unresolved");
        if (epochStaging != null && epochStaging.boundaryOpen()) {
            throw new IllegalStateException("cannot acknowledge a legacy failure during an epoch boundary");
        }
        java.nio.file.Path marker = historyDirectory.resolve("epoch-source").resolve("FAILED");
        if (!java.nio.file.Files.isRegularFile(marker)) {
            throw new IllegalStateException("no legacy epoch staging failure marker exists");
        }
        java.nio.file.Path audit = marker.resolveSibling("FAILED.acknowledged."
                + Instant.now().toString().replace(':', '-'));
        try {
            java.nio.file.Files.move(marker, audit, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            try { java.nio.file.Files.move(marker, audit); }
            catch (java.io.IOException nested) { throw new IllegalStateException("cannot acknowledge marker", nested); }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot acknowledge marker", e);
        }
        log.warn("ADR-045 legacy epoch staging failure acknowledged by operator; audit copy={}", audit);
        return Map.of("acknowledged", true, "auditFile", audit.getFileName().toString(),
                // A staging service that observed the marker keeps its in-memory global latch;
                // and a future staged enrollment is resolved only during initialization.
                "restartRequired", true);
    }

    /** Fail closed before an unbounded epoch-history read can silently skip unavailable epochs. */
    public void requireCompleteEpochHistory(
            com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId dataset) {
        requireCompleteEpochHistory(dataset, null, null);
    }

    /** Range-aware guard used by epoch snapshot APIs as they move to the archive (ADR-046). */
    public void requireCompleteEpochHistory(
            com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId dataset,
            Integer fromEpoch, Integer throughEpoch) {
        if (fromEpoch != null && (fromEpoch < 0 || throughEpoch == null || throughEpoch < fromEpoch)) {
            throw new IllegalArgumentException("epoch history range is invalid");
        }
        if (fromEpoch == null && throughEpoch != null) {
            throw new IllegalArgumentException("from epoch is required when through epoch is supplied");
        }
        var enrollment = artifactEnrollments.enrollmentFor(dataset).orElseThrow(() ->
                new IncompleteEpochHistoryException(dataset, "NOT_SELECTED", List.of(
                        Map.of("reason", "NOT_SELECTED"))));
        var missing = new java.util.ArrayList<Map<String, Object>>();
        boolean stagedDataset = selectedArtifacts.contractFor(dataset)
                .map(contract -> contract.representation()
                        == com.bloxbean.cardano.yano.archive.api.projection
                                .ProjectionArtifactRepresentation.STAGED_FILE)
                .orElse(false);
        if (stagedDataset) {
            legacyStagingFailure().ifPresent(detail -> missing.add(Map.of(
                    "reason", "UNKNOWN_LEGACY_FAILURE",
                    "detail", detail,
                    "repair", "rebuild or acknowledge the audited legacy marker, then restart")));
        }
        int projectedFrom = enrollment.projectedFromEpoch().orElse(0);
        int requestedFrom = fromEpoch != null ? fromEpoch
                : enrollment.origin() == com.bloxbean.cardano.yano.archive.api.projection
                        .ProjectionArtifactEnrollmentOrigin.PROSPECTIVE_JOIN ? 0 : projectedFrom;
        if (enrollment.projectedFromEpoch().isEmpty()) {
            missing.add(Map.of("reason", "UNKNOWN_LEGACY_COVERAGE"));
        } else if (requestedFrom < projectedFrom && (fromEpoch != null || enrollment.origin()
                == com.bloxbean.cardano.yano.archive.api.projection
                        .ProjectionArtifactEnrollmentOrigin.PROSPECTIVE_JOIN)) {
            missing.add(Map.of("reason", "NOT_PROJECTED", "from", requestedFrom, "through",
                    Math.min(throughEpoch == null ? projectedFrom - 1 : throughEpoch,
                            projectedFrom - 1)));
        }
        var gaps = new java.util.LinkedHashMap<Integer,
                com.bloxbean.cardano.yano.archive.api.projection.EpochArtifactGap>();
        if (outbox != null) outbox.epochArtifactGaps().stream()
                .filter(gap -> gap.dataset() == dataset).forEach(gap -> gaps.put(gap.semanticEpoch(), gap));
        if (projectionSink != null) projectionSink.epochArtifactGaps().stream()
                .filter(gap -> gap.dataset() == dataset).forEach(gap -> gaps.putIfAbsent(gap.semanticEpoch(), gap));
        int observed = gaps.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
        var intervals = new java.util.LinkedHashMap<String,
                com.bloxbean.cardano.yano.archive.api.projection.EpochArtifactGapInterval>();
        if (projectionSink != null) projectionSink.epochArtifactGapIntervals().stream()
                .filter(interval -> interval.dataset() == dataset)
                .forEach(interval -> intervals.put(interval.causedByEpoch() + "/" + interval.fromEpoch(), interval));
        if (outbox != null) outbox.epochArtifactGapIntervals().stream()
                .filter(interval -> interval.dataset() == dataset)
                .forEach(interval -> intervals.put(interval.causedByEpoch() + "/" + interval.fromEpoch(), interval));
        observed = Math.max(observed, intervals.values().stream()
                .mapToInt(interval -> interval.throughEpoch()).max().orElse(-1));
        var pending = outbox == null ? List.<Integer>of() : outbox.pendingArtifacts().stream()
                .filter(ref -> ref.dataset() == dataset)
                .map(com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef::semanticEpoch)
                .distinct().sorted().toList();
        var complete = projectionSink == null ? List.<com.bloxbean.cardano.yano.archive.api.ArchiveRange>of()
                : projectionSink.epochArtifactCoverage().getOrDefault(dataset, List.of());
        observed = Math.max(observed, Math.toIntExact(complete.stream()
                .mapToLong(com.bloxbean.cardano.yano.archive.api.ArchiveRange::endInclusive)
                .max().orElse(-1)));
        observed = Math.max(observed, pending.stream().mapToInt(Integer::intValue).max().orElse(-1));
        int requestedThrough = throughEpoch == null ? Math.max(observed, projectedFrom) : throughEpoch;
        java.util.function.IntPredicate intersects = epoch -> epoch >= requestedFrom
                && epoch <= requestedThrough;
        gaps.values().stream().filter(gap -> intersects.test(gap.semanticEpoch()))
                .forEach(gap -> missing.add(Map.of("reason", "GAP", "from", gap.semanticEpoch(),
                        "through", gap.semanticEpoch(), "failureClass", gap.failureClass())));
        intervals.values().stream().filter(interval -> interval.throughEpoch() >= requestedFrom
                        && interval.fromEpoch() <= requestedThrough)
                .forEach(interval -> missing.add(Map.of("reason", "GAP",
                        "from", Math.max(requestedFrom, interval.fromEpoch()),
                        "through", Math.min(requestedThrough, interval.throughEpoch()),
                        "failureClass", interval.failureClass(), "pausedInterval", true)));
        var requestedPending = pending.stream().filter(intersects::test).limit(100).toList();
        if (!requestedPending.isEmpty()) {
            missing.add(Map.of("reason", "PENDING", "epochs", requestedPending));
        }
        if (complete.isEmpty() && requestedThrough >= Math.max(requestedFrom, projectedFrom)) {
            missing.add(Map.of("reason", "PENDING", "detail", "no epoch is complete yet"));
        } else {
            int cursor = Math.max(requestedFrom, projectedFrom);
            var known = new java.util.ArrayList<com.bloxbean.cardano.yano.archive.api.ArchiveRange>(complete);
            gaps.values().forEach(gap -> known.add(new com.bloxbean.cardano.yano.archive.api.EpochRange(
                    gap.semanticEpoch(), gap.semanticEpoch())));
            intervals.values().forEach(interval -> known.add(new com.bloxbean.cardano.yano.archive.api.EpochRange(
                    interval.fromEpoch(), interval.throughEpoch())));
            pending.forEach(epoch -> known.add(new com.bloxbean.cardano.yano.archive.api.EpochRange(epoch, epoch)));
            for (var range : known.stream().sorted(java.util.Comparator.comparingLong(
                    com.bloxbean.cardano.yano.archive.api.ArchiveRange::startInclusive)).toList()) {
                if (range.endInclusive() < cursor || range.startInclusive() > requestedThrough) continue;
                if (range.startInclusive() > cursor) {
                    missing.add(Map.of("reason", "UNKNOWN", "from", cursor,
                            "through", Math.min(requestedThrough, range.startInclusive() - 1)));
                }
                cursor = Math.max(cursor, Math.toIntExact(range.endInclusive() + 1));
                if (cursor > requestedThrough) break;
            }
            if (cursor <= requestedThrough) {
                missing.add(Map.of("reason", "UNKNOWN", "from", cursor, "through", requestedThrough));
            }
        }
        if (!missing.isEmpty()) {
            String reason = missing.stream().map(value -> String.valueOf(value.get("reason")))
                    .distinct().collect(java.util.stream.Collectors.joining(","));
            var status = new java.util.LinkedHashMap<String, Object>();
            status.put("selected", true);
            enrollment.projectedFromEpoch().ifPresent(value -> status.put("projectedFromEpoch", value));
            status.put("captureState", outbox == null ? "UNKNOWN"
                    : outbox.epochArtifactCaptureState(dataset).name());
            status.put("completeRanges", complete.stream().map(range -> Map.of(
                    "from", range.startInclusive(), "through", range.endInclusive())).toList());
            status.put("resumeApplicable", outbox != null
                    && outbox.epochArtifactCaptureState(dataset)
                    == com.bloxbean.cardano.yano.archive.api.projection.EpochArtifactCaptureState.PAUSED);
            throw new IncompleteEpochHistoryException(dataset, reason, missing, status);
        }
    }

    /** Low-cardinality values consumed by the Micrometer adapter. */
    public Map<String, Map<String, Double>> epochArtifactMetrics() {
        var complete = projectionSink == null
                ? Map.<com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId,
                        List<com.bloxbean.cardano.yano.archive.api.ArchiveRange>>of()
                : projectionSink.epochArtifactCoverage();
        var gaps = outbox == null ? List.<com.bloxbean.cardano.yano.archive.api.projection.EpochArtifactGap>of()
                : outbox.epochArtifactGaps();
        var intervals = outbox == null
                ? List.<com.bloxbean.cardano.yano.archive.api.projection.EpochArtifactGapInterval>of()
                : outbox.epochArtifactGapIntervals();
        var result = new java.util.TreeMap<String, Map<String, Double>>();
        for (var contract : com.bloxbean.cardano.yano.archive.api.projection
                .ProjectionArtifactContracts.shipped().contracts().values()) {
            var dataset = contract.dataset();
            var enrollment = artifactEnrollments.enrollmentFor(dataset);
            var ranges = complete.getOrDefault(dataset, List.of());
            var datasetGaps = gaps.stream().filter(gap -> gap.dataset() == dataset).toList();
            double lastComplete = ranges.stream().mapToLong(
                    com.bloxbean.cardano.yano.archive.api.ArchiveRange::endInclusive).max().orElse(-1);
            double lastGap = datasetGaps.stream().mapToInt(
                    com.bloxbean.cardano.yano.archive.api.projection.EpochArtifactGap::semanticEpoch)
                    .max().orElse(-1);
            double lastInterval = intervals.stream().filter(interval -> interval.dataset() == dataset)
                    .mapToInt(com.bloxbean.cardano.yano.archive.api.projection
                            .EpochArtifactGapInterval::throughEpoch).max().orElse(-1);
            var values = new java.util.LinkedHashMap<String, Double>();
            values.put("selected", enrollment.isPresent() ? 1d : 0d);
            values.put("paused", outbox != null && outbox.epochArtifactCaptureState(dataset)
                    == com.bloxbean.cardano.yano.archive.api.projection.EpochArtifactCaptureState.PAUSED
                    ? 1d : 0d);
            values.put("projectedFrom", enrollment.isPresent()
                    && enrollment.orElseThrow().projectedFromEpoch().isPresent()
                    ? (double) enrollment.orElseThrow().projectedFromEpoch().getAsInt() : Double.NaN);
            values.put("lastComplete", lastComplete);
            values.put("observedThrough", Math.max(lastComplete, Math.max(lastGap, lastInterval)));
            values.put("gaps", (double) datasetGaps.size());
            values.put("gapRanges", (double) intervals.stream()
                    .filter(interval -> interval.dataset() == dataset).count());
            for (String failureClass : List.of("io", "filesystem", "capacity", "capture")) {
                values.put("gaps." + failureClass, (double) datasetGaps.stream()
                        .filter(gap -> failureClass.equals(gap.failureClass())).count());
            }
            result.put(dataset.logicalName(), Map.copyOf(values));
        }
        return Map.copyOf(result);
    }

    private Optional<String> legacyStagingFailure() {
        var staging = epochStaging;
        if (staging != null && staging.failure().isPresent()) return staging.failure();
        var directory = historyDirectory;
        if (directory == null) return Optional.empty();
        java.nio.file.Path marker = directory.resolve("epoch-source").resolve("FAILED");
        if (!java.nio.file.Files.isRegularFile(marker)) return Optional.empty();
        try {
            return Optional.of(java.nio.file.Files.readString(marker));
        } catch (java.io.IOException e) {
            return Optional.of("legacy epoch staging failure marker is unreadable: " + e.getMessage());
        }
    }

    /**
     * Datasets this archive can answer for: every projected section plus every captured artifact.
     *
     * <p>Used to route historical reads. Deliberately not "all datasets" - a query for something
     * the projection does not maintain must be refused rather than answered from an empty table.
     */
    public Set<com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId> coveredDatasets() {
        if (!enabled || identity == null) return Set.of();
        var covered = new java.util.LinkedHashSet<com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId>();
        identity.requiredSections().forEach(section -> covered.add(section.dataset()));
        selectedArtifacts.contracts().keySet().forEach(covered::add);
        return Set.copyOf(covered);
    }

    /** Greatest durably committed block, or -1 before the first batch. */
    public long committedThroughBlock() {
        var sink = projectionSink;
        if (sink == null) return -1L;
        var coordinate = sink.coordinate();
        return coordinate.isPresent() ? coordinate.blockNumber() : -1L;
    }

    /**
     * Cross-dataset consistency point, derived from the projection's own receipts.
     *
     * <p>The projection's answer is also stronger: every required section for a block range
     * commits in one transaction with its receipt, so the committed coordinate is a consistency
     * point across all datasets by construction rather than by intersection.
     *
     * @return empty when projection history is disabled or unavailable
     */
    public Optional<Map<String, Object>> consistencyPoint(Set<com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId> requested) {
        if (!enabled || projectionSink == null) return Optional.empty();

        var coordinate = projectionSink.coordinate();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("source", "projection");
        if (!coordinate.isPresent()) {
            // Explicitly not "unavailable": the archive exists and is healthy, it simply has no
            // committed range yet. A caller must not read this as a missing archive.
            value.put("available", false);
            value.put("reason", "no projection batch has committed yet");
            return Optional.of(value);
        }

        // Every required section is committed for the same range, so a dataset the caller asked
        // for is either covered by that range or not projected at all.
        var covered = coveredDatasets();
        var missing = requested.stream()
                .filter(dataset -> !covered.contains(dataset))
                .map(com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId::logicalName)
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            value.put("available", false);
            value.put("reason", "not projected by this archive: " + String.join(", ", missing));
            return Optional.of(value);
        }

        // A selected epoch dataset is not necessarily complete: it may have joined
        // prospectively or carry a durable gap. The block receipt coordinate alone cannot prove
        // epoch-history completeness.
        for (var dataset : requested) {
            if (dataset.sourceKind() != com.bloxbean.cardano.yano.archive.api.SourceKind.EPOCH) continue;
            try {
                requireCompleteEpochHistory(dataset);
            } catch (IncompleteEpochHistoryException incomplete) {
                value.put("available", false);
                value.put("reason", incomplete.getMessage());
                value.put("epochCoverage", incomplete.response());
                return Optional.of(value);
            }
        }

        if (!genesisComplete) {
            // A fresh archive must not claim complete block coverage before its genesis
            // distribution is durable, or a balance query over a genesis-funded address that
            // never moved would answer "nothing" instead of "not yet".
            value.put("available", false);
            value.put("reason", "the genesis distribution has not been captured yet");
            return Optional.of(value);
        }

        value.put("available", true);
        value.put("generation", identity.fingerprint());
        value.put("fromBlock", 0L);
        value.put("toBlock", coordinate.blockNumber());

        // Resolve the coordinate canonically instead of publishing what the sink stored. A sink
        // only needs the block number to recognise a committed range, so it is free to store a
        // placeholder slot and hash - and DuckLake does. Publishing those would put fabricated
        // values in an API that callers use to pin a consistency point. If the chain cannot
        // resolve it, asOf is omitted rather than invented.
        ChainQuery chain = chainQuery;
        if (chain != null) {
            chain.getCanonicalBlockReference(coordinate.blockNumber()).ifPresent(canonical ->
                    value.put("asOf", Map.of(
                            "blockNumber", canonical.blockNumber(),
                            "slot", canonical.slot(),
                            "blockHash", java.util.HexFormat.of().formatHex(canonical.blockHash()))));
        }
        Map<String, Integer> versions = new java.util.TreeMap<>();
        identity.requiredSections().forEach(section -> versions.put(section.dataset().logicalName(),
                com.bloxbean.cardano.yano.archive.api.schema.ArchiveSchemas
                        .schema(section.dataset()).projectionVersion()));
        value.put("projectionVersions", versions);
        return Optional.of(value);
    }

    /**
     * What this archive can answer for, stated so a caller never mistakes lag for absence.
     *
     * <p>Near tip a block can be final and durable in the outbox but not yet committed to the
     * sink, for up to the batch linger plus one maintenance budget. A query for that range must
     * be told the range is not yet covered rather than returned an empty result, which is
     * indistinguishable from "this never happened".
     */
    public Map<String, Object> coverage() {
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("enabled", enabled);
        if (!enabled || !initialized) {
            // Throwing here would turn a diagnosable misconfiguration into an opaque 500 on the
            // page an operator opens to diagnose it.
            if (enabled && initializationError != null) coverage.put("error", initializationError);
            return coverage;
        }

        coverage.put("identity", identity.fingerprint());
        coverage.put("sections", identity.requiredSections().stream()
                .map(ProjectionSectionType::wireName).sorted().toList());
        coverage.put("artifactContracts", selectedArtifacts.wireForm());
        coverage.put("epochArtifacts", artifactCoverageEntries(outbox.pendingArtifacts()));

        long committedThrough = -1;
        if (projectionSink != null) {
            var coordinate = projectionSink.coordinate();
            committedThrough = coordinate.isPresent() ? coordinate.blockNumber() : -1;
            coverage.put("sinkHealth", projectionSink.health().state().name());
        }
        // The queryable floor is genesis: ADR-039 archives are fresh-sync only, so there is no
        // partial lower bound to report.
        coverage.put("genesisCaptured", genesisComplete);
        // Coverage is only claimable from genesis once genesis itself is durable.
        coverage.put("queryableFromBlock", committedThrough < 0 || !genesisComplete ? -1 : 0);
        coverage.put("queryableThroughBlock", genesisComplete ? committedThrough : -1);

        var tip = chainTip == null ? -1 : chainTip.getAsLong();
        coverage.put("tipBlock", tip);
        coverage.put("blocksBehindTip", tip < 0 || committedThrough < 0 ? -1 : tip - committedThrough);

        ProjectionOutboxConsumer active = consumer;
        if (active != null) {
            var policy = active.batchPolicy();
            // The honest upper bound on how long a final block can take to become queryable.
            coverage.put("maxCommitLatency", policy.maxLinger(active.nearTip()).toString());
        }
        // The read facade builds a best-effort tx-hash locator when it opens. It is an accelerator,
        // never coverage authority: a missing or stale hint falls back to the pinned transaction
        // table, preserving correctness while the projection continues to append.
        coverage.put("transactionHashLookup", Map.of(
                "mode", "derived-hint-with-authoritative-fallback",
                "correct", true,
                "note", "a missing or stale locator hint scans the pinned transactions table"));
        legacyStagingFailure().ifPresent(detail -> {
            coverage.put("epochStagingError", detail);
            coverage.put("epochStagingFailureActive",
                    !stagedFileDatasets(selectedArtifacts).isEmpty());
        });
        coverage.put("note", "blocks above queryableThroughBlock are not yet committed to the"
                + " archive; treat them as unknown rather than absent");
        return coverage;
    }

    /** Bounded canonical gap detail; the summary endpoint never emits an unbounded failure list. */
    public Map<String, Object> coverageDetails(
            com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId dataset,
            Integer fromEpoch, Integer toEpoch, Integer offset, Integer limit) {
        Objects.requireNonNull(dataset, "dataset");
        int from = fromEpoch == null ? 0 : fromEpoch;
        int through = toEpoch == null ? Integer.MAX_VALUE : toEpoch;
        int skip = offset == null ? 0 : offset;
        int pageSize = limit == null ? 50 : limit;
        if (from < 0 || through < from) {
            throw new IllegalArgumentException("epoch detail range is invalid");
        }
        if (skip < 0 || pageSize < 1 || pageSize > 200) {
            throw new IllegalArgumentException("offset must be non-negative and limit must be 1..200");
        }
        Map<String, Object> result = new LinkedHashMap<>(coverage());
        if (!enabled || !initialized) return Map.copyOf(result);
        var all = new java.util.LinkedHashMap<String, Map<String, Object>>();
        outbox.epochArtifactGaps().stream()
                .filter(gap -> gap.dataset() == dataset
                        && gap.semanticEpoch() >= from && gap.semanticEpoch() <= through)
                .forEach(gap -> all.put("point/" + gap.semanticEpoch(), Map.of(
                        "kind", "POINT", "from", gap.semanticEpoch(), "through", gap.semanticEpoch(),
                        "blockNumber", gap.boundaryBlockNumber(), "slot", gap.boundarySlot(),
                        "blockHash", java.util.HexFormat.of().formatHex(gap.boundaryBlockHash()),
                        "failureClass", gap.failureClass(), "detail", gap.detail())));
        outbox.epochArtifactGapIntervals().stream()
                .filter(interval -> interval.dataset() == dataset
                        && interval.throughEpoch() >= from && interval.fromEpoch() <= through)
                .forEach(interval -> all.put("range/" + interval.causedByEpoch() + '/'
                                + interval.fromEpoch(), Map.of(
                        "kind", "PAUSED_RANGE", "from", interval.fromEpoch(),
                        "through", interval.throughEpoch(), "open", interval.open(),
                        "causedByEpoch", interval.causedByEpoch(),
                        "failureClass", interval.failureClass())));
        var ordered = all.values().stream()
                .sorted(java.util.Comparator.<Map<String, Object>>comparingInt(
                                value -> ((Number) value.get("from")).intValue())
                        .thenComparing(value -> String.valueOf(value.get("kind"))))
                .toList();
        result.put("gapDetail", Map.of(
                "dataset", dataset.logicalName(), "fromEpoch", from,
                "toEpoch", through == Integer.MAX_VALUE ? "latest" : through,
                "offset", skip, "limit", pageSize, "total", ordered.size(),
                "items", ordered.stream().skip(skip).limit(pageSize).toList()));
        return Map.copyOf(result);
    }

    @Override
    public void close() {
        // Stop new sink work, let an in-flight commit finish, and preserve the outbox.
        // Shutdown never deletes pending projection data.
        drainControl.stop();
        Thread thread = drainThread;
        if (thread != null) {
            // The cooperative signal breaks an idle/retry wait without interrupting JDBC. The
            // join is what proves an in-flight operation completed before the sink is closed.
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
