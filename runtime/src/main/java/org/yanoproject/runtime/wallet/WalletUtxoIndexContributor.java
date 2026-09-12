package org.yanoproject.runtime.wallet;

import org.yanoproject.api.chain.ChainPoint;
import org.yanoproject.api.genesis.GenesisUtxo;
import org.yanoproject.api.utxo.index.IndexWriter;
import org.yanoproject.api.utxo.index.UtxoChanges;
import org.yanoproject.api.utxo.index.UtxoIndexContributor;
import org.yanoproject.api.wallet.WalletCredential;
import org.yanoproject.runtime.chain.ArchiveChainStateCapabilities;
import org.yanoproject.runtime.utxo.index.IndexStorage;
import org.rocksdb.RocksDBException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Wallet feature policy lives here. The host knows only the contributor transaction contract. */
public final class WalletUtxoIndexContributor implements UtxoIndexContributor {
    public static final String ID = "wallet";
    private final Supplier<WalletIndexStore> store;
    private final Supplier<ArchiveChainStateCapabilities> canonical;

    public WalletUtxoIndexContributor(Supplier<WalletIndexStore> store,
                                      Supplier<ArchiveChainStateCapabilities> canonical) {
        this.store = store;
        this.canonical = canonical;
    }

    public static Map<String, String> tables() {
        return Map.of(WalletIndexCf.FIRST_SEEN, WalletIndexCf.FIRST_SEEN,
                WalletIndexCf.FILTERS, WalletIndexCf.FILTERS, WalletIndexCf.META, WalletIndexCf.META,
                WalletIndexCf.UNDO, WalletIndexCf.UNDO, WalletIndexCf.GENESIS, WalletIndexCf.GENESIS,
                WalletIndexCf.ERRORS, WalletIndexCf.ERRORS);
    }

    @Override public void stageApply(UtxoChanges changes, IndexWriter writer) {
        WalletQueryService.requireCanonicalPoint(canonical.get(), changes.previous());
        WalletQueryService.requireCanonicalPoint(canonical.get(), changes.point());
        List<String> addresses = new ArrayList<>();
        var credentials = new HashSet<WalletCredential>();
        String failure = null;
        boolean filters = store.get().filtersEnabled();
        for (var tx : changes.transactions()) {
            for (var output : tx.created()) {
                addresses.add(output.address());
                if (filters) {
                    try { WalletCredentials.address(output.address(), credentials); }
                    catch (RuntimeException error) { if (failure == null) failure = "Output credential extraction failed: " + tx.hash() + ": " + error.getMessage(); }
                }
            }
            if (!filters) continue;
            for (var input : tx.consumed()) {
                try {
                    if (input.resolution() != UtxoChanges.Resolution.RESOLVED) throw new IllegalStateException("Unresolved effective input");
                    WalletCredentials.address(input.address(), credentials);
                } catch (RuntimeException error) { if (failure == null) failure = "Input credential extraction failed: " + input.outpoint() + ": " + error.getMessage(); }
            }
            try { WalletCredentials.subjects(tx.subjects(), credentials); }
            catch (RuntimeException error) { if (failure == null) failure = "Event credential extraction failed: " + tx.hash() + ": " + error.getMessage(); }
            if (failure == null && tx.subjectError() != null) failure = "Event credential extraction failed: " + tx.hash() + ": " + tx.subjectError();
        }
        byte[] filter = filters ? CredentialFilter.encode(Arrays.copyOf(HexFormat.of().parseHex(changes.point().blockHash()), 16),
                credentials.stream().map(WalletCredential::filterElement).toList()) : null;
        try { store.get().stageBlock(writer, changes.previous(), changes.point(), addresses, filter, null, failure); }
        catch (RocksDBException error) { throw new IndexStorage.StorageFailure(error); }
    }

    @Override public void stageGenesis(String identity, List<GenesisUtxo> outputs, IndexWriter writer) {
        try {
            store.get().stageGenesis(writer, identity, outputs.stream().map(GenesisUtxo::address).toList());
            store.get().stageScanGenesis(writer, outputs);
        } catch (RocksDBException error) { throw new IndexStorage.StorageFailure(error); }
    }

    @Override public void stageRollback(ChainPoint target, IndexWriter writer) {
        try { store.get().stageRollback(writer, target); }
        catch (RocksDBException error) { throw new IndexStorage.StorageFailure(error); }
    }

    @Override public void stagePruneUndo(ChainPoint point, IndexWriter writer) {
        try { store.get().stagePruneUndo(writer, point.blockNumber()); }
        catch (RocksDBException error) { throw new IndexStorage.StorageFailure(error); }
    }
}
