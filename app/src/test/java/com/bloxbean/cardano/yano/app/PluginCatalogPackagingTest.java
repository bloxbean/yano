package com.bloxbean.cardano.yano.app;

import com.bloxbean.cardano.yano.api.config.PluginsOptions;
import com.bloxbean.cardano.yano.api.plugin.PluginDigestMode;
import com.bloxbean.cardano.yano.runtime.plugins.PluginDiscoveryMode;
import com.bloxbean.cardano.yano.runtime.plugins.PluginRuntimeEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginCatalogPackagingTest {

    private static final String GENERATED_REFLECTION_CONFIG =
            "META-INF/native-image/com.bloxbean.cardano/yano-plugin-catalog/reflect-config.json";
    private static final String CODEC_REFLECTION_CONFIG =
            "META-INF/native-image/com.bloxbean.cardano/"
                    + "yano-plugin-catalog-codec/reflect-config.json";

    @Test
    void generatedIndexRetainsAndCorrelatesSelectedBuildTimeBundlesWithoutChangingTccl()
            throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader before = thread.getContextClassLoader();
        String firstFingerprint;
        try (PluginRuntimeEnvironment environment = PluginRuntimeEnvironment.packagedClasspath(
                PluginsOptions.defaults(), before)) {
            Set<String> bundleIds = environment.catalog().bundles().stream()
                    .map(bundle -> bundle.id())
                    .collect(Collectors.toSet());
            assertEquals(Set.of("com.bloxbean.cardano.yano.plugins.logging"), bundleIds,
                    "lean Yano must package only the host logging bundle");
            assertTrue(environment.catalog().bundles().stream()
                    .allMatch(bundle -> bundle.selected()
                            && !bundle.legacy()
                            && bundle.digestMode() == PluginDigestMode.ARTIFACT_CLOSURE));
            firstFingerprint = environment.catalog().fingerprint();
            assertTrue(firstFingerprint.matches("sha256:[0-9a-f]{64}"));
        }
        assertSame(before, thread.getContextClassLoader());

        try (PluginRuntimeEnvironment second = PluginRuntimeEnvironment.packagedClasspath(
                PluginsOptions.defaults(), before)) {
            assertEquals(firstFingerprint, second.catalog().fingerprint());
        }
        assertSame(before, thread.getContextClassLoader());
    }

    @Test
    void generatedNativeReflectionConfigCoversEveryCatalogProviderConstructor() throws IOException {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        String reflectionJson;
        try (InputStream input = loader.getResourceAsStream(GENERATED_REFLECTION_CONFIG)) {
            assertTrue(input != null, "generated native reflection config is missing");
            reflectionJson = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        try (PluginRuntimeEnvironment environment = PluginRuntimeEnvironment.packagedClasspath(
                PluginsOptions.defaults(), loader)) {
            environment.catalog().bundles().stream()
                    .flatMap(bundle -> bundle.contributions().stream())
                    .forEach(contribution -> assertTrue(
                            reflectionJson.contains("\"name\":\"" + contribution.providerClass() + "\""),
                            () -> "native reflection config misses " + contribution.providerClass()));
        }
    }

    @Test
    void packagedApplicationRetainsCatalogCodecNativeReflectionConfig() throws IOException {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try (InputStream input = loader.getResourceAsStream(CODEC_REFLECTION_CONFIG)) {
            assertTrue(input != null, "catalog codec native reflection config is missing");
            String reflectionJson = new String(
                    input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(reflectionJson.contains(
                    "\"name\": \"com.bloxbean.cardano.yano.catalog.PluginIndexCodec$RawIndex\""));
            assertTrue(reflectionJson.contains(
                    "\"name\": \"com.bloxbean.cardano.yano.catalog.BundleManifestParser$RawManifest\""));
        }
    }

    @Test
    void cdiDisposerClosesAnUnclaimedDirectoryLoaderIdempotently(@TempDir Path directory)
            throws IOException {
        Path jar = directory.resolve("orphan.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("orphan-marker.txt"));
            output.write(new byte[]{1});
            output.closeEntry();
        }
        var handle = com.bloxbean.cardano.yano.runtime.plugins.PluginLoaderHandle.directory(
                directory, getClass().getClassLoader());
        Path snapshotDirectory = handle.artifacts().getFirst().getParent();
        assertSame(getClass().getClassLoader(), handle.classLoader());
        assertSame(getClass().getClassLoader(), handle.hostClassLoader());
        assertTrue(handle.classLoader().getResource("orphan-marker.txt") == null);
        assertTrue(Files.exists(snapshotDirectory));

        var producer = new PluginClassLoaderProducer();
        producer.disposePluginClassLoader(handle);
        producer.disposePluginClassLoader(handle);

        assertTrue(handle.classLoader().getResource("orphan-marker.txt") == null);
        assertTrue(Files.notExists(snapshotDirectory));
    }

    @Test
    void cdiDisposerDoesNotCloseLoaderClaimedByRuntimeEnvironment(@TempDir Path directory)
            throws IOException {
        Path jar = directory.resolve("claimed.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("claimed-marker.txt"));
            output.write(new byte[]{1});
            output.closeEntry();
        }
        var handle = com.bloxbean.cardano.yano.runtime.plugins.PluginLoaderHandle.directory(
                directory, getClass().getClassLoader());
        Path snapshotDirectory = handle.artifacts().getFirst().getParent();
        var disabled = new PluginsOptions(false, false, Set.of(), Set.of(), Map.of());
        var environment = PluginRuntimeEnvironment.open(disabled, handle);
        var producer = new PluginClassLoaderProducer();
        try {
            producer.disposePluginClassLoader(handle);
            assertSame(getClass().getClassLoader(), handle.classLoader());
            assertTrue(handle.classLoader().getResource("claimed-marker.txt") == null);
            assertTrue(Files.exists(snapshotDirectory));
        } finally {
            environment.close();
        }

        assertTrue(handle.classLoader().getResource("claimed-marker.txt") == null);
        assertTrue(Files.notExists(snapshotDirectory));
    }

    @Test
    void genesisResolutionUsesTrustedHostAndNeverPluginTccl(@TempDir Path directory)
            throws Exception {
        String hostResource = "genesis/preprod/host-only-test.json";
        String pluginOnlyResource = "genesis/preprod/plugin-only-test.json";
        Path hostJar = directory.resolve("trusted-host.jar");
        Path pluginJar = directory.resolve("plugin-context.jar");
        writeResourceJar(hostJar, Map.of(
                hostResource, "trusted-host".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        writeResourceJar(pluginJar, Map.of(
                hostResource, "malicious-shadow".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                pluginOnlyResource, "plugin-only".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        String resolvedHost = null;
        try (URLClassLoader host = new URLClassLoader(
                     new URL[]{hostJar.toUri().toURL()}, null);
             URLClassLoader plugin = new URLClassLoader(
                     new URL[]{pluginJar.toUri().toURL()}, null)) {
            var handle = com.bloxbean.cardano.yano.runtime.plugins.PluginLoaderHandle
                    .classpath(host);
            try {
                thread.setContextClassLoader(plugin);
                YanoProducer producer = new YanoProducer(handle);
                resolvedHost = producer.resolveGenesisFile(
                        null, 1, "host-only-test.json");
                assertEquals("trusted-host", Files.readString(Path.of(resolvedHost)));
                assertNull(producer.resolveGenesisFile(
                        null, 1, "plugin-only-test.json"));
                assertSame(plugin, thread.getContextClassLoader());
            } finally {
                thread.setContextClassLoader(original);
                handle.close();
            }
        } finally {
            if (resolvedHost != null) {
                Files.deleteIfExists(Path.of(resolvedHost));
            }
        }
    }

    @Test
    void productionClassLoaderProducerSelectsStrictPackagedJvmDiscovery() {
        var producer = new PluginClassLoaderProducer();
        var handle = producer.createPluginClassLoader();
        try {
            assertEquals(PluginDiscoveryMode.PACKAGED_JVM, handle.discoveryMode());
        } finally {
            producer.disposePluginClassLoader(handle);
        }
    }

    private static void writeResourceJar(Path jar, Map<String, byte[]> entries)
            throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
    }

    @Test
    void pluginDirectoryCaptureFailureCannotExposeConfiguredPathOrCause() {
        String sentinel = "plugin-directory-secret-path";
        var producer = new PluginClassLoaderProducer();

        PluginStartupException failure = assertThrows(
                PluginStartupException.class,
                () -> producer.createPluginClassLoader(
                        sentinel + "\0invalid", true,
                        getClass().getClassLoader(), false));

        assertEquals(PluginStartupException.DIRECTORY_CAPTURE_FAILURE,
                failure.sourceFailureType());
        assertEquals(null, failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        java.io.StringWriter rendered = new java.io.StringWriter();
        failure.printStackTrace(new java.io.PrintWriter(rendered));
        assertFalse(rendered.toString().contains(sentinel));
    }

    @Test
    void pluginDirectoryBoundaryPromotesWrappedAndSuppressedProcessFatalErrors() {
        OutOfMemoryError wrappedFatal = new OutOfMemoryError("wrapped fatal");
        assertSame(wrappedFatal, assertThrows(OutOfMemoryError.class,
                () -> PluginClassLoaderProducer.throwSecretSafeDirectoryFailure(
                        new RuntimeException("wrapper", wrappedFatal))));

        OutOfMemoryError suppressedFatal = new OutOfMemoryError("suppressed fatal");
        RuntimeException wrapper = new RuntimeException("wrapper");
        wrapper.addSuppressed(suppressedFatal);
        assertSame(suppressedFatal, assertThrows(OutOfMemoryError.class,
                () -> PluginClassLoaderProducer.throwSecretSafeDirectoryFailure(wrapper)));
    }

    @Test
    void nativePluginDirectoryInspectionStopsAtItsEntryBound(@TempDir Path directory)
            throws IOException {
        for (int index = 0; index < 300; index++) {
            Files.write(directory.resolve("entry-" + index + ".txt"), new byte[]{1});
        }

        PluginClassLoaderProducer.NativeDirectoryInspection inspection =
                PluginClassLoaderProducer.inspectNativeDirectory(directory);

        assertEquals(256, inspection.inspectedEntries());
        assertEquals(0, inspection.jarCount());
        assertTrue(inspection.truncated());
    }
}
