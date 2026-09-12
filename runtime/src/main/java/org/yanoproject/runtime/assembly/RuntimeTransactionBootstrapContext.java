package org.yanoproject.runtime.assembly;

import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import org.yanoproject.api.EpochParamProvider;
import org.yanoproject.api.account.LedgerStateProvider;
import org.yanoproject.api.config.YanoConfig;
import org.yanoproject.api.utxo.UtxoState;
import org.yanoproject.runtime.internal.RuntimeNode;
import org.yanoproject.runtime.config.InMemoryDevnetGenesis;
import org.yanoproject.runtime.tx.TransactionBootstrapContext;

import java.util.Objects;

/**
 * Adapts the assembled runtime node to the transaction-service bootstrap SPI.
 */
final class RuntimeTransactionBootstrapContext implements TransactionBootstrapContext {
    private final RuntimeNode runtimeNode;
    private final YanoConfig config;
    private final InMemoryDevnetGenesis inMemoryGenesis;

    RuntimeTransactionBootstrapContext(RuntimeNode runtimeNode, YanoConfig config, InMemoryDevnetGenesis inMemoryGenesis) {
        this.runtimeNode = Objects.requireNonNull(runtimeNode, "runtimeNode");
        this.config = Objects.requireNonNull(config, "config");
        this.inMemoryGenesis = inMemoryGenesis;
    }

    @Override
    public YanoConfig config() {
        return config;
    }

    @Override
    public UtxoState utxoState() {
        return runtimeNode.getUtxoState();
    }

    @Override
    public LedgerStateProvider ledgerStateProvider() {
        return runtimeNode.getLedgerStateProvider();
    }

    @Override
    public EpochParamProvider epochParamProvider() {
        return runtimeNode.getEpochParamProvider();
    }

    @Override
    public ChainTip localTip() {
        return runtimeNode.getLocalTip();
    }

    @Override
    public long resolvedGenesisTimestamp() {
        return runtimeNode.getResolvedGenesisTimestamp();
    }

    @Override
    public InMemoryDevnetGenesis inMemoryDevnetGenesis() {
        return inMemoryGenesis;
    }

    RuntimeNode runtime() {
        return runtimeNode;
    }
}
