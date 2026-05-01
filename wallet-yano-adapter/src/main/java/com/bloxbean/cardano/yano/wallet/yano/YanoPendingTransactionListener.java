package com.bloxbean.cardano.yano.wallet.yano;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.events.api.DomainEventListener;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.events.RollbackEvent;
import com.bloxbean.cardano.yano.wallet.core.tx.PendingTransactionStore;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public class YanoPendingTransactionListener {
    private final PendingTransactionStore pendingTransactions;
    private final String networkId;

    public YanoPendingTransactionListener(PendingTransactionStore pendingTransactions, String networkId) {
        this.pendingTransactions = Objects.requireNonNull(pendingTransactions, "pendingTransactions is required");
        if (networkId == null || networkId.isBlank()) {
            throw new IllegalArgumentException("networkId is required");
        }
        this.networkId = networkId;
    }

    @DomainEventListener(order = 160)
    public void onBlockApplied(BlockAppliedEvent event) {
        if (event == null || event.block() == null) {
            return;
        }

        Block block = event.block();
        if (block.getTransactionBodies() == null || block.getTransactionBodies().isEmpty()) {
            return;
        }

        Set<Integer> invalidIndexes = block.getInvalidTransactions() == null
                ? Set.of()
                : new HashSet<>(block.getInvalidTransactions());
        for (int txIndex = 0; txIndex < block.getTransactionBodies().size(); txIndex++) {
            if (invalidIndexes.contains(txIndex)) {
                continue;
            }

            TransactionBody tx = block.getTransactionBodies().get(txIndex);
            if (tx == null || tx.getTxHash() == null || tx.getTxHash().isBlank()) {
                continue;
            }

            pendingTransactions.find(tx.getTxHash())
                    .filter(pending -> networkId.equals(pending.networkId()))
                    .filter(pending -> pending.awaitsConfirmation())
                    .ifPresent(pending -> pendingTransactions.save(pending.markConfirmed(
                            event.slot(),
                            event.blockNumber(),
                            event.blockHash())));
        }
    }

    @DomainEventListener(order = 160)
    public void onRollback(RollbackEvent event) {
        if (event == null || event.target() == null) {
            return;
        }

        long targetSlot = event.target().getSlot();
        pendingTransactions.listByNetwork(networkId).stream()
                .filter(transaction -> transaction.confirmedAfter(targetSlot))
                .forEach(transaction -> pendingTransactions.save(transaction.markRolledBack(targetSlot)));
    }
}
