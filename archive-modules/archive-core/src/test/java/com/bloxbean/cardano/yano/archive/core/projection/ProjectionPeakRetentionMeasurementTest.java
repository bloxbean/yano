package com.bloxbean.cardano.yano.archive.core.projection;

import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveRow;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionBatch;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionBlockKind;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionEnvelope;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionEnvelopeHeader;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionIdentity;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSection;
import com.bloxbean.cardano.yano.archive.api.projection.ProjectionSectionType;
import com.bloxbean.cardano.yano.archive.core.dataset.TransactionFact;
import com.bloxbean.cardano.yano.archive.core.dataset.UtxoHistoryFact;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Measures — rather than asserts by construction — that the materialisation working set is
 * bounded by one envelope.
 *
 * <p>{@link ProjectionStreamingBoundTest} pins the structural properties (chunks are never
 * joined, the stream is single-use). This class puts numbers on them: peak retained heap
 * during streaming, and bytes allocated per row. The interesting property is the
 * <em>scaling</em>: growing the batch by 20x must grow the working set by roughly nothing,
 * because only the envelope currently being decoded is live.
 *
 * <p>Measurements are printed so a change in encoding cost is visible in CI output even when
 * the assertions still hold.
 */
class ProjectionPeakRetentionMeasurementTest {

    private static final ArchiveNetworkIdentity NETWORK = new ArchiveNetworkIdentity(1, "fixture");
    private static final ProjectionIdentity IDENTITY = new ProjectionIdentity(NETWORK, "test", 1,
            Set.of(ProjectionSectionType.TRANSACTION, ProjectionSectionType.UTXO_HISTORY));

    /**
     * Per-envelope density calibrated to the densest genuine preprod blocks observed during
     * ADR-039 validation: many outputs carrying inline datums, multi-asset bundles and
     * redeemers, rather than the average block which is far cheaper.
     */
    private static final int DENSE_ITEMS = 300;

    private record Measurement(int envelopes, long rows, long peakRetainedBytes,
                               long allocatedBytes) {
        long bytesPerRow() {
            return rows == 0 ? 0 : allocatedBytes / rows;
        }
    }

    @Test
    void theMaterialisationWorkingSetDoesNotGrowWithBatchSize() {
        Measurement small = measure(5);
        Measurement large = measure(100);

        System.out.printf("ADR-039 peak retention: %d envelopes -> %d rows, peak %d KiB, %d B/row%n",
                small.envelopes(), small.rows(), small.peakRetainedBytes() >> 10, small.bytesPerRow());
        System.out.printf("ADR-039 peak retention: %d envelopes -> %d rows, peak %d KiB, %d B/row%n",
                large.envelopes(), large.rows(), large.peakRetainedBytes() >> 10, large.bytesPerRow());

        assertThat(large.rows())
                .as("the larger batch really does carry 20x the rows")
                .isEqualTo(small.rows() * 20);

        // Allocation scales with total work, as it must: every row is still produced.
        assertThat(large.allocatedBytes())
                .as("total allocation tracks total rows")
                .isGreaterThan(small.allocatedBytes() * 10);

        // Retention does not. A 20x batch may not cost more than 4x the working set; a
        // materialise-the-whole-batch implementation would show ~20x here.
        long ceiling = Math.max(small.peakRetainedBytes() * 4, 24L << 20);
        assertThat(large.peakRetainedBytes())
                .as("peak retained heap must not scale with batch size (small=%d B, large=%d B)",
                        small.peakRetainedBytes(), large.peakRetainedBytes())
                .isLessThanOrEqualTo(ceiling);
    }

    @Test
    void oneDenseEnvelopeWorkingSetIsReportedForCapacityPlanning() {
        Measurement one = measure(1);
        System.out.printf("ADR-039 densest single envelope: %d rows, peak %d KiB, %d B/row%n",
                one.rows(), one.peakRetainedBytes() >> 10, one.bytesPerRow());

        // The singleton safety guard is sized in encoded bytes and rows; this records the
        // heap cost that sizing is protecting against.
        assertThat(one.rows()).isGreaterThan(1_000);
        assertThat(one.peakRetainedBytes()).isLessThan(256L << 20);
    }

