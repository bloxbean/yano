package com.bloxbean.cardano.yano.archive.core.dataset;

import java.util.List;

public record ArchiveBlockFacts(List<TransactionFact> transactions, List<AccountEventFact> accountEvents) {
    public ArchiveBlockFacts {
        transactions = List.copyOf(transactions);
        accountEvents = List.copyOf(accountEvents);
    }
}
