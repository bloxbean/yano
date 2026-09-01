package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yano.api.archive.ProjectionCfNames;
import com.bloxbean.cardano.yano.api.archive.ProjectionStagingWriter;
import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.projection.EpochArtifactIntervalRepair;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionBatch;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionCoordinate;
import com.bloxbean.cardano.yano.archive.api.projection.EpochArtifactGapInterval;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionEnvelope;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionEnvelopeHeader;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionIdentity;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSection;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSectionManifest;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSectionType;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.Slice;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Durable RocksDB outbox for canonical projection envelopes (ADR-039 §2, §8).
 *
 * <p>Writes are contributed into a caller-supplied {@link WriteBatch}. That is the
 * whole point: a contributor commits its section, its per-section manifest, and its
 * own cursor in the same batch as the state those facts were derived from, so a
 * section can never be durable without its state or vice versa. There is deliberately
 * no batch spanning chain, UTXO and ledger.
 *
 * <p>Reads are performed only after commit. Nothing here reads back a key the caller
 * has merely staged in a batch, because RocksDB point reads cannot observe an
 * uncommitted batch and a store that depended on that would silently lose same-block
 * writes.
 */
public final class ProjectionOutboxStore {

    private volatile RocksDB db;
    private volatile ColumnFamilyHandle headerCf;
    private volatile ColumnFamilyHandle sectionCf;
    private volatile ColumnFamilyHandle metaCf;
    private volatile ColumnFamilyHandle artifactCf;
    private final Supplier<RocksDB> dbSupplier;
    private final Function<String, ColumnFamilyHandle> handleSupplier;
    private final Set<Long> pendingEpochArtifactCarriers =
            ConcurrentHashMap.newKeySet();

    public ProjectionOutboxStore(RocksDB db, ColumnFamilyHandle headerCf, ColumnFamilyHandle sectionCf,
                                 ColumnFamilyHandle metaCf, ColumnFamilyHandle artifactCf) {
        this(() -> db, name -> switch (name) {
            case ProjectionCfNames.PROJ_HEADER -> headerCf;
            case ProjectionCfNames.PROJ_SECTION -> sectionCf;
            case ProjectionCfNames.PROJ_META -> metaCf;
            case ProjectionCfNames.PROJ_ARTIFACT -> artifactCf;
            default -> null;
        });
    }

    /**
     * Construct an outbox whose native handles can be refreshed after snapshot restore.
     */
    public ProjectionOutboxStore(Supplier<RocksDB> dbSupplier,
                                 Function<String, ColumnFamilyHandle> handleSupplier) {
        this.dbSupplier = Objects.requireNonNull(dbSupplier, "dbSupplier");
        this.handleSupplier = Objects.requireNonNull(handleSupplier, "handleSupplier");
        reinitializeAfterSnapshotRestore();
    }

    /** Refresh native handles after the chain-state database has been replaced. */
    public synchronized void reinitializeAfterSnapshotRestore() {
        this.db = Objects.requireNonNull(dbSupplier.get(), "db");
        this.headerCf = requiredHandle(ProjectionCfNames.PROJ_HEADER);
        this.sectionCf = requiredHandle(ProjectionCfNames.PROJ_SECTION);
        this.metaCf = requiredHandle(ProjectionCfNames.PROJ_META);
        this.artifactCf = requiredHandle(ProjectionCfNames.PROJ_ARTIFACT);
        refreshPendingEpochArtifactCarriers();
    }

    private ColumnFamilyHandle requiredHandle(String name) {
        return Objects.requireNonNull(handleSupplier.apply(name), "column family " + name);
    }

    // ------------------------------------------------------------------ identity

    public void putIdentity(ProjectionIdentity identity) {
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions().setSync(true)) {
            batch.put(metaCf, ProjectionOutboxKeys.META_IDENTITY,
                    identity.fingerprint().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            db.write(options, batch);
        } catch (RocksDBException e) {
            throw new ProjectionOutboxException("failed to persist projection identity", e);
        }
    }

