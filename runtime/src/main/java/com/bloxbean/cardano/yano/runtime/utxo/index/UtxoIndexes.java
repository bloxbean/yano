package com.bloxbean.cardano.yano.runtime.utxo.index;

import com.bloxbean.cardano.yano.api.chain.ChainPoint;
import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import com.bloxbean.cardano.yano.api.utxo.index.IndexRequirements;
import com.bloxbean.cardano.yano.api.utxo.index.UtxoIndexContext;
import com.bloxbean.cardano.yano.api.utxo.index.UtxoIndexContributor;
import com.bloxbean.cardano.yano.api.utxo.index.UtxoIndexContributorProvider;
import com.bloxbean.cardano.yano.runtime.chain.ArchiveChainStateCapabilities;
import com.bloxbean.cardano.yano.runtime.db.RocksDbSupplier;
import com.bloxbean.cardano.yano.runtime.wallet.WalletIndexStore;
import com.bloxbean.cardano.yano.runtime.wallet.WalletQueryService;
import com.bloxbean.cardano.yano.runtime.wallet.WalletUtxoIndexContributor;
import org.slf4j.Logger;

import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Composition root for UTxO indexes. Feature selection is not part of block application. */
public final class UtxoIndexes {
    private final UtxoIndexRegistry registry;
    private final WalletQueryService walletQueries;
    private final RocksDbSupplier supplier;
    private final boolean firstSeen;
    private final boolean filters;
    private WalletIndexStore wallet;

    public UtxoIndexes(RocksDbSupplier supplier, Object lock, Supplier<ChainPoint> applied,
                       LongSupplier generation, Map<String, Object> config, Logger log) {
        this.supplier = supplier;
        Map<String, Object> values = config == null ? Map.of() : config;
        firstSeen = bool(values, YanoPropertyKeys.WalletIndex.FIRST_SEEN_ENABLED);
        filters = bool(values, YanoPropertyKeys.WalletIndex.FILTERS_ENABLED);
        wallet = new WalletIndexStore(supplier.rocks(), firstSeen, filters);
        registry = new UtxoIndexRegistry(supplier::rocks, lock, applied, generation,
                String.valueOf(values.getOrDefault(YanoPropertyKeys.Remote.PROTOCOL_MAGIC, "unspecified")), log);
        if (wallet.enabled()) {
            registry.registerBuiltin(new UtxoIndexContributorProvider() {
                @Override public String id() { return WalletUtxoIndexContributor.ID; }
                @Override public IndexRequirements requirements() { return new IndexRequirements(filters, filters, true); }
                @Override public UtxoIndexContributor create(UtxoIndexContext context) {
                    if (wallet.hasStoredMetadata() && !context.read(UtxoIndexContext.ReadScope::available)) {
                        log.warn("Existing wallet index history is unavailable under the contributor safety gate; "
                                + "a fresh sync is required when upgrading from the pre-contributor format");
                    }
                    return new WalletUtxoIndexContributor(() -> wallet,
                            () -> supplier instanceof ArchiveChainStateCapabilities canonical ? canonical : null);
                }
            }, WalletUtxoIndexContributor.tables());
        }
        walletQueries = new WalletQueryService(lock, supplier, () -> wallet, applied, generation,
                registry::requireReadable,
                () -> {
                    if (wallet.enabled()) registry.requireAvailable(WalletUtxoIndexContributor.ID);
                });
    }

    public UtxoIndexRegistry registry() { return registry; }
    public WalletQueryService walletQueries() { return walletQueries; }

    public void reinitialize() {
        wallet = new WalletIndexStore(supplier.rocks(), firstSeen, filters);
        registry.reinitialize();
    }

    private static boolean bool(Map<String, Object> config, String key) {
        return Boolean.parseBoolean(String.valueOf(config.getOrDefault(key, false)));
    }
}
