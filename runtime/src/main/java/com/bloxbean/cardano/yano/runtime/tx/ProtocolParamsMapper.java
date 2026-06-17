package com.bloxbean.cardano.yano.runtime.tx;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.api.model.ProtocolParamsSnapshot;

import java.io.IOException;

/**
 * Transaction-facing protocol-parameter mapper.
 */
public final class ProtocolParamsMapper {
    private ProtocolParamsMapper() {
    }

    public static ProtocolParams fromNodeProtocolParam(String json) throws IOException {
        return com.bloxbean.cardano.yano.runtime.blockproducer.ProtocolParamsMapper.fromNodeProtocolParam(json);
    }

    public static ProtocolParams fromEpochParamProvider(EpochParamProvider provider, int epoch) {
        return com.bloxbean.cardano.yano.runtime.blockproducer.ProtocolParamsMapper
                .fromEpochParamProvider(provider, epoch);
    }

    public static ProtocolParams fromSnapshot(ProtocolParamsSnapshot snapshot) {
        return com.bloxbean.cardano.yano.runtime.blockproducer.ProtocolParamsMapper.fromSnapshot(snapshot);
    }
}
