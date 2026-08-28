package com.bloxbean.cardano.yano.archive.api.projection;

/** Positive durable sink outcome; row absence is never an outcome. */
public enum EpochArtifactCoverageOutcome {
    COMPLETE,
    GAP
}
