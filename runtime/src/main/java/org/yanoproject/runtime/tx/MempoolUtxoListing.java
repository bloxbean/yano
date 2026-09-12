package org.yanoproject.runtime.tx;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.yanoproject.api.utxo.UtxoState;
import org.yanoproject.api.utxo.UtxoReadView;
import org.yanoproject.api.utxo.model.Utxo;
import org.yanoproject.api.util.AddressKeyUtil;
import org.yanoproject.runtime.chain.MemPool;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

/** Shared opt-in listing; never holds the mempool admission lock during storage reads. */
final class MempoolUtxoListing {
    private static final Semaphore QUERIES = new Semaphore(2);
    private static final Comparator<Utxo> ORDER = Comparator.comparingLong(Utxo::slot)
            .thenComparing(u -> u.outpoint().txHash()).thenComparingInt(u -> u.outpoint().index());

    private MempoolUtxoListing() { }

    static List<Utxo> list(UtxoState state, MemPool pool, String query, boolean credential,
                           String asset, int page, int count, boolean descending) {
        if (state == null || !state.isEnabled()) {
            throw new IllegalStateException("UTXO state disabled");
        }
        if (page < 1 || count < 1 || count > UtxoReadView.MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("count must be between 1 and " + UtxoReadView.MAX_PAGE_SIZE);
        }
        if (!QUERIES.tryAcquire()) throw new IllegalStateException("UTxO queries busy; retry");
        try {
            return read(state, pool, query, credential, asset, page, count, descending);
        } finally {
            QUERIES.release();
        }
    }

    private static List<Utxo> read(UtxoState state, MemPool pool, String query, boolean credential,
                                   String asset, int page, int count, boolean descending) {
        if (!credential) query = normalizeAddress(query);
        byte[] key = credential ? paymentKey(query) : AddressKeyUtil.addrHash28(query);
        if (key == null) return List.of();

        try (UtxoReadView view = state.openUtxoReadView(query, credential, descending)) {
            MemPool.UtxoOverlay overlay = pool.utxoOverlay(key, credential);
            List<Utxo> pending = new ArrayList<>();
            for (Utxo output : overlay.outputs()) {
                if (matchesAsset(output, asset)
                        && view.getUtxo(output.outpoint()).isEmpty()) {
                    // Prefer canonical metadata when confirmation raced with the mempool snapshot.
                    pending.add(output);
                }
            }
            pending.sort(descending ? ORDER.reversed() : ORDER);
            Page result = new Page((long) (page - 1) * count, count);
            if (descending) {
                for (Utxo output : pending) if (result.accept(output)) return finish(view, result);
            }
            for (var next = view.next(); next.isPresent(); next = view.next()) {
                Utxo output = next.get();
                if (!overlay.spent().contains(output.outpoint()) && matchesAsset(output, asset)
                        && result.accept(output)) return finish(view, result);
            }
            if (!descending) {
                for (Utxo output : pending) if (result.accept(output)) break;
            }
            return finish(view, result);
        }
    }

    private static List<Utxo> finish(UtxoReadView view, Page page) {
        view.checkCurrent();
        return page.outputs;
    }

    private static boolean matchesAsset(Utxo output, String asset) {
        return asset == null || "lovelace".equalsIgnoreCase(asset)
                || output.assets() != null && output.assets().stream()
                .anyMatch(a -> asset.equals(a.policyId() + a.assetName()));
    }

    private static final class Page {
        private long skip;
        private final int count;
        private final List<Utxo> outputs = new ArrayList<>();

        private Page(long skip, int count) {
            this.skip = skip;
            this.count = count;
        }

        private boolean accept(Utxo output) {
            if (skip > 0) skip--;
            else outputs.add(output);
            return outputs.size() == count;
        }
    }

    private static byte[] paymentKey(String query) {
        try {
            byte[] hash = HexUtil.decodeHexString(query);
            if (hash.length == 28) return hash;
        } catch (Exception ignored) {
            // Address form is supported by the canonical credential listing too.
        }
        return AddressKeyUtil.paymentCred28(query);
    }

    private static String normalizeAddress(String query) {
        try {
            return new Address(HexUtil.decodeHexString(query)).toBech32();
        } catch (Exception ignored) {
            return query;
        }
    }
}
