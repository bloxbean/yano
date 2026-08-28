package com.bloxbean.cardano.yano.runtime.utxo;

import com.bloxbean.cardano.client.address.Address;

import java.util.Arrays;

final class UtxoTestAddresses {
    private UtxoTestAddresses() {
    }

    static String enterprise(int fill) {
        byte[] address = new byte[29];
        address[0] = 0x60; // key-hash enterprise address on testnet network id 0
        Arrays.fill(address, 1, address.length, (byte) fill);
        return new Address(address).toBech32();
    }
}
