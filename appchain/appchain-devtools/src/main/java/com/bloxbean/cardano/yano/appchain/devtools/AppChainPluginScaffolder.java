package com.bloxbean.cardano.yano.appchain.devtools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Creates small, buildable JVM plugin starting points without executing build logic. */
final class AppChainPluginScaffolder {
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    private static final Pattern JAVA_PACKAGE = Pattern.compile(
            "[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*){1,15}");
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.+_-]{0,127}");
    private static final Set<String> MODES = Set.of(
            "state-machine", "composite-role", "effect-executor", "sink");

    Result scaffold(
            String mode,
            String componentId,
            String packageName,
            String yanoVersion,
            Path output) throws IOException {
        if (!MODES.contains(mode)) {
            throw new IllegalArgumentException(
                    "plugin scaffold mode must be state-machine, composite-role, "
                            + "effect-executor, or sink");
        }
        if (componentId == null || !ID.matcher(componentId).matches()) {
            throw new IllegalArgumentException("plugin component id is invalid");
        }
        if (packageName == null || !JAVA_PACKAGE.matcher(packageName).matches()) {
            throw new IllegalArgumentException("plugin Java package is invalid");
        }
        if (yanoVersion == null || !VERSION.matcher(yanoVersion).matches()) {
            throw new IllegalArgumentException("Yano version is invalid");
        }
        Path root = output.toAbsolutePath().normalize();
        requireMissingOrEmpty(root);
        Files.createDirectories(root);

        String classStem = classStem(componentId);
        String providerClass = switch (mode) {
            case "state-machine", "composite-role" -> classStem + "StateMachineProvider";
            case "effect-executor" -> classStem + "EffectExecutorFactory";
            case "sink" -> classStem + "SinkFactory";
            default -> throw new IllegalStateException("unreachable scaffold mode");
        };
        String bundleId = "plugin-bundle." + componentId;
        String artifactId = "plugin." + componentId;
        String catalogId = componentId + "-plugin";
        String contributionKind = switch (mode) {
            case "state-machine", "composite-role" -> "app-state-machine";
            case "effect-executor" -> "effect-executor";
            case "sink" -> "finalized-sink";
            default -> throw new IllegalStateException("unreachable scaffold mode");
        };

        write(root.resolve("settings.gradle"),
                "rootProject.name = '" + componentId + "-yano-plugin'\n");
        write(root.resolve("build.gradle"), buildGradle(yanoVersion));
        write(root.resolve("README.md"), readme(mode, componentId, packageName,
                providerClass, bundleId));
        Path javaFile = root.resolve("src/main/java")
                .resolve(packageName.replace('.', '/')).resolve(providerClass + ".java");
        write(javaFile, javaSource(mode, componentId, packageName, providerClass));

        Path resources = root.resolve("src/main/resources");
        String service = switch (mode) {
            case "state-machine", "composite-role" ->
                    "com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider";
            case "effect-executor" ->
                    "com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutorFactory";
            case "sink" ->
                    "com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSinkFactory";
            default -> throw new IllegalStateException("unreachable scaffold mode");
        };
        write(resources.resolve("META-INF/services").resolve(service),
                packageName + "." + providerClass + "\n");
        write(resources.resolve("META-INF/yano/plugins/" + bundleId + ".json"),
                runtimeManifest(bundleId, componentId, contributionKind,
                        packageName + "." + providerClass));
        write(resources.resolve(AppChainComponentCatalogLoader.CATALOG_PATH),
                componentCatalog(mode, catalogId, bundleId, artifactId, componentId));

        return new Result(root, mode, componentId, providerClass, catalogId);
    }

    private static void requireMissingOrEmpty(Path output) throws IOException {
        if (!Files.exists(output, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(output) || !Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("plugin scaffold output must be a missing or empty directory");
        }
        try (var contents = Files.list(output)) {
            if (contents.findAny().isPresent()) {
                throw new IOException("plugin scaffold output directory is not empty");
            }
        }
    }

    private static void write(Path path, String content) throws IOException {
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static String buildGradle(String yanoVersion) {
        return """
                plugins {
                    id 'java-library'
                }

                repositories {
                    mavenCentral()
                }

                dependencies {
                    compileOnly 'com.bloxbean.cardano:yano-core-api:%s'
                }

                java {
                    toolchain { languageVersion = JavaLanguageVersion.of(25) }
                }
                """.formatted(yanoVersion);
    }

    private static String readme(
            String mode,
            String id,
            String packageName,
            String providerClass,
            String bundleId) {
        String extra = "composite-role".equals(mode)
                ? "This mode is a neutral provider shell. Add appchain-composite and "
                + "appchain-role-workflow dependencies, then assemble only the components "
                + "your domain needs.\n\n"
                : "";
        return """
                # %s Yano plugin

                Mode: `%s`

                This scaffold is intentionally small. Implement and test `%s.%s`; the generated
                provider currently performs no application work. Yano does not trust or install
                this plugin automatically.

                %sBuild with `gradle jar` (or add your organization's Gradle wrapper). Before use:

                1. Review the runtime manifest and component product catalog.
                2. Sign their exact bytes with `./yano.sh appchain plugin sign`.
                3. Package the generated signature as
                   `%s` in the JAR.
                4. Validate/export it with `./yano.sh appchain plugin validate`.
                5. Copy the reviewed JAR to every JVM node's `plugins/` directory.

                Bundle ID: `%s`
                """.formatted(id, mode, packageName, providerClass, extra,
                AppChainComponentCatalogLoader.SIGNATURE_PATH, bundleId);
    }

    private static String javaSource(
            String mode,
            String id,
            String packageName,
            String providerClass) {
        return switch (mode) {
            case "state-machine", "composite-role" -> """
                    package %s;

                    import com.bloxbean.cardano.yano.api.appchain.AppBlock;
                    import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
                    import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
                    import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;

                    public final class %s implements AppStateMachineProvider {
                        @Override
                        public String id() {
                            return "%s";
                        }

                        @Override
                        public AppStateMachine create() {
                            return new Machine();
                        }

                        private static final class Machine implements AppStateMachine {
                            @Override
                            public String id() {
                                return "%s";
                            }

                            @Override
                            public AdmissionResult validate(
                                    com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage message) {
                                return AdmissionResult.reject("plugin scaffold is not implemented");
                            }

                            @Override
                            public void apply(AppBlock block, AppStateWriter writer) {
                                // Implement one deterministic transition before enabling admission.
                            }
                        }
                    }
                    """.formatted(packageName, providerClass, id, id);
            case "effect-executor" -> """
                    package %s;

                    import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
                    import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutorFactory;
                    import java.util.List;
                    import java.util.Map;

                    public final class %s implements AppEffectExecutorFactory {
                        @Override
                        public String scheme() {
                            return "%s";
                        }

                        @Override
                        public List<AppEffectExecutor> create(
                                String chainId, Map<String, String> config) {
                            // Return fresh, configured executors when required settings are present.
                            return List.of();
                        }
                    }
                    """.formatted(packageName, providerClass, id);
            case "sink" -> """
                    package %s;

                    import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSink;
                    import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSinkFactory;
                    import java.util.List;
                    import java.util.Map;

                    public final class %s implements FinalizedStreamSinkFactory {
                        @Override
                        public String scheme() {
                            return "%s";
                        }

                        @Override
                        public List<FinalizedStreamSink> create(
                                String chainId, Map<String, String> config) {
                            // Return fresh, configured sinks when required settings are present.
                            return List.of();
                        }
                    }
                    """.formatted(packageName, providerClass, id);
            default -> throw new IllegalStateException("unreachable scaffold mode");
        };
    }

    private static String runtimeManifest(
            String bundleId,
            String componentId,
            String kind,
            String provider) {
        return """
                {
                  "schemaVersion": 1,
                  "id": "%s",
                  "version": "1.0.0",
                  "yanoApi": { "min": 1, "max": 1, "minLevel": 1 },
                  "dependencies": [],
                  "contributions": [
                    { "kind": "%s", "name": "%s", "provider": "%s" }
                  ]
                }
                """.formatted(bundleId, kind, componentId, provider);
    }

    private static String componentCatalog(
            String mode,
            String catalogId,
            String bundleId,
            String artifactId,
            String componentId) {
        String category = switch (mode) {
            case "state-machine", "composite-role" -> "state";
            case "effect-executor" -> "effect-executor";
            case "sink" -> "finalized-sink";
            default -> throw new IllegalStateException("unreachable scaffold mode");
        };
        String capabilityId = switch (mode) {
            case "state-machine", "composite-role" -> "state:" + componentId;
            case "effect-executor" -> "executor:" + componentId;
            case "sink" -> "sink:" + componentId;
            default -> throw new IllegalStateException("unreachable scaffold mode");
        };
        String provided = switch (mode) {
            case "state-machine", "composite-role" -> "state-machine";
            case "effect-executor" -> "effect-executor:" + componentId;
            case "sink" -> "finalized-sink:" + componentId;
            default -> throw new IllegalStateException("unreachable scaffold mode");
        };
        String properties = switch (mode) {
            case "state-machine", "composite-role" ->
                    "{\"state-machine\":\"" + componentId + "\"}";
            default -> "{}";
        };
        return """
                {
                  "schemaVersion": "v1alpha1",
                  "catalogId": "%s",
                  "bundleId": "%s",
                  "bundleVersion": "1.0.0",
                  "artifact": {
                    "id": "%s", "availability": "REFERENCE", "bundleId": "%s",
                    "nativePosture": "unsupported", "runtimeTypes": ["jvm"],
                    "deploymentTargets": ["host", "docker-compose"]
                  },
                  "capabilities": [
                    {
                      "id": "%s", "name": "%s", "category": "%s",
                      "availability": "REFERENCE", "maturity": "experimental",
                      "scope": "chain", "selectable": true,
                      "trustStatement": "Operator-owned custom JVM plugin; review before use.",
                      "description": "Generated custom %s plugin starting point.",
                      "provides": ["%s"], "requires": [], "implies": [], "conflicts": [],
                      "runtimeTypes": ["jvm"],
                      "deploymentTargets": ["host", "docker-compose"],
                      "artifacts": ["%s"], "nativePosture": "unsupported",
                      "externalPrerequisites": ["reviewed-plugin-jar"],
                      "bootstrapRequirements": ["copy-plugin-to-every-jvm-node"],
                      "nonSecretAnswers": [], "secretReferences": {},
                      "properties": %s, "documentation": "README.md",
                      "acceptanceScenario": "custom-plugin-owned"
                    }
                  ]
                }
                """.formatted(catalogId, bundleId, artifactId, bundleId, capabilityId,
                title(componentId), category, mode, provided, artifactId, properties);
    }

    private static String classStem(String id) {
        StringBuilder result = new StringBuilder();
        for (String part : id.split("-")) {
            if (part.isEmpty()) continue;
            result.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append(part.substring(1));
        }
        return result.toString();
    }

    private static String title(String id) {
        return String.join(" ", List.of(id.split("-"))).toUpperCase(Locale.ROOT);
    }

    record Result(Path output, String mode, String componentId,
                  String providerClass, String catalogId) {
    }
}
