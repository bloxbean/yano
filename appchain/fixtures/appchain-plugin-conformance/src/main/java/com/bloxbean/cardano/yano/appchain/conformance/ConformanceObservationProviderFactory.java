package com.bloxbean.cardano.yano.appchain.conformance;

import com.bloxbean.cardano.yano.api.appchain.observation.ObservationCandidate;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationProvider;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationProviderFactory;
import com.bloxbean.cardano.yano.api.appchain.observation.ObservationRequest;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Harmless observation-provider fixture used by native plugin conformance. */
public final class ConformanceObservationProviderFactory implements ObservationProviderFactory {
    public static final String TYPE = "conformance-observation";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public ObservationProvider create(String definitionId, Map<String, String> settings) {
        AtomicBoolean firstCallback = new AtomicBoolean(true);
        ObservationProvider provider = new ObservationProvider() {
            @Override
            public ObservationCandidate acquire(ObservationRequest request) {
                ConformanceTcclProbe.requireCatalogFacade("observation-provider acquisition");
                ConformanceTcclProbe.productCallback(firstCallback,
                        "observation-provider acquisition");
                return new ObservationCandidate(new byte[]{1}, new byte[]{2}, new byte[0],
                        new byte[]{3}, request.round().anchorType().code(),
                        request.round().dueAnchor());
            }

            @Override
            public void close() {
                ConformanceTcclProbe.productCallback(firstCallback,
                        "observation-provider close");
            }
        };
        ConformanceTcclProbe.poisonProviderCallback();
        return provider;
    }
}
