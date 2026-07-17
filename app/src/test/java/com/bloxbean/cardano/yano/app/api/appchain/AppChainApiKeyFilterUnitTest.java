package com.bloxbean.cardano.yano.app.api.appchain;

import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiAccess;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiGateway;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiResponse;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRouteInfo;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainHttpMethod;
import com.bloxbean.cardano.yano.app.api.plugin.PluginOperationsResource;
import com.bloxbean.cardano.yano.appchain.composite.contracts.CompositeProfileGovernanceV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AppChainApiKeyFilterUnitTest {

    @Test
    void operatorAuthConfigurationIsNotInjectedDuringNativeStaticInitialization()
            throws Exception {
        for (String field : List.of("authEnabled", "apiKeysConfig")) {
            assertNull(AppChainApiKeyFilter.class.getDeclaredField(field)
                    .getAnnotation(org.eclipse.microprofile.config.inject.ConfigProperty.class),
                    field);
        }
    }

    @Test
    void rawQueryAndDomainBodiesAreBoundBeforeDeserialization() throws Exception {
        Method query = AppChainResource.ChainScopedResource.class.getMethod(
                "query", String.class,
                AppChainResource.ChainScopedResource.QueryRequest.class);
        AppChainApiKeyFilter queryFilter = filter(false, "", DomainApiAccess.READ,
                AppChainResource.ChainScopedResource.class, query);
        RequestProbe oversizedQuery = new RequestProbe("POST", null,
                new byte[132 * 1024 + 1]);

        queryFilter.filter(oversizedQuery.context());

        assertEquals(413, oversizedQuery.aborted.getStatus());

        Method post = PluginDomainResource.class.getMethod(
                "post", String.class, String.class, UriInfo.class, byte[].class);
        AppChainApiKeyFilter domainFilter = filter(false, "", DomainApiAccess.READ,
                PluginDomainResource.class, post);
        RequestProbe oversizedDomain = new RequestProbe("POST", null,
                new byte[64 * 1024 + 1]);

        domainFilter.filter(oversizedDomain.context());

        assertEquals(413, oversizedDomain.aborted.getStatus());

        Method get = PluginDomainResource.class.getMethod(
                "get", String.class, String.class, UriInfo.class);
        AppChainApiKeyFilter getFilter = filter(false, "", DomainApiAccess.READ,
                PluginDomainResource.class, get);
        RequestProbe getWithBody = new RequestProbe("GET", null, new byte[]{1});

        getFilter.filter(getWithBody.context());

        assertEquals(400, getWithBody.aborted.getStatus());

        Method governance = AppChainResource.ChainScopedResource.class.getMethod(
                "submitProfileGovernanceCommand",
                AppChainResource.ChainScopedResource.CompositeProfileCommandRequest.class);
        AppChainApiKeyFilter governanceFilter = filter(false, "full", DomainApiAccess.READ,
                AppChainResource.ChainScopedResource.class, governance);
        int governanceJsonLimit = CompositeProfileGovernanceV1.MAX_COMMAND_BYTES * 2 + 1_024;
        RequestProbe oversizedGovernance = new RequestProbe(
                "POST", "full", new byte[governanceJsonLimit + 1]);

        governanceFilter.filter(oversizedGovernance.context());

        assertEquals(413, oversizedGovernance.aborted.getStatus());
    }

    @Test
    void exactRawBoundaryIsRestoredForResourceDeserialization() throws Exception {
        Method query = AppChainResource.ChainScopedResource.class.getMethod(
                "query", String.class,
                AppChainResource.ChainScopedResource.QueryRequest.class);
        AppChainApiKeyFilter filter = filter(false, "", DomainApiAccess.READ,
                AppChainResource.ChainScopedResource.class, query);
        RequestProbe request = new RequestProbe("POST", null, new byte[132 * 1024]);

        filter.filter(request.context());

        assertNull(request.aborted);
        assertEquals(132 * 1024, request.entity.readAllBytes().length);
    }

    @Test
    void staticallyPrivilegedOperationsRequireAFullKeyButNotBroadAuth() throws Exception {
        Method pause = AppChainResource.ChainScopedResource.class.getMethod("pause");

        RequestProbe disabled = new RequestProbe("POST", null, null);
        filter(false, "", DomainApiAccess.READ,
                AppChainResource.ChainScopedResource.class, pause).filter(disabled.context());
        assertEquals(503, disabled.aborted.getStatus());

        RequestProbe scopedOnly = new RequestProbe("POST", "restricted", null);
        filter(true, "restricted=orders", DomainApiAccess.READ,
                AppChainResource.ChainScopedResource.class, pause).filter(scopedOnly.context());
        assertEquals(503, scopedOnly.aborted.getStatus());

        RequestProbe full = new RequestProbe("POST", "full", null);
        filter(true, "full,restricted=orders", DomainApiAccess.READ,
                AppChainResource.ChainScopedResource.class, pause).filter(full.context());
        assertNull(full.aborted);

        RequestProbe keyOnlyMissing = new RequestProbe("POST", null, null);
        filter(false, "full", DomainApiAccess.READ,
                AppChainResource.ChainScopedResource.class, pause)
                .filter(keyOnlyMissing.context());
        assertEquals(401, keyOnlyMissing.aborted.getStatus());

        RequestProbe keyOnlyFull = new RequestProbe("POST", "full", null);
        filter(false, "full", DomainApiAccess.READ,
                AppChainResource.ChainScopedResource.class, pause).filter(keyOnlyFull.context());
        assertNull(keyOnlyFull.aborted);
    }

    @Test
    void adminMembershipInventoryIsPrivilegedInKeyOnlyMode() throws Exception {
        Method members = AppChainResource.ChainScopedResource.class.getMethod("listMembers");

        RequestProbe missing = new RequestProbe("GET", null, null);
        filter(false, "full,restricted=orders", DomainApiAccess.READ,
                AppChainResource.ChainScopedResource.class, members).filter(missing.context());
        assertEquals(401, missing.aborted.getStatus());

        RequestProbe restricted = new RequestProbe("GET", "restricted", null);
        filter(false, "full,restricted=orders", DomainApiAccess.READ,
                AppChainResource.ChainScopedResource.class, members).filter(restricted.context());
        assertEquals(403, restricted.aborted.getStatus());

        RequestProbe full = new RequestProbe("GET", "full", null);
        filter(false, "full,restricted=orders", DomainApiAccess.READ,
                AppChainResource.ChainScopedResource.class, members).filter(full.context());
        assertNull(full.aborted);
    }

    @Test
    void compositeProfileGovernanceSubmissionRequiresAFullKey() throws Exception {
        Method submit = AppChainResource.ChainScopedResource.class.getMethod(
                "submitProfileGovernanceCommand",
                AppChainResource.ChainScopedResource.CompositeProfileCommandRequest.class);

        RequestProbe missing = new RequestProbe("POST", null, null);
        filter(false, "full,restricted=orders", DomainApiAccess.READ,
                AppChainResource.ChainScopedResource.class, submit).filter(missing.context());
        assertEquals(401, missing.aborted.getStatus());

        RequestProbe restricted = new RequestProbe("POST", "restricted", null);
        filter(false, "full,restricted=orders", DomainApiAccess.READ,
                AppChainResource.ChainScopedResource.class, submit).filter(restricted.context());
        assertEquals(403, restricted.aborted.getStatus());

        RequestProbe full = new RequestProbe("POST", "full", null);
        filter(false, "full,restricted=orders", DomainApiAccess.READ,
                AppChainResource.ChainScopedResource.class, submit).filter(full.context());
        assertNull(full.aborted);
    }

    @Test
    void pluginOperationsRequireAnUnscopedFullKeyButNotBroadAuth() throws Exception {
        Method summary = PluginOperationsResource.class.getMethod("summary");

        RequestProbe disabled = new RequestProbe("GET", null, null);
        filter(false, "", DomainApiAccess.READ,
                PluginOperationsResource.class, summary,
                "plugin-operations").filter(disabled.context());
        assertEquals(503, disabled.aborted.getStatus());

        RequestProbe scopedOnly = new RequestProbe("GET", "restricted", null);
        filter(true, "restricted=orders", DomainApiAccess.READ,
                PluginOperationsResource.class, summary,
                "plugin-operations").filter(scopedOnly.context());
        assertEquals(503, scopedOnly.aborted.getStatus());

        RequestProbe missing = new RequestProbe("GET", null, null);
        filter(true, "full,restricted=orders", DomainApiAccess.READ,
                PluginOperationsResource.class, summary,
                "plugin-operations").filter(missing.context());
        assertEquals(401, missing.aborted.getStatus());

        RequestProbe restricted = new RequestProbe("GET", "restricted", null);
        filter(true, "full,restricted=orders", DomainApiAccess.READ,
                PluginOperationsResource.class, summary,
                "plugin-operations").filter(restricted.context());
        assertEquals(403, restricted.aborted.getStatus());

        RequestProbe full = new RequestProbe("GET", "full", null);
        filter(true, "full,restricted=orders", DomainApiAccess.READ,
                PluginOperationsResource.class, summary,
                "plugin-operations").filter(full.context());
        assertNull(full.aborted);

        RequestProbe keyOnlyFull = new RequestProbe("GET", "full", null);
        filter(false, "full", DomainApiAccess.READ,
                PluginOperationsResource.class, summary,
                "plugin-operations").filter(keyOnlyFull.context());
        assertNull(keyOnlyFull.aborted);
    }

    @Test
    void pluginOperationsRejectNonCanonicalAliasesBeforeDispatch() throws Exception {
        Method summary = PluginOperationsResource.class.getMethod("summary");
        for (String path : List.of(
                "plugin-operations/",
                "plugin-operations;x=1",
                "plugin%2doperations",
                "plugin-operations//bundles",
                "./plugin-operations",
                "api/plugin-operations",
                "plugin-operations-prefix/plugin-operations/bundles",
                "plugin-operations/plugin-operations/bundles/com.example.bundle")) {
            RequestProbe request = new RequestProbe("GET", "full", null);
            filter(true, "full", DomainApiAccess.READ,
                    PluginOperationsResource.class, summary, path).filter(request.context());
            assertEquals(404, request.aborted.getStatus(), path);
        }

        for (String path : List.of(
                "plugin-operations",
                "plugin-operations/bundles",
                "plugin-operations/bundles/com.example.bundle")) {
            RequestProbe request = new RequestProbe("GET", "full", null);
            filter(true, "full", DomainApiAccess.READ,
                    PluginOperationsResource.class, summary, path).filter(request.context());
            assertNull(request.aborted, path);
        }
    }

    @Test
    void pluginOperationsUseTheExactBakedPrefixEvenWhenItContainsTheRouteName()
            throws Exception {
        Method summary = PluginOperationsResource.class.getMethod("summary");
        String path = "/tenant/plugin-operations/plugin-operations";

        AppChainApiKeyFilter matching = filter(true, "full", DomainApiAccess.READ,
                PluginOperationsResource.class, summary, path);
        matching.apiPathPrefix = "/tenant/plugin-operations";
        RequestProbe accepted = new RequestProbe("GET", "full", null);
        matching.filter(accepted.context());
        assertNull(accepted.aborted);

        AppChainApiKeyFilter otherArtifact = filter(true, "full", DomainApiAccess.READ,
                PluginOperationsResource.class, summary, path);
        otherArtifact.apiPathPrefix = "/tenant";
        RequestProbe rejected = new RequestProbe("GET", "full", null);
        otherArtifact.filter(rejected.context());
        assertEquals(404, rejected.aborted.getStatus());
    }

    @Test
    void privilegedDomainRoutesAreHiddenWithoutUsableFullKey() throws Exception {
        Method get = PluginDomainResource.class.getMethod(
                "get", String.class, String.class, UriInfo.class);

        RequestProbe disabled = new RequestProbe("GET", null, null);
        filter(false, "", DomainApiAccess.PRIVILEGED,
                PluginDomainResource.class, get).filter(disabled.context());
        assertEquals(404, disabled.aborted.getStatus());

        RequestProbe scopedOnly = new RequestProbe("GET", "restricted", null);
        filter(true, "restricted=orders", DomainApiAccess.PRIVILEGED,
                PluginDomainResource.class, get).filter(scopedOnly.context());
        assertEquals(404, scopedOnly.aborted.getStatus());
    }

    @Test
    void semanticDomainAccessControlsScopedAndFullKeys() throws Exception {
        Method get = PluginDomainResource.class.getMethod(
                "get", String.class, String.class, UriInfo.class);

        RequestProbe read = new RequestProbe("POST", "restricted", null);
        filter(true, "full,restricted=orders", DomainApiAccess.READ,
                PluginDomainResource.class, get).filter(read.context());
        assertNull(read.aborted, "READ is semantic and does not depend on the HTTP verb");

        RequestProbe privileged = new RequestProbe("GET", "restricted", null);
        filter(true, "full,restricted=orders", DomainApiAccess.PRIVILEGED,
                PluginDomainResource.class, get).filter(privileged.context());
        assertEquals(403, privileged.aborted.getStatus());

        RequestProbe privilegedFull = new RequestProbe("GET", "full", null);
        filter(true, "full,restricted=orders", DomainApiAccess.PRIVILEGED,
                PluginDomainResource.class, get).filter(privilegedFull.context());
        assertNull(privilegedFull.aborted);

        RequestProbe keyOnlyMissing = new RequestProbe("GET", null, null);
        filter(false, "full,restricted=orders", DomainApiAccess.PRIVILEGED,
                PluginDomainResource.class, get).filter(keyOnlyMissing.context());
        assertEquals(401, keyOnlyMissing.aborted.getStatus());

        RequestProbe keyOnlyRestricted = new RequestProbe("GET", "restricted", null);
        filter(false, "full,restricted=orders", DomainApiAccess.PRIVILEGED,
                PluginDomainResource.class, get).filter(keyOnlyRestricted.context());
        assertEquals(403, keyOnlyRestricted.aborted.getStatus());

        RequestProbe keyOnlyFull = new RequestProbe("GET", "full", null);
        filter(false, "full,restricted=orders", DomainApiAccess.PRIVILEGED,
                PluginDomainResource.class, get).filter(keyOnlyFull.context());
        assertNull(keyOnlyFull.aborted);

        RequestProbe internal = new RequestProbe("GET", "full", null);
        filter(true, "full,restricted=orders", DomainApiAccess.INTERNAL,
                PluginDomainResource.class, get).filter(internal.context());
        assertEquals(404, internal.aborted.getStatus());
    }

    @Test
    void absentOrUnclassifiableDomainRouteIsNotPublished() throws Exception {
        Method get = PluginDomainResource.class.getMethod(
                "get", String.class, String.class, UriInfo.class);
        RequestProbe unknown = new RequestProbe("GET", "full", null);

        filter(true, "full", null, PluginDomainResource.class, get)
                .filter(unknown.context());

        assertEquals(404, unknown.aborted.getStatus());
    }

    @Test
    void matrixAndTrailingSlashAliasesFailBeforeNormalizedDispatch() throws Exception {
        Method get = PluginDomainResource.class.getMethod(
                "get", String.class, String.class, UriInfo.class);
        RequestProbe matrixDomain = new RequestProbe("GET", null, null);
        filter(false, "", DomainApiAccess.READ,
                PluginDomainResource.class, get,
                "plugins/bundle/route;x=1").filter(matrixDomain.context());
        assertEquals(404, matrixDomain.aborted.getStatus());

        Method query = AppChainResource.ChainScopedResource.class.getMethod(
                "query", String.class,
                AppChainResource.ChainScopedResource.QueryRequest.class);
        RequestProbe trailingQuery = new RequestProbe("POST", null, new byte[0]);
        filter(false, "", DomainApiAccess.READ,
                AppChainResource.ChainScopedResource.class, query,
                "app-chain/chains/chain/query/route/").filter(trailingQuery.context());
        assertEquals(400, trailingQuery.aborted.getStatus());
    }

    private static AppChainApiKeyFilter filter(
            boolean authEnabled,
            String keys,
            DomainApiAccess domainAccess,
            Class<?> resourceClass,
            Method resourceMethod) {
        String rawPath = resourceClass == PluginDomainResource.class
                ? "plugins/bundle/route"
                : "app-chain/chains/chain/query/route";
        return filter(authEnabled, keys, domainAccess,
                resourceClass, resourceMethod, rawPath);
    }

    private static AppChainApiKeyFilter filter(
            boolean authEnabled,
            String keys,
            DomainApiAccess domainAccess,
            Class<?> resourceClass,
            Method resourceMethod,
            String rawPath) {
        AppChainApiKeyFilter filter = new AppChainApiKeyFilter();
        filter.authEnabled = authEnabled;
        filter.apiKeysConfig = Optional.ofNullable(keys);
        filter.apiPathPrefix = "";
        filter.objectMapper = new ObjectMapper();
        filter.domainApis = new AccessGateway(domainAccess);
        filter.resourceInfo = new ResourceInfo() {
            @Override
            public Method getResourceMethod() {
                return resourceMethod;
            }

            @Override
            public Class<?> getResourceClass() {
                return resourceClass;
            }
        };
        MultivaluedHashMap<String, String> path = new MultivaluedHashMap<>();
        path.putSingle("bundleId", "bundle");
        path.putSingle("chainId", "chain");
        path.putSingle("path", "route");
        filter.uriInfo = (UriInfo) Proxy.newProxyInstance(
                UriInfo.class.getClassLoader(), new Class<?>[]{UriInfo.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPathParameters" -> path;
                    case "getPath" -> rawPath;
                    case "getRequestUri" -> java.net.URI.create(
                            "http://localhost" + (rawPath.startsWith("/") ? "" : "/")
                                    + rawPath);
                    default -> null;
                });
        return filter;
    }

    private static final class AccessGateway implements DomainApiGateway {
        private final DomainApiAccess access;

        private AccessGateway(DomainApiAccess access) {
            this.access = access;
        }

        @Override
        public List<DomainApiRouteInfo> routes() {
            return List.of();
        }

        @Override
        public Optional<DomainApiAccess> access(
                String bundleId, DomainHttpMethod method, String relativePath) {
            return Optional.ofNullable(access);
        }

        @Override
        public DomainApiResponse dispatch(
                String bundleId,
                DomainHttpMethod method,
                String relativePath,
                Map<String, List<String>> queryParameters,
                byte[] body) {
            throw new AssertionError("filter must not dispatch");
        }
    }

    private static final class RequestProbe {
        private final String method;
        private final String apiKey;
        private InputStream entity;
        private Response aborted;

        private RequestProbe(String method, String apiKey, byte[] body) {
            this.method = method;
            this.apiKey = apiKey;
            this.entity = new ByteArrayInputStream(body != null ? body : new byte[0]);
        }

        private ContainerRequestContext context() {
            return (ContainerRequestContext) Proxy.newProxyInstance(
                    ContainerRequestContext.class.getClassLoader(),
                    new Class<?>[]{ContainerRequestContext.class},
                    (proxy, called, args) -> switch (called.getName()) {
                        case "getMethod" -> method;
                        case "getHeaderString" -> apiKey;
                        case "hasEntity" -> entity.available() > 0;
                        case "getEntityStream" -> entity;
                        case "setEntityStream" -> {
                            entity = (InputStream) args[0];
                            yield null;
                        }
                        case "abortWith" -> {
                            aborted = (Response) args[0];
                            yield null;
                        }
                        case "toString" -> "request-probe";
                        default -> defaultValue(called.getReturnType());
                    });
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) {
                return null;
            }
            if (type == boolean.class) {
                return false;
            }
            if (type == char.class) {
                return '\0';
            }
            if (type == byte.class) {
                return (byte) 0;
            }
            if (type == short.class) {
                return (short) 0;
            }
            if (type == int.class) {
                return 0;
            }
            if (type == long.class) {
                return 0L;
            }
            if (type == float.class) {
                return 0F;
            }
            return 0D;
        }
    }
}
