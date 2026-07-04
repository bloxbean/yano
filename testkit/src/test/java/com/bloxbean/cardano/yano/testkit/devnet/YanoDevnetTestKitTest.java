package com.bloxbean.cardano.yano.testkit.devnet;

import com.bloxbean.cardano.yano.api.config.BootstrapOutpointConfig;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import com.bloxbean.cardano.yano.runtime.assembly.YanoAssembly;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YanoDevnetTestKitTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> DEVNET_PROFILE_FILES = List.of(
            "shelley-genesis.json",
            "byron-genesis.json",
            "alonzo-genesis.json",
            "conway-genesis.json",
            "protocol-param.json",
            "vrf.skey",
            "kes.skey",
            "opcert.cert"
    );

    @TempDir
    Path tempDir;

    @Test
    void buildsOnToolkitAssembly() {
        try (YanoDevnetTestKit kit = YanoDevnetTestKit.devnet(devnetConfig("toolkit"))) {
            assertNotNull(kit.devnet());
            assertNotNull(kit.lifecycle());
            assertNotNull(kit.chain());
            assertNotNull(kit.ledger());
            assertNotNull(kit.txGateway());
            assertNotNull(kit.txEvaluationGateway());
            assertNotNull(kit.producerControl());
            assertNotNull(kit.queries());
            assertNotNull(kit.await());
            assertNotNull(kit.wallets());
            assertNotNull(kit.faucet());
            assertNotNull(kit.snapshots());
            assertNotNull(kit.time());
            assertNotNull(kit.transactions());
            assertNotNull(kit.assertions());
        }
    }

    @Test
    void defaultTestConfigUsesTemporaryRocksDbStorageAndCleansUp() {
        YanoDevnetTestConfig config = YanoDevnetTestConfig.builder().build();
        Path storageRoot = config.storageRoot().orElseThrow();

        assertTrue(config.yanoConfig().isUseRocksDB());
        assertTrue(config.yanoConfig().getRocksDBPath().startsWith(storageRoot.toString()));
        assertTrue((Boolean) config.runtimeOptions().globals().get(YanoPropertyKeys.Utxo.ENABLED));
        assertTrue(config.yanoConfig().getServerPort() > 0);
        assertProfileFilesExist(config.devnetProfileDir().orElseThrow());
        assertTrue(Files.exists(storageRoot));

        try (YanoDevnetTestKit ignored = YanoDevnetTestKit.devnet(config)) {
            assertTrue(Files.exists(storageRoot));
        }

        assertFalse(Files.exists(storageRoot));
    }

    @Test
    void defaultRocksDbProfileStartsAndResolvesProtocolParams() {
        try (YanoDevnetTestKit kit = YanoDevnetTestKit.devnet(YanoDevnetTestConfig.builder().build())) {
            kit.start();
            kit.await().untilBlockAtLeast(1);

            int epoch = (int) kit.queries().currentEpoch();
            assertTrue(kit.queries().protocolParameters(epoch).isPresent());
        }
    }

    @Test
    void staticProtocolParamsResolveWhenEpochParamTrackingIsDisabled() {
        try (YanoDevnetTestKit kit = YanoDevnetTestKit.devnet(YanoDevnetTestConfig.builder()
                .runtimeOption(YanoPropertyKeys.Ledger.EPOCH_PARAMS_TRACKING_ENABLED, false)
                .build())) {
            kit.start();
            kit.await().untilBlockAtLeast(1);

            int epoch = (int) kit.queries().currentEpoch();
            var params = kit.queries().protocolParameters(epoch).orElseThrow();
            assertEquals(11, params.protocolMajorVer());
        }
    }

    @Test
    void defaultProfileUsesProtocol11DevnetFilesAndSigningFixtures() throws Exception {
        try (YanoDevnetTestConfig config = YanoDevnetTestConfig.builder().build()) {
            Path profileDir = config.devnetProfileDir().orElseThrow();
            YanoConfig yano = config.yanoConfig();

            assertEquals(profileDir.resolve("shelley-genesis.json").toString(), yano.getShelleyGenesisFile());
            assertEquals(profileDir.resolve("byron-genesis.json").toString(), yano.getByronGenesisFile());
            assertEquals(profileDir.resolve("alonzo-genesis.json").toString(), yano.getAlonzoGenesisFile());
            assertEquals(profileDir.resolve("conway-genesis.json").toString(), yano.getConwayGenesisFile());
            assertEquals(profileDir.resolve("protocol-param.json").toString(), yano.getProtocolParametersFile());
            assertEquals(profileDir.resolve("vrf.skey").toString(), yano.getVrfSkeyFile());
            assertEquals(profileDir.resolve("kes.skey").toString(), yano.getKesSkeyFile());
            assertEquals(profileDir.resolve("opcert.cert").toString(), yano.getOpCertFile());
            assertFalse(Files.exists(profileDir.resolve("pv10")));

            JsonNode shelley = JSON.readTree(profileDir.resolve("shelley-genesis.json").toFile());
            JsonNode protocolParams = JSON.readTree(profileDir.resolve("protocol-param.json").toFile());
            assertEquals(42, shelley.path("networkMagic").asInt());
            assertEquals(11, shelley.path("protocolParams").path("protocolVersion").path("major").asInt());
            assertEquals(11, protocolParams.path("protocol_major_ver").asInt());
        }
    }

    @Test
    void defaultRuntimeOptionsMirrorDevnetProfileDerivedState() {
        var globals = YanoDevnetTestConfig.defaultRuntimeOptions().globals();

        assertEquals(Boolean.TRUE, globals.get(YanoPropertyKeys.Utxo.ENABLED));
        assertEquals(Boolean.TRUE, globals.get(YanoPropertyKeys.AccountState.ENABLED));
        assertEquals(Boolean.TRUE, globals.get(YanoPropertyKeys.EpochSnapshot.AMOUNTS_ENABLED));
        assertEquals(Boolean.TRUE, globals.get(YanoPropertyKeys.Ledger.ADAPOT_ENABLED));
        assertEquals(Boolean.TRUE, globals.get(YanoPropertyKeys.Ledger.REWARDS_ENABLED));
        assertEquals(Boolean.TRUE, globals.get(YanoPropertyKeys.Ledger.EPOCH_PARAMS_TRACKING_ENABLED));
        assertEquals(Boolean.TRUE, globals.get(YanoPropertyKeys.Ledger.GOVERNANCE_ENABLED));
        assertFalse(globals.containsKey(YanoPropertyKeys.AccountHistory.ENABLED));
    }

    @Test
    void runtimeDevnetResourcesMirrorAppDevnetTopLevelFiles() throws Exception {
        Path appDevnetDir = appDevnetDir();
        for (String fileName : DEVNET_PROFILE_FILES) {
            byte[] appBytes = Files.readAllBytes(appDevnetDir.resolve(fileName));
            byte[] runtimeBytes;
            try (InputStream in = classpathResource("genesis/devnet/" + fileName)) {
                runtimeBytes = in.readAllBytes();
            }

            assertArrayEquals(appBytes, runtimeBytes,
                    "runtime devnet resource should match app devnet file: " + fileName);
        }
        try (InputStream in = classpathResource("genesis/devnet/README.md")) {
            assertTrue(in.readAllBytes().length > 0);
        }
        assertThrows(IllegalStateException.class, () -> classpathResource("genesis/devnet/pv10/shelley-genesis.json"));
    }

    @Test
    void epochLengthOverridePatchesCopiedShelleyGenesis() throws Exception {
        try (YanoDevnetTestConfig config = YanoDevnetTestConfig.builder()
                .epochLength(5)
                .build()) {
            Path shelley = config.devnetProfileDir().orElseThrow().resolve("shelley-genesis.json");
            assertEquals(5, JSON.readTree(shelley.toFile()).path("epochLength").asLong());
            assertEquals(5, config.yanoConfig().getEpochLength());
        }
    }

    @Test
    void runtimeOptionOverridesProfileDefault() {
        try (YanoDevnetTestConfig config = YanoDevnetTestConfig.builder()
                .runtimeOption(YanoPropertyKeys.AccountState.ENABLED, false)
                .runtimeOption(YanoPropertyKeys.Ledger.GOVERNANCE_ENABLED, null)
                .build()) {
            var globals = config.runtimeOptions().globals();
            assertEquals(Boolean.FALSE, globals.get(YanoPropertyKeys.AccountState.ENABLED));
            assertFalse(globals.containsKey(YanoPropertyKeys.Ledger.GOVERNANCE_ENABLED));
        }
    }

    @Test
    void customGenesisPathsOverrideProfilePathsButMissingPathsAreFilled() throws Exception {
        Path customShelley = tempDir.resolve("custom-shelley-genesis.json");
        Files.writeString(customShelley, "{}");

        YanoConfig callerConfig = YanoConfig.devnetDefault(0);
        callerConfig.setShelleyGenesisFile(customShelley.toString());

        try (YanoDevnetTestConfig config = YanoDevnetTestConfig.builder()
                .yanoConfig(callerConfig)
                .build()) {
            Path profileDir = config.devnetProfileDir().orElseThrow();
            assertEquals(customShelley.toString(), config.yanoConfig().getShelleyGenesisFile());
            assertEquals(profileDir.resolve("conway-genesis.json").toString(),
                    config.yanoConfig().getConwayGenesisFile());
            assertEquals(profileDir.resolve("protocol-param.json").toString(),
                    config.yanoConfig().getProtocolParametersFile());
        }
    }

    @Test
    void persistentRocksDbStorageKeepsProfileFilesNextToChainstate() {
        Path storage = tempDir.resolve("chainstate");
        YanoDevnetTestConfig config = YanoDevnetTestConfig.builder()
                .persistentRocksDbStorage(storage)
                .build();
        Path profileDir = config.devnetProfileDir().orElseThrow();

        assertEquals(tempDir.resolve("config/network/devnet"), profileDir);
        assertProfileFilesExist(profileDir);
        config.close();
        assertTrue(Files.exists(profileDir));
    }

    @Test
    void persistentProfileCopyPreservesExistingFilesAcrossBuilds() throws Exception {
        Path storage = tempDir.resolve("chainstate");
        YanoDevnetTestConfig first = YanoDevnetTestConfig.builder()
                .persistentRocksDbStorage(storage)
                .build();
        Path profileDir = first.devnetProfileDir().orElseThrow();
        Path shelley = profileDir.resolve("shelley-genesis.json");

        ObjectNode root = (ObjectNode) JSON.readTree(shelley.toFile());
        root.put("systemStart", "2030-01-01T00:00:00Z");
        JSON.writerWithDefaultPrettyPrinter().writeValue(shelley.toFile(), root);
        first.close();

        try (YanoDevnetTestConfig second = YanoDevnetTestConfig.builder()
                .persistentRocksDbStorage(storage)
                .build()) {
            assertEquals(profileDir, second.devnetProfileDir().orElseThrow());
            assertEquals("2030-01-01T00:00:00Z",
                    JSON.readTree(shelley.toFile()).path("systemStart").asText());
        }
    }

    @Test
    void rawYanoConfigWithRelativeRocksDbPathUsesOwnedTemporaryProfile() {
        YanoConfig callerConfig = YanoConfig.devnetDefault(0);
        YanoDevnetTestConfig config = YanoDevnetTestKit.configFrom(callerConfig);
        Path storageRoot = config.storageRoot().orElseThrow();

        try {
            assertTrue(config.cleanupStorage());
            assertTrue(config.yanoConfig().isUseRocksDB());
            assertTrue(Path.of(config.yanoConfig().getRocksDBPath()).startsWith(storageRoot));
            assertProfileFilesExist(config.devnetProfileDir().orElseThrow());
        } finally {
            config.close();
        }

        assertFalse(Files.exists(storageRoot));
    }

    @Test
    void rawYanoConfigWithAbsoluteRocksDbPathKeepsPersistentProfileNextToChainstate() {
        YanoConfig callerConfig = devnetConfig("raw-absolute");

        try (YanoDevnetTestConfig config = YanoDevnetTestKit.configFrom(callerConfig)) {
            assertFalse(config.cleanupStorage());
            assertEquals(tempDir.resolve("config/network/devnet"), config.devnetProfileDir().orElseThrow());
            assertEquals(tempDir.resolve("raw-absolute").toString(), config.yanoConfig().getRocksDBPath());
            assertProfileFilesExist(config.devnetProfileDir().orElseThrow());
        }
    }

    @Test
    void rawYanoConfigWithRocksDbDisabledUsesOwnedTemporaryRocksDbProfile() {
        YanoConfig callerConfig = YanoConfig.devnetDefault(0);
        callerConfig.setUseRocksDB(false);
        callerConfig.setRocksDBPath(null);
        YanoDevnetTestConfig config = YanoDevnetTestKit.configFrom(callerConfig);
        Path storageRoot = config.storageRoot().orElseThrow();

        try {
            assertTrue(config.cleanupStorage());
            assertTrue(config.yanoConfig().isUseRocksDB());
            assertTrue(Path.of(config.yanoConfig().getRocksDBPath()).startsWith(storageRoot));
            assertProfileFilesExist(config.devnetProfileDir().orElseThrow());
        } finally {
            config.close();
        }

        assertFalse(Files.exists(storageRoot));
    }

    @Test
    void builderDoesNotMutateCallerConfigOrPreviouslyBuiltConfigs() {
        YanoConfig callerConfig = YanoConfig.devnetDefault(12345);
        callerConfig.setRocksDBPath(tempDir.resolve("caller").toString());

        YanoDevnetTestConfig.Builder builder = YanoDevnetTestConfig.builder()
                .yanoConfig(callerConfig)
                .temporaryRocksDbStorage();

        YanoDevnetTestConfig first = builder.build();
        Path firstStorageRoot = first.storageRoot().orElseThrow();
        Path persistentPath = tempDir.resolve("persistent-chainstate");
        YanoDevnetTestConfig second = builder.persistentRocksDbStorage(persistentPath).build();

        try {
            assertTrue(first.yanoConfig().isUseRocksDB());
            assertTrue(second.yanoConfig().isUseRocksDB());
            assertTrue(first.yanoConfig().getRocksDBPath().startsWith(firstStorageRoot.toString()));
            assertEquals(persistentPath.toString(), second.yanoConfig().getRocksDBPath());
            assertTrue(callerConfig.isUseRocksDB());
            assertTrue(callerConfig.getRocksDBPath().endsWith("caller"));
        } finally {
            first.close();
            second.close();
        }
    }

    @Test
    void builderCopiesBootstrapCollectionsAndElements() {
        YanoConfig callerConfig = YanoConfig.devnetDefault(12345);
        List<String> addresses = new ArrayList<>(List.of("addr1"));
        BootstrapOutpointConfig outpoint = BootstrapOutpointConfig.builder()
                .txHash("tx1")
                .outputIndex(0)
                .build();
        callerConfig.setBootstrapAddresses(addresses);
        callerConfig.setBootstrapUtxos(new ArrayList<>(List.of(outpoint)));

        YanoDevnetTestConfig built = YanoDevnetTestConfig.builder()
                .yanoConfig(callerConfig)
                .build();

        addresses.set(0, "changed");
        outpoint.setTxHash("changed");

        try {
            assertTrue(built.yanoConfig().getBootstrapAddresses().contains("addr1"));
            assertTrue(built.yanoConfig().getBootstrapUtxos().stream()
                    .anyMatch(config -> "tx1".equals(config.getTxHash())));
        } finally {
            built.close();
        }
    }

    @Test
    void cleansTemporaryStorageWhenAssemblyFails() {
        YanoDevnetTestConfig config = YanoDevnetTestConfig.builder().build();
        Path storageRoot = config.storageRoot().orElseThrow();
        config.yanoConfig().setDevMode(false);

        assertThrows(IllegalStateException.class, () -> YanoDevnetTestKit.devnet(config));
        assertFalse(Files.exists(storageRoot));
    }

    @Test
    void rejectsRuntimeOnlyNodeWithoutDevnetControl() {
        var node = YanoAssembly.devnet(devnetConfig("runtime-only")).build();
        try {
            assertThrows(IllegalArgumentException.class, () -> YanoDevnetTestKit.from(node));
        } finally {
            node.close();
        }
    }

    private YanoConfig devnetConfig(String name) {
        YanoConfig config = YanoConfig.devnetDefault(0);
        config.setRocksDBPath(tempDir.resolve(name).toString());
        return config;
    }

    private static void assertProfileFilesExist(Path profileDir) {
        for (String fileName : DEVNET_PROFILE_FILES) {
            assertTrue(Files.isRegularFile(profileDir.resolve(fileName)),
                    "missing copied devnet profile file: " + fileName);
        }
        assertFalse(Files.exists(profileDir.resolve("pv10")), "pv10 overlay should not be copied");
    }

    private static InputStream classpathResource(String resource) {
        InputStream in = YanoDevnetTestKitTest.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException("Classpath resource not found: " + resource);
        }
        return in;
    }

    private static Path appDevnetDir() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve("app/config/network/devnet");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate app/config/network/devnet from test working directory");
    }
}
