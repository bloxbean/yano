package com.bloxbean.cardano.yano.app.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/** Isolated short-epoch profile for the opt-in pool-lifecycle integration matrix. */
public final class PoolLifecycleDevnetTestProfile extends DevnetTestProfile {
    static final int EPOCH_LENGTH = 60;

    private static final Path STORAGE_DIR;
    private static final Path HISTORY_DIR;
    private static final Path SHELLEY_GENESIS;
    private static final int NODE_SERVER_PORT;

    static {
        try {
            STORAGE_DIR = Files.createTempDirectory("yano-pool-lifecycle-chainstate");
            HISTORY_DIR = Files.createTempDirectory("yano-pool-lifecycle-history");
            SHELLEY_GENESIS = STORAGE_DIR.resolve("shelley-genesis.json");
            NODE_SERVER_PORT = availablePort();

            ObjectMapper mapper = new ObjectMapper();
            ObjectNode genesis = (ObjectNode) mapper.readTree(configFile("shelley-genesis.json").toFile());
            genesis.put("epochLength", EPOCH_LENGTH);
            mapper.writerWithDefaultPrettyPrinter().writeValue(SHELLEY_GENESIS.toFile(), genesis);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            deleteTree(STORAGE_DIR);
            deleteTree(HISTORY_DIR);
        }));
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
        overrides.put("yano.storage.path", STORAGE_DIR.toString());
        overrides.put("yano.history.dir", HISTORY_DIR.toString());
        overrides.put("yano.genesis.shelley-genesis-file", SHELLEY_GENESIS.toString());
        overrides.put("yano.server.port", Integer.toString(NODE_SERVER_PORT));
        overrides.put("yano.block-producer.block-time-millis", "5000");
        overrides.put("yano.block-producer.lazy", "true");
        return Map.copyOf(overrides);
    }

    private static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static void deleteTree(Path directory) {
        try {
            if (Files.exists(directory)) {
                try (var paths = Files.walk(directory)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // Best-effort shutdown cleanup.
                        }
                    });
                }
            }
        } catch (IOException ignored) {
            // Best-effort shutdown cleanup.
        }
    }
}
