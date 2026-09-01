package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.ChainQuery;
import com.bloxbean.cardano.yano.api.LedgerQuery;
import com.bloxbean.cardano.yano.api.archive.EpochArchiveStagingSink;
import com.bloxbean.cardano.yano.archive.api.*;
import com.bloxbean.cardano.yano.archive.core.dataset.*;
import com.bloxbean.cardano.yano.archive.core.source.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Streams ephemeral epoch facts to immutable restartable source files. */
final class EpochArchiveStagingService implements EpochArchiveStagingSink {
    private final ChainQuery chain;
    private final LedgerQuery ledger;
    private final ArchiveNetworkIdentity network;
    private final Path root;
    private final Set<Dataset> enabled;
    private final Map<Dataset, Integer> projectedFrom;
    private final int firstPostByronEpoch;
    private final Map<String, SourceBinding<?>> sources = new ConcurrentHashMap<>();
    /** Fast negative check: public-network blocks never scan provisional directories. */
    private final Set<Long> provisionalBoundaries = ConcurrentHashMap.newKeySet();
    /** Durable evidence announced during the open boundary, even if a fast sink already released it. */
    private final Set<DatasetBoundary> announcedEvidence = ConcurrentHashMap.newKeySet();
    private volatile Boundary boundary;
    /** True only while the prepared boundary's source epoch is still Byron. */
    private volatile boolean skipCurrentBoundary;
    private final Map<Dataset, String> datasetErrors = new ConcurrentHashMap<>();
    private final Map<Dataset, Long> failedAtBlock = new ConcurrentHashMap<>();
    private volatile String error;

