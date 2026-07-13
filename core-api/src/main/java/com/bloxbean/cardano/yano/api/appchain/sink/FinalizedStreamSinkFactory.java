package com.bloxbean.cardano.yano.api.appchain.sink;

import java.util.List;
import java.util.Map;

/**
 * ServiceLoader SPI for {@link FinalizedStreamSink} plugins (ADR app-layer/006
 * E3.2), e.g. a Kafka bridge. Discovered on the plugin classloader; each
 * factory produces zero or more sinks for a chain from its configuration
 * (the {@code yano.app-chain.sinks.<scheme>.*} sub-map).
 */
public interface FinalizedStreamSinkFactory {

    /** Config scheme this factory handles, e.g. {@code "kafka"}. */
    String scheme();

    /**
     * Create sinks for {@code chainId} from {@code config} (keys with the
     * {@code yano.app-chain.sinks.<scheme>.} prefix stripped). Return an empty
     * list when nothing is configured.
     */
    List<FinalizedStreamSink> create(String chainId, Map<String, String> config);
}
