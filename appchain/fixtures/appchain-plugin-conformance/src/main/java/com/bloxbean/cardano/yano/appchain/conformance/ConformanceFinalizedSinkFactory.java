package com.bloxbean.cardano.yano.appchain.conformance;

import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSink;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSinkFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Harmless finalized-sink fixture used only by the native plugin conformance build. */
public final class ConformanceFinalizedSinkFactory implements FinalizedStreamSinkFactory {
    public static final String SCHEME = "conformance-sink";
    public static final String SINK_ID = "conformance-finalized-sink";

    @Override
    public String scheme() {
        return SCHEME;
    }

    @Override
    public List<FinalizedStreamSink> create(String chainId, Map<String, String> config) {
        List<FinalizedStreamSink> sinks = List.of(new FinalizedStreamSink() {
            private final AtomicBoolean firstCallback = new AtomicBoolean(true);

            @Override
            public String id() {
                ConformanceTcclProbe.requireCatalogFacade("finalized-sink identity");
                ConformanceTcclProbe.productCallback(firstCallback,
                        "finalized-sink identity");
                return SINK_ID;
            }

            @Override
            public boolean deliver(AppBlock block) {
                ConformanceTcclProbe.productCallback(firstCallback,
                        "finalized-sink delivery");
                return true;
            }

            @Override
            public String legacyCursorKey() {
                ConformanceTcclProbe.productCallback(firstCallback,
                        "finalized-sink legacy cursor");
                return null;
            }

            @Override
            public void close() {
                ConformanceTcclProbe.productCallback(firstCallback,
                        "finalized-sink close");
            }
        });
        ConformanceTcclProbe.poisonProviderCallback();
        return sinks;
    }
}
