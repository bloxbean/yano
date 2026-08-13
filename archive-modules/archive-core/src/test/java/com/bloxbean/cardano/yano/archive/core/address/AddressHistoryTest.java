package com.bloxbean.cardano.yano.archive.core.address;

import com.bloxbean.cardano.yano.archive.api.*;
import com.bloxbean.cardano.yano.archive.core.dataset.*;
import com.bloxbean.cardano.yano.archive.core.hot.RocksDbHotHistoryStore;
import com.bloxbean.cardano.yano.archive.core.worker.ArchiveProgress;
import com.bloxbean.cardano.yano.archive.core.worker.ArchiveTrack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
        try (var reopened = new RocksDbHotHistoryStore(temp.resolve("hot"))) {
            SequentialOutpointResolver resolver = new SequentialOutpointResolver(reopened);
            assertThat(resolver.resolve(new Outpoint(new byte[] {9}, 0))).isPresent();
        }
    }

    @Test
    void byronGenesisAddressIsCanonicalWithoutShelleyCredentials() {
        String byronAddress = "FHnt4NL7yPXhCzCHVywZLqVsvwuG3HvwmjKXQJBrXh3h2aigv6uxkePbpzRNV8q";
        AddressKeyCodec codec = new AddressKeyCodec();
        try (var hot = new RocksDbHotHistoryStore(temp.resolve("byron-address"))) {
            var parts = new AddressTransactionDataset(hot, codec).address(byronAddress);

            assertThat(parts.raw()).isNotEmpty();
            assertThat(parts.addressKey()).containsExactly(codec.key(parts.raw()));
            assertThat(parts.paymentCredential()).isNull();
            assertThat(parts.stakeCredential()).isNull();
        }
    }

    @Test
    void restartGenesisSeedDoesNotResurrectAConsumedOutput() {
        Outpoint genesis = new Outpoint(new byte[] {7}, 0);
        var entry = new SequentialOutpointResolver.Entry(genesis,
                new ResolvedOutput(new byte[] {1}, null, null));
        try (var hot = new RocksDbHotHistoryStore(temp.resolve("idempotent-genesis"))) {
            var resolver = new SequentialOutpointResolver(hot);
            resolver.seedGenesis(List.of(entry));
            hot.deleteData(ArchiveDatasetId.ADDRESS_TRANSACTION,
                    List.of(resolver.logicalKey(genesis)));

            new SequentialOutpointResolver(hot).seedGenesis(List.of(entry));

            assertThat(new SequentialOutpointResolver(hot).resolve(genesis)).isEmpty();
        }
    }

    @Test
    void resolverStateAndCursorCommitAtomicallyAfterBackendReceipt() {
        byte[] firstHash = java.util.HexFormat.of().parseHex("11".repeat(32));
        byte[] secondHash = java.util.HexFormat.of().parseHex("22".repeat(32));
        String address = "60" + "33".repeat(28);
        var firstTx = com.bloxbean.cardano.yaci.core.model.TransactionBody.builder()
                .txHash("11".repeat(32)).outputs(List.of(
                        com.bloxbean.cardano.yaci.core.model.TransactionOutput.builder().address(address).build()))
                .build();
        var secondTx = com.bloxbean.cardano.yaci.core.model.TransactionBody.builder()
                .txHash("22".repeat(32)).inputs(java.util.Set.of(
                        com.bloxbean.cardano.yaci.core.model.TransactionInput.builder()
                                .transactionId("11".repeat(32)).index(0).build())).build();
        var block1 = com.bloxbean.cardano.yaci.core.model.Block.builder()
                .transactionBodies(List.of(firstTx)).invalidTransactions(List.of()).build();
        var block2 = com.bloxbean.cardano.yaci.core.model.Block.builder()
                .transactionBodies(List.of(secondTx)).invalidTransactions(List.of()).build();
        var c1 = new BlockSourceContext<>(1, 10, 0, Instant.EPOCH, new byte[] {1}, new byte[0], block1);
        var c2 = new BlockSourceContext<>(2, 11, 0, Instant.EPOCH, new byte[] {2}, new byte[] {1}, block2);
        ArchiveJob job = ArchiveJob.deterministic(new ArchiveNetworkIdentity(1, "g"),
                ArchiveDatasetId.ADDRESS_TRANSACTION, 1, new BlockRange(1, 2),
                new ArchiveRangeAnchor(10, new byte[] {1}, 11, new byte[] {2}), "v1");

        try (var hot = new RocksDbHotHistoryStore(temp.resolve("atomic"))) {
            var dataset = new AddressTransactionDataset(hot, new AddressKeyCodec());
            dataset.seedGenesis(List.of());
            dataset.beginBatch(job, List.of(c1, c2));
            List<ArchiveRow> rows = new ArrayList<>();
            dataset.derive(job, c1, rows::add);
            dataset.derive(job, c2, rows::add);
            assertThat(rows).isNotEmpty();
            ArchiveReceipt receipt = new ArchiveReceipt(job.jobId(), job.networkIdentity(), job.dataset(), 1,
                    job.range(), job.anchors(), 7, Map.of("address_transactions", (long) rows.size()),
                    "digest", Instant.EPOCH);
            dataset.commitBatch(receipt);

            assertThat(hot.load(ArchiveDatasetId.ADDRESS_TRANSACTION, ArchiveTrack.BACKFILL))
                    .get().extracting(ArchiveProgress::coordinate).isEqualTo(2L);
            assertThat(new SequentialOutpointResolver(hot).resolve(new Outpoint(firstHash, 0))).isEmpty();
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

    @Test
    void preConwayPointerAddressProducesStakeSubjectFromArchiveRegistrationState() {
        String stakeHash = "77".repeat(28);
        String pointerAddress = "40" + "66".repeat(28) + "0a0000";
        var registration = com.bloxbean.cardano.yaci.core.model.certs.StakeRegistration.builder()
                .stakeCredential(com.bloxbean.cardano.yaci.core.model.certs.StakeCredential.builder()
                        .type(com.bloxbean.cardano.yaci.core.model.certs.StakeCredType.ADDR_KEYHASH)
                        .hash(stakeHash).build()).build();
        var tx = com.bloxbean.cardano.yaci.core.model.TransactionBody.builder()
                .txHash("11".repeat(32)).certificates(List.of(registration))
                .outputs(List.of(com.bloxbean.cardano.yaci.core.model.TransactionOutput.builder()
                        .address(pointerAddress).build())).build();
        var block = com.bloxbean.cardano.yaci.core.model.Block.builder()
                .era(com.bloxbean.cardano.yaci.core.model.Era.Babbage)
                .transactionBodies(List.of(tx)).invalidTransactions(List.of()).build();
        var context = new BlockSourceContext<>(1, 10, 0, Instant.EPOCH,
                new byte[]{1}, new byte[0], block);
        ArchiveJob job = ArchiveJob.deterministic(new ArchiveNetworkIdentity(1, "g"),
                ArchiveDatasetId.ADDRESS_TRANSACTION, 1, new BlockRange(1, 1),
                new ArchiveRangeAnchor(10, new byte[]{1}, 10, new byte[]{1}), "v1");

        try (var state = new RocksDbHotHistoryStore(temp.resolve("address-pointer"))) {
            var dataset = new AddressTransactionDataset(state, new AddressKeyCodec());
            dataset.seedGenesis(List.of());
            dataset.beginBatch(job, List.of(context));
            List<ArchiveRow> rows = new ArrayList<>();
            dataset.derive(job, context, rows::add);
            assertThat(rows).anySatisfy(row -> {
                if (row.values().getFirst().equals("stake_credential")) {
                    assertThat((byte[]) row.values().get(1)).containsOnly((byte) 0x77);
                }
            });
            dataset.abortBatch();
        }
    }
}
