package com.bloxbean.cardano.yano.api.appchain;

import java.util.Map;
import java.util.Optional;

/**
 * Context handed to an {@link AppStateMachineProvider} when a chain creates its
 * state machine, so plugins can read their configuration (ADR app-layer/006).
 * The settings map carries the chain's dynamic plugin config — every
 * {@code yano.app-chain.<prefix>.*} key with the {@code yano.app-chain.} stem
 * stripped (e.g. {@code zk.circuits[0].vk-file}). A machine reads its own
 * namespaced sub-map (e.g. keys under {@code zk.}).
 */
public interface AppStateMachineContext {

    /** The chain this machine instance belongs to. */
    String chainId();

    /** Dynamic plugin settings (suffix-keyed, e.g. {@code zk.max-proofs-per-block}). */
    Map<String, String> settings();

    /**
     * Normalized and authenticated framework consensus profile (ADR-016).
     *
     * <p>The default preserves source/binary compatibility for contexts built
     * outside the Yano runtime. The normal runtime always supplies a value;
     * first-party machines that depend on framework limits fail construction
     * when it is absent.</p>
     */
    default Optional<AppChainConsensusProfile> consensusProfile() {
        return Optional.empty();
    }

    /**
     * Immutable height-versioned membership view for machines whose own
     * governance must bind one finalized membership epoch.
     */
    default Optional<AppChainMembershipView> membershipView() {
        return Optional.empty();
    }
}
