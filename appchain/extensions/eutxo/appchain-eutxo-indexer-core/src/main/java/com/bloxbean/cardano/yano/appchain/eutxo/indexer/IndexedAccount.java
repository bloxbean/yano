package com.bloxbean.cardano.yano.appchain.eutxo.indexer;

import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoTransactionSummary;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

public record IndexedAccount(
        String address,
        BigInteger lovelace,
        List<EutxoTransactionSummary.Entry> utxos,
        List<String> activityTransactionIds
) {
    public IndexedAccount {
        address = Objects.requireNonNull(address, "address");
        lovelace = Objects.requireNonNull(lovelace, "lovelace");
        utxos = List.copyOf(utxos);
        activityTransactionIds = List.copyOf(activityTransactionIds);
    }
}
