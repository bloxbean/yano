package org.yanoproject.runtime.wallet;

import com.bloxbean.cardano.client.address.Address;
import org.yanoproject.runtime.genesis.AvvmAddressConverter;
import org.yanoproject.api.chain.ChainPoint;
import org.yanoproject.api.wallet.WalletIndexUnavailableException;
import org.yanoproject.runtime.chain.DirectRocksDBChainState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.WriteBatch;
import org.yanoproject.runtime.utxo.index.IndexStorage;
import org.rocksdb.WriteOptions;

import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletIndexStoreTest {
    @TempDir Path directory;
    private DirectRocksDBChainState chain;
    private WalletIndexStore store;
    private static final String A = address(1, 2);
    private static final String B = address(1, 3);
    private static final String C = address(4, 5);
    private static final ChainPoint P1 = new ChainPoint(1, 10, "11".repeat(32));
    private static final ChainPoint P2 = new ChainPoint(2, 20, "22".repeat(32));

    @BeforeEach void open() {
        chain = new DirectRocksDBChainState(directory.toString());
        store = new WalletIndexStore(chain.rocks(), true, true);
    }

    @AfterEach void close() { chain.close(); }

    @Test void failedAddressDoesNotDiscardOtherRowsAndErrorsSurviveRestartAndRollback() throws Exception {
        genesis(List.of());
        apply(ChainPoint.ORIGIN, P1, List.of(A));
        apply(P1, P2, List.of("not-an-address", B));
        ChainPoint p3 = new ChainPoint(3, 30, "33".repeat(32));
        apply(P2, p3, List.of(C));
        assertThat(chain.rocks().db().get(chain.rocks().handle(WalletIndexCf.FIRST_SEEN),
                WalletIndexStore.addressBytes(B))).isEqualTo(ByteBuffer.allocate(8).putLong(20).array());
        assertThat(chain.rocks().db().get(chain.rocks().handle(WalletIndexCf.FIRST_SEEN),
                WalletIndexStore.addressBytes(C))).isNotNull();
        assertThatThrownBy(() -> store.firstSeen(B, p3)).isInstanceOf(WalletIndexUnavailableException.class);
        assertThat(store.coverage(WalletIndexStore.FILTERS, p3).from()).isEqualTo(ChainPoint.ORIGIN);
        assertThat(store.readFilters(-1, 3, 10)).extracting(WalletIndexStore.FilterRecord::error)
                .containsExactly(null, "Address decoding failed: not-an-address", null);

        chain.close();
        chain = new DirectRocksDBChainState(directory.toString());
        store = new WalletIndexStore(chain.rocks(), true, true);
        assertThat(store.readFilters(1, 2, 10).getFirst().error()).contains("not-an-address");
        rollback(P1);
        assertThat(store.firstSeen(B, P1).firstSeenSlot()).isNull();
        assertThat(store.firstSeen(A, P1).coverage().available()).isTrue();
        ChainPoint replacement = new ChainPoint(2, 21, "44".repeat(32));
        apply(P1, replacement, List.of(B));
        assertThat(store.readFilters(1, 2, 10).getFirst().error()).isNull();
        assertThat(store.firstSeen(B, replacement).firstSeenSlot()).isEqualTo(21);
    }

    @Test void filterFailureKeepsEarlierCoverageAndDoesNotPoisonSubsequentBlocks() throws Exception {
        genesis(List.of());
        apply(ChainPoint.ORIGIN, P1, List.of(A));
        try (WriteBatch batch = new WriteBatch(); var writer = new IndexStorage(chain::rocks, "wallet", WalletUtxoIndexContributor.tables()).open(batch); WriteOptions options = new WriteOptions()) {
            store.stageBlock(writer, P1, P2, List.of(B), emptyFilter(), null, "unresolved input");
            chain.rocks().db().write(options, batch);
        }
        ChainPoint p3 = new ChainPoint(3, 30, "33".repeat(32));
        apply(P2, p3, List.of(C));
        assertThat(store.coverage(WalletIndexStore.FILTERS, p3).completeFromOrigin()).isTrue();
        assertThat(store.readFilters(-1, 1, 10).getFirst().error()).isNull();
        assertThat(store.readFilters(1, 2, 10).getFirst().error()).isEqualTo("unresolved input");
        assertThat(store.readFilters(2, 3, 10).getFirst().error()).isNull();
        assertThat(store.firstSeen(B, p3).firstSeenSlot()).isEqualTo(20);
        rollback(P1);
        apply(P1, P2, List.of(B));
        assertThat(store.readFilters(1, 2, 10).getFirst().error()).isNull();
    }

    @Test void abandonedBatchPublishesNeitherFailureNorPartialIndex() throws Exception {
        genesis(List.of());
        try (WriteBatch batch = new WriteBatch(); var writer = new IndexStorage(chain::rocks, "wallet", WalletUtxoIndexContributor.tables()).open(batch)) {
            store.stageBlock(writer, ChainPoint.ORIGIN, P1, List.of("not-an-address", A), emptyFilter(), "failure");
        }
        assertThat(store.readFilters(-1, 1, 10)).isEmpty();
        assertThat(store.firstSeen(A, ChainPoint.ORIGIN).coverage().available()).isTrue();
        apply(ChainPoint.ORIGIN, P1, List.of(A));
        assertThat(store.readFilters(-1, 1, 10).getFirst().error()).isNull();
    }

    @Test void corruptFirstSeenValuesCannotProduceAuthoritativeSlots() throws Exception {
        genesis(List.of(A));
        for (byte[] invalid : List.of(new byte[7], new byte[9], ByteBuffer.allocate(8).putLong(-1).array(), ByteBuffer.allocate(8).putLong(1).array())) {
            chain.rocks().db().put(chain.rocks().handle(WalletIndexCf.FIRST_SEEN), WalletIndexStore.addressBytes(A), invalid);
            assertThatThrownBy(() -> store.firstSeen(A, ChainPoint.ORIGIN)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void byronGenesisFirstSeenSurvivesSubsequentOutputs() throws Exception {
        String byron = AvvmAddressConverter.convertAvvmToByronAddress(
                Base64.getEncoder().encodeToString(new byte[32])).orElseThrow();
        genesis(List.of(byron));
        assertThat(store.firstSeen(byron, ChainPoint.ORIGIN).firstSeenSlot()).isZero();
        apply(ChainPoint.ORIGIN, P1, List.of(byron));
        assertThat(store.firstSeen(byron, P1).firstSeenSlot()).isZero();
        rollback(ChainPoint.ORIGIN);
        assertThat(store.firstSeen(byron, ChainPoint.ORIGIN).firstSeenSlot()).isZero();
    }

    @Test void completeGenesisSlotZeroAndExactAddressIdentity() throws Exception {
        genesis(List.of(A));
        assertThat(store.firstSeen(A, ChainPoint.ORIGIN).firstSeenSlot()).isZero();
        assertThat(store.firstSeen(B, ChainPoint.ORIGIN).firstSeenSlot()).isNull();
        apply(ChainPoint.ORIGIN, P1, List.of(B, B));
        assertThat(store.firstSeen(A, P1).firstSeenSlot()).isZero();
        assertThat(store.firstSeen(B, P1).firstSeenSlot()).isEqualTo(10);
        assertThat(store.firstSeen(C, P1).firstSeenSlot()).isNull();
        assertThat(store.firstSeen(B, P1).coverage().identity()).isEqualTo("test-genesis");
    }

    @Test void rollbackPreservesEarlierOccurrenceAndAllowsReplacement() throws Exception {
        genesis(List.of());
        apply(ChainPoint.ORIGIN, P1, List.of(A));
        apply(P1, P2, List.of(A, B));
        rollback(P1);
        assertThat(store.firstSeen(A, P1).firstSeenSlot()).isEqualTo(10);
        assertThat(store.firstSeen(B, P1).firstSeenSlot()).isNull();
        ChainPoint replacement = new ChainPoint(2, 21, "33".repeat(32));
        apply(P1, replacement, List.of(B));
        assertThat(store.firstSeen(B, replacement).firstSeenSlot()).isEqualTo(21);
        rollback(ChainPoint.ORIGIN);
        assertThat(store.firstSeen(A, ChainPoint.ORIGIN).firstSeenSlot()).isNull();
        assertThat(store.firstSeen(B, ChainPoint.ORIGIN).firstSeenSlot()).isNull();
    }

    @Test void incompleteEnablementCannotReturnObservedSlotAsFirst() throws Exception {
        apply(P1, P2, List.of(A));
        assertThatThrownBy(() -> store.firstSeen(A, P2)).isInstanceOf(WalletIndexUnavailableException.class);
        assertThatThrownBy(() -> store.firstSeen(B, P2)).isInstanceOf(WalletIndexUnavailableException.class);
        assertThat(store.coverage(WalletIndexStore.FILTERS, P2).available()).isTrue();
        assertThat(store.coverage(WalletIndexStore.FILTERS, P2).completeFromOrigin()).isFalse();
        assertThat(store.coverage(WalletIndexStore.FILTERS, P2).from()).isEqualTo(P2);
    }

    @Test void restartAndDisableReenableWithoutGapPreserveCoverage() throws Exception {
        genesis(List.of());
        apply(ChainPoint.ORIGIN, P1, List.of(A));
        chain.close();
        open();
        assertThat(store.firstSeen(A, P1).firstSeenSlot()).isEqualTo(10);
        WalletIndexStore disabled = new WalletIndexStore(chain.rocks(), false, false);
        assertThatThrownBy(() -> disabled.firstSeen(A, P1)).isInstanceOf(WalletIndexUnavailableException.class);
        store = new WalletIndexStore(chain.rocks(), true, true);
        assertThat(store.firstSeen(A, P1).firstSeenSlot()).isEqualTo(10);
    }

    @Test void disabledIntervalInvalidatesCoverageAndDoesNotWrite() throws Exception {
        genesis(List.of());
        apply(ChainPoint.ORIGIN, P1, List.of(A));
        store = new WalletIndexStore(chain.rocks(), false, false);
        apply(P1, P2, List.of(B));
        store = new WalletIndexStore(chain.rocks(), true, true);
        assertThatThrownBy(() -> store.firstSeen(A, P2)).isInstanceOf(WalletIndexUnavailableException.class);
        ChainPoint p3 = new ChainPoint(3, 30, "33".repeat(32));
        apply(P2, p3, List.of(C));
        assertThatThrownBy(() -> store.firstSeen(C, p3)).isInstanceOf(WalletIndexUnavailableException.class);
        assertThat(store.coverage(WalletIndexStore.FILTERS, p3).from()).isEqualTo(p3);
    }

    @Test void uncommittedBatchDoesNotAdvanceCoverageOrLeakInsertions() throws Exception {
        genesis(List.of());
        try (WriteBatch batch = new WriteBatch(); var writer = new IndexStorage(chain::rocks, "wallet", WalletUtxoIndexContributor.tables()).open(batch)) {
            store.stageBlock(writer, ChainPoint.ORIGIN, P1, List.of(A), emptyFilter(), null);
        }
        assertThat(store.firstSeen(A, ChainPoint.ORIGIN).firstSeenSlot()).isNull();
        apply(ChainPoint.ORIGIN, P1, List.of(A));
        assertThat(store.firstSeen(A, P1).firstSeenSlot()).isEqualTo(10);
    }

    @Test void invalidAddressIsNotAnUnusedAddress() {
        assertThatThrownBy(() -> store.firstSeen("not-an-address", ChainPoint.ORIGIN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void missingIntermediateUndoCannotRestoreFalseCompleteness() throws Exception {
        genesis(List.of());
        apply(ChainPoint.ORIGIN, P1, List.of(A));
        apply(P1, P2, List.of(B));
        ChainPoint p3 = new ChainPoint(3, 30, "33".repeat(32));
        apply(P2, p3, List.of(C));
        chain.rocks().db().delete(chain.rocks().handle(WalletIndexCf.UNDO),
                ByteBuffer.allocate(9).put(WalletIndexStore.FIRST_SEEN).putLong(2).array());
        rollback(ChainPoint.ORIGIN);
        assertThatThrownBy(() -> store.firstSeen(B, ChainPoint.ORIGIN))
                .isInstanceOf(WalletIndexUnavailableException.class);
    }

    private void genesis(List<String> addresses) throws Exception {
        try (WriteBatch batch = new WriteBatch(); var writer = new IndexStorage(chain::rocks, "wallet", WalletUtxoIndexContributor.tables()).open(batch); WriteOptions options = new WriteOptions()) {
            store.stageGenesis(writer, "test-genesis", addresses);
            chain.rocks().db().write(options, batch);
        }
    }

    private void apply(ChainPoint previous, ChainPoint point, List<String> addresses) throws Exception {
        try (WriteBatch batch = new WriteBatch(); var writer = new IndexStorage(chain::rocks, "wallet", WalletUtxoIndexContributor.tables()).open(batch); WriteOptions options = new WriteOptions()) {
            store.stageBlock(writer, previous, point, addresses, emptyFilter(), null);
            chain.rocks().db().write(options, batch);
        }
    }

    private void rollback(ChainPoint point) throws Exception {
        try (WriteBatch batch = new WriteBatch(); var writer = new IndexStorage(chain::rocks, "wallet", WalletUtxoIndexContributor.tables()).open(batch); WriteOptions options = new WriteOptions()) {
            store.stageRollback(writer, point);
            chain.rocks().db().write(options, batch);
        }
    }

    private static byte[] emptyFilter() { return CredentialFilter.encode(new byte[16], List.of()); }

    private static String address(int payment, int stake) {
        byte[] bytes = new byte[57];
        Arrays.fill(bytes, 1, 29, (byte) payment);
        Arrays.fill(bytes, 29, 57, (byte) stake);
        return new Address(bytes).toBech32();
    }
}
