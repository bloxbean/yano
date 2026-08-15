package com.bloxbean.cardano.yano.archive.sqlite;

import com.bloxbean.cardano.yano.archive.core.hot.*;
import java.nio.file.Path;
import java.sql.DriverManager;
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

    @Test void resolverSeedUsesOneClusteredPrimaryKeyAndDoesNotEnterTheBlockIndex() throws Exception {
        Path database = temp.resolve("resolver-layout").resolve("hot-history.sqlite");
        try (HotHistoryStore ignored = new SqliteHotHistoryStore(database)) {
            // Schema installation is the behavior under test.
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            try (var statement = connection.prepareStatement(
                    "SELECT sql FROM sqlite_schema WHERE name='resolver_outputs'");
                 var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1))
                        .containsIgnoringCase("WITHOUT ROWID")
                        .doesNotContainIgnoringCase("namespace");
            }
            try (var statement = connection.prepareStatement(
                    "SELECT sql FROM sqlite_schema WHERE name='resolver_outputs_created'");
                 var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).containsIgnoringCase("WHERE source_kind = 'BLOCK'");
            }
            try (var statement = connection.prepareStatement(
                    "SELECT count(*) FROM sqlite_schema WHERE type='table' AND name='hot_track'");
                 var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isZero();
            }
        }
    }
}
