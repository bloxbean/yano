package com.bloxbean.cardano.yano.wallet.core.tx;

import java.util.List;
import java.util.Optional;

public interface PendingTransactionStore {
    PendingTransaction save(PendingTransaction transaction);

    Optional<PendingTransaction> find(String txHash);

    List<PendingTransaction> list();

    default List<PendingTransaction> list(String walletId, String networkId) {
        return list().stream()
                .filter(tx -> walletId.equals(tx.walletId()))
                .filter(tx -> networkId.equals(tx.networkId()))
                .toList();
    }

    default List<PendingTransaction> listByNetwork(String networkId) {
        return list().stream()
                .filter(tx -> networkId.equals(tx.networkId()))
                .toList();
    }
}
