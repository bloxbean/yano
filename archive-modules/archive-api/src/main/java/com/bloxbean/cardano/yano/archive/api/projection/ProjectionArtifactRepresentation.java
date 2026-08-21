package com.bloxbean.cardano.yano.archive.api.projection;

/** How an artifact's durable source is materialised (ADR-039 §5). */
public enum ProjectionArtifactRepresentation {
    /** Immutable staged file written at the transition; freezes its codec at capture time. */
    STAGED_FILE,
    /** Immutable epoch-keyed generation retained in the live store under a pruning lease. */
    IMMUTABLE_GENERATION
}
