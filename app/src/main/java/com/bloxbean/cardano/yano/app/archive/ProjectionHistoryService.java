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
    /** Live chain tip, for reporting how far behind tip the archive's coverage is. */
    private volatile java.util.function.LongSupplier chainTip;

    /** Held so published coordinates can be resolved canonically rather than reported as stored. */
    private volatile ChainQuery chainQuery;

    /** Held for the genesis coordinate's block time. */
    private volatile LedgerQuery ledgerQuery;

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
     * <p>Owned here rather than by the legacy service because Phase 7a deletes that service's
     * block machinery. REWARD, DREP_DISTRIBUTION and GOVERNANCE_PROPOSAL_STATUS still depend on
     * staged epoch files, so their lifecycle has to move before the old owner is reduced -
     * otherwise deleting it silently disables three datasets, which is the same shape of failure
     * as the missing genesis distribution and just as invisible.
     *
     * <p>Retired dataset by dataset: each entry disappears from {@code UNMIGRATED} as its
     * projection artifact lands, and the whole field goes with Phase 7b when the set empties.
     */
    private volatile EpochArchiveStagingService epochStaging;

    /**
     * Epoch datasets still served by staged files rather than by a projection artifact.
     *
     * <p>Derived from the shipped artifact contracts, so it cannot drift: an artifact that ships
     * removes itself from this set automatically.
     */
    private static java.util.Set<com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink.Dataset>
            unmigratedEpochDatasets() {
        var migrated = com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactContracts
                .shipped().contracts().keySet();
        var pending = java.util.EnumSet.noneOf(
                com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink.Dataset.class);
        for (var dataset : com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.values()) {
            if (dataset.sourceKind() != com.bloxbean.cardano.yano.archive.api.SourceKind.EPOCH) continue;
            if (migrated.contains(dataset)) continue;
            try {
                pending.add(com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink.Dataset
                        .valueOf(dataset.name()));
            } catch (IllegalArgumentException noStagingCounterpart) {
                // An epoch dataset with no staging counterpart cannot be served that way at all.
            }
        }
        return pending;
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
     * This mirrors {@code HistoryArchiveService.firstCanonicalBlockNumber} exactly - the two
     * pipelines must attribute genesis to the same coordinate or every genesis row differs.
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
        firstCanonicalBlock = genesis.hasByronGenesis() ? 1L : 0L;
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

        outbox.putIdentity(identity);
        verifyArtifactContracts(sinkEmpty && acknowledgedThrough < 0, chain, ledger);

        installEpochArtifacts(yano, chain, ledger, network.networkMagic());
        installEpochStagingForUnmigratedDatasets(chain, ledger, network);

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



    /**
     * Refuse an archive whose epoch artifacts differ from what this build captures.
     *
     * <p>The section fingerprint cannot carry this: artifacts are referenced from envelopes, not
     * named in the identity, so two nodes capturing different artifact sets produce the same
     * fingerprint. Without this check the archive would keep reporting an artifact nobody was
     * still maintaining, or claim one it never captured.
     */
    private void verifyArtifactContracts(boolean freshArchive, ChainQuery chain, LedgerQuery ledger) {
        var shipped = com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactContracts.shipped();
        var stored = com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactIdentity
                .parse(outbox.artifactIdentityWire().orElse(""));

        if (outbox.artifactIdentityWire().isEmpty() && !freshArchive) {
            // An archive written before artifacts existed. Adding them mid-archive would leave
            // every earlier epoch permanently missing, so it is a rebuild, not an upgrade.
            throw new IllegalStateException("this archive predates epoch artifacts and holds blocks"
                    + " already; enabling " + shipped.wireForm() + " requires a fresh sync");
        }

        // An empty archive has no epochs to cover, so the coverage rule is vacuous there. A
        // populated one must prove the sources still exist, and the default coverage proves nothing.
        int throughEpoch = freshArchive ? -1 : currentEpoch(chain, ledger);
        shipped.refuseToOpen(stored,
                        com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactCoverage.NONE,
                        0, throughEpoch)
                .ifPresent(reason -> { throw new IllegalStateException(
                        "projection artifacts do not match this archive: " + reason); });

        outbox.putArtifactIdentity(shipped.wireForm());
    }

    /** Current epoch, or a value that keeps the coverage rule engaged when it cannot be read. */
    private int currentEpoch(ChainQuery chain, LedgerQuery ledger) {
        try {
            var tip = chain.getLocalTip();
            if (tip == null) return Integer.MAX_VALUE;
            return Math.toIntExact(ledger.slotToEpoch(tip.getSlot()));
        } catch (RuntimeException e) {
            // Fail closed: returning 0 would make the range empty and skip the coverage check.
            log.warn("ADR-039 could not read the current epoch; treating artifact coverage as unproven");
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Install the epoch-artifact contributor and the reader that serves it.
     *
     * <p>Deliberately not configurable. Artifact datasets are not sections, so they do not enter
     * the projection fingerprint: a node that ran with epoch artifacts disabled and was later
     * restarted with them enabled would present the same fingerprint while its {@code epoch_stakes}
     * table had a hole, and the startup guard could not see it. Always-on removes the failure mode
     * rather than detecting it.
     */
    private void installEpochArtifacts(Yano yano, ChainQuery chain, LedgerQuery ledger, long networkMagic) {
        var clamp = yano.snapshotRetentionClamp();
        var store = yano.accountStateStoreForArtifacts().orElseThrow(() -> new IllegalStateException(
                "projection history requires an account-state store: epoch artifacts are read from the"
                        + " delegation snapshot the epoch boundary persists"));

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

        artifactReader = new RoutingArtifactReader(java.util.Map.of(
                com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.EPOCH_STAKE,
                new EpochSnapshotArtifactReader(store, clamp, pageSize, networkMagic, boundaryFacts),
                com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.ADA_POT,
                new AdaPotArtifactReader(networkMagic, boundaryFacts)));

        // The state version must match the replay worker's exactly. Both write it into
        // source_state_version, and a mismatch would make identical data look like it came from
        // different producers.
        var collector = new com.bloxbean.cardano.yano.archive.core.projection.EpochArtifactCollector(
                outbox, clamp, true,
                com.bloxbean.cardano.yano.archive.api.schema.ArchiveSchemas
                        .schema(com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.EPOCH_STAKE)
                        .projectionVersion(),
                // Base only: the collector appends the per-dataset part, matching the replay
                // worker's "snapshot" for epoch stake and "final" for the ada pot.
                "ledger-boundary-v1");

        if (!yano.installEpochArtifactContributor(collector)) {
            throw new IllegalStateException("could not install the epoch artifact contributor;"
                    + " epoch datasets would be silently missing from the archive");
        }
        log.info("ADR-039 epoch artifacts enabled ({}, {} rows per artifact page)",
                com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactContracts
                        .shipped().wireForm(), pageSize);
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


    /**
     * Keep staged epoch files flowing for datasets the projection cannot yet serve.
     *
     * <p>Strictly transitional. Every dataset here is one the projection has not migrated, and
     * the whole method disappears in Phase 7b once the set is empty.
     */
    private void installEpochStagingForUnmigratedDatasets(ChainQuery chain, LedgerQuery ledger,
                                                          ArchiveNetworkIdentity network) {
        var pending = unmigratedEpochDatasets();
        if (pending.isEmpty()) {
            log.info("ADR-039 every epoch dataset is served by a projection artifact;"
                    + " no staged epoch files are produced");
            return;
        }
        epochStaging = new EpochArchiveStagingService(chain, ledger, network,
                historyDirectory.resolve("epoch-source"), pending);
        ledger.setEpochArchiveStagingSink(epochStaging);
        log.info("ADR-039 epoch staging retained for {} unmigrated dataset(s): {}",
                pending.size(), pending);
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
                // Genesis must be durable before any block range is committed, or the archive
                // would hold blocks against a distribution it never captured.
                captureGenesisIfPossible();

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
            long removed = collector.rollbackToSlot(target.getSlot());
            // Whatever the drain thread has buffered may describe the discarded fork. The
            // flag is observed at its next safe point; taking a lock here would stall the
            // event bus behind an in-flight sink commit.
            ProjectionOutboxConsumer active = consumer;
            if (active != null) active.discardPendingBatch();
            if (removed > 0) {
                // The rollback deleted artifact references along with their envelopes. Re-derive
                // protection from what actually survives, or the reader would keep a source pinned
                // for an artifact that no longer exists and pruning would never resume.
                artifactReader.reconcileAfterRestart(outbox.pendingArtifacts());
                // Staged epoch files above the rollback point describe a discarded fork. The
                // cutoff is derived from the surviving tip, since the rollback point is a slot.
                var staging = epochStaging;
                if (staging != null) {
                    var tip = chainQuery == null ? null : chainQuery.getLocalTip();
                    if (tip != null) staging.discardAfterBlock(tip.getBlockNumber());
                }
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
        status.put("artifactContracts",
                com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactContracts
                        .shipped().wireForm());
        var pending = outbox.pendingArtifacts();
        status.put("pendingArtifacts", pending.size());
        status.put("oldestPendingArtifactEpoch", pending.stream()
                .mapToInt(com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef::semanticEpoch)
                .min().orElse(-1));
        // What retention is actually being held open on this node's behalf.
        Yano node = runtimeYano;
        status.put("protectedSnapshotFloorEpoch", node == null ? -1
                : node.snapshotRetentionClamp().protectedSnapshotFloorEpoch());
        return status;
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
        com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactContracts.shipped()
                .contracts().keySet().forEach(covered::add);
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
     * <p>The legacy watermark is computed from {@code archive_coverage}, which the projection
     * never writes. Reporting that here once the projection is the primary writer would say
     * "nothing is archived" over a complete archive - the false absence the ADR forbids.
     *
     * <p>The projection's answer is also stronger: every required section for a block range
     * commits in one transaction with its receipt, so the committed coordinate is a consistency
     * point across all datasets by construction rather than by intersection.
     *
     * @return empty when the projection is not the primary writer, so the caller can fall back
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
        var missing = requested.stream()
                .filter(dataset -> dataset.sourceKind() == com.bloxbean.cardano.yano.archive.api.SourceKind.BLOCK)
                .filter(dataset -> identity.requiredSections().stream()
                        .noneMatch(section -> section.dataset() == dataset))
                .map(com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId::logicalName)
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            value.put("available", false);
            value.put("reason", "not projected by this archive: " + String.join(", ", missing));
            return Optional.of(value);
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
        if (!enabled || outbox == null) return coverage;

        coverage.put("identity", identity.fingerprint());
        coverage.put("sections", identity.requiredSections().stream()
                .map(ProjectionSectionType::wireName).sorted().toList());
        coverage.put("artifactContracts",
                com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactContracts
                        .shipped().wireForm());

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
        // ADR-039 Phase 6 requires this to be stated rather than discovered. The projection does
        // not build the replay worker's SQLite tx-hash locator, so a lookup by transaction hash
        // falls back to a full-range scan of the transactions table. The result is correct - the
        // locator was only ever an accelerator, and the fallback query is the authoritative one -
        // but it is O(archive), not O(1), until a derived index exists.
        coverage.put("transactionHashLookup", Map.of(
                "mode", "full-scan",
                "correct", true,
                "note", "no derived tx-hash index is built for projection archives; lookup by hash"
                        + " scans the transactions table and is not suitable for hot paths"));
        coverage.put("note", "blocks above queryableThroughBlock are not yet committed to the"
                + " archive; treat them as unknown rather than absent");
        return coverage;
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
