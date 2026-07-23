package com.bloxbean.cardano.yano.api.plugin.operations;

import java.util.List;

/** Lifecycle-owned source whose callbacks run only behind the runtime sampler fence. */
public interface PluginHealthSource extends AutoCloseable {
    /** Complete fixed check schema, frozen by the runtime on activation. */
    List<PluginHealthCheckDescriptor> checks();

    /** Current reports; the runtime validates exact descriptor identity. */
    PluginHealthSnapshot snapshot();

    @Override
    void close();
}
