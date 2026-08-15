package com.bloxbean.cardano.yano.archive.core.dataset;

import com.bloxbean.cardano.yano.archive.api.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class UtxoHistoryDatasetTest {
    @TempDir Path temp;
    @Test
    void normalizesOutputAssetsAndKeepsStakeCredentialQueryable() {
        byte[] hash = {1}; byte[] address = {2}; byte[] stake = {3};
        ArchiveJob job = ArchiveJob.deterministic(new ArchiveNetworkIdentity(1, "g"), ArchiveDatasetId.UTXO_HISTORY,
                1, new BlockRange(1, 1), new ArchiveRangeAnchor(10, hash, 10, hash), "v1");
        var facts = new UtxoHistoryFact(com.bloxbean.cardano.yaci.core.model.Era.Conway.getValue(), List.of(),
                List.of(new UtxoHistoryFact.Address(address, new byte[] {4}, "addr", 0, "base", "key",
                        new byte[] {5}, "credential", "key", stake, null, null, null)),
                List.of(new UtxoHistoryFact.Output(hash, 0, 0, "regular", address, new byte[] {5}, stake,
                        10, "none", null, null, null, null, null, false)),
                List.of(new UtxoHistoryFact.Asset(hash, 0, new byte[28], new byte[] {6},
                        new BigInteger("18446744073709551615"))), List.of(), List.of(), List.of());
        List<ArchiveRow> rows = new ArrayList<>();
        new UtxoHistoryDataset().derive(job, new BlockSourceContext<>(1, 10, 0, Instant.EPOCH,
                hash, new byte[0], facts), rows::add);
        assertThat(rows).extracting(ArchiveRow::table)
                .containsExactly("addresses", "transaction_outputs", "transaction_output_assets");
        assertThat(rows.get(1).values().get(6)).isEqualTo(stake);
        assertThat(rows.get(2).values().get(4)).isEqualTo(new BigInteger("18446744073709551615"));
    }

    @Test
    void sameBatchDeregistrationMakesOldPointerUnresolved() {
        byte[] credential = new byte[28];
        Arrays.fill(credential, (byte) 7);
        byte[] firstHash = new byte[] {1};
        byte[] secondHash = new byte[] {2};
        var registration = new UtxoHistoryFact.PointerRegistration(10, 0, 0, "key", credential);
        var deregistration = new UtxoHistoryFact.PointerDeregistration(0, 0, "key", credential);
        var firstAddress = pointerAddress(new byte[] {11});
        var secondAddress = pointerAddress(new byte[] {11});
        var first = new UtxoHistoryFact(com.bloxbean.cardano.yaci.core.model.Era.Babbage.getValue(),
                List.of(registration), List.of(), List.of(firstAddress),
                List.of(output(firstHash, firstAddress.addressKey())), List.of(), List.of(), List.of(), List.of());
        var second = new UtxoHistoryFact(com.bloxbean.cardano.yaci.core.model.Era.Babbage.getValue(),
                List.of(), List.of(deregistration), List.of(secondAddress),
                List.of(output(secondHash, secondAddress.addressKey())), List.of(), List.of(), List.of(), List.of());
        var firstContext = new BlockSourceContext<>(1, 10, 0, Instant.EPOCH,
                firstHash, new byte[0], first);
        var secondContext = new BlockSourceContext<>(2, 20, 0, Instant.EPOCH,
                secondHash, firstHash, second);
        ArchiveJob job = ArchiveJob.deterministic(new ArchiveNetworkIdentity(1, "g"),
                ArchiveDatasetId.UTXO_HISTORY, 1, new BlockRange(1, 2),
                new ArchiveRangeAnchor(10, firstHash, 20, secondHash), "v1");

        try (var state = new com.bloxbean.cardano.yano.archive.core.hot.RocksDbHotHistoryStore(temp.resolve("pointer"))) {
            var dataset = new UtxoHistoryDataset(state, "backfill",
                    com.bloxbean.cardano.yano.archive.core.worker.ArchiveTrack.BACKFILL);
            dataset.beginBatch(job, List.of(firstContext, secondContext));
            List<ArchiveRow> rows = new ArrayList<>();
            dataset.derive(job, firstContext, rows::add);
            dataset.derive(job, secondContext, rows::add);

            assertThat(rows).filteredOn(row -> row.table().equals("addresses"))
                    .extracting(row -> row.values().get(7))
                    .containsExactly("pointer", "pointer");
            List<ArchiveRow> addressRows = rows.stream()
                    .filter(row -> row.table().equals("addresses"))
                    .toList();
            assertThat(addressRows.get(0).values().subList(0, 13))
                    .containsExactlyElementsOf(addressRows.get(1).values().subList(0, 13));
            assertThat(rows).filteredOn(row -> row.table().equals("transaction_outputs"))
                    .extracting(row -> row.values().get(6))
                    .containsExactly(credential, null);
            dataset.abortBatch();
        }
    }

    private static UtxoHistoryFact.Address pointerAddress(byte[] key) {
        return new UtxoHistoryFact.Address(key, new byte[] {4}, "pointer", 0, "ptr", "key",
                new byte[28], "pointer", null, null, 10L, 0, 0);
    }

    private static UtxoHistoryFact.Output output(byte[] txHash, byte[] addressKey) {
        return new UtxoHistoryFact.Output(txHash, 0, 0, "regular", addressKey,
                new byte[28], null, 1, "none", null, null, null, null, null, false);
    }
}
