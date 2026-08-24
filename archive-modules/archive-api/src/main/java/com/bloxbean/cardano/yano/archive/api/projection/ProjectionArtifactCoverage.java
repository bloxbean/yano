package com.bloxbean.cardano.yano.archive.api.projection;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;

/**
 * Whether the node still holds the sources needed to produce an artifact for a past epoch range.
 *
 * <p>Reconstructibility is a statement about <em>kind</em> - epoch stake is a function of retained
 * state, rewards are not. It is not a statement about <em>this node, today</em>. Epoch stake's
 * delegation generations are pruned on a normal node unless an archive lease has protected them
 * since genesis, so "reconstructible" and "actually reconstructable here" are different questions
 * and only the second one licenses adding an artifact to an existing archive.
 *
 * <p>The default answers no. Being wrong in that direction costs a rebuild that might not have
 * been necessary; being wrong the other way produces an archive that claims epochs it can never
 * fill, which is discovered only when someone asks for one.
 */
@FunctionalInterface
public interface ProjectionArtifactCoverage {

    /**
     * @param dataset     the artifact's dataset
     * @param fromEpoch   first epoch that would need backfilling, inclusive
     * @param throughEpoch last epoch that would need backfilling, inclusive
     * @return true only if every source needed for every epoch in the range is still retained
     */
    boolean covers(ArchiveDatasetId dataset, int fromEpoch, int throughEpoch);

    /** Nothing is retained; every addition requires a rebuild. The safe default. */
    ProjectionArtifactCoverage NONE = (dataset, fromEpoch, throughEpoch) -> false;

    /** Everything is retained. For tests and for a genesis-lease deployment that guarantees it. */
    ProjectionArtifactCoverage COMPLETE = (dataset, fromEpoch, throughEpoch) -> true;
}
