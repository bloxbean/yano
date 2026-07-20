package com.bloxbean.cardano.yano.appchain.devtools;

import com.bloxbean.cardano.yano.appchain.config.AppChainPropertyRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppChainProjectTest {
    @TempDir
    Path temporary;

    @Test
    void embeddedDescriptorsResolveRecipesImplicationsArtifactsAndConsensusDefaults()
            throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectResolver resolver = new AppChainProjectResolver(properties, catalog);

        AppChainProjectModel.Resolution resolution = resolver.resolve(
                blueprint("evidence-publication", "rotating", List.of()));

        assertThat(catalog.recipes()).extracting(AppChainProjectModel.Recipe::id)
                .containsExactly("audit-log", "owned-registry", "evidence-publication",
                        "approval-workflow", "role-evidence", "custom-plugin");
        assertThat(resolution.selectedCapabilities()).contains(
                "state:ordered-log", "effects:publication", "sequencer:rotating", "l1:slot-feed");
        assertThat(resolution.impliedCapabilities()).containsExactly("l1:slot-feed");
        assertThat(resolution.artifacts()).contains("yano-runtime", "appchain-stdlib");
        assertThat(resolution.consensusProperties())
                .containsEntry("yano.app-chain.chains[0].effects.enabled", "true")
                .containsEntry("yano.app-chain.chains[0].sequencer.mode", "rotating")
                .containsEntry("yano.app-chain.chains[0].block.max-bytes", "4194304")
                .containsEntry("yano.app-chain.chains[0].threshold", "2");
        assertThat(resolution.bootstrapRequired()).isTrue();
        assertThat(catalog.digests().values()).allMatch(value -> value.matches("[0-9a-f]{64}"));
        assertThat(catalog.digests()).containsEntry("blueprintSchema",
                golden("appchain-blueprint.schema.json"))
                .containsEntry("lockSchema", golden("appchain-lock.schema.json"))
                .containsEntry("capabilities", golden("appchain-capability-catalog.json"))
                .containsEntry("recipes", golden("appchain-recipe-catalog.json"))
                .containsEntry("firstPartyMetadata",
                        golden("appchain-first-party-metadata.json"))
                .containsEntry("releaseIndex",
                        golden("appchain-release-capability-index.json"))
                .containsEntry("metadataTrustSchema",
                        golden("appchain-metadata-trust.schema.json"))
                .containsEntry("gitOpsLockSchema",
                        golden("appchain-gitops-lock.schema.json"));
        assertThat(catalog.releaseIndex().schemaStatus()).isEqualTo("alpha");
        assertThat(catalog.releaseIndex().stabilizationDecision())
                .isEqualTo("RETAIN_V1ALPHA1");
    }

    @Test
    void publicMemberKeysArePinnedAndCapabilityConflictsFailResolution() throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectResolver resolver = new AppChainProjectResolver(properties, catalog);
        List<String> keys = List.of("a".repeat(64), "b".repeat(64), "c".repeat(64));

        AppChainProjectModel.Resolution resolution = resolver.resolve(
                blueprint("audit-log", "fixed", keys));

        assertThat(resolution.bootstrapRequired()).isFalse();
        assertThat(resolution.consensusProperties())
                .containsEntry("yano.app-chain.chains[0].members", String.join(",", keys))
                .containsEntry("yano.app-chain.chains[0].sequencer.proposer", keys.getFirst());

        AppChainProjectModel.Blueprint conflicting = withCapabilities(
                blueprint("audit-log", "fixed", keys), List.of("state:kv-registry"));
        assertThatThrownBy(() -> resolver.resolve(conflicting))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Conflicting capabilities");
    }

    @Test
    void renderingIsByteDeterministicSecretSafeAndRefusesManualGeneratedEdits()
            throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectRenderer renderer = new AppChainProjectRenderer(
                catalog, new AppChainProjectResolver(properties, catalog));
        Path first = temporary.resolve("first");
        Path second = temporary.resolve("second");
        AppChainProjectModel.Blueprint blueprint = blueprint(
                "owned-registry", "fixed",
                List.of("a".repeat(64), "b".repeat(64), "c".repeat(64)));

        AppChainProjectModel.Lock firstLock = renderer.initialize(first, blueprint);
        AppChainProjectModel.Lock secondLock = renderer.initialize(second, blueprint);

        assertThat(fileDigests(first)).isEqualTo(fileDigests(second));
        assertThat(firstLock).isEqualTo(secondLock);
        assertThat(firstLock.generatedFiles()).containsKeys(
                "config/shared-consensus.yaml", "scripts/start", "secrets/.gitignore",
                "ci/verify", ".github/workflows/appchain-verify.yml",
                "ai/configure-yano-appchain/SKILL.md");
        assertShellSyntax(first.resolve("ci/verify"));
        assertThat(Files.readString(first.resolve(".github/workflows/appchain-verify.yml")))
                .contains("YANO_DISTRIBUTION_SHA256", "sha256sum --check", "download=(curl")
                .doesNotContain("secrets.");
        new ObjectMapper(new YAMLFactory()).readTree(
                Files.readAllBytes(first.resolve(".github/workflows/appchain-verify.yml")));
        assertThat(Files.readString(first.resolve("ai/configure-yano-appchain/SKILL.md")))
                .contains("name: configure-yano-appchain", "Never invent configuration keys");
        assertThat(Files.readString(first.resolve("secrets/node0.env.example")))
                .contains("YANO_APPCHAIN_SIGNING_KEY=")
                .contains("YANO_APPCHAIN_API_KEYS=")
                .doesNotContain("a".repeat(64));
        Path nodeConfig = first.resolve("config/nodes/node0.yaml");
        assertThat(yamlValues(nodeConfig))
                .containsEntry("yano.app-chain.validation.strict", "true")
                .containsEntry("yano.app-chain.dx.resolved-config-digest",
                        firstLock.resolvedConfigDigest())
                .containsEntry("yano.app-chain.dx.release-catalog-digest",
                        firstLock.catalogDigests().get("releaseIndex"))
                .containsEntry("yano.app-chain.api.keys", "${YANO_APPCHAIN_API_KEYS:}");
        assertThat(Files.readString(nodeConfig))
                .contains("yano:", "app-chain:", "chains:")
                .doesNotContain("yano.app-chain.");
        assertThat(yamlValues(first.resolve("config/shared-consensus.yaml")))
                .isEqualTo(firstLock.consensusValues());
        assertThat(allText(first)).doesNotContain(temporary.toString());

        Files.writeString(first.resolve("config/shared-consensus.yaml"),
                "# manual edit\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);
        assertThatThrownBy(() -> renderer.render(first))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("manual edits");
    }

    @Test
    void regenerationRefusesAUserFileCollidingWithANewRendererOutput() throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectRenderer renderer = new AppChainProjectRenderer(
                catalog, new AppChainProjectResolver(properties, catalog));
        Path project = temporary.resolve("new-output-collision");
        renderer.initialize(project, blueprint("audit-log", "fixed",
                List.of("a".repeat(64), "b".repeat(64), "c".repeat(64))));

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode lock = (ObjectNode) mapper.readTree(project.resolve("appchain.lock").toFile());
        ((ObjectNode) lock.path("generatedFiles")).remove("README.md");
        mapper.writerWithDefaultPrettyPrinter().writeValue(
                project.resolve("appchain.lock").toFile(), lock);
        Files.writeString(project.resolve("README.md"), "user-owned notes\n");

        assertThatThrownBy(() -> renderer.render(project))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("user-owned path")
                .hasMessageContaining("README.md");
        assertThat(Files.readString(project.resolve("README.md")))
                .isEqualTo("user-owned notes\n");
    }

    @Test
    void studioBlueprintCanBeMaterializedWithoutASeedLock() throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectRenderer renderer = new AppChainProjectRenderer(
                catalog, new AppChainProjectResolver(properties, catalog));
        Path source = temporary.resolve("studio-source");
        Path imported = Files.createDirectory(temporary.resolve("studio-import"));
        renderer.initialize(source, blueprint("audit-log", "fixed", List.of()));
        Files.copy(source.resolve("appchain.yaml"), imported.resolve("appchain.yaml"));

        AppChainProjectModel.Lock lock = renderer.render(imported);

        assertThat(lock.recipe()).isEqualTo("audit-log:1");
        assertThat(imported.resolve("appchain.lock")).isRegularFile();
        assertThat(imported.resolve("scripts/start")).isExecutable();
    }

    @Test
    void cliSupportsNonInteractiveAndGuidedInitializationAndSafeRegeneration()
            throws Exception {
        Path nonInteractive = temporary.resolve("registry-project");
        StringWriter output = new StringWriter();
        StringWriter error = new StringWriter();
        AppChainDevtoolsCli cli = new AppChainDevtoolsCli();

        int init = cli.run(new String[]{
                        "appchain", "init", "--non-interactive",
                        "--recipe", "owned-registry", "--network", "devnet",
                        "--members", "3", "--output", nonInteractive.toString(),
                        "--http-port-base", "18080", "--server-port-base", "23337",
                        "--format", "json"
                }, new PrintWriter(output), new PrintWriter(error));

        assertThat(init).isZero();
        assertThat(error.toString()).isEmpty();
        assertThat(output.toString()).contains("PROJECT_INITIALIZED", "owned-registry:1")
                .doesNotContain(temporary.toString());
        assertThat(nonInteractive.resolve("appchain.yaml")).isRegularFile();
        assertThat(nonInteractive.resolve("appchain.lock")).isRegularFile();
        assertThat(yamlValues(nonInteractive.resolve("config/nodes/node1.yaml")))
                .containsEntry("yano.block-producer.enabled", "false")
                .containsEntry("yano.remote.host", "127.0.0.1")
                .containsEntry("quarkus.http.port", "18081")
                .containsEntry("yano.server.port", "23338");

        output.getBuffer().setLength(0);
        int render = cli.run(new String[]{"render", nonInteractive.toString()},
                new PrintWriter(output), new PrintWriter(error));
        assertThat(render).isZero();
        assertThat(output.toString()).contains("PROJECT_RENDERED");

        Path guided = temporary.resolve("guided");
        StringWriter guidedOutput = new StringWriter();
        AppChainProjectCli projectCli = new AppChainProjectCli(
                new BufferedReader(new StringReader("audit-log\npreprod\n2\n")),
                new PrintWriter(guidedOutput), AppChainPropertyRegistry.framework(),
                new ObjectMapper());
        assertThat(projectCli.run(new String[]{"init", "--output", guided.toString()})).isZero();
        assertThat(guidedOutput.toString()).contains("Recipe [audit-log]", "PROJECT_INITIALIZED");
    }

    @Test
    void everyM1RecipeResolvesForAdvertisedRuntimeAndDeploymentTargets() throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectRenderer renderer = new AppChainProjectRenderer(
                catalog, new AppChainProjectResolver(properties, catalog));
        int sequence = 0;
        for (String recipe : List.of("audit-log", "owned-registry", "evidence-publication",
                "approval-workflow", "role-evidence", "custom-plugin")) {
            List<String> runtimes = "custom-plugin".equals(recipe)
                    ? List.of("jvm") : List.of("jvm", "native");
            for (String runtime : runtimes) {
                for (String deployment : List.of("host", "docker-compose")) {
                    AppChainProjectModel.Blueprint blueprint = withTarget(
                            blueprint(recipe, "fixed", List.of()), runtime, deployment);
                    if ("custom-plugin".equals(recipe)) {
                        blueprint = withAnswers(blueprint,
                                Map.of("stateMachine", "com.example.custom-machine"));
                    }
                    Path project = temporary.resolve("matrix-" + sequence++);

                    AppChainProjectModel.Lock lock = renderer.initialize(project, blueprint);

                    assertThat(lock.runtime()).isEqualTo(runtime);
                    assertThat(lock.deployment()).isEqualTo(deployment);
                    assertThat(lock.artifacts()).isNotEmpty();
                    assertThat(project.resolve("scripts/start")).isExecutable();
                    assertShellSyntax(project.resolve("scripts/start"));
                    assertShellSyntax(project.resolve("scripts/stop"));
                    assertShellSyntax(project.resolve("scripts/status"));
                    if ("host".equals(deployment)) {
                        assertThat(Files.readString(project.resolve("scripts/stop")))
                                .contains("did not stop within 10 seconds");
                    }
                    if (Files.exists(project.resolve("scripts/start-node"))) {
                        assertShellSyntax(project.resolve("scripts/start-node"));
                    }
                    assertThat(project.resolve("compose.yaml").toFile().exists())
                            .isEqualTo("docker-compose".equals(deployment));
                    if ("docker-compose".equals(deployment)) {
                        assertThat(Files.readString(project.resolve("compose.yaml")))
                                .contains("YANO_PROFILE: preprod")
                                .doesNotContain("YANO_PROFILE: preprod,appchain")
                                .doesNotContain("entrypoint:");
                        assertThat(yamlValues(project.resolve("config/nodes/node0.yaml")))
                                .containsEntry("yano.storage.path", "/app/chainstate");
                        assertThat(Files.readString(project.resolve("compose.yaml")))
                                .contains("node0-data:/app/chainstate")
                                .doesNotContain("node0-data:/project");
                    }
                }
            }
        }
    }

    private static void assertShellSyntax(Path script) throws Exception {
        Process process = new ProcessBuilder("bash", "-n", script.toString())
                .redirectErrorStream(true)
                .start();
        String diagnostics = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as("%s: %s", script, diagnostics).isZero();
    }

    @Test
    void hostTargetCanRenderPortablePerMachinePeerOverlays() throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectRenderer renderer = new AppChainProjectRenderer(
                catalog, new AppChainProjectResolver(properties, catalog));
        AppChainProjectModel.Blueprint blueprint = withHosts(
                blueprint("audit-log", "fixed",
                        List.of("a".repeat(64), "b".repeat(64), "c".repeat(64))),
                List.of("node-a.example", "node-b.example", "node-c.example"));
        Path project = temporary.resolve("multi-machine");

        renderer.initialize(project, blueprint);

        assertThat(yamlValues(project.resolve("config/nodes/node0.yaml")))
                .containsEntry("yano.app-chain.chains[0].peers",
                        "node-b.example:13337,node-c.example:13337")
                .containsEntry("yano.server.port", "13337");
        assertThat(yamlValues(project.resolve("config/nodes/node2.yaml")))
                .containsEntry("yano.app-chain.chains[0].peers",
                        "node-a.example:13337,node-b.example:13337")
                .containsEntry("quarkus.http.port", "8080");
        assertThat(Files.readString(project.resolve("scripts/start")))
                .contains("Usage: start NODE_INDEX")
                .doesNotContain("for node in");
    }

    @Test
    void m2ProjectLifecycleValidatesDoctorsDiffsAndMigrates() throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectRenderer renderer = new AppChainProjectRenderer(
                catalog, new AppChainProjectResolver(properties, catalog));
        AppChainProjectLifecycle lifecycle = new AppChainProjectLifecycle(properties);
        Path project = temporary.resolve("lifecycle");
        renderer.initialize(project, blueprint("approval-workflow", "fixed",
                List.of("a".repeat(64), "b".repeat(64), "c".repeat(64))));

        AppChainProjectModel.ProjectValidation validation = lifecycle.validate(project);
        assertThat(validation.lock().recipe()).isEqualTo("approval-workflow:1");
        assertThat(validation.generatedFileCount()).isGreaterThan(10);
        assertThat(Files.readString(project.resolve("docs/TRUST.md"))).contains("Trust model");
        assertThat(Files.readString(project.resolve("docs/BOOTSTRAP.md"))).contains("Bootstrap");
        assertThat(Files.readString(project.resolve("docs/VERIFY.md")))
                .contains("validate --mode project");

        Path oldLock = temporary.resolve("old.lock");
        Files.copy(project.resolve("appchain.lock"), oldLock);
        String blueprint = Files.readString(project.resolve("appchain.yaml"));
        Files.writeString(project.resolve("appchain.yaml"),
                blueprint.replace("two-thirds", "all"));
        renderer.render(project);

        AppChainProjectModel.LockDiff difference = lifecycle.diff(
                oldLock, project.resolve("appchain.lock"));
        assertThat(difference.status()).isEqualTo("CHANGESET");
        assertThat(difference.changes()).anySatisfy(change -> {
            assertThat(change.key()).endsWith(".threshold");
            assertThat(change.policy()).isEqualTo("GOVERNED_ACTIVATION");
        });
        assertThat(lifecycle.migrate(project, true)).isEqualTo(
                "NO_MIGRATION_REQUIRED_DRY_RUN");

        Path distribution = Files.createDirectory(temporary.resolve("release"));
        Files.write(distribution.resolve("yano.jar"), new byte[]{0});
        Path index = distribution.resolve(
                "tools/yano-appchain/metadata/appchain-dx/v1alpha1/"
                        + "appchain-release-capability-index.json");
        Files.createDirectories(index.getParent());
        Files.write(index, catalog.releaseIndexBytes());
        AppChainProjectModel.DoctorReport doctor = lifecycle.doctor(project, distribution);
        assertThat(doctor.status()).isEqualTo("DOCTOR_OK");
        assertThat(doctor.checks()).allMatch(check -> "PASS".equals(check.status()));
    }

    @Test
    void customPluginRecipeRequiresAnAnswerAndRejectsNativeRuntime() throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectResolver resolver = new AppChainProjectResolver(properties, catalog);
        AppChainProjectModel.Blueprint custom = blueprint(
                "custom-plugin", "fixed", List.of("a".repeat(64), "b".repeat(64), "c".repeat(64)));

        assertThatThrownBy(() -> resolver.resolve(custom))
                .hasMessageContaining("unknown variable");
        AppChainProjectModel.Resolution resolved = resolver.resolve(withAnswers(
                custom, Map.of("stateMachine", "com.example.reviewed")));
        assertThat(resolved.consensusProperties())
                .containsEntry("yano.app-chain.chains[0].state-machine",
                        "com.example.reviewed");
        assertThatThrownBy(() -> resolver.resolve(withTarget(withAnswers(custom,
                Map.of("stateMachine", "com.example.reviewed")), "native", "host")))
                .hasMessageContaining("does not support runtime native");
    }

    @Test
    void standaloneJvmToolingInspectsEveryAdvertisedNativeDistributionFlavor() throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectLifecycle lifecycle = new AppChainProjectLifecycle(properties);
        assertThat(catalog.releaseIndex().distributions())
                .filteredOn(flavor -> "native".equals(flavor.runtimeType()))
                .allMatch(flavor -> "external-version-matched".equals(flavor.tooling()));

        for (String executable : List.of("yano-native-test/yano", "yano-native-test/yano.exe")) {
            Path archive = temporary.resolve(executable.endsWith(".exe")
                    ? "native-windows.zip" : "native-unix.zip");
            try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
                output.putNextEntry(new ZipEntry(executable));
                output.write(0);
                output.closeEntry();
                output.putNextEntry(new ZipEntry(
                        "yano-native-test/config/schema/"
                                + "appchain-release-capability-index.json"));
                output.write(catalog.releaseIndexBytes());
                output.closeEntry();
            }

            AppChainProjectModel.DoctorReport doctor = lifecycle.doctor(null, archive);
            assertThat(doctor.status()).isEqualTo("DOCTOR_OK");
            assertThat(doctor.checks()).allMatch(check -> "PASS".equals(check.status()));
        }
    }

    @Test
    void doctorInspectsRootlessJvmDistributionArchives() throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectLifecycle lifecycle = new AppChainProjectLifecycle(properties);
        Path archive = temporary.resolve("rootless-jvm.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("yano.jar"));
            output.write(0);
            output.closeEntry();
            output.putNextEntry(new ZipEntry("config/schema/"
                    + "appchain-release-capability-index.json"));
            output.write(catalog.releaseIndexBytes());
            output.closeEntry();
        }

        AppChainProjectModel.DoctorReport doctor = lifecycle.doctor(null, archive);

        assertThat(doctor.status()).isEqualTo("DOCTOR_OK");
        assertThat(doctor.checks()).allMatch(check -> "PASS".equals(check.status()));
    }

    @Test
    void gitOpsExportsAreDeterministicSecretFreeAndBoundToValidatedSource() throws Exception {
        AppChainPropertyRegistry properties = AppChainPropertyRegistry.framework();
        AppChainProjectCatalog catalog = new AppChainProjectCatalog(properties);
        AppChainProjectResolver resolver = new AppChainProjectResolver(properties, catalog);
        AppChainProjectRenderer renderer = new AppChainProjectRenderer(catalog, resolver);
        AppChainProjectLifecycle lifecycle = new AppChainProjectLifecycle(properties);
        Path project = temporary.resolve("gitops-project");
        renderer.initialize(project, blueprint("owned-registry", "fixed",
                List.of("a".repeat(64), "b".repeat(64), "c".repeat(64))));

        Path first = temporary.resolve("kustomize-one");
        Path second = temporary.resolve("kustomize-two");
        AppChainProjectModel.GitOpsResult exported = lifecycle.gitOps(
                project, AppChainGitOpsExporter.Target.KUSTOMIZE, first);
        lifecycle.gitOps(project, AppChainGitOpsExporter.Target.KUSTOMIZE, second);
        Path helm = temporary.resolve("helm");
        lifecycle.gitOps(project, AppChainGitOpsExporter.Target.HELM, helm);

        assertThat(exported.status()).isEqualTo("GITOPS_EXPORTED");
        assertThat(fileDigests(first)).isEqualTo(fileDigests(second));
        assertThat(yamlValues(first.resolve("files/node0.yaml")))
                .containsEntry("yano.storage.path", "/var/lib/yano/chainstate")
                .containsEntry("yano.app-chain.chains[0].peers",
                        "node1:13337,node2:13337");
        assertThat(Files.readString(first.resolve("node0.yaml")))
                .contains("secretRef:", "yano-appchain-node0")
                .doesNotContain("YANO_APPCHAIN_SIGNING_KEY=");
        assertThat(Files.readString(first.resolve("gitops.lock")))
                .contains("sourceBlueprintDigest", "sourceResolvedConfigDigest",
                        "sourceReleaseCatalogDigest");
        assertThat(helm.resolve("Chart.yaml")).isRegularFile();
        assertThat(helm.resolve("templates/nodes.yaml")).isRegularFile();
        assertThat(allText(helm)).doesNotContain("YANO_APPCHAIN_SIGNING_KEY=");

        Path nonEmpty = Files.createDirectory(temporary.resolve("non-empty"));
        Files.writeString(nonEmpty.resolve("keep"), "user data");
        assertThatThrownBy(() -> lifecycle.gitOps(
                project, AppChainGitOpsExporter.Target.HELM, nonEmpty))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("must be empty");

        Path devnet = temporary.resolve("devnet-project");
        renderer.initialize(devnet, withNetwork(
                blueprint("audit-log", "fixed", List.of()), "devnet"));
        assertThatThrownBy(() -> lifecycle.gitOps(devnet,
                AppChainGitOpsExporter.Target.KUSTOMIZE, temporary.resolve("devnet-export")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ephemeral genesis");
    }

    private static AppChainProjectModel.Blueprint blueprint(
            String recipe,
            String sequencing,
            List<String> memberKeys) {
        return new AppChainProjectModel.Blueprint(
                AppChainProjectModel.API_VERSION,
                AppChainProjectModel.BLUEPRINT_KIND,
                new AppChainProjectModel.Metadata("product-evidence"),
                new AppChainProjectModel.Spec(
                        "0.1.0-test",
                        "preprod",
                        new AppChainProjectModel.RuntimeSelection("jvm"),
                        new AppChainProjectModel.DeploymentSelection("host"),
                        List.of(new AppChainProjectModel.ChainIntent(
                                "product-evidence", recipe, List.of(), Map.of(),
                                new AppChainProjectModel.Topology(
                                        3, memberKeys, List.of(),
                                        "two-thirds", sequencing, "static", null, null)))));
    }

    private static AppChainProjectModel.Blueprint withCapabilities(
            AppChainProjectModel.Blueprint blueprint,
            List<String> capabilities) {
        AppChainProjectModel.ChainIntent chain = blueprint.spec().chains().getFirst();
        AppChainProjectModel.ChainIntent changed = new AppChainProjectModel.ChainIntent(
                chain.chainId(), chain.recipe(), capabilities, chain.answers(), chain.topology());
        AppChainProjectModel.Spec spec = blueprint.spec();
        return new AppChainProjectModel.Blueprint(
                blueprint.apiVersion(), blueprint.kind(), blueprint.metadata(),
                new AppChainProjectModel.Spec(spec.yanoVersion(), spec.network(), spec.runtime(),
                        spec.deployment(), List.of(changed)));
    }

    private static AppChainProjectModel.Blueprint withTarget(
            AppChainProjectModel.Blueprint blueprint,
            String runtime,
            String deployment) {
        AppChainProjectModel.Spec spec = blueprint.spec();
        return new AppChainProjectModel.Blueprint(
                blueprint.apiVersion(), blueprint.kind(), blueprint.metadata(),
                new AppChainProjectModel.Spec(
                        spec.yanoVersion(), spec.network(),
                        new AppChainProjectModel.RuntimeSelection(runtime),
                        new AppChainProjectModel.DeploymentSelection(deployment),
                        spec.chains()));
    }

    private static AppChainProjectModel.Blueprint withNetwork(
            AppChainProjectModel.Blueprint blueprint,
            String network) {
        AppChainProjectModel.Spec spec = blueprint.spec();
        return new AppChainProjectModel.Blueprint(
                blueprint.apiVersion(), blueprint.kind(), blueprint.metadata(),
                new AppChainProjectModel.Spec(spec.yanoVersion(), network, spec.runtime(),
                        spec.deployment(), spec.chains()));
    }

    private static AppChainProjectModel.Blueprint withHosts(
            AppChainProjectModel.Blueprint blueprint,
            List<String> hosts) {
        AppChainProjectModel.Spec spec = blueprint.spec();
        AppChainProjectModel.ChainIntent chain = spec.chains().getFirst();
        AppChainProjectModel.Topology topology = chain.topology();
        AppChainProjectModel.Topology changedTopology = new AppChainProjectModel.Topology(
                topology.members(), topology.memberKeys(), hosts, topology.finality(),
                topology.sequencing(), topology.membership(), topology.httpPortBase(),
                topology.serverPortBase());
        AppChainProjectModel.ChainIntent changedChain = new AppChainProjectModel.ChainIntent(
                chain.chainId(), chain.recipe(), chain.capabilities(), chain.answers(), changedTopology);
        return new AppChainProjectModel.Blueprint(
                blueprint.apiVersion(), blueprint.kind(), blueprint.metadata(),
                new AppChainProjectModel.Spec(spec.yanoVersion(), spec.network(), spec.runtime(),
                        spec.deployment(), List.of(changedChain)));
    }

    private static AppChainProjectModel.Blueprint withAnswers(
            AppChainProjectModel.Blueprint blueprint,
            Map<String, String> answers) {
        AppChainProjectModel.Spec spec = blueprint.spec();
        AppChainProjectModel.ChainIntent chain = spec.chains().getFirst();
        AppChainProjectModel.ChainIntent changed = new AppChainProjectModel.ChainIntent(
                chain.chainId(), chain.recipe(), chain.capabilities(), answers, chain.topology());
        return new AppChainProjectModel.Blueprint(
                blueprint.apiVersion(), blueprint.kind(), blueprint.metadata(),
                new AppChainProjectModel.Spec(spec.yanoVersion(), spec.network(), spec.runtime(),
                        spec.deployment(), List.of(changed)));
    }

    private static Map<String, String> yamlValues(Path path) throws IOException {
        Map<String, String> values = new TreeMap<>();
        new AppChainConfigFileLoader().load(path).forEach((key, value) ->
                values.put(key, String.valueOf(value)));
        return values;
    }

    private static Map<String, String> fileDigests(Path root) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                files.put(root.relativize(path).toString(),
                        AppChainProjectCatalog.sha256(Files.readAllBytes(path)));
            }
        }
        return files;
    }

    private static String allText(Path root) throws IOException {
        StringBuilder text = new StringBuilder();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                text.append(Files.readString(path, StandardCharsets.UTF_8));
            }
        }
        return text.toString();
    }

    private static String golden(String name) throws IOException {
        java.util.Properties hashes = new java.util.Properties();
        try (var input = AppChainProjectTest.class.getClassLoader().getResourceAsStream(
                "appchain-dx/v1alpha1/metadata.sha256")) {
            if (input == null) throw new IOException("missing project metadata golden hashes");
            hashes.load(input);
        }
        return hashes.getProperty(name);
    }
}
