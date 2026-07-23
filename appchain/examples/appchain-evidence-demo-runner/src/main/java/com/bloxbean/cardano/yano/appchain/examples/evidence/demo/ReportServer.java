package com.bloxbean.cardano.yano.appchain.examples.evidence.demo;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Credential-free, read-only report UI using only the JDK HTTP server. */
public final class ReportServer implements AutoCloseable {
    private static final Map<String, Asset> ASSETS = Map.of(
            "/", new Asset("/demo-ui/index.html", "text/html; charset=utf-8"),
            "/index.html", new Asset("/demo-ui/index.html", "text/html; charset=utf-8"),
            "/app.js", new Asset("/demo-ui/app.js", "text/javascript; charset=utf-8"),
            "/styles.css", new Asset("/demo-ui/styles.css", "text/css; charset=utf-8"));
    private static final byte[] HEALTH = "{\"status\":\"UP\"}\n"
            .getBytes(StandardCharsets.UTF_8);
    private static final Pattern EVIDENCE_DETAIL = Pattern.compile(
            "^/api/v1/evidence/([a-z0-9][a-z0-9-]{0,63})/versions/([1-9][0-9]{0,18})$");

    private final HttpServer server;
    private final ExecutorService executor;
    private final ReportStore reports;
    private final Semaphore admission = new Semaphore(8);

    public ReportServer(String bindAddress, int port, ReportStore reports) {
        try {
            this.reports = reports;
            server = HttpServer.create(new InetSocketAddress(bindAddress, port), 16);
            executor = new ThreadPoolExecutor(8, 16, 30, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(16),
                    Thread.ofPlatform().name("evidence-demo-ui-", 0).factory(),
                    new ThreadPoolExecutor.CallerRunsPolicy());
            server.setExecutor(executor);
            server.createContext("/", this::handle);
        } catch (IOException | RuntimeException failure) {
            throw new DemoException(DemoError.UI_START_FAILED);
        }
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        if (!admission.tryAcquire()) {
            try (exchange) {
                secureHeaders(exchange.getResponseHeaders());
                send(exchange, 503, "text/plain; charset=utf-8", new byte[0],
                        exchange.getRequestMethod());
            }
            return;
        }
        try (exchange) {
            secureHeaders(exchange.getResponseHeaders());
            String method = exchange.getRequestMethod();
            if (!("GET".equals(method) || "HEAD".equals(method))) {
                send(exchange, 405, "text/plain; charset=utf-8", new byte[0], "HEAD");
                return;
            }
            String path = exchange.getRequestURI().getRawPath();
            String rawQuery = exchange.getRequestURI().getRawQuery();
            if (rawQuery != null && !"/api/v1/evidence".equals(path)) {
                send(exchange, 404, "text/plain; charset=utf-8", new byte[0], method);
                return;
            }
            if ("/healthz".equals(path)) {
                send(exchange, 200, "application/json; charset=utf-8", HEALTH, method);
                return;
            }
            if ("/api/v1/reports/latest".equals(path)) {
                byte[] report = reports.readLatest();
                send(exchange, report == null ? 404 : 200,
                        "application/json; charset=utf-8",
                        report == null ? new byte[0] : report, method);
                return;
            }
            if ("/api/v1/reports".equals(path)) {
                send(exchange, 200, "application/json; charset=utf-8",
                        reports.readRecent(), method);
                return;
            }
            if ("/api/v1/load/latest".equals(path)) {
                byte[] report = reports.readLatestLoad();
                send(exchange, report == null ? 404 : 200,
                        "application/json; charset=utf-8",
                        report == null ? new byte[0] : report, method);
                return;
            }
            if ("/api/v1/evidence".equals(path)) {
                EvidenceCatalogRequest request;
                try {
                    request = parseEvidenceCatalogRequest(rawQuery);
                } catch (IllegalArgumentException invalidQuery) {
                    send(exchange, 404, "application/json; charset=utf-8", new byte[0], method);
                    return;
                }
                send(exchange, 200, "application/json; charset=utf-8",
                        reports.readEvidenceCatalog(request.page(), request.pageSize(),
                                request.query()), method);
                return;
            }
            Matcher evidenceDetail = EVIDENCE_DETAIL.matcher(path);
            if (evidenceDetail.matches()) {
                final long version;
                try {
                    version = Long.parseLong(evidenceDetail.group(2));
                } catch (NumberFormatException invalidVersion) {
                    send(exchange, 404, "application/json; charset=utf-8",
                            new byte[0], method);
                    return;
                }
                byte[] detail = reports.readEvidenceDetail(
                        evidenceDetail.group(1), version);
                send(exchange, detail == null ? 404 : 200,
                        "application/json; charset=utf-8",
                        detail == null ? new byte[0] : detail, method);
                return;
            }
            Asset asset = ASSETS.get(path);
            if (asset == null) {
                send(exchange, 404, "text/plain; charset=utf-8", new byte[0], method);
                return;
            }
            byte[] body = readAsset(asset.resource());
            send(exchange, 200, asset.contentType(), body, method);
        } finally {
            admission.release();
        }
    }

