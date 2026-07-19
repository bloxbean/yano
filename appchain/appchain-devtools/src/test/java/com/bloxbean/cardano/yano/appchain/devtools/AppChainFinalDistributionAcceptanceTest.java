package com.bloxbean.cardano.yano.appchain.devtools;

import com.bloxbean.cardano.yano.appchain.config.AppChainPropertyRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/** Black-box matrix gate that executes the CLI from the final Yano release archive. */
class AppChainFinalDistributionAcceptanceTest {
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_ARCHIVE_ENTRIES = 50_000;
    private static final String[] MEMBER_KEYS = {
            "8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c",
            "8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394",
            "ed4928c628d1c2c6eae90338905995612959273a5c63f93636c14614ac8737d1"
    };

    @TempDir
    Path temporary;

    @Test
    void finalDistributionGeneratesAndValidatesEveryAdvertisedCombination() throws Exception {
        Path archive = Path.of(System.getProperty("yano.test.final-yano-dist-zip"))
                .toAbsolutePath().normalize();
        Path release = extractRelease(archive, temporary.resolve("release"));
        Path launcher = release.resolve("yano.sh");
        assertThat(launcher).isRegularFile();
        assertThat(release.resolve("tools/yano-appchain/bin/yano-appchain")
                .toFile().setExecutable(true)).isTrue();

        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectLifecycle lifecycle = new AppChainProjectLifecycle(properties);
        int accepted = 0;
        for (AppChainProjectModel.Recipe recipe : catalog.recipes()) {
            for (String runtime : recipe.runtimeTypes()) {
                for (String deployment : recipe.deploymentTargets()) {
                    Path project = temporary.resolve("matrix").resolve(
                            recipe.id() + "-" + runtime + "-" + deployment);
                    List<String> init = new ArrayList<>(List.of(
                            "appchain", "init", "--non-interactive",
                            "--recipe", recipe.id(), "--network", "devnet",
                            "--members", "3", "--runtime", runtime,
                            "--deployment", deployment,
                            "--output", project.toString(), "--format", "json"));
                    for (String memberKey : MEMBER_KEYS) {
                        init.add("--member-key");
                        init.add(memberKey);
                    }
                    if ("custom-plugin".equals(recipe.id())) {
                        init.add("--answer");
                        init.add("stateMachine=com.example.acceptance");
                    }

                    Result initialized = run(launcher, init);
                    assertThat(initialized.exit()).as(initialized.error()).isZero();
                    assertThat(initialized.output()).contains("PROJECT_INITIALIZED")
                            .doesNotContain(temporary.toString());
                    Result validated = run(launcher, List.of(
                            "appchain", "config", "validate", "--mode", "project",
                            project.toString(), "--format", "json"));
                    assertThat(validated.exit()).as(validated.error()).isZero();
                    assertThat(validated.output()).contains("VALID_PROJECT");

                    AppChainProjectModel.ProjectValidation projectValidation =
                            lifecycle.validate(project);
                    assertThat(projectValidation.lock().runtime()).isEqualTo(runtime);
                    assertThat(projectValidation.lock().deployment()).isEqualTo(deployment);
                    assertThat(projectValidation.lock().acknowledgements())
                            .doesNotContain("PUBLIC_MEMBER_IDENTITIES_REQUIRED_BEFORE_START");
                    assertTrackedOutputIsPortableAndSecretFree(project);
                    accepted++;
                }
            }
        }
        assertThat(accepted).isEqualTo(22);
    }

    private Path extractRelease(Path archive, Path output) throws IOException {
        Files.createDirectories(output);
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            if (zip.size() > MAX_ARCHIVE_ENTRIES) fail("Yano distribution has too many entries");
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path target = output.resolve(entry.getName()).normalize();
                if (!target.startsWith(output)) fail("Unsafe distribution entry");
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (var input = zip.getInputStream(entry)) {
                        Files.copy(input, target);
                    }
                }
            }
        }
        try (var children = Files.list(output)) {
            List<Path> roots = children.filter(Files::isDirectory).toList();
            assertThat(roots).hasSize(1);
            return roots.getFirst();
        }
    }

    private static void assertTrackedOutputIsPortableAndSecretFree(Path project)
            throws IOException {
        StringBuilder tracked = new StringBuilder();
        try (var paths = Files.walk(project)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                tracked.append(Files.readString(path, StandardCharsets.UTF_8));
            }
        }
        assertThat(tracked.toString())
                .doesNotContain("0101010101010101010101010101010101010101010101010101010101010101")
                .doesNotContain("0202020202020202020202020202020202020202020202020202020202020202")
                .doesNotContain(temporaryPathMarker(project));
    }

    private static String temporaryPathMarker(Path project) {
        Path matrix = project.getParent();
        return matrix == null || matrix.getParent() == null
                ? "path-that-must-not-appear" : matrix.getParent().toString();
    }

    private Result run(Path launcher, List<String> arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("bash");
        command.add(launcher.toString());
        command.addAll(arguments);
        Process process = new ProcessBuilder(command).directory(temporary.toFile()).start();
        if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            fail("final-distribution CLI exceeded " + PROCESS_TIMEOUT);
        }
        return new Result(process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private record Result(int exit, String output, String error) {
    }
}
