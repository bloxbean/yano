package com.bloxbean.cardano.yano.archive.core.address;

import com.bloxbean.cardano.yano.archive.api.*;
import com.bloxbean.cardano.yano.archive.core.dataset.*;
import com.bloxbean.cardano.yano.archive.core.hot.RocksDbHotHistoryStore;
import com.bloxbean.cardano.yano.archive.core.worker.ArchiveProgress;
import com.bloxbean.cardano.yano.archive.core.worker.ArchiveTrack;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
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
        assertThat(codec.key(new byte[] {1, 2})).containsExactly(
                Blake2bUtil.blake2bHash256(new byte[] {1, 2}));
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
    void addressRoleCountsArePerSubjectRatherThanWholeTransaction() {
        String firstAddress = "60" + "33".repeat(28);
        String secondAddress = "60" + "44".repeat(28);
        var tx = com.bloxbean.cardano.yaci.core.model.TransactionBody.builder()
                .txHash("11".repeat(32)).outputs(List.of(
                        com.bloxbean.cardano.yaci.core.model.TransactionOutput.builder().address(firstAddress).build(),
                        com.bloxbean.cardano.yaci.core.model.TransactionOutput.builder().address(secondAddress).build()))
                .build();
        var block = com.bloxbean.cardano.yaci.core.model.Block.builder()
                .era(com.bloxbean.cardano.yaci.core.model.Era.Conway)
                .transactionBodies(List.of(tx)).invalidTransactions(List.of()).build();
        var context = new BlockSourceContext<>(1, 10, 0, Instant.EPOCH,
                new byte[]{1}, new byte[0], block);
        ArchiveJob job = ArchiveJob.deterministic(new ArchiveNetworkIdentity(1, "g"),
                ArchiveDatasetId.ADDRESS_TRANSACTION, 3, new BlockRange(1, 1),
                new ArchiveRangeAnchor(10, new byte[]{1}, 10, new byte[]{1}), "v3");

        try (var state = new RocksDbHotHistoryStore(temp.resolve("subject-counts"))) {
            var dataset = new AddressTransactionDataset(state, new AddressKeyCodec());
            dataset.seedGenesis(List.of());
            dataset.beginBatch(job, List.of(context));
            List<ArchiveRow> rows = new ArrayList<>();
            dataset.derive(job, context, rows::add);

            assertThat(rows).allSatisfy(row -> {
                assertThat(row.values().get(11)).isEqualTo(0);
                assertThat(row.values().get(12)).isEqualTo(1);
                assertThat(row.values().get(13)).isEqualTo(0);
                assertThat(row.values().get(14)).isEqualTo(0);
            });
            dataset.abortBatch();
        }
    }

    @Test
    void statefulProjectionWritesOnlySelectedStakeSubject() {
        String baseAddress = "00" + "33".repeat(28) + "44".repeat(28);
        var tx = com.bloxbean.cardano.yaci.core.model.TransactionBody.builder()
                .txHash("11".repeat(32)).outputs(List.of(
                        com.bloxbean.cardano.yaci.core.model.TransactionOutput.builder()
                                .address(baseAddress).build()))
                .build();
        var block = com.bloxbean.cardano.yaci.core.model.Block.builder()
                .era(com.bloxbean.cardano.yaci.core.model.Era.Conway)
                .transactionBodies(List.of(tx)).invalidTransactions(List.of()).build();
        var context = new BlockSourceContext<>(1, 10, 0, Instant.EPOCH,
                new byte[] {1}, new byte[0], block);
        ArchiveJob job = ArchiveJob.deterministic(new ArchiveNetworkIdentity(1, "g"),
                ArchiveDatasetId.ADDRESS_TRANSACTION, 3, new BlockRange(1, 1),
                new ArchiveRangeAnchor(10, new byte[] {1}, 10, new byte[] {1}), "stake-only");

        try (var state = new RocksDbHotHistoryStore(temp.resolve("stake-only-stateful"))) {
            var dataset = new AddressTransactionDataset(state, new AddressKeyCodec(),
                    ArchiveTrack.BACKFILL, new AddressTransactionSubjects(false, false, true));
            dataset.seedGenesis(List.of());
            dataset.beginBatch(job, List.of(context));
            List<ArchiveRow> rows = new ArrayList<>();
            dataset.derive(job, context, rows::add);

            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().values().getFirst()).isEqualTo("stake_credential");
            assertThat(rows.getFirst().values().get(2)).isNull();
            assertThat(rows.getFirst().values().get(3)).asString().startsWith("stake_test");
            dataset.abortBatch();
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
            hot.applyBlock(ArchiveDatasetId.ADDRESS_TRANSACTION,
                    new com.bloxbean.cardano.yano.archive.core.hot.HotBlockCheckpoint(
                            1, 1, new byte[] {1}, new byte[] {0}),
                    List.of(resolver.consumeOperation(genesis, new byte[32], "ordinary")),
                    new com.bloxbean.cardano.yano.archive.core.worker.ArchiveProgress(
                            ArchiveDatasetId.ADDRESS_TRANSACTION,
                            com.bloxbean.cardano.yano.archive.core.worker.ArchiveTrack.BACKFILL,
                            1, 1, new byte[] {1}, 0));

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
    void duplicateOutpointAcceptsOnlyIdenticalReplayAndRejectsConflictingContent() {
        String txHash = "11".repeat(32);
        String address = "60" + "33".repeat(28);
        var tx = com.bloxbean.cardano.yaci.core.model.TransactionBody.builder()
                .txHash(txHash).outputs(List.of(
                        com.bloxbean.cardano.yaci.core.model.TransactionOutput.builder().address(address).build()))
                .build();
        var block = com.bloxbean.cardano.yaci.core.model.Block.builder()
                .era(com.bloxbean.cardano.yaci.core.model.Era.Conway)
                .transactionBodies(List.of(tx)).invalidTransactions(List.of()).build();
        var context = new BlockSourceContext<>(1, 10, 0, Instant.EPOCH,
                new byte[] {1}, new byte[0], block);
        ArchiveJob job = ArchiveJob.deterministic(new ArchiveNetworkIdentity(1, "g"),
                ArchiveDatasetId.ADDRESS_TRANSACTION, 2, new BlockRange(1, 1),
                new ArchiveRangeAnchor(10, new byte[] {1}, 10, new byte[] {1}), "v2");

        try (var state = new RocksDbHotHistoryStore(temp.resolve("duplicate-outpoint"))) {
            var dataset = new AddressTransactionDataset(state, new AddressKeyCodec());
            dataset.seedGenesis(List.of());
            dataset.beginBatch(job, List.of(context));
            dataset.derive(job, context, ignored -> { });
            dataset.commitCoveredBatch(0);

            // Re-applying the same resolver fact after interrupted rollback is idempotent.
            dataset.beginBatch(job, List.of(context));
            dataset.derive(job, context, ignored -> { });
            dataset.abortBatch();
        }

        try (var state = new RocksDbHotHistoryStore(temp.resolve("conflicting-outpoint"))) {
            Outpoint outpoint = new Outpoint(java.util.HexFormat.of().parseHex(txHash), 0);
            var dataset = new AddressTransactionDataset(state, new AddressKeyCodec());
            dataset.seedGenesis(List.of(new SequentialOutpointResolver.Entry(outpoint,
                    new ResolvedOutput(new byte[] {9}, null, null))));
            dataset.beginBatch(job, List.of(context));
            assertThatThrownBy(() -> dataset.derive(job, context, ignored -> { }))
                    .isInstanceOf(ArchiveStoreException.class)
                    .hasMessageContaining("conflicting address-history outpoint");
            dataset.abortBatch();
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
        assertThat(rows.getFirst().values().subList(11, 15)).containsExactly(2, 1, 1, 1);
    }

    @Test
    void stakeOnlySelectionOmitsAddressAndPaymentCredentialRows() {
        byte[] hash = {1};
        ArchiveJob job = ArchiveJob.deterministic(new ArchiveNetworkIdentity(1, "g"),
                ArchiveDatasetId.ADDRESS_TRANSACTION, 1, new BlockRange(1, 1),
                new ArchiveRangeAnchor(10, hash, 10, hash), "stake-only");
        var fact = new AddressTransactionFact(new byte[] {2}, 0,
                List.of(new AddressSubject("address", new byte[] {3}),
                        new AddressSubject("payment_credential", new byte[] {4}),
                        new AddressSubject("stake_credential", new byte[] {5})), 2, 1, 0, 0);
        var block = new BlockSourceContext<>(1, 10, 0, Instant.EPOCH, hash, new byte[0],
                new ArchiveBlockFacts(List.of(), List.of(), List.of(fact)));
        List<ArchiveRow> rows = new ArrayList<>();

        StandardBlockDatasets.addressTransactions(
                new AddressTransactionSubjects(false, false, true)).derive(job, block, rows::add);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().values().getFirst()).isEqualTo("stake_credential");
        assertThat(rows.getFirst().values().get(1)).isEqualTo(new byte[] {5});
    }

    @Test
    void addressSubjectSelectionRequiresAtLeastOneScope() {
        assertThatThrownBy(() -> new AddressTransactionSubjects(false, false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one");
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
