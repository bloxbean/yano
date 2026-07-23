package com.bloxbean.cardano.yano.appchain.devtools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class YanoAppChainDispatchTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @TempDir
    Path temporary;

    @Test
    void unifiedLauncherDispatchesProjectConfigAndClusterWithoutStartingNode() throws Exception {
        Path root = fakeDistribution();
        Path tool = root.resolve("tools/yano-appchain/bin/yano-appchain");
        executable(tool, "#!/usr/bin/env bash\nprintf 'tool:%s\\n' \"$*\"\n");
        Path cluster = root.resolve("appchain-cluster/cluster.sh");
        executable(cluster, "#!/usr/bin/env bash\nprintf 'cluster:%s\\n' \"$*\"\n");

        Result config = run(root.resolve("yano.sh"),
                "appchain", "config", "explain", "block.max-bytes");
        Result initialize = run(root.resolve("yano.sh"),
                "appchain", "init", "--recipe", "audit-log");
        Result clusterResult = run(root.resolve("yano.sh"),
                "appchain", "cluster", "start", "3");
        Result joinResult = run(root.resolve("yano.sh"),
                "appchain", "cluster", "node", "join", "3");
        Result effectResult = run(root.resolve("yano.sh"),
                "appchain", "cluster", "effect", "demo", "order 42 approved");

        assertThat(config.exit()).isZero();
        assertThat(config.output()).isEqualTo("tool:config explain block.max-bytes\n");
        assertThat(initialize.exit()).isZero();
        assertThat(initialize.output()).isEqualTo("tool:init --recipe audit-log\n");
        assertThat(clusterResult.exit()).isZero();
        assertThat(clusterResult.output()).isEqualTo("cluster:start 3\n");
        assertThat(joinResult.exit()).isZero();
        assertThat(joinResult.output()).isEqualTo("cluster:node join 3\n");
        assertThat(effectResult.exit()).isZero();
        assertThat(effectResult.output()).isEqualTo("cluster:effect demo order 42 approved\n");
    }

    @Test
    void nativeLayoutExplainsHowToInstallVersionMatchedDevtools() throws Exception {
        Path root = fakeDistribution();
        executable(root.resolve("yano"), "#!/usr/bin/env bash\nexit 99\n");

        Result result = run(root.resolve("yano.sh"),
                "appchain", "config", "explain", "block.max-bytes");

        assertThat(result.exit()).isOne();
        assertThat(result.error()).contains(
                        "requires version-matched app-chain tooling")
                .contains("version-matched tooling archive")
                .contains("YANO_APPCHAIN_CLI");
    }

    @Test
    void appchainHelpIsPublicAndDoesNotRequireTheInternalTooling() throws Exception {
        Path root = fakeDistribution();

        Result explicit = run(root.resolve("yano.sh"), "appchain", "help");
        Result implicit = run(root.resolve("yano.sh"), "appchain");

        assertThat(explicit.exit()).isZero();
        assertThat(implicit.exit()).isZero();
        assertThat(explicit.output()).isEqualTo(implicit.output())
                .contains("Usage: ./yano.sh appchain <command>")
                .contains("Discover capabilities:", "Create and update a project:",
                        "Validate and operate:", "Run a local cluster:")
                .contains("derived YAML config");
        assertThat(explicit.error()).isEmpty();
    }

    @Test
    void unifiedLauncherPreservesCallerRelativePathsForDeveloperTools() throws Exception {
        Path root = fakeDistribution();
        Path tool = root.resolve("tools/yano-appchain/bin/yano-appchain");
        executable(tool, "#!/usr/bin/env bash\nprintf 'cwd:%s\\n' \"$PWD\"\n");
        Path caller = Files.createDirectory(temporary.resolve("caller"));

        Result result = runFrom(caller, root.resolve("yano.sh"),
                "appchain", "config", "validate", "--mode", "project", ".");

        assertThat(result.exit()).isZero();
        assertThat(result.output()).startsWith("cwd:").endsWith("/caller\n");
    }

    @Test
    void sourceTreeWrapperUsesTheSameLauncherForClusterAndDeveloperTools() throws Exception {
        Path repository = temporary.resolve("source-repository");
        Path app = repository.resolve("app");
        Files.createDirectories(app.resolve("bin"));
        Files.createDirectories(app.resolve("config"));
        Files.copy(Path.of(System.getProperty("yano.test.repo-root")).resolve("app/bin/yano.sh"),
                app.resolve("bin/yano.sh"));
        Files.copy(Path.of(System.getProperty("yano.test.repo-root")).resolve("app/yano.sh"),
                app.resolve("yano.sh"));
        assertThat(app.resolve("bin/yano.sh").toFile().setExecutable(true)).isTrue();
        assertThat(app.resolve("yano.sh").toFile().setExecutable(true)).isTrue();

        executable(app.resolve("appchain-cluster/cluster.sh"),
                "#!/usr/bin/env bash\nprintf 'source-cluster:<%s>\\n' \"$@\"\n");
        executable(repository.resolve(
                        "appchain/appchain-devtools/build/install/yano-devtools/bin/yano-appchain"),
                "#!/usr/bin/env bash\nprintf 'source-tool:<%s>\\n' \"$@\"\n");

        Result cluster = run(app.resolve("yano.sh"),
                "appchain", "cluster", "effect", "demo", "order 42 approved");
        Result tool = run(app.resolve("yano.sh"),
                "appchain", "config", "explain", "block.max-bytes");

        assertThat(cluster.exit()).isZero();
        assertThat(cluster.output()).isEqualTo("source-cluster:<effect>\n"
                + "source-cluster:<demo>\nsource-cluster:<order 42 approved>\n");
        assertThat(tool.exit()).isZero();
        assertThat(tool.output()).isEqualTo("source-tool:<config>\n"
                + "source-tool:<explain>\nsource-tool:<block.max-bytes>\n");
    }

    @Test
    void configuredRelativeCliIsCheckedAndExecutedFromTheCallerDirectory() throws Exception {
        Path root = fakeDistribution();
        Path caller = Files.createDirectory(temporary.resolve("configured-caller"));
        executable(caller.resolve("bin/custom-appchain"),
                "#!/usr/bin/env bash\nprintf 'configured:%s\\n' \"$*\"\n");

        Result result = runFrom(caller, root.resolve("yano.sh"),
                java.util.Map.of("YANO_APPCHAIN_CLI", "bin/custom-appchain"),
                "appchain", "recipes");

        assertThat(result.exit()).isZero();
        assertThat(result.output()).isEqualTo("configured:recipes\n");
    }

    private Path fakeDistribution() throws Exception {
        Path repository = Path.of(System.getProperty("yano.test.repo-root"));
        Path root = Files.createDirectory(temporary.resolve("distribution"));
        Files.copy(repository.resolve("app/bin/yano.sh"), root.resolve("yano.sh"));
        root.resolve("yano.sh").toFile().setExecutable(true);
        return root;
    }

    private static void executable(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
        assertThat(path.toFile().setExecutable(true)).isTrue();
    }

    private static Result run(Path launcher, String... args) throws Exception {
        return runFrom(null, launcher, args);
    }

    private static Result runFrom(Path directory, Path launcher, String... args) throws Exception {
        return runFrom(directory, launcher, java.util.Map.of(), args);
    }

    private static Result runFrom(
            Path directory,
            Path launcher,
            java.util.Map<String, String> environment,
            String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add(launcher.toString());
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().putAll(environment);
        if (directory != null) builder.directory(directory.toFile());
        Process process = builder.start();
        if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            fail("yano.sh dispatch exceeded " + TIMEOUT);
        }
        return new Result(process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private record Result(int exit, String output, String error) {
    }
}
