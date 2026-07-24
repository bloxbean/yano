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
import java.util.Map;
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
        assertThat(launcher.toFile().setExecutable(true)).isTrue();
        assertThat(release.resolve("tools/yano-appchain/bin/yano-appchain")
                .toFile().setExecutable(true)).isTrue();
        assertThat(release.resolve("studio/index.html")).isRegularFile();
        assertThat(release.resolve("studio/assets/appchain-release-capability-index.json"))
                .isRegularFile();
        assertThat(release.resolve("studio/assets/appchain-release-acceptance-index.json"))
                .isRegularFile();
        assertThat(release.resolve("skills/configure-yano-appchain/SKILL.md"))
                .isRegularFile();
        assertThat(release.resolve("skills/configure-yano-appchain/agents/openai.yaml"))
                .isRegularFile();
        assertThat(release.resolve("config/schema/appchain-metadata-trust.schema.json"))
                .isRegularFile();
        assertThat(release.resolve("config/schema/appchain-gitops-lock.schema.json"))
                .isRegularFile();
        assertThat(release.resolve("config/schema/appchain-component-catalog.schema.json"))
                .isRegularFile();
        assertThat(release.resolve(
                "config/schema/appchain-component-catalog-snapshot.schema.json"))
                .isRegularFile();
        assertThat(release.resolve("config/schema/appchain-release-acceptance-index.json"))
                .isRegularFile();
        assertStudioBlueprintRoundTrips(release, launcher);

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
                            "--recipe", recipe.id(), "--network", "preprod",
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
                    } else if ("eutxo-zeroj-preview".equals(recipe.id())) {
                        init.add("--acknowledge");
                        init.add("EUTXO_ZEROJ_UNSAFE_DEVELOPMENT_TESTNET");
                    } else if ("eutxo-ledger".equals(recipe.id())
                            || "eutxo-zeroj-validity".equals(recipe.id())) {
                        init.add("--answer");
                        init.add("eutxoGenesisAddress=addr_test1vr8nlm7example");
                        init.add("--answer");
                        init.add("eutxoGenesisLovelace=100000000");
                    } else if ("eutxo-cardano-bridge".equals(recipe.id())) {
                        init.add("--answer");
                        init.add("bridgeVaultAddress=addr_test1wzvault");
                        init.add("--answer");
                        init.add("bridgeVaultScriptHash=" + "1".repeat(56));
                        init.add("--answer");
                        init.add("bridgeMaxDepositLovelace=100000000");
                        init.add("--answer");
                        init.add("bridgeWithdrawalAddress=addr_test1vwithdrawals");
                        init.add("--answer");
                        init.add("bridgeEpoch=1");
                        init.add("--answer");
                        init.add("bridgeMaxWithdrawalLovelace=50000000");
                        init.add("--answer");
                        init.add("bridgeMaxPendingWithdrawals=100");
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

                    byte[] firstLock = Files.readAllBytes(project.resolve("appchain.lock"));
                    Result rendered = run(launcher, List.of(
                            "appchain", "render", project.toString(), "--format", "json"));
                    assertThat(rendered.exit()).as(rendered.error()).isZero();
                    assertThat(Files.readAllBytes(project.resolve("appchain.lock")))
                            .isEqualTo(firstLock);

                    for (String target : List.of("helm", "kustomize")) {
                        Path output = temporary.resolve("gitops").resolve(
                                recipe.id() + "-" + runtime + "-" + deployment + "-" + target);
                        Result exported = run(launcher, List.of(
                                "appchain", "gitops", project.toString(), "--target", target,
                                "--output", output.toString(), "--format", "json"));
                        assertThat(exported.exit()).as(exported.error()).isZero();
                        assertThat(exported.output()).contains("GITOPS_EXPORTED");
                        assertThat(output.resolve("gitops.lock")).isRegularFile();
                    }

                    if ("host".equals(deployment)) {
                        assertThat(project.resolve("scripts/start-node")).isRegularFile();
                        assertThat(project.resolve("compose.yaml")).doesNotExist();
                    } else {
                        assertThat(project.resolve("compose.yaml")).isRegularFile();
                        assertThat(project.resolve("scripts/start-node")).doesNotExist();
                    }
                    if ("jvm".equals(runtime)) {
                        Result ci = run(project.resolve("ci/verify"), List.of(),
                                Map.of("YANO_HOME", release.toString()));
                        assertThat(ci.exit()).as("stdout=%s%n stderr=%s",
                                ci.output(), ci.error()).isZero();
                        assertThat(ci.output()).contains("VALID_PROJECT");
                    }

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
        int advertised = catalog.recipes().stream()
                .mapToInt(recipe -> recipe.runtimeTypes().size()
                        * recipe.deploymentTargets().size())
                .sum();
        assertThat(accepted).isEqualTo(advertised);
        assertThat(catalog.releaseAcceptanceIndex().recipes()).hasSameSizeAs(catalog.recipes());
    }

    @Test
    void finalDistributionRunsEutxoValidityLifecyclePolicy()
            throws Exception {
        Path archive = Path.of(System.getProperty(
                        "yano.test.final-yano-dist-zip"))
                .toAbsolutePath().normalize();
        Path release = extractRelease(
                archive, temporary.resolve("validity-release"));
        Path launcher = release.resolve("yano.sh");
        assertThat(launcher.toFile().setExecutable(true)).isTrue();
        assertThat(release.resolve(
                        "tools/yano-appchain/bin/yano-appchain")
                .toFile().setExecutable(true)).isTrue();
        assertThat(release.resolve(
                "config/schema/eutxo-zk-network-acceptance.schema.json"))
                .isRegularFile();
        Path evidence = release.resolve(
                "evidence/eutxo-zk/network-acceptance-v1.json");
        assertThat(evidence).isRegularFile();
        assertThat(Files.readString(evidence))
                .contains("\"authorizationProfile\""
                                + ": \"zeroj-jubjub-dev-v1\"",
                        "\"liveDepositToWithdrawal\"",
                        "\"status\": \"NOT_EXERCISED\"");

        Path devnet = temporary.resolve("payments-zk-devnet");
        Result initialized = run(launcher,
                previewInit(devnet, "devnet", false));
        assertThat(initialized.exit()).as(initialized.error()).isZero();
        Result validated = run(launcher, List.of(
                "appchain", "config", "validate",
                "--mode", "project", devnet.toString(),
                "--format", "json"));
        assertThat(validated.exit()).as(validated.error()).isZero();
        Result bootstrapped = run(launcher, List.of(
                "appchain", "validity", "bootstrap",
                "--project", devnet.toString()));
        assertThat(bootstrapped.exit()).as(bootstrapped.error()).isZero();
        assertThat(bootstrapped.output())
                .contains("CONTRACTS_PLANNED_CEREMONY_REQUIRED",
                        "zeroj-jubjub-dev-v1",
                        "disposable-test-funds-only");
        assertThat(Files.readString(devnet.resolve(
                "runtime/validity/contract-plan.json")))
                .contains("PLANNED_NOT_SUBMITTED");
        Result status = run(launcher, List.of(
                "appchain", "validity", "status",
                "--project", devnet.toString()));
        assertThat(status.exit()).as(status.error()).isZero();
        assertThat(status.output())
                .contains("CONTRACTS_PLANNED_CEREMONY_REQUIRED",
                        "cardano-payment-b16");

        for (String network : List.of("preview", "preprod")) {
            Path missingAcknowledgement = temporary.resolve(
                    "payments-zk-" + network + "-rejected");
            Result rejected = run(launcher, previewInit(
                    missingAcknowledgement, network, false));
            assertThat(rejected.exit()).isNotZero();
            assertThat(rejected.error())
                    .contains("EUTXO_ZEROJ_UNSAFE_DEVELOPMENT_TESTNET");

            Path acknowledged = temporary.resolve(
                    "payments-zk-" + network);
            Result accepted = run(launcher, previewInit(
                    acknowledged, network, true));
            assertThat(accepted.exit()).as(accepted.error()).isZero();
            assertThat(Files.readString(
                    acknowledged.resolve("appchain.lock")))
                    .contains("EUTXO_ZEROJ_UNSAFE_DEVELOPMENT_TESTNET");
            Result publicBootstrap = run(launcher, List.of(
                    "appchain", "validity", "bootstrap",
                    "--project", acknowledged.toString()));
            assertThat(publicBootstrap.exit())
                    .as(publicBootstrap.error()).isZero();
        }

        Result mainnet = run(launcher, previewInit(
                temporary.resolve("payments-zk-mainnet"),
                "mainnet", true));
        assertThat(mainnet.exit()).isNotZero();
        assertThat(mainnet.error().toLowerCase())
                .contains("mainnet");
    }

    private static List<String> previewInit(
            Path output,
            String network,
            boolean acknowledge
    ) {
        List<String> command = new ArrayList<>(List.of(
                "appchain", "init", "--non-interactive",
                "--recipe", "eutxo-zeroj-preview",
                "--network", network,
                "--members", "3",
                "--runtime", "jvm",
                "--deployment", "host",
                "--name", output.getFileName().toString(),
                "--chain-id", output.getFileName().toString(),
                "--output", output.toString(),
                "--format", "json"));
        for (String memberKey : MEMBER_KEYS) {
            command.add("--member-key");
            command.add(memberKey);
        }
        if (acknowledge) {
            command.add("--acknowledge");
            command.add(
                    "EUTXO_ZEROJ_UNSAFE_DEVELOPMENT_TESTNET");
        }
        return command;
    }

    private void assertStudioBlueprintRoundTrips(Path release, Path launcher) throws Exception {
        Path project = Files.createDirectory(temporary.resolve("studio-round-trip"));
        String script = """
                import fs from 'node:fs';
                const core = await import(process.argv[1]);
                const release = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
                const intent = {
                  recipe:'audit-log', network:'devnet', members:3,
                  finality:'two-thirds', sequencing:'fixed', runtime:'jvm',
                  deployment:'host', name:'studio-round-trip', chainId:'studio-round-trip'
                };
                process.stdout.write(core.blueprintYaml(intent, release.yanoVersion));
                """;
        Process node = new ProcessBuilder("node", "--input-type=module", "-e", script,
                release.resolve("studio/studio-core.mjs").toUri().toString(),
                release.resolve("studio/assets/appchain-release-capability-index.json").toString())
                .start();
        assertThat(node.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        String yaml = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String error = new String(node.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(node.exitValue()).as(error).isZero();
        Files.writeString(project.resolve("appchain.yaml"), yaml, StandardCharsets.UTF_8);

        Result rendered = run(launcher, List.of("appchain", "render",
                project.toString(), "--format", "json"));

        assertThat(rendered.exit()).as(rendered.error()).isZero();
        assertThat(rendered.output()).contains("PROJECT_RENDERED");
        assertThat(project.resolve("appchain.lock")).isRegularFile();
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
        return run(launcher, arguments, Map.of());
    }

    private Result run(
            Path launcher,
            List<String> arguments,
            Map<String, String> environment) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("bash");
        command.add(launcher.toString());
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).directory(temporary.toFile());
        builder.environment().putAll(environment);
        Process process = builder.start();
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
