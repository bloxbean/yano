package com.bloxbean.cardano.yano.wallet.bridge;

public enum BridgeMethod {
    ENABLE("enable"),
    GET_NETWORK_ID("getNetworkId"),
    GET_BALANCE("getBalance"),
    GET_UTXOS("getUtxos"),
    GET_CHANGE_ADDRESS("getChangeAddress"),
    GET_REWARD_ADDRESSES("getRewardAddresses"),
    SIGN_TX("signTx"),
    SUBMIT_TX("submitTx");

    private final String cip30Name;

    BridgeMethod(String cip30Name) {
        this.cip30Name = cip30Name;
    }

    public String cip30Name() {
        return cip30Name;
    }
}
