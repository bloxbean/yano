package com.bloxbean.cardano.yano.runtime.wallet;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionInput;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.events.RollbackEvent;
import com.bloxbean.cardano.yano.api.plugin.StorageFilter;
import com.bloxbean.cardano.yano.runtime.utxo.StorageFilterChain;
import com.bloxbean.cardano.yano.api.wallet.WalletScanRequest;
import com.bloxbean.cardano.yano.api.wallet.WalletScanRollbackException;
import com.bloxbean.cardano.yano.api.wallet.WalletCredential;
import com.bloxbean.cardano.yano.api.wallet.WalletChainPoint;
import com.bloxbean.cardano.yano.api.wallet.WalletIndexUnavailableException;
import com.bloxbean.cardano.yano.runtime.chain.DirectRocksDBChainState;
import com.bloxbean.cardano.yano.runtime.chain.BlockPruner;
import com.bloxbean.cardano.yano.runtime.utxo.DefaultUtxoStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletIndexRuntimeTest {
    @TempDir Path directory;
    @TempDir Path snapshots;
    private DirectRocksDBChainState chain;
    private DefaultUtxoStore store;
    private static final String A = address(1);
    private static final String B = address(2);
    private static final String C = address(3);

    @BeforeEach void open() {
        chain = new DirectRocksDBChainState(directory.toString());
        store = new DefaultUtxoStore(chain, LoggerFactory.getLogger(getClass()), Map.of(
                YanoPropertyKeys.WalletIndex.FIRST_SEEN_ENABLED, true,
                YanoPropertyKeys.WalletIndex.FILTERS_ENABLED, true,
                YanoPropertyKeys.Metrics.ENABLED, false));
        store.wireAllegraBootstrapRemoval(chain);
        store.initializeFreshFullStateGenesis(Map.of(), 42, Map.of(), Map.of(), 0, 0, "00".repeat(32));
    }

    @AfterEach void close() { store.close(); chain.close(); }

    @Test void spentAddressRemainsSeenAndOutgoingOnlyBlockMatches() throws Exception {
        apply(1, List.of(tx(1, List.of(), List.of(output(A)))), List.of());
        apply(2, List.of(tx(2, List.of(input(1)), List.of(output(B)))), List.of());
        assertThat(store.getUtxosByAddress(A, 1, 10)).isEmpty();
        assertThat(store.getAddressFirstSeen(A).firstSeenSlot()).isEqualTo(10);
        assertThat(store.getAddressFirstSeen(B).firstSeenSlot()).isEqualTo(20);
        assertThat(filterMatches(2, 1)).isTrue();
        store.rollbackToSlot(10);
        assertThat(store.getAddressFirstSeen(A).firstSeenSlot()).isEqualTo(10);
        assertThat(store.getAddressFirstSeen(B).firstSeenSlot()).isNull();
        assertThat(chain.rocks().db().get(chain.rocks().handle(WalletIndexCf.FILTERS), number(2))).isNull();
    }

    @Test void sameBlockCreateSpendIsSeenEvenWithoutSurvivingUtxo() throws Exception {
        apply(1, List.of(tx(1, List.of(), List.of(output(A))),
                tx(2, List.of(input(1)), List.of(output(B)))), List.of());
        assertThat(store.getUtxosByAddress(A, 1, 10)).isEmpty();
        assertThat(store.getAddressFirstSeen(A).firstSeenSlot()).isEqualTo(10);
        assertThat(filterMatches(1, 1)).isTrue();
    }

    @Test void invalidTransactionIndexesOnlyCollateralEffects() throws Exception {
        apply(1, List.of(tx(1, List.of(), List.of(output(A)))), List.of());
        TransactionBody invalid = TransactionBody.builder().txHash(hash(2))
                .inputs(Set.of()).outputs(List.of(output(B)))
                .collateralInputs(Set.of(input(1))).collateralReturn(output(C)).build();
        apply(2, List.of(invalid), List.of(0));
        assertThat(store.getAddressFirstSeen(B).firstSeenSlot()).isNull();
        assertThat(store.getAddressFirstSeen(C).firstSeenSlot()).isEqualTo(20);
        assertThat(filterMatches(2, 1)).isTrue();
        assertThat(filterMatches(2, 3)).isTrue();
        assertThat(filterMatches(2, 2)).isFalse();
    }

    @Test void unresolvedScanInputDoesNotInvalidateFirstSeen() {
        apply(1, List.of(tx(1, List.of(input(99)), List.of(output(A)))), List.of());
        assertThat(store.getAddressFirstSeen(A).firstSeenSlot()).isEqualTo(10);
    }

    @Test void lateEnablementIsUnavailable() {
        store.close();
        store = new DefaultUtxoStore(chain, LoggerFactory.getLogger(getClass()), Map.of(YanoPropertyKeys.Metrics.ENABLED, false));
        apply(1, List.of(tx(1, List.of(), List.of(output(A)))), List.of());
        store.close();
        store = new DefaultUtxoStore(chain, LoggerFactory.getLogger(getClass()), Map.of(
                YanoPropertyKeys.WalletIndex.FIRST_SEEN_ENABLED, true, YanoPropertyKeys.Metrics.ENABLED, false));
        assertThatThrownBy(() -> store.getAddressFirstSeen(B)).isInstanceOf(WalletIndexUnavailableException.class);
        apply(2, List.of(tx(2, List.of(), List.of(output(B)))), List.of());
        assertThatThrownBy(() -> store.getAddressFirstSeen(B)).isInstanceOf(WalletIndexUnavailableException.class);
    }

    @Test void producerDeferredGenesisIsIncludedBeforeLiveUtxoInsertion() throws Exception {
        store.close();
        chain.close();
        chain = new DirectRocksDBChainState(directory.resolve("producer").toString());
        store = new DefaultUtxoStore(chain, LoggerFactory.getLogger(getClass()), Map.of(
                YanoPropertyKeys.WalletIndex.FIRST_SEEN_ENABLED, true,
                YanoPropertyKeys.WalletIndex.FILTERS_ENABLED, true,
                YanoPropertyKeys.Metrics.ENABLED, false));
        store.wireAllegraBootstrapRemoval(chain);
        Map<String, BigInteger> funds = Map.of(HexFormat.of().formatHex(new Address(A).getBytes()), BigInteger.TEN);
        store.initializeFreshFullStateGenesis(Map.of(), 42, Map.of(), Map.of(), 0, 0,
                "00".repeat(32), funds);
        assertThat(store.getUtxosByAddress(A, 1, 10)).isEmpty();
        assertThat(store.getAddressFirstSeen(A).firstSeenSlot()).isZero();
        WalletIndexStore indexes = new WalletIndexStore(chain.rocks(), true, true);
        assertThat(indexes.scanGenesis()).singleElement().satisfies(output -> {
            assertThat(output.address()).isEqualTo(A);
            assertThat(output.lovelace()).isEqualTo(BigInteger.TEN);
        });
        store.storeGenesisUtxos(funds, 42, 0, 0, hash(0));
        assertThat(store.getUtxosByAddress(A, 1, 10)).hasSize(1);
        assertThat(store.getAddressFirstSeen(A).firstSeenSlot()).isZero();
    }

    @Test void incompatibleDerivedMetadataFailsClosedWithoutStoppingLedgerApply() throws Exception {
        apply(1, List.of(tx(1, List.of(), List.of(output(B)))), List.of());
        chain.rocks().db().put(chain.rocks().handle(WalletIndexCf.META),
                new byte[]{WalletIndexStore.FIRST_SEEN}, new byte[]{0, 0, 0, 99});
        apply(2, List.of(tx(2, List.of(), List.of(output(A)))), List.of());
        assertThat(store.getUtxosByAddress(A, 1, 10)).hasSize(1);
        assertThatThrownBy(() -> store.getAddressFirstSeen(A)).isInstanceOf(WalletIndexUnavailableException.class);
        WalletIndexStore index = new WalletIndexStore(chain.rocks(), true, true);
        assertThat(index.coverage(WalletIndexStore.FIRST_SEEN,
                new WalletChainPoint(2, 20, hash(2))).available()).isFalse();
        store.rollbackToSlot(10);
        assertThatThrownBy(() -> store.getAddressFirstSeen(A)).isInstanceOf(WalletIndexUnavailableException.class);
    }

    @Test void orphanedUtxoPointCannotServeFirstSeenBeforeDerivedRollback() {
        apply(1, List.of(tx(1, List.of(), List.of(output(A)))), List.of());
        byte[] replacement = HexFormat.of().parseHex(hash(99));
        chain.storeBlockHeader(replacement, 1L, 10L, new byte[]{0});
        assertThatThrownBy(() -> store.getAddressFirstSeen(A)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer canonical");
        assertThatThrownBy(() -> store.getAddressFirstSeen(B)).isInstanceOf(IllegalStateException.class);
    }

    @Test void corruptWalletUndoCannotPreventCanonicalUtxoRollback() throws Exception {
        apply(1, List.of(tx(1, List.of(), List.of(output(A)))), List.of());
        apply(2, List.of(tx(2, List.of(input(1)), List.of(output(B)))), List.of());
        byte[] key = ByteBuffer.allocate(9).put(WalletIndexStore.FIRST_SEEN).putLong(2).array();
        chain.rocks().db().put(chain.rocks().handle(WalletIndexCf.UNDO), key, new byte[]{0});
        store.rollbackToSlot(10);
        assertThat(store.getUtxosByAddress(A, 1, 10)).hasSize(1);
        assertThat(store.getUtxosByAddress(B, 1, 10)).isEmpty();
        assertThatThrownBy(() -> store.getAddressFirstSeen(A)).isInstanceOf(WalletIndexUnavailableException.class);
        assertThat(chain.rocks().db().get(chain.rocks().handle(WalletIndexCf.FILTERS), number(2))).isNull();
        apply(2, List.of(tx(3, List.of(input(1)), List.of(output(C)))), List.of());
        assertThat(store.getUtxosByAddress(C, 1, 10)).hasSize(1);
        assertThatThrownBy(() -> store.getAddressFirstSeen(C)).isInstanceOf(WalletIndexUnavailableException.class);
    }

    @Test void pruningRetainsPermanentIndexesAndRejectsScansAcrossRemovedBodies() throws Exception {
        store.close();
        store = new DefaultUtxoStore(chain, LoggerFactory.getLogger(getClass()), Map.of(
                YanoPropertyKeys.WalletIndex.FIRST_SEEN_ENABLED, true,
                YanoPropertyKeys.WalletIndex.FILTERS_ENABLED, true,
                YanoPropertyKeys.Utxo.PRUNE_DEPTH, 20,
                YanoPropertyKeys.Utxo.ROLLBACK_WINDOW, 20,
                YanoPropertyKeys.Metrics.ENABLED, false));
        apply(1, List.of(tx(1, List.of(), List.of(output(A)))), List.of());
        apply(2, List.of(tx(2, List.of(input(1)), List.of(output(B)))), List.of());
        apply(3, List.of(tx(3, List.of(), List.of(output(C)))), List.of());
        apply(4, List.of(), List.of());
        var request = new WalletScanRequest(1, List.of(new WalletCredential("payment", "key", "ff".repeat(28))),
                WalletChainPoint.ORIGIN, null, List.of());
        try (var active = store.openWalletScan(request)) {
            assertThat(active.next()).hasSize(1);
            store.pruneOnce();
            new BlockPruner(chain, chain, 2, 100).pruneOnce();
            assertThat(chain.getBlock(HexFormat.of().parseHex(hash(1)))).isNull();
            assertThat(chain.getBlock(HexFormat.of().parseHex(hash(3)))).isNotNull();
            assertThat(chain.rocks().db().get(chain.rocks().handle(WalletIndexCf.UNDO),
                    ByteBuffer.allocate(9).put(WalletIndexStore.FIRST_SEEN).putLong(2).array())).isNull();
            assertThat(chain.rocks().db().get(chain.rocks().handle(WalletIndexCf.FILTERS), number(1))).isNotNull();
            assertThat(store.getAddressFirstSeen(A).firstSeenSlot()).isEqualTo(10);
            assertThat(store.getAddressFirstSeen(B).firstSeenSlot()).isEqualTo(20);
            assertThatThrownBy(active::next).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("bodies are no longer retained");
            assertThat(active.finished()).isFalse();
            assertThatThrownBy(() -> store.openWalletScan(request)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("bodies are no longer retained");
        }
        store.rollbackToPoint(new Point(30, hash(3)));
        assertThat(store.getAddressFirstSeen(A).firstSeenSlot()).isEqualTo(10);
        assertThat(store.getAddressFirstSeen(C).firstSeenSlot()).isEqualTo(30);
        assertThatThrownBy(() -> store.rollbackToPoint(new Point(10, hash(1)))).isInstanceOf(RuntimeException.class);
        assertThat(store.getAddressFirstSeen(A).firstSeenSlot()).isEqualTo(10);
        // The legacy origin path bypasses the retention floor; wallet coverage must fail closed.
        store.rollbackToPoint(Point.ORIGIN);
        assertThatThrownBy(() -> store.getAddressFirstSeen(A)).isInstanceOf(WalletIndexUnavailableException.class);
    }

    @Test void canonicalRollbackInvalidatesActiveScanBeforeAndAfterDerivedRollbackEvent() {
        apply(1, List.of(tx(1, List.of(), List.of(output(A)))), List.of());
        apply(2, List.of(tx(2, List.of(input(1)), List.of(output(B)))), List.of());
        var request = new WalletScanRequest(1, List.of(new WalletCredential("payment", "key", "ff".repeat(28))),
                WalletChainPoint.ORIGIN, null, List.of());
        try (var active = store.openWalletScan(request)) {
            active.next();
            chain.rollbackTo(new Point(10, hash(1)));
            assertThatThrownBy(active::next).isInstanceOf(WalletScanRollbackException.class);
            assertThatThrownBy(() -> store.getAddressFirstSeen(B)).isInstanceOf(WalletScanRollbackException.class);
            store.rollbackTo(new RollbackEvent(new Point(10, hash(1)), true));
            assertThat(store.getAddressFirstSeen(A).firstSeenSlot()).isEqualTo(10);
            assertThat(store.getAddressFirstSeen(B).firstSeenSlot()).isNull();
            assertThatThrownBy(active::next).isInstanceOf(WalletScanRollbackException.class);
            assertThat(active.finished()).isFalse();
        }
    }

    @Test void headerOnlyRollbackPreservesAppliedWalletCoverage() {
        apply(1, List.of(tx(1, List.of(), List.of(output(A)))), List.of());
        apply(2, List.of(tx(2, List.of(), List.of(output(B)))), List.of());
        var before = store.getAddressFirstSeen(A).coverage();
        chain.storeBlockHeader(HexFormat.of().parseHex(hash(3)), 3L, 30L, new byte[]{0});
        chain.storeBlockHeader(HexFormat.of().parseHex(hash(4)), 4L, 40L, new byte[]{0});
        chain.rollbackTo(new Point(30, hash(3)));
        assertThat(chain.getTip().getBlockNumber()).isEqualTo(2);
        assertThat(chain.getHeaderTip().getBlockNumber()).isEqualTo(3);
        assertThat(store.getAddressFirstSeen(A).coverage()).isEqualTo(before);
        assertThat(store.getAddressFirstSeen(B).firstSeenSlot()).isEqualTo(20);
    }

    @Test void restoringCheckpointWithoutWalletIndexesCannotFabricateCoverage() {
        Path snapshot = snapshots.resolve("without-wallet-indexes");
        try (var baseline = new DirectRocksDBChainState(snapshots.resolve("source").toString())) {
            var baselineStore = new DefaultUtxoStore(baseline, LoggerFactory.getLogger(getClass()),
                    Map.of(YanoPropertyKeys.Metrics.ENABLED, false));
            try {
                baselineStore.wireAllegraBootstrapRemoval(baseline);
                baselineStore.initializeFreshFullStateGenesis(Map.of(), 42, Map.of(), Map.of(), 0, 0, "00".repeat(32));
                byte[] blockHash = HexFormat.of().parseHex(hash(1));
                baseline.storeBlockHeader(blockHash, 1L, 10L, new byte[]{0});
                baseline.storeBlock(blockHash, 1L, 10L, new byte[]{0});
                baselineStore.applyBlock(new BlockAppliedEvent(Era.Babbage, 10, 1, hash(1),
                        Block.builder().era(Era.Babbage).transactionBodies(List.of(tx(1, List.of(), List.of(output(B)))))
                                .invalidTransactions(List.of()).build()));
                baseline.createSnapshot(snapshot.toString());
            } finally { baselineStore.close(); }
        }
        apply(1, List.of(tx(1, List.of(), List.of(output(A)))), List.of());
        var request = new WalletScanRequest(1, List.of(new WalletCredential("payment", "key", "ff".repeat(28))),
                WalletChainPoint.ORIGIN, null, List.of());
        try (var active = store.openWalletScan(request)) {
            active.next();
            chain.restoreFromSnapshot(snapshot.toString());
            store.reinitialize();
            assertThatThrownBy(active::next).isInstanceOf(WalletScanRollbackException.class);
            assertThat(store.getUtxosByAddress(B, 1, 10)).hasSize(1);
            assertThatThrownBy(() -> store.getAddressFirstSeen(B)).isInstanceOf(WalletIndexUnavailableException.class);
            assertThatThrownBy(() -> store.openWalletScan(request)).isInstanceOf(WalletIndexUnavailableException.class);
            apply(2, List.of(tx(2, List.of(), List.of(output(C)))), List.of());
            assertThatThrownBy(() -> store.getAddressFirstSeen(C)).isInstanceOf(WalletIndexUnavailableException.class);
            assertThatThrownBy(() -> store.openWalletScan(request)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("outside complete filter coverage");
        }
    }

    @Test void eachIndependentFeatureRejectsIncompleteUtxoStorage() {
        for (String flag : List.of(YanoPropertyKeys.WalletIndex.FIRST_SEEN_ENABLED, YanoPropertyKeys.WalletIndex.FILTERS_ENABLED)) {
            assertThatThrownBy(() -> new DefaultUtxoStore(chain, LoggerFactory.getLogger(getClass()), Map.of(
                    flag, true, YanoPropertyKeys.Utxo.ENABLED, false, YanoPropertyKeys.Metrics.ENABLED, false)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unfiltered UTxO");
            assertThatThrownBy(() -> new DefaultUtxoStore(chain, LoggerFactory.getLogger(getClass()), Map.of(
                    flag, true, YanoPropertyKeys.UtxoFilter.ENABLED, true, YanoPropertyKeys.Metrics.ENABLED, false)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unfiltered UTxO");
            var independent = new DefaultUtxoStore(chain, LoggerFactory.getLogger(getClass()),
                    Map.of(flag, true, YanoPropertyKeys.Metrics.ENABLED, false));
            try {
                independent.setFilterChain(new StorageFilterChain(List.of()));
                assertThatThrownBy(() -> independent.setFilterChain(new StorageFilterChain(List.of(new StorageFilter() {}))))
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("including plugins");
            } finally { independent.close(); }
        }
    }

    @Test void skippedApplyCannotPublishContinuousWalletHistory() {
        apply(1, List.of(tx(1, List.of(), List.of(output(A)))), List.of());
        byte[] skippedHash = HexFormat.of().parseHex(hash(2));
        chain.storeBlockHeader(skippedHash, 2L, 20L, new byte[]{0});
        chain.storeBlock(skippedHash, 2L, 20L, new byte[]{0});
        apply(3, List.of(tx(3, List.of(), List.of(output(B)))), List.of());
        assertThatThrownBy(() -> store.getAddressFirstSeen(A)).isInstanceOf(WalletIndexUnavailableException.class);
        assertThatThrownBy(() -> store.getAddressFirstSeen(B)).isInstanceOf(WalletIndexUnavailableException.class);
    }

    @Test void advancingAfterMissedSameHeightRollbackCannotHideOrphanedFirstSeen() {
        apply(1, List.of(tx(1, List.of(), List.of(output(A)))), List.of());
        byte[] replacement = HexFormat.of().parseHex(hash(99));
        chain.storeBlockHeader(replacement, 1L, 10L, new byte[]{0});
        chain.storeBlock(replacement, 1L, 10L, new byte[]{0});
        apply(2, List.of(tx(2, List.of(), List.of(output(B)))), List.of());
        assertThatThrownBy(() -> store.getAddressFirstSeen(A)).isInstanceOf(WalletIndexUnavailableException.class);
        assertThatThrownBy(() -> store.getAddressFirstSeen(B)).isInstanceOf(WalletIndexUnavailableException.class);
    }

    private boolean filterMatches(int block, int payment) throws Exception {
        byte[] stored = chain.rocks().db().get(chain.rocks().handle(WalletIndexCf.FILTERS), number(block));
        byte[] element = new WalletCredential("payment", "key", "%02x".formatted(payment).repeat(28)).filterElement();
        return CredentialFilter.matches(Arrays.copyOfRange(stored, 40, stored.length), List.of(element));
    }

    private void apply(int blockNumber, List<TransactionBody> transactions, List<Integer> invalid) {
        Block block = Block.builder().era(Era.Babbage).transactionBodies(transactions).invalidTransactions(invalid).build();
        byte[] blockHash = HexFormat.of().parseHex(hash(blockNumber));
        chain.storeBlockHeader(blockHash, (long) blockNumber, blockNumber * 10L, new byte[]{0});
        chain.storeBlock(blockHash, (long) blockNumber, blockNumber * 10L, new byte[]{0});
        store.applyBlock(new BlockAppliedEvent(Era.Babbage, blockNumber * 10L, blockNumber, hash(blockNumber), block));
    }

    private static TransactionBody tx(int id, List<TransactionInput> inputs, List<TransactionOutput> outputs) {
        return TransactionBody.builder().txHash(hash(id)).inputs(Set.copyOf(inputs)).outputs(outputs).build();
    }

    private static TransactionInput input(int tx) { return TransactionInput.builder().transactionId(hash(tx)).index(0).build(); }
    private static TransactionOutput output(String address) {
        return TransactionOutput.builder().address(address)
                .amounts(List.of(Amount.builder().unit("lovelace").quantity(BigInteger.valueOf(1_000_000)).build())).build();
    }
    private static byte[] number(long value) { return ByteBuffer.allocate(8).putLong(value).array(); }
    private static String hash(int value) { return "%064x".formatted(value); }
    private static String address(int value) {
        byte[] raw = new byte[57];
        Arrays.fill(raw, 1, 29, (byte) value);
        Arrays.fill(raw, 29, 57, (byte) 5);
        return new Address(raw).toBech32();
    }
}
