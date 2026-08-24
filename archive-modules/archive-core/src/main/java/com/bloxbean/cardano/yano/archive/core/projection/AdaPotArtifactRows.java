package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * The {@code ada_pots} row, and the inline evidence form it travels in.
 *
 * <p>Column order matches {@code StandardEpochDatasets.adaPot} exactly, because the projection and
 * the replay worker must produce the same archive.
 */
public final class AdaPotArtifactRows {

    /** treasury, reserves, deposits, fees, distributed, undistributed, rewardsPot, poolRewardsPot. */
    public static final int VALUE_COUNT = 8;

    private AdaPotArtifactRows() {}

    public static byte[] encode(long[] values) {
        if (values.length != VALUE_COUNT) {
            throw new IllegalArgumentException("ada pot evidence must carry exactly "
                    + VALUE_COUNT + " values, got " + values.length);
        }
        ByteBuffer out = ByteBuffer.allocate(VALUE_COUNT * Long.BYTES);
        for (long value : values) out.putLong(value);
        return out.array();
    }

    public static long[] decode(byte[] payload) {
        if (payload.length != VALUE_COUNT * Long.BYTES) {
            throw new IllegalArgumentException("malformed ada pot evidence: " + payload.length + " bytes");
        }
        ByteBuffer in = ByteBuffer.wrap(payload);
        long[] values = new long[VALUE_COUNT];
        for (int i = 0; i < VALUE_COUNT; i++) values[i] = in.getLong();
        return values;
    }

    public static ArchiveRow row(ProjectionArtifactRef ref, long[] values, byte[] boundaryBlockHash,
                                 long boundaryBlockTimeSeconds, UUID jobId) {
        long[] v = values.length == VALUE_COUNT ? values : decode(ref.inlinePayload());
        return new ArchiveRow("ada_pots", Arrays.asList(
                (long) ref.semanticEpoch(), v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7],
                boundaryBlockHash, ref.producingBlockNumber(), ref.producingSlot(),
                boundaryBlockTimeSeconds, ref.sourceStateVersion(), jobId));
    }

    /** Deterministic job identity, so replay reproduces the same {@code archive_job_id}. */
    public static UUID jobId(ProjectionArtifactRef ref) {
        return UUID.nameUUIDFromBytes(("ada-pot:" + ref.semanticEpoch() + ':'
                + ref.sourceCodecVersion()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Column names, kept beside the row builder so a schema change breaks both together. */
    public static List<String> columns() {
        return List.of("epoch", "treasury", "reserves", "deposits", "fees", "distributed",
                "undistributed", "rewards_pot", "pool_rewards_pot", "boundary_block_hash",
                "boundary_block_number", "boundary_slot", "boundary_block_time",
                "source_state_version", "archive_job_id");
    }
}
