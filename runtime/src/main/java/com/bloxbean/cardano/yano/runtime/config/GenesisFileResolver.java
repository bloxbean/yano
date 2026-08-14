package com.bloxbean.cardano.yano.runtime.config;

import com.bloxbean.cardano.yaci.core.common.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;

/**
 * Resolves configured genesis files, falling back to bundled network resources
 * for known protocol magic values.
 */
public final class GenesisFileResolver {
    private static final Logger log = LoggerFactory.getLogger(GenesisFileResolver.class);
    private static final long SANCHONET_PROTOCOL_MAGIC = 4;

    private GenesisFileResolver() {
    }

    /**
     * Resolve a genesis file path. If the user provided an existing file, use it.
     * Otherwise, for known networks, extract the bundled classpath resource to a
     * temporary file.
     */
    public static String resolve(String userPath, long magic, String filename) {
        return resolve(userPath, magic, filename, null);
    }

    /**
     * Resolve a genesis file path using an explicit resource classloader.
     */
    public static String resolve(String userPath, long magic, String filename, ClassLoader classLoader) {
        return resolveWithLoaders(userPath, magic, filename, resourceLoaders(classLoader));
    }

    /**
     * Resolve bootstrap input from trusted host/application loaders only.
     * Unlike the compatibility overload, this method never consults the
     * calling thread's context loader, which may be a selected plugin loader.
     */
    public static String resolveHostOnly(
            String userPath,
            long magic,
            String filename,
            ClassLoader hostClassLoader
    ) {
        return resolveWithLoaders(
                userPath, magic, filename, hostResourceLoaders(hostClassLoader));
    }

    private static String resolveWithLoaders(
            String userPath,
            long magic,
            String filename,
            Iterable<ClassLoader> resourceLoaders
    ) {
        if (userPath != null && !userPath.isBlank()) {
            if (new File(userPath).exists()) {
                return userPath;
            }
            log.debug("User-configured genesis file not found: {}, trying bundled resource", userPath);
        }

        String networkDir = networkDirForMagic(magic);
        if (networkDir == null) {
            return userPath;
        }

        String classpathResource = "genesis/" + networkDir + "/" + filename;
        for (ClassLoader loader : resourceLoaders) {
            try (InputStream is = loader.getResourceAsStream(classpathResource)) {
                if (is == null) {
                    continue;
                }
                Path tempFile = Files.createTempFile("yaci-" + networkDir + "-", "-" + filename);
                tempFile.toFile().deleteOnExit();
                Files.copy(is, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                log.info("Auto-resolved {} from bundled resource for {} network", filename, networkDir);
                return tempFile.toString();
            } catch (IOException e) {
                log.warn("Failed to extract bundled genesis resource {}: {}", classpathResource, e.getMessage());
                return userPath;
            }
        }
        log.debug("Bundled genesis resource not found: {}", classpathResource);
        return userPath;
    }

    private static Iterable<ClassLoader> resourceLoaders(ClassLoader explicitLoader) {
        var loaders = new LinkedHashSet<ClassLoader>();
        if (explicitLoader != null) {
            loaders.add(explicitLoader);
        }
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        if (contextLoader != null) {
            loaders.add(contextLoader);
        }
        ClassLoader runtimeLoader = GenesisFileResolver.class.getClassLoader();
        if (runtimeLoader != null) {
            loaders.add(runtimeLoader);
        }
        return loaders;
    }

    private static Iterable<ClassLoader> hostResourceLoaders(ClassLoader explicitLoader) {
        var loaders = new LinkedHashSet<ClassLoader>();
        if (explicitLoader != null) {
            loaders.add(explicitLoader);
        }
        ClassLoader runtimeLoader = GenesisFileResolver.class.getClassLoader();
        if (runtimeLoader != null) {
            loaders.add(runtimeLoader);
        }
        return loaders;
    }

    public static String networkDirForMagic(long magic) {
        if (magic == Constants.MAINNET_PROTOCOL_MAGIC) return "mainnet";
        if (magic == Constants.PREPROD_PROTOCOL_MAGIC) return "preprod";
        if (magic == Constants.PREVIEW_PROTOCOL_MAGIC) return "preview";
        if (magic == SANCHONET_PROTOCOL_MAGIC) return "sanchonet";
        return null;
    }
}
