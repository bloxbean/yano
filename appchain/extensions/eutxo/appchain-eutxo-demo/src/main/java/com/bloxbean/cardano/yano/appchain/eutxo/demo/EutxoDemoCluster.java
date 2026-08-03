package com.bloxbean.cardano.yano.appchain.eutxo.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Generated-project lifecycle adapter used by every maintained demo scenario. */
public final class EutxoDemoCluster {
    private static final int MAX_PROCESS_OUTPUT = 64 * 1024;
    private static final int MAX_INDEX_RESPONSE = 2 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String INDEX_API =
            "/api/v1/plugins/com.bloxbean.cardano.yano.appchain.eutxo.indexer"
                    + "/index/v1";
    private final EutxoDemoWorkspace workspace;
    private final Path yanoHome;

    public EutxoDemoCluster(EutxoDemoWorkspace workspace) {
        this.workspace = workspace;
        this.yanoHome = resolveYanoHome();
    }

    public void generateProject(
            String recipe,
            Map<String, String> answers) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(
                yanoHome.resolve("yano.sh").toString(),
                "appchain", "init", "--non-interactive",
                "--recipe", recipe,
                "--network", "devnet",
                "--runtime", "jvm",
                "--deployment", "host",
                "--members", Integer.toString(workspace.manifest().members()),
                "--http-port-base", Integer.toString(workspace.manifest().httpPortBase()),
                "--server-port-base", Integer.toString(workspace.manifest().serverPortBase()),
                "--name", workspace.manifest().projectName(),
                "--chain-id", workspace.manifest().chainId()));
        for (String member : workspace.manifest().memberPublicKeys()) {
            command.add("--member-key");
            command.add(member);
        }
        answers.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(answer -> {
            command.add("--answer");
            command.add(answer.getKey() + "=" + answer.getValue());
        });
        command.add("--output");
        command.add(workspace.project().toString());
        run(command, Duration.ofMinutes(2), "PROJECT_GENERATION_FAILED");
        for (int index = 0; index < workspace.manifest().members(); index++) {
            Files.copy(
                    workspace.root().resolve("secrets/members/node" + index + ".env"),
                    workspace.project().resolve("secrets/node" + index + ".env"),
                    StandardCopyOption.COPY_ATTRIBUTES);
            EutxoDemoIdentityService.ownerFile(
                    workspace.project().resolve("secrets/node" + index + ".env"));
        }
        run(List.of(yanoHome.resolve("yano.sh").toString(),
                        "appchain", "config", "validate", "--mode", "project",
                        workspace.project().toString()),
                Duration.ofMinutes(1), "PROJECT_VALIDATION_FAILED");
    }

    public void start() throws IOException, InterruptedException {
        requireProjectScript("start");
        requirePackagedSqliteDriver();
        run(List.of(workspace.project().resolve("scripts/start").toString()),
                Duration.ofMinutes(8), "CLUSTER_START_FAILED");
        awaitIndexReady(Duration.ofMinutes(2));
        requireOwnedIndexFiles();
    }

    public void stop() throws IOException, InterruptedException {
        Path script = workspace.project().resolve("scripts/stop");
        if (Files.isExecutable(script)) {
            run(List.of(script.toString()), Duration.ofSeconds(30),
                    "CLUSTER_STOP_FAILED");
        }
    }

    public ClusterStatus status() {
        int ready = 0;
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1)).build();
        for (int index = 0; index < workspace.manifest().members(); index++) {
            int port = workspace.manifest().httpPortBase() + index;
            try {
                HttpResponse<Void> response = client.send(
                        HttpRequest.newBuilder(
                                        URI.create("http://127.0.0.1:" + port
                                                + "/q/health/ready"))
                                .timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    ready++;
                }
            } catch (IOException ignored) {
                // Not ready.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return new ClusterStatus(ready, workspace.manifest().members(),
                ready == workspace.manifest().members());
    }

    public String apiBase() {
        return "http://127.0.0.1:" + workspace.manifest().httpPortBase() + "/api/v1";
    }

    public String consoleUrl() {
        return "http://127.0.0.1:" + workspace.manifest().httpPortBase()
                + "/ui/app-chain/eutxo/?chain="
                + URLEncoder.encode(
                workspace.manifest().chainId(), StandardCharsets.UTF_8);
    }

    public List<IndexStatus> indexStatuses() {
        List<IndexStatus> statuses = new ArrayList<>();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1)).build();
        for (int node = 0; node < workspace.manifest().members(); node++) {
            statuses.add(indexStatus(client, node));
        }
        return List.copyOf(statuses);
    }

    public List<IndexStatus> awaitIndexReady(Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<IndexStatus> latest = List.of();
        while (System.nanoTime() < deadline) {
            latest = indexStatuses();
            if (latest.size() == workspace.manifest().members()
                    && latest.stream().allMatch(IndexStatus::ready)) {
                requireMatchingDigests(latest);
                return latest;
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException(
                "EUTXO_INDEX_NOT_READY" + boundedIndexDiagnostic(latest));
    }

    public Map<String, Object> indexSummary() {
        List<IndexStatus> statuses = indexStatuses();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("nodes", statuses);
        summary.put("ready", statuses.size() == workspace.manifest().members()
                && statuses.stream().allMatch(IndexStatus::ready));
        summary.put("consistent", matchingDigests(statuses));
        summary.put("console", consoleUrl());
        return Map.copyOf(summary);
    }

    private IndexStatus indexStatus(HttpClient client, int node) {
        int port = workspace.manifest().httpPortBase() + node;
        String endpoint = "http://127.0.0.1:" + port + INDEX_API
                + "/status?chain=" + URLEncoder.encode(
                workspace.manifest().chainId(), StandardCharsets.UTF_8);
        try {
            HttpResponse<java.io.InputStream> response = client.send(
                    HttpRequest.newBuilder(URI.create(endpoint))
                            .timeout(Duration.ofSeconds(3))
                            .header("Accept", "application/json")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (java.io.InputStream body = response.body()) {
                byte[] bytes = body.readNBytes(MAX_INDEX_RESPONSE + 1);
                if (bytes.length > MAX_INDEX_RESPONSE) {
                    return IndexStatus.unavailable(
                            node, "INDEX_RESPONSE_TOO_LARGE");
                }
                if (response.statusCode() != 200) {
                    return IndexStatus.unavailable(
                            node, "HTTP_" + response.statusCode());
                }
                JsonNode root = JSON.readTree(bytes);
                JsonNode projection = root.path("projection");
                JsonNode data = root.path("data");
                String status = projection.path("status").asText("");
                return new IndexStatus(
                        node,
                        status.startsWith("READY_")
                                && projection.path("lagBlocks").asLong(-1) == 0,
                        status,
                        projection.path("indexedHeight").asLong(0),
                        projection.path("finalizedHeight").asLong(0),
                        projection.path("lagBlocks").asLong(0),
                        data.path("coverage").asText("NONE"),
                        data.path("storeType").asText(""),
                        data.path("normalizedDigest").asText(""),
                        "");
            }
        } catch (IOException unavailable) {
            return IndexStatus.unavailable(node, "UNAVAILABLE");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return IndexStatus.unavailable(node, "INTERRUPTED");
        } catch (RuntimeException malformed) {
            return IndexStatus.unavailable(node, "INVALID_RESPONSE");
        }
    }

    private void requireOwnedIndexFiles() {
        for (int node = 0; node < workspace.manifest().members(); node++) {
            Path directory = indexDirectory(node);
            Path database = directory.resolve("eutxo-lifecycle.db");
            Path marker = directory.resolve(".yano-eutxo-index");
            if (!Files.isRegularFile(database)
                    || Files.isSymbolicLink(database)
                    || !Files.isRegularFile(marker)
                    || Files.isSymbolicLink(marker)) {
                throw new IllegalStateException(
                        "node" + node + " index files are missing or unsafe");
            }
        }
    }

    private Path indexDirectory(int node) {
        return workspace.project().resolve("data")
                .resolve("node" + node)
                .resolve("chainstate/appchains")
                .resolve(workspace.manifest().chainId())
                .resolve("indexes")
                .toAbsolutePath().normalize();
    }

    private static void requirePackagedSqliteDriver() {
        try {
            Class.forName("org.sqlite.JDBC", false,
                    EutxoDemoCluster.class.getClassLoader());
        } catch (ClassNotFoundException missing) {
            throw new IllegalStateException(
                    "PACKAGED_SQLITE_DRIVER_MISSING", missing);
        }
    }

    private static void requireMatchingDigests(List<IndexStatus> statuses) {
        if (!matchingDigests(statuses)) {
            throw new IllegalStateException("EUTXO_INDEX_DIGEST_MISMATCH");
        }
    }

    private static boolean matchingDigests(List<IndexStatus> statuses) {
        return !statuses.isEmpty()
                && statuses.stream().map(IndexStatus::normalizedDigest)
                .filter(value -> !value.isBlank()).distinct().count() == 1
                && statuses.stream().allMatch(
                value -> !value.normalizedDigest().isBlank());
    }

    private static String boundedIndexDiagnostic(List<IndexStatus> statuses) {
        return statuses.stream()
                .filter(status -> !status.ready())
                .findFirst()
                .map(status -> ": node" + status.node() + "="
                        + status.diagnostic())
                .orElse("");
    }

    private void requireProjectScript(String name) {
        if (!Files.isExecutable(workspace.project().resolve("scripts/" + name))) {
            throw new IllegalStateException(
                    "demo project is not generated; run setup first");
        }
    }

    private void run(List<String> command, Duration timeout, String code)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workspace.root().toFile())
                .redirectErrorStream(true);
        builder.environment().put("YANO_HOME", yanoHome.toString());
        Process process = builder.start();
        CompletableFuture<String> output = CompletableFuture.supplyAsync(
                () -> drain(process));
        boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            throw new IllegalStateException(code + ": command timed out");
        }
        String diagnostic = output.join();
        if (process.exitValue() != 0) {
            throw new IllegalStateException(code + safeDiagnostic(diagnostic));
        }
    }

    private static String drain(Process process) {
        StringBuilder retained = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (retained.length() < MAX_PROCESS_OUTPUT) {
                    int remaining = MAX_PROCESS_OUTPUT - retained.length();
                    retained.append(line, 0, Math.min(line.length(), remaining))
                            .append('\n');
                }
            }
        } catch (IOException ignored) {
            // Process exit code remains authoritative.
        }
        return retained.toString();
    }

    private static String safeDiagnostic(String output) {
        java.util.regex.Matcher marker = java.util.regex.Pattern.compile(
                "YANO_STARTUP_FAILURE code=([A-Z0-9_]+)").matcher(output);
        if (marker.find()) {
            return ": YANO_STARTUP_FAILURE code=" + marker.group(1);
        }
        java.util.regex.Matcher field = java.util.regex.Pattern.compile(
                "Recipe answer ([A-Za-z][A-Za-z0-9]{0,63})").matcher(output);
        if (field.find()) {
            return ": invalid recipe answer " + field.group(1);
        }
        return "";
    }

    private static Path resolveYanoHome() {
        String configured = System.getProperty("yano.home");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("YANO_HOME");
        }
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "YANO_HOME is unavailable; invoke the demo through ./yano.sh");
        }
        Path home = Path.of(configured).toAbsolutePath().normalize();
        if (!Files.isExecutable(home.resolve("yano.sh"))) {
            throw new IllegalStateException("YANO_HOME does not contain executable yano.sh");
        }
        return home;
    }

    public record ClusterStatus(int readyNodes, int expectedNodes, boolean ready) {
    }

    public record IndexStatus(
            int node,
            boolean ready,
            String status,
            long indexedHeight,
            long finalizedHeight,
            long lagBlocks,
            String coverage,
            String storeType,
            String normalizedDigest,
            String diagnostic
    ) {
        static IndexStatus unavailable(int node, String diagnostic) {
            return new IndexStatus(
                    node, false, "UNAVAILABLE", 0, 0, 0,
                    "NONE", "", "", diagnostic);
        }
    }
}
