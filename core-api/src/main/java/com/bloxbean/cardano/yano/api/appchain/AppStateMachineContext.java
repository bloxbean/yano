package com.bloxbean.cardano.yano.api.appchain;

import java.util.Map;

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
}