    // ------------------------------------------------------------------ measurement

    private static Measurement measure(int envelopeCount) {
        List<ProjectionEnvelope> envelopes = new ArrayList<>();
        for (int b = 0; b < envelopeCount; b++) envelopes.add(denseEnvelope(b));
        var batch = new ProjectionBatch(IDENTITY, envelopes);

        // Baseline is taken with the envelopes already live, so what follows isolates the
        // cost of materialising rows from the cost of holding the encoded batch.
        long baseline = usedHeapAfterGc();
        long allocationBefore = allocatedBytes();

        long rows = 0;
        long peak = 0;
        int sampleEvery = Math.max(1, envelopeCount / 4);
        int envelopeBoundary = 0;
        long lastBlock = -1;
        var iterator = ProjectionRowBuilder.materialise(batch).rows().iterator();
        while (iterator.hasNext()) {
            ArchiveRow row = iterator.next();
            rows++;
            // Sample at a few envelope boundaries rather than every row: a forced GC per row
            // would dominate the measurement it is trying to take.
            long block = blockOf(row, lastBlock);
            if (block != lastBlock) {
                lastBlock = block;
                if (envelopeBoundary++ % sampleEvery == 0) {
                    peak = Math.max(peak, usedHeapAfterGc() - baseline);
                }
            }
        }
        long allocated = allocatedBytes() - allocationBefore;
        return new Measurement(envelopeCount, rows, Math.max(peak, 0), allocated);
    }

    /** Row-order proxy for "a new envelope started"; exact identity is not needed. */
    private static long blockOf(ArchiveRow row, long fallback) {
        for (Object value : row.values()) {
            if (value instanceof Long l && l >= 0 && l < 1_000_000) return l;
        }
        return fallback;
    }

    private static long usedHeapAfterGc() {
        Runtime runtime = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static long allocatedBytes() {
        var bean = ManagementFactory.getThreadMXBean();
        if (bean instanceof com.sun.management.ThreadMXBean sun && sun.isThreadAllocatedMemoryEnabled()) {
            return sun.getThreadAllocatedBytes(Thread.currentThread().threadId());
        }
        return 0;
    }

    // ------------------------------------------------------------------ fixtures

    private static ProjectionEnvelope denseEnvelope(long block) {
        List<TransactionFact> txs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            byte[] hash = new byte[32];
            hash[0] = (byte) i;
            hash[1] = (byte) block;
            txs.add(new TransactionFact(hash, i, true, 170_000L + i));
        }
        UtxoHistoryFact utxo = denseUtxo(DENSE_ITEMS, block);

        byte[] txEncoded = ProjectionFactCodec.encodeTransactions(txs);
        byte[] utxoEncoded = ProjectionFactCodec.encodeUtxoHistory(utxo);
        var txSection = new ProjectionSection(ProjectionSectionType.TRANSACTION,
                ProjectionSectionType.TRANSACTION.version(),
                ProjectionChunking.split(txEncoded, 1 << 20), txs.size());
        long utxoRows = utxo.outputs().size() + utxo.assets().size() + utxo.inputs().size()
                + utxo.transactionDatums().size() + utxo.transactionRedeemers().size();
        var utxoSection = new ProjectionSection(ProjectionSectionType.UTXO_HISTORY,
                ProjectionSectionType.UTXO_HISTORY.version(),
                ProjectionChunking.split(utxoEncoded, 1 << 20), utxoRows);
        var eventSection = accountEventSection(block);
        var addressSection = addressSection(block);
        var header = new ProjectionEnvelopeHeader(NETWORK, ProjectionBlockKind.SHELLEY_PLUS, block,
                new byte[]{(byte) block}, new byte[]{(byte) (block - 1)}, block * 20, 1, 1L, 1,
                List.of(txSection.manifest(), utxoSection.manifest(), eventSection.manifest(),
                        addressSection.manifest()),
                List.of());
        return new ProjectionEnvelope(header,
                List.of(txSection, utxoSection, eventSection, addressSection));
    }

