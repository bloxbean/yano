package com.bloxbean.cardano.yano.api.appchain.effects;

import java.util.Objects;

/**
 * One executable effect handed to an {@link AppEffectExecutor} (ADR
 * app-layer/010 F5): the consensus-derived emission record plus its
 * precomputed idempotency key.
 *
 * @param record the emission record (type, payload, scope, gate, expiry)
 * @param idHash the universal idempotency key — {@code EffectId.hash()},
 *               identical across retries, restarts and executor failover;
 *               pass it to the external system on EVERY attempt
 */
public record PendingEffect(EffectRecord record, byte[] idHash) {

    public PendingEffect {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(idHash, "idHash");
    }

    public static PendingEffect of(EffectRecord record) {
        return new PendingEffect(record, record.effectId().hash());
    }

    public EffectId effectId() {
        return record.effectId();
    }

    public String type() {
        return record.type();
    }

    public String scope() {
        return record.scope();
    }

    public byte[] payload() {
        return record.payload();
    }

    public long expiryHeight() {
        return record.expiryHeight();
    }
}
