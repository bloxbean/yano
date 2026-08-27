package com.bloxbean.cardano.yano.archive.core.dataset;

import java.util.List;

public record ArchiveBlockFacts(List<TransactionFact> transactions, List<AccountEventFact> accountEvents,
                                List<AddressTransactionFact> addressTransactions) {
    public ArchiveBlockFacts {
        transactions = List.copyOf(transactions);
        accountEvents = List.copyOf(accountEvents);
        addressTransactions = List.copyOf(addressTransactions);
    }

    public ArchiveBlockFacts(List<TransactionFact> transactions, List<AccountEventFact> accountEvents) {
        this(transactions, accountEvents, List.of());
    }
}
