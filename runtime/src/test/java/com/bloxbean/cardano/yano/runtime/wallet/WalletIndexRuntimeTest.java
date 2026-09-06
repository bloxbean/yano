package com.bloxbean.cardano.yano.runtime.wallet;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionInput;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.wallet.WalletCredential;
import com.bloxbean.cardano.yano.api.wallet.WalletChainPoint;
import com.bloxbean.cardano.yano.api.wallet.WalletIndexUnavailableException;
import com.bloxbean.cardano.yano.runtime.chain.DirectRocksDBChainState;
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
