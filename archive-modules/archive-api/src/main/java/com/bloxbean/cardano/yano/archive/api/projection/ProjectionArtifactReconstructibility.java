package com.bloxbean.cardano.yano.archive.api.projection;

/**
 * Whether an epoch artifact can be produced again later, or exists only if it was captured.
 *
 * <p>This is the property that decides whether an archive missing an artifact can be completed or
 * must be rebuilt, so it is recorded in the artifact contract rather than inferred. The
 * distinction is not "is it expensive to recompute" but "are the inputs still there".
 */
public enum ProjectionArtifactReconstructibility {

    /**
     * Derivable again from data the node retains <em>in kind</em>. Epoch stake qualifies: it is a
     * function of the UTXO set and delegation state at the boundary, and that snapshot is
     * persisted. Ada pots qualify for a simpler reason - the computed pot for each epoch is
     * written under its own key and never deleted, so the value itself is still there.
     *
     * <p><strong>Being in this class does not license adding the artifact to an existing
     * archive.</strong> It says the kind is derivable, not that this node still has the sources:
     * epoch stake's generations are pruned unless a lease protected them since genesis. Coverage
     * is checked separately, through {@link ProjectionArtifactCoverage}.
     */
    RECONSTRUCTIBLE,

    /**
     * Derivable only from inputs the boundary itself held, which the node does not retain
     * indefinitely - capturable cheaply at the time, unrecoverable afterwards.
     *
     * <p>No shipped dataset is currently in this class. Ada pots were placed here on the
     * reasoning that they are the transition's <em>output</em>, so recomputing would need the
     * pre-transition pots plus that epoch's fees; checking the source showed that reasoning
     * irrelevant, because the computed value is persisted per epoch and never pruned. The class
     * is kept for artifacts that genuinely fit it.
     */
    BOUNDARY_INPUTS_ONLY,

    /**
     * Not derivable at all once the boundary has passed - either because the calculation's own
     * version is part of the answer, or because the value is an observation rather than a
     * function of retained state.
     *
     * <p>Rewards are the first kind: the result depends on the reward calculation's version as
     * well as its inputs, and neither closure is proven. Governance proposal status is the second:
     * {@code observationPhase}, {@code statusCode} and {@code decisionReason} are decisions taken
     * <em>at</em> a boundary, and later state records the outcome rather than the observation that
     * produced it.
     *
     * <p>An archive that did not capture one of these can never be completed for that epoch. It
     * must say so rather than return an empty result.
     */
    IRREPRODUCIBLE
}
