package com.bloxbean.cardano.yano.api.archive;

/**
 * Holds epoch snapshots back from pruning while an archive still references them.
 *
 * <p>ADR-039 projects epoch stake by referencing the delegation snapshot the boundary already
 * persisted, rather than copying it into the envelope. That is only safe if the snapshot outlives
 * the reference: normal retention would otherwise delete a generation an unacknowledged artifact
 * still points at.
 *
 * <p>The clamp is a floor, not a hold-everything switch. It moves forward as the sink acknowledges,
 * so a healthy node prunes on its usual schedule and only a lagging sink extends retention.
 */
public interface SnapshotRetentionClamp {

    /**
     * Protect snapshots for {@code epoch} and later from pruning.
     *
     * @param epoch the oldest epoch an archive still references, or {@code -1} to release
     */
    void protectSnapshotsFrom(int epoch);

    /** The current floor, or {@code -1} when nothing is protected. */
    int protectedSnapshotFloorEpoch();

    /** No protection; pruning follows its configured retention alone. */
    SnapshotRetentionClamp NONE = new SnapshotRetentionClamp() {
        @Override public void protectSnapshotsFrom(int epoch) { }
        @Override public int protectedSnapshotFloorEpoch() { return -1; }
    };
}
