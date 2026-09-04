package com.bloxbean.cardano.yano.archive.api.projection;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;

import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Reference to a large immutable epoch artifact streamed by the sink rather than
 * copied into the block envelope (ADR-039 §5).
 *
 * <p>{@code producingBlockNumber}, {@code producingSlot} and {@code producingBlockHash}
 * identify the last canonical block of the source epoch. They describe provenance, not
 * transport: the outbox stores this reference separately under the first applying block of the
 * new epoch, and that carrier block governs ordering, rollback attachment and finality.
 */
public record ProjectionArtifactRef(ArchiveDatasetId dataset, int semanticEpoch,
                                    long producingBlockNumber, long producingSlot,
                                    byte[] producingBlockHash,
                                    ProjectionArtifactRepresentation representation,
                                    String sourceGeneration, int sourceCodecVersion,
                                    String sourceStateVersion,
                                    OptionalLong expectedRowCount, String contentDigest,
                                    long oldestRequiredSlot, byte[] inlinePayload) {
    /**
     * Canonical identity of every field in this reference, for binding into a receipt digest.
     *
     * <p>The chunks are length-prefixed by {@link ProjectionDigest#ofChunks}, so values containing
     * delimiters cannot collide. Content and row metadata are both part of the identity: a
     * receipt must not authorise releasing evidence whose payload, source version, coordinate or
     * retention requirement differs from what the sink committed.
     */
    public String canonicalForm() {
        var utf8 = java.nio.charset.StandardCharsets.UTF_8;
        return ProjectionDigest.ofChunks(java.util.List.of(
                dataset.logicalName().getBytes(utf8),
                Integer.toString(semanticEpoch).getBytes(utf8),
                Long.toString(producingBlockNumber).getBytes(utf8),
                Long.toString(producingSlot).getBytes(utf8),
                producingBlockHash,
                representation.name().getBytes(utf8),
                sourceGeneration.getBytes(utf8),
                Integer.toString(sourceCodecVersion).getBytes(utf8),
                sourceStateVersion.getBytes(utf8),
                expectedRowCount.isPresent()
                        ? ("present:" + expectedRowCount.getAsLong()).getBytes(utf8)
                        : "absent".getBytes(utf8),
                contentDigest.getBytes(utf8),
                Long.toString(oldestRequiredSlot).getBytes(utf8),
                inlinePayload));
    }

    public ProjectionArtifactRef {
        Objects.requireNonNull(dataset, "dataset");
        // Block datasets travel as sections inside the envelope, not as artifacts referenced from
        // it. Accepting one here would create a reference the artifact reader can never resolve,
        // and the mistake would surface as a retention pin rather than as a type error.
        if (dataset.sourceKind() != com.bloxbean.cardano.yano.archive.api.SourceKind.EPOCH) {
            throw new IllegalArgumentException("artifact references describe epoch datasets; "
                    + dataset + " is a block dataset and belongs in a section");
        }
        Objects.requireNonNull(representation, "representation");
        Objects.requireNonNull(producingBlockHash, "producingBlockHash");
        if (producingBlockHash.length == 0) {
            throw new IllegalArgumentException("producingBlockHash is required");
        }
        producingBlockHash = producingBlockHash.clone();
        sourceGeneration = Objects.requireNonNull(sourceGeneration, "sourceGeneration").trim();
        sourceStateVersion = Objects.requireNonNull(sourceStateVersion, "sourceStateVersion").trim();
        Objects.requireNonNull(expectedRowCount, "expectedRowCount");
        contentDigest = contentDigest == null ? "" : contentDigest.trim().toLowerCase();
        // ATOMIC_EVIDENCE means the evidence travels with the reference, because its source is
        // not written atomically with anything the outbox controls. The other representations
        // point at a durable source, so carrying a copy would create a second truth.
        inlinePayload = inlinePayload == null ? new byte[0] : inlinePayload.clone();
        if (representation == ProjectionArtifactRepresentation.ATOMIC_EVIDENCE) {
            if (inlinePayload.length == 0) {
                throw new IllegalArgumentException("ATOMIC_EVIDENCE artifacts must carry their evidence");
            }
        } else if (inlinePayload.length > 0) {
            throw new IllegalArgumentException(representation
                    + " artifacts read from their source; they must not carry an inline payload");
        }
        if (semanticEpoch < 0 || producingBlockNumber < 0 || producingSlot < 0) {
            throw new IllegalArgumentException("invalid artifact coordinate");
        }
        if (sourceGeneration.isEmpty() || sourceStateVersion.isEmpty()) {
            throw new IllegalArgumentException("sourceGeneration and sourceStateVersion are required");
        }
        if (sourceCodecVersion < 1) throw new IllegalArgumentException("sourceCodecVersion must be positive");
        // This is the oldest CHAIN slot whose retention the artifact depends on - the input to
        // the lost-replay-data check. It is not "the slot below which rollback would invalidate
        // this artifact": rollback already deletes artifact references along with their
        // envelopes, so that case is handled structurally rather than by retention health.
        //
        // -1 means "no chain retention dependency" and is legitimate for any representation whose
        // source is not replay data: inline evidence carries its own, and a protected generation
        // is held by its pruning clamp. Consumers must exclude -1 from the batch retention floor
        // rather than minimise over it.
        if (oldestRequiredSlot < -1) {
            throw new IllegalArgumentException("oldestRequiredSlot must be a slot, or -1 for none");
        }
    }

    /**
     * A reference-style artifact, which carries no inline evidence.
     *
     * <p>The common case: the artifact points at a durable source and the sink reads it through
     * the reader. Only ATOMIC_EVIDENCE needs the canonical form.
     */
    public ProjectionArtifactRef(ArchiveDatasetId dataset, int semanticEpoch,
                                 long producingBlockNumber, long producingSlot,
                                 byte[] producingBlockHash,
                                 ProjectionArtifactRepresentation representation,
                                 String sourceGeneration, int sourceCodecVersion,
                                 String sourceStateVersion,
                                 OptionalLong expectedRowCount, String contentDigest,
                                 long oldestRequiredSlot) {
        this(dataset, semanticEpoch, producingBlockNumber, producingSlot, producingBlockHash, representation,
                sourceGeneration, sourceCodecVersion, sourceStateVersion, expectedRowCount,
                contentDigest, oldestRequiredSlot, new byte[0]);
    }

    @Override
    public byte[] producingBlockHash() {
        return producingBlockHash.clone();
    }

    /**
     * Defensive copy, and value semantics for the array.
     *
     * <p>A record's generated {@code equals} compares arrays by identity, which would make two
     * refs decoded from the same bytes unequal - and artifact refs are compared when verifying a
     * replayed batch.
     */
    @Override
    public byte[] inlinePayload() {
        return inlinePayload.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ProjectionArtifactRef ref)) return false;
        return semanticEpoch == ref.semanticEpoch
                && producingBlockNumber == ref.producingBlockNumber
                && producingSlot == ref.producingSlot
                && Arrays.equals(producingBlockHash, ref.producingBlockHash)
                && sourceCodecVersion == ref.sourceCodecVersion
                && oldestRequiredSlot == ref.oldestRequiredSlot
                && dataset == ref.dataset
                && representation == ref.representation
                && sourceGeneration.equals(ref.sourceGeneration)
                && sourceStateVersion.equals(ref.sourceStateVersion)
                && expectedRowCount.equals(ref.expectedRowCount)
                && contentDigest.equals(ref.contentDigest)
                && Arrays.equals(inlinePayload, ref.inlinePayload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(dataset, semanticEpoch, producingBlockNumber, producingSlot,
                Arrays.hashCode(producingBlockHash),
                representation, sourceGeneration, sourceCodecVersion, sourceStateVersion,
                expectedRowCount, contentDigest, oldestRequiredSlot,
                Arrays.hashCode(inlinePayload));
    }

    @Override
    public String toString() {
        return "ProjectionArtifactRef[" + dataset + " epoch=" + semanticEpoch
                + " block=" + producingBlockNumber + " " + representation
                + " generation=" + sourceGeneration + " payload=" + inlinePayload.length + "B]";
    }
}
