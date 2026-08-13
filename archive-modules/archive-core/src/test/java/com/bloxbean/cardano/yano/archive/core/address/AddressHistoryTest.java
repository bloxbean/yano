package com.bloxbean.cardano.yano.archive.core.address;

import com.bloxbean.cardano.yano.archive.api.*;
import com.bloxbean.cardano.yano.archive.core.dataset.*;
import com.bloxbean.cardano.yano.archive.core.hot.RocksDbHotHistoryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AddressHistoryTest {
    @TempDir Path temp;

    @Test
    void canonicalAddressKeyIsStableAndResolverRequiresGenesisSeed() {
        AddressKeyCodec codec = new AddressKeyCodec();
        assertThat(codec.key(new byte[] {1, 2})).isEqualTo(codec.key(new byte[] {1, 2}));
        try (var hot = new RocksDbHotHistoryStore(temp.resolve("hot"))) {
            SequentialOutpointResolver resolver = new SequentialOutpointResolver(hot);
            Outpoint genesis = new Outpoint(new byte[] {9}, 0);
            assertThatThrownBy(() -> resolver.resolve(genesis)).hasMessageContaining("genesis-seeded");
            resolver.seedGenesis(List.of(new SequentialOutpointResolver.Entry(genesis,
                    new ResolvedOutput(new byte[] {1}, new byte[] {2}, new byte[] {3}))));
            assertThat(resolver.resolve(genesis).orElseThrow().stakeCredential()).containsExactly(3);
        }
    }

    @Test
    void collateralCountsAndCredentialSubjectsAreProjected() {
        byte[] hash = {1};
        ArchiveJob job = ArchiveJob.deterministic(new ArchiveNetworkIdentity(1, "g"),
                ArchiveDatasetId.ADDRESS_TRANSACTION, 1, new BlockRange(1, 1),
                new ArchiveRangeAnchor(10, hash, 10, hash), "v1");
        var fact = new AddressTransactionFact(new byte[] {2}, 0,
                List.of(new AddressSubject("address", new byte[] {3}),
                        new AddressSubject("stake_credential", new byte[] {4})), 2, 1, 1, 1);
        var block = new BlockSourceContext<>(1, 10, 0, Instant.EPOCH, hash, new byte[0],
                new ArchiveBlockFacts(List.of(), List.of(), List.of(fact)));
        List<ArchiveRow> rows = new ArrayList<>();
        StandardBlockDatasets.addressTransactions().derive(job, block, rows::add);
        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst().values().subList(9, 13)).containsExactly(2, 1, 1, 1);
    }
}
