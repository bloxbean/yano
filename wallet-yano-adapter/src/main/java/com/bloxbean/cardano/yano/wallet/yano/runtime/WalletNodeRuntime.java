package com.bloxbean.cardano.yano.wallet.yano.runtime;

import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yano.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yano.api.model.NodeStatus;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;

public interface WalletNodeRuntime {
    void start();

    void stop();

    boolean isRunning();

    boolean isSyncing();

    NodeStatus status();

    ChainTip localTip();

    UtxoState utxoState();

    LedgerStateProvider ledgerStateProvider();

    String protocolParameters();

    String submitTransaction(byte[] txCbor);

    void registerListeners(Object... listeners);
}
