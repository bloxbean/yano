package com.bloxbean.cardano.yano.runtime.utxo;

import com.bloxbean.cardano.yano.api.archive.ConsumedOutputAddresses;

import java.util.HashMap;
import java.util.Map;

/** Captures input addresses while canonical UTXO records are available. */
final class ConsumedAddressCapture {
    private static final ConsumedAddressCapture DISABLED = new ConsumedAddressCapture(false);
    private final Map<String, String> addresses;

    private ConsumedAddressCapture(boolean enabled) {
        this.addresses = enabled ? new HashMap<>() : null;
    }

    static ConsumedAddressCapture create(boolean enabled) {
        return enabled ? new ConsumedAddressCapture(true) : DISABLED;
    }

    void recordSpent(String txHash, int outputIndex, UtxoCborCodec.StoredUtxo output) {
        if (addresses != null && output != null) {
            addresses.put(key(txHash, outputIndex), output.address);
        }
    }

    void recordCreated(String txHash, int outputIndex, String address) {
        if (addresses != null && address != null) {
            addresses.put(key(txHash, outputIndex), address);
        }
    }

    ConsumedOutputAddresses view() {
        if (addresses == null) return ConsumedOutputAddresses.NONE;
        return (txHash, outputIndex) -> addresses.get(key(txHash, outputIndex));
    }

    private static String key(String txHash, int outputIndex) {
        return txHash + '#' + outputIndex;
    }
}
