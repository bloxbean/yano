package com.bloxbean.cardano.yano.runtime.utxo;

import java.nio.charset.StandardCharsets;

final class StakeBalanceIndexKeys {
    static final byte[] READY_MARKER = "stake_balance_index_ready".getBytes(StandardCharsets.UTF_8);

    private StakeBalanceIndexKeys() {
    }
}
