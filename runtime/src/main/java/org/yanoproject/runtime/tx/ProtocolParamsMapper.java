package org.yanoproject.runtime.tx;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import org.yanoproject.api.EpochParamProvider;
import org.yanoproject.api.model.ProtocolParamsSnapshot;

import java.io.IOException;

/**
 * Transaction-facing protocol-parameter mapper.
 */
public final class ProtocolParamsMapper {
    private ProtocolParamsMapper() {
    }

    public static ProtocolParams fromNodeProtocolParam(String json) throws IOException {
        return org.yanoproject.runtime.blockproducer.ProtocolParamsMapper.fromNodeProtocolParam(json);
    }

    public static ProtocolParams fromEpochParamProvider(EpochParamProvider provider, int epoch) {
        return org.yanoproject.runtime.blockproducer.ProtocolParamsMapper
                .fromEpochParamProvider(provider, epoch);
    }

    public static ProtocolParams fromSnapshot(ProtocolParamsSnapshot snapshot) {
        return org.yanoproject.runtime.blockproducer.ProtocolParamsMapper.fromSnapshot(snapshot);
    }
}
