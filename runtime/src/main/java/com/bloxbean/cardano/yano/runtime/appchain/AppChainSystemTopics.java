package com.bloxbean.cardano.yano.runtime.appchain;

/** Classification of reserved app-chain topics that belong to node diffusion, not blocks. */
final class AppChainSystemTopics {
    static final String CONSENSUS_DIFFUSION_PREFIX = "~consensus/";
    static final String ANCHOR_DIFFUSION_PREFIX = "~anchor/";

    private AppChainSystemTopics() {
    }

    static boolean isDiffusionOnly(String topic) {
        return topic != null && (topic.startsWith(CONSENSUS_DIFFUSION_PREFIX)
                || topic.startsWith(ANCHOR_DIFFUSION_PREFIX));
    }
}