    public Optional<String> identityFingerprint() {
        return get(metaCf, ProjectionOutboxKeys.META_IDENTITY)
                .map(value -> new String(value, java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Persist the artifact contracts this archive is maintained under.
     *
     * <p>Kept separate from the section fingerprint because artifacts are referenced from
     * envelopes rather than named in the identity string: without this, a node capturing epoch
     * stake and a node capturing nothing would present the same fingerprint, and the second would
     * report itself complete for artifacts it never captured.
     */
    public void putArtifactIdentity(String wireForm) {
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions().setSync(true)) {
            batch.put(metaCf, ProjectionOutboxKeys.META_ARTIFACTS,
                    wireForm.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            db.write(options, batch);
        } catch (RocksDBException e) {
            throw new ProjectionOutboxException("failed to persist artifact contracts", e);
        }
    }

    public Optional<String> artifactIdentityWire() {
        return get(metaCf, ProjectionOutboxKeys.META_ARTIFACTS)
                .map(value -> new String(value, java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Persist the selected contracts and their capture lifetime in one synced RocksDB batch.
     * A crash must never leave an artifact selected without an honest projected-from epoch.
     */
    public void putArtifactSelection(
            com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactIdentity identity,
            com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactEnrollments enrollments) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(enrollments, "enrollments");
        enrollments.requireMatches(identity);
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions().setSync(true)) {
            batch.put(metaCf, ProjectionOutboxKeys.META_ARTIFACTS,
                    identity.wireForm().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            batch.put(metaCf, ProjectionOutboxKeys.META_ARTIFACT_ENROLLMENTS,
                    enrollments.wireForm().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            db.write(options, batch);
        } catch (RocksDBException e) {
            throw new ProjectionOutboxException("failed to persist artifact selection", e);
        }
    }

    /** Persist block identity plus artifact selection as one startup decision. */
    public void putProjectionSelection(
            ProjectionIdentity projectionIdentity,
            com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactIdentity artifactIdentity,
            com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactEnrollments enrollments) {
        Objects.requireNonNull(projectionIdentity, "projectionIdentity");
        Objects.requireNonNull(artifactIdentity, "artifactIdentity");
        Objects.requireNonNull(enrollments, "enrollments");
        enrollments.requireMatches(artifactIdentity);
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions().setSync(true)) {
            batch.put(metaCf, ProjectionOutboxKeys.META_IDENTITY,
                    projectionIdentity.fingerprint().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            batch.put(metaCf, ProjectionOutboxKeys.META_ARTIFACTS,
                    artifactIdentity.wireForm().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            batch.put(metaCf, ProjectionOutboxKeys.META_ARTIFACT_ENROLLMENTS,
                    enrollments.wireForm().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            db.write(options, batch);
        } catch (RocksDBException e) {
            throw new ProjectionOutboxException("failed to persist projection selection", e);
        }
    }

    public Optional<String> artifactEnrollmentsWire() {
        return get(metaCf, ProjectionOutboxKeys.META_ARTIFACT_ENROLLMENTS)
                .map(value -> new String(value, java.nio.charset.StandardCharsets.UTF_8));
    }

    // --------------------------------------------------------- epoch coverage outcomes

    /** Atomically record a canonical point gap and pause future capture for its dataset. */
    public synchronized void recordEpochArtifactGap(
            com.bloxbean.cardano.yano.archive.api.projection.EpochArtifactGap gap) {
        Objects.requireNonNull(gap, "gap");
        if (gap.carrierBlockNumber() <= artifactsSealedThrough()
                || gap.carrierBlockNumber() <= acknowledgedThrough()) {
            throw new ProjectionOutboxException("cannot record epoch-artifact gap for sealed block "
                    + gap.carrierBlockNumber());
        }
        byte[] key = ProjectionOutboxKeys.epochGapKey(gap.dataset().name(), gap.semanticEpoch());
        get(metaCf, key).ifPresent(existing -> {
            var decoded = EpochArtifactGapCodec.decode(existing);
            if (!decoded.sameOutcome(gap)) {
                throw new ProjectionOutboxException("conflicting epoch-artifact gap for "
                        + gap.dataset() + " epoch " + gap.semanticEpoch());
            }
        });
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions().setSync(true)) {
            // A dataset may have published one part before a later part failed. The point GAP
            // replaces the whole dataset outcome for this boundary, so remove every earlier
            // reference in the same durable batch before staging is allowed to delete evidence.
            byte[] lower = ProjectionOutboxKeys.blockKey(gap.carrierBlockNumber());
            byte[] upper = ProjectionOutboxKeys.blockKey(gap.carrierBlockNumber() + 1);
            try (Slice upperSlice = new Slice(upper);
                 ReadOptions read = new ReadOptions().setIterateUpperBound(upperSlice);
                 RocksIterator iterator = db.newIterator(artifactCf, read)) {
                for (iterator.seek(lower); iterator.isValid(); iterator.next()) {
                    var ref = ProjectionSectionCodec.decodeArtifact(iterator.value());
                    if (ref.dataset() == gap.dataset()) batch.delete(artifactCf, iterator.key());
                }
                iterator.status();
            }
            batch.put(metaCf, key, EpochArtifactGapCodec.encode(gap));
            batch.put(metaCf, ProjectionOutboxKeys.epochStateKey(gap.dataset().name()),
                    com.bloxbean.cardano.yano.archive.api.projection.EpochArtifactCaptureState.PAUSED
                            .name().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            batch.put(metaCf, ProjectionOutboxKeys.epochPauseCauseKey(gap.dataset().name()),
                    EpochArtifactGapCodec.encode(gap));
            db.write(options, batch);
        } catch (RocksDBException e) {
            throw new ProjectionOutboxException("failed to persist epoch-artifact gap", e);
        }
    }

    public List<com.bloxbean.cardano.yano.archive.api.projection.EpochArtifactGap> epochArtifactGaps() {
        byte[] prefix = ProjectionOutboxKeys.epochGapPrefix();
        List<com.bloxbean.cardano.yano.archive.api.projection.EpochArtifactGap> gaps = new ArrayList<>();
        try (RocksIterator iterator = db.newIterator(metaCf)) {
            iterator.seek(prefix);
            while (iterator.isValid() && startsWith(iterator.key(), prefix)) {
                gaps.add(EpochArtifactGapCodec.decode(iterator.value()));
                iterator.next();
            }
            iterator.status();
        } catch (RocksDBException e) {
            throw new ProjectionOutboxException("failed to scan epoch-artifact gaps", e);
        }
        gaps.sort(java.util.Comparator
                .comparing((com.bloxbean.cardano.yano.archive.api.projection.EpochArtifactGap gap) ->
                        gap.dataset().name())
                .thenComparingInt(com.bloxbean.cardano.yano.archive.api.projection
                        .EpochArtifactGap::semanticEpoch));
        return List.copyOf(gaps);
    }

    public com.bloxbean.cardano.yano.archive.api.projection.EpochArtifactCaptureState
            epochArtifactCaptureState(com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId dataset) {
        return get(metaCf, ProjectionOutboxKeys.epochStateKey(dataset.name()))
                .map(value -> com.bloxbean.cardano.yano.archive.api.projection.EpochArtifactCaptureState
                        .valueOf(new String(value, java.nio.charset.StandardCharsets.UTF_8)))
                .orElse(com.bloxbean.cardano.yano.archive.api.projection.EpochArtifactCaptureState.ACTIVE);
    }

    public synchronized void resumeEpochArtifact(
            com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId dataset) {
        resumeEpochArtifact(dataset, -1, null);
    }

    public synchronized void resumeEpochArtifact(
            com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId dataset,
            long slot, byte[] hash) {
        if (slot >= 0 && (hash == null || hash.length == 0)) {
            throw new IllegalArgumentException("resume point hash is required");
        }
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions().setSync(true)) {
            batch.put(metaCf, ProjectionOutboxKeys.epochStateKey(dataset.name()),
                    com.bloxbean.cardano.yano.archive.api.projection.EpochArtifactCaptureState.ACTIVE
                            .name().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] prefix = ProjectionOutboxKeys.epochIntervalPrefix();
            try (RocksIterator iterator = db.newIterator(metaCf)) {
                iterator.seek(prefix);
                while (iterator.isValid() && startsWith(iterator.key(), prefix)) {
                    var state = EpochGapIntervalCodec.decode(iterator.value());
                    if (state.dataset() == dataset && state.open()) {
                        batch.put(metaCf, iterator.key(), EpochGapIntervalCodec.encode(
                                new EpochGapIntervalCodec.State(state.dataset(), state.causedByEpoch(),
                                        state.failureClass(), false, state.checkpoints())));
                    }
                    iterator.next();
                }
                iterator.status();
            }
            if (slot >= 0) {
                byte[] resume = java.nio.ByteBuffer.allocate(12 + hash.length)
                        .order(java.nio.ByteOrder.BIG_ENDIAN).putLong(slot).putInt(hash.length).put(hash).array();
                batch.put(metaCf, ProjectionOutboxKeys.epochResumeKey(dataset.name()), resume);
            }
            db.write(options, batch);
        } catch (RocksDBException e) {
            throw new ProjectionOutboxException("failed to resume epoch artifact " + dataset, e);
        }
    }

    /** Remove point-gap intent after the sink atomically repaired it to COMPLETE. */
    public synchronized void acknowledgeEpochArtifactRepair(
            com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId dataset, int epoch) {
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions().setSync(true)) {
            batch.delete(metaCf, ProjectionOutboxKeys.epochGapKey(dataset.name(), epoch));
            if (epochArtifactCaptureState(dataset)
                    == com.bloxbean.cardano.yano.archive.api.projection
                            .EpochArtifactCaptureState.ACTIVE) {
                batch.delete(metaCf, ProjectionOutboxKeys.epochPauseCauseKey(dataset.name()));
            }
            byte[] prefix = ProjectionOutboxKeys.epochIntervalPrefix();
            try (RocksIterator iterator = db.newIterator(metaCf)) {
                iterator.seek(prefix);
                while (iterator.isValid() && startsWith(iterator.key(), prefix)) {
                    var state = EpochGapIntervalCodec.decode(iterator.value());
                    if (state.dataset() == dataset
                            && state.checkpoints().stream().anyMatch(point -> point.epoch() == epoch)) {
                        var retained = state.checkpoints().stream()
                                .filter(point -> point.epoch() != epoch).toList();
                        if (retained.isEmpty()) batch.delete(metaCf, iterator.key());
                        else batch.put(metaCf, iterator.key(), EpochGapIntervalCodec.encode(
                                new EpochGapIntervalCodec.State(state.dataset(), state.causedByEpoch(),
                                        state.failureClass(), state.open(), retained)));
                    }
                    iterator.next();
                }
                iterator.status();
            }
            db.write(options, batch);
        } catch (RocksDBException e) {
            throw new ProjectionOutboxException("failed to acknowledge epoch-artifact repair", e);
        }
    }

    /** Preview interval splits that the sink must commit atomically with repaired rows. */
    public List<com.bloxbean.cardano.yano.archive.api.projection.EpochArtifactIntervalRepair>
            previewEpochArtifactIntervalRepairs(List<ProjectionArtifactRef> artifacts) {
        var repaired = artifacts.stream().collect(java.util.stream.Collectors.groupingBy(
                ProjectionArtifactRef::dataset,
                java.util.stream.Collectors.mapping(ProjectionArtifactRef::semanticEpoch,
                        java.util.stream.Collectors.toSet())));
        if (repaired.isEmpty()) return List.of();
        var result = new ArrayList<EpochArtifactIntervalRepair>();
        byte[] prefix = ProjectionOutboxKeys.epochIntervalPrefix();
        try (RocksIterator iterator = db.newIterator(metaCf)) {
            iterator.seek(prefix);
            while (iterator.isValid() && startsWith(iterator.key(), prefix)) {
                var state = EpochGapIntervalCodec.decode(iterator.value());
                var epochs = repaired.get(state.dataset());
                if (epochs != null && state.checkpoints().stream()
                        .anyMatch(point -> epochs.contains(point.epoch()))) {
                    var retained = state.checkpoints().stream()
                            .filter(point -> !epochs.contains(point.epoch())).toList();
                    var segments = retained.isEmpty() ? List.<EpochArtifactGapInterval>of()
                            : new EpochGapIntervalCodec.State(state.dataset(), state.causedByEpoch(),
                                    state.failureClass(), state.open(), retained).intervals();
                    result.add(new EpochArtifactIntervalRepair(
                            state.dataset(), state.causedByEpoch(), segments));
                }
                iterator.next();
            }
            iterator.status();
        } catch (RocksDBException e) {
            throw new ProjectionOutboxException("failed to preview epoch interval repair", e);
        }
        return List.copyOf(result);
    }

    public void acknowledgeEpochArtifactRepairs(List<ProjectionArtifactRef> artifacts) {
        artifacts.stream().map(ref -> java.util.Map.entry(ref.dataset(), ref.semanticEpoch()))
                .distinct().forEach(entry -> acknowledgeEpochArtifactRepair(
                        entry.getKey(), entry.getValue()));
    }

    @FunctionalInterface
    public interface CompleteEpochArtifactProbe {
        boolean isComplete(ArchiveDatasetId dataset,
                           int epoch, long slot, byte[] hash);
    }

    /**
     * Reconcile a crash after sink COMPLETE but before outbox acknowledgement using exact points.
     */
    public int acknowledgeRepairsAlreadyComplete(CompleteEpochArtifactProbe probe) {
        Objects.requireNonNull(probe, "probe");
        record Candidate(ArchiveDatasetId dataset,
                         int epoch, long slot, byte[] hash) {
            Candidate { hash = hash.clone(); }
            @Override public byte[] hash() { return hash.clone(); }
        }
        var candidates = new java.util.LinkedHashMap<String, Candidate>();
        for (var gap : epochArtifactGaps()) {
            var candidate = new Candidate(gap.dataset(), gap.semanticEpoch(), gap.boundarySlot(),
                    gap.boundaryBlockHash());
            candidates.put(gap.dataset().name() + '/' + gap.semanticEpoch(), candidate);
        }
        byte[] prefix = ProjectionOutboxKeys.epochIntervalPrefix();
        try (RocksIterator iterator = db.newIterator(metaCf)) {
            iterator.seek(prefix);
            while (iterator.isValid() && startsWith(iterator.key(), prefix)) {
                var state = EpochGapIntervalCodec.decode(iterator.value());
                for (var point : state.checkpoints()) {
                    var candidate = new Candidate(state.dataset(), point.epoch(), point.slot(), point.hash());
                    candidates.putIfAbsent(state.dataset().name() + '/' + point.epoch(), candidate);
                }
                iterator.next();
            }
            iterator.status();
        } catch (RocksDBException e) {
            throw new ProjectionOutboxException("failed to scan repair candidates", e);
        }
        int acknowledged = 0;
        for (var candidate : candidates.values()) {
            if (probe.isComplete(candidate.dataset(), candidate.epoch(), candidate.slot(), candidate.hash())) {
                acknowledgeEpochArtifactRepair(candidate.dataset(), candidate.epoch());
                acknowledged++;
            }
        }
        return acknowledged;
    }

    /** Extend the one compact interval representing boundaries missed while paused. */
    public synchronized void recordPausedEpoch(
            ArchiveDatasetId dataset,
            int epoch, long carrierBlockNumber, long slot, byte[] hash) {
        if (carrierBlockNumber < 0) throw new IllegalArgumentException("carrier block is required");
        if (hash == null || hash.length == 0) throw new IllegalArgumentException("boundary hash is required");
        var cause = get(metaCf, ProjectionOutboxKeys.epochPauseCauseKey(dataset.name()))
                .map(EpochArtifactGapCodec::decode)
                .orElseThrow(() -> new ProjectionOutboxException(
                        "cannot open a paused interval without a durable pause cause"));
        byte[] key = ProjectionOutboxKeys.epochIntervalKey(dataset.name(), cause.semanticEpoch());
        var existing = get(metaCf, key).map(EpochGapIntervalCodec::decode);
        EpochGapIntervalCodec.State next;
        if (existing.isPresent() && existing.orElseThrow().open()) {
            var state = existing.orElseThrow();
            var checkpoints = new ArrayList<>(state.checkpoints());
            var last = checkpoints.getLast();
            if (epoch < last.epoch()) throw new ProjectionOutboxException("paused epoch moved backward");
            if (epoch == last.epoch()) {
                if (last.carrierBlockNumber() != carrierBlockNumber || last.slot() != slot
                        || !Arrays.equals(last.hash(), hash)) {
                    throw new ProjectionOutboxException("conflicting paused boundary for " + dataset
                            + " epoch " + epoch);
                }
                return;
            }
            checkpoints.add(new EpochGapIntervalCodec.Checkpoint(epoch, carrierBlockNumber, slot, hash));
            next = new EpochGapIntervalCodec.State(dataset, state.causedByEpoch(),
                    state.failureClass(), true, checkpoints);
        } else {
            next = new EpochGapIntervalCodec.State(dataset, cause.semanticEpoch(), cause.failureClass(),
                    true, List.of(new EpochGapIntervalCodec.Checkpoint(
                            epoch, carrierBlockNumber, slot, hash)));
        }
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions().setSync(true)) {
            batch.put(metaCf, key, EpochGapIntervalCodec.encode(next));
            db.write(options, batch);
        } catch (RocksDBException e) {
            throw new ProjectionOutboxException("failed to persist paused epoch interval", e);
        }
    }

    public List<EpochArtifactGapInterval>
            epochArtifactGapIntervals() {
        return epochArtifactGapIntervals(Long.MAX_VALUE);
    }

    /** Gap intervals visible through an acknowledged carrier block. */
    public List<EpochArtifactGapInterval>
            epochArtifactGapIntervals(long acknowledgedThrough) {
        byte[] prefix = ProjectionOutboxKeys.epochIntervalPrefix();
        var values = new ArrayList<EpochArtifactGapInterval>();
        try (RocksIterator iterator = db.newIterator(metaCf)) {
            iterator.seek(prefix);
            while (iterator.isValid() && startsWith(iterator.key(), prefix)) {
                values.addAll(EpochGapIntervalCodec.decode(iterator.value()).intervals(acknowledgedThrough));
                iterator.next();
            }
            iterator.status();
        } catch (RocksDBException e) {
            throw new ProjectionOutboxException("failed to scan paused epoch intervals", e);
        }
        values.sort(Comparator
                .comparing((EpochArtifactGapInterval value)
                        -> value.dataset().name())
                .thenComparingInt(EpochArtifactGapInterval::causedByEpoch)
                .thenComparingInt(EpochArtifactGapInterval::fromEpoch));
        return List.copyOf(values);
    }

    /** Drop point gaps above an exact canonical rollback target and re-derive paused states. */
    public synchronized int rollbackEpochArtifactGaps(long slot, byte[] hash, boolean origin) {
        if (!origin && (hash == null || hash.length == 0)) {
            throw new IllegalArgumentException("non-origin rollback requires a hash");
        }
        List<com.bloxbean.cardano.yano.archive.api.projection.EpochArtifactGap> all = epochArtifactGaps();
        List<com.bloxbean.cardano.yano.archive.api.projection.EpochArtifactGap> remove = all.stream()
                .filter(gap -> origin || gap.boundarySlot() > slot
                        || (gap.boundarySlot() == slot
                        && !java.util.Arrays.equals(gap.boundaryBlockHash(), hash)))
                .toList();
        var intervalUpdates = new java.util.LinkedHashMap<byte[], byte[]>();
        var intervalDeletes = new java.util.ArrayList<byte[]>();
        var revertedResumes = new java.util.HashSet<com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId>();
        var survivingPauseCauses = new java.util.HashSet<
                com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId>();
        var removedPauseCauses = new java.util.HashSet<
                com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId>();
        for (var dataset : com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.values()) {
            get(metaCf, ProjectionOutboxKeys.epochPauseCauseKey(dataset.name())).ifPresent(encoded -> {
                var cause = EpochArtifactGapCodec.decode(encoded);
                boolean retained = !origin && (cause.boundarySlot() < slot
                        || cause.boundarySlot() == slot
                        && java.util.Arrays.equals(cause.boundaryBlockHash(), hash));
                if (retained) survivingPauseCauses.add(dataset);
                else removedPauseCauses.add(dataset);
            });
            get(metaCf, ProjectionOutboxKeys.epochResumeKey(dataset.name())).ifPresent(encoded -> {
                var buffer = java.nio.ByteBuffer.wrap(encoded).order(java.nio.ByteOrder.BIG_ENDIAN);
                long resumeSlot = buffer.getLong(); int length = buffer.getInt();
                if (length <= 0 || length != buffer.remaining()) {
                    throw new ProjectionOutboxException("invalid resume point for " + dataset);
                }
                byte[] resumeHash = new byte[length]; buffer.get(resumeHash);
                if (origin || resumeSlot > slot || (resumeSlot == slot
                        && !java.util.Arrays.equals(resumeHash, hash))) revertedResumes.add(dataset);
            });
        }
        byte[] intervalPrefix = ProjectionOutboxKeys.epochIntervalPrefix();
        try (RocksIterator iterator = db.newIterator(metaCf)) {
            iterator.seek(intervalPrefix);
            while (iterator.isValid() && startsWith(iterator.key(), intervalPrefix)) {
                var state = EpochGapIntervalCodec.decode(iterator.value());
                var retained = state.checkpoints().stream().filter(point -> !origin
                        && (point.slot() < slot || (point.slot() == slot
                        && java.util.Arrays.equals(point.hash(), hash)))).toList();
                byte[] key = iterator.key().clone();
                if (retained.isEmpty()) intervalDeletes.add(key);
                else {
                    int latestCause = all.stream().filter(gap -> gap.dataset() == state.dataset()
                                    && !remove.contains(gap))
                            .mapToInt(com.bloxbean.cardano.yano.archive.api.projection
                                    .EpochArtifactGap::semanticEpoch).max().orElse(-1);
                    boolean reopen = revertedResumes.contains(state.dataset())
                            && state.causedByEpoch() == latestCause;
                    if (retained.size() != state.checkpoints().size() || (reopen && !state.open())) {
                    intervalUpdates.put(key, EpochGapIntervalCodec.encode(new EpochGapIntervalCodec.State(
                            state.dataset(), state.causedByEpoch(), state.failureClass(),
                            state.open() || reopen, retained)));
                    }
                }
                iterator.next();
            }
            iterator.status();
        } catch (RocksDBException e) {
            throw new ProjectionOutboxException("failed to scan paused intervals for rollback", e);
        }
        if (remove.isEmpty() && intervalUpdates.isEmpty() && intervalDeletes.isEmpty()
                && revertedResumes.isEmpty() && removedPauseCauses.isEmpty()) return 0;
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions().setSync(true)) {
            for (var gap : remove) {
                batch.delete(metaCf, ProjectionOutboxKeys.epochGapKey(
                        gap.dataset().name(), gap.semanticEpoch()));
            }
            for (byte[] key : intervalDeletes) batch.delete(metaCf, key);
            for (var update : intervalUpdates.entrySet()) {
                batch.put(metaCf, update.getKey(), update.getValue());
            }
            for (var dataset : com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId.values()) {
                boolean surviving = survivingPauseCauses.contains(dataset);
                if (!surviving) batch.delete(metaCf, ProjectionOutboxKeys.epochStateKey(dataset.name()));
                else if (revertedResumes.contains(dataset)) batch.put(metaCf,
                        ProjectionOutboxKeys.epochStateKey(dataset.name()),
                        com.bloxbean.cardano.yano.archive.api.projection.EpochArtifactCaptureState.PAUSED
                                .name().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (revertedResumes.contains(dataset)) {
                    batch.delete(metaCf, ProjectionOutboxKeys.epochResumeKey(dataset.name()));
                }
                if (removedPauseCauses.contains(dataset)) {
                    batch.delete(metaCf, ProjectionOutboxKeys.epochPauseCauseKey(dataset.name()));
                }
            }
            db.write(options, batch);
            return remove.size();
        } catch (RocksDBException e) {
            throw new ProjectionOutboxException("failed to roll back epoch-artifact gaps", e);
        }
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (value[i] != prefix[i]) return false;
        return true;
    }

    // ------------------------------------------------------------------- writing

    /**
     * Record canonical block identity for {@code header}. Sections and artifacts are
     * written by their own contributors; the manifest a sink later verifies is derived
     * from what is actually stored rather than from a promise made here.
     */
    public void putBlockIdentity(ProjectionStagingWriter writer, ProjectionEnvelopeHeader header) {
        ProjectionEnvelopeHeader identityOnly = new ProjectionEnvelopeHeader(header.networkIdentity(),
                header.blockKind(), header.blockNumber(), header.blockHash(), header.parentHash(),
                header.slot(), header.epoch(), header.blockTime(), header.canonicalProjectionVersion(),
                List.of(), List.of());
        writer.put(ProjectionCfNames.PROJ_HEADER, ProjectionOutboxKeys.blockKey(header.blockNumber()),
                ProjectionEnvelopeCodec.encodeHeader(identityOnly));
        stageCursorMax(writer, ProjectionOutboxKeys.META_CURSOR_IDENTITY, header.blockNumber());
    }

    /** Stage one contributor's section plus its cursor. Both land in the caller's batch. */
    public void putSection(ProjectionStagingWriter writer, long blockNumber, ProjectionSection section) {
        ProjectionSectionManifest manifest = section.manifest();
        writer.put(ProjectionCfNames.PROJ_SECTION,
                ProjectionOutboxKeys.sectionManifestKey(blockNumber, section.type()),
                ProjectionSectionCodec.encodeManifest(manifest));
        List<byte[]> chunks = section.chunks();
        for (int i = 0; i < chunks.size(); i++) {
            writer.put(ProjectionCfNames.PROJ_SECTION,
                    ProjectionOutboxKeys.sectionChunkKey(blockNumber, section.type(), i), chunks.get(i));
        }
        stageCursorMax(writer, ProjectionOutboxKeys.cursorKey(section.type()), blockNumber);
    }

    /**
     * Advance a contributor's cursor without writing a section. A block that legitimately
     * produces no rows for a dataset must still advance, or the envelope would wait
     * forever on a contributor that has nothing to say.
     */
    public void advanceContributor(ProjectionStagingWriter writer, ProjectionSectionType type, long blockNumber) {
        stageCursorMax(writer, ProjectionOutboxKeys.cursorKey(type), blockNumber);
    }

    private void stageCursorMax(ProjectionStagingWriter writer, byte[] key, long candidate) {
        long current = get(metaCf, key).map(ProjectionOutboxKeys::decodeLong).orElse(-1L);
        if (candidate > current) {
            writer.put(ProjectionCfNames.PROJ_META, key, ProjectionOutboxKeys.encodeLong(candidate));
        }
    }

    /** Stage an epoch-artifact reference alongside the block that carries it. */
    public void putArtifact(ProjectionStagingWriter writer, long blockNumber,
                            com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef ref) {
        writer.put(ProjectionCfNames.PROJ_ARTIFACT,
                ProjectionOutboxKeys.artifactKey(blockNumber, ref.dataset().name(), ref.semanticEpoch(),
                        ref.sourceGeneration()),
                ProjectionSectionCodec.encodeArtifact(ref));
    }

    /**
     * Persist transition evidence as an intent, without putting a reference under a future block.
     * The intent is written atomically with its source state and attached later by
     * {@link #bindPendingEpochArtifacts} from the carrier block's own batch.
     */
    public void putPendingEpochArtifact(ProjectionStagingWriter writer, long carrierBlockNumber,
                                        ProjectionArtifactRef ref) {
        if (carrierBlockNumber <= ref.producingBlockNumber()) {
            throw new ProjectionOutboxException("epoch artifact carrier must follow its anchor");
        }
        if (carrierBlockNumber <= artifactsSealedThrough()
                || carrierBlockNumber <= acknowledgedThrough()) {
            throw new ProjectionOutboxException("cannot stage epoch artifact intent for sealed carrier block "
                    + carrierBlockNumber);
        }
        writer.put(ProjectionCfNames.PROJ_META,
                ProjectionOutboxKeys.pendingEpochArtifactKey(carrierBlockNumber,
                        ref.dataset().name(), ref.semanticEpoch(), ref.sourceGeneration()),
                ProjectionSectionCodec.encodeArtifact(ref));
        // The caller commits immediately after contribution. A failed caller batch can leave a
        // harmless false positive in this cache; bindPendingEpochArtifacts removes it on lookup.
        pendingEpochArtifactCarriers.add(carrierBlockNumber);
    }

    /** Copy every durable transition intent into the currently applying carrier block's batch. */
    public int bindPendingEpochArtifacts(ProjectionStagingWriter writer, long carrierBlockNumber) {
        if (!pendingEpochArtifactCarriers.contains(carrierBlockNumber)) return 0;
        byte[] prefix = ProjectionOutboxKeys.pendingEpochArtifactPrefix(carrierBlockNumber);
        int bound = 0;
        try (RocksIterator iterator = db.newIterator(metaCf)) {
            for (iterator.seek(prefix); iterator.isValid() && startsWith(iterator.key(), prefix); iterator.next()) {
                ProjectionArtifactRef ref = ProjectionSectionCodec.decodeArtifact(iterator.value());
                putArtifact(writer, carrierBlockNumber, ref);
                bound++;
            }
            iterator.status();
        } catch (RocksDBException e) {
            throw new ProjectionOutboxException("failed to bind pending epoch artifacts for carrier block "
                    + carrierBlockNumber, e);
        }
        if (bound == 0) pendingEpochArtifactCarriers.remove(carrierBlockNumber);
        return bound;
    }

    /**
     * Adapter binding a RocksDB {@link WriteBatch} to the opaque staging contract, so the
     * same store code serves the runtime hook and direct callers.
     */
    public static ProjectionStagingWriter batchWriter(WriteBatch batch,
                                                      java.util.function.Function<String, ColumnFamilyHandle> handles) {
        return (columnFamily, key, value) -> {
            try {
                batch.put(handles.apply(columnFamily), key, value);
            } catch (RocksDBException e) {
                throw new ProjectionOutboxException("failed to stage projection record", e);
            }
        };
    }

    /**
     * Run {@code work} in the store's own atomic batch.
     *
     * <p>Used by contributors that have no other subsystem batch to join — notably Byron
     * epoch-boundary blocks, which have no UTXO transition. Main blocks use the UTXO batch.
     */
    public void commit(java.util.function.Consumer<ProjectionStagingWriter> work) {
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions().setSync(true)) {
            work.accept(batchWriter(batch, handles()));
            db.write(options, batch);
        } catch (RocksDBException e) {
            throw new ProjectionOutboxException("failed to commit projection batch", e);
        }
    }

    /** Column-family handle lookup for {@link #batchWriter}. */
    public java.util.function.Function<String, ColumnFamilyHandle> handles() {
        return name -> switch (name) {
            case ProjectionCfNames.PROJ_HEADER -> headerCf;
            case ProjectionCfNames.PROJ_SECTION -> sectionCf;
            case ProjectionCfNames.PROJ_META -> metaCf;
            case ProjectionCfNames.PROJ_ARTIFACT -> artifactCf;
            default -> throw new ProjectionOutboxException("unknown projection column family: " + name);
        };
    }

    // ------------------------------------------------------------------- cursors

    public long contributorCursor(ProjectionSectionType type) {
        return get(metaCf, ProjectionOutboxKeys.cursorKey(type))
                .map(ProjectionOutboxKeys::decodeLong).orElse(-1L);
    }

    public long identityCursor() {
        return get(metaCf, ProjectionOutboxKeys.META_CURSOR_IDENTITY)
                .map(ProjectionOutboxKeys::decodeLong).orElse(-1L);
    }

    public long acknowledgedThrough() {
        return get(metaCf, ProjectionOutboxKeys.META_ACK)
                .map(ProjectionOutboxKeys::decodeLong).orElse(-1L);
    }

    /** Greatest block whose artifact set has been durably frozen for a sink commit. */
    public long artifactsSealedThrough() {
        return get(metaCf, ProjectionOutboxKeys.META_ARTIFACT_SEALED)
                .map(ProjectionOutboxKeys::decodeLong).orElse(-1L);
    }

    /**
     * Greatest block for which every required contributor is durably done.
     *
     * <p>This is the completion rule of ADR-039 §8: canonical progress may lead a
     * contributor, and the envelope is eligible only when the slowest required
     * contributor has reached it.
     */
    public long completeThrough(Set<ProjectionSectionType> requiredSections) {
        long complete = identityCursor();
        for (ProjectionSectionType type : requiredSections) {
            complete = Math.min(complete, contributorCursor(type));
        }
        return complete;
    }

    // ------------------------------------------------------------------- reading

    /**
     * Whether a canonical block already claimed this coordinate.
     *
     * <p>Exists for the Byron epoch boundary. An EBB's block number is its chain difficulty, and
     * an EBB does not advance difficulty — so it reports the number of the main block before it,
     * which owns that coordinate and its sections. The chain state refuses EBBs a number mapping
     * for the same reason.
     */
    public boolean hasBlockIdentity(long blockNumber) {
        return get(headerCf, ProjectionOutboxKeys.blockKey(blockNumber)).isPresent();
    }

    /** Canonical identity already retained at a projection coordinate. */
    public Optional<ProjectionEnvelopeHeader> blockIdentity(long blockNumber) {
        return get(headerCf, ProjectionOutboxKeys.blockKey(blockNumber))
                .map(ProjectionEnvelopeCodec::decodeHeader);
    }

    /** Required sections that are absent or incomplete at an existing coordinate. */
    public Set<ProjectionSectionType> missingSections(long blockNumber,
                                                      Set<ProjectionSectionType> requiredSections) {
        Map<ProjectionSectionType, ProjectionSectionManifest> manifests =
                new EnumMap<>(ProjectionSectionType.class);
        Map<ProjectionSectionType, List<byte[]>> chunks = new EnumMap<>(ProjectionSectionType.class);
        scanSections(blockNumber, manifests, chunks);
        Set<ProjectionSectionType> missing = java.util.EnumSet.noneOf(ProjectionSectionType.class);
        for (ProjectionSectionType required : requiredSections) {
            ProjectionSectionManifest manifest = manifests.get(required);
            if (manifest == null
                    || chunks.getOrDefault(required, List.of()).size() != manifest.chunkCount()) {
                missing.add(required);
            }
        }
        return Set.copyOf(missing);
    }

    public Optional<ProjectionEnvelope> readEnvelope(long blockNumber, Set<ProjectionSectionType> requiredSections) {
        Optional<byte[]> identity = get(headerCf, ProjectionOutboxKeys.blockKey(blockNumber));
        if (identity.isEmpty()) return Optional.empty();
        ProjectionEnvelopeHeader identityHeader = ProjectionEnvelopeCodec.decodeHeader(identity.get());

        Map<ProjectionSectionType, ProjectionSectionManifest> manifests = new EnumMap<>(ProjectionSectionType.class);
        Map<ProjectionSectionType, List<byte[]>> chunks = new EnumMap<>(ProjectionSectionType.class);
        scanSections(blockNumber, manifests, chunks);

        List<ProjectionSection> sections = new ArrayList<>(manifests.size());
        List<ProjectionSectionManifest> orderedManifests = new ArrayList<>(manifests.size());
        for (Map.Entry<ProjectionSectionType, ProjectionSectionManifest> entry : manifests.entrySet()) {
            ProjectionSectionManifest manifest = entry.getValue();
            List<byte[]> payload = chunks.getOrDefault(entry.getKey(), List.of());
            if (payload.size() != manifest.chunkCount()) {
                throw new ProjectionOutboxException("section " + manifest.type().wireName() + " at block "
                        + blockNumber + " has " + payload.size() + " chunks but its manifest declares "
                        + manifest.chunkCount());
            }
            ProjectionSection section = new ProjectionSection(manifest.type(), manifest.version(), payload,
                    manifest.rowCount());
            sections.add(section);
            orderedManifests.add(manifest);
        }

        for (ProjectionSectionType required : requiredSections) {
            if (!manifests.containsKey(required) && !identityHeader.blockKind().allowsEmptyEnvelope()) {
                return Optional.empty();
            }
        }

        ProjectionEnvelopeHeader header = new ProjectionEnvelopeHeader(identityHeader.networkIdentity(),
                identityHeader.blockKind(), identityHeader.blockNumber(), identityHeader.blockHash(),
                identityHeader.parentHash(), identityHeader.slot(), identityHeader.epoch(),
                identityHeader.blockTime(), identityHeader.canonicalProjectionVersion(),
                orderedManifests, readArtifacts(blockNumber));
        return Optional.of(new ProjectionEnvelope(header, sections));
    }

    private void scanSections(long blockNumber, Map<ProjectionSectionType, ProjectionSectionManifest> manifests,
                              Map<ProjectionSectionType, List<byte[]>> chunks) {
        byte[] lower = ProjectionOutboxKeys.blockKey(blockNumber);
        byte[] upper = ProjectionOutboxKeys.blockKey(blockNumber + 1);
        try (Slice upperSlice = new Slice(upper);
             ReadOptions options = new ReadOptions().setIterateUpperBound(upperSlice);
             RocksIterator it = db.newIterator(sectionCf, options)) {
            for (it.seek(lower); it.isValid(); it.next()) {
                byte[] key = it.key();
                if (key.length != 14) continue;
                ProjectionSectionType type = ProjectionSectionType.fromCode(key[8]);
                if (key[9] == ProjectionOutboxKeys.KIND_MANIFEST) {
                    manifests.put(type, ProjectionSectionCodec.decodeManifest(it.value()));
                } else {
                    chunks.computeIfAbsent(type, k -> new ArrayList<>()).add(it.value());
                }
            }
        }
    }

    public List<com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef> readArtifacts(long blockNumber) {
        List<com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef> refs = new ArrayList<>();
        byte[] lower = ProjectionOutboxKeys.blockKey(blockNumber);
        byte[] upper = ProjectionOutboxKeys.blockKey(blockNumber + 1);
        try (Slice upperSlice = new Slice(upper);
             ReadOptions options = new ReadOptions().setIterateUpperBound(upperSlice);
             RocksIterator it = db.newIterator(artifactCf, options)) {
            for (it.seek(lower); it.isValid(); it.next()) {
                refs.add(ProjectionSectionCodec.decodeArtifact(it.value()));
            }
        }
        return refs;
    }

    /**
     * Every artifact reference in a block range, grouped by producing block.
     *
     * <p>One iterator for a whole batch rather than one per block. The per-block form costs a
     * RocksDB iterator and an upper-bound Slice for every block committed, which on a full sync
     * is millions of them for the handful of blocks that actually carry an artifact.
     *
     * <p>Only blocks with at least one reference appear, so a caller can treat an absent key as
     * an empty list without materialising one per block.
     */
    public java.util.Map<Long, List<com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef>>
            readArtifacts(long fromBlock, long throughBlock) {
        java.util.Map<Long, List<com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef>> byBlock =
                new java.util.HashMap<>();
        if (throughBlock < fromBlock) return byBlock;
        byte[] lower = ProjectionOutboxKeys.blockKey(fromBlock);
        byte[] upper = ProjectionOutboxKeys.blockKey(throughBlock + 1);
        try (Slice upperSlice = new Slice(upper);
             ReadOptions options = new ReadOptions().setIterateUpperBound(upperSlice);
             RocksIterator it = db.newIterator(artifactCf, options)) {
            for (it.seek(lower); it.isValid(); it.next()) {
                byBlock.computeIfAbsent(ProjectionOutboxKeys.blockFromKey(it.key()), k -> new ArrayList<>())
                        .add(ProjectionSectionCodec.decodeArtifact(it.value()));
            }
        }
        return byBlock;
    }

    /**
     * Record an artifact reference in its own synced write.
     *
     * <p>For evidence that became durable outside any RocksDB batch - a staged file, fsynced and
     * published before this is called. There is no batch to join, and joining one would be worse:
     * the reference must not become durable before the evidence it points at.
     */
    public synchronized void putArtifactDirect(long blockNumber,
            com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef ref) {
        long sealed = artifactsSealedThrough();
        if (blockNumber <= sealed) {
            throw new ProjectionOutboxException("cannot record staged artifact for block " + blockNumber
                    + ": artifact sets are sealed through block " + sealed);
        }
        long acknowledged = acknowledgedThrough();
        if (blockNumber <= acknowledged) {
            throw new ProjectionOutboxException("cannot record staged artifact for block " + blockNumber
                    + ": the outbox is acknowledged through block " + acknowledged);
        }
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions().setSync(true)) {
            batch.put(artifactCf,
                    ProjectionOutboxKeys.artifactKey(blockNumber, ref.dataset().name(), ref.semanticEpoch(),
                            ref.sourceGeneration()),
                    ProjectionSectionCodec.encodeArtifact(ref));
            db.write(options, batch);
        } catch (RocksDBException e) {
            throw new ProjectionOutboxException("failed to record staged artifact reference for "
                    + ref.dataset() + " epoch " + ref.semanticEpoch(), e);
        }
    }

    /**
     * Durably close every artifact set through {@code throughBlock} before a batch is built.
     *
     * <p>The marker is monotonic and the write is synchronized with {@link #putArtifactDirect}.
     * Once this returns, the consumer can re-read a stable artifact snapshot without holding a
     * Java lock across the sink commit. A crash preserves the seal, so restart cannot attach new
     * evidence to a range whose receipt may already be durable.
     */
    public synchronized void sealArtifactsThrough(long throughBlock) {
        if (throughBlock < 0) throw new IllegalArgumentException("artifact seal block must not be negative");
        long sealed = artifactsSealedThrough();
        if (throughBlock <= sealed) return;
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions().setSync(true)) {
            batch.put(metaCf, ProjectionOutboxKeys.META_ARTIFACT_SEALED,
                    ProjectionOutboxKeys.encodeLong(throughBlock));
            db.write(options, batch);
        } catch (RocksDBException e) {
            throw new ProjectionOutboxException("failed to seal projection artifacts through block "
                    + throughBlock, e);
        }
    }

    /**
     * Every artifact reference the outbox still holds.
     *
     * <p>This is the durable pruning contract. In-memory leases do not survive a restart, but
     * acknowledging a range deletes its artifact references, so what remains here is exactly the
     * set whose sources must stay retained. Startup replays this to re-establish protection
     * before anything can prune.
     */
    public List<com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef> pendingArtifacts() {
        Map<String, ProjectionArtifactRef> refs = new LinkedHashMap<>();
        try (RocksIterator it = db.newIterator(artifactCf)) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                ProjectionArtifactRef ref = ProjectionSectionCodec.decodeArtifact(it.value());
                refs.put(ref.canonicalForm(), ref);
            }
            it.status();
        } catch (RocksDBException e) {
            throw new ProjectionOutboxException("failed to scan pending artifact references", e);
        }
        byte[] prefix = ProjectionOutboxKeys.pendingEpochArtifactPrefix();
        try (RocksIterator it = db.newIterator(metaCf)) {
            for (it.seek(prefix); it.isValid() && startsWith(it.key(), prefix); it.next()) {
                ProjectionArtifactRef ref = ProjectionSectionCodec.decodeArtifact(it.value());
                refs.putIfAbsent(ref.canonicalForm(), ref);
            }
            it.status();
        } catch (RocksDBException e) {
            throw new ProjectionOutboxException("failed to scan pending epoch artifact intents", e);
        }
        return List.copyOf(refs.values());
    }

