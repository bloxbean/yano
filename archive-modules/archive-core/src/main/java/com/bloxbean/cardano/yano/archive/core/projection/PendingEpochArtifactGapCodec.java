package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.time.Instant;

final class PendingEpochArtifactGapCodec {
    private static final int VERSION = 1;

    private PendingEpochArtifactGapCodec() { }

    static byte[] encode(PendingEpochArtifactGap gap) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeInt(VERSION);
                out.writeUTF(gap.dataset().name());
                out.writeInt(gap.semanticEpoch());
                out.writeLong(gap.intendedCarrierBlockNumber());
                out.writeUTF(gap.failureClass());
                out.writeUTF(gap.detail());
                out.writeLong(gap.recordedAt().toEpochMilli());
                out.writeBoolean(gap.pausedContinuation());
            }
            return bytes.toByteArray();
        } catch (Exception e) {
            throw new ProjectionOutboxException("failed to encode pending epoch-artifact gap", e);
        }
    }

    static PendingEpochArtifactGap decode(byte[] encoded) {
        try (var in = new DataInputStream(new ByteArrayInputStream(encoded))) {
            int version = in.readInt();
            if (version != VERSION) {
                throw new IllegalArgumentException("unsupported pending gap version " + version);
            }
            var gap = new PendingEpochArtifactGap(
                    ArchiveDatasetId.valueOf(in.readUTF()), in.readInt(), in.readLong(),
                    in.readUTF(), in.readUTF(), Instant.ofEpochMilli(in.readLong()),
                    in.readBoolean());
            if (in.available() != 0) throw new IllegalArgumentException("trailing pending gap bytes");
            return gap;
        } catch (Exception e) {
            throw new ProjectionOutboxException("failed to decode pending epoch-artifact gap", e);
        }
    }
}
