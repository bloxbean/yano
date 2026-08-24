package com.bloxbean.cardano.yano.archive.core.hot;

import java.nio.file.Path;
import java.util.Map;

/** Service-provider boundary for optional hot-store engines. */
public interface HotHistoryStoreProvider {
    String engine();

    HotHistoryStore open(Path path, Map<String, String> properties);
}
