package com.bloxbean.cardano.yano.archive.api.projection;

import com.bloxbean.cardano.yano.archive.api.ArchiveRow;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A projection batch presented to a sink as a <em>stream</em> of archive rows.
 *
 * <p>Row derivation happens once, in shared archive-core code, so every backend sees the same
 * interpretation of the chain and a storage module needs only {@code archive-api} — no
 * projection codec, no fact model. A sink does format translation and batching; it does not
 * repeat ledger resolution.
 *
 * <p>{@code rows} is an {@link Iterable} rather than a {@code List} deliberately. Holding the
 * whole batch as a list made peak heap proportional to batch size, which meant the configured
 * memory ceiling was nominal: a single dense block larger than the ceiling was still
 * materialised in full. Streaming keeps only one envelope's rows live at a time, so the bound
 * holds however dense a block turns out to be.
 *
 * <p>Implementations must be <strong>re-iterable</strong>. A sink may need a second pass — to
 * retry after a recoverable failure, or to verify — and a single-shot iterator would silently
 * produce an empty second pass rather than failing.
 */
public record ProjectionRowBatch(ProjectionIdentity identity, long firstBlock, long lastBlock,
                                 long blockCount, String firstEnvelopeId, String lastEnvelopeId,
                                 String orderedDigest, Iterable<ArchiveRow> rows,
                                 List<ProjectionArtifactRef> artifacts,
                                 Map<Long, byte[]> canonicalBlockHashes,
                                 long lastSlot,
                                 List<EpochArtifactIntervalRepair> intervalRepairs) implements ProjectionJob {
    public ProjectionRowBatch {
        Objects.requireNonNull(identity, "identity");
        firstEnvelopeId = Objects.requireNonNull(firstEnvelopeId, "firstEnvelopeId").trim().toLowerCase();
        lastEnvelopeId = Objects.requireNonNull(lastEnvelopeId, "lastEnvelopeId").trim().toLowerCase();
        orderedDigest = Objects.requireNonNull(orderedDigest, "orderedDigest").trim().toLowerCase();
        Objects.requireNonNull(rows, "rows");
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
        Objects.requireNonNull(canonicalBlockHashes, "canonicalBlockHashes");
        canonicalBlockHashes = canonicalBlockHashes.entrySet().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        entry -> entry.getValue().clone()));
        intervalRepairs = List.copyOf(Objects.requireNonNull(intervalRepairs, "intervalRepairs"));
        if (firstBlock < 0 || lastBlock < firstBlock) throw new IllegalArgumentException("invalid batch range");
        if (lastSlot < 0) throw new IllegalArgumentException("lastSlot must not be negative");
        if (blockCount != lastBlock - firstBlock + 1) {
            throw new IllegalArgumentException("blockCount does not match the batch range");
        }
        if (firstEnvelopeId.isEmpty() || lastEnvelopeId.isEmpty() || orderedDigest.isEmpty()) {
            throw new IllegalArgumentException("batch identity fields are required");
        }
    }

    /** Compatibility constructor for row batches without epoch artifacts. */
    public ProjectionRowBatch(ProjectionIdentity identity, long firstBlock, long lastBlock,
                              long blockCount, String firstEnvelopeId, String lastEnvelopeId,
                              String orderedDigest, Iterable<ArchiveRow> rows,
                              List<ProjectionArtifactRef> artifacts) {
        this(identity, firstBlock, lastBlock, blockCount, firstEnvelopeId, lastEnvelopeId,
                orderedDigest, rows, artifacts, Map.of(), lastBlock, List.of());
    }

    /** Compatibility constructor for callers that provide hashes but no interval repair. */
    public ProjectionRowBatch(ProjectionIdentity identity, long firstBlock, long lastBlock,
                              long blockCount, String firstEnvelopeId, String lastEnvelopeId,
                              String orderedDigest, Iterable<ArchiveRow> rows,
                              List<ProjectionArtifactRef> artifacts,
                              Map<Long, byte[]> canonicalBlockHashes) {
        this(identity, firstBlock, lastBlock, blockCount, firstEnvelopeId, lastEnvelopeId,
                orderedDigest, rows, artifacts, canonicalBlockHashes, lastBlock, List.of());
    }

    @Override
    public Map<Long, byte[]> canonicalBlockHashes() {
        return canonicalBlockHashes.entrySet().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        entry -> entry.getValue().clone()));
    }
}
