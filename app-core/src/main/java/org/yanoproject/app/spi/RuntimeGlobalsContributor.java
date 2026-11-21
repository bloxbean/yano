package org.yanoproject.app.spi;

import java.util.Map;

/**
 * CDI bean that adds entries to the runtime globals the application passes to Yano. The host merges
 * every contributor before it builds the runtime options.
 *
 * <p>Startup fails, naming the contributor, when two contributors set the same key, when a contributor
 * sets a key the host itself set in this run, or when {@link #globals()} returns {@code null}. The keys
 * the host sets depend on the run: a Cardano node sets its node, ledger and plugin globals, an
 * {@code app-ledger} host only {@code yano.plugins.required} when configured. A contributor therefore
 * uses keys of its own namespace only.</p>
 */
public interface RuntimeGlobalsContributor {

    /** The globals to add, keyed by property name; never {@code null}. */
    Map<String, Object> globals();
}
