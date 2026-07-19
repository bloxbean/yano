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
    void unifiedLauncherDispatchesConfigAndClusterWithoutStartingNode() throws Exception {
        Path root = fakeDistribution();
        Path tool = root.resolve("tools/yano-appchain/bin/yano-appchain");
        executable(tool, "#!/usr/bin/env bash\nprintf 'tool:%s\\n' \"$*\"\n");
        Path cluster = root.resolve("appchain-cluster/cluster.sh");
        executable(cluster, "#!/usr/bin/env bash\nprintf 'cluster:%s\\n' \"$*\"\n");

        Result config = run(root.resolve("yano.sh"),
                "appchain", "config", "explain", "block.max-bytes");
        Result clusterResult = run(root.resolve("yano.sh"),
                "appchain", "cluster", "start", "3");

        assertThat(config.exit()).isZero();
        assertThat(config.output()).isEqualTo("tool:config explain block.max-bytes\n");
        assertThat(clusterResult.exit()).isZero();
        assertThat(clusterResult.output()).isEqualTo("cluster:start 3\n");
    }

    @Test
    void nativeLayoutExplainsHowToInstallVersionMatchedDevtools() throws Exception {
        Path root = fakeDistribution();
        executable(root.resolve("yano"), "#!/usr/bin/env bash\nexit 99\n");

        Result result = run(root.resolve("yano.sh"),
                "appchain", "config", "explain", "block.max-bytes");

        assertThat(result.exit()).isOne();
        assertThat(result.error()).contains("App-chain developer tools were not found")
                .contains("version-matched yano-devtools")
                .contains("YANO_APPCHAIN_CLI");
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
        List<String> command = new java.util.ArrayList<>();
        command.add(launcher.toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).start();
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
