package com.bloxbean.cardano.yano.compat.ccl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Thin HTTP client for Yano's Blockfrost-shaped REST API and Prometheus metrics. */
public final class YanoClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final String apiBase;   // e.g. http://localhost:7070/api/v1
    private final String root;      // e.g. http://localhost:7070

    public YanoClient(String apiBase) {
        this.apiBase = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
        this.root = this.apiBase.replaceAll("/api/v\\d+$", "");
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public String apiBase() {
        return apiBase;
    }

    /** Result of one transaction submission. */
    public record SubmitResult(int status, String txHash, String body, long latencyNanos) {
        public boolean accepted() {
            return status >= 200 && status < 300;
        }

        /**
         * Classify a rejection into a stable bucket for the report. Yano returns
         * the mempool admission status or the ledger rule name in the body.
         */
        public String category() {
            if (accepted()) return "ACCEPTED";
            String b = body == null ? "" : body;
            for (String s : new String[]{"TRANSACTION_CAPACITY", "BYTE_CAPACITY", "INDEX_CAPACITY",
                    "REENTRANT_ADMISSION", "CONFLICT", "DUPLICATE", "MALFORMED"}) {
                if (b.contains(s)) return s;
            }
            if (b.contains("UtxoNotFound")) return "UtxoNotFound";
            if (b.contains("ValueNotConserved")) return "ValueNotConserved";
            if (b.contains("BadInputs")) return "BadInputs";
            if (b.contains("FeeTooSmall")) return "FeeTooSmall";
            if (b.contains("MaxTxSize")) return "MaxTxSize";
            return "HTTP_" + status;
        }
    }

    /** Submit raw transaction CBOR. Never throws; transport errors become status 0. */
    public SubmitResult submit(byte[] txCbor) {
        long t0 = System.nanoTime();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(apiBase + "/tx/submit"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/cbor")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(txCbor))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            long dt = System.nanoTime() - t0;
            String hash = null;
            String body = res.body();
            if (body != null && body.startsWith("{")) {
                try {
                    JsonNode n = MAPPER.readTree(body);
                    if (n.hasNonNull("txHash")) hash = n.get("txHash").asText();
                    else if (n.hasNonNull("tx_hash")) hash = n.get("tx_hash").asText();
                } catch (Exception ignored) {
                    // keep raw body for the report
                }
            } else if (body != null && body.length() == 64 && body.matches("[0-9a-fA-F]+")) {
                hash = body;
            }
            return new SubmitResult(res.statusCode(), hash, body, dt);
        } catch (Exception e) {
            return new SubmitResult(0, null, "transport: " + e, System.nanoTime() - t0);
        }
    }

    /** One call to {@code /utils/txs/evaluate}. */
    public record EvaluateResult(int status, String body, Map<String, long[]> exUnits, String failure) {
        public boolean evaluated() {
            return failure == null && !exUnits.isEmpty();
        }
    }

    /**
     * Price a transaction through the node's Ogmios-shaped evaluate endpoint.
     *
     * <p>The endpoint answers 200 for both outcomes, carrying either an
     * {@code EvaluationResult} map of {@code tag:index -> ExUnits} or an
     * {@code EvaluationFailure} message, so the caller has to inspect the body.
     * Inputs resolve from canonical UTXO state only — the script input must be
     * confirmed before evaluating.</p>
     */
    public EvaluateResult evaluate(byte[] txCbor) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(apiBase + "/utils/txs/evaluate"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/cbor")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(txCbor))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            Map<String, long[]> units = new LinkedHashMap<>();
            String failure = null;
            if (res.statusCode() != 200) {
                failure = "HTTP " + res.statusCode() + " " + res.body();
            } else {
                JsonNode result = MAPPER.readTree(res.body()).path("result");
                JsonNode ok = result.path("EvaluationResult");
                JsonNode bad = result.path("EvaluationFailure");
                if (ok.isObject()) {
                    ok.fields().forEachRemaining(e -> units.put(e.getKey(), new long[]{
                            e.getValue().path("memory").asLong(), e.getValue().path("steps").asLong()}));
                }
                if (units.isEmpty()) {
                    failure = bad.isMissingNode() ? "no EvaluationResult in response"
                            : bad.path("message").asText(bad.toString());
                }
            }
            return new EvaluateResult(res.statusCode(), res.body(), units, failure);
        } catch (Exception e) {
            return new EvaluateResult(0, "transport: " + e, new LinkedHashMap<>(), "transport: " + e);
        }
    }

    /** Devnet faucet. Returns the created outpoint as "txHash#index". */
    public String fund(String address, long ada) {
        String payload = "{\"address\":\"" + address + "\",\"ada\":" + ada + "}";
        JsonNode n = postJson("/devnet/fund", payload);
        return n.get("tx_hash").asText() + "#" + n.get("index").asInt();
    }

    public long fundLovelace(String address, long ada) {
        String payload = "{\"address\":\"" + address + "\",\"ada\":" + ada + "}";
        return postJson("/devnet/fund", payload).get("lovelace").asLong();
    }

    public JsonNode utxos(String address) {
        return getJson("/addresses/" + address + "/utxos?count=100");
    }

    /** Outpoint lookup opting into the mempool-inclusive view. */
    public JsonNode utxoAtIncludingMempool(String txHash, int index) {
        return getJson("/utxos/" + txHash + "/" + index + "?include_mempool=true");
    }

    /** Canonical lookup of a single outpoint; throws if not (yet) visible. */
    public JsonNode utxoAt(String txHash, int index) {
        return getJson("/utxos/" + txHash + "/" + index);
    }

    public JsonNode protocolParams() {
        return getJson("/epochs/latest/parameters");
    }

    public JsonNode latestBlock() {
        return getJson("/blocks/latest");
    }

    public JsonNode blockByNumber(long number) {
        return getJson("/blocks/" + number);
    }

    public JsonNode status() {
        return getJson("/status");
    }

    /** Devnet rollback by block count. Returns the raw response text. */
    public String postRollback(int blocks) {
        try {
            return postJson("/devnet/rollback", "{\"count\":" + blocks + "}").toString();
        } catch (RuntimeException e) {
            return "FAILED " + e.getMessage();
        }
    }

    /**
     * Best-effort residency check. Yano has no "is this hash in the mempool"
     * endpoint, so a transaction that is neither canonical nor re-submittable as a
     * duplicate is treated as gone.
     */
    public String txInMempoolHint(String txHash) {
        try {
            getJson("/txs/" + txHash);
            return "confirmed on chain";
        } catch (Exception e) {
            return "not on chain (mempool residency not exposed by any endpoint)";
        }
    }

    /** Scrape the mempool gauges Yano actually exposes on /q/metrics. */
    public Map<String, Double> mempoolMetrics() {
        Map<String, Double> out = new LinkedHashMap<>();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(root + "/q/metrics"))
                    .timeout(Duration.ofSeconds(15)).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            for (String line : res.body().split("\n")) {
                if (line.startsWith("#") || line.isBlank()) continue;
                if (!line.startsWith("yano_node_mempool") && !line.startsWith("yano_node_tx_")) continue;
                int sp = line.lastIndexOf(' ');
                if (sp <= 0) continue;
                try {
                    out.put(line.substring(0, sp), Double.parseDouble(line.substring(sp + 1)));
                } catch (NumberFormatException ignored) {
                    // non-numeric metric line
                }
            }
        } catch (Exception ignored) {
            // sampling is best-effort
        }
        return out;
    }

    public boolean waitUntilReady(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(root + "/q/health/ready"))
                        .timeout(Duration.ofSeconds(5)).GET().build();
                if (http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode() == 200) return true;
            } catch (Exception ignored) {
                // not up yet
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private JsonNode getJson(String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(apiBase + path))
                    .timeout(Duration.ofSeconds(30)).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new IllegalStateException("GET " + path + " -> " + res.statusCode() + " " + res.body());
            }
            return MAPPER.readTree(res.body());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("GET " + path + " failed", e);
        }
    }

    private JsonNode postJson(String path, String payload) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(apiBase + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                throw new IllegalStateException("POST " + path + " -> " + res.statusCode() + " " + res.body());
            }
            return MAPPER.readTree(res.body());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("POST " + path + " failed", e);
        }
    }

    static BigDecimal ada(long lovelace) {
        return BigDecimal.valueOf(lovelace).movePointLeft(6);
    }
}
