package org.yanoproject.api.plugin.spi;

/**
 * Host positions of an embedded owner's request-facing plugin products.
 *
 * <p>The host calls {@link #resume()} in registration order once plugin
 * callbacks are admitted for a runtime cycle, and {@link #sealAndAwait()} in
 * reverse order on every stop path, including startup rollback and an unsafe
 * drain. Both must be idempotent. Registration does not transfer close
 * ownership.</p>
 */
public interface PluginProducts {
    /** Activates or reopens the products for a starting runtime cycle. */
    void resume();

    /** Rejects new product work and waits for admitted work to finish. */
    void sealAndAwait();
}
