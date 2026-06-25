package com.bloxbean.cardano.yano.testkit.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Opt-in process helper for tests that need the Yano app HTTP/n2n surface.
 */
public final class YanoAppProcess implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(YanoAppProcess.class);
    private static final int MAX_LOG_CHARS = 50_000;

    private final Path workDir;
    private final Path jarPath;
    private final Path configSourceDir;
    private final boolean applyProtocol10Overlay;
    private final int httpPort;
    private final int n2nPort;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StringBuilder logOutput = new StringBuilder();

    private Process process;

    /**
     * Creates a process helper and infers the app config directory from the jar
     * location.
     *
     * @param workDir dedicated working directory
     * @param jarPath Yano runnable jar
     * @throws IOException if ports cannot be allocated
     */
    public YanoAppProcess(Path workDir, Path jarPath) throws IOException {
        this(workDir, jarPath, inferConfigSource(jarPath), true, findAvailablePort(), findAvailablePort());
    }

    /**
     * Creates a process helper.
     *
     * @param workDir dedicated working directory
     * @param jarPath Yano runnable jar
     * @param configSourceDir app config directory to copy into workDir
     * @param applyProtocol10Overlay whether to overlay {@code network/devnet/pv10}
     * @throws IOException if ports cannot be allocated
     */
    public YanoAppProcess(Path workDir, Path jarPath, Path configSourceDir,
                          boolean applyProtocol10Overlay) throws IOException {
        this(workDir, jarPath, configSourceDir, applyProtocol10Overlay,
                findAvailablePort(), findAvailablePort());
    }

    YanoAppProcess(Path workDir, Path jarPath, Path configSourceDir,
                   boolean applyProtocol10Overlay, int httpPort, int n2nPort) {
        this.workDir = Objects.requireNonNull(workDir, "workDir");
        this.jarPath = Objects.requireNonNull(jarPath, "jarPath");
        this.configSourceDir = Objects.requireNonNull(configSourceDir, "configSourceDir");
        this.applyProtocol10Overlay = applyProtocol10Overlay;
        this.httpPort = httpPort;
        this.n2nPort = n2nPort;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Locates the Yano app jar using {@code yano.uber.jar}, then common Gradle
     * output locations.
     *
     * @return runnable jar path
     */
    public static Path locateUberJar() {
        String jarPath = System.getProperty("yano.uber.jar");
        if (jarPath != null && !jarPath.isBlank()) {
            Path p = Path.of(jarPath);
            if (Files.exists(p)) {
                return p;
            }
        }

        Path projectRoot = Path.of(System.getProperty("user.dir"));
        Path candidate = projectRoot.resolve("app").resolve("build").resolve("yano.jar");
        if (Files.exists(candidate)) {
            return candidate;
        }

        candidate = projectRoot.resolve("build").resolve("yano.jar");
        if (Files.exists(candidate)) {
            return candidate;
        }

        throw new IllegalStateException(
                "Uber-jar not found. Run './gradlew :app:quarkusBuild' first, "
                        + "or set -Dyano.uber.jar=<path>");
    }

    /**
     * Returns the HTTP port assigned to this process.
     *
     * @return HTTP port
     */
    public int httpPort() {
        return httpPort;
    }

    /**
     * Compatibility alias for existing app e2e tests.
     *
     * @return HTTP port
     */
    public int getHttpPort() {
        return httpPort();
    }

    /**
     * Returns the node-to-node port assigned to this process.
     *
     * @return n2n port
     */
    public int n2nPort() {
        return n2nPort;
    }

    /**
     * Compatibility alias for existing app e2e tests.
     *
     * @return n2n port
     */
    public int getN2nPort() {
        return n2nPort();
    }

    /**
     * Returns the app base URL.
     *
     * @return base URL
     */
    public URI baseUrl() {
        return URI.create("http://localhost:" + httpPort + "/");
    }

    /**
     * Returns the Blockfrost-compatible API base URL.
     *
     * @return API base URL
     */
    public URI apiBaseUrl() {
        return baseUrl().resolve("api/v1/");
    }

    /**
     * Returns the copied config directory in the process work directory.
     *
     * @return copied config directory
     */
    public Path configDir() {
        return workDir.resolve("config");
    }

    /**
     * Log file written by this helper.
     *
     * @return log path
     */
    public Path logPath() {
        return workDir.resolve("yano.log");
    }

    /**
     * Starts Yano with the devnet profile and optional JVM system properties.
     *
     * @param extraProps extra JVM system properties, either {@code -Dkey=value}
     *                   or {@code key=value}
     * @throws IOException if startup fails
     */
    public void start(String... extraProps) throws IOException {
        stop();
        terminateRecordedProcess(pidPath(), jarPath.getFileName().toString());
        prepareConfig();

        List<String> cmd = command(extraProps);
        log.info("Starting Yano app process: httpPort={}, n2nPort={}", httpPort, n2nPort);
        log.debug("Command: {}", String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);

        process = pb.start();
        Files.writeString(pidPath(), Long.toString(process.pid()));
        startLogReader(process, logPath(), "yano-app-log-reader");
    }

    /**
     * Waits for the app health endpoint to report ready.
     *
     * @param timeout timeout
     * @throws Exception if readiness fails or times out
     */
    public void waitForReady(Duration timeout) throws Exception {
        Objects.requireNonNull(timeout, "timeout");
        long deadline = System.nanoTime() + timeout.toNanos();
        URI healthUri = baseUrl().resolve("q/health/ready");

        while (System.nanoTime() < deadline) {
            if (process != null && !process.isAlive()) {
                throw new IllegalStateException("Yano process died during startup (exit: "
                        + process.exitValue() + ").\nLog:\n" + logTail());
            }
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(healthUri)
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    log.info("Yano ready on port {}", httpPort);
                    return;
                }
            } catch (Exception ignored) {
                // not ready yet
            }
            Thread.sleep(500);
        }

        throw new AssertionError("Timed out waiting for Yano app readiness.\nLog tail:\n" + logTail());
    }

    /**
     * Compatibility overload for existing app e2e tests.
     *
     * @param timeoutMillis timeout in milliseconds
     * @throws Exception if readiness fails or times out
     */
    public void waitForReady(long timeoutMillis) throws Exception {
        waitForReady(Duration.ofMillis(timeoutMillis));
    }

    /**
     * Gets the current Yano tip from HTTP.
     *
     * @return tip JSON
     * @throws Exception if the endpoint fails
     */
    public JsonNode tip() throws Exception {
        return getJson("node/tip");
    }

    /**
     * Compatibility alias for existing app e2e tests.
     *
     * @return tip JSON
     * @throws Exception if the endpoint fails
     */
    public JsonNode getTip() throws Exception {
        return tip();
    }

    /**
     * Calls an API path under {@code /api/v1}.
     *
     * @param apiPath path relative to {@code /api/v1}
     * @return response JSON
     * @throws Exception if the endpoint fails
     */
    public JsonNode getJson(String apiPath) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(apiBaseUrl().resolve(stripLeadingSlash(apiPath)))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("GET " + apiPath + " failed: "
                    + response.statusCode() + " " + response.body());
        }
        return objectMapper.readTree(response.body());
    }

    /**
     * POSTs to a devnet API path under {@code /api/v1/devnet}.
     *
     * @param path path relative to {@code /api/v1/devnet}
     * @param jsonBody JSON body
     * @return response JSON
     * @throws Exception if the endpoint fails
     */
    public JsonNode postDevnet(String path, String jsonBody) throws Exception {
        String apiPath = "devnet/" + stripLeadingSlash(path);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(apiBaseUrl().resolve(apiPath))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("POST " + path + " failed: "
                    + response.statusCode() + " " + response.body());
        }
        return objectMapper.readTree(response.body());
    }

    /**
     * Compatibility alias for existing app e2e tests.
     *
     * @param path path relative to {@code /api/v1/devnet}
     * @param jsonBody JSON body
     * @return response JSON
     * @throws Exception if the endpoint fails
     */
    public JsonNode post(String path, String jsonBody) throws Exception {
        return postDevnet(path, jsonBody);
    }

    /**
     * Copies the process devnet genesis files to a target directory.
     *
     * @param targetDir destination directory
     * @throws IOException if copy fails
     */
    public void copyGenesisTo(Path targetDir) throws IOException {
        YanoGenesisFiles.copyRequiredGenesis(YanoGenesisFiles.devnetGenesisDir(configDir()), targetDir);
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
     * Stops the app process if it is running.
     */
    public void stop() {
        if (process != null && process.isAlive()) {
            log.info("Stopping Yano app process");
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
            log.debug("Could not delete Yano app pid file {}", pidPath(), e);
        }
    }

    @Override
    public void close() {
        stop();
    }

    List<String> command(String... extraProps) {
        Path chainstateDir = workDir.resolve("chainstate");
        List<String> cmd = new ArrayList<>();
        cmd.add("java");
        cmd.add("-Dquarkus.profile=devnet");
        cmd.add("-Dquarkus.http.port=" + httpPort);
        cmd.add("-Dyano.server.port=" + n2nPort);
        cmd.add("-Dyano.remote.protocol-magic=42");
        cmd.add("-Dyano.storage.path=" + chainstateDir);
        cmd.add("-Dyano.block-producer.block-time-millis=200");

        addSystemProperties(cmd, extraProps);

        cmd.add("-jar");
        cmd.add(jarPath.toString());
        return cmd;
    }

    private void prepareConfig() throws IOException {
        Files.createDirectories(workDir);
        Path target = configDir();
        if (!Files.exists(target)) {
            YanoGenesisFiles.copyDirectory(configSourceDir, target);
        }
        if (applyProtocol10Overlay) {
            boolean applied = YanoGenesisFiles.applyProtocol10Overlay(target);
            if (applied) {
                log.info("Applied protocol-10 devnet config overlay for Haskell sync compatibility");
            } else {
                log.warn("Haskell sync protocol-10 devnet config not found at {}",
                        YanoGenesisFiles.devnetGenesisDir(target).resolve("pv10"));
            }
        }
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
                        log.trace("[yano] {}", line);
                    }
                }
            } catch (IOException e) {
                if (source.isAlive()) {
                    log.warn("Error reading Yano app output", e);
                }
            }
        }, threadName);
        logReader.setDaemon(true);
        logReader.start();
    }

    private void appendLogLine(String line) {
        synchronized (logOutput) {
            logOutput.append(line).append('\n');
            if (logOutput.length() > MAX_LOG_CHARS) {
                logOutput.delete(0, logOutput.length() - 40_000);
            }
        }
    }

    private Path pidPath() {
        return workDir.resolve("yano-app.pid");
    }

    private static String stripLeadingSlash(String path) {
        String result = Objects.requireNonNull(path, "path");
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        return result;
    }

    private static Path inferConfigSource(Path jarPath) {
        Objects.requireNonNull(jarPath, "jarPath");
        Path parent = jarPath.getParent();
        if (parent != null && parent.getParent() != null) {
            Path candidate = parent.getParent().resolve("config");
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not infer Yano app config directory for jar " + jarPath);
    }

    private static void addSystemProperties(List<String> cmd, String... extraProps) {
        if (extraProps == null) {
            return;
        }
        for (String prop : extraProps) {
            if (prop == null || prop.isBlank()) {
                continue;
            }
            if (prop.startsWith("-D")) {
                cmd.add(prop);
            } else if (prop.contains("=") && !prop.startsWith("-")) {
                cmd.add("-D" + prop);
            } else {
                throw new IllegalArgumentException(
                        "extraProps must be JVM system properties in -Dkey=value or key=value form: " + prop);
            }
        }
    }

    private static int findAvailablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(false);
            return socket.getLocalPort();
        }
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
}
