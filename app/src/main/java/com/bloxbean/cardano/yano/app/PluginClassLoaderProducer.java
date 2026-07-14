package com.bloxbean.cardano.yano.app;

import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import com.bloxbean.cardano.yano.runtime.plugins.PluginLoaderHandle;
import com.bloxbean.cardano.yano.runtime.util.LifecycleFailures;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.ConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CDI producer for the plugin classloader used by optional runtime plugins.
 */
@Singleton
public class PluginClassLoaderProducer {

    private static final Logger log = LoggerFactory.getLogger(PluginClassLoaderProducer.class);
    private static final int MAX_NATIVE_DIRECTORY_ENTRIES_INSPECTED = 256;

    @Produces
    @Singleton
    @Named("pluginClassLoader")
    public PluginLoaderHandle createPluginClassLoader() {
        try {
            // Read config programmatically to avoid circular dependency
            // (CDI proxy for ClassLoader + SmallRye Config ServiceLoader = infinite recursion)
            String pluginDirectory = ConfigProvider.getConfig()
                    .getOptionalValue(YanoPropertyKeys.Plugins.DIRECTORY, String.class)
                    .orElse("plugins");
            boolean enabled = ConfigProvider.getConfig()
                    .getOptionalValue(YanoPropertyKeys.Plugins.ENABLED, Boolean.class)
                    .orElse(true);
            return createPluginClassLoader(
                    pluginDirectory,
                    enabled,
                    Thread.currentThread().getContextClassLoader(),
                    "Substrate VM".equalsIgnoreCase(System.getProperty("java.vm.name", "")));
        } catch (Throwable failure) {
            return throwSecretSafeDirectoryFailure(failure);
        }
    }

    /** Package-private secret-safety seam for invalid-path and capture tests. */
    PluginLoaderHandle createPluginClassLoader(
            String pluginDirectory,
            boolean enabled,
            ClassLoader parent,
            boolean nativeImage
    ) {
        try {
            return createPluginClassLoaderRaw(
                    pluginDirectory, enabled, parent, nativeImage);
        } catch (Throwable failure) {
            return throwSecretSafeDirectoryFailure(failure);
        }
    }

    private PluginLoaderHandle createPluginClassLoaderRaw(
            String pluginDirectory,
            boolean enabled,
            ClassLoader parent,
            boolean nativeImage
    ) {
        if (!enabled) {
            log.info("Plugin loading disabled by {}", YanoPropertyKeys.Plugins.ENABLED);
            return PluginLoaderHandle.packagedClasspath(parent);
        }

        // In GraalVM native image mode, dynamic class loading is not supported
        if (nativeImage) {
            try {
                Path directory = pluginDirectory == null || pluginDirectory.isBlank()
                        ? null : Path.of(pluginDirectory);
                if (directory != null && Files.isDirectory(directory)) {
                    NativeDirectoryInspection inspection = inspectNativeDirectory(directory);
                        if (inspection.jarCount() > 0 || inspection.truncated()) {
                            log.warn("Native image ignores plugin-directory JARs; "
                                            + "inspectedEntries={}, jarCount={}, scanTruncated={}, "
                                            + "plugins must be included at build time",
                                    inspection.inspectedEntries(), inspection.jarCount(),
                                    inspection.truncated());
                        }
                }
            } catch (Throwable inspectionFailure) {
                LifecycleFailures.rethrowIfProcessFatal(inspectionFailure);
                log.warn("Could not inspect native plugin directory (errorType={})",
                        inspectionFailure.getClass().getName());
            }
            log.info("Running in native image mode - dynamic plugin loading disabled");
            return PluginLoaderHandle.nativeClasspath(parent);
        }

        if (pluginDirectory == null || pluginDirectory.isBlank()) {
            log.debug("No plugin directory configured");
            return PluginLoaderHandle.packagedClasspath(parent);
        }
        PluginLoaderHandle handle = PluginLoaderHandle.packagedDirectory(
                Path.of(pluginDirectory), parent);
        log.info("Plugin directory resolved {} deterministic JAR(s)",
                handle.artifacts().size());
        return handle;
    }

    /** Package-private bounded native-directory inspection seam. */
    static NativeDirectoryInspection inspectNativeDirectory(Path directory)
            throws java.io.IOException {
        int inspected = 0;
        int jars = 0;
        boolean truncated;
        try (var entries = Files.newDirectoryStream(directory)) {
            var iterator = entries.iterator();
            while (inspected < MAX_NATIVE_DIRECTORY_ENTRIES_INSPECTED
                    && iterator.hasNext()) {
                Path entry = iterator.next();
                inspected++;
                if (Files.isRegularFile(entry)
                        && entry.getFileName().toString().endsWith(".jar")) {
                    jars++;
                }
            }
            truncated = iterator.hasNext();
        }
        return new NativeDirectoryInspection(inspected, jars, truncated);
    }

    record NativeDirectoryInspection(
            int inspectedEntries,
            int jarCount,
            boolean truncated
    ) {
    }

    static PluginLoaderHandle throwSecretSafeDirectoryFailure(Throwable failure) {
        LifecycleFailures.rethrowIfProcessFatalReachable(failure);
        if (failure instanceof PluginStartupException alreadySanitized) {
            throw alreadySanitized;
        }
        PluginStartupException sanitized = PluginStartupException.directoryCaptureFailure();
        log.error(sanitized.getMessage());
        throw sanitized;
    }

    /**
     * Fallback ownership for shutdown before runtime assembly accepts the handle.
     * Normal runtime shutdown may already have closed it; close is idempotent.
     */
    void disposePluginClassLoader(
            @Disposes @Named("pluginClassLoader") PluginLoaderHandle handle) {
        handle.closeIfUnclaimed();
    }
}
