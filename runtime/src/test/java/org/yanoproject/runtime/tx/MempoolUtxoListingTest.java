package org.yanoproject.runtime.tx;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.yanoproject.api.utxo.UtxoState;
import org.yanoproject.api.utxo.UtxoReadView;
import org.yanoproject.api.utxo.model.AssetAmount;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.api.utxo.model.Utxo;
import org.yanoproject.api.util.AddressKeyUtil;
import org.yanoproject.runtime.chain.MemPool;
import org.yanoproject.runtime.chain.DefaultMemPool;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Arrays;
import java.util.Set;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MempoolUtxoListingTest {
    private static final String ADDRESS =
            "addr_test1qz2fxv2umyhttkxyxp8x0dlpdt3k6cwng5pxj3jhsydzer3jcu5d8ps7zex2k2xt3uqxgjqnnj83ws8lhrn648jjxtwq2ytjqp";
    private static final String POLICY = "aa".repeat(28);
    private final TestState state = new TestState();
    private MemPool.UtxoOverlay snapshot = new MemPool.UtxoOverlay(List.of(), Set.of());
    private final MemPool pool = new DefaultMemPool() {
        @Override
        public UtxoOverlay utxoOverlay(byte[] subject, boolean credential) {
            return new UtxoOverlay(snapshot.outputs().stream().filter(u -> Arrays.equals(subject,
                    credential ? AddressKeyUtil.paymentCred28(u.address()) : AddressKeyUtil.addrHash28(u.address())))
                    .toList(), snapshot.spent());
        }
    };

    @Test
    void excludesClaimsDeduplicatesAndPaginatesAfterMerging() {
        Utxo spent = output(1, ADDRESS, 1, "block", false);
        Utxo confirmed = output(2, ADDRESS, 2, "block", false);
        Utxo pending = output(3, ADDRESS, 0, null, false);
        Utxo unrelated = output(4, "other-address", 0, null, false);
        state.outputs = List.of(spent, confirmed);
        snapshot = new MemPool.UtxoOverlay(
                List.of(output(2, ADDRESS, 0, null, false), pending, unrelated), Set.of(spent.outpoint()));
        assertThat(list(null, 1, 1, false)).containsExactly(confirmed);
        assertThat(list(null, 2, 1, false)).containsExactly(pending);
        assertThat(list(null, 3, 1, false)).isEmpty();
        assertThat(list(null, 1, 2, true)).containsExactly(pending, confirmed);
        assertThatThrownBy(() -> list(null, Integer.MAX_VALUE, Integer.MAX_VALUE, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void filtersAssetsBeforePaginationAndKeepsLovelaceAsAllOutputs() {
        Utxo ada = output(1, ADDRESS, 1, "block", false);
        Utxo token = output(2, ADDRESS, 2, "block", true);
        Utxo pendingToken = output(3, ADDRESS, 0, null, true);
        state.outputs = List.of(ada, token);
        snapshot = new MemPool.UtxoOverlay(List.of(pendingToken), Set.of());
        assertThat(list(POLICY + "01", 1, 1, false)).containsExactly(token);
        assertThat(list(POLICY + "01", 2, 1, false)).containsExactly(pendingToken);
        assertThat(list("lovelace", 1, 10, false)).containsExactly(ada, token, pendingToken);
        assertThat(list("unknown", 1, 10, false)).isEmpty();
    }

    @Test
    void acceptsCredentialHexAddressAndRawAddressForms() {
        Utxo pending = output(1, ADDRESS, 0, null, false);
        String credential = HexUtil.encodeHexString(AddressKeyUtil.paymentCred28(ADDRESS));
        String rawAddress = HexUtil.encodeHexString(new Address(ADDRESS).getBytes());
        snapshot = new MemPool.UtxoOverlay(List.of(pending), Set.of());
        for (String query : List.of(credential, credential.toUpperCase(), ADDRESS, rawAddress)) {
            assertThat(MempoolUtxoListing.list(state, pool, query, true, null, 1, 20, false))
                    .as("credential query %s", query).containsExactly(pending);
        }
        assertThat(MempoolUtxoListing.list(state, pool, rawAddress, false, null, 1, 20, false))
                .containsExactly(pending);
        assertThat(MempoolUtxoListing.list(state, pool, "ff".repeat(28), true, null, 1, 20, false)).isEmpty();
    }

    @Test
    void batchesSkipFilteredRowsAndStopAsSoonAsPageIsFull() {
        state.outputs = IntStream.rangeClosed(1, 3000)
                .mapToObj(i -> output(i, ADDRESS, i, "block", i > 256)).toList();
        snapshot = new MemPool.UtxoOverlay(List.of(), state.outputs.subList(256, 260).stream()
                .map(Utxo::outpoint).collect(Collectors.toSet()));
        assertThat(list(POLICY + "01", 1, 20, false)).isEqualTo(state.outputs.subList(260, 280));
        assertThat(state.reads).isEqualTo(280);
        state.reads = 0;
        assertThat(list(null, 1, 20, true)).isEqualTo(state.outputs.reversed().subList(0, 20));
        assertThat(state.reads).isEqualTo(20);
        state.reads = 0;
        assertThat(list(null, 1, 20, false)).isEqualTo(state.outputs.subList(0, 20));
        assertThat(state.reads).isEqualTo(20);
    }

    @Test
    void pageCrossesConfirmedPendingBoundaryInBothDirections() {
        state.outputs = IntStream.rangeClosed(1, 256)
                .mapToObj(i -> output(i, ADDRESS, i, "block", false)).toList();
        Utxo pending = output(257, ADDRESS, 0, null, false);
        snapshot = new MemPool.UtxoOverlay(List.of(pending), Set.of());
        assertThat(list(null, 86, 3, false)).containsExactly(state.outputs.getLast(), pending);
        assertThat(list(null, 1, 3, true)).containsExactly(pending,
                state.outputs.getLast(), state.outputs.get(254));
        assertThat(list(null, 2, 3, true)).isEqualTo(state.outputs.reversed().subList(2, 5));
    }

    @Test
    void pendingOnlyDescendingPageDoesNotReadConfirmedBatches() {
        Utxo pending = output(1, ADDRESS, 0, null, false);
        snapshot = new MemPool.UtxoOverlay(List.of(pending), Set.of());
        assertThat(list(null, 1, 1, true)).containsExactly(pending);
        assertThat(state.reads).isZero();
    }

    @Test
    void sortsMultiplePendingOutputsAndKeepsSnapshotDuringConfirmation() {
        Utxo first = output(1, ADDRESS, 0, null, false);
        Utxo last = output(3, ADDRESS, 0, null, false);
        snapshot = new MemPool.UtxoOverlay(List.of(last, first), Set.of());
        state.afterOpen = () -> state.outputs = List.of(output(1, ADDRESS, 1, "confirmed", false));
        assertThat(list(null, 1, 20, false)).containsExactly(first, last);
        state.outputs = List.of();
        assertThat(list(null, 1, 20, true)).containsExactly(last, first);
        assertThat(state.closes).isEqualTo(2);
    }

    @Test
    void chainMovementFailsClosedForFullEmptyAndPendingOnlyResponses() {
        state.changed = true;
        Utxo pending = output(1, ADDRESS, 0, null, false);
        assertThatThrownBy(() -> list(null, 1, 1, false)).hasMessageContaining("chain moved");
        snapshot = new MemPool.UtxoOverlay(List.of(pending), Set.of());
        assertThatThrownBy(() -> list(null, 1, 1, true)).hasMessageContaining("chain moved");
        state.outputs = List.of(output(2, ADDRESS, 1, "block", false));
        assertThatThrownBy(() -> list(null, 1, 1, false)).hasMessageContaining("chain moved");
        assertThat(state.closes).isEqualTo(3);
    }

    @Test
    void disabledStorageDoesNotMasqueradeAsAnEmptyWallet() {
        state.enabled = false;
        assertThatThrownBy(() -> list(null, 1, 20, false)).isInstanceOf(IllegalStateException.class);
    }

    private List<Utxo> list(String asset, int page, int count, boolean descending) {
        return MempoolUtxoListing.list(state, pool, ADDRESS, false, asset, page, count, descending);
    }

    private static Utxo output(int id, String address, long slot, String blockHash, boolean token) {
        return new Utxo(new Outpoint(String.format("%064x", id), 0), address, BigInteger.TEN,
                token ? List.of(new AssetAmount(POLICY, "01", BigInteger.ONE)) : List.of(),
                null, null, null, null, false, slot, slot, blockHash);
    }

    private static class TestState implements UtxoState {
        List<Utxo> outputs = List.of();
        boolean enabled = true;
        int reads;
        int closes;
        Runnable afterOpen = () -> { };
        boolean changed;

        public List<Utxo> getUtxosByAddress(String query, int page, int count) {
            return getUtxosByAddress(query, page, count, false);
        }

        public List<Utxo> getUtxosByPaymentCredential(String query, int page, int count) {
            return getUtxosByAddress(query, page, count);
        }

        public List<Utxo> getUtxosByAddress(String query, int page, int count, boolean descending) {
            return (descending ? outputs.reversed() : outputs).stream()
                    .skip((long) (page - 1) * count).limit(count).toList();
        }

        public List<Utxo> getUtxosByPaymentCredential(String query, int page, int count, boolean descending) {
            return getUtxosByAddress(query, page, count, descending);
        }

        public Optional<Utxo> getUtxo(Outpoint outpoint) {
            return outputs.stream().filter(u -> u.outpoint().equals(outpoint)).findFirst();
        }

        @Override
        public UtxoReadView openUtxoReadView(String query, boolean credential, boolean descending) {
            List<Utxo> stable = List.copyOf(descending ? outputs.reversed() : outputs);
            afterOpen.run();
            return new UtxoReadView() {
                int index;
                public Optional<Utxo> next() {
                    reads++;
                    return index < stable.size() ? Optional.of(stable.get(index++)) : Optional.empty();
                }
                public Optional<Utxo> getUtxo(Outpoint key) {
                    return stable.stream().filter(u -> u.outpoint().equals(key)).findFirst();
                }
                public void close() { closes++; }
                public void checkCurrent() {
                    if (changed) throw new IllegalStateException("chain moved");
                }
            };
        }

        public boolean isEnabled() { return enabled; }
    }
}
