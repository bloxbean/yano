package com.bloxbean.cardano.yano.archive.api.projection;

/** Why an epoch artifact became part of this archive's capture contract. */
public enum ProjectionArtifactEnrollmentOrigin {
    FRESH,
    PROSPECTIVE_JOIN,
    LEGACY_UNKNOWN
}
