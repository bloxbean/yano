package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yano.api.appchain.observation.ObservationTopics;

/** Classification of reserved app-chain topics that belong to node diffusion, not blocks. */
final class AppChainSystemTopics {
    static final String CONSENSUS_DIFFUSION_PREFIX = "~consensus/";
    static final String ANCHOR_DIFFUSION_PREFIX = "~anchor/";
    static final String BRIDGE_DIFFUSION_PREFIX =
            com.bloxbean.cardano.yano.api.appchain.l1view
                    .BridgeDiffusionHandler.TOPIC_PREFIX;

    private AppChainSystemTopics() {
    }

    static boolean isDiffusionOnly(String topic) {
        return topic != null && (topic.startsWith(CONSENSUS_DIFFUSION_PREFIX)
                || topic.startsWith(ANCHOR_DIFFUSION_PREFIX)
                || topic.startsWith(BRIDGE_DIFFUSION_PREFIX)
                || ObservationTopics.isDiffusionOnly(topic));
    }

    static boolean isUnknownObservationTopic(String topic) {
        return ObservationTopics.isReserved(topic) && !ObservationTopics.isAllowed(topic);
    }
}
