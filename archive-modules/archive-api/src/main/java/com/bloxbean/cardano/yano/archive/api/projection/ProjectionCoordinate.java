package com.bloxbean.cardano.yano.archive.api.projection;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * The greatest <em>contiguous</em> canonical block envelope durably committed by the
 * primary sink (ADR-039 §16). Epoch-artifact coverage is reported separately by type
 * and semantic epoch because its publication delay differs.
 */
public record ProjectionCoordinate(long blockNumber, long slot, byte[] blockHash, String envelopeId) {
    /** No envelope has been committed yet; a fresh archive starts here. */
    public static final ProjectionCoordinate NONE = new ProjectionCoordinate(-1, -1, new byte[0], "");

    public ProjectionCoordinate {
        Objects.requireNonNull(blockHash, "blockHash");
        envelopeId = Objects.requireNonNull(envelopeId, "envelopeId").trim().toLowerCase();
        blockHash = Arrays.copyOf(blockHash, blockHash.length);
        boolean empty = blockNumber < 0;
        if (empty && (slot >= 0 || blockHash.length != 0 || !envelopeId.isEmpty())) {
            throw new IllegalArgumentException("an empty coordinate must carry no block identity");
        }
        if (!empty && (slot < 0 || blockHash.length == 0 || envelopeId.isEmpty())) {
            throw new IllegalArgumentException("a present coordinate requires slot, hash and envelope id");
        }
    }

    public boolean isPresent() {
        return blockNumber >= 0;
    }

    public static ProjectionCoordinate of(ProjectionEnvelopeHeader header) {
        return new ProjectionCoordinate(header.blockNumber(), header.slot(), header.blockHash(), header.envelopeId());
    }

    public Optional<Long> nextExpectedBlock() {
        return isPresent() ? Optional.of(blockNumber + 1) : Optional.empty();
    }

    @Override public byte[] blockHash() { return Arrays.copyOf(blockHash, blockHash.length); }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ProjectionCoordinate c
                && blockNumber == c.blockNumber && slot == c.slot
                && Arrays.equals(blockHash, c.blockHash) && envelopeId.equals(c.envelopeId);
    }

    @Override
    public int hashCode() {
        return 31 * Long.hashCode(blockNumber) + Arrays.hashCode(blockHash);
    }
}
