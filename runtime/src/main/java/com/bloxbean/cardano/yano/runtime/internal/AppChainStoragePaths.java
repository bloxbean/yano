package com.bloxbean.cardano.yano.runtime.internal;

import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;

import java.nio.file.Path;

/** Resolves the node-local app-chain storage root independently of L1 RocksDB state. */
final class AppChainStoragePaths {
    private AppChainStoragePaths() {
    }

    /** Relative app-chain paths use the process working directory, like L1 storage paths. */
    static Path resolve(String l1StoragePath, String configuredAppChainPath) {
        Path l1Path = l1Path(l1StoragePath);
        String value;
        if (configuredAppChainPath == null) {
            value = YanoConfig.DEFAULT_APP_CHAIN_STORAGE_PATH;
        } else {
            value = configuredAppChainPath.trim();
            if (value.isEmpty()) {
                throw new IllegalArgumentException(
                        YanoPropertyKeys.AppChain.STORAGE_PATH + " must not be blank");
            }
        }

        Path result = Path.of(value).toAbsolutePath().normalize();

        if (result.equals(l1Path)) {
            throw new IllegalArgumentException(
                    YanoPropertyKeys.AppChain.STORAGE_PATH
                            + " must be different from yano.storage.path");
        }
        return result;
    }

    private static Path l1Path(String l1StoragePath) {
        String value = l1StoragePath == null || l1StoragePath.isBlank()
                ? "./chainstate"
                : l1StoragePath.trim();
        return Path.of(value).toAbsolutePath().normalize();
    }
}