    /** Certificates: cheap per row, but they are part of a four-dataset envelope. */
    private static ProjectionSection accountEventSection(long block) {
        List<com.bloxbean.cardano.yano.archive.core.dataset.AccountEventFact> events = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            byte[] credential = new byte[28];
            credential[0] = (byte) i;
            credential[1] = (byte) block;
            byte[] hash = new byte[32];
            hash[0] = (byte) i;
            events.add(new com.bloxbean.cardano.yano.archive.core.dataset.AccountEventFact(
                    credential, "key", "registration", hash, i, ((long) i) << 32,
                    null, null, null, 2_000_000L));
        }
        byte[] encoded = ProjectionFactCodec.encodeAccountEvents(events);
        return new ProjectionSection(ProjectionSectionType.ACCOUNT_EVENT,
                ProjectionSectionType.ACCOUNT_EVENT.version(),
                ProjectionChunking.split(encoded, 1 << 20), events.size());
    }

    /**
     * The heaviest of the four per row: every participation carries an address key, a display
     * address and two credentials, measured at ~234 bytes each.
     */
    private static ProjectionSection addressSection(long block) {
        List<com.bloxbean.cardano.yano.archive.core.dataset.AddressParticipationFact.Transaction> txs =
                new ArrayList<>();
        long participations = 0;
        for (int t = 0; t < 20; t++) {
            List<com.bloxbean.cardano.yano.archive.core.dataset.AddressParticipationFact.Participation> parts =
                    new ArrayList<>();
            for (int i = 0; i < DENSE_ITEMS / 4; i++) {
                byte[] key = new byte[28];
                key[0] = (byte) i;
                key[1] = (byte) block;
                parts.add(new com.bloxbean.cardano.yano.archive.core.dataset
                        .AddressParticipationFact.Participation(
                        i % 2 == 0 ? "INPUT" : "OUTPUT",
                        new com.bloxbean.cardano.yano.archive.core.dataset.AddressSubjectRows.Participant(
                                key, "addr_test1" + block + "_" + i, key, "key", key)));
            }
            byte[] txHash = new byte[32];
            txHash[0] = (byte) t;
            txHash[1] = (byte) block;
            txs.add(new com.bloxbean.cardano.yano.archive.core.dataset
                    .AddressParticipationFact.Transaction(txHash, t, parts));
            participations += parts.size();
        }
        byte[] encoded = ProjectionFactCodec.encodeAddressParticipations(
                new com.bloxbean.cardano.yano.archive.core.dataset.AddressParticipationFact(txs));
        return new ProjectionSection(ProjectionSectionType.ADDRESS_TRANSACTION,
                ProjectionSectionType.ADDRESS_TRANSACTION.version(),
                ProjectionChunking.split(encoded, 1 << 20), participations);
    }

    private static UtxoHistoryFact denseUtxo(int n, long block) {
        List<UtxoHistoryFact.Address> addresses = new ArrayList<>();
        List<UtxoHistoryFact.Output> outputs = new ArrayList<>();
        List<UtxoHistoryFact.Asset> assets = new ArrayList<>();
        List<UtxoHistoryFact.TransactionDatum> datums = new ArrayList<>();
        List<UtxoHistoryFact.TransactionRedeemer> redeemers = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            byte[] key = new byte[28];
            key[0] = (byte) i;
            key[1] = (byte) block;
            byte[] hash = new byte[32];
            hash[0] = (byte) i;
            hash[1] = (byte) block;
            addresses.add(new UtxoHistoryFact.Address(key, key, "addr" + block + "_" + i, 0, "base",
                    "key", key, "none", null, null, null, null, null));
            outputs.add(new UtxoHistoryFact.Output(hash, i, i, "output", key, key, null, 1_000_000L,
                    "hash", hash, new byte[512], null, null, null, false));
            assets.add(new UtxoHistoryFact.Asset(hash, i, key, new byte[]{1}, BigInteger.valueOf(7)));
            datums.add(new UtxoHistoryFact.TransactionDatum(hash, i, hash, new byte[1024]));
            redeemers.add(new UtxoHistoryFact.TransactionRedeemer(hash, i, "spend", i, new byte[512],
                    hash, BigInteger.TEN, BigInteger.TEN));
        }
        return new UtxoHistoryFact(6, List.of(), List.of(), addresses, outputs, assets, List.of(),
                datums, redeemers);
    }
}
