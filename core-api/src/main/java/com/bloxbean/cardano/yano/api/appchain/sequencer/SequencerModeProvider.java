package com.bloxbean.cardano.yano.api.appchain.sequencer;

/**
 * ServiceLoader discovery for custom {@link SequencerMode}s (ADR 008.2 §2.7) —
 * same pattern as {@code AppStateMachineProvider}: implement, register in
 * {@code META-INF/services}, drop the jar in {@code plugins/} (or register
 * programmatically in library mode), select via
 * {@code yano.app-chain.sequencer.mode}.
 */
public interface SequencerModeProvider {

    String id();

    /**
     * Create a fresh mode instance owned by the requesting chain. The same
     * instance must not be returned by another invocation or provider.
     */
    SequencerMode create(SequencerContext context);
}
