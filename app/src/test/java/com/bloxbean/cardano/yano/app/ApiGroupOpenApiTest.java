package com.bloxbean.cardano.yano.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-034: the REST surface is published as one OpenAPI document per API
 * group (core / app-chain / devnet / admin) selected by SmallRye scan
 * profiles, plus the unchanged "all" document at {@code /q/openapi}.
 * Swagger UI exposes the groups in a "Select a definition" drop-down with
 * Core API pre-selected.
 *
 * <p>Only the documentation is grouped — every route stays exactly where it
 * was. Path keys are compared under the artifact prefix read from the
 * default document, so the test is independent of the baked prefix
 * (ADR-018).</p>
 */
@QuarkusTest
@TestProfile(NoAutoStartTestProfile.class)
class ApiGroupOpenApiTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> GROUPS = List.of("core", "app-chain", "devnet", "admin");
    private static String prefix;

    @Test
    void coreDocument_containsDeveloperSurfaceOnly() {
        Map<String, Set<String>> core = operations("/q/openapi-core");

        assertOperation(core, "GET", "/blocks/latest");
        assertOperation(core, "POST", "/tx/submit");
        assertOperation(core, "GET", "/txs/{txHash}/utxos");
        assertOperation(core, "GET", "/addresses/{address}/utxos");
        assertOperation(core, "GET", "/epochs/latest/parameters");
        assertOperation(core, "GET", "/status");
        assertOperation(core, "POST", "/utils/txs/evaluate");
        // Read-only node views are shared with the admin group.
        assertOperation(core, "GET", "/node/tip");
        assertOperation(core, "GET", "/node/status");
        assertOperation(core, "GET", "/node/protocol-params");

        assertNoOperation(core, "/devnet/rollback");
        assertNoOperation(core, "/node/start");
        assertNoOperation(core, "/node/stop");
        assertNoOperation(core, "/node/recover");
        assertNoOperation(core, "/node/tx/submit");
        assertNoOperation(core, "/api/debug/adapot/{epoch}");
        assertNoOperation(core, "/plugin-operations");
        assertNoOperation(core, "/app-chain/chains");
    }

    @Test
    void appChainDocument_containsChainScopedAndPluginRoutesOnly() {
        Map<String, Set<String>> appChain = operations("/q/openapi-app-chain");

        assertOperation(appChain, "GET", "/app-chain/chains");
        // Sub-resource operations must inherit the group from ChainScopedResource.
        assertOperation(appChain, "GET", "/app-chain/chains/{chainId}/status");
        assertOperation(appChain, "POST", "/app-chain/chains/{chainId}/messages");
        assertOperation(appChain, "GET", "/app-chain/chains/{chainId}/state/proof/{keyHex}");
        assertOperation(appChain, "GET", "/plugins/{bundleId}/{path}");

        assertNoOperation(appChain, "/blocks/latest");
        assertNoOperation(appChain, "/devnet/rollback");
        assertNoOperation(appChain, "/node/start");
        assertNoOperation(appChain, "/plugin-operations");
        // Chain-less aliases stay hidden everywhere (AppChainOpenApiTest).
        assertNoOperation(appChain, "/app-chain/status");
    }

    @Test
    void devnetDocument_containsDevnetControlsOnly() {
        Map<String, Set<String>> devnet = operations("/q/openapi-devnet");

        assertOperation(devnet, "POST", "/devnet/rollback");
        assertOperation(devnet, "POST", "/devnet/snapshot");
        assertOperation(devnet, "POST", "/devnet/fund");
        assertOperation(devnet, "POST", "/devnet/time/advance");

        assertThat(devnet.keySet()).allMatch(path -> path.contains("/devnet/"),
                "devnet document must not contain non-devnet paths: " + devnet.keySet());
    }

    @Test
    void adminDocument_containsOperatorSurfaceOnly() {
        Map<String, Set<String>> admin = operations("/q/openapi-admin");

        assertOperation(admin, "POST", "/node/start");
        assertOperation(admin, "POST", "/node/stop");
        assertOperation(admin, "POST", "/node/recover");
        assertOperation(admin, "GET", "/node/config");
        assertOperation(admin, "POST", "/node/tx/submit");
        assertOperation(admin, "GET", "/node/tip");
        assertOperation(admin, "GET", "/node/epoch-nonce");
        assertOperation(admin, "GET", "/plugin-operations");
        assertOperation(admin, "GET", "/api/debug/adapot/{epoch}");

        assertNoOperation(admin, "/blocks/latest");
        assertNoOperation(admin, "/tx/submit");
        assertNoOperation(admin, "/devnet/rollback");
        assertNoOperation(admin, "/app-chain/chains");
    }

    @Test
    void defaultDocument_stillContainsEveryGroup() {
        Map<String, Set<String>> all = operations("/q/openapi");

        assertOperation(all, "GET", "/blocks/latest");
        assertOperation(all, "GET", "/app-chain/chains/{chainId}/status");
        assertOperation(all, "POST", "/devnet/rollback");
        assertOperation(all, "POST", "/node/start");
        assertOperation(all, "GET", "/api/debug/adapot/{epoch}");
    }

    /**
     * Every operation published in the default document must be reachable
     * from at least one group; otherwise a new resource was added without an
     * {@code ApiGroup} extension and would be invisible on the default
     * Swagger UI page.
     */
    @Test
    void everyOperation_belongsToAtLeastOneGroup() {
        Map<String, Set<String>> all = operations("/q/openapi");
        Map<String, Set<String>> union = new TreeMap<>();
        for (String group : GROUPS) {
            operations("/q/openapi-" + group).forEach((path, methods) ->
                    union.computeIfAbsent(path, ignored -> new TreeSet<>()).addAll(methods));
        }

        List<String> orphans = new ArrayList<>();
        all.forEach((path, methods) -> {
            Set<String> grouped = union.getOrDefault(path, Set.of());
            for (String method : methods) {
                if (!grouped.contains(method)) {
                    orphans.add(method + " " + path);
                }
            }
        });
        assertThat(orphans)
                .as("operations missing an ApiGroup @Extension (ADR-034)")
                .isEmpty();
        assertThat(all).isNotEmpty();
    }

    @Test
    void swaggerUi_offersGroupDropDownWithCoreSelected() {
        String index = given()
                .when().get("/q/swagger-ui/")
                .then()
                .statusCode(200)
                .extract().asString();

        assertThat(index).contains("url: \"/q/openapi-core\", name: \"Core API\"");
        assertThat(index).contains("url: \"/q/openapi-app-chain\", name: \"App Chain API\"");
        assertThat(index).contains("url: \"/q/openapi-devnet\", name: \"Devnet API\"");
        assertThat(index).contains("url: \"/q/openapi-admin\", name: \"Admin API\"");
        assertThat(index).contains("url: \"/q/openapi\", name: \"All APIs\"");
        assertThat(index).contains("\"urls.primaryName\": 'Core API'");
    }

    @Test
    void groupDocuments_carryTheirOwnTitles() {
        assertThat(info("/q/openapi-core")).isEqualTo("Yano Core API");
        assertThat(info("/q/openapi-app-chain")).isEqualTo("Yano App Chain API");
        assertThat(info("/q/openapi-devnet")).isEqualTo("Yano Devnet API");
        assertThat(info("/q/openapi-admin")).isEqualTo("Yano Admin API");
        assertThat(info("/q/openapi")).isEqualTo("Yano API");
    }

    // ------------------------------------------------------------------

    private static JsonNode document(String path) {
        String body = given()
                .when().get(path + "?format=json")
                .then()
                .statusCode(200)
                .extract().asString();
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new AssertionError("Invalid OpenAPI JSON at " + path, e);
        }
    }

    private static String info(String path) {
        return document(path).path("info").path("title").asText();
    }

    /** path → upper-case HTTP methods documented for it. */
    private static Map<String, Set<String>> operations(String docPath) {
        Map<String, Set<String>> result = new TreeMap<>();
        JsonNode paths = document(docPath).path("paths");
        Iterator<Map.Entry<String, JsonNode>> it = paths.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            Set<String> methods = new TreeSet<>();
            entry.getValue().fieldNames().forEachRemaining(field -> {
                switch (field.toLowerCase(Locale.ROOT)) {
                    case "get", "post", "put", "delete", "patch", "head", "options" ->
                            methods.add(field.toUpperCase(Locale.ROOT));
                    default -> { }
                }
            });
            if (!methods.isEmpty()) {
                result.put(entry.getKey(), methods);
            }
        }
        return result;
    }

    /**
     * The artifact API prefix (ADR-018), derived lazily from the default
     * document so assertions compare exact path keys without hard-coding
     * {@code /api/v1}.
     */
    private static synchronized String prefix() {
        if (prefix == null) {
            String anchor = "/blocks/latest";
            prefix = operations("/q/openapi").keySet().stream()
                    .filter(p -> p.endsWith(anchor))
                    .map(p -> p.substring(0, p.length() - anchor.length()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("/blocks/latest missing from /q/openapi"));
        }
        return prefix;
    }

    private static void assertOperation(Map<String, Set<String>> ops, String method, String path) {
        String key = prefix() + path;
        assertThat(ops).as("path " + key + " documented; have " + ops.keySet()).containsKey(key);
        assertThat(ops.get(key)).as(method + " " + key).contains(method);
    }

    private static void assertNoOperation(Map<String, Set<String>> ops, String path) {
        String key = prefix() + path;
        assertThat(ops).as("path " + key + " must be absent").doesNotContainKey(key);
    }
}
