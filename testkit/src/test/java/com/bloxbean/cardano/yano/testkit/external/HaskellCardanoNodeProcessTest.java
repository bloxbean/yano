package com.bloxbean.cardano.yano.testkit.external;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HaskellCardanoNodeProcessTest {
    @TempDir
    Path tempDir;

    @Test
    void defaultVersionTracksLatestHardForkCompatibleNode() {
        assertEquals("11.0.1", HaskellCardanoNodeProcess.DEFAULT_VERSION);
    }

    @Test
    void configurationUsesRelativeGenesisPaths() {
        HaskellCardanoNodeProcess process = new HaskellCardanoNodeProcess(
                tempDir, "11.0.1", tempDir.resolve("cache"));

        String config = process.buildConfigurationJson();

        assertTrue(config.contains("\"ShelleyGenesisFile\": \"./haskell-genesis/shelley-genesis.json\""));
        assertTrue(config.contains("\"ByronGenesisFile\": \"./haskell-genesis/byron-genesis.json\""));
        assertTrue(config.contains("\"AlonzoGenesisFile\": \"./haskell-genesis/alonzo-genesis.json\""));
        assertTrue(config.contains("\"ConwayGenesisFile\": \"./haskell-genesis/conway-genesis.json\""));
        assertTrue(config.contains("\"DijkstraGenesisFile\": \"./haskell-genesis/dijkstra-genesis.json\""));
        assertTrue(config.contains("\"TestConwayHardForkAtEpoch\": 0"));
    }

    @Test
    void topologyUsesYanoN2nPort() {
        HaskellCardanoNodeProcess process = new HaskellCardanoNodeProcess(
                tempDir, "11.0.1", tempDir.resolve("cache"));

        String topology = process.buildTopologyJson(30_001);

        assertTrue(topology.contains("\"port\": 30001"));
        assertTrue(topology.contains("\"bootstrapPeers\""));
        assertTrue(topology.contains("\"localRoots\""));
    }

    @Test
    void latestSyncedSlotParsesLastChainExtendedSlot() {
        HaskellCardanoNodeProcess process = new HaskellCardanoNodeProcess(
                tempDir, "11.0.1", tempDir.resolve("cache"));
        process.appendLogLine("Chain extended, new tip at slot 10");
        process.appendLogLine("Chain extended something slot 7");
        process.appendLogLine("Chain extended, new tip at slot 42");
        process.appendLogLine("Chain extended after rollback slot 30");

        assertEquals(30, process.latestSyncedSlot());
        assertEquals(30, process.getLatestSyncedSlot());
    }

    @Test
    void configurationForNode11IncludesDijkstraGenesis() {
        HaskellCardanoNodeProcess process = new HaskellCardanoNodeProcess(
                tempDir, "11.0.1", tempDir.resolve("cache"));

        String config = process.buildConfigurationJson();

        assertTrue(config.contains("\"DijkstraGenesisFile\": \"./haskell-genesis/dijkstra-genesis.json\""));
    }

    @Test
    void node11WritesDefaultDijkstraGenesisWhenMissing() throws Exception {
        HaskellCardanoNodeProcess process = new HaskellCardanoNodeProcess(
                tempDir, "11.0.1", tempDir.resolve("cache"));

        process.ensureDijkstraGenesisIfNeeded();

        String dijkstra = java.nio.file.Files.readString(
                process.genesisDir().resolve(YanoGenesisFiles.DIJKSTRA_GENESIS_FILE));
        assertTrue(dijkstra.contains("\"maxRefScriptSizePerBlock\": 1048576"));
        assertTrue(dijkstra.contains("\"refScriptCostMultiplier\": 1.2"));
    }

    @Test
    void releaseAssetsPreferLegacyNamesFor10AndArchQualifiedNamesFor11() {
        HaskellCardanoNodeProcess node10 = new HaskellCardanoNodeProcess(
                tempDir, "10.5.2", tempDir.resolve("cache"));
        HaskellCardanoNodeProcess node11 = new HaskellCardanoNodeProcess(
                tempDir, "11.0.1", tempDir.resolve("cache"));

        assertTrue(node10.releaseAssetNames().getFirst()
                .matches("cardano-node-10\\.5\\.2-(macos|linux)\\.tar\\.gz"));
        assertTrue(node11.releaseAssetNames().getFirst()
                .matches("cardano-node-11\\.0\\.1-(macos|linux)-(amd64|arm64)\\.tar\\.gz"));
    }
}
