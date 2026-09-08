package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRepresentation;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionBlockKind;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionDigest;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionEnvelopeHeader;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSection;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSectionType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectionEnvelopeCodecTest {

    private static final ArchiveNetworkIdentity PREPROD = new ArchiveNetworkIdentity(1, "162d29c4e1cf6b8a");

    private static ProjectionEnvelopeHeader sampleHeader() {
        var tx = new ProjectionSection(ProjectionSectionType.TRANSACTION,
                ProjectionSectionType.TRANSACTION.version(), List.of(new byte[]{1, 2, 3}), 2);
        var utxo = new ProjectionSection(ProjectionSectionType.UTXO_HISTORY,
                ProjectionSectionType.UTXO_HISTORY.version(),
                List.of(new byte[]{4, 5}, new byte[]{6}), 7);
        var artifact = new ProjectionArtifactRef(ArchiveDatasetId.EPOCH_STAKE, 42, 1000, 20000,
                new byte[] {1, 0, 0, 0},
                ProjectionArtifactRepresentation.IMMUTABLE_GENERATION, "epoch-deleg-snapshot/42", 1,
                "account-state-v1", OptionalLong.of(1_300_000L), "abcdef01", 19000);
        return new ProjectionEnvelopeHeader(PREPROD, ProjectionBlockKind.SHELLEY_PLUS, 1000,
                new byte[]{10, 20, 30}, new byte[]{9, 19, 29}, 20000, 42, 1_700_000_000L, 1,
                List.of(tx.manifest(), utxo.manifest()), List.of(artifact));
    }

    @Test
    void headerRoundTripsExactly() {
        var header = sampleHeader();
        var decoded = ProjectionEnvelopeCodec.decodeHeader(ProjectionEnvelopeCodec.encodeHeader(header));
        assertThat(decoded).isEqualTo(header);
        assertThat(decoded.envelopeId()).isEqualTo(header.envelopeId());
        assertThat(decoded.artifacts()).isEqualTo(header.artifacts());
    }

    @Test
    void encodingIsStableAcrossCalls() {
        assertThat(ProjectionEnvelopeCodec.encodeHeader(sampleHeader()))
                .isEqualTo(ProjectionEnvelopeCodec.encodeHeader(sampleHeader()));
    }

    /**
     * Golden fixture. A change to this digest means the persisted outbox format changed;
     * that requires a format-version bump and a migration decision, not a test update.
     *
     * <p>Last changed for v3, which added the producing anchor hash to artifact references.
     * The migration decision is recorded on {@code ProjectionEnvelopeCodec.FORMAT_VERSION}:
     * none is offered because projection history is still preview-only and fresh-sync-only.
     *
     * <p>The digest also moved when every dataset was renumbered to v1 before release. That is
     * not a structural change - the header still writes the same fields in the same order - but
     * the encoding embeds each section's wire name, so {@code transaction:v2} became
     * {@code transaction:v1} in the bytes. An outbox written before the renumber therefore fails
     * to decode, which is the same consequence as a format bump and carries the same decision:
     * no migration, because nothing was released and ADR-039 archives are fresh-sync-only.
     */
    @Test
    void encodedHeaderMatchesItsGoldenDigest() {
        String digest = ProjectionDigest.ofChunks(List.of(ProjectionEnvelopeCodec.encodeHeader(sampleHeader())));
        assertThat(digest)
                .as("projection envelope header wire format v%d", ProjectionEnvelopeCodec.FORMAT_VERSION)
                .isEqualTo("87c4e2a644ef5af545be19ff0ff999926655db12106c0af22cb47a7712d6c9fd");
    }

    @Test
    void emptyEbbHeaderRoundTrips() {
        var ebb = new ProjectionEnvelopeHeader(PREPROD, ProjectionBlockKind.BYRON_EBB, 500,
                new byte[]{5, 0}, new byte[]{4, 9}, 10000, 21, 1_600_000_000L, 1, List.of(), List.of());
        var decoded = ProjectionEnvelopeCodec.decodeHeader(ProjectionEnvelopeCodec.encodeHeader(ebb));
        assertThat(decoded).isEqualTo(ebb);
        assertThat(decoded.sections()).isEmpty();
        assertThat(decoded.blockKind()).isEqualTo(ProjectionBlockKind.BYRON_EBB);
    }

    @Test
    void byronMainHeaderRoundTrips() {
        var tx = new ProjectionSection(ProjectionSectionType.TRANSACTION,
                ProjectionSectionType.TRANSACTION.version(), List.of(new byte[]{7}), 1);
        var byron = new ProjectionEnvelopeHeader(PREPROD, ProjectionBlockKind.BYRON_MAIN, 501,
                new byte[]{5, 1}, new byte[]{5, 0}, 10020, 21, 1_600_000_020L, 1,
                List.of(tx.manifest()), List.of());
        assertThat(ProjectionEnvelopeCodec.decodeHeader(ProjectionEnvelopeCodec.encodeHeader(byron)))
                .isEqualTo(byron);
    }

    // --- malformed input fails closed -------------------------------------------

    @Test
    void aForeignRecordIsRejected() {
        assertThatThrownBy(() -> ProjectionEnvelopeCodec.decodeHeader(new byte[]{'X', 'X', 'X', 'X', 1}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a projection envelope header");
    }

    @Test
    void anUnsupportedFormatVersionIsRejected() {
        byte[] encoded = ProjectionEnvelopeCodec.encodeHeader(sampleHeader());
        encoded[4] = 99;
        assertThatThrownBy(() -> ProjectionEnvelopeCodec.decodeHeader(encoded))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported envelope header format version");
    }

    @Test
    void aTruncatedRecordIsRejected() {
        byte[] encoded = ProjectionEnvelopeCodec.encodeHeader(sampleHeader());
        byte[] truncated = Arrays.copyOf(encoded, encoded.length - 5);
        assertThatThrownBy(() -> ProjectionEnvelopeCodec.decodeHeader(truncated))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void trailingBytesAreRejected() {
        byte[] encoded = ProjectionEnvelopeCodec.encodeHeader(sampleHeader());
        byte[] extended = Arrays.copyOf(encoded, encoded.length + 3);
        assertThatThrownBy(() -> ProjectionEnvelopeCodec.decodeHeader(extended))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trailing bytes");
    }

    @Test
    void anUnknownSectionWireNameIsRejectedRatherThanSkipped() {
        var header = sampleHeader();
        byte[] encoded = ProjectionEnvelopeCodec.encodeHeader(header);
        String from = ProjectionSectionType.TRANSACTION.wireName();
        String to = "transaction:v3";
        byte[] patched = replaceUtf(encoded, from, to);
        assertThatThrownBy(() -> ProjectionEnvelopeCodec.decodeHeader(patched))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown projection section");
    }

    /** Replaces a same-length modified UTF string body in an encoded record. */
    private static byte[] replaceUtf(byte[] encoded, String from, String to) {
        byte[] fromBytes = from.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] toBytes = to.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (fromBytes.length != toBytes.length) throw new IllegalArgumentException("test helper needs equal lengths");
        byte[] copy = encoded.clone();
        outer:
        for (int i = 0; i + fromBytes.length <= copy.length; i++) {
            for (int j = 0; j < fromBytes.length; j++) {
                if (copy[i + j] != fromBytes[j]) continue outer;
            }
            System.arraycopy(toBytes, 0, copy, i, toBytes.length);
            return copy;
        }
        throw new IllegalStateException("pattern not found");
    }
}
