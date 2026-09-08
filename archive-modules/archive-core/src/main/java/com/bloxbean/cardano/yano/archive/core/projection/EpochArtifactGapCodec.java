package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.projection.EpochArtifactGap;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.time.Instant;

final class EpochArtifactGapCodec {
    private static final int VERSION = 2;

    private EpochArtifactGapCodec() { }

    static byte[] encode(EpochArtifactGap gap) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeInt(VERSION);
                out.writeUTF(gap.dataset().name());
                out.writeInt(gap.semanticEpoch());
                out.writeLong(gap.carrierBlockNumber());
                out.writeLong(gap.boundaryBlockNumber());
                out.writeLong(gap.boundarySlot());
                byte[] hash = gap.boundaryBlockHash();
                out.writeInt(hash.length);
                out.write(hash);
                out.writeUTF(gap.failureClass());
                out.writeUTF(gap.detail());
                out.writeLong(gap.recordedAt().toEpochMilli());
            }
            return bytes.toByteArray();
        } catch (Exception e) {
            throw new ProjectionOutboxException("failed to encode epoch-artifact gap", e);
        }
    }

    static EpochArtifactGap decode(byte[] encoded) {
        try (var in = new DataInputStream(new ByteArrayInputStream(encoded))) {
            int version = in.readInt();
            if (version != VERSION) throw new IllegalArgumentException("unsupported gap version " + version);
            ArchiveDatasetId dataset = ArchiveDatasetId.valueOf(in.readUTF());
            int epoch = in.readInt();
            long carrierBlock = in.readLong();
            long block = in.readLong();
            long slot = in.readLong();
            int hashLength = in.readInt();
            if (hashLength <= 0 || hashLength > 128) throw new IllegalArgumentException("invalid gap hash length");
            byte[] hash = in.readNBytes(hashLength);
            if (hash.length != hashLength) throw new IllegalArgumentException("truncated gap hash");
            String failureClass = in.readUTF();
            String detail = in.readUTF();
            Instant recordedAt = Instant.ofEpochMilli(in.readLong());
            if (in.available() != 0) throw new IllegalArgumentException("trailing gap bytes");
            return new EpochArtifactGap(dataset, epoch, carrierBlock, block, slot, hash,
                    failureClass, detail, recordedAt);
        } catch (Exception e) {
            throw new ProjectionOutboxException("failed to decode epoch-artifact gap", e);
        }
    }
}
