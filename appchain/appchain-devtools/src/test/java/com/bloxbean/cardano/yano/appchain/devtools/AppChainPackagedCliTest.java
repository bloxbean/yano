package com.bloxbean.cardano.yano.appchain.devtools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class AppChainPackagedCliTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @TempDir
    Path temporary;

    @Test
    void installedAndStandaloneZipLaunchersContainRunnableVersionedMetadata() throws Exception {
        Path install = configuredPath("yano.test.appchain-cli-install-dir");
        Path distribution = configuredPath("yano.test.appchain-cli-dist-zip");
        Path launcher = launcher(install);
        Path config = temporary.resolve("application-appchain.yml");
        Files.writeString(config, """
                yano:
                  app-chain:
                    chain-id: packaged-test
                """, StandardCharsets.UTF_8);

        Result validate = run(launcher, "config", "validate", "--mode", "template",
                config.toString());
        Result explain = run(launcher, "config", "explain", "block.max-bytes");

        String signingKey = "b".repeat(64);
        Path resolvedConfig = temporary.resolve("resolved-appchain.yml");
        Files.writeString(resolvedConfig, """
                yano:
                  app-chain:
                    enabled: true
                    chain-id: packaged-resolved-test
                    signing-key: %s
                    members: %s
                    threshold: 1
                """.formatted(signingKey, "a".repeat(64)), StandardCharsets.UTF_8);
        Result resolved = run(launcher, "config", "validate", "--mode", "resolved",
                "--format", "json", "--config", resolvedConfig.toString());
        Result effective = run(launcher, "config", "effective", "--mode", "resolved",
                "--format", "json", "--show-sources", "--config", resolvedConfig.toString());

        assertThat(validate.exitCode()).isZero();
        assertThat(validate.output()).contains("VALID_TEMPLATE");
        assertThat(validate.error()).isEmpty();
        assertThat(explain.exitCode()).isZero();
        assertThat(explain.output()).contains("PROPERTY\tyano.app-chain.block.max-bytes");
        assertThat(resolved.exitCode()).isZero();
        assertThat(resolved.output()).contains("\"status\":\"VALID_RESOLVED\"")
                .doesNotContain(signingKey);
        assertThat(resolved.error()).isEmpty();
        assertThat(effective.exitCode()).isZero();
        assertThat(effective.output()).contains("<redacted>", "resolved-appchain.yml")
                .doesNotContain(signingKey)
                .doesNotContain(temporary.toString());
        assertThat(effective.error()).isEmpty();

        try (ZipFile archive = new ZipFile(distribution.toFile())) {
            List<String> entries = archive.stream().map(entry -> entry.getName()).toList();
            assertThat(entries).anyMatch(name -> name.endsWith("/bin/yano-appchain"));
            assertThat(entries).anyMatch(name ->
                    name.endsWith("/metadata/appchain-dx/v1/appchain-runtime.schema.json"));
            assertThat(entries).anyMatch(name ->
                    name.endsWith("/metadata/appchain-dx/v1/appchain-property-catalog.json"));
        }
    }

    private static Path configuredPath(String property) {
        String value = System.getProperty(property);
        assertThat(value).as(property).isNotBlank();
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static Path launcher(Path install) {
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).contains("win");
        Path path = install.resolve("bin").resolve(windows ? "yano-appchain.bat" : "yano-appchain");
        assertThat(path).isRegularFile();
        return path;
    }

    private Result run(Path launcher, String... arguments) throws Exception {
        boolean windows = launcher.getFileName().toString().endsWith(".bat");
        List<String> command = new ArrayList<>();
        if (windows) {
            command.addAll(List.of("cmd.exe", "/d", "/c"));
        }
        command.add(launcher.toString());
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(temporary.toFile()).start();
        if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            fail("packaged app-chain CLI exceeded " + TIMEOUT);
        }
        return new Result(process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private record Result(int exitCode, String output, String error) {
    }
}
