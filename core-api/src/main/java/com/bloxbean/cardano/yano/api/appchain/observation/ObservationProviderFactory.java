package com.bloxbean.cardano.yano.api.appchain.observation;

import java.util.Map;

/** Typed plugin contribution for an operator-approved observation adapter. */
public interface ObservationProviderFactory {
    /** Exact operator selector used in observations.providers.&lt;id&gt;.type. */
    String type();

    ObservationProvider create(String definitionId, Map<String, String> operationalSettings);
}
