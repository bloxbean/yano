package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef;
import com.bloxbean.cardano.yano.archive.core.address.StakeAddressCodec;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Turns one delegator's snapshot entry into an {@code epoch_stakes} row.
 *
 * <p>Column order and content match {@code StandardEpochDatasets.epochStake} exactly, because both
 * paths must produce the same archive - the projection reads the snapshot the boundary wrote,
 * while the replay worker reads the same snapshot through a different pipeline. Sharing the row
 * shape is what makes them comparable at all.
 */
public final class EpochStakeArtifactRows {

    private EpochStakeArtifactRows() {}

    /** {@code 0} is a key hash and {@code 1} a script hash, as the account store encodes them. */
    public static String credentialType(int code) {
        return code == 1 ? "script" : "key";
    }

    public static ArchiveRow row(ProjectionArtifactRef ref, long networkMagic, int credentialTypeCode,
                                 byte[] credentialHash, String poolHash, BigInteger amount,
                                 byte[] boundaryBlockHash, long boundaryBlockTimeSeconds,
                                 UUID jobId) {
        String type = credentialType(credentialTypeCode);
        return new ArchiveRow("epoch_stakes", Arrays.asList(
                ref.semanticEpoch(), type, credentialHash,
                StakeAddressCodec.encode(networkMagic, type, credentialHash),
                poolHash == null ? null : com.bloxbean.cardano.yaci.core.util.HexUtil.decodeHexString(poolHash),
                amount == null ? null : amount.longValueExact(),
                boundaryBlockHash, ref.producingBlockNumber(), ref.producingSlot(),
                boundaryBlockTimeSeconds, ref.sourceStateVersion(), jobId));
    }

    /** Deterministic job identity, so replay reproduces the same {@code archive_job_id}. */
    public static UUID jobId(ProjectionArtifactRef ref) {
        return UUID.nameUUIDFromBytes(("epoch-stake:" + ref.semanticEpoch() + ':'
                + ref.sourceGeneration() + ':' + ref.sourceCodecVersion())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Rows an artifact is expected to produce, for receipt accounting without a second pass. */
    public static long expectedRows(ProjectionArtifactRef ref) {
        return ref.expectedRowCount().orElse(-1);
    }

    /** Column names, kept beside the row builder so a schema change breaks both together. */
    public static List<String> columns() {
        return List.of("epoch", "stake_credential_type", "stake_credential", "stake_address", "pool_hash",
                "amount", "boundary_block_hash", "boundary_block_number", "boundary_slot",
                "boundary_block_time", "source_state_version", "archive_job_id");
    }
}