    /**
     * Read a contiguous, complete, eligible run starting at {@code fromBlock}.
     *
     * <p>Stops at the first block whose envelope is not yet assembled, so a batch never
     * spans a gap. Bounds are applied after at least one envelope so a single oversized
     * envelope still makes progress rather than deadlocking the consumer.
     */
    public List<ProjectionEnvelope> readRange(long fromBlock, long throughBlock,
                                              Set<ProjectionSectionType> requiredSections,
                                              int maxBlocks, long maxBytes) {
        List<ProjectionEnvelope> envelopes = new ArrayList<>();
        long bytes = 0;
        for (long block = fromBlock; block <= throughBlock && envelopes.size() < maxBlocks; block++) {
            Optional<ProjectionEnvelope> envelope = readEnvelope(block, requiredSections);
            if (envelope.isEmpty()) break;
            long size = envelope.get().sections().stream().mapToLong(ProjectionSection::byteCount).sum();
            if (!envelopes.isEmpty() && bytes + size > maxBytes) break;
            bytes += size;
            envelopes.add(envelope.get());
        }
        return envelopes;
    }

    // -------------------------------------------------------------- cleanup

    /**
     * Delete everything through {@code throughBlock} and record acknowledgement.
     *
     * <p>Acknowledgement is recorded in the same batch as the deletion, so a crash
     * cannot leave the outbox claiming data it has already removed. Re-running with the
     * same argument is harmless.
     */
    public synchronized void acknowledgeThrough(long throughBlock) {
        byte[] upper = ProjectionOutboxKeys.blockKey(throughBlock + 1);
        byte[] lower = ProjectionOutboxKeys.blockKey(0);
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions().setSync(true)) {
            batch.deleteRange(headerCf, lower, upper);
            batch.deleteRange(sectionCf, lower, upper);
            batch.deleteRange(artifactCf, lower, upper);
            byte[] pendingPrefix = ProjectionOutboxKeys.pendingEpochArtifactPrefix();
            try (RocksIterator iterator = db.newIterator(metaCf)) {
                for (iterator.seek(pendingPrefix);
                     iterator.isValid() && startsWith(iterator.key(), pendingPrefix); iterator.next()) {
                    if (ProjectionOutboxKeys.carrierFromPendingEpochArtifactKey(iterator.key()) <= throughBlock) {
                        batch.delete(metaCf, iterator.key());
                    }
                }
                iterator.status();
            }
            batch.put(metaCf, ProjectionOutboxKeys.META_ACK, ProjectionOutboxKeys.encodeLong(throughBlock));
            db.write(options, batch);
            pendingEpochArtifactCarriers.removeIf(carrier -> carrier <= throughBlock);
        } catch (RocksDBException e) {
            throw new ProjectionOutboxException("failed to acknowledge projection range", e);
        }
    }

    /**
     * Roll back every pending envelope newer than {@code rollbackSlot}.
     *
     * <p>Preferred over {@link #rollbackFrom} at the event boundary. Deriving the cutoff
     * from a live chain tip read inside a rollback listener is racy: the listener's order
     * relative to chain-state rollback is unspecified, so a tip read too early leaves
     * stale envelopes above the surviving tip — precisely the "replacement block at the
     * same height" case that must not survive. The envelope's own recorded slot is
     * authoritative and needs no cross-subsystem timing assumption.
     *
     * <p>Scans backwards from the newest header and stops at the first envelope at or
     * below the rollback slot, so the cost is proportional to the rolled-back suffix
     * rather than to the whole backlog.
     *
     * @return the number of envelopes removed
     */
    public long rollbackToSlot(long rollbackSlot, Set<ProjectionSectionType> requiredSections) {
        return rollbackToPoint(rollbackSlot, null, false, false, requiredSections);
    }

    /**
     * Roll back pending envelopes to an exact canonical point. A null hash denotes
     * origin when {@code origin} is true. At a shared Byron slot, a different-hash
     * successor is removed while the matching EBB or main-block envelope is retained.
     */
    public long rollbackToPoint(long rollbackSlot,
                                byte[] rollbackHash,
                                boolean origin,
                                Set<ProjectionSectionType> requiredSections) {
        return rollbackToPoint(rollbackSlot, rollbackHash, origin, true, requiredSections);
    }

    private long rollbackToPoint(long rollbackSlot,
                                 byte[] rollbackHash,
                                 boolean origin,
                                 boolean exact,
                                 Set<ProjectionSectionType> requiredSections) {
        if (!origin && (rollbackHash == null || rollbackHash.length != 32)) {
            if (exact) {
                throw new IllegalArgumentException("Non-origin projection rollback requires a 32-byte hash");
            }
        }
        long firstRemoved = -1;
        try (RocksIterator it = db.newIterator(headerCf)) {
            for (it.seekToLast(); it.isValid(); it.prev()) {
                ProjectionEnvelopeHeader header = ProjectionEnvelopeCodec.decodeHeader(it.value());
                if (!origin && (header.slot() < rollbackSlot
                        || header.slot() == rollbackSlot
                        && (!exact || Arrays.equals(header.blockHash(), rollbackHash)))) {
                    break;
                }
                firstRemoved = header.blockNumber();
            }
        }
        long removedPending = rollbackPendingEpochArtifacts(rollbackSlot, rollbackHash, origin, exact);
        if (firstRemoved < 0) return removedPending;
        long lastBlock = identityCursor();
        rollbackFrom(firstRemoved, requiredSections);
        return Math.max(0, lastBlock - firstRemoved + 1) + removedPending;
    }

    /**
     * Remove transition intents only when their anchor is no longer canonical. An intent whose
     * carrier was rolled back but whose anchor survives is deliberately retained so the replacement
     * carrier can attach the same facts without re-running the epoch transition.
     */
    private long rollbackPendingEpochArtifacts(long rollbackSlot, byte[] rollbackHash,
                                               boolean origin, boolean exact) {
        long removed = 0;
        byte[] prefix = ProjectionOutboxKeys.pendingEpochArtifactPrefix();
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions().setSync(true);
             RocksIterator iterator = db.newIterator(metaCf)) {
            for (iterator.seek(prefix); iterator.isValid() && startsWith(iterator.key(), prefix); iterator.next()) {
                ProjectionArtifactRef ref = ProjectionSectionCodec.decodeArtifact(iterator.value());
                if (origin || ref.producingSlot() > rollbackSlot
                        || ref.producingSlot() == rollbackSlot
                        && (!exact || !Arrays.equals(ref.producingBlockHash(), rollbackHash))) {
                    batch.delete(metaCf, iterator.key());
                    removed++;
                }
            }
            iterator.status();
            if (removed > 0) {
                db.write(options, batch);
                refreshPendingEpochArtifactCarriers();
            }
            return removed;
        } catch (RocksDBException e) {
            throw new ProjectionOutboxException("failed to roll back pending epoch artifacts", e);
        }
    }

    private void refreshPendingEpochArtifactCarriers() {
        pendingEpochArtifactCarriers.clear();
        if (metaCf == null) return;
        byte[] prefix = ProjectionOutboxKeys.pendingEpochArtifactPrefix();
        try (RocksIterator iterator = db.newIterator(metaCf)) {
            for (iterator.seek(prefix); iterator.isValid() && startsWith(iterator.key(), prefix); iterator.next()) {
                pendingEpochArtifactCarriers.add(
                        ProjectionOutboxKeys.carrierFromPendingEpochArtifactKey(iterator.key()));
            }
            iterator.status();
        } catch (RocksDBException e) {
            throw new ProjectionOutboxException("failed to restore pending epoch artifact carriers", e);
        }
    }

    /**
     * Remove pending envelopes at or above {@code fromBlock} after a rollback, and pull
     * every contributor cursor back with them.
     *
     * <p>Cursors must move back too. Leaving one ahead of the deleted data would make
     * the outbox believe a block it no longer holds was already contributed, and the
     * replaced block at that height would never be projected.
     */
    public void rollbackFrom(long fromBlock, Set<ProjectionSectionType> requiredSections) {
        byte[] lower = ProjectionOutboxKeys.blockKey(fromBlock);
        byte[] upper = ProjectionOutboxKeys.blockKey(Long.MAX_VALUE);
        long rewound = fromBlock - 1;
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions().setSync(true)) {
            batch.deleteRange(headerCf, lower, upper);
            batch.deleteRange(sectionCf, lower, upper);
            batch.deleteRange(artifactCf, lower, upper);
            if (identityCursor() > rewound) {
                batch.put(metaCf, ProjectionOutboxKeys.META_CURSOR_IDENTITY,
                        ProjectionOutboxKeys.encodeLong(rewound));
            }
            for (ProjectionSectionType type : requiredSections) {
                if (contributorCursor(type) > rewound) {
                    batch.put(metaCf, ProjectionOutboxKeys.cursorKey(type), ProjectionOutboxKeys.encodeLong(rewound));
                }
            }
            db.write(options, batch);
        } catch (RocksDBException e) {
            throw new ProjectionOutboxException("failed to roll back pending projection envelopes", e);
        }
    }

    // ------------------------------------------------------------------- metrics

    public ProjectionOutboxStats stats(Set<ProjectionSectionType> requiredSections) {
        long pendingBlocks = 0;
        long oldest = -1;
        try (RocksIterator it = db.newIterator(headerCf)) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                if (oldest < 0) oldest = ProjectionOutboxKeys.blockFromKey(it.key());
                pendingBlocks++;
            }
        }
        long bytes = 0;
        long rows = 0;
        try (RocksIterator it = db.newIterator(sectionCf)) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                byte[] key = it.key();
                if (key.length == 14 && key[9] == ProjectionOutboxKeys.KIND_MANIFEST) {
                    ProjectionSectionManifest manifest = ProjectionSectionCodec.decodeManifest(it.value());
                    rows += manifest.rowCount();
                } else {
                    bytes += it.value().length;
                }
            }
        }
        return new ProjectionOutboxStats(pendingBlocks, bytes, rows, oldest,
                completeThrough(requiredSections), acknowledgedThrough());
    }

    public ProjectionCoordinate coordinateOf(ProjectionBatch batch) {
        var envelopes = batch.envelopes();
        return ProjectionCoordinate.of(envelopes.get(envelopes.size() - 1).header());
    }

    private Optional<byte[]> get(ColumnFamilyHandle cf, byte[] key) {
        try {
            return Optional.ofNullable(db.get(cf, key));
        } catch (RocksDBException e) {
            throw new ProjectionOutboxException("failed to read projection outbox key", e);
        }
    }
}
