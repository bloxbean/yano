package com.bloxbean.cardano.yano.api.plugin.domain;

import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.appchain.AppQueryResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainApiModelTest {

    @Test
    void validatesRelativeWholeSegmentRouteTemplates() {
        DomainApiRoute route = new DomainApiRoute(
                "passport.read",
                DomainHttpMethod.GET,
                "claims/{claim_id}/passports/{asset_id}",
                DomainApiAccess.READ);

        assertThat(route.parameterNames()).containsExactly("claim_id", "asset_id");
        assertThat(route.template()).isEqualTo("claims/{claim_id}/passports/{asset_id}");
        assertThat(DomainApiMediaType.JSON.value()).isEqualTo("application/json");
        assertThat(DomainApiMediaType.OCTET_STREAM.value())
                .isEqualTo("application/octet-stream");
    }

    @Test
    void rejectsAmbiguousOrUnsafeRouteTemplates() {
        assertInvalidRoute("/passports/{id}", "normalized relative path");
        assertInvalidRoute("passports/{id}/", "normalized relative path");
        assertInvalidRoute("passports//{id}", "non-empty segments");
        assertInvalidRoute("passports/../claims", "dot segments");
        assertInvalidRoute("passports/%2fid", "normalized relative path");
        assertInvalidRoute("passports/{id}/claims/{id}", "repeat a path parameter");
        assertInvalidRoute("passports/prefix-{id}", "whole-segment");
        assertInvalidRoute("passports/{Bad}", "whole-segment");
        assertInvalidRoute("a/b/c/d/e/f/g/h/i", "1-8");
        assertInvalidRoute("a".repeat(DomainApiRoute.MAX_TEMPLATE_LENGTH + 1), "at most 256");
        assertThatThrownBy(() -> new DomainApiRoute(
                "Bad Route", DomainHttpMethod.GET, "passports", DomainApiAccess.READ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("routeId");
    }

    @Test
    void routeSetRejectsDuplicateIdsAndStructuralAmbiguity() {
        DomainApiRoute first = route("read", DomainHttpMethod.GET,
                "claims/{claim_id}/status");

        assertThatThrownBy(() -> DomainApiRouteSet.validateAndOrder(List.of(
                first,
                route("read", DomainHttpMethod.POST, "claims"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate domain API route id 'read'");

        assertThatThrownBy(() -> DomainApiRouteSet.validateAndOrder(List.of(
                first,
                route("other", DomainHttpMethod.GET, "claims/{asset_id}/status"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("structurally duplicate domain API routes")
                .hasMessageContaining("claims/{claim_id}/status")
                .hasMessageContaining("claims/{asset_id}/status");

        assertThat(DomainApiRouteSet.validateAndOrder(List.of(
                first,
                route("post", DomainHttpMethod.POST, "claims/{asset_id}/status"))))
                .hasSize(2);
    }

    @Test
    void routeSetUsesStableLiteralBeforeVariableDispatchOrder() {
        DomainApiRoute earlyVariable = route("more-literals", DomainHttpMethod.GET,
                "{tenant}/passports/status");
        DomainApiRoute earlyLiteral = route("early-literal", DomainHttpMethod.GET,
                "acme/{kind}/{id}");
        DomainApiRoute crossingVariable = route("crossing-variable", DomainHttpMethod.POST,
                "{tenant}/open");
        DomainApiRoute crossingLiteral = route("crossing-literal", DomainHttpMethod.POST,
                "acme/{kind}");
        DomainApiRoute shortRoute = route("short", DomainHttpMethod.GET, "health");
        List<DomainApiRoute> source = new ArrayList<>(List.of(
                earlyVariable, crossingVariable, earlyLiteral, crossingLiteral, shortRoute));

        List<DomainApiRoute> ordered = DomainApiRouteSet.validateAndOrder(source);
        source.clear();

        assertThat(ordered).extracting(DomainApiRoute::routeId)
                .containsExactly("early-literal", "short", "more-literals",
                        "crossing-literal", "crossing-variable");
        assertThatThrownBy(() -> ordered.add(earlyVariable))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void requestDeepCopiesAndRedactsEveryCallerOwnedContainer() {
        Map<String, String> path = new LinkedHashMap<>();
        path.put("asset_id", "abc-123");
        List<String> values = new ArrayList<>(List.of("active", "verified"));
        Map<String, List<String>> query = new LinkedHashMap<>();
        query.put("status", values);
        byte[] body = {1, 2, 3};

        DomainApiRequest request = new DomainApiRequest(
                "passport.search",
                DomainHttpMethod.POST,
                "passports/abc-123",
                path,
                query,
                body);
        path.put("asset_id", "changed");
        values.set(0, "changed");
        query.put("other", List.of("changed"));
        body[0] = 9;

        assertThat(request.pathParameters()).containsEntry("asset_id", "abc-123");
        assertThat(request.queryParameters()).containsEntry(
                "status", List.of("active", "verified"));
        assertThat(request.body()).containsExactly(1, 2, 3);
        byte[] returned = request.body();
        returned[0] = 8;
        assertThat(request.body()).containsExactly(1, 2, 3);
        assertThatThrownBy(() -> request.pathParameters().put("next", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> request.queryParameters().get("status").add("next"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(request.toString())
                .contains("path=<redacted>", "queryParameterNames=[status]", "bodyBytes=3")
                .doesNotContain("abc-123", "active", "verified");
    }

    @Test
    void requestEnforcesMethodPathQueryAndBodyBounds() {
        assertThatThrownBy(() -> request(
                DomainHttpMethod.GET, "passports/id", Map.of(), new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GET");
        assertThatThrownBy(() -> request(
                DomainHttpMethod.POST, "passports/%2fid", Map.of(), new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("normalized relative path");
        assertThatThrownBy(() -> request(
                DomainHttpMethod.POST,
                "passports/id",
                Map.of("q", List.of("bad\nvalue")),
                new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control-free");
        assertThatThrownBy(() -> request(
                DomainHttpMethod.POST,
                "passports/id",
                Map.of("q", List.of("x".repeat(DomainApiRequest.MAX_QUERY_VALUE_LENGTH + 1))),
                new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2048");
        assertThatThrownBy(() -> request(
                DomainHttpMethod.POST,
                "passports/id",
                Map.of(),
                new byte[DomainApiRequest.MAX_BODY_BYTES + 1]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("65536");
    }

    @Test
    void responseCopiesBytesAndReservesServerFailuresForTheHost() {
        byte[] bytes = {4, 5};
        DomainApiResponse response = new DomainApiResponse(
                200, DomainApiMediaType.JSON, bytes);
        bytes[0] = 9;

        assertThat(response.body()).containsExactly(4, 5);
        byte[] returned = response.body();
        returned[0] = 8;
        assertThat(response.body()).containsExactly(4, 5);
        assertThat(response.toString()).contains("bodyBytes=2").doesNotContain("4, 5");
        assertThatThrownBy(() -> new DomainApiResponse(
                500, DomainApiMediaType.JSON, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host owns 5xx");
        for (int pluginOwned : List.of(400, 404, 409, 410, 422)) {
            assertThat(new DomainApiResponse(
                    pluginOwned, DomainApiMediaType.JSON, new byte[0]).status())
                    .isEqualTo(pluginOwned);
        }
        for (int hostOwned : List.of(401, 403, 407, 429)) {
            assertThatThrownBy(() -> new DomainApiResponse(
                    hostOwned, DomainApiMediaType.JSON, new byte[0]))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("host-owned");
        }
        for (int unsupported : List.of(201, 202, 204, 205, 206, 418)) {
            assertThatThrownBy(() -> new DomainApiResponse(
                    unsupported, DomainApiMediaType.JSON, new byte[0]))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("allow-list");
        }
        assertThatThrownBy(() -> new DomainApiResponse(
                200,
                DomainApiMediaType.OCTET_STREAM,
                new byte[DomainApiResponse.MAX_BODY_BYTES + 1]))
                .isInstanceOfSatisfying(DomainApiException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(
                            DomainApiException.Code.RESULT_TOO_LARGE);
                    assertThat(failure).hasMessage(
                            "Domain API response exceeds the host size limit");
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void contextDeepCopiesTypedConfigAndNeverPrintsValues() {
        String secretKey = "credential-key-sentinel";
        String secretValue = "credential-value-sentinel";
        List<Object> flags = new ArrayList<>(List.of("one"));
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("flags", flags);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("endpoint", "https://internal.example");
        source.put("limits", nested);
        source.put(secretKey, secretValue);

        DomainApiContext context = new DomainApiContext(source, emptyQueries());
        flags.add("two");
        nested.put("new", true);
        source.put("secret", "do-not-copy-late");

        Map<String, Object> copiedNested = (Map<String, Object>) context.bundleConfig().get("limits");
        assertThat((List<Object>) copiedNested.get("flags")).containsExactly("one");
        assertThat(copiedNested).doesNotContainKey("new");
        assertThat(context.bundleConfig()).doesNotContainKey("secret");
        assertThatThrownBy(() -> context.bundleConfig().put("next", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((List<Object>) copiedNested.get("flags")).add("next"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(context.toString())
                .contains("bundleConfigEntries=3", "queryService=<host-owned>")
                .doesNotContain("endpoint", "limits", "https://internal.example",
                        secretKey, secretValue);
        assertThatThrownBy(() -> new DomainApiContext(
                Map.of("mutable", new StringBuilder("value")), emptyQueries()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("immutable scalars");
        assertThatThrownBy(() -> new DomainApiContext(
                Map.of("safe\u202econfusing", "value"), emptyQueries()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ASCII property names");

        Map<String, Object> cycle = new LinkedHashMap<>();
        cycle.put("nested", cycle);
        assertThatThrownBy(() -> new DomainApiContext(cycle, emptyQueries()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reference cycle");
        assertThatThrownBy(() -> new DomainApiContext(
                Map.of("number", Double.NaN), emptyQueries()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("numbers must be finite");
    }

    @Test
    void contextBoundsQueryInputsAndCopiesChainListsAndRequestBytes() {
        List<String> chainIds = new ArrayList<>(List.of("zeta", "alpha"));
        byte[][] observed = new byte[1][];
        DomainQueryService delegate = new DomainQueryService() {
            @Override
            public List<String> chainIds() {
                return chainIds;
            }

            @Override
            public AppQueryResult query(String chainId, String path, byte[] params) {
                observed[0] = params;
                params[0] = 99;
                throw new AppQueryException(AppQueryException.Code.UNAVAILABLE, "test stop");
            }
        };
        DomainQueryService bounded = new DomainApiContext(Map.of(), delegate).queryService();

        List<String> first = bounded.chainIds();
        chainIds.add("later");
        assertThat(first).containsExactly("alpha", "zeta");
        assertThatThrownBy(() -> first.add("next"))
                .isInstanceOf(UnsupportedOperationException.class);

        byte[] request = {7};
        assertThatThrownBy(() -> bounded.query("alpha", "passport/read", request))
                .isInstanceOf(AppQueryException.class);
        assertThat(observed[0]).isNotSameAs(request);
        assertThat(request).containsExactly(7);
        assertThatThrownBy(() -> bounded.query("alpha", "passport/%2fread", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("normalized relative path");
        assertThatThrownBy(() -> bounded.query(
                "alpha", "passport/read", new byte[DomainQueryService.MAX_REQUEST_BYTES + 1]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("65536");
    }

    private static void assertInvalidRoute(String template, String reason) {
        assertThatThrownBy(() -> new DomainApiRoute(
                "route", DomainHttpMethod.GET, template, DomainApiAccess.READ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(reason);
    }

    private static DomainApiRoute route(
            String routeId,
            DomainHttpMethod method,
            String template
    ) {
        return new DomainApiRoute(routeId, method, template, DomainApiAccess.READ);
    }

    private static DomainApiRequest request(
            DomainHttpMethod method,
            String path,
            Map<String, List<String>> query,
            byte[] body
    ) {
        return new DomainApiRequest("route", method, path, Map.of(), query, body);
    }

    private static DomainQueryService emptyQueries() {
        return new DomainQueryService() {
            @Override
            public List<String> chainIds() {
                return List.of();
            }

            @Override
            public AppQueryResult query(String chainId, String path, byte[] params) {
                throw new AppQueryException(AppQueryException.Code.UNAVAILABLE, "not configured");
            }
        };
    }
}
