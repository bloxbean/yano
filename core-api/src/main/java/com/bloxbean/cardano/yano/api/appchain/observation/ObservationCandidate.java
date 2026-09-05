package com.bloxbean.cardano.yano.api.appchain.observation;

/** Canonical node-local acquisition output before the member report is signed. */
public record ObservationCandidate(
        byte[] sourceId,
        byte[] value,
        byte[] evidence,
        byte[] sourceVersion,
        int freshnessAnchorType,
        long freshnessAnchor
) {
    public ObservationCandidate {
        sourceId = ObservationCbor.bounded(sourceId, 256, "candidate source id");
        value = ObservationCbor.bounded(value, 64 * 1024, "candidate value");
        evidence = ObservationCbor.bounded(evidence, 384 * 1024, "candidate evidence");
        sourceVersion = ObservationCbor.bounded(
                sourceVersion, 256, "candidate source version");
        if (sourceId.length == 0 || sourceVersion.length == 0
                || freshnessAnchorType < 0 || freshnessAnchor < 0) {
            throw new IllegalArgumentException("invalid observation candidate");
        }
    }

    @Override public byte[] sourceId() { return sourceId.clone(); }
    @Override public byte[] value() { return value.clone(); }
    @Override public byte[] evidence() { return evidence.clone(); }
    @Override public byte[] sourceVersion() { return sourceVersion.clone(); }
}
