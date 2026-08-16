package com.bloxbean.cardano.yano.archive.ducklake;

import java.sql.Connection;
import java.sql.SQLException;

/** Loads only build-packaged, signed extensions; implementations must never INSTALL. */
@FunctionalInterface
public interface DuckDbExtensionLoader {
    void load(Connection connection) throws SQLException;

    static DuckDbExtensionLoader none() {
        return connection -> { };
    }
}
