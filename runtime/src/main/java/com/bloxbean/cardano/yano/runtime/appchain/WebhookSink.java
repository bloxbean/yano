package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.sink.AppBlockJson;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSink;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Built-in webhook {@link FinalizedStreamSink} (ADR app-layer/006 E3.1/E3.2):
 * POSTs a finalized block's JSON to an HTTP endpoint. Ordering/cursor/retry are
 * handled by {@link SinkRunner}; this just performs one write.
 */
final class WebhookSink implements FinalizedStreamSink {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final String url;
    private final String chainId;
    private final HttpClient httpClient;
    private final Logger log;

    WebhookSink(String url, String chainId, Logger log) {
        this.url = url;
        this.chainId = chainId;
        this.log = log;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public String id() {
        return "webhook:" + HexUtil.encodeHexString(
                Blake2bUtil.blake2bHash224(url.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Cursor key used by the pre-Wave-2 WebhookStreamSink, so an in-place
     * upgrade resumes delivery instead of skipping undelivered blocks.
     */
    @Override
    public String legacyCursorKey() {
        return "webhook_cursor_" + HexUtil.encodeHexString(
                Blake2bUtil.blake2bHash224(url.getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public boolean deliver(AppBlock block) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("X-App-Chain-Id", chainId)
                .header("X-App-Chain-Height", Long.toString(block.height()))
                .POST(HttpRequest.BodyPublishers.ofString(AppBlockJson.toJson(block)))
                .build();
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return true;
        }
        // Throw so SinkRunner records the HTTP status as lastError and retries.
        throw new IOException("HTTP " + response.statusCode());
    }
}
