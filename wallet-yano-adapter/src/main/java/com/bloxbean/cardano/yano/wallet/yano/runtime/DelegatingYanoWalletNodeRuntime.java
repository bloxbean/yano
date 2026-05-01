package com.bloxbean.cardano.yano.wallet.yano.runtime;

import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yano.api.NodeAPI;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.api.model.NodeStatus;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;

import java.util.Objects;

public class DelegatingYanoWalletNodeRuntime implements WalletNodeRuntime {
    private final NodeAPI nodeAPI;

    public DelegatingYanoWalletNodeRuntime(NodeAPI nodeAPI) {
        this.nodeAPI = Objects.requireNonNull(nodeAPI, "nodeAPI is required");
    }

    public NodeAPI nodeAPI() {
        return nodeAPI;
    }

    @Override
    public void start() {
        nodeAPI.start();
    }

    @Override
    public void stop() {
        nodeAPI.stop();
    }

    @Override
    public boolean isRunning() {
        return nodeAPI.isRunning();
    }

    @Override
    public boolean isSyncing() {
        return nodeAPI.isSyncing();
    }

    @Override
    public NodeStatus status() {
        return nodeAPI.getStatus();
    }

    @Override
    public ChainTip localTip() {
        return nodeAPI.getLocalTip();
    }

    @Override
    public UtxoState utxoState() {
        return nodeAPI.getUtxoState();
    }

    @Override
    public LedgerStateProvider ledgerStateProvider() {
        return nodeAPI.getLedgerStateProvider();
    }

    @Override
    public String protocolParameters() {
        return nodeAPI.getProtocolParameters();
    }

    @Override
    public String submitTransaction(byte[] txCbor) {
        return nodeAPI.submitTransaction(txCbor);
    }

    @Override
    public void registerListeners(Object... listeners) {
        nodeAPI.registerListeners(listeners);
    }
}
