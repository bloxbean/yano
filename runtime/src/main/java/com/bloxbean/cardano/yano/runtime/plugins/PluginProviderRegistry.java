package com.bloxbean.cardano.yano.runtime.plugins;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Immutable selector registry for the six typed app-chain plugin SPIs.
 *
 * <p>The manifested catalog implements this contract with lazy, cached
 * provider handles. The legacy adapter exists only for source-compatible
 * library constructors; normal runtime assembly supplies the catalog-owned
 * registry.</p>
 */
public interface PluginProviderRegistry {

    /** Resolve one provider by its exact, case-sensitive selector. */
    <P> Optional<P> find(Class<P> providerType, String selector);

    /** Available selectors in deterministic lexical order. */
    <P> List<String> names(Class<P> providerType);

    /**
     * Register the terminal cleanup signal for a product callback owner.
     * Catalog environments use these barriers to keep bundle lifecycle and
     * class-loader resources alive until every returned product is quiescent.
     */
    default void registerContributionCleanup(CompletableFuture<Void> completion) {
        // Direct/legacy registries do not own a closeable plugin environment.
    }

    /** True while a registered contribution can still invoke plugin code. */
    default boolean hasPendingContributionCleanup() {
        return false;
    }

    /**
     * Freeze cleanup registration for the current generation, wait for every
     * registered product owner, then seal and drain terminal close callbacks.
     */
    default void awaitContributionCleanup() {
        // No owned contribution lifetime in direct/legacy registries.
    }

    /**
     * Reopen ordinary callbacks, terminal cleanup callbacks and cleanup
     * registration for a new runtime cycle after the previous one is quiescent.
     */
    default void resumeContributionCallbacks() {
        // Direct/legacy registries do not own a callback lifecycle fence.
    }

    /**
     * Reject new ordinary plugin product/provider callbacks for the stopping
     * runtime cycle. Registered owners may still invoke terminal product close.
     */
    default void sealContributionCallbacks() {
        // Direct/legacy registries do not own a callback lifecycle fence.
    }

    /**
     * Reject lifecycle teardown initiated from a contribution callback. Such a
     * teardown would otherwise wait for the initiating callback itself.
     */
    default void requireContributionTeardownAllowed(String action) {
        // Direct/legacy registries do not own a callback lifecycle fence.
    }

    /** True while a callback admitted before the shutdown seal is still running. */
    default boolean hasPendingContributionCallbacks() {
        return false;
    }

    /** Wait for all callbacks admitted before the shutdown seal to return. */
    default void awaitContributionCallbacks() {
        // Direct/legacy registries do not own a callback lifecycle fence.
    }

    default <P> P require(Class<P> providerType, String selector) {
        return find(providerType, selector).orElseThrow(() ->
                new IllegalArgumentException("Unknown " + providerType.getSimpleName()
                        + " selector '" + selector + "' (available: "
                        + names(providerType) + ")"));
    }

    static PluginProviderRegistry empty() {
        return EmptyPluginProviderRegistry.INSTANCE;
    }
}
