package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRepresentation;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSectionManifest;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSectionType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.OptionalLong;

/** Deterministic encoding for per-section manifests and artifact references. */
final class ProjectionSectionCodec {
    private static final int MANIFEST_FORMAT = 1;
    private static final int ARTIFACT_FORMAT = 1;

    private ProjectionSectionCodec() {}

    static byte[] encodeManifest(ProjectionSectionManifest manifest) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(MANIFEST_FORMAT);
            out.writeByte(manifest.type().code());
            out.writeInt(manifest.version());
            out.writeInt(manifest.chunkCount());
            out.writeLong(manifest.rowCount());
            out.writeLong(manifest.byteCount());
            out.writeUTF(manifest.digest());
        } catch (IOException e) {
            throw new UncheckedIOException("failed to encode section manifest", e);
        }
        return bytes.toByteArray();
    }

    static ProjectionSectionManifest decodeManifest(byte[] encoded) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded))) {
            int format = in.readUnsignedByte();
            if (format != MANIFEST_FORMAT) {
                throw new IllegalArgumentException("unsupported section manifest format " + format);
            }
            ProjectionSectionType type = ProjectionSectionType.fromCode(in.readUnsignedByte());
            return new ProjectionSectionManifest(type, in.readInt(), in.readInt(), in.readLong(),
                    in.readLong(), in.readUTF());
        } catch (IOException e) {
            throw new UncheckedIOException("failed to decode section manifest", e);
        }
    }

    static byte[] encodeArtifact(ProjectionArtifactRef ref) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(ARTIFACT_FORMAT);
            out.writeUTF(ref.dataset().name());
            out.writeInt(ref.semanticEpoch());
            out.writeLong(ref.producingBlockNumber());
            out.writeLong(ref.producingSlot());
            out.writeUTF(ref.representation().name());
            out.writeUTF(ref.sourceGeneration());
            out.writeInt(ref.sourceCodecVersion());
            out.writeUTF(ref.sourceStateVersion());
            out.writeBoolean(ref.expectedRowCount().isPresent());
            out.writeLong(ref.expectedRowCount().orElse(0L));
            out.writeUTF(ref.contentDigest());
            out.writeLong(ref.oldestRequiredSlot());
        } catch (IOException e) {
            throw new UncheckedIOException("failed to encode artifact reference", e);
        }
        return bytes.toByteArray();
    }

    static ProjectionArtifactRef decodeArtifact(byte[] encoded) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded))) {
            int format = in.readUnsignedByte();
            if (format != ARTIFACT_FORMAT) {
                throw new IllegalArgumentException("unsupported artifact reference format " + format);
            }
            ArchiveDatasetId dataset = ArchiveDatasetId.valueOf(in.readUTF());
            int semanticEpoch = in.readInt();
            long producingBlock = in.readLong();
            long producingSlot = in.readLong();
            ProjectionArtifactRepresentation representation = ProjectionArtifactRepresentation.valueOf(in.readUTF());
            String generation = in.readUTF();
            int codecVersion = in.readInt();
            String stateVersion = in.readUTF();
            boolean hasRowCount = in.readBoolean();
            long rowCount = in.readLong();
            String digest = in.readUTF();
            long oldestRequiredSlot = in.readLong();
            return new ProjectionArtifactRef(dataset, semanticEpoch, producingBlock, producingSlot,
                    representation, generation, codecVersion, stateVersion,
                    hasRowCount ? OptionalLong.of(rowCount) : OptionalLong.empty(), digest, oldestRequiredSlot);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to decode artifact reference", e);
        }
    }
}
