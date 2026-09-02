package com.bloxbean.cardano.yano.api.appchain.l1view;

import java.util.Map;

/**
 * ServiceLoader discovery for custom {@link L1Observer}s (ADR app-layer/
 * 008.4 §3.1) — same pattern as {@code SequencerModeProvider}: implement,
 * register in {@code META-INF/services}, drop the jar in {@code plugins/}
 * (or register programmatically in library mode). Observers are configured
 * per instance under {@code yano.app-chain.observers.<id>.*} with
 * {@code type} selecting the provider.
 */
public interface L1ObserverProvider {

    /** The {@code observers.<id>.type} value this provider handles. */
    String type();

    /**
     * Returns the complete deterministic identity for this configured observer.
     * Operational endpoints, credentials, paths, retry, and timeout settings
     * must not affect observation output and therefore must not be included.
     */
    default L1ObserverConsensusIdentity consensusIdentity(
            String observerId, Map<String, String> settings) {
        throw new UnsupportedOperationException(
                "Custom L1 observer providers must declare a consensus identity");
    }

    /**
     * @param observerId the configured instance id (config key segment)
     * @param settings   the {@code observers.<id>.*} settings (key = suffix)
     * @return a fresh observer owned by this configured instance; it must not
     *         be shared with another factory invocation or provider
     */
    L1Observer create(String observerId, Map<String, String> settings);
}
