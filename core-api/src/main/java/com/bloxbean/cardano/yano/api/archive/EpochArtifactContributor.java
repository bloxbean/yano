package com.bloxbean.cardano.yano.api.archive;

/**
 * Stages an epoch artifact reference while the boundary's own write batch is still open.
 *
 * <p>The reference and the generation it points at therefore commit together, which is the whole
 * safety argument: a reference cannot become durable without its snapshot, and a snapshot cannot
 * be recorded as referenced without the reference existing. Neither half can survive alone.
 *
 * <p>Note what this deliberately does <em>not</em> do: it copies nothing. Epoch stake is projected
 * by referencing the delegation snapshot the boundary already persisted, so the artifact costs one
 * small record rather than a second copy of the whole distribution. What it costs instead is
 * retention - the snapshot must outlive the reference - which is why a contributor that stages a
 * reference must also clamp pruning.
 */
public interface EpochArtifactContributor {

    /** False for a history-disabled node; callers skip all artifact work. */
    boolean enabled();

    /**
     * Stage the epoch-stake artifact reference for a snapshot just written into {@code writer}'s
     * batch.
     *
     * @param epoch              the epoch the snapshot describes
     * @param boundarySlot       slot of the transition that produced it
     * @param boundaryBlockNumber block of that transition, the coordinate finality is judged on
     * @param rowCount           rows the snapshot contains, for receipt accounting
     */
    void contributeEpochStake(int epoch, long boundarySlot, long boundaryBlockNumber,
                              long rowCount, ProjectionStagingWriter writer);

    /**
     * Stage the ADA-pot artifact, carrying its values inline.
     *
     * <p>Unlike epoch stake, the pot is not written through the boundary's batch - it is stored
     * directly, and re-stored as rewards and governance adjust it. There is therefore no batch to
     * be atomic with and no generation to protect, so the evidence travels with the reference
     * instead. It is eight numbers; copying them is cheaper than any protection scheme.
     *
     * @param epoch               the epoch the pot describes
     * @param boundarySlot        slot of the transition that finalised it
     * @param boundaryBlockNumber block of that transition, the coordinate finality is judged on
     * @param values              the pot's columns, in {@code ada_pots} schema order
     */
    void contributeAdaPot(int epoch, long boundarySlot, long boundaryBlockNumber,
                          long[] values, ProjectionStagingWriter writer);

    EpochArtifactContributor NOOP = new EpochArtifactContributor() {
        @Override public boolean enabled() { return false; }
        @Override public void contributeEpochStake(int epoch, long boundarySlot, long boundaryBlockNumber,
                                                   long rowCount, ProjectionStagingWriter writer) { }
        @Override public void contributeAdaPot(int epoch, long boundarySlot, long boundaryBlockNumber,
                                               long[] values, ProjectionStagingWriter writer) { }
    };
}
