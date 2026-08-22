package com.bloxbean.cardano.yano.archive.api.projection;

/** How an artifact's durable source is materialised (ADR-039 §5). */
public enum ProjectionArtifactRepresentation {
    /** Immutable staged file written at the transition; freezes its codec at capture time. */
    STAGED_FILE,
    /** Immutable epoch-keyed generation retained in the live store under a pruning lease. */
    IMMUTABLE_GENERATION,

    /**
     * Small boundary facts carried inline on the reference itself, with no staged file and no
     * retained generation behind them, committed atomically with the state they describe.
     *
     * <p>This is a statement about <em>materialisation</em>, not about reproducibility - the two
     * are independent axes. It is the right choice whenever there is no durable source to point
     * at, for either of two quite different reasons:
     *
     * <ul>
     *   <li><strong>Nothing to reference later.</strong> Governance proposal status is the clearest
     *       case: {@code observationPhase}, {@code statusCode} and {@code decisionReason} are
     *       decisions taken <em>at</em> a boundary, and later state records the outcome rather than
     *       the observation. Those are also IRREPRODUCIBLE.</li>
     *   <li><strong>A source that exists but shares no batch.</strong> The ada pot is persisted per
     *       epoch and never pruned, so it is RECONSTRUCTIBLE - but it is stored directly rather
     *       than through the boundary's batch, and re-stored as rewards and governance adjust it.
     *       There is no batch to join and no generation to protect, so its eight values travel
     *       inline.</li>
     * </ul>
     *
     * <p>Only appropriate while the payload is small enough to sit inside a block batch. Anything
     * large belongs in a staged file, whose durability is independent of the batch.
     */
    ATOMIC_EVIDENCE
}
