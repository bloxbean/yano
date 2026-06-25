package com.bloxbean.cardano.yano.testkit.external;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opt-in process helper for Haskell {@code cardano-node} downstream sync tests.
 */
public final class HaskellCardanoNodeProcess implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(HaskellCardanoNodeProcess.class);

    static final String DEFAULT_VERSION = "11.0.1";
    private static final int MAX_LOG_CHARS = 50_000;
    private static final Pattern CHAIN_EXTENDED_PATTERN =
            Pattern.compile("Chain extended.*slot (\\d+)");

    private final Path workDir;
    private final Path cacheRoot;
    private final String version;
    private final StringBuilder logOutput = new StringBuilder();

    private Process process;

    /**
     * Creates a cardano-node process helper using the default cache and version.
     *
     * @param workDir dedicated working directory for this test process
     */
    public HaskellCardanoNodeProcess(Path workDir) {
        this(workDir, resolveVersion(), defaultCacheRoot());
    }

    /**
     * Creates a cardano-node process helper.
     *
     * @param workDir dedicated working directory for this test process
     * @param version cardano-node release version
     * @param cacheRoot binary cache root
     */
    public HaskellCardanoNodeProcess(Path workDir, String version, Path cacheRoot) {
        this.workDir = Objects.requireNonNull(workDir, "workDir");
        this.version = requireText(version, "version");
        this.cacheRoot = Objects.requireNonNull(cacheRoot, "cacheRoot");
    }

    /**
     * Resolves the default cardano-node version from system property, environment,
     * then the built-in default.
     *
     * @return resolved version
     */
    public static String resolveVersion() {
        String v = System.getProperty("cardano.node.version");
        if (v == null || v.isBlank()) {
            v = System.getenv("CARDANO_NODE_VERSION");
        }
        return (v != null && !v.isBlank()) ? v : DEFAULT_VERSION;
    }

    /**
     * Returns the default binary cache root.
     *
     * @return cache root
     */
    public static Path defaultCacheRoot() {
        return Path.of(System.getProperty("user.home"), ".yaci", "test");
    }

    /**
     * Returns this process work directory.
     *
     * @return work directory
     */
    public Path workDir() {
        return workDir;
    }

    /**
     * Returns the cardano-node version used by this helper.
     *
     * @return cardano-node version
     */
    public String version() {
        return version;
    }

    /**
     * Directory where Yano genesis files must be copied before startup.
     *
     * @return genesis directory
     */
    public Path genesisDir() {
        return workDir.resolve("haskell-genesis");
    }

    /**
     * Compatibility alias for existing app e2e tests.
     *
     * @return genesis directory
     */
    public Path getGenesisDir() {
        return genesisDir();
    }

    /**
     * Log file written by this helper.
     *
     * @return log path
     */
    public Path logPath() {
        return workDir.resolve("cardano-node.log");
    }

    /**
     * Returns the cardano-node binary, downloading and extracting it when needed.
     *
     * @return executable path
     * @throws IOException if download or extraction fails
     * @throws InterruptedException if extraction is interrupted
     */
    public Path ensureBinary() throws IOException, InterruptedException {
        Path cacheDir = cacheRoot.resolve("cardano-node-" + version);
        Path binDir = cacheDir.resolve("bin");
        Path binary = binDir.resolve("cardano-node");

        if (Files.isExecutable(binary)) {
            log.info("Using cached cardano-node {} at {}", version, binary);
            return binary;
        }

        Files.createDirectories(cacheDir);
        Path tarball = downloadReleaseAsset(cacheDir);

        Process extract = new ProcessBuilder("tar", "xzf", tarball.toString(), "-C", cacheDir.toString())
                .redirectErrorStream(true)
                .start();
        extract.getInputStream().transferTo(OutputStream.nullOutputStream());
        int exit = extract.waitFor();
        if (exit != 0) {
            throw new IOException("Failed to extract cardano-node tarball, exit code: " + exit);
        }

        if (!Files.exists(binary)) {
            try (var stream = Files.walk(cacheDir, 3)) {
                binary = stream
                        .filter(path -> path.getFileName().toString().equals("cardano-node")
                                && !Files.isDirectory(path))
                        .findFirst()
                        .orElseThrow(() -> new IOException(
                                "cardano-node binary not found after extraction in " + cacheDir));
            }
        }

        binary.toFile().setExecutable(true);
        Files.deleteIfExists(tarball);
        log.info("cardano-node {} ready at {}", version, binary);
        return binary;
    }

    /**
     * Starts cardano-node and connects it to the given Yano node-to-node port.
     *
     * @param yanoN2nPort Yano n2n server port
     * @throws IOException if process startup fails
     * @throws InterruptedException if binary extraction is interrupted
     */
    public void start(int yanoN2nPort) throws IOException, InterruptedException {
        stop();
        terminateRecordedProcess(pidPath(), "cardano-node");
        clearLogs();

        Path binary = ensureBinary();
        Files.createDirectories(workDir);
        Files.createDirectories(genesisDir());
        ensureDijkstraGenesisIfNeeded();
        validateRequiredGenesisFiles();

        Path dbDir = workDir.resolve("hdb");
        Files.createDirectories(dbDir);

        Path socketPath = dbDir.resolve("node.socket");
        if (socketPath.toString().length() > 100) {
            Path shortSocketDir = Files.createTempDirectory("cn");
            socketPath = shortSocketDir.resolve("n.sock");
        }

        Path configFile = workDir.resolve("configuration.json");
        Files.writeString(configFile, buildConfigurationJson());

        Path topologyFile = workDir.resolve("topology.json");
        Files.writeString(topologyFile, buildTopologyJson(yanoN2nPort));

        ProcessBuilder pb = new ProcessBuilder(
                binary.toString(), "run",
                "--topology", topologyFile.toString(),
                "--database-path", dbDir.toString(),
                "--socket-path", socketPath.toString(),
                "--host-addr", "0.0.0.0",
                "--port", "0",
                "--config", configFile.toString()
        );
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);

        log.info("Starting cardano-node {} against Yano n2n port {}", version, yanoN2nPort);
        process = pb.start();
        Files.writeString(pidPath(), Long.toString(process.pid()));
        startLogReader(process, logPath(), "cardano-node-log-reader");
    }

    /**
     * Waits until the Haskell node has extended its chain to at least the target
     * slot.
     *
     * @param targetSlot target slot
     * @param timeout timeout
     * @throws InterruptedException if interrupted
     */
    public void waitForChainExtended(long targetSlot, Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            long latest = latestSyncedSlot();
            if (latest >= targetSlot) {
                log.info("cardano-node reached slot {} (target: {})", latest, targetSlot);
                return;
            }
            if (process != null && !process.isAlive()) {
                throw new IllegalStateException("cardano-node process died unexpectedly (exit: "
                        + process.exitValue() + ").\nLog:\n" + logTail());
            }
            Thread.sleep(500);
        }
        throw new AssertionError("Timed out waiting for cardano-node to reach slot " + targetSlot
                + ". Latest synced: " + latestSyncedSlot() + "\nLog tail:\n" + logTail());
    }

    /**
     * Waits until the Haskell node has extended its chain to at least the target
     * slot.
     *
     * @param targetSlot target slot
     * @param timeoutMillis timeout in milliseconds
     * @throws InterruptedException if interrupted
     */
    public void waitForChainExtended(long targetSlot, long timeoutMillis) throws InterruptedException {
        waitForChainExtended(targetSlot, Duration.ofMillis(timeoutMillis));
    }

    /**
     * Parses the last chain-extended slot from cardano-node logs.
     *
     * @return latest observed slot, or -1 if none has been observed
     */
    public long latestSyncedSlot() {
        String logs;
        synchronized (logOutput) {
            logs = logOutput.toString();
        }
        long latestSlot = -1;
        Matcher matcher = CHAIN_EXTENDED_PATTERN.matcher(logs);
        while (matcher.find()) {
            latestSlot = Long.parseLong(matcher.group(1));
        }
        return latestSlot;
    }

    /**
     * Compatibility alias for existing app e2e tests.
     *
     * @return latest observed slot, or -1 if none has been observed
     */
    public long getLatestSyncedSlot() {
        return latestSyncedSlot();
    }

    /**
     * Returns the in-memory log tail retained by this helper.
     *
     * @return log tail
     */
    public String logTail() {
        synchronized (logOutput) {
            int len = logOutput.length();
            return len > 3_000 ? logOutput.substring(len - 3_000) : logOutput.toString();
        }
    }

    /**
     * Stops the process if it is running.
     */
    public void stop() {
        if (process != null && process.isAlive()) {
            log.info("Stopping cardano-node");
            process.destroy();
            try {
                process.waitFor(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        process = null;
        try {
            Files.deleteIfExists(pidPath());
        } catch (IOException e) {
            log.debug("Could not delete cardano-node pid file {}", pidPath(), e);
        }
    }

    @Override
    public void close() {
        stop();
    }

    String buildConfigurationJson() {
        Path genesis = genesisDir();
        String shelleyPath = workDir.relativize(genesis.resolve("shelley-genesis.json")).toString();
        String byronPath = workDir.relativize(genesis.resolve("byron-genesis.json")).toString();
        String alonzoPath = workDir.relativize(genesis.resolve("alonzo-genesis.json")).toString();
        String conwayPath = workDir.relativize(genesis.resolve("conway-genesis.json")).toString();
        String dijkstraLine = "";
        if (includeDijkstraGenesis()) {
            String dijkstraPath = workDir.relativize(genesis.resolve("dijkstra-genesis.json")).toString();
            dijkstraLine = "  \"DijkstraGenesisFile\": \"./" + dijkstraPath + "\",\n";
        }

        return """
                {
                  "AlonzoGenesisFile": "./%s",
                  "ByronGenesisFile": "./%s",
                  "ConwayGenesisFile": "./%s",
                %s  "EnableP2P": true,
                  "LastKnownBlockVersion-Alt": 0,
                  "LastKnownBlockVersion-Major": 2,
                  "LastKnownBlockVersion-Minor": 0,
                  "LedgerDB": {
                    "Backend": "V2InMemory",
                    "NumOfDiskSnapshots": 2,
                    "QueryBatchSize": 100000,
                    "SnapshotInterval": 4320
                  },
                  "PeerSharing": true,
                  "Protocol": "Cardano",
                  "RequiresNetworkMagic": "RequiresMagic",
                  "ShelleyGenesisFile": "./%s",
                  "TestShelleyHardForkAtEpoch": 0,
                  "TestAllegraHardForkAtEpoch": 0,
                  "TestMaryHardForkAtEpoch": 0,
                  "TestAlonzoHardForkAtEpoch": 0,
                  "TestBabbageHardForkAtEpoch": 0,
                  "TestConwayHardForkAtEpoch": 0,
                  "ExperimentalHardForksEnabled": true,
                  "ExperimentalProtocolsEnabled": true,
                  "TargetNumberOfActivePeers": 20,
                  "TargetNumberOfEstablishedPeers": 40,
                  "TargetNumberOfKnownPeers": 100,
                  "TargetNumberOfRootPeers": 100,
                  "TraceBlockFetchClient": true,
                  "TraceBlockFetchDecisions": true,
                  "TraceBlockFetchProtocol": true,
                  "TraceChainDb": true,
                  "TraceChainSyncClient": true,
                  "TraceConnectionManager": true,
                  "TraceDiffusionInitialization": true,
                  "TraceHandshake": true,
                  "TraceInboundGovernor": true,
                  "TraceLedgerPeers": true,
                  "TraceLocalRootPeers": true,
                  "TraceMempool": true,
                  "TracePeerSelection": true,
                  "TracePeerSelectionActions": true,
                  "TracePublicRootPeers": true,
                  "TraceServer": true,
                  "TracingVerbosity": "NormalVerbosity",
                  "TurnOnLogMetrics": false,
                  "TurnOnLogging": true,
                  "UseTraceDispatcher": false,
                  "MinBigLedgerPeersForTrustedState": 0,
                  "defaultBackends": ["KatipBK"],
                  "defaultScribes": [["StdoutSK", "stdout"]],
                  "minSeverity": "Info",
                  "options": {
                    "mapBackends": {},
                    "mapSubtrace": {
                      "cardano.node.metrics": {"subtrace": "Neutral"}
                    }
                  },
                  "rotation": {
                    "rpKeepFilesNum": 10,
                    "rpLogLimitBytes": 5000000,
                    "rpMaxAgeHours": 24
                  },
                  "setupBackends": ["KatipBK"],
                  "setupScribes": [{
                    "scFormat": "ScText",
                    "scKind": "StdoutSK",
                    "scName": "stdout",
                    "scRotation": null
                  }],
                  "TraceChainDB": true
                }
                """.formatted(alonzoPath, byronPath, conwayPath, dijkstraLine, shelleyPath);
    }

    String buildTopologyJson(int yanoN2nPort) {
        return """
                {
                  "bootstrapPeers": [
                    {"address": "127.0.0.1", "port": %d}
                  ],
                  "localRoots": [
                    {
                      "accessPoints": [
                        {"address": "127.0.0.1", "port": %d}
                      ],
                      "valency": 1
                    }
                  ],
                  "publicRoots": [],
                  "useLedgerAfterSlot": -1
                }
                """.formatted(yanoN2nPort, yanoN2nPort);
    }

    void appendLogLine(String line) {
        synchronized (logOutput) {
            logOutput.append(line).append('\n');
            if (logOutput.length() > MAX_LOG_CHARS) {
                logOutput.delete(0, logOutput.length() - 40_000);
            }
        }
    }

    void ensureDijkstraGenesisIfNeeded() throws IOException {
        if (includeDijkstraGenesis()) {
            YanoGenesisFiles.writeDefaultDijkstraGenesis(genesisDir());
        }
    }

    private void clearLogs() {
        synchronized (logOutput) {
            logOutput.setLength(0);
        }
    }

    private void validateRequiredGenesisFiles() throws IOException {
        for (String fileName : YanoGenesisFiles.REQUIRED_GENESIS_FILES) {
            Path path = genesisDir().resolve(fileName);
            if (!Files.isRegularFile(path)) {
                throw new IOException("Required genesis file not found for cardano-node: " + path);
            }
        }
        if (includeDijkstraGenesis()) {
            Path path = genesisDir().resolve("dijkstra-genesis.json");
            if (!Files.isRegularFile(path)) {
                throw new IOException("cardano-node " + version
                        + " requires dijkstra-genesis.json in " + genesisDir());
            }
        }
    }

    private Path downloadReleaseAsset(Path cacheDir) throws IOException {
        IOException lastFailure = null;
        for (String assetName : releaseAssetNames()) {
            String url = "https://github.com/IntersectMBO/cardano-node/releases/download/"
                    + version + "/" + assetName;
            Path tarball = cacheDir.resolve(assetName);
            log.info("Downloading cardano-node {} from {}", version, url);
            try (InputStream in = URI.create(url).toURL().openStream()) {
                Files.copy(in, tarball, StandardCopyOption.REPLACE_EXISTING);
                return tarball;
            } catch (IOException e) {
                lastFailure = e;
                Files.deleteIfExists(tarball);
                log.debug("Could not download cardano-node asset {}", assetName, e);
            }
        }
        throw new IOException("Could not download a cardano-node " + version
                + " release asset for this platform. Tried " + releaseAssetNames(), lastFailure);
    }

    List<String> releaseAssetNames() {
        String os = detectPlatform();
        String arch = detectArch(os);
        String legacy = "cardano-node-" + version + "-" + os + ".tar.gz";
        String archQualified = "cardano-node-" + version + "-" + os + "-" + arch + ".tar.gz";
        if (versionAtLeast(version, "11.0.0")) {
            return List.of(archQualified, legacy);
        }
        return List.of(legacy, archQualified);
    }

    private boolean includeDijkstraGenesis() {
        return versionAtLeast(version, "11.0.0");
    }

    private void startLogReader(Process source, Path logFile, String threadName) {
        Thread logReader = new Thread(() -> {
            try {
                Files.createDirectories(logFile.getParent());
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(source.getInputStream()));
                     BufferedWriter writer = Files.newBufferedWriter(logFile,
                             java.nio.file.StandardOpenOption.CREATE,
                             java.nio.file.StandardOpenOption.APPEND)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        appendLogLine(line);
                        writer.write(line);
                        writer.newLine();
                        writer.flush();
                        log.trace("[cardano-node] {}", line);
                    }
                }
            } catch (IOException e) {
                if (source.isAlive()) {
                    log.warn("Error reading cardano-node output", e);
                }
            }
        }, threadName);
        logReader.setDaemon(true);
        logReader.start();
    }

    private Path pidPath() {
        return workDir.resolve("cardano-node.pid");
    }

    private static void terminateRecordedProcess(Path pidPath, String commandFragment) throws IOException {
        if (!Files.exists(pidPath)) {
            return;
        }
        String raw = Files.readString(pidPath).trim();
        Files.deleteIfExists(pidPath);
        if (raw.isEmpty()) {
            return;
        }
        long pid;
        try {
            pid = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return;
        }
        ProcessHandle.of(pid).ifPresent(handle -> {
            if (!looksLike(handle, commandFragment)) {
                return;
            }
            handle.destroy();
            try {
                handle.onExit().get(10, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                handle.destroyForcibly();
            }
        });
    }

    private static boolean looksLike(ProcessHandle handle, String fragment) {
        ProcessHandle.Info info = handle.info();
        String command = info.command().orElse("");
        String args = String.join(" ", info.arguments().orElse(new String[0]));
        return command.contains(fragment) || args.contains(fragment);
    }

    private static String detectPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac") || os.contains("darwin")) {
            return "macos";
        }
        if (os.contains("linux")) {
            return "linux";
        }
        throw new UnsupportedOperationException("Unsupported OS for cardano-node download: " + os);
    }

    private static String detectArch(String os) {
        if ("macos".equals(os)) {
            return "amd64";
        }
        String arch = System.getProperty("os.arch", "").toLowerCase();
        return switch (arch) {
            case "arm64", "aarch64" -> "arm64";
            case "x86_64", "amd64" -> "amd64";
            default -> throw new UnsupportedOperationException("Unsupported arch for cardano-node download: " + arch);
        };
    }

    private static boolean versionAtLeast(String actual, String minimum) {
        int[] a = parseVersion(actual);
        int[] b = parseVersion(minimum);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int av = i < a.length ? a[i] : 0;
            int bv = i < b.length ? b[i] : 0;
            if (av != bv) {
                return av > bv;
            }
        }
        return true;
    }

    private static int[] parseVersion(String version) {
        String numeric = version.replaceFirst("^[^0-9]*", "");
        String[] parts = numeric.split("[.-]");
        int length = Math.min(parts.length, 3);
        int[] result = new int[length];
        for (int i = 0; i < length; i++) {
            String part = parts[i].replaceAll("[^0-9].*$", "");
            result[i] = part.isBlank() ? 0 : Integer.parseInt(part);
        }
        return result;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
