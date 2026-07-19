package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ReportServerTest {
    @TempDir
    Path temporary;

    @Test
    void servesOnlyCredentialFreeReadOnlySurfaceWithSecurityHeaders() throws Exception {
        ReportStore store = new ReportStore(temporary.resolve("reports"));
        Instant start = Instant.parse("2026-07-18T00:00:00Z");
        byte[] content = "{\"evidence\":\"inspection-product-a\",\"version\":1}\n"
                .getBytes(StandardCharsets.UTF_8);
        ScenarioReport scenario = ReportStoreTest.verifiedReport(
                "scenario-ui", "inspection-product-a", 1, content, start);
        store.write(scenario);
        store.writeVerifiedContent(scenario.evidenceId(), scenario.businessVersion(),
                content, Digests.sha256(content));
        store.writeLoad(new LoadReport(2, "load-1784332800000-1234abcd", "load-ui",
                2, 2, 2, 0, 1, "lifecycle", 1, 1, 8,
                "L1_ANCHORED", true, "PASS",
                start.toString(), start.plusSeconds(1).toString(),
                1_000, 2.0, 2.0,
                new LoadReport.Throughput(8, 8, 2, 2, 6, 6, 2, 2),
                new LoadReport.LatencyMillis(400, 450, 500, 500),
                java.util.Map.of("lifecycle", new LoadReport.StageMetrics(
                        2, 2, 0, 1_000, 2.0,
                        new LoadReport.LatencyMillis(400, 450, 500, 500))),
                java.util.Map.of(), java.util.List.of()));
        try (ReportServer server = new ReportServer("127.0.0.1", 0, store)) {
            server.start();
            URI base = URI.create("http://127.0.0.1:" + server.port());

            HttpResponse<byte[]> health = send(base.resolve("/healthz"), "GET");
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(new String(health.body(), StandardCharsets.UTF_8))
                    .isEqualTo("{\"status\":\"UP\"}\n");
            assertSecurityHeaders(health);

            HttpResponse<byte[]> report = send(base.resolve("/api/v1/reports/latest"), "GET");
            assertThat(report.statusCode()).isEqualTo(200);
            assertThat(StrictJson.parse(report.body()).path("outcome").asText()).isEqualTo("PASS");

            HttpResponse<byte[]> history = send(base.resolve("/api/v1/reports"), "GET");
            assertThat(history.statusCode()).isEqualTo(200);
            assertThat(StrictJson.parse(history.body())).hasSize(1);
            assertSecurityHeaders(history);

            HttpResponse<byte[]> load = send(base.resolve("/api/v1/load/latest"), "GET");
            assertThat(load.statusCode()).isEqualTo(200);
            assertThat(StrictJson.parse(load.body()).path("requested").asInt()).isEqualTo(2);
            assertSecurityHeaders(load);

            HttpResponse<byte[]> catalog = send(base.resolve("/api/v1/evidence"), "GET");
            assertThat(catalog.statusCode()).isEqualTo(200);
            assertThat(StrictJson.parse(catalog.body()).path("items")).hasSize(1);
            assertThat(StrictJson.parse(catalog.body()).path("page").asInt()).isEqualTo(1);
            assertThat(StrictJson.parse(catalog.body()).path("pageSize").asInt()).isEqualTo(20);
            assertThat(StrictJson.parse(catalog.body()).path("items").get(0)
                    .path("contentAvailable").asBoolean()).isTrue();
            assertSecurityHeaders(catalog);

            HttpResponse<byte[]> filteredCatalog = send(URI.create(base
                    + "/api/v1/evidence?page=1&pageSize=20&q=inspection-product-a"), "GET");
            assertThat(filteredCatalog.statusCode()).isEqualTo(200);
            assertThat(StrictJson.parse(filteredCatalog.body()).path("total").asInt())
                    .isEqualTo(1);
            assertSecurityHeaders(filteredCatalog);

            HttpResponse<byte[]> detail = send(base.resolve(
                    "/api/v1/evidence/inspection-product-a/versions/1"), "GET");
            assertThat(detail.statusCode()).isEqualTo(200);
            assertThat(StrictJson.parse(detail.body()).path("content").path("text").asText())
                    .isEqualTo(new String(content, StandardCharsets.UTF_8));
            assertSecurityHeaders(detail);

            HttpResponse<byte[]> head = send(base.resolve("/api/v1/reports/latest"), "HEAD");
            assertThat(head.statusCode()).isEqualTo(200);
            assertThat(head.body()).isEmpty();

            HttpResponse<byte[]> method = send(base.resolve("/healthz"), "POST");
            assertThat(method.statusCode()).isEqualTo(405);
            assertThat(method.headers().firstValue("Allow")).hasValue("GET, HEAD");

            assertThat(send(base.resolve("/%2e%2e/secret"), "GET").statusCode()).isEqualTo(404);
            assertThat(send(base.resolve(
                    "/api/v1/evidence/%2e%2e/versions/1"), "GET").statusCode()).isEqualTo(404);
            assertThat(send(base.resolve(
                    "/api/v1/evidence/inspection-product-a/versions/0"), "GET").statusCode())
                    .isEqualTo(404);
            assertThat(send(URI.create(base + "/index.html?key=secret"), "GET").statusCode())
                    .isEqualTo(404);
            assertThat(send(URI.create(base + "/api/v1/evidence?page=0"), "GET").statusCode())
                    .isEqualTo(404);
            assertThat(send(URI.create(base + "/api/v1/evidence?pageSize=101"), "GET")
                    .statusCode()).isEqualTo(404);
            assertThat(send(URI.create(base + "/api/v1/evidence?page=1&page=2"), "GET")
                    .statusCode()).isEqualTo(404);
            assertThat(send(URI.create(base + "/api/v1/evidence?unknown=1"), "GET")
                    .statusCode()).isEqualTo(404);
        }
    }

    @Test
    void uiNeverUsesCredentialStorageOrUnsafeHtmlInsertion() throws IOException {
        String script;
        String page;
        String styles;
        try (var scriptInput = ReportServer.class.getResourceAsStream("/demo-ui/app.js");
             var pageInput = ReportServer.class.getResourceAsStream("/demo-ui/index.html");
             var styleInput = ReportServer.class.getResourceAsStream("/demo-ui/styles.css")) {
            script = new String(scriptInput.readAllBytes(), StandardCharsets.UTF_8);
            page = new String(pageInput.readAllBytes(), StandardCharsets.UTF_8);
            styles = new String(styleInput.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(script).contains("textContent", "openEvidence", "latestToken",
                        "Browser SHA-256 matched", "previousPageButton",
                        "nextPageButton", "pageSize: String(state.catalogPageSize)")
                .doesNotContain("innerHTML", "localStorage", "sessionStorage", "apiKey",
                        "render(report)", "showMoreButton");
        assertThat(page).doesNotContain("onclick=", "<script>")
                .contains("src=\"/app.js\"", "does not prove that the real-world inspection",
                        "claim is true",
                        "Latest published evidence", "Evidence library",
                        "Verified evidence document", "Business authorization",
                        "Previous", "Next",
                        "Recent activity", "Latest load run");
        assertThat(script).contains("Role quorum authenticated", "relayMember",
                "satisfiedClauses", "acceptedDecisions");
        assertThat(styles)
                .contains("grid-template-columns: repeat(4, minmax(0, 1fr))",
                        "grid-template-columns: minmax(8.5rem, 39%) minmax(0, 1fr)",
                        ".pagination", "dialog::backdrop", "@media (max-width: 760px)",
                        "@media (max-width: 460px)")
                .doesNotContain("grid-template-columns: minmax(7rem, auto) 1fr");
    }

    private static HttpResponse<byte[]> send(URI uri, String method) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static void assertSecurityHeaders(HttpResponse<?> response) {
        assertThat(response.headers().firstValue("Content-Security-Policy")).isPresent();
        assertThat(response.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(response.headers().firstValue("Permissions-Policy")).isPresent();
        assertThat(response.headers().firstValue("Cross-Origin-Resource-Policy"))
                .hasValue("same-origin");
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
    }
}
