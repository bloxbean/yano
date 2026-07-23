package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

/** Stable state-machine and wire identifiers for EUTxO clients and plugins. */
public final class EutxoContract {
    public static final String STATE_MACHINE_ID = "eutxo-ledger";
    public static final String TRANSACTION_TOPIC = "eutxo.transactions";

    private EutxoContract() {
    }
}
