package com.bloxbean.cardano.yano.wallet.core.config;

import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.common.model.Networks;

import java.util.Arrays;

public enum WalletNetwork {
    DEVNET("devnet", false),
    PREVIEW("preview", false),
    PREPROD("preprod", false),
    MAINNET("mainnet", true);

    private final String id;
    private final boolean production;

    WalletNetwork(String id, boolean production) {
        this.id = id;
        this.production = production;
    }

    public String id() {
        return id;
    }

    public boolean production() {
        return production;
    }

    public Network toCclNetwork() {
        return switch (this) {
            case DEVNET, PREPROD -> Networks.preprod();
            case PREVIEW -> Networks.preview();
            case MAINNET -> Networks.mainnet();
        };
    }

    public static WalletNetwork fromId(String id) {
        return Arrays.stream(values())
                .filter(network -> network.id.equalsIgnoreCase(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported wallet network: " + id));
    }
}
