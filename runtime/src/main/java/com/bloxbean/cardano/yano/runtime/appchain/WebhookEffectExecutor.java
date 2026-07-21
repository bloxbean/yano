package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecution;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutorOperationalSnapshot;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutorOperationsTracker;
import com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Built-in {@code webhook.post} executor (ADR app-layer/010 F5/F12): POSTs an
 * effect's payload to the configured endpoint with the deterministic
 * idempotency key in headers. The target URL comes from executor CONFIG
 * ({@code effects.executors.webhook.url}) — never from the payload unless
 * {@code allow-payload-url=true} — keeping targets/credentials out of
 * replicated records (F11).
 * <p>
 * Semantics: 2xx → CONFIRMED (external ref = Location header or HTTP status);
 * 4xx → FAILED non-retryable (the target rejected the request — retrying the
 * same bytes cannot succeed); 5xx / transport errors → retryable. Receivers
 * dedup on the {@code Idempotency-Key} header (at-least-once contract).
 */
final class WebhookEffectExecutor implements AppEffectExecutor {

    static final String TYPE = "webhook.post";

    private final String configuredUrl;
    private final boolean allowPayloadUrl;
    private final Duration requestTimeout;
    private final HttpClient httpClient;
    private final Logger log;
    private final EffectExecutorOperationsTracker operations =
            new EffectExecutorOperationsTracker();

    WebhookEffectExecutor(Map<String, String> config, Logger log) {
        this.configuredUrl = config.getOrDefault("url", "").trim();
        this.allowPayloadUrl = Boolean.parseBoolean(config.getOrDefault("allow-payload-url", "false"));
        this.requestTimeout = Duration.ofMillis(
                Long.parseLong(config.getOrDefault("timeout-ms", "10000").trim()));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.log = log;
    }

    @Override
    public String id() {
        return "webhook";
    }

    @Override
    public Set<String> effectTypes() {
        return Set.of(TYPE);
    }

    @Override
    public boolean supports(String effectType) {
        return TYPE.equals(effectType);
    }

    @Override
    public EffectExecution execute(EffectExecutionContext ctx, PendingEffect effect) throws Exception {
        return operations.observeChecked(() -> executeAttempt(ctx, effect));
    }

    @Override
    public EffectExecutorOperationalSnapshot operationalSnapshot() {
        return operations.snapshot();
    }

    private EffectExecution executeAttempt(EffectExecutionContext ctx, PendingEffect effect)
            throws Exception {
        WebhookCommand command = WebhookCommand.decode(effect.payload());
        String url = configuredUrl;
        if (command.url() != null && !command.url().isBlank()) {
            if (!allowPayloadUrl) {
                return EffectExecution.failed(
                        "payload URL rejected: effects.executors.webhook.allow-payload-url=false", false);
            }
            url = command.url();
        }
        if (url.isEmpty()) {
            return EffectExecution.failed(
                    "no target: set effects.executors.webhook.url (or allow payload URLs)", false);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .header("Content-Type", command.contentType())
                .header("Idempotency-Key", HexUtil.encodeHexString(effect.idHash()))
                .header("X-App-Chain-Id", ctx.chainId())
                .header("X-Effect-Id", effect.effectId().canonical())
                .header("X-Effect-Type", effect.type())
                .header("X-Effect-Scope", effect.scope())
                .POST(HttpRequest.BodyPublishers.ofByteArray(command.body()))
                .build();
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        int code = response.statusCode();
        if (code >= 200 && code < 300) {
            String ref = response.headers().firstValue("Location").orElse("HTTP " + code);
            return EffectExecution.confirmed(ref.getBytes(StandardCharsets.UTF_8));
        }
        if (code >= 400 && code < 500) {
            return EffectExecution.failed("HTTP " + code, false);
        }
        return EffectExecution.failed("HTTP " + code, true);
    }

    /**
     * Payload convention: either raw bytes (posted verbatim as
     * {@code application/cbor}) or a CBOR map {@code {url?, body?,
     * content-type?}} for per-effect targets/typing.
     */
    record WebhookCommand(String url, byte[] body, String contentType) {

        static WebhookCommand decode(byte[] payload) {
            try {
                var item = com.bloxbean.cardano.yaci.core.util.CborSerializationUtil
                        .deserializeOne(payload);
                if (item instanceof co.nstant.in.cbor.model.Map map) {
                    String url = stringField(map, "url");
                    byte[] body = bytesField(map, "body");
                    String contentType = stringField(map, "content-type");
                    return new WebhookCommand(url,
                            body != null ? body : payload,
                            contentType != null ? contentType : "application/cbor");
                }
            } catch (Exception ignored) {
                // not a CBOR map — post verbatim
            }
            return new WebhookCommand(null, payload, "application/cbor");
        }

        private static String stringField(co.nstant.in.cbor.model.Map map, String key) {
            var value = map.get(new co.nstant.in.cbor.model.UnicodeString(key));
            return value instanceof co.nstant.in.cbor.model.UnicodeString text ? text.getString() : null;
        }

        private static byte[] bytesField(co.nstant.in.cbor.model.Map map, String key) {
            var value = map.get(new co.nstant.in.cbor.model.UnicodeString(key));
            return value instanceof co.nstant.in.cbor.model.ByteString bytes ? bytes.getBytes() : null;
        }
    }
}
