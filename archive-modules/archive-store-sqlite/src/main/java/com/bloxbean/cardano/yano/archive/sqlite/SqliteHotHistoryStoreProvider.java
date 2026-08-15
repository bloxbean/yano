package com.bloxbean.cardano.yano.archive.sqlite;

import com.bloxbean.cardano.yano.archive.core.hot.*;
import java.nio.file.Path;
import java.util.Map;

public final class SqliteHotHistoryStoreProvider implements HotHistoryStoreProvider {
    @Override public String engine() { return "sqlite"; }
    @Override public HotHistoryStore open(Path path, Map<String, String> properties) {
        return new SqliteHotHistoryStore(path);
    }
}