    private static byte[] readAsset(String resource) {
        try (InputStream input = ReportServer.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new DemoException(DemoError.UI_START_FAILED);
            }
            byte[] body = input.readNBytes(262_145);
            if (body.length > 262_144) {
                throw new DemoException(DemoError.UI_START_FAILED);
            }
            return body;
        } catch (IOException failure) {
            throw new DemoException(DemoError.UI_START_FAILED);
        }
    }

    private static EvidenceCatalogRequest parseEvidenceCatalogRequest(String rawQuery) {
        if (rawQuery == null) {
            return new EvidenceCatalogRequest(1, ReportStore.DEFAULT_CATALOG_PAGE_SIZE, null);
        }
        if (rawQuery.isEmpty() || rawQuery.length() > 128) {
            throw new IllegalArgumentException("invalid catalog query");
        }
        Map<String, String> parameters = new HashMap<>();
        for (String part : rawQuery.split("&", -1)) {
            int separator = part.indexOf('=');
            if (separator < 1 || separator != part.lastIndexOf('=')
                    || separator == part.length() - 1) {
                throw new IllegalArgumentException("invalid catalog query");
            }
            String name = part.substring(0, separator);
            String value = part.substring(separator + 1);
            if (!(name.equals("page") || name.equals("pageSize") || name.equals("q"))
                    || parameters.putIfAbsent(name, value) != null) {
                throw new IllegalArgumentException("invalid catalog query");
            }
        }
        int page = positiveInt(parameters.getOrDefault("page", "1"));
        int pageSize = positiveInt(parameters.getOrDefault(
                "pageSize", Integer.toString(ReportStore.DEFAULT_CATALOG_PAGE_SIZE)));
        if (pageSize > ReportStore.MAX_CATALOG_PAGE_SIZE) {
            throw new IllegalArgumentException("invalid catalog page size");
        }
        String query = parameters.get("q");
        if (query != null) {
            query = query.toLowerCase(Locale.ROOT);
            if (query.length() > 64 || !query.matches("[a-z0-9-]+")) {
                throw new IllegalArgumentException("invalid catalog filter");
            }
        }
        return new EvidenceCatalogRequest(page, pageSize, query);
    }

    private static int positiveInt(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,8}")) {
            throw new IllegalArgumentException("invalid positive integer");
        }
        return Integer.parseInt(value);
    }

    private static void secureHeaders(Headers headers) {
        headers.set("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self'; "
                        + "connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");
        headers.set("Cross-Origin-Resource-Policy", "same-origin");
        headers.set("Cache-Control", "no-store");
    }

    private static void send(HttpExchange exchange, int status, String contentType,
                             byte[] body, String method) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        if (status == 405) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD");
        }
        long length = "HEAD".equals(method) ? -1 : body.length;
        exchange.sendResponseHeaders(status, length);
        if (!"HEAD".equals(method)) {
            exchange.getResponseBody().write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }

    private record Asset(String resource, String contentType) {
    }

    private record EvidenceCatalogRequest(int page, int pageSize, String query) {
    }
}
