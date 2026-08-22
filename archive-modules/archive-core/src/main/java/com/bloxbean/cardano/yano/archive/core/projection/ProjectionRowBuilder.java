package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveJob;
import com.bloxbean.cardano.yano.archive.api.ArchiveRangeAnchor;
import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
import com.bloxbean.cardano.yano.archive.api.BlockRange;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionBatch;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionEnvelope;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionRowBatch;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSection;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSectionType;
import com.bloxbean.cardano.yano.archive.core.dataset.ArchiveBlockFacts;
import com.bloxbean.cardano.yano.archive.core.dataset.BlockSourceContext;
import com.bloxbean.cardano.yano.archive.core.dataset.StandardBlockDatasets;
import com.bloxbean.cardano.yano.archive.core.dataset.UtxoHistoryDataset;
import com.bloxbean.cardano.yano.archive.core.dataset.UtxoHistoryFact;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Materialises a projection batch into archive rows, once, for every backend.
 *
 * <p>Rows are derived through the same {@code BlockArchiveDataset} implementations the
 * existing archive path uses. Keeping one derivation is what makes the differential
 * parity gate meaningful: DuckLake and SQLite cannot disagree about what a block means,
 * because neither of them decides.
 */
public final class ProjectionRowBuilder {

    private ProjectionRowBuilder() {}

    /**
     * Present a batch as a lazily materialised row stream.
     *
     * <p>Rows for one envelope are derived, handed to the consumer, and discarded before the
     * next envelope is touched. Peak heap is therefore bounded by the largest single block,
     * not by batch size — which is what makes the configured memory ceiling a real bound
     * rather than a nominal one, and lets an oversized single envelope be processed instead
     * of either deadlocking the consumer or blowing the heap.
     */
    public static ProjectionRowBatch materialise(ProjectionBatch batch) {
        Iterable<ArchiveRow> rows = new SingleUseRowSource(batch.envelopes());
        return new ProjectionRowBatch(batch.identity(), batch.firstBlock(), batch.lastBlock(),
                batch.blockCount(), batch.firstEnvelopeId(), batch.lastEnvelopeId(),
                batch.orderedDigest(), rows, batch.artifacts());
    }

    /**
     * Walks envelopes in canonical order, holding at most one envelope's rows at a time.
     *
     * <p><strong>Single-use.</strong> A sink iterates exactly once and accumulates its receipt
     * row counts during that pass. A second {@code iterator()} call throws rather than
     * silently replaying: a partially consumed source would otherwise yield a short or
     * duplicated second pass that looks like valid data. After a failure the caller
     * re-materialises from the still-unacknowledged outbox batch, which is durable, so a fresh
     * stream is always obtainable without reusing a spent one.
     */
    /** Enforces single use; see {@link #materialise}. */
    private static final class SingleUseRowSource implements Iterable<ArchiveRow> {
        private final List<ProjectionEnvelope> envelopes;
        private final java.util.concurrent.atomic.AtomicBoolean consumed =
                new java.util.concurrent.atomic.AtomicBoolean();

        SingleUseRowSource(List<ProjectionEnvelope> envelopes) {
            this.envelopes = envelopes;
        }

        @Override
        public java.util.Iterator<ArchiveRow> iterator() {
            if (!consumed.compareAndSet(false, true)) {
                throw new IllegalStateException("projection row stream is single-use; re-materialise the"
                        + " batch from the outbox instead of iterating a partially consumed source");
            }
            return new StreamingRowIterator(envelopes);
        }
    }

    private static final class StreamingRowIterator implements java.util.Iterator<ArchiveRow> {
        private final List<ProjectionEnvelope> envelopes;
        private int nextEnvelope;
        private List<ArchiveRow> buffer = List.of();
        private int nextRow;

        StreamingRowIterator(List<ProjectionEnvelope> envelopes) {
            this.envelopes = envelopes;
        }

        @Override
        public boolean hasNext() {
            while (nextRow >= buffer.size()) {
                if (nextEnvelope >= envelopes.size()) return false;
                List<ArchiveRow> next = new ArrayList<>();
                appendRows(envelopes.get(nextEnvelope++), next);
                buffer = next;
                nextRow = 0;
            }
            return true;
        }

        @Override
        public ArchiveRow next() {
            if (!hasNext()) throw new java.util.NoSuchElementException();
            return buffer.get(nextRow++);
        }
    }

