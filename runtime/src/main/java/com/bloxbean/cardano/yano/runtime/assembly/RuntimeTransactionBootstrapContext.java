package com.bloxbean.cardano.yano.runtime.assembly;

import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.runtime.internal.RuntimeNode;
import com.bloxbean.cardano.yano.runtime.config.InMemoryDevnetGenesis;
import com.bloxbean.cardano.yano.runtime.tx.TransactionBootstrapContext;

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
