package com.bloxbean.cardano.yano.appchain.eutxo.testkit;

import java.util.Arrays;

/** Prints the public identity for the fixed, no-value M1 demonstration wallet. */
public final class EutxoNoFundsDemo {
    private EutxoNoFundsDemo() {
    }

    public static void main(String[] args) {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) 0x42);
        EutxoTestWallet wallet = EutxoTestWallet.fromSeed(seed);
        System.out.println("DEMO_ONLY_NO_REAL_FUNDS");
        System.out.println("address=" + wallet.address());
        System.out.println("genesisLovelace=100000000");
    }
}
