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

    enum Dataset { EPOCH_STAKE, ADA_POT }

    /** False for a history-disabled node; callers skip all artifact work. */
    boolean enabled();

    /**
     * Stage the epoch-stake artifact reference for a snapshot just written into {@code writer}'s
     * batch.
     *
     * @param epoch              the epoch the snapshot describes
     * @param anchorSlot          slot of the last block of the source epoch
     * @param anchorBlockNumber   last block of the source epoch
     * @param anchorBlockHash     hash of the last block of the source epoch
     * @param carrierBlockNumber  first applying block of the new epoch; finality is judged on it
     * @param rowCount           rows the snapshot contains, for receipt accounting
     */
    void contributeEpochStake(int epoch, long anchorSlot, long anchorBlockNumber,
                              byte[] anchorBlockHash, long carrierBlockNumber,
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
     * @param anchorSlot          slot of the last block of the source epoch
     * @param anchorBlockNumber   last block of the source epoch
     * @param anchorBlockHash     hash of the last block of the source epoch
     * @param carrierBlockNumber  first applying block of the new epoch; finality is judged on it
     * @param values              the pot's columns, in {@code ada_pots} schema order
     */
    void contributeAdaPot(int epoch, long anchorSlot, long anchorBlockNumber,
                          byte[] anchorBlockHash, long carrierBlockNumber,
                          long[] values, ProjectionStagingWriter writer);

    /** Report an archive-only capture failure after the ledger has chosen to continue. */
    default void captureFailed(Dataset dataset, int epoch, long carrierBlockNumber,
                               RuntimeException failure) { }

    EpochArtifactContributor NOOP = new EpochArtifactContributor() {
        @Override public boolean enabled() { return false; }
        @Override public void contributeEpochStake(int epoch, long anchorSlot, long anchorBlockNumber,
                                                   byte[] anchorBlockHash, long carrierBlockNumber,
                                                   long rowCount, ProjectionStagingWriter writer) { }
        @Override public void contributeAdaPot(int epoch, long anchorSlot, long anchorBlockNumber,
                                               byte[] anchorBlockHash, long carrierBlockNumber,
                                               long[] values, ProjectionStagingWriter writer) { }
    };
}
