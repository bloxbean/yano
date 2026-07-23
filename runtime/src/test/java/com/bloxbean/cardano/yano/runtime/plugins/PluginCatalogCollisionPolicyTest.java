package com.bloxbean.cardano.yano.runtime.plugins;

import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSinkFactory;
import com.bloxbean.cardano.yano.api.config.PluginsOptions;
import com.bloxbean.cardano.yano.api.plugin.PluginDigestMode;
import com.bloxbean.cardano.yano.api.plugin.PluginSelectionStatus;
import com.bloxbean.cardano.yano.catalog.BundleContribution;
import com.bloxbean.cardano.yano.catalog.BundleManifest;
import com.bloxbean.cardano.yano.catalog.ContributionKind;
import com.bloxbean.cardano.yano.catalog.IndexedBundle;
import com.bloxbean.cardano.yano.catalog.PluginIndex;
import com.bloxbean.cardano.yano.catalog.PluginIndexCodec;
import com.bloxbean.cardano.yano.catalog.SemVersion;
import com.bloxbean.cardano.yano.catalog.YanoApiRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginCatalogCollisionPolicyTest {
    private static final String ALPHA_ID = "com.example.collision.alpha";
    private static final String BETA_ID = "com.example.collision.beta";
    private static final String ALPHA_PROVIDER = "com.example.collision.AlphaSinkFactory";
    private static final String BETA_PROVIDER = "com.example.collision.BetaSinkFactory";
    private static final String SHARED_SCHEME = "shared-sink";
    private static final String SINK_SERVICE = "META-INF/services/"
            + FinalizedStreamSinkFactory.class.getName();

    @TempDir
    Path temporary;

    @Test
    void policyExcludedAlternativeIsAcceptedInEveryDiscoveryMode() throws Exception {
        Fixture fixture = fixture();
        PluginsOptions alphaOnly = new PluginsOptions(
                true, false, Set.of(ALPHA_ID), Set.of(), Map.of());

        for (Mode mode : Mode.values()) {
            try (Opened opened = open(mode, fixture, alphaOnly)) {
                assertThat(opened.environment().selectedBundleIds())
                        .as(mode + " selected bundle")
                        .containsExactly(ALPHA_ID);
                assertThat(opened.environment().providers()
                        .find(FinalizedStreamSinkFactory.class, SHARED_SCHEME))
                        .as(mode + " selected contribution")
                        .isPresent();

                Map<String, PluginSelectionStatus> statuses = new LinkedHashMap<>();
                opened.environment().catalog().bundles().forEach(bundle ->
                        statuses.put(bundle.id(), bundle.selectionStatus()));
                assertThat(statuses)
                        .as(mode + " policy inventory")
                        .containsExactly(
                                Map.entry(ALPHA_ID, PluginSelectionStatus.SELECTED),
                                Map.entry(BETA_ID, PluginSelectionStatus.NOT_ALLOW_LISTED));
            }
        }
    }

    @Test
    void selectingCollidingAlternativesFailsDeterministicallyInEveryDiscoveryMode()
            throws Exception {
        Fixture fixture = fixture();
        String diagnostic = "Duplicate selected contribution 'finalized-sink/"
                + SHARED_SCHEME + "' from bundles '" + ALPHA_ID + "' and '" + BETA_ID + "'";

        for (Mode mode : Mode.values()) {
            assertThatThrownBy(() -> {
                try (Opened ignored = open(mode, fixture, PluginsOptions.defaults())) {
                    // Closing an unexpectedly successful open keeps the regression leak-free.
                }
            })
                    .as(mode + " selected collision")
                    .isInstanceOf(PluginCatalogActivationException.class)
                    .hasMessageContaining(diagnostic);
        }
    }

    private Fixture fixture() throws Exception {
        Path classes = compileProviders();
        byte[] alphaClass = Files.readAllBytes(
                classes.resolve(ALPHA_PROVIDER.replace('.', '/') + ".class"));
        byte[] betaClass = Files.readAllBytes(
                classes.resolve(BETA_PROVIDER.replace('.', '/') + ".class"));

        Path directory = Files.createDirectory(temporary.resolve("directory-plugins"));
        writeJar(directory.resolve("alpha.jar"), Map.of(
                ALPHA_PROVIDER.replace('.', '/') + ".class", alphaClass,
                SINK_SERVICE, (ALPHA_PROVIDER + "\n").getBytes(StandardCharsets.UTF_8),
                manifestPath(ALPHA_ID), manifest(ALPHA_ID, ALPHA_PROVIDER)));
        writeJar(directory.resolve("beta.jar"), Map.of(
                BETA_PROVIDER.replace('.', '/') + ".class", betaClass,
                SINK_SERVICE, (BETA_PROVIDER + "\n").getBytes(StandardCharsets.UTF_8),
                manifestPath(BETA_ID), manifest(BETA_ID, BETA_PROVIDER)));

        PluginIndex index = new PluginIndex(
                PluginIndex.CURRENT_SCHEMA_VERSION,
                List.of(indexedBundle(ALPHA_ID, ALPHA_PROVIDER, 'a'),
                        indexedBundle(BETA_ID, BETA_PROVIDER, 'b')),
                List.of());
        Path packaged = temporary.resolve("packaged-plugins.jar");
        writeJar(packaged, Map.of(
                ALPHA_PROVIDER.replace('.', '/') + ".class", alphaClass,
                BETA_PROVIDER.replace('.', '/') + ".class", betaClass,
                SINK_SERVICE, (ALPHA_PROVIDER + "\n" + BETA_PROVIDER + "\n")
                        .getBytes(StandardCharsets.UTF_8),
                PluginIndex.RESOURCE_PATH, new PluginIndexCodec().write(index)));
        return new Fixture(directory, packaged);
    }

    private Path compileProviders() throws IOException {
        Path sourceRoot = Files.createDirectories(temporary.resolve("collision-source"));
        Path classes = Files.createDirectories(temporary.resolve("collision-classes"));
        Path alpha = writeProviderSource(sourceRoot, ALPHA_PROVIDER);
        Path beta = writeProviderSource(sourceRoot, BETA_PROVIDER);
        String apiPath;
        try {
            apiPath = Path.of(FinalizedStreamSinkFactory.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toString();
        } catch (Exception e) {
            throw new IOException("Could not locate plugin API classes", e);
        }
        int result = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-classpath", apiPath, "-d", classes.toString(),
                alpha.toString(), beta.toString());
        assertThat(result).isZero();
        return classes;
    }

    private static Path writeProviderSource(Path root, String provider) throws IOException {
        int separator = provider.lastIndexOf('.');
        String packageName = provider.substring(0, separator);
        String simpleName = provider.substring(separator + 1);
        Path source = root.resolve(provider.replace('.', '/') + ".java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package %s;
                import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSink;
                import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSinkFactory;
                import java.util.List;
                import java.util.Map;
                public final class %s implements FinalizedStreamSinkFactory {
                    public String scheme() { return "%s"; }
                    public List<FinalizedStreamSink> create(
                            String chainId, Map<String, String> config) {
                        return List.of();
                    }
                }
                """.formatted(packageName, simpleName, SHARED_SCHEME), StandardCharsets.UTF_8);
        return source;
    }

    private Opened open(Mode mode, Fixture fixture, PluginsOptions options) throws Exception {
        if (mode == Mode.DIRECTORY) {
            ServiceOnlyClassLoader parent = new ServiceOnlyClassLoader(
                    new URL[0], getClass().getClassLoader());
            try {
                PluginRuntimeEnvironment environment = PluginRuntimeEnvironment.open(
                        options, PluginLoaderHandle.directory(fixture.directory(), parent));
                return new Opened(environment, parent);
            } catch (RuntimeException | Error failure) {
                try {
                    parent.close();
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        ServiceOnlyClassLoader loader = new ServiceOnlyClassLoader(
                new URL[]{fixture.packaged().toUri().toURL()}, getClass().getClassLoader());
        try {
            PluginLoaderHandle handle = mode == Mode.PACKAGED_JVM
                    ? PluginLoaderHandle.packagedClasspath(loader)
                    : PluginLoaderHandle.nativeClasspath(loader);
            return new Opened(PluginRuntimeEnvironment.open(options, handle), loader);
        } catch (RuntimeException | Error failure) {
            try {
                loader.close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static IndexedBundle indexedBundle(String id, String provider, char digest) {
        BundleManifest manifest = new BundleManifest(
                BundleManifest.CURRENT_SCHEMA_VERSION,
                id,
                SemVersion.parse("1.0.0"),
                new YanoApiRange(1, 1, 1),
                List.of(),
                List.of(new BundleContribution(
                        ContributionKind.FINALIZED_SINK, SHARED_SCHEME, provider)));
        return new IndexedBundle(manifest,
                "sha256:" + String.valueOf(digest).repeat(64),
                PluginDigestMode.ARTIFACT_CLOSURE);
    }

    private static byte[] manifest(String id, String provider) {
        return """
                {
                  "schemaVersion": 1,
                  "id": "%s",
                  "version": "1.0.0",
                  "yanoApi": {"min": 1, "max": 1, "minLevel": 1},
                  "dependencies": [],
                  "contributions": [{
                    "kind": "finalized-sink",
                    "name": "%s",
                    "provider": "%s"
                  }]
                }
                """.formatted(id, SHARED_SCHEME, provider).getBytes(StandardCharsets.UTF_8);
    }

    private static String manifestPath(String id) {
        return "META-INF/yano/plugins/" + id + ".json";
    }

    private static void writeJar(Path jar, Map<String, byte[]> entries) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
    }

    private enum Mode {
        PACKAGED_JVM,
        NATIVE,
        DIRECTORY
    }

    private record Fixture(Path directory, Path packaged) {
    }

    private record Opened(
            PluginRuntimeEnvironment environment,
            URLClassLoader externalLoader
    ) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            Throwable failure = null;
            try {
                environment.close();
            } catch (Throwable closeFailure) {
                failure = closeFailure;
            }
            try {
                externalLoader.close();
            } catch (Throwable closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
            if (failure instanceof Exception exception) {
                throw exception;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }
    }

    private static final class ServiceOnlyClassLoader extends URLClassLoader {
        private ServiceOnlyClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            if (name.startsWith("META-INF/services/")) {
                return findResources(name);
            }
            return super.getResources(name);
        }
    }
}
