package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRepresentation;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionBlockKind;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionEnvelopeHeader;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSectionManifest;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSectionType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * Deterministic encoding of an envelope header.
 *
 * <p>Encoding is stable and byte-exact for a given header so that golden fixtures can
 * detect an accidental format change. Enums are written by their stable wire name or
 * an explicit code rather than by {@code ordinal()}, so reordering a Java enum cannot
 * silently reinterpret already-persisted outbox records.
 *
 * <p>Section chunk payloads are stored as opaque bytes under their own keys and are
 * not part of this encoding; the header's manifest is what binds them together.
 */
public final class ProjectionEnvelopeCodec {
    private static final byte[] MAGIC = {'Y', 'P', 'E', 'H'};
    static final int FORMAT_VERSION = 1;

    private ProjectionEnvelopeCodec() {}

    public static byte[] encodeHeader(ProjectionEnvelopeHeader header) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.write(MAGIC);
            out.writeByte(FORMAT_VERSION);
            out.writeInt(header.networkIdentity().networkMagic());
            out.writeUTF(header.networkIdentity().genesisHash());
            out.writeUTF(header.blockKind().name());
            out.writeLong(header.blockNumber());
            out.writeLong(header.slot());
            out.writeInt(header.epoch());
            out.writeLong(header.blockTime());
            out.writeInt(header.canonicalProjectionVersion());
            writeBytes(out, header.blockHash());
            writeBytes(out, header.parentHash());

            out.writeInt(header.sections().size());
            for (ProjectionSectionManifest section : header.sections()) {
                out.writeUTF(section.type().wireName());
                out.writeInt(section.version());
                out.writeInt(section.chunkCount());
                out.writeLong(section.rowCount());
                out.writeLong(section.byteCount());
                out.writeUTF(section.digest());
            }

            out.writeInt(header.artifacts().size());
            for (ProjectionArtifactRef artifact : header.artifacts()) {
                out.writeUTF(artifact.dataset().name());
                out.writeInt(artifact.semanticEpoch());
                out.writeLong(artifact.producingBlockNumber());
                out.writeLong(artifact.producingSlot());
                out.writeUTF(artifact.representation().name());
                out.writeUTF(artifact.sourceGeneration());
                out.writeInt(artifact.sourceCodecVersion());
                out.writeUTF(artifact.sourceStateVersion());
                out.writeBoolean(artifact.expectedRowCount().isPresent());
                out.writeLong(artifact.expectedRowCount().orElse(0L));
                out.writeUTF(artifact.contentDigest());
                out.writeLong(artifact.oldestRequiredSlot());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to encode projection envelope header", e);
        }
        return bytes.toByteArray();
    }

    public static ProjectionEnvelopeHeader decodeHeader(byte[] encoded) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded))) {
            byte[] magic = in.readNBytes(MAGIC.length);
            if (!java.util.Arrays.equals(magic, MAGIC)) {
                throw new IllegalArgumentException("not a projection envelope header record");
            }
            int format = in.readUnsignedByte();
            if (format != FORMAT_VERSION) {
                throw new IllegalArgumentException("unsupported envelope header format version " + format);
            }
            int networkMagic = in.readInt();
            String genesisHash = in.readUTF();
            ProjectionBlockKind blockKind = ProjectionBlockKind.valueOf(in.readUTF());
            long blockNumber = in.readLong();
            long slot = in.readLong();
            int epoch = in.readInt();
            long blockTime = in.readLong();
            int projectionVersion = in.readInt();
            byte[] blockHash = readBytes(in);
            byte[] parentHash = readBytes(in);

            int sectionCount = in.readInt();
            List<ProjectionSectionManifest> sections = new ArrayList<>(Math.max(0, sectionCount));
            for (int i = 0; i < sectionCount; i++) {
                ProjectionSectionType type = ProjectionSectionType.fromWireName(in.readUTF());
                sections.add(new ProjectionSectionManifest(type, in.readInt(), in.readInt(),
                        in.readLong(), in.readLong(), in.readUTF()));
            }

            int artifactCount = in.readInt();
            List<ProjectionArtifactRef> artifacts = new ArrayList<>(Math.max(0, artifactCount));
            for (int i = 0; i < artifactCount; i++) {
                ArchiveDatasetId dataset = ArchiveDatasetId.valueOf(in.readUTF());
                int semanticEpoch = in.readInt();
                long producingBlock = in.readLong();
                long producingSlot = in.readLong();
                ProjectionArtifactRepresentation representation =
                        ProjectionArtifactRepresentation.valueOf(in.readUTF());
                String generation = in.readUTF();
                int codecVersion = in.readInt();
                String stateVersion = in.readUTF();
                boolean hasRowCount = in.readBoolean();
                long rowCount = in.readLong();
                String digest = in.readUTF();
                long oldestRequiredSlot = in.readLong();
                artifacts.add(new ProjectionArtifactRef(dataset, semanticEpoch, producingBlock, producingSlot,
                        representation, generation, codecVersion, stateVersion,
                        hasRowCount ? OptionalLong.of(rowCount) : OptionalLong.empty(), digest, oldestRequiredSlot));
            }

            if (in.available() != 0) {
                throw new IllegalArgumentException("trailing bytes after projection envelope header");
            }
            return new ProjectionEnvelopeHeader(new ArchiveNetworkIdentity(networkMagic, genesisHash), blockKind,
                    blockNumber, blockHash, parentHash, slot, epoch, blockTime, projectionVersion,
                    sections, artifacts);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to decode projection envelope header", e);
        }
    }

    private static void writeBytes(DataOutputStream out, byte[] value) throws IOException {
        out.writeInt(value.length);
        out.write(value);
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > 1 << 20) throw new IllegalArgumentException("implausible byte length " + length);
        return in.readNBytes(length);
    }
}
