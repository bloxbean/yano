package com.bloxbean.cardano.yano.archive.core.hot;

import java.nio.file.Path;

class RocksDbHotHistoryStoreConformanceTest extends AbstractHotHistoryStoreConformanceTest {
    @Override
    protected HotHistoryStore open(Path path) {
        return new RocksDbHotHistoryStore(path);
    }
}
