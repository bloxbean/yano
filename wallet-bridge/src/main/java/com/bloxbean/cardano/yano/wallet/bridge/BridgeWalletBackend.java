package com.bloxbean.cardano.yano.wallet.bridge;

import java.util.List;

public interface BridgeWalletBackend {
    int networkId();

    String balanceCborHex();

    List<String> utxosCborHex();

    String changeAddressHex();

    List<String> rewardAddressHexes();

    BridgeSignTxResult signTx(String txCborHex, boolean partialSign);

    String submitTx(String txCborHex);
}
