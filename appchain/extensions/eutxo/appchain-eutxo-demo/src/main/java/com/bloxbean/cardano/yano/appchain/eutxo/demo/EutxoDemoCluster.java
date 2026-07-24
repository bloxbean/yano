package com.bloxbean.cardano.yano.appchain.eutxo.demo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Generated-project lifecycle adapter used by every maintained demo scenario. */
public final class EutxoDemoCluster {
    private static final int MAX_PROCESS_OUTPUT = 64 * 1024;
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
        run(List.of(workspace.project().resolve("scripts/start").toString()),
                Duration.ofMinutes(8), "CLUSTER_START_FAILED");
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
}
