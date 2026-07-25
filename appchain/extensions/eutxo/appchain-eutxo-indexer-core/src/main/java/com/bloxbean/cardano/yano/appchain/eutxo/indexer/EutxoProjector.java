package com.bloxbean.cardano.yano.appchain.eutxo.indexer;

import java.util.List;
import java.util.Objects;

/** Applies one canonical source block and its events as a single store transaction. */
public final class EutxoProjector {
    private final EutxoIndexStore store;

    public EutxoProjector(EutxoIndexStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public void apply(
            SourcePoint source,
            List<EutxoIndexEvent> events,
            IndexCoverage coverage
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(events, "events");
        IndexCheckpoint previous = store.checkpoint();
        long transactions = previous.transactionSequence();
        long deposits = previous.depositSequence();
        long withdrawals = previous.withdrawalSequence();
        try (EutxoIndexWrite write = store.begin(source)) {
            for (EutxoIndexEvent event : events) {
                write.apply(event);
                if (event instanceof EutxoIndexEvent.Transaction transaction) {
                    transactions = Math.max(transactions, transaction.sequence());
                } else if (event instanceof EutxoIndexEvent.Deposit deposit) {
                    deposits = Math.max(deposits, deposit.sequence());
                } else if (event instanceof EutxoIndexEvent.Withdrawal withdrawal) {
                    withdrawals = Math.max(withdrawals, withdrawal.sequence());
                }
            }
            write.commit(new IndexCheckpoint(
                    store.identity().digest(),
                    source,
                    transactions,
                    deposits,
                    withdrawals,
                    coverage));
        }
    }
}