    /**
     * Derive one envelope's rows.
     *
     * <p>Section payloads are read through {@link ProjectionChunkedInput}, so the encoded bytes
     * are never concatenated into a second copy. Transaction facts are streamed and released
     * one at a time. The utxo-history fact is decoded whole because row derivation resolves
     * each output's address through a map built from the section's address list — outputs
     * cannot be emitted before the addresses are known.
     *
     * <p>That retained size is bounded by the protocol rather than by configuration: a section
     * derives from exactly one block, and Cardano caps block body size, so peak heap here is
     * one block's decoded facts. Batch size no longer contributes, which is what makes the
     * configured memory ceiling a real bound and lets an oversized single envelope be
     * processed rather than deadlocking the consumer.
     */
    /**
     * Genesis distribution rows, through the same dataset derivation ordinary outputs use.
     *
     * <p>Genesis funds belong to no block, so they cannot arrive as an envelope section - but
     * they must not become a second row derivation either. This routes the normalised genesis
     * fact through {@link UtxoHistoryDataset}, so address decomposition, column order and schema
     * semantics are shared with every other output row by construction.
     *
     * <p>The job identity is deterministic over the genesis coordinate, so a bootstrap replayed
     * after a crash reproduces the same {@code archive_job_id} rather than a fresh one.
     */
    public static List<ArchiveRow> genesisRows(com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity network, int projectionVersion,
                                               long blockNumber, long slot, int epoch, long blockTime,
                                               byte[] blockHash, byte[] parentHash,
                                               UtxoHistoryFact fact) {
        List<ArchiveRow> rows = new ArrayList<>();
        if (fact.outputs().isEmpty() && fact.newAddresses().isEmpty()) return rows;
        ArchiveJob job = ArchiveJob.deterministic(network, ArchiveDatasetId.UTXO_HISTORY,
                projectionVersion, new BlockRange(blockNumber, blockNumber),
                new ArchiveRangeAnchor(slot, blockHash, slot, blockHash), "genesis");
        new UtxoHistoryDataset().derive(job,
                new BlockSourceContext<>(blockNumber, slot, epoch, Instant.ofEpochSecond(blockTime),
                        blockHash, parentHash, fact),
                rows::add);
        return rows;
    }

    private static void appendRows(ProjectionEnvelope envelope, List<ArchiveRow> rows) {
        var header = envelope.header();
        long blockNumber = header.blockNumber();
        byte[] blockHash = header.blockHash();
        byte[] parentHash = header.parentHash();
        Instant blockTime = Instant.ofEpochSecond(header.blockTime());

        envelope.section(ProjectionSectionType.TRANSACTION).ifPresent(section -> {
            List<com.bloxbean.cardano.yano.archive.core.dataset.TransactionFact> facts = new ArrayList<>();
            ProjectionFactCodec.streamTransactions(section.chunks(), facts::add);
            StandardBlockDatasets.transactions().derive(
                    job(ArchiveDatasetId.TRANSACTION, envelope),
                    new BlockSourceContext<>(blockNumber, header.slot(), header.epoch(), blockTime,
                            blockHash, parentHash, new ArchiveBlockFacts(facts, List.of())),
                    rows::add);
        });

        envelope.section(ProjectionSectionType.ACCOUNT_EVENT).ifPresent(section -> {
            List<com.bloxbean.cardano.yano.archive.core.dataset.AccountEventFact> facts = new ArrayList<>();
            ProjectionFactCodec.streamAccountEvents(section.chunks(), facts::add);
            StandardBlockDatasets.accountEvents().derive(
                    job(ArchiveDatasetId.ACCOUNT_EVENT, envelope),
                    new BlockSourceContext<>(blockNumber, header.slot(), header.epoch(), blockTime,
                            blockHash, parentHash, new ArchiveBlockFacts(List.of(), facts)),
                    rows::add);
        });

        envelope.section(ProjectionSectionType.ADDRESS_TRANSACTION).ifPresent(section -> {
            // One transaction at a time: row emission groups by subject across a whole
            // transaction, so that is the smallest unit that can be materialised, and it is
            // protocol-bounded. Rows come from the same accumulator the live path uses.
            var job = job(ArchiveDatasetId.ADDRESS_TRANSACTION, envelope);
            ProjectionFactCodec.streamAddressParticipations(section.chunks(), tx -> {
                var accumulator = new com.bloxbean.cardano.yano.archive.core.dataset.AddressSubjectRows(
                        com.bloxbean.cardano.yano.archive.core.dataset.AddressTransactionSubjects.all(),
                        header.networkIdentity().networkMagic());
                for (var participation : tx.participations()) {
                    accumulator.add(participation.participant(),
                            com.bloxbean.cardano.yano.archive.core.dataset.AddressSubjectRows.Role
                                    .valueOf(participation.role()));
                }
                accumulator.emit(tx.txHash(), tx.txIndex(), blockHash, blockNumber, header.slot(),
                        header.epoch(), header.blockTime(), job.jobId(), rows::add);
            });
        });

        envelope.section(ProjectionSectionType.UTXO_HISTORY).ifPresent(section -> {
            UtxoHistoryFact fact = ProjectionFactCodec.decodeUtxoHistory(section.chunks());
            new UtxoHistoryDataset().derive(
                    job(ArchiveDatasetId.UTXO_HISTORY, envelope),
                    new BlockSourceContext<>(blockNumber, header.slot(), header.epoch(), blockTime,
                            blockHash, parentHash, fact),
                    rows::add);
        });
    }

    /**
     * Deterministic per-envelope job identity. The archive_job_id column therefore stays
     * reproducible on replay: the same canonical block always yields the same job.
     */
    private static ArchiveJob job(ArchiveDatasetId dataset, ProjectionEnvelope envelope) {
        var header = envelope.header();
        long blockNumber = header.blockNumber();
        return ArchiveJob.deterministic(header.networkIdentity(), dataset,
                header.canonicalProjectionVersion(), new BlockRange(blockNumber, blockNumber),
                new ArchiveRangeAnchor(header.slot(), header.blockHash(), header.slot(), header.blockHash()),
                "projection-v" + header.canonicalProjectionVersion());
    }

    /** Total bytes of section payload in a batch, for bounds and metrics. */
    public static long payloadBytes(ProjectionBatch batch) {
        return batch.envelopes().stream()
                .flatMap(e -> e.sections().stream())
                .mapToLong(ProjectionSection::byteCount)
                .sum();
    }
}
