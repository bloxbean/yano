package com.bloxbean.cardano.yano.appchain.conformance;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observer;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1ObserverProvider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Harmless L1-observer fixture used only by the native plugin conformance build. */
public final class ConformanceL1ObserverProvider implements L1ObserverProvider {
    public static final String TYPE = "conformance-observer";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public L1Observer create(String observerId, Map<String, String> settings) {
        L1Observer observer = new L1Observer() {
            private final AtomicBoolean firstCallback = new AtomicBoolean(true);

            @Override
            public String observerId() {
                ConformanceTcclProbe.requireCatalogFacade("L1-observer identity");
                ConformanceTcclProbe.productCallback(firstCallback,
                        "L1-observer identity");
                return observerId;
            }

            @Override
            public List<L1Observation> observe(long slot, byte[] blockHash, Block block) {
                ConformanceTcclProbe.productCallback(firstCallback,
                        "L1-observer observation");
                return List.of();
            }

            @Override
            public Map<String, Object> status() {
                ConformanceTcclProbe.productCallback(firstCallback,
                        "L1-observer status");
                return Map.of();
            }
        };
        ConformanceTcclProbe.poisonProviderCallback();
        return observer;
    }
}
