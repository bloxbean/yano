package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Built-in webhook sink (ADR app-layer/006 E3.1): delivers finalized blocks to
 * an HTTP endpoint as JSON, strictly in height order, at-least-once. The
 * per-sink cursor is persisted in the app ledger meta CF, so delivery resumes
 * where it left off across restarts; a failed POST simply halts the cursor
 * until the next tick.
 */
final class WebhookStreamSink {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final String url;
    private final String cursorKey;
    private final AppLedgerStore ledger;
    private final String chainId;
    private final HttpClient httpClient;
    private final Logger log;

    private volatile long deliveredCount;
    private volatile String lastError;

    WebhookStreamSink(String url, String chainId, AppLedgerStore ledger, Logger log) {
        this.url = url;
        this.chainId = chainId;
        this.ledger = ledger;
        this.log = log;
        // Stable cursor identity per sink URL
        this.cursorKey = "webhook_cursor_" + HexUtil.encodeHexString(
                Blake2bUtil.blake2bHash224(url.getBytes(StandardCharsets.UTF_8)));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        // First registration starts at the current tip: webhooks deliver NEW
        // blocks; history is available via catch-up/REST if a consumer needs it.
        if (ledger.metaLong(cursorKey, -1L) < 0) {
            ledger.metaPutLong(cursorKey, ledger.tipHeight());
        }
    }

    String url() {
        return url;
    }

    long cursor() {
        return ledger.metaLong(cursorKey, 0L);
    }

    long deliveredCount() {
        return deliveredCount;
    }

    String lastError() {
        return lastError;
    }

    /** Deliver pending blocks up to the tip; stops at the first failure. */
    void deliveryTick() {
        long tip = ledger.tipHeight();
        long cursor = cursor();
        while (cursor < tip) {
            long next = cursor + 1;
            AppBlock block = ledger.block(next).orElse(null);
            if (block == null) {
                return;
            }
            if (!post(block)) {
                return; // retry from the same cursor next tick (at-least-once)
            }
            ledger.metaPutLong(cursorKey, next);
            cursor = next;
            deliveredCount++;
        }
    }

    private boolean post(AppBlock block) {
        try {
            String payload = toJson(block);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("X-App-Chain-Id", chainId)
                    .header("X-App-Chain-Height", Long.toString(block.height()))
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                lastError = null;
                return true;
            }
            lastError = "HTTP " + response.statusCode();
            log.warn("Webhook {} rejected block {}: HTTP {}", url, block.height(), response.statusCode());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            lastError = e.toString();
            log.warn("Webhook {} delivery of block {} failed: {}", url, block.height(), e.toString());
            return false;
        }
    }

    private String toJson(AppBlock block) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"chainId\":\"").append(escape(block.chainId()))
                .append("\",\"height\":").append(block.height())
                .append(",\"blockHash\":\"")
                .append(HexUtil.encodeHexString(AppBlockCodec.blockHash(block)))
                .append("\",\"stateRoot\":\"").append(HexUtil.encodeHexString(block.stateRoot()))
                .append("\",\"timestamp\":").append(block.timestamp())
                .append(",\"messages\":[");
        List<AppMessage> messages = block.messages();
        for (int i = 0; i < messages.size(); i++) {
            AppMessage message = messages.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"messageId\":\"").append(message.getMessageIdHex())
                    .append("\",\"topic\":\"").append(escape(message.getTopic()))
                    .append("\",\"sender\":\"").append(HexUtil.encodeHexString(message.getSender()))
                    .append("\",\"senderSeq\":").append(message.getSenderSeq())
                    .append(",\"bodyHex\":\"").append(HexUtil.encodeHexString(message.getBody()))
                    .append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
