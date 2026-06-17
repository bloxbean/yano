package com.bloxbean.cardano.yano.api;

/**
 * Optional block-producer control surface.
 *
 * <p>Default methods preserve compatibility for assemblies that do not include a
 * producer. Producer-specific implementations should override the relevant
 * methods.</p>
 */
public interface ProducerControl {
    default void startProducer() {
        throw new UnsupportedOperationException("Producer control is not available");
    }

    default void stopProducer() {
        throw new UnsupportedOperationException("Producer control is not available");
    }

    default void resetProducerToChainTip() {
        throw new UnsupportedOperationException("Producer control is not available");
    }

    default boolean isProducerRunning() {
        return false;
    }
}
