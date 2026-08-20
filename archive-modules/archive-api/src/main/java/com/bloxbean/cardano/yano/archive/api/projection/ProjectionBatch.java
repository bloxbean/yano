package com.bloxbean.cardano.yano.archive.api.projection;

import java.util.List;
import java.util.Objects;

/**
 * A contiguous run of complete, rollback-safe envelopes offered to the sink as one
 * deterministic job (ADR-039 §9, §10).
 *
 * <p>Contiguity is enforced here rather than trusted from the caller: the sink may
 * batch blocks 100..120 only when every envelope in that range is present and in
 * canonical order. A gap is a bug in the consumer, and failing at construction keeps
 * it from reaching a sink transaction where it would look like legitimate progress.
 */
public record ProjectionBatch(ProjectionIdentity identity, List<ProjectionEnvelope> envelopes)
        implements ProjectionJob {
    public ProjectionBatch {
        Objects.requireNonNull(identity, "identity");
        envelopes = List.copyOf(Objects.requireNonNull(envelopes, "envelopes"));
        if (envelopes.isEmpty()) throw new IllegalArgumentException("a projection batch must not be empty");
        for (int i = 1; i < envelopes.size(); i++) {
            long previous = envelopes.get(i - 1).blockNumber();
            long current = envelopes.get(i).blockNumber();
            if (current != previous + 1) {
                throw new IllegalArgumentException(
                        "projection batch is not contiguous at block " + current + " (previous " + previous + ")");
            }
        }
    }

    public long firstBlock() {
        return envelopes.get(0).blockNumber();
    }

    public long lastBlock() {
        return envelopes.get(envelopes.size() - 1).blockNumber();
    }

    public long blockCount() {
        return envelopes.size();
    }

    @Override
    public String firstEnvelopeId() {
        return envelopes.get(0).envelopeId();
    }

    @Override
    public String lastEnvelopeId() {
        return envelopes.get(envelopes.size() - 1).envelopeId();
    }

    public long byteCount() {
        return envelopes.stream()
                .flatMap(e -> e.sections().stream())
                .mapToLong(ProjectionSection::byteCount)
                .sum();
    }

    public long rowCount() {
        return envelopes.stream()
                .flatMap(e -> e.sections().stream())
                .mapToLong(ProjectionSection::rowCount)
                .sum();
    }

    public List<ProjectionArtifactRef> artifacts() {
        return envelopes.stream().flatMap(e -> e.header().artifacts().stream()).toList();
    }

    /** Ordered digest over every envelope identity and section digest in canonical order. */
    public String orderedDigest() {
        return ProjectionDigest.ofDigests(envelopes.stream()
                .flatMap(e -> java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(e.envelopeId()),
                        e.header().sections().stream().map(ProjectionSectionManifest::digest)))
                .toList());
    }
}
