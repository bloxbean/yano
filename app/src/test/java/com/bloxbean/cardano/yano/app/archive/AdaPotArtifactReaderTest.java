package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveRowCodec;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRef;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionArtifactRepresentation;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveSchemas;
import com.bloxbean.cardano.yano.archive.core.projection.AdaPotArtifactRows;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ada pot travels as inline evidence, so this reader's contract is that it needs no source:
 * it must produce the archive row from the reference alone, and protect nothing.
 */
class AdaPotArtifactReaderTest {

    private static final long[] VALUES = {10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L};

    private static ProjectionArtifactRef ref(int epoch) {
        return new ProjectionArtifactRef(ArchiveDatasetId.ADA_POT, epoch, 4_800_000L, 100_000L,
                new byte[] {5, 5},
                ProjectionArtifactRepresentation.ATOMIC_EVIDENCE, "ada-pot:" + epoch, 1,
                "ledger-boundary-v1/final", OptionalLong.of(1), "", -1L,
                AdaPotArtifactRows.encode(VALUES));
    }

    private static final ArtifactBoundaryFacts FACTS = new ArtifactBoundaryFacts() {
        @Override public Optional<byte[]> blockHash(long blockNumber) {
            return Optional.of(new byte[]{5, 5});
        }
        @Override public long blockTimeSeconds(long slot) { return 1_600_000_000L; }
    };

    @Test
    void theRowIsBuiltFromTheReferenceAloneWithNoStoreAccess() {
        var reader = new AdaPotArtifactReader(1, FACTS);
        var artifact = ref(250);

        try (var lease = reader.acquire(artifact, Instant.now().plusSeconds(60))) {
            var page = reader.read(artifact, lease, Optional.empty(), 100);

            assertThat(page.rows()).hasSize(1);
            assertThat(page.hasMore()).isFalse();
            var row = ArchiveRowCodec.decode(page.rows().get(0));
            assertThat(row.table()).isEqualTo("ada_pots");
            assertThat(row.values()).hasSize(AdaPotArtifactRows.columns().size());
            assertThat(row.values().get(0)).isEqualTo(250L);
            assertThat(row.values().get(1)).isEqualTo(10L);
            assertThat(row.values().get(8)).isEqualTo(80L);
        }
    }

    @Test
    void theRowShapeMatchesTheShippedSchema() {
        var columns = ArchiveSchemas.schema(ArchiveDatasetId.ADA_POT).tables().stream()
                .filter(table -> table.physicalName().equals("ada_pots"))
                .findFirst().orElseThrow()
                .columns().stream().map(column -> column.name()).toList();

        assertThat(AdaPotArtifactRows.columns()).isEqualTo(columns);
    }

    @Test
    void aMissingBoundaryReferenceFailsRatherThanWritingANullHash() {
        var reader = new AdaPotArtifactReader(1, new ArtifactBoundaryFacts() {
            @Override public Optional<byte[]> blockHash(long blockNumber) { return Optional.empty(); }
            @Override public long blockTimeSeconds(long slot) { return 0; }
        });
        var artifact = ref(250);

        try (var lease = reader.acquire(artifact, Instant.now().plusSeconds(60))) {
            assertThatThrownBy(() -> reader.read(artifact, lease, Optional.empty(), 100))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no canonical block reference");
        }
    }

    @Test
    void aDifferentCanonicalHashRefusesEvidenceFromTheRolledBackAnchor() {
        var reader = new AdaPotArtifactReader(1, new ArtifactBoundaryFacts() {
            @Override public Optional<byte[]> blockHash(long blockNumber) {
                return Optional.of(new byte[]{9, 9});
            }
            @Override public long blockTimeSeconds(long slot) { return 0; }
        });
        var artifact = ref(250);

        try (var lease = reader.acquire(artifact, Instant.now().plusSeconds(60))) {
            assertThatThrownBy(() -> reader.read(artifact, lease, Optional.empty(), 100))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("anchor is no longer canonical");
        }
    }

    @Test
    void theRouterSendsEachDatasetToItsOwnReaderAndRefusesUnknownOnes() {
        // Each dataset has its own retention story, so serving one with another's reader would
        // silently apply the wrong contract.
        var router = new RoutingArtifactReader(Map.of(
                ArchiveDatasetId.ADA_POT, new AdaPotArtifactReader(1, FACTS)));

        try (var lease = router.acquire(ref(250), Instant.now().plusSeconds(60))) {
            assertThat(router.read(ref(250), lease, Optional.empty(), 10).rows()).hasSize(1);
        }

        var unrouted = new ProjectionArtifactRef(ArchiveDatasetId.EPOCH_STAKE, 250, 1, 1, new byte[] {1},
                ProjectionArtifactRepresentation.IMMUTABLE_GENERATION, "g", 1, "s",
                OptionalLong.of(1), "", 1);
        assertThatThrownBy(() -> router.acknowledge(unrouted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no artifact reader is installed");
    }
}
