package com.bloxbean.cardano.yano.api.appchain.effects;

import java.util.Objects;

/**
 * A state machine's declarative request for an external action (ADR
 * app-layer/010 F1). Pure data — emitting an intent performs nothing; the
 * Effect Runtime executes it after finality, outside deterministic execution.
 *
 * @param type            executor routing key, e.g. {@code cardano.payment},
 *                        {@code webhook.post}. Exact-match against
 *                        {@code AppEffectExecutor.supports}
 * @param payload         opaque command for the executor (canonical CBOR
 *                        recommended); bounded by {@code effects.max-payload-bytes}.
 *                        Never put secrets or raw PII here — records are
 *                        replicated to every member and committed via effectsRoot
 * @param scope           app-level idempotency scope (e.g. {@code approvals/rel-42});
 *                        also the executor's per-key ordering handle. Must not
 *                        embed sensitive data
 * @param gate            when execution becomes eligible (ADR-010 F7)
 * @param result          whether the outcome re-enters the chain (ADR-010 F8)
 * @param expiryBlocks    for {@link ResultPolicy#CHAIN}: deterministic EXPIRED
 *                        after this many blocks with no incorporated result.
 *                        0 = chain default. CHAIN effects ALWAYS expire — the
 *                        kernel defaults 0 to min(max-expiry-blocks,
 *                        result-window-blocks) and rejects values beyond the
 *                        result window, so every CHAIN effect provably closes
 *                        while results are still incorporable (ADR-010 F9)
 * @param sourceMessageId optional provenance link to the triggering message
 */
public record EffectIntent(String type,
                           byte[] payload,
                           String scope,
                           FinalityGate gate,
                           ResultPolicy result,
                           long expiryBlocks,
                           byte[] sourceMessageId) {

    public EffectIntent {
        Objects.requireNonNull(type, "type");
        if (type.isBlank()) {
            throw new IllegalArgumentException("effect type must not be blank");
        }
        if (type.startsWith("~")) {
            throw new IllegalArgumentException("effect type must not start with '~' (reserved)");
        }
        payload = payload != null ? payload : new byte[0];
        scope = scope != null ? scope : "";
        gate = gate != null ? gate : FinalityGate.CHAIN_DEFAULT;
        result = result != null ? result : ResultPolicy.NONE;
        if (expiryBlocks < 0) {
            throw new IllegalArgumentException("expiryBlocks must be >= 0");
        }
        if (expiryBlocks > 0 && result == ResultPolicy.NONE) {
            throw new IllegalArgumentException(
                    "expiryBlocks applies only to ResultPolicy.CHAIN effects (ADR-010 F9)");
        }
        sourceMessageId = sourceMessageId != null && sourceMessageId.length > 0 ? sourceMessageId : null;
    }

    public static Builder of(String type, byte[] payload) {
        return new Builder(type, payload);
    }

    public static final class Builder {
        private final String type;
        private final byte[] payload;
        private String scope = "";
        private FinalityGate gate = FinalityGate.CHAIN_DEFAULT;
        private ResultPolicy result = ResultPolicy.NONE;
        private long expiryBlocks;
        private byte[] sourceMessageId;

        private Builder(String type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }

        public Builder scope(String value) { this.scope = value; return this; }
        public Builder gate(FinalityGate value) { this.gate = value; return this; }
        public Builder result(ResultPolicy value) { this.result = value; return this; }
        public Builder expiryBlocks(long value) { this.expiryBlocks = value; return this; }
        public Builder sourceMessageId(byte[] value) { this.sourceMessageId = value; return this; }

        public EffectIntent build() {
            return new EffectIntent(type, payload, scope, gate, result, expiryBlocks, sourceMessageId);
        }
    }
}
