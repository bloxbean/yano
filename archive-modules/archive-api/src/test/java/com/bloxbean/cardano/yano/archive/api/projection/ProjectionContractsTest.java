package com.bloxbean.cardano.yano.archive.api.projection;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.api.schema.ArchiveSchemas;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectionContractsTest {

    static final ArchiveNetworkIdentity PREPROD = new ArchiveNetworkIdentity(1, "162d29c4e1cf6b8a");

    static ProjectionSection section(ProjectionSectionType type, long rows, byte[]... chunks) {
        return new ProjectionSection(type, type.version(), List.of(chunks), rows);
    }

    static ProjectionEnvelopeHeader header(long blockNumber, List<ProjectionSection> sections) {
        return new ProjectionEnvelopeHeader(PREPROD, ProjectionBlockKind.SHELLEY_PLUS, blockNumber,
                new byte[]{(byte) blockNumber, 1, 2}, new byte[]{(byte) (blockNumber - 1), 1, 2},
                blockNumber * 20, 5, 1_600_000_000L + blockNumber, 1,
                sections.stream().map(ProjectionSection::manifest).toList(), List.of());
    }

    static ProjectionEnvelope envelope(long blockNumber, List<ProjectionSection> sections) {
        return new ProjectionEnvelope(header(blockNumber, sections), sections);
    }

    // --- section identity is bound to shipped dataset identity -------------------

    @Test
    void sectionVersionsTrackTheShippedDatasetProjectionVersion() {
        assertThat(ProjectionSectionType.TRANSACTION.wireName()).isEqualTo("transaction:v1");
        assertThat(ProjectionSectionType.UTXO_HISTORY.wireName()).isEqualTo("utxo-history:v1");
        assertThat(ProjectionSectionType.ACCOUNT_EVENT.wireName()).isEqualTo("account-events:v1");
        assertThat(ProjectionSectionType.ADDRESS_TRANSACTION.wireName()).isEqualTo("address-transaction:v1");

        for (ProjectionSectionType type : ProjectionSectionType.values()) {
            assertThat(type.version())
                    .isEqualTo(ArchiveSchemas.schema(type.dataset()).projectionVersion());
            assertThat(ProjectionSectionType.fromWireName(type.wireName())).isEqualTo(type);
        }
    }

    @Test
    void everyShippedBlockDatasetHasExactlyOneSection() {
        Set<ArchiveDatasetId> covered = Set.of(
                ProjectionSectionType.TRANSACTION.dataset(), ProjectionSectionType.UTXO_HISTORY.dataset(),
                ProjectionSectionType.ACCOUNT_EVENT.dataset(), ProjectionSectionType.ADDRESS_TRANSACTION.dataset());
        Set<ArchiveDatasetId> shippedBlockDatasets = java.util.Arrays.stream(ArchiveDatasetId.values())
                .filter(d -> d.sourceKind() == com.bloxbean.cardano.yano.archive.api.SourceKind.BLOCK)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        assertThat(covered).isEqualTo(shippedBlockDatasets);
    }

    @Test
    void datumAndRedeemerTablesRemainUnderUtxoHistory() {
        // Guards ADR-039 review D2: transport grouping must not move these to TRANSACTION.
        assertThat(ArchiveSchemas.schema(ArchiveDatasetId.UTXO_HISTORY).tables())
                .extracting(com.bloxbean.cardano.yano.archive.api.schema.ArchiveTableSchema::physicalName)
                .contains("transaction_datums", "transaction_redeemers");
        assertThat(ArchiveSchemas.schema(ArchiveDatasetId.TRANSACTION).tables())
                .extracting(com.bloxbean.cardano.yano.archive.api.schema.ArchiveTableSchema::physicalName)
                .containsExactly("chain_transaction");
    }

    @Test
    void unknownSectionWireNameIsRejected() {
        assertThatThrownBy(() -> ProjectionSectionType.fromWireName("transaction:v99"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown projection section");
    }

    // --- envelope identity ------------------------------------------------------

    @Test
    void envelopeIdIsDeterministicForTheSameCanonicalBlock() {
        var a = header(100, List.of(section(ProjectionSectionType.TRANSACTION, 2, new byte[]{1, 2, 3})));
        var b = header(100, List.of(section(ProjectionSectionType.TRANSACTION, 2, new byte[]{1, 2, 3})));
        assertThat(a.envelopeId()).isEqualTo(b.envelopeId()).hasSize(64);
    }

    @Test
    void aReplacementBlockAtTheSameHeightGetsADifferentEnvelopeId() {
        var original = header(100, List.of());
        var replacement = new ProjectionEnvelopeHeader(PREPROD, ProjectionBlockKind.SHELLEY_PLUS, 100,
                new byte[]{9, 9, 9}, original.parentHash(), original.slot(), original.epoch(),
                original.blockTime(), 1, List.of(), List.of());
        assertThat(replacement.envelopeId()).isNotEqualTo(original.envelopeId());
    }

    @Test
    void envelopeIdChangesWithProjectionVersionAndNetwork() {
        var base = header(100, List.of());
        var otherVersion = new ProjectionEnvelopeHeader(PREPROD, ProjectionBlockKind.SHELLEY_PLUS, 100,
                base.blockHash(), base.parentHash(), base.slot(), base.epoch(), base.blockTime(), 2,
                List.of(), List.of());
        var otherNetwork = new ProjectionEnvelopeHeader(new ArchiveNetworkIdentity(764824073, "abcdef"),
                ProjectionBlockKind.SHELLEY_PLUS, 100, base.blockHash(), base.parentHash(),
                base.slot(), base.epoch(), base.blockTime(), 1, List.of(), List.of());
        assertThat(otherVersion.envelopeId()).isNotEqualTo(base.envelopeId());
        assertThat(otherNetwork.envelopeId()).isNotEqualTo(base.envelopeId());
    }

    // --- completeness is proven at construction ---------------------------------

    @Test
    void anEnvelopeMissingAManifestedSectionIsRejected() {
        var tx = section(ProjectionSectionType.TRANSACTION, 1, new byte[]{7});
        var utxo = section(ProjectionSectionType.UTXO_HISTORY, 3, new byte[]{8});
        var head = header(100, List.of(tx, utxo));
        assertThatThrownBy(() -> new ProjectionEnvelope(head, List.of(tx)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTamperedSectionPayloadIsRejectedAgainstItsManifest() {
        var declared = section(ProjectionSectionType.TRANSACTION, 1, new byte[]{1, 2, 3});
        var head = header(100, List.of(declared));
        var tampered = section(ProjectionSectionType.TRANSACTION, 1, new byte[]{1, 2, 4});
        assertThatThrownBy(() -> new ProjectionEnvelope(head, List.of(tampered)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match its manifest");
    }

    @Test
    void aWrongRowCountIsRejectedEvenWhenBytesMatch() {
        var declared = section(ProjectionSectionType.TRANSACTION, 5, new byte[]{1, 2, 3});
        var head = header(100, List.of(declared));
        var wrongRows = section(ProjectionSectionType.TRANSACTION, 6, new byte[]{1, 2, 3});
        assertThatThrownBy(() -> new ProjectionEnvelope(head, List.of(wrongRows)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void chunkSplittingIsPartOfTheDigestedContract() {
        String oneChunk = ProjectionDigest.ofChunks(List.of(new byte[]{1, 2, 3, 4}));
        String twoChunks = ProjectionDigest.ofChunks(List.of(new byte[]{1, 2}, new byte[]{3, 4}));
        assertThat(oneChunk).isNotEqualTo(twoChunks);
    }

    @Test
    void sectionPayloadsAreDefensivelyCopied() {
        byte[] mutable = {1, 2, 3};
        var section = new ProjectionSection(ProjectionSectionType.TRANSACTION, 2, List.of(mutable), 1);
        String before = section.manifest().digest();
        mutable[0] = 99;
        assertThat(section.manifest().digest()).isEqualTo(before);
    }

    // --- Byron / EBB ------------------------------------------------------------

    @Test
    void anEpochBoundaryBlockProducesAnEmptyEnvelopeAndStillHasIdentity() {
        var ebb = new ProjectionEnvelopeHeader(PREPROD, ProjectionBlockKind.BYRON_EBB, 42,
                new byte[]{4, 2}, new byte[]{4, 1}, 900, 2, 1_500_000_000L, 1, List.of(), List.of());
        var envelope = new ProjectionEnvelope(ebb, List.of());
        assertThat(envelope.sections()).isEmpty();
        assertThat(envelope.envelopeId()).hasSize(64);
        assertThat(ebb.blockKind().isByron()).isTrue();
        assertThat(ebb.blockKind().allowsEmptyEnvelope()).isTrue();
    }

    @Test
    void anEpochBoundaryBlockCannotCarrySections() {
        var tx = section(ProjectionSectionType.TRANSACTION, 1, new byte[]{1});
        assertThatThrownBy(() -> new ProjectionEnvelopeHeader(PREPROD, ProjectionBlockKind.BYRON_EBB, 42,
                new byte[]{4, 2}, new byte[]{4, 1}, 900, 2, 1L, 1, List.of(tx.manifest()), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty envelope");
    }

    @Test
    void byronMainBlocksMayCarrySections() {
        var tx = section(ProjectionSectionType.TRANSACTION, 1, new byte[]{1});
        var head = new ProjectionEnvelopeHeader(PREPROD, ProjectionBlockKind.BYRON_MAIN, 43,
                new byte[]{4, 3}, new byte[]{4, 2}, 920, 2, 1L, 1, List.of(tx.manifest()), List.of());
        assertThat(new ProjectionEnvelope(head, List.of(tx)).sections()).hasSize(1);
        assertThat(ProjectionBlockKind.BYRON_MAIN.allowsEmptyEnvelope()).isFalse();
    }

    // --- batches ----------------------------------------------------------------

    @Test
    void aBatchWithAGapIsRejected() {
        var e100 = envelope(100, List.of());
        var e102 = envelope(102, List.of());
        var identity = identity();
        assertThatThrownBy(() -> new ProjectionBatch(identity, List.of(e100, e102)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not contiguous");
    }

    @Test
    void aContiguousBatchReportsItsRangeAndTotals() {
        var batch = new ProjectionBatch(identity(), List.of(
                envelope(100, List.of(section(ProjectionSectionType.TRANSACTION, 2, new byte[]{1, 2}))),
                envelope(101, List.of(section(ProjectionSectionType.TRANSACTION, 3, new byte[]{3})))));
        assertThat(batch.firstBlock()).isEqualTo(100);
        assertThat(batch.lastBlock()).isEqualTo(101);
        assertThat(batch.blockCount()).isEqualTo(2);
        assertThat(batch.rowCount()).isEqualTo(5);
        assertThat(batch.byteCount()).isEqualTo(3);
    }

    @Test
    void anEmptyBatchIsRejected() {
        assertThatThrownBy(() -> new ProjectionBatch(identity(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- receipts ---------------------------------------------------------------

    @Test
    void aMatchingRetryIsRecognisedAndAReshapedOneIsNot() {
        var batch = new ProjectionBatch(identity(), List.of(
                envelope(100, List.of(section(ProjectionSectionType.TRANSACTION, 2, new byte[]{1, 2})))));
        var receipt = ProjectionReceipt.of(batch, Map.of("chain_transaction", 2L), java.time.Instant.EPOCH);

        assertThat(receipt.matches(batch)).isTrue();

        var reshaped = new ProjectionBatch(identity(), List.of(
                envelope(100, List.of(section(ProjectionSectionType.TRANSACTION, 2, new byte[]{9, 9})))));
        assertThat(receipt.matches(reshaped)).isFalse();

        var longer = new ProjectionBatch(identity(), List.of(
                envelope(100, List.of(section(ProjectionSectionType.TRANSACTION, 2, new byte[]{1, 2}))),
                envelope(101, List.of())));
        assertThat(receipt.matches(longer)).isFalse();
    }

    @Test
    void aReceiptRangeMustAgreeWithItsBlockCount() {
        assertThatThrownBy(() -> new ProjectionReceipt("fp", 100, 105, "a", "b", 2,
                Map.of(), "d", java.time.Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blockCount");
    }

    // --- identity ---------------------------------------------------------------

    static ProjectionIdentity identity() {
        return new ProjectionIdentity(PREPROD, "ducklake", 1,
                Set.of(ProjectionSectionType.TRANSACTION, ProjectionSectionType.UTXO_HISTORY));
    }

    @Test
    void identityFingerprintIsOrderIndependentButSetSensitive() {
        var a = new ProjectionIdentity(PREPROD, "ducklake", 1,
                Set.of(ProjectionSectionType.UTXO_HISTORY, ProjectionSectionType.TRANSACTION));
        var b = new ProjectionIdentity(PREPROD, "ducklake", 1,
                Set.of(ProjectionSectionType.TRANSACTION, ProjectionSectionType.UTXO_HISTORY));
        var c = new ProjectionIdentity(PREPROD, "ducklake", 1, Set.of(ProjectionSectionType.TRANSACTION));
        var d = new ProjectionIdentity(PREPROD, "sqlite", 1,
                Set.of(ProjectionSectionType.TRANSACTION, ProjectionSectionType.UTXO_HISTORY));

        assertThat(a.matches(b)).isTrue();
        assertThat(a.matches(c)).isFalse();
        assertThat(a.matches(d)).isFalse();
        assertThat(a.fingerprint()).contains("transaction:v1", "utxo-history:v1", "ducklake");
    }

    @Test
    void coordinateEmptyAndPresentStatesAreDistinct() {
        assertThat(ProjectionCoordinate.NONE.isPresent()).isFalse();
        assertThat(ProjectionCoordinate.NONE.nextExpectedBlock()).isEmpty();
        var present = ProjectionCoordinate.of(header(100, List.of()));
        assertThat(present.isPresent()).isTrue();
        assertThat(present.nextExpectedBlock()).contains(101L);
    }
}
