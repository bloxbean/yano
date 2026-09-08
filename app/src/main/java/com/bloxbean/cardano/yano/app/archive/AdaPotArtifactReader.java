package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveRowCodec;
import com.bloxbean.cardano.yano.archive.api.projection.ArchiveArtifactReader;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef;
import com.bloxbean.cardano.yano.archive.core.projection.AdaPotArtifactRows;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Serves the ADA-pot artifact from the evidence carried on the reference itself.
 *
 * <p>Nothing is read from the store. The pot is not written through the boundary's batch and is
 * re-stored as rewards and governance adjust it, so there is no generation to reference; the eight
 * values travel inline instead. That makes this reader stateless, and it is why there is no lease
 * to hold and nothing for retention to protect.
 */
public final class AdaPotArtifactReader implements ArchiveArtifactReader {

    private final long networkMagic;
    private final ArtifactBoundaryFacts boundaryFacts;

    public AdaPotArtifactReader(long networkMagic, ArtifactBoundaryFacts boundaryFacts) {
        this.networkMagic = networkMagic;
        this.boundaryFacts = boundaryFacts;
    }

    @Override
    public ArtifactLease acquire(ProjectionArtifactRef ref, Instant expiresAt) {
        require(ref);
        // A lease with nothing to protect. Kept rather than skipped so the sink's read path is
        // uniform, and so lease discipline stays observable for every artifact.
        return new InlineLease(expiresAt);
    }

    @Override
    public ArtifactPage read(ProjectionArtifactRef ref, ArtifactLease lease, Optional<String> cursor,
                             int limit) {
        require(ref);
        if (!lease.isOpen()) throw new IllegalStateException("artifact lease is closed");
        if (cursor.isPresent()) return new ArtifactPage(List.of(), Optional.empty());

        long[] values = AdaPotArtifactRows.decode(ref.inlinePayload());
        byte[] hash = Objects.requireNonNull(boundaryFacts, "boundary facts are required")
                .blockHash(ref.producingBlockNumber())
                .orElseThrow(() -> new IllegalStateException("no canonical block reference at boundary block "
                        + ref.producingBlockNumber() + " for the ada-pot artifact of epoch " + ref.semanticEpoch()));
        if (!Arrays.equals(hash, ref.producingBlockHash())) {
            throw new IllegalStateException("ada-pot anchor is no longer canonical at block "
                    + ref.producingBlockNumber());
        }

        var row = AdaPotArtifactRows.row(ref, values, hash,
                boundaryFacts.blockTimeSeconds(ref.producingSlot()), AdaPotArtifactRows.jobId(ref));
        return new ArtifactPage(List.of(ArchiveRowCodec.encode(row)), Optional.empty());
    }

    @Override
    public void acknowledge(ProjectionArtifactRef ref) {
        require(ref);
        // Deliberately nothing. Acknowledgement exists to release a protected source, and this
        // artifact has none - the evidence was inline and the outbox drops it with the range.
    }

    @Override
    public void reconcileAfterRestart(java.util.Collection<ProjectionArtifactRef> pending) {
        // Deliberately nothing, for the same reason: there is no source that pruning could take.
    }

    /** Unused by this reader, but kept so callers need not special-case the magic. */
    long networkMagic() {
        return networkMagic;
    }

    private static void require(ProjectionArtifactRef ref) {
        if (ref.dataset() != ArchiveDatasetId.ADA_POT) {
            throw new IllegalArgumentException("this reader serves ADA_POT, not " + ref.dataset());
        }
    }

    private static final class InlineLease implements ArtifactLease {
        private final UUID id = UUID.randomUUID();
        private Instant expiry;
        private boolean open = true;

        InlineLease(Instant expiry) { this.expiry = expiry; }

        @Override public UUID leaseId() { return id; }
        @Override public String ownerFence() { return "projection-drain"; }
        @Override public Instant expiresAt() { return expiry; }
        @Override public ArtifactLease renew(Instant newExpiry) { expiry = newExpiry; return this; }
        @Override public boolean isOpen() { return open; }
        @Override public void close() { open = false; }
    }
}
