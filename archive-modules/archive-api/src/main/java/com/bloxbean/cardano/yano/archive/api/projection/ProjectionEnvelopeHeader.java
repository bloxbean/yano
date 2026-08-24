package com.bloxbean.cardano.yano.archive.api.projection;

import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Logical header for one canonical block's projection envelope (ADR-039 §2).
 *
 * <p>{@link #envelopeId()} is <em>derived</em> rather than stored. Replaying the same
 * canonical block therefore addresses the same logical envelope, and a replacement
 * block at the same height necessarily addresses a different one because its hash and
 * parent participate in the derivation. Because the identifier cannot be supplied by a
 * caller it also cannot disagree with the header it labels.
 */
public record ProjectionEnvelopeHeader(ArchiveNetworkIdentity networkIdentity,
                                       ProjectionBlockKind blockKind,
                                       long blockNumber, byte[] blockHash, byte[] parentHash,
                                       long slot, int epoch, long blockTime,
                                       int canonicalProjectionVersion,
                                       List<ProjectionSectionManifest> sections,
                                       List<ProjectionArtifactRef> artifacts) {
    public ProjectionEnvelopeHeader {
        Objects.requireNonNull(networkIdentity, "networkIdentity");
        Objects.requireNonNull(blockKind, "blockKind");
        if (blockNumber < 0 || slot < 0 || epoch < 0) throw new IllegalArgumentException("invalid block coordinate");
        if (blockHash == null || blockHash.length == 0) throw new IllegalArgumentException("blockHash is required");
        if (parentHash == null) throw new IllegalArgumentException("parentHash is required");
        if (canonicalProjectionVersion < 1) throw new IllegalArgumentException("canonicalProjectionVersion must be positive");
        blockHash = Arrays.copyOf(blockHash, blockHash.length);
        parentHash = Arrays.copyOf(parentHash, parentHash.length);
        sections = List.copyOf(Objects.requireNonNull(sections, "sections"));
        artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
        if (blockKind.allowsEmptyEnvelope() && !sections.isEmpty()) {
            throw new IllegalArgumentException("an epoch-boundary block must produce an empty envelope");
        }
        if (sections.stream().map(ProjectionSectionManifest::type).distinct().count() != sections.size()) {
            throw new IllegalArgumentException("duplicate section type in envelope header");
        }
    }

    @Override public byte[] blockHash() { return Arrays.copyOf(blockHash, blockHash.length); }
    @Override public byte[] parentHash() { return Arrays.copyOf(parentHash, parentHash.length); }

    /** Deterministic identity over canonical coordinates and projection version only. */
    public String envelopeId() {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        digest.update(networkIdentity.canonicalForm().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        ByteBuffer numbers = ByteBuffer.allocate(8 + 8 + 4).order(ByteOrder.BIG_ENDIAN);
        numbers.putLong(blockNumber).putLong(slot).putInt(canonicalProjectionVersion);
        digest.update(numbers.array());
        digest.update(blockHash);
        digest.update((byte) 0);
        digest.update(parentHash);
        return ProjectionDigest.hex(digest.digest());
    }

    public java.util.Optional<ProjectionSectionManifest> section(ProjectionSectionType type) {
        return sections.stream().filter(s -> s.type() == type).findFirst();
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ProjectionEnvelopeHeader h
                && blockNumber == h.blockNumber && slot == h.slot && epoch == h.epoch
                && blockTime == h.blockTime && canonicalProjectionVersion == h.canonicalProjectionVersion
                && blockKind == h.blockKind && networkIdentity.equals(h.networkIdentity)
                && Arrays.equals(blockHash, h.blockHash) && Arrays.equals(parentHash, h.parentHash)
                && sections.equals(h.sections) && artifacts.equals(h.artifacts);
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(blockNumber);
        result = 31 * result + Arrays.hashCode(blockHash);
        result = 31 * result + Arrays.hashCode(parentHash);
        return 31 * result + sections.hashCode();
    }
}
