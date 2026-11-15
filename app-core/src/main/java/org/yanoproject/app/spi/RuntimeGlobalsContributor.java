package org.yanoproject.app.spi;

import java.util.Map;

/**
 * CDI bean that adds entries to the runtime globals the application passes to Yano. The host merges
 * every contributor before it builds the runtime options. Startup fails when two contributors set the
 * same key or a contributor sets a key the host itself sets.
 */
public interface RuntimeGlobalsContributor {

    /** The globals to add, keyed by property name. */
    Map<String, Object> globals();
}
