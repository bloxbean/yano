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

import static org.assertj.core.api.Assertions.assertThat;

class ReportServerTest {
    @TempDir
    Path temporary;

    @Test
    void servesOnlyCredentialFreeReadOnlySurfaceWithSecurityHeaders() throws Exception {
        ReportStore store = new ReportStore(temporary.resolve("reports"));
        store.write(ReportStoreTest.report("scenario-ui", "PASS", null));
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

            HttpResponse<byte[]> head = send(base.resolve("/api/v1/reports/latest"), "HEAD");
            assertThat(head.statusCode()).isEqualTo(200);
            assertThat(head.body()).isEmpty();

            HttpResponse<byte[]> method = send(base.resolve("/healthz"), "POST");
            assertThat(method.statusCode()).isEqualTo(405);
            assertThat(method.headers().firstValue("Allow")).hasValue("GET, HEAD");

            assertThat(send(base.resolve("/%2e%2e/secret"), "GET").statusCode()).isEqualTo(404);
            assertThat(send(URI.create(base + "/index.html?key=secret"), "GET").statusCode())
                    .isEqualTo(404);
        }
    }

    @Test
    void uiNeverUsesCredentialStorageOrUnsafeHtmlInsertion() throws IOException {
        String script;
        String page;
        try (var scriptInput = ReportServer.class.getResourceAsStream("/demo-ui/app.js");
             var pageInput = ReportServer.class.getResourceAsStream("/demo-ui/index.html")) {
            script = new String(scriptInput.readAllBytes(), StandardCharsets.UTF_8);
            page = new String(pageInput.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(script).contains("textContent", "failureCode")
                .doesNotContain("innerHTML", "localStorage", "sessionStorage", "apiKey");
        assertThat(page).doesNotContain("onclick=", "<script>")
                .contains("src=\"/app.js\"", "does not prove that the real-world inspection claim is true");
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