    EpochArchiveStagingService(ChainQuery chain, LedgerQuery ledger, ArchiveNetworkIdentity network,
                               Path root, Set<Dataset> enabled, int firstPostByronEpoch) {
        this(chain, ledger, network, root, enabled, firstPostByronEpoch,
                enabled.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                        dataset -> dataset, ignored -> 0)));
    }

    EpochArchiveStagingService(ChainQuery chain, LedgerQuery ledger, ArchiveNetworkIdentity network,
                               Path root, Set<Dataset> enabled, int firstPostByronEpoch,
                               Map<Dataset, Integer> projectedFrom) {
        if (firstPostByronEpoch < 0) {
            throw new IllegalArgumentException("firstPostByronEpoch must be non-negative");
        }
        this.chain = chain; this.ledger = ledger; this.network = network;
        this.root = root.toAbsolutePath().normalize(); this.enabled = Set.copyOf(enabled);
        if (!projectedFrom.keySet().equals(this.enabled)) {
            throw new IllegalArgumentException("projected-from datasets must match enabled staging datasets");
        }
        if (projectedFrom.values().stream().anyMatch(epoch -> epoch == null || epoch < 0)) {
            throw new IllegalArgumentException("projected-from epochs must be non-negative");
        }
        this.projectedFrom = Map.copyOf(projectedFrom);
        this.firstPostByronEpoch = firstPostByronEpoch;
        try {
            Files.createDirectories(completedDirectory());
            try (var files = Files.list(completedDirectory())) {
                for (Path path : files.filter(Files::isRegularFile).toList()) {
                    if (path.getFileName().toString().endsWith(".tmp")) Files.deleteIfExists(path);
                }
            }
            if (Files.exists(failureMarker())) error = Files.readString(failureMarker(), StandardCharsets.UTF_8);
        }
        catch (Exception e) { throw new ArchiveStoreException("cannot create epoch completion directory", e); }
        registerKnownSources();
        refreshProvisionalBoundaries();
    }

    public boolean enabled(Dataset dataset) {
        Boundary current = boundary;
        Integer floor = projectedFrom.get(dataset);
        return floor != null && current != null && current.newEpoch() >= floor
                && error == null && !datasetErrors.containsKey(dataset) && !skipCurrentBoundary;
    }

    /**
     * Why all staged-file capture stopped, when it has.
     *
     * <p>This is the legacy/shared-infrastructure latch. Dataset-local failures are exposed by
     * {@link #datasetFailures()} and pause only that dataset. A shared failure is written to the
     * legacy marker and disables every staged dataset until an audited operator action.
     */
    public java.util.Optional<String> failure() { return java.util.Optional.ofNullable(error); }
    public Map<Dataset, String> datasetFailures() { return Map.copyOf(datasetErrors); }
    boolean boundaryOpen() { return boundary != null; }
    public void beginBoundary(Boundary value) {
        boundary = Objects.requireNonNull(value, "epoch boundary is required");
        skipCurrentBoundary = value.previousEpoch() < firstPostByronEpoch
                || projectedFrom.values().stream().noneMatch(floor -> value.newEpoch() >= floor);
    }
    public void completeBoundary(Boundary value) {
        if (!Objects.equals(boundary, value)) return;
        try {
            if (skipCurrentBoundary || enabled.isEmpty()) return;
            if (error == null) {
                var listener = failureListener;
                if (listener != null) {
                    for (Dataset dataset : datasetErrors.keySet()) {
                        Integer floor = projectedFrom.get(dataset);
                        if (floor != null && value.newEpoch() >= floor
                                && !Objects.equals(failedAtBlock.get(dataset), value.blockNumber())) {
                            listener.missed(dataset, value);
                        }
                    }
                }
                ensureZeroRowEvidence(value);
                if (datasetErrors.size() < enabled.size() && hasPendingAtBoundary(value.blockNumber())) {
                    publishCompleted(value);
                }
            }
            else discardBoundary(value);
        } catch (Exception e) { failGlobal(e); }
        finally { clearBoundary(); }
    }

    /**
     * Give every healthy selected staged dataset a positive outcome at every eligible boundary.
     *
     * <p>A producer that legitimately has no rows may never open a writer (notably governance
     * before Conway). Without an empty artifact, absence is indistinguishable from a producer
     * that silently failed. A zero-row staged job is small, durable evidence that the dataset was
     * evaluated and empty; the sink then commits its {@code COMPLETE} outcome with row count zero.
     */
    private void ensureZeroRowEvidence(Boundary value) {
        for (Dataset dataset : enabled) {
            if (datasetErrors.containsKey(dataset)) continue;
            boolean present = announcedEvidence.contains(new DatasetBoundary(dataset, value.blockNumber()))
                    || sources().stream()
                    .filter(binding -> binding.dataset() == ArchiveDatasetId.valueOf(dataset.name()))
                    .anyMatch(binding -> binding.source().pending(Integer.MAX_VALUE).stream()
                            .anyMatch(job -> job.boundaryBlockNumber() == value.blockNumber())
                            || binding.provisional().pending().stream()
                            .anyMatch(job -> job.boundaryBlockNumber() == value.blockNumber()));
            if (present) continue;

            FactWriter<?> empty = switch (dataset) {
                // Reuse registered source parts so restart discovery sees zero-row evidence too.
                case REWARD -> openRewards(value.newEpoch(), "rewards");
                case DREP_DISTRIBUTION -> openDrep(value.newEpoch());
                case GOVERNANCE_PROPOSAL_STATUS -> openGovernance(value.newEpoch(), "lifecycle");
                // These datasets use the direct, batch-owned artifact contributor and never
                // belong to a staged-file selection.
                case EPOCH_STAKE, ADA_POT -> null;
            };
            if (empty != null) empty.commit();
        }
    }

    private boolean hasPendingAtBoundary(long blockNumber) {
        return sources().stream().anyMatch(binding ->
                binding.source().pending(Integer.MAX_VALUE).stream()
                        .anyMatch(job -> job.boundaryBlockNumber() == blockNumber)
                        || binding.provisional().pending().stream()
                        .anyMatch(job -> job.boundaryBlockNumber() == blockNumber));
    }
    public void abortBoundary(Boundary value) {
        if (!Objects.equals(boundary, value)) return;
        // Core epoch phases commit independently. Preserve source parts from
        // already-committed phases so startup recovery can add the remaining
        // parts and publish one whole-boundary completion marker.
        try {
            if (!skipCurrentBoundary) Files.deleteIfExists(completion(value.blockNumber()));
        }
        catch (Exception e) { failGlobal(e); }
        finally { clearBoundary(); }
    }
    private void clearBoundary() {
        Boundary completed = boundary;
        if (completed != null) {
            announcedEvidence.removeIf(value -> value.blockNumber() == completed.blockNumber());
        }
        boundary = null;
        skipCurrentBoundary = false;
    }
    Optional<String> error() { return Optional.ofNullable(error); }
    Collection<SourceBinding<?>> sources() { return List.copyOf(sources.values()); }

    List<EpochArchiveJob> pending(SourceBinding<?> binding, int limit) {
        return binding.source().pending(Integer.MAX_VALUE).stream().filter(this::isCompleted).limit(limit).toList();
    }

    void acknowledge(SourceBinding<?> binding, EpochArchiveJob job) {
        binding.source().acknowledge(job);
        binding.provisional().acknowledgeBound(job.jobId()).stream()
                .map(ProvisionalEpochArchiveJob::boundaryBlockNumber)
                .forEach(this::refreshProvisionalBoundary);
        boolean remaining = hasPendingAtBoundary(job.boundaryBlockNumber());
        if (!remaining) try { Files.deleteIfExists(completion(job.boundaryBlockNumber())); }
        catch (Exception e) { throw new ArchiveStoreException("cannot clean epoch completion marker", e); }
    }

    int discardAfterEpoch(long epoch) {
        int discarded = 0;
        for (SourceBinding<?> binding : sources()) {
            discarded += discardProvisionalAfterEpoch(binding, epoch);
            discarded += binding.source().discardAfterEpoch(epoch);
        }
        try (var markers = Files.list(completedDirectory())) {
            for (Path marker : markers.filter(path -> Files.isRegularFile(path)
                    && path.getFileName().toString().endsWith(".properties")).toList()) {
                Properties values = readProperties(marker);
                if (Long.parseLong(values.getProperty("newEpoch")) > epoch) Files.deleteIfExists(marker);
            }
        } catch (Exception e) { throw new ArchiveStoreException("cannot discard epoch completion markers", e); }
        refreshProvisionalBoundaries();
        return discarded;
    }

    int discardAfterBlock(long block) {
        int discarded = 0;
        for (SourceBinding<?> binding : sources()) {
            discarded += discardProvisionalAfterBlock(binding, block);
            discarded += binding.source().discardAfterBlock(block);
        }
        try (var markers = Files.list(completedDirectory())) {
            for (Path marker : markers.filter(path -> Files.isRegularFile(path)
                    && path.getFileName().toString().endsWith(".properties")).toList()) {
                Properties values = readProperties(marker);
                if (Long.parseLong(values.getProperty("block")) > block) Files.deleteIfExists(marker);
            }
        } catch (Exception e) { throw new ArchiveStoreException("cannot discard epoch completion markers", e); }
        refreshProvisionalBoundaries();
        return discarded;
    }

    public FactWriter<StakeFact> openStake(int epoch) {
        return writer(Dataset.EPOCH_STAKE, ArchiveDatasetId.EPOCH_STAKE, epoch, "snapshot",
                StandardEpochFactCodecs.EPOCH_STAKE, StandardEpochDatasets.epochStake(), fact ->
                        new EpochStakeFact(credentialType(fact.credentialType()), hex(fact.credentialHash()),
                                hex(fact.poolHash()), exact(fact.amount())));
    }
    public FactWriter<DrepFact> openDrep(int epoch) {
        return writer(Dataset.DREP_DISTRIBUTION, ArchiveDatasetId.DREP_DISTRIBUTION, epoch, "distribution",
                StandardEpochFactCodecs.DREP, StandardEpochDatasets.drepDistribution(), fact ->
                        new DrepDistributionFact(drepType(fact.drepType()),
                                fact.drepType() <= 1 ? nullableHex(fact.credentialHash()) : null,
                                exact(fact.amount()), fact.storedExpiry() == null ? null : fact.storedExpiry().longValue(),
                                fact.dormantEpochs(), fact.effectiveExpiry() == null ? null : fact.effectiveExpiry().longValue(),
                                fact.active()));
    }
    public FactWriter<AdaPotFact> openAdaPot(int epoch) {
        return writer(Dataset.ADA_POT, ArchiveDatasetId.ADA_POT, epoch, "final",
                StandardEpochFactCodecs.ADA_POT, StandardEpochDatasets.adaPot(), fact ->
                        new com.bloxbean.cardano.yano.archive.core.dataset.AdaPotFact(exact(fact.treasury()),
                                exact(fact.reserves()), exact(fact.deposits()), exact(fact.fees()),
                                exact(fact.distributed()), exact(fact.undistributed()), exact(fact.rewardsPot()),
                                exact(fact.poolRewardsPot())));
    }
    public FactWriter<GovernanceFact> openGovernance(int epoch, String part) {
        return writer(Dataset.GOVERNANCE_PROPOSAL_STATUS, ArchiveDatasetId.GOVERNANCE_PROPOSAL_STATUS,
                epoch, part, StandardEpochFactCodecs.GOVERNANCE, StandardEpochDatasets.governanceProposalStatus(),
                fact -> new GovernanceProposalStatusFact(hex(fact.txHash()), fact.governanceActionIndex(),
                        fact.actionType().toLowerCase(Locale.ROOT), fact.observationPhase().toLowerCase(Locale.ROOT),
                        fact.statusCode().toLowerCase(Locale.ROOT), fact.decisionReason(), exact(fact.deposit()),
                        hex(fact.returnAddress()), fact.submittedEpoch(), fact.expiresAfterEpoch()));
    }
    public FactWriter<RewardFact> openRewards(int epoch, String part) {
        return writer(Dataset.REWARD, ArchiveDatasetId.REWARD, epoch, part,
                StandardEpochFactCodecs.REWARD, StandardEpochDatasets.rewards(), fact ->
                        new com.bloxbean.cardano.yano.archive.core.dataset.RewardFact(hex(fact.credentialHash()),
                                credentialType(fact.credentialType()), nullableHex(fact.poolHash()),
                                fact.rewardType().toLowerCase(Locale.ROOT), fact.earnedEpoch(), fact.spendableEpoch(),
                                exact(fact.amount()), fact.sourceId()));
    }

    private <I, O> FactWriter<I> writer(Dataset selected, ArchiveDatasetId dataset, long epoch, String part,
                                         EpochFactCodec<O> codec, EpochArchiveDataset<O> projection,
                                         Function<I, O> converter) {
        if (!enabled(selected) || epoch < projectedFrom.getOrDefault(selected, Integer.MAX_VALUE)) return noop();
        try {
            Boundary current = Objects.requireNonNull(boundary, "epoch boundary was not prepared");
            String key = dataset.name() + "/" + safePart(part);
            @SuppressWarnings("unchecked")
            SourceBinding<O> binding = (SourceBinding<O>) sources.computeIfAbsent(key,
                    ignored -> binding(key, dataset, codec, projection));
            String state = "ledger-boundary-v1/" + safePart(part);
            String reference = key + "/" + epoch;
            var canonical = chain.getCanonicalBlockReference(current.blockNumber());
            EpochArchiveJob job = null;
            ProvisionalEpochArchiveJob provisional = null;
            DurableEpochFileSource.StagingWriter<O> output;
            if (canonical.isPresent()) {
                if (canonical.orElseThrow().slot() != current.slot()) {
                    throw new ArchiveStoreException("epoch boundary slot mismatch");
                }
                job = canonicalJob(dataset, projection.projectionVersion(), epoch, current,
                        canonical.orElseThrow().blockHash(), state, reference);
                output = binding.source().open(job);
            } else {
                UUID captureId = UUID.nameUUIDFromBytes(("provisional|" + network.canonicalForm() + '|'
                        + dataset + '|' + epoch + '|' + current.blockNumber() + '|' + current.slot()
                        + '|' + state).getBytes(StandardCharsets.UTF_8));
                provisional = new ProvisionalEpochArchiveJob(captureId, network, dataset,
                        projection.projectionVersion(), epoch, current.blockNumber(), current.slot(),
                        state, reference, Instant.now());
                output = binding.provisional().open(provisional);
            }
            EpochArchiveJob committedJob = job;
            ProvisionalEpochArchiveJob committedProvisional = provisional;
            return new FactWriter<>() {
                private boolean done;
                private boolean failed;
                public void append(I fact) {
                    if (done || failed) return;
                    try {
                        output.append(converter.apply(fact));
                    } catch (Exception e) {
                        failed = true;
                        output.close();
                        fail(selected, Math.toIntExact(epoch), e);
                    }
                }
                public void commit() {
                    if (done) return;
                    done = true;
                    if (failed) { output.close(); return; }
                    try {
                        output.commit();
                        // Only now is the evidence durable, so only now may anything reference it.
                        if (committedJob != null) {
                            announceStaged(binding, committedJob);
                        } else {
                            provisionalBoundaries.add(committedProvisional.boundaryBlockNumber());
                            announcedEvidence.add(new DatasetBoundary(selected,
                                    committedProvisional.boundaryBlockNumber()));
                        }
                    }
                    catch (Exception e) { output.close(); fail(selected, Math.toIntExact(epoch), e); }
                }
                public void abort() { if (!done) { done = true; output.close(); } }
            };
        } catch (Exception e) {
            fail(selected, Math.toIntExact(epoch), e); return noop();
        }
    }

    /** Evidence bound to the exact block that will atomically carry its outbox reference. */
    record BoundArtifact(EpochArchiveJob job, DurableEpochFileSource.StagedEvidence evidence) { }

    boolean hasProvisionalBoundary(long blockNumber) {
        return provisionalBoundaries.contains(blockNumber);
    }

    List<BoundArtifact> bindCanonicalBoundary(long blockNumber, long slot, byte[] blockHash) {
        if (!hasProvisionalBoundary(blockNumber)) return List.of();
        if (blockHash == null || blockHash.length == 0) {
            throw new IllegalArgumentException("canonical boundary hash is required");
        }
        List<BoundArtifact> bound = new ArrayList<>();
        for (SourceBinding<?> binding : sources()) {
            for (ProvisionalEpochArchiveJob capture : binding.provisional().pending()) {
                if (capture.boundaryBlockNumber() != blockNumber) continue;
                if (capture.boundarySlot() != slot) {
                    throw new ArchiveStoreException("epoch boundary slot mismatch at block " + blockNumber);
                }
                if (!isCompleted(capture)) continue;
                EpochArchiveJob job = canonicalJob(capture.dataset(), capture.projectionVersion(),
                        capture.epoch(), new Boundary(0, Math.toIntExact(capture.epoch()), slot, blockNumber),
                        blockHash, capture.sourceStateVersion(), capture.sourceReference());
                bound.add(bind(binding, capture, job));
            }
        }
        return List.copyOf(bound);
    }

    private EpochArchiveJob canonicalJob(ArchiveDatasetId dataset, int projectionVersion, long epoch,
                                         Boundary boundary, byte[] blockHash, String state,
                                         String reference) {
        UUID id = UUID.nameUUIDFromBytes((network.canonicalForm() + '|' + dataset + '|' + epoch + '|'
                + boundary.blockNumber() + '|' + HexUtil.encodeHexString(blockHash) + '|' + state)
                .getBytes(StandardCharsets.UTF_8));
        return new EpochArchiveJob(id, network, dataset, projectionVersion, epoch,
                boundary.blockNumber(), boundary.slot(), ledger.slotToUnixTime(boundary.slot()),
                blockHash, state, reference, Instant.now());
    }

    /**
     * Notified once a job's evidence is durable on disk.
     *
     * <p>Ordering is the whole safety argument: the rows are fsynced and published before this
     * fires, so a reference recorded here implies durable evidence. A crash in the gap leaves an
     * orphaned staged file, which cleanup removes - never a reference to evidence that is not
     * there.
     */
    @FunctionalInterface
    interface StagedArtifactListener {
        void staged(EpochArchiveJob job, DurableEpochFileSource.StagedEvidence evidence);
    }

    private volatile StagedArtifactListener stagedArtifactListener;

    void setStagedArtifactListener(StagedArtifactListener listener) {
        this.stagedArtifactListener = listener;
    }

    /** Announce durable evidence, after commit and never before. */
    private void announceStaged(SourceBinding<?> binding, EpochArchiveJob job) {
        var listener = stagedArtifactListener;
        if (!(binding.source() instanceof DurableEpochFileSource<?> durable)) return;
        if (listener != null) listener.staged(job, durable.evidenceOf(job));
        announcedEvidence.add(new DatasetBoundary(
                Dataset.valueOf(job.dataset().name()), job.boundaryBlockNumber()));
    }

    /**
     * Materialise a staged job's rows, through the dataset derivation inherited from the
     * replay worker, so staged evidence yields byte-identical rows to what it produced.
     *
     * <p>Evidence is verified before the first row - a truncated or corrupt file raises rather
     * than yielding a short epoch - and the archive job identity is deterministic over the staged
     * job, so a replay after a crash reproduces the same {@code archive_job_id}.
     */
    List<com.bloxbean.cardano.yano.archive.api.ArchiveRow> materialise(ArchiveDatasetId dataset,
                                                                       java.util.UUID jobId) {
        for (SourceBinding<?> binding : bindingsFor(dataset)) {
            EpochArchiveJob job = jobIn(binding, jobId);
            if (job != null) return materialise(binding, job);
        }
        throw new IllegalStateException("staged epoch job " + jobId + " for " + dataset
                + " is no longer present in any source");
    }

    /** One page of a staged job's archive rows, with the cursor that continues it. */
    record MaterialisedPage(List<com.bloxbean.cardano.yano.archive.api.ArchiveRow> rows,
                            java.util.Optional<String> nextCursor) { }

    /**
     * Derive a single page of archive rows, instead of an entire epoch.
     *
     * <p>A mainnet reward epoch is over a million rows. Accumulating all of them, then encoding
     * them into a second list, makes peak heap proportional to the largest epoch the chain has
     * ever had - for evidence that cannot be recomputed if the drain dies holding it.
     */
    MaterialisedPage materialisePage(ArchiveDatasetId dataset, java.util.UUID jobId,
                                     java.util.Optional<String> cursor, int limit) {
        for (SourceBinding<?> binding : bindingsFor(dataset)) {
            EpochArchiveJob job = jobIn(binding, jobId);
            if (job != null) return materialisePage(binding, job, cursor, limit);
        }
        throw new IllegalStateException("staged epoch job " + jobId + " for " + dataset
                + " is no longer present in any source");
    }

    private <T> MaterialisedPage materialisePage(SourceBinding<T> binding, EpochArchiveJob job,
                                                 java.util.Optional<String> cursor, int limit) {
        List<com.bloxbean.cardano.yano.archive.api.ArchiveRow> rows = new ArrayList<>();
        var lease = binding.source().acquire(job, Instant.now().plus(java.time.Duration.ofMinutes(30)));
        try {
            var page = binding.source().read(job, cursor, limit, lease);
            binding.projection().derive(
                    com.bloxbean.cardano.yano.archive.api.ArchiveJob.deterministic(
                            job.networkIdentity(), job.dataset(), job.projectionVersion(),
                            new com.bloxbean.cardano.yano.archive.api.EpochRange(job.epoch(), job.epoch()),
                            new com.bloxbean.cardano.yano.archive.api.ArchiveRangeAnchor(
                                    job.boundarySlot(), job.boundaryBlockHash(),
                                    job.boundarySlot(), job.boundaryBlockHash()),
                            job.sourceStateVersion()),
                    page, rows::add);
            return new MaterialisedPage(rows, page.nextCursor());
        } finally {
            lease.close();
        }
    }

    private <T> List<com.bloxbean.cardano.yano.archive.api.ArchiveRow> materialise(
            SourceBinding<T> binding, EpochArchiveJob job) {
        List<com.bloxbean.cardano.yano.archive.api.ArchiveRow> rows = new ArrayList<>();
        var lease = binding.source().acquire(job, Instant.now().plus(java.time.Duration.ofMinutes(30)));
        try {
            java.util.Optional<String> cursor = java.util.Optional.empty();
            do {
                var page = binding.source().read(job, cursor, 50_000, lease);
                binding.projection().derive(
                        com.bloxbean.cardano.yano.archive.api.ArchiveJob.deterministic(
                                job.networkIdentity(), job.dataset(), job.projectionVersion(),
                                new com.bloxbean.cardano.yano.archive.api.EpochRange(job.epoch(), job.epoch()),
                                new com.bloxbean.cardano.yano.archive.api.ArchiveRangeAnchor(
                                        job.boundarySlot(), job.boundaryBlockHash(),
                                        job.boundarySlot(), job.boundaryBlockHash()),
                                job.sourceStateVersion()),
                        page, rows::add);
                cursor = page.nextCursor();
            } while (cursor.isPresent());
        } finally {
            lease.close();
        }
        return rows;
    }

    /** Whether a staged job's evidence is still present and intact. */
    boolean present(ArchiveDatasetId dataset, java.util.UUID jobId) {
        for (SourceBinding<?> binding : bindingsFor(dataset)) {
            EpochArchiveJob job = jobIn(binding, jobId);
            if (job == null) continue;
            try {
                binding.source().evidenceOf(job);
                return true;
            } catch (RuntimeException damaged) {
                return false;
            }
        }
        return false;
    }

    /**
     * Release staged evidence once the sink has durably committed its rows.
     *
     * <p>Acknowledgement is what permits cleanup - never the reverse. Deleting before a receipt
     * exists would destroy evidence that cannot be recomputed.
     */
    void release(ArchiveDatasetId dataset, java.util.UUID jobId) {
        Set<Long> releasedBoundaries = new HashSet<>();
        for (SourceBinding<?> binding : bindingsFor(dataset)) {
            EpochArchiveJob job = jobIn(binding, jobId);
            if (job != null) {
                binding.source().acknowledge(job);
                releasedBoundaries.add(job.boundaryBlockNumber());
            }
            binding.provisional().acknowledgeBound(jobId).stream()
                    .map(ProvisionalEpochArchiveJob::boundaryBlockNumber)
                    .forEach(releasedBoundaries::add);
            if (job != null) break;
        }
        for (long blockNumber : releasedBoundaries) {
            refreshProvisionalBoundary(blockNumber);
            if (!hasPendingAtBoundary(blockNumber)) {
                try {
                    Files.deleteIfExists(completion(blockNumber));
                } catch (Exception e) {
                    throw new ArchiveStoreException("cannot clean epoch completion marker", e);
                }
            }
        }
        // Already released: acknowledgement is replayed after a crash, so this is not an error.
    }

    /**
     * Every source for a dataset, not just the first.
     *
     * <p>Bindings are keyed by dataset AND part: rewards alone have separate sources for the
     * calculator, MIR certificates and governance withdrawals. Looking only at the first meant a
     * job staged under one part was invisible when asked for through another, and the drain read
     * that as evidence that had gone missing.
     */
    private List<SourceBinding<?>> bindingsFor(ArchiveDatasetId dataset) {
        List<SourceBinding<?>> matching = new ArrayList<>();
        for (SourceBinding<?> binding : sources()) {
            if (binding.dataset() == dataset) matching.add(binding);
        }
        if (matching.isEmpty()) throw new IllegalStateException("no staged epoch source for " + dataset);
        return matching;
    }

    /** The job in this source, or null when it belongs to a different part. */
    private EpochArchiveJob jobIn(SourceBinding<?> binding, java.util.UUID jobId) {
        for (EpochArchiveJob job : binding.source().pending(Integer.MAX_VALUE)) {
            if (job.jobId().equals(jobId)) return job;
        }
        return null;
    }

    @FunctionalInterface
    interface DatasetFailureListener {
        void failed(Dataset dataset, int semanticEpoch, Boundary boundary, Exception failure);

        default void missed(Dataset dataset, Boundary boundary) { }
    }

    private volatile DatasetFailureListener failureListener;

    void setDatasetFailureListener(DatasetFailureListener listener) {
        this.failureListener = listener;
    }

    void restorePaused(Dataset dataset, String detail) {
        if (enabled.contains(dataset)) datasetErrors.put(dataset, detail == null ? "paused" : detail);
    }

    void restoreActive(Dataset dataset) {
        datasetErrors.remove(dataset);
        failedAtBlock.remove(dataset);
    }

    synchronized void resumeDurably(Dataset dataset, Runnable persistActive) {
        if (!enabled.contains(dataset)) throw new IllegalArgumentException("unselected staging dataset " + dataset);
        if (boundary != null) throw new IllegalStateException("cannot resume while an epoch boundary is open");
        durabilityProbe();
        Objects.requireNonNull(persistActive, "persistActive").run();
        datasetErrors.remove(dataset);
        failedAtBlock.remove(dataset);
    }

    private void fail(Dataset dataset, int semanticEpoch, Exception e) {
        if (datasetErrors.containsKey(dataset)) return;
        Boundary current = boundary;
        if (current == null) {
            failGlobal(e);
            return;
        }
        try {
            var listener = failureListener;
            if (listener == null) throw new IllegalStateException("dataset failure listener is not installed");
            listener.failed(dataset, semanticEpoch, current, e);
            datasetErrors.put(dataset, failureDetail(e));
            failedAtBlock.put(dataset, current.blockNumber());
            discardDatasetBoundary(dataset, current);
        } catch (Exception persistenceFailure) {
            persistenceFailure.addSuppressed(e);
            failGlobal(persistenceFailure);
        }
    }

    private void failGlobal(Exception e) {
        Boundary current = boundary;
        String detail = failureDetail(e);

        // A shared staging failure still has precise provenance while a boundary is open. If
        // RocksDB can durably accept every dataset outcome, degrade those datasets independently
        // instead of falling back to the old unstructured global latch.
        if (current != null && failureListener != null) {
            try {
                for (Dataset dataset : enabled) {
                    if (!datasetErrors.containsKey(dataset)) {
                        failureListener.failed(dataset, current.newEpoch(), current, e);
                    }
                }
                for (Dataset dataset : enabled) {
                    datasetErrors.put(dataset, detail);
                    failedAtBlock.put(dataset, current.blockNumber());
                }
                try { discardBoundary(current); } catch (Exception ignored) { }
                return;
            } catch (Exception outcomeFailure) {
                outcomeFailure.addSuppressed(e);
                e = outcomeFailure;
                detail = failureDetail(e);
            }
        }

        // No exact boundary, or the durable outcome store itself failed. Retain the legacy
        // fail-closed marker because continuing with an unattributed gap is forbidden.
        error = detail;
        if (current != null) try { discardBoundary(current); } catch (Exception ignored) { }
        try {
            Files.createDirectories(root);
            Path temporary = Files.createTempFile(root, "epoch-source-failure-", ".tmp");
            try {
                Files.writeString(temporary, error, StandardCharsets.UTF_8);
                move(temporary, failureMarker());
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (Exception ignored) {
            // The in-memory error still keeps capture disabled for this run.
        }
    }

    private static String failureDetail(Exception e) {
        String value = e.getMessage() == null ? e.toString() : e.getMessage();
        value = value.replace('\n', ' ').replace('\r', ' ').trim();
        return value.length() <= 1_024 ? value : value.substring(0, 1_024);
    }

    private void durabilityProbe() {
        Path temporary = null;
        Path moved = root.resolve(".resume-probe");
        try {
            Files.createDirectories(root);
            temporary = Files.createTempFile(root, ".resume-probe-", ".tmp");
            try (var channel = java.nio.channels.FileChannel.open(temporary,
                    java.nio.file.StandardOpenOption.WRITE)) {
                channel.write(java.nio.ByteBuffer.wrap(new byte[]{1}));
                channel.force(true);
            }
            move(temporary, moved);
            Files.deleteIfExists(moved);
        } catch (Exception e) {
            throw new ArchiveStoreException("epoch staging durability probe failed", e);
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (Exception ignored) { }
            try { Files.deleteIfExists(moved); } catch (Exception ignored) { }
        }
    }
    private static <T> FactWriter<T> noop() { return new FactWriter<>() { public void append(T ignored) { } public void commit() { } }; }
    private static String safePart(String value) { if (value == null || !value.matches("[a-z0-9-]+")) throw new IllegalArgumentException("invalid epoch source part"); return value; }
    private static byte[] hex(String value) { return HexUtil.decodeHexString(Objects.requireNonNull(value)); }
    private static byte[] nullableHex(String value) { return value == null || value.isBlank() ? null : hex(value); }
    private static long exact(java.math.BigInteger value) { return Objects.requireNonNull(value).longValueExact(); }
    private static String credentialType(int type) { return type == 0 ? "key" : type == 1 ? "script" : "type_" + type; }
    private static String drepType(int type) { return switch (type) { case 0 -> "key"; case 1 -> "script"; case 2 -> "always_abstain"; case 3 -> "always_no_confidence"; default -> "type_" + type; }; }

    private void registerKnownSources() {
        if (enabled.contains(Dataset.EPOCH_STAKE)) register("EPOCH_STAKE/snapshot", ArchiveDatasetId.EPOCH_STAKE,
                StandardEpochFactCodecs.EPOCH_STAKE, StandardEpochDatasets.epochStake());
        if (enabled.contains(Dataset.DREP_DISTRIBUTION)) register("DREP_DISTRIBUTION/distribution",
                ArchiveDatasetId.DREP_DISTRIBUTION, StandardEpochFactCodecs.DREP,
                StandardEpochDatasets.drepDistribution());
        if (enabled.contains(Dataset.ADA_POT)) register("ADA_POT/final", ArchiveDatasetId.ADA_POT,
                StandardEpochFactCodecs.ADA_POT, StandardEpochDatasets.adaPot());
        if (enabled.contains(Dataset.GOVERNANCE_PROPOSAL_STATUS)) {
            register("GOVERNANCE_PROPOSAL_STATUS/lifecycle", ArchiveDatasetId.GOVERNANCE_PROPOSAL_STATUS,
                    StandardEpochFactCodecs.GOVERNANCE, StandardEpochDatasets.governanceProposalStatus());
            register("GOVERNANCE_PROPOSAL_STATUS/ratification", ArchiveDatasetId.GOVERNANCE_PROPOSAL_STATUS,
                    StandardEpochFactCodecs.GOVERNANCE, StandardEpochDatasets.governanceProposalStatus());
        }
        if (enabled.contains(Dataset.REWARD)) for (String part : List.of("rewards", "pool-reap", "governance", "mir")) {
            register("REWARD/" + part, ArchiveDatasetId.REWARD, StandardEpochFactCodecs.REWARD,
                    StandardEpochDatasets.rewards());
        }
    }

    private <T> void register(String key, ArchiveDatasetId dataset, EpochFactCodec<T> codec,
                              EpochArchiveDataset<T> projection) {
        sources.putIfAbsent(key, binding(key, dataset, codec, projection));
    }

    private <T> SourceBinding<T> binding(String key, ArchiveDatasetId dataset, EpochFactCodec<T> codec,
                                         EpochArchiveDataset<T> projection) {
        Path directory = root.resolve(key.toLowerCase(Locale.ROOT));
        return new SourceBinding<>(
                new DurableEpochFileSource<>(dataset, directory, codec),
                new DurableProvisionalEpochFileSource<>(dataset, directory.resolve(".provisional"), codec),
                projection);
    }

    private void publishCompleted(Boundary value) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("previousEpoch", Integer.toString(value.previousEpoch()));
        properties.setProperty("newEpoch", Integer.toString(value.newEpoch()));
        properties.setProperty("slot", Long.toString(value.slot()));
        properties.setProperty("block", Long.toString(value.blockNumber()));
        Path temporary = Files.createTempFile(completedDirectory(), Long.toString(value.blockNumber()), ".tmp");
        try {
            try (var out = Files.newOutputStream(temporary)) { properties.store(out, "completed epoch boundary"); }
            try { Files.move(temporary, completion(value.blockNumber()), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, completion(value.blockNumber()), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    private void discardBoundary(Boundary value) {
        for (SourceBinding<?> binding : sources()) {
            for (EpochArchiveJob job : binding.source().pending(Integer.MAX_VALUE)) {
                if (job.boundaryBlockNumber() == value.blockNumber()) binding.source().acknowledge(job);
            }
            for (ProvisionalEpochArchiveJob job : binding.provisional().pending()) {
                if (job.boundaryBlockNumber() == value.blockNumber()) binding.provisional().acknowledge(job);
            }
        }
        try { Files.deleteIfExists(completion(value.blockNumber())); }
        catch (Exception e) { throw new ArchiveStoreException("cannot discard epoch boundary", e); }
        refreshProvisionalBoundary(value.blockNumber());
    }

    private void discardDatasetBoundary(Dataset dataset, Boundary value) {
        ArchiveDatasetId archiveDataset = ArchiveDatasetId.valueOf(dataset.name());
        for (SourceBinding<?> binding : sources()) {
            if (binding.dataset() != archiveDataset) continue;
            for (EpochArchiveJob job : binding.source().pending(Integer.MAX_VALUE)) {
                if (job.boundaryBlockNumber() == value.blockNumber()) binding.source().acknowledge(job);
            }
            for (ProvisionalEpochArchiveJob job : binding.provisional().pending()) {
                if (job.boundaryBlockNumber() == value.blockNumber()) binding.provisional().acknowledge(job);
            }
        }
        refreshProvisionalBoundary(value.blockNumber());
    }

    private boolean isCompleted(EpochArchiveJob job) {
        Path marker = completion(job.boundaryBlockNumber());
        if (!Files.exists(marker)) return false;
        Properties values = readProperties(marker);
        return Long.parseLong(values.getProperty("block")) == job.boundaryBlockNumber()
                && Long.parseLong(values.getProperty("slot")) == job.boundarySlot();
    }

    private boolean isCompleted(ProvisionalEpochArchiveJob job) {
        Path marker = completion(job.boundaryBlockNumber());
        if (!Files.exists(marker)) return false;
        Properties values = readProperties(marker);
        return Long.parseLong(values.getProperty("block")) == job.boundaryBlockNumber()
                && Long.parseLong(values.getProperty("slot")) == job.boundarySlot();
    }

    private Properties readProperties(Path path) {
        try (var in = Files.newInputStream(path)) { Properties values = new Properties(); values.load(in); return values; }
        catch (Exception e) { throw new ArchiveStoreException("invalid epoch completion marker " + path, e); }
    }

    private Path completedDirectory() { return root.resolve("completed"); }
    private Path completion(long block) { return completedDirectory().resolve(block + ".properties"); }
    private Path failureMarker() { return root.resolve("FAILED"); }

    private static void move(Path source, Path target) throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static <T> BoundArtifact bind(SourceBinding<T> binding,
                                          ProvisionalEpochArchiveJob capture,
                                          EpochArchiveJob job) {
        return new BoundArtifact(job,
                binding.provisional().bind(capture, binding.source(), job));
    }

    private static <T> int discardProvisionalAfterEpoch(SourceBinding<T> binding, long epoch) {
        return binding.provisional().discardAfterEpoch(epoch, binding.source());
    }

    private static <T> int discardProvisionalAfterBlock(SourceBinding<T> binding, long block) {
        return binding.provisional().discardAfterBlock(block, binding.source());
    }

    private void refreshProvisionalBoundaries() {
        provisionalBoundaries.clear();
        for (SourceBinding<?> binding : sources()) {
            binding.provisional().pending().stream()
                    .map(ProvisionalEpochArchiveJob::boundaryBlockNumber)
                    .forEach(provisionalBoundaries::add);
        }
    }

    private void refreshProvisionalBoundary(long blockNumber) {
        boolean present = sources().stream().anyMatch(binding -> binding.provisional().pending().stream()
                .anyMatch(job -> job.boundaryBlockNumber() == blockNumber));
        if (present) provisionalBoundaries.add(blockNumber);
        else provisionalBoundaries.remove(blockNumber);
    }

    record SourceBinding<T>(DurableEpochFileSource<T> source,
                            DurableProvisionalEpochFileSource<T> provisional,
                            EpochArchiveDataset<T> projection) {
        ArchiveDatasetId dataset() {
            return projection.dataset();
        }
    }

    private record DatasetBoundary(Dataset dataset, long blockNumber) { }
}
