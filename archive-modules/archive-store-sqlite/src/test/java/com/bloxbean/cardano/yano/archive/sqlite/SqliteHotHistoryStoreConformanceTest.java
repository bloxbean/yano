package com.bloxbean.cardano.yano.archive.sqlite;

import com.bloxbean.cardano.yano.archive.core.hot.*;
import java.nio.file.Path;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SqliteHotHistoryStoreConformanceTest extends AbstractHotHistoryStoreConformanceTest {
    @Override protected HotHistoryStore open(Path path) {
        return new SqliteHotHistoryStore(path.resolve("hot-history.sqlite"));
    }

    @Test void providerIsDiscoverableWithoutApplicationCompileDependency() {
        assertThat(ServiceLoader.load(HotHistoryStoreProvider.class).stream()
                .map(ServiceLoader.Provider::get).map(HotHistoryStoreProvider::engine))
                .contains("sqlite");
    }
}
