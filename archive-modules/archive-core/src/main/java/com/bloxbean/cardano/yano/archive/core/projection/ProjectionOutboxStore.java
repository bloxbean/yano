package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yano.api.archive.ProjectionCfNames;
import com.bloxbean.cardano.yano.api.archive.ProjectionStagingWriter;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionBatch;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionCoordinate;
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
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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

    private final RocksDB db;
    private final ColumnFamilyHandle headerCf;
    private final ColumnFamilyHandle sectionCf;
    private final ColumnFamilyHandle metaCf;
    private final ColumnFamilyHandle artifactCf;

    public ProjectionOutboxStore(RocksDB db, ColumnFamilyHandle headerCf, ColumnFamilyHandle sectionCf,
                                 ColumnFamilyHandle metaCf, ColumnFamilyHandle artifactCf) {
        this.db = Objects.requireNonNull(db, "db");
        this.headerCf = Objects.requireNonNull(headerCf, "headerCf");
        this.sectionCf = Objects.requireNonNull(sectionCf, "sectionCf");
        this.metaCf = Objects.requireNonNull(metaCf, "metaCf");
        this.artifactCf = Objects.requireNonNull(artifactCf, "artifactCf");
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
        writer.put(ProjectionCfNames.PROJ_META, ProjectionOutboxKeys.META_CURSOR_IDENTITY,
                ProjectionOutboxKeys.encodeLong(header.blockNumber()));
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
        writer.put(ProjectionCfNames.PROJ_META, ProjectionOutboxKeys.cursorKey(section.type()),
                ProjectionOutboxKeys.encodeLong(blockNumber));
    }

    /**
     * Advance a contributor's cursor without writing a section. A block that legitimately
     * produces no rows for a dataset must still advance, or the envelope would wait
     * forever on a contributor that has nothing to say.
     */
    public void advanceContributor(ProjectionStagingWriter writer, ProjectionSectionType type, long blockNumber) {
        writer.put(ProjectionCfNames.PROJ_META, ProjectionOutboxKeys.cursorKey(type),
                ProjectionOutboxKeys.encodeLong(blockNumber));
    }

    /** Stage an epoch-artifact reference alongside its producing block. */
    public void putArtifact(ProjectionStagingWriter writer, long blockNumber,
                            com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef ref) {
        writer.put(ProjectionCfNames.PROJ_ARTIFACT,
                ProjectionOutboxKeys.artifactKey(blockNumber, ref.dataset().name(), ref.semanticEpoch()),
                ProjectionSectionCodec.encodeArtifact(ref));
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
     * <p>Used by contributors that have no other subsystem batch to join — notably Byron,
     * which the live UTXO path never applies. The section and its cursor still commit
     * atomically with each other, and the durable replay intent remains the retained
     * canonical body plus that cursor.
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

    private List<com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef> readArtifacts(long blockNumber) {
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
    public void acknowledgeThrough(long throughBlock) {
        byte[] upper = ProjectionOutboxKeys.blockKey(throughBlock + 1);
        byte[] lower = ProjectionOutboxKeys.blockKey(0);
        try (WriteBatch batch = new WriteBatch(); WriteOptions options = new WriteOptions().setSync(true)) {
            batch.deleteRange(headerCf, lower, upper);
            batch.deleteRange(sectionCf, lower, upper);
            batch.deleteRange(artifactCf, lower, upper);
            batch.put(metaCf, ProjectionOutboxKeys.META_ACK, ProjectionOutboxKeys.encodeLong(throughBlock));
            db.write(options, batch);
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
        long firstRemoved = -1;
        try (RocksIterator it = db.newIterator(headerCf)) {
            for (it.seekToLast(); it.isValid(); it.prev()) {
                ProjectionEnvelopeHeader header = ProjectionEnvelopeCodec.decodeHeader(it.value());
                if (header.slot() <= rollbackSlot) break;
                firstRemoved = header.blockNumber();
            }
        }
        if (firstRemoved < 0) return 0;
        long lastBlock = identityCursor();
        rollbackFrom(firstRemoved, requiredSections);
        return Math.max(0, lastBlock - firstRemoved + 1);
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
