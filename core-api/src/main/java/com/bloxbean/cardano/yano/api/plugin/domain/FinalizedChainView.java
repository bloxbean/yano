package com.bloxbean.cardano.yano.api.plugin.domain;

import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppQueryResult;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentIdentity;

import java.util.Optional;

/** Read-only, finalized chain surface available to local read-model plugins. */
public interface FinalizedChainView {
    String chainId();

    long tipHeight();

    Optional<AppBlock> block(long height);

    AppQueryResult query(String path, byte[] request);

    Optional<StateCommitmentIdentity> stateCommitmentIdentity();

    AutoCloseable subscribe(FinalizedBlockListener listener);

    @FunctionalInterface
    interface FinalizedBlockListener {
        void onFinalized(AppBlock block, byte[] blockHash);
    }
}
