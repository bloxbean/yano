package com.bloxbean.cardano.yano.runtime.utxo;

import java.nio.charset.StandardCharsets;

final class StakeBalanceIndexKeys {
    static final byte[] READY_MARKER = "stake_balance_index_ready".getBytes(StandardCharsets.UTF_8);
    static final byte[] READY_VERSION = {1};

    private StakeBalanceIndexKeys() {
    }

    static boolean isCurrent(byte[] value) {
        return java.util.Arrays.equals(READY_VERSION, value);
    }
}
