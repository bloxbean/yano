package com.bloxbean.cardano.yano.app;

import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Immutable contract between the REST path baked by Quarkus augmentation and
 * the operator-facing Yano API prefix.
 *
 * <p>The build writes the canonical prefix to a raw classpath marker. Runtime
 * configuration cannot replace that resource, so both effective prefix values
 * are checked against it and the host HTTP root is required to remain
 * {@code /} before ordinary startup observers can assemble Yano or activate
 * plugins.</p>
 */
@ApplicationScoped
public class ApiPrefixContract {

    static final String MARKER_RESOURCE = "META-INF/yano-api-prefix-v1";
    static final String RESTEASY_PATH = "quarkus.resteasy.path";
    static final String HTTP_ROOT_PATH = "quarkus.http.root-path";
    static final String HTTP_ROOT_PATH_ENV = "QUARKUS_HTTP_ROOT_PATH";
    static final String FAILURE_MESSAGE = "The configured API prefix does not match the "
            + "prefix baked into this Yano artifact; rebuild the artifact with "
            + "-PyanoApiPrefix=<path> instead of changing the prefix at launch";
    static final int MAX_PREFIX_LENGTH = 256;
    private static final int MAX_MARKER_BYTES = MAX_PREFIX_LENGTH + 2;
    private static final Pattern CANONICAL_PREFIX = Pattern.compile(
            "/(?:[A-Za-z0-9._~-]+(?:/[A-Za-z0-9._~-]+)*)?");

    private final String bakedPrefix;
    private final String configuredPrefix;
    private final String resteasyPath;
    private final String httpRootPath;
    private final String launchHttpRootPath;

    @Inject
    public ApiPrefixContract(
            @ConfigProperty(name = YanoPropertyKeys.API_PREFIX,
                    defaultValue = "/api/v1") String configuredPrefix,
            @ConfigProperty(name = RESTEASY_PATH,
                    defaultValue = "/api/v1") String resteasyPath,
            @ConfigProperty(name = HTTP_ROOT_PATH,
                    defaultValue = "/") String httpRootPath
    ) {
        this(configuredPrefix, resteasyPath, httpRootPath, launchHttpRootPath(),
                readMarker(Thread.currentThread().getContextClassLoader()));
    }

    ApiPrefixContract(
            String configuredPrefix,
            String resteasyPath,
            String httpRootPath,
            String launchHttpRootPath,
            String bakedPrefix
    ) {
        this.configuredPrefix = requireCanonical(configuredPrefix);
        this.resteasyPath = requireCanonical(resteasyPath);
        this.httpRootPath = requireCanonical(httpRootPath);
        this.launchHttpRootPath = requireCanonical(launchHttpRootPath);
        this.bakedPrefix = requireCanonical(bakedPrefix);
    }

    /** Runs before default-priority StartupEvent observers. */
    void verifyBeforeStartup(
            @Observes @Priority(Interceptor.Priority.PLATFORM_BEFORE) StartupEvent ignored
    ) {
        verify();
    }

    void verify() {
        if (!bakedPrefix.equals(configuredPrefix)
                || !bakedPrefix.equals(resteasyPath)
                || !"/".equals(httpRootPath)
                || !"/".equals(launchHttpRootPath)) {
            throw new ApiPrefixContractException();
        }
    }

    String publicPrefix() {
        return bakedPrefix;
    }

    public String pathPrefix() {
        return "/".equals(bakedPrefix) ? "" : bakedPrefix;
    }

    private static String launchHttpRootPath() {
        String systemValue = System.getProperty(HTTP_ROOT_PATH);
        if (systemValue != null) {
            return systemValue;
        }
        String environmentValue = System.getenv(HTTP_ROOT_PATH_ENV);
        return environmentValue != null ? environmentValue : "/";
    }

    private static String readMarker(ClassLoader loader) {
        try {
            Enumeration<URL> resources = loader.getResources(MARKER_RESOURCE);
            List<URL> markers = new ArrayList<>(2);
            while (resources.hasMoreElements() && markers.size() < 2) {
                markers.add(resources.nextElement());
            }
            if (markers.size() != 1 || resources.hasMoreElements()) {
                throw new ApiPrefixContractException();
            }
            try (InputStream input = markers.getFirst().openStream()) {
                byte[] value = input.readNBytes(MAX_MARKER_BYTES + 1);
                if (value.length == 0 || value.length > MAX_MARKER_BYTES) {
                    throw new ApiPrefixContractException();
                }
                String marker = new String(value, StandardCharsets.UTF_8);
                if (marker.endsWith("\r\n")) {
                    return marker.substring(0, marker.length() - 2);
                }
                if (marker.endsWith("\n")) {
                    return marker.substring(0, marker.length() - 1);
                }
                return marker;
            }
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof ApiPrefixContractException contractFailure) {
                throw contractFailure;
            }
            throw new ApiPrefixContractException();
        }
    }

    static String requireCanonical(String value) {
        if (value == null) {
            throw new ApiPrefixContractException();
        }
        String prefix = value;
        if (prefix.length() > MAX_PREFIX_LENGTH
                || !prefix.equals(prefix.trim())
                || !prefix.startsWith("/")
                || (prefix.length() > 1 && prefix.endsWith("/"))
                || !CANONICAL_PREFIX.matcher(prefix).matches()) {
            throw new ApiPrefixContractException();
        }
        for (String segment : prefix.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new ApiPrefixContractException();
            }
        }
        return prefix;
    }

    static final class ApiPrefixContractException extends IllegalStateException {
        private ApiPrefixContractException() {
            super(FAILURE_MESSAGE, null);
        }
    }
}
