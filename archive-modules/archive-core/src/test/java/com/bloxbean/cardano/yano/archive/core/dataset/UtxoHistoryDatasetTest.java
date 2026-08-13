package com.bloxbean.cardano.yano.archive.core.dataset;

import com.bloxbean.cardano.yano.archive.api.*;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class UtxoHistoryDatasetTest {
    @Test
    void normalizesOutputAssetsAndKeepsStakeCredentialQueryable() {
        byte[] hash = {1}; byte[] address = {2}; byte[] stake = {3};
        ArchiveJob job = ArchiveJob.deterministic(new ArchiveNetworkIdentity(1, "g"), ArchiveDatasetId.UTXO_HISTORY,
                1, new BlockRange(1, 1), new ArchiveRangeAnchor(10, hash, 10, hash), "v1");
        var facts = new UtxoHistoryFact(com.bloxbean.cardano.yaci.core.model.Era.Conway.getValue(), List.of(),
                List.of(new UtxoHistoryFact.Address(address, new byte[] {4}, "addr", 0, "base", "key",
                        new byte[] {5}, "credential", "key", stake, null, null, null)),
                List.of(new UtxoHistoryFact.Output(hash, 0, 0, "regular", address, new byte[] {5}, stake,
                        10, "none", null, null, false)),
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
}
