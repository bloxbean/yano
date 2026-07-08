package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.sink.AppBlockJson;
import com.bloxbean.cardano.yano.api.appchain.sink.FinalizedStreamSink;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
        return "webhook:" + url;
    }

    @Override
    public boolean deliver(AppBlock block) {
        try {
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
            log.warn("Webhook {} rejected block {}: HTTP {}", url, block.height(), response.statusCode());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("Webhook {} delivery of block {} failed: {}", url, block.height(), e.toString());
            return false;
        }
    }
}
