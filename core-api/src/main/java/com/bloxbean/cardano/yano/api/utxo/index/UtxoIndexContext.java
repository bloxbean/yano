package com.bloxbean.cardano.yano.api.utxo.index;

import com.bloxbean.cardano.yano.api.chain.ChainPoint;
import java.util.Map;
import java.util.function.Function;

/** Host services, not a service locator. Read scopes expire when the callback returns. */
public interface UtxoIndexContext {
    String id();
    String networkIdentity();
    Map<String, String> configuration();
    <T> T read(Function<ReadScope, T> query);

    interface ReadScope extends IndexReader {
        ChainPoint appliedPoint();
        long generation();
        /** False for gaps, callback failures, missing undo or a stale index. */
        boolean available();
    }
}
