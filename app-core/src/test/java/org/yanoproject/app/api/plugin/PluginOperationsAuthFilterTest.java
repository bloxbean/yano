package org.yanoproject.app.api.plugin;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PluginOperationsAuthFilterTest {

    @Test
    void operatorKeysAreNotInjectedDuringNativeStaticInitialization() throws Exception {
        assertNull(PluginOperationsAuthFilter.class.getDeclaredField("apiKeysConfig")
                .getAnnotation(org.eclipse.microprofile.config.inject.ConfigProperty.class));
    }

    @Test
    void pluginOperationsRequireAnUnscopedFullKey() {
        assertEquals(503, filter("", "plugin-operations", null).getStatus());
        assertEquals(503, filter("restricted=orders", "plugin-operations", "restricted").getStatus());
        assertEquals(401, filter("full,restricted=orders", "plugin-operations", null).getStatus());
        assertEquals(401, filter("full,restricted=orders", "plugin-operations", "wrong").getStatus());
        assertEquals(403, filter("full,restricted=orders", "plugin-operations", "restricted").getStatus());
        assertNull(filter("full,restricted=orders", "plugin-operations", "full"));
        assertNull(filter("full", "plugin-operations", "full"));
    }

    @Test
    void pluginOperationsRejectNonCanonicalAliasesBeforeDispatch() {
        for (String path : List.of(
                "plugin-operations/",
                "plugin-operations;x=1",
                "plugin%2doperations",
                "plugin-operations//bundles",
                "./plugin-operations",
                "api/plugin-operations",
                "plugin-operations-prefix/plugin-operations/bundles",
                "plugin-operations/plugin-operations/bundles/com.example.bundle")) {
            assertEquals(404, filter("full", path, "full").getStatus(), path);
        }
        // A non-canonical path is rejected before the key configuration is consulted.
        assertEquals(404, filter("", "plugin-operations/", null).getStatus());
        for (String path : List.of(
                "plugin-operations",
                "plugin-operations/bundles",
                "plugin-operations/bundles/com.example.bundle")) {
            assertNull(filter("full", path, "full"), path);
        }
    }

    @Test
    void pluginOperationsUseTheExactBakedPrefixEvenWhenItContainsTheRouteName() {
        String path = "/tenant/plugin-operations/plugin-operations";
        assertNull(filter("full", "/tenant/plugin-operations", path, "full"));
        assertEquals(404, filter("full", "/tenant", path, "full").getStatus());
    }

    @Test
    void otherResourcesAreNotMatched() {
        PluginOperationsAuthFilter filter = filter(String.class, "", "", "nothing-canonical/");
        RequestProbe request = new RequestProbe(null);
        filter.filter(request.context());
        assertNull(request.aborted);
    }

    private static Response filter(String keys, String path, String apiKey) {
        return filter(keys, "", path, apiKey);
    }

    private static Response filter(String keys, String prefix, String path, String apiKey) {
        PluginOperationsAuthFilter filter = filter(PluginOperationsResource.class, keys, prefix, path);
        RequestProbe request = new RequestProbe(apiKey);
        filter.filter(request.context());
        return request.aborted;
    }

    private static PluginOperationsAuthFilter filter(Class<?> resourceClass, String keys, String prefix,
                                                     String rawPath) {
        PluginOperationsAuthFilter filter = new PluginOperationsAuthFilter();
        filter.apiKeysConfig = Optional.of(keys);
        filter.apiPathPrefix = prefix;
        Method summary = method("summary");
        filter.resourceInfo = new ResourceInfo() {
            @Override
            public Method getResourceMethod() {
                return summary;
            }

            @Override
            public Class<?> getResourceClass() {
                return resourceClass;
            }
        };
        filter.uriInfo = (UriInfo) Proxy.newProxyInstance(
                UriInfo.class.getClassLoader(), new Class<?>[]{UriInfo.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPath" -> rawPath;
                    case "getRequestUri" -> URI.create(
                            "http://localhost" + (rawPath.startsWith("/") ? "" : "/") + rawPath);
                    default -> null;
                });
        return filter;
    }

    private static Method method(String name) {
        try {
            return PluginOperationsResource.class.getMethod(name);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    private static final class RequestProbe {
        private final String apiKey;
        private Response aborted;

        private RequestProbe(String apiKey) {
            this.apiKey = apiKey;
        }

        private ContainerRequestContext context() {
            return (ContainerRequestContext) Proxy.newProxyInstance(
                    ContainerRequestContext.class.getClassLoader(),
                    new Class<?>[]{ContainerRequestContext.class},
                    (proxy, called, args) -> switch (called.getName()) {
                        case "getMethod" -> "GET";
                        case "getHeaderString" -> apiKey;
                        case "abortWith" -> {
                            aborted = (Response) args[0];
                            yield null;
                        }
                        case "hasEntity" -> false;
                        case "toString" -> "request-probe";
                        default -> null;
                    });
        }
    }
}
