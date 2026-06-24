package com.bloxbean.cardano.yano.runtime.config;

import com.bloxbean.cardano.yaci.core.common.Constants;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenesisFileResolverTest {
    @Test
    void existingUserPathWins() throws Exception {
        var file = Files.createTempFile("yano-genesis", ".json");

        assertEquals(file.toString(), GenesisFileResolver.resolve(
                file.toString(), Constants.PREPROD_PROTOCOL_MAGIC, "shelley-genesis.json",
                Thread.currentThread().getContextClassLoader()));
    }

    @Test
    void knownProtocolMagicsMapToBundledDirectories() {
        assertEquals("mainnet", GenesisFileResolver.networkDirForMagic(Constants.MAINNET_PROTOCOL_MAGIC));
        assertEquals("preprod", GenesisFileResolver.networkDirForMagic(Constants.PREPROD_PROTOCOL_MAGIC));
        assertEquals("preview", GenesisFileResolver.networkDirForMagic(Constants.PREVIEW_PROTOCOL_MAGIC));
        assertEquals("sanchonet", GenesisFileResolver.networkDirForMagic(4));
        assertNull(GenesisFileResolver.networkDirForMagic(42));
    }

    @Test
    void bundledResourceIsExtractedForKnownNetwork() {
        String resolved = GenesisFileResolver.resolve(
                null, Constants.PREPROD_PROTOCOL_MAGIC, "shelley-genesis.json",
                Thread.currentThread().getContextClassLoader());

        assertTrue(resolved.endsWith("shelley-genesis.json"));
        assertTrue(Files.exists(java.nio.file.Path.of(resolved)));
    }

    @Test
    void bundledResourceIsExtractedWithDefaultClassLoader() {
        String resolved = GenesisFileResolver.resolve(
                null, Constants.PREPROD_PROTOCOL_MAGIC, "shelley-genesis.json");

        assertTrue(resolved.endsWith("shelley-genesis.json"));
        assertTrue(Files.exists(java.nio.file.Path.of(resolved)));
    }

    @Test
    void bundledResourceFallsBackWhenExplicitClassLoaderCannotSeeRuntimeResources() {
        ClassLoader isolated = new ClassLoader(null) {
        };
        Thread thread = Thread.currentThread();
        ClassLoader originalContextLoader = thread.getContextClassLoader();
        thread.setContextClassLoader(isolated);

        try {
            String resolved = GenesisFileResolver.resolve(
                    null, Constants.PREPROD_PROTOCOL_MAGIC, "shelley-genesis.json", isolated);

            assertTrue(resolved.endsWith("shelley-genesis.json"));
            assertTrue(Files.exists(java.nio.file.Path.of(resolved)));
        } finally {
            thread.setContextClassLoader(originalContextLoader);
        }
    }
}
