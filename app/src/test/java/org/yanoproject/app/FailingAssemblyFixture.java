package org.yanoproject.app;

import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.yanoproject.api.host.HostApi;
import org.yanoproject.api.plugin.NodePlugin;
import org.yanoproject.api.plugin.PluginApiVersion;
import org.yanoproject.api.plugin.PluginContext;
import org.yanoproject.catalog.PluginIndex;
import org.yanoproject.runtime.plugins.PluginLoaderHandle;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** An embedded bundle whose host assembly step throws a secret-bearing failure (I8-N2). */
final class FailingAssemblyFixture {
    static final String ID = "org.yanoproject.app.fixture.failing-assembly";
    static final String SECRET = "ASSEMBLY-SECRET-5c1e7a";

    private FailingAssemblyFixture() {
    }

    /** A host class loader over {@code parent} plus a JAR carrying the fixture's manifest and service file. */
    static URLClassLoader loader(ClassLoader parent) {
        try {
            Path jar = Files.createTempFile("failing-assembly-", ".jar");
            jar.toFile().deleteOnExit();
            try (OutputStream file = Files.newOutputStream(jar); JarOutputStream output = new JarOutputStream(file)) {
                output.putNextEntry(new JarEntry("META-INF/yano/plugins/" + ID + ".json"));
                output.write("""
                        {"schemaVersion": 1, "id": "%s", "version": "1.0.0",
                         "yanoApi": {"min": %d, "max": %d, "minLevel": %d},
                         "contributions": [{"kind": "node-plugin", "name": "%s", "provider": "%s"}]}
                        """.formatted(ID, PluginApiVersion.CURRENT_MAJOR, PluginApiVersion.CURRENT_MAJOR,
                        PluginApiVersion.CURRENT_LEVEL, ID, Plugin.class.getName())
                        .getBytes(StandardCharsets.UTF_8));
                output.putNextEntry(new JarEntry("META-INF/services/" + NodePlugin.class.getName()));
                output.write(Plugin.class.getName().getBytes(StandardCharsets.UTF_8));
                // The catalog requires a provider's class entry in the artifact that declares it.
                String classEntry = Plugin.class.getName().replace('.', '/') + ".class";
                ClassLoader classes = FailingAssemblyFixture.class.getClassLoader();
                try (InputStream bytes = classes.getResourceAsStream(classEntry)) {
                    output.putNextEntry(new JarEntry(classEntry));
                    output.write(bytes.readAllBytes());
                }
                output.closeEntry();
            }
            // Library-mode discovery: hide the application's build-time index so the catalog reads manifests.
            return new URLClassLoader(new URL[]{jar.toUri().toURL()}, parent) {
                @Override public URL getResource(String name) {
                    return PluginIndex.RESOURCE_PATH.equals(name) ? findResource(name) : super.getResource(name);
                }
                @Override public Enumeration<URL> getResources(String name) throws IOException {
                    return PluginIndex.RESOURCE_PATH.equals(name) ? findResources(name) : super.getResources(name);
                }
            };
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Embedded owner whose assembly step fails with {@link #SECRET} in its own message. */
    public static final class Plugin implements NodePlugin {
        @Override public String id() { return ID; }
        @Override public String version() { return "1.0.0"; }
        @Override public void init(PluginContext context) {
            context.getService(HostApi.SERVICE_KEY, HostApi.class).orElseThrow().lifecycle()
                    .registerAssembly(ID, () -> {
                        throw new IllegalStateException("assembly credentials rejected: " + SECRET);
                    });
        }
        @Override public void start() { }
        @Override public void stop() { }
        @Override public void close() { }
    }

    /** Replaces the application's plugin loader in {@link FailingAssemblyTest}'s profile only. */
    @Alternative
    @Singleton
    public static class LoaderProducer {
        @Produces
        @Singleton
        @Named("pluginClassLoader")
        PluginLoaderHandle pluginClassLoader() {
            return PluginLoaderHandle.classpath(loader(Thread.currentThread().getContextClassLoader()));
        }
    }
}
