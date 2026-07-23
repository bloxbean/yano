package com.bloxbean.cardano.yano.api.appchain.effects;

import java.util.Objects;

/**
 * A consensus-incorporated terminal outcome handed to
 * {@code AppStateMachine.onEffectResult} (ADR app-layer/010 F8/F9) — either a
 * member-attested {@code ~fx/result} message the framework interpreter
 * accepted, or a deterministic EXPIRED transition from the expiry sweep.
 *
 * @param effectId     the effect this outcome closes
 * @param type         the effect's type (from the emission record)
 * @param scope        the effect's idempotency scope (from the emission record)
 * @param outcome      terminal outcome — first incorporated wins, absorbing
 * @param externalRef  canonical external handle (txHash, CID, resource id) or
 *                     failure reason code; empty for EXPIRED
 * @param detailHash   optional 32-byte commitment to an off-chain detail
 *                     document held by the executor; null when absent
 * @param resultHeight height of the block that incorporated this outcome
 */
public record EffectResult(EffectId effectId,
                           String type,
                           String scope,
                           EffectOutcome outcome,
                           byte[] externalRef,
                           byte[] detailHash,
                           long resultHeight) {

    public EffectResult {
        Objects.requireNonNull(effectId, "effectId");
        Objects.requireNonNull(type, "type");
        scope = scope != null ? scope : "";
        Objects.requireNonNull(outcome, "outcome");
        externalRef = externalRef != null ? externalRef.clone() : new byte[0];
        detailHash = detailHash != null && detailHash.length > 0 ? detailHash.clone() : null;
        if (detailHash != null && detailHash.length != 32) {
            throw new IllegalArgumentException("detailHash must be 32 bytes when present");
        }
    }

    @Override public byte[] externalRef() { return externalRef.clone(); }
    @Override public byte[] detailHash() {
        return detailHash != null ? detailHash.clone() : null;
    }

    public boolean confirmed() {
        return outcome == EffectOutcome.CONFIRMED;
    }

    /**
     * Canonical outcome envelope — the value committed under
     * {@code ~fx/done/<idHash>} (hashed) and the leaf of a per-block
     * resultsRoot (ADR-010 F8): {@code [v, chain-id, height, ordinal,
     * outcome, external-ref, detail-hash, result-height]}.
     */
    public byte[] encodeEnvelope() {
        co.nstant.in.cbor.model.Array arr = new co.nstant.in.cbor.model.Array();
        arr.add(new co.nstant.in.cbor.model.UnsignedInteger(1));
        arr.add(new co.nstant.in.cbor.model.UnicodeString(effectId.chainId()));
        arr.add(new co.nstant.in.cbor.model.UnsignedInteger(effectId.height()));
        arr.add(new co.nstant.in.cbor.model.UnsignedInteger(effectId.ordinal()));
        arr.add(new co.nstant.in.cbor.model.UnsignedInteger(outcome.code()));
        arr.add(new co.nstant.in.cbor.model.ByteString(externalRef));
        arr.add(new co.nstant.in.cbor.model.ByteString(detailHash != null ? detailHash : new byte[0]));
        arr.add(new co.nstant.in.cbor.model.UnsignedInteger(resultHeight));
        return com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.serialize(arr);
    }

    /** blake2b-256 over {@link #encodeEnvelope()}. */
    public byte[] envelopeHash() {
        return com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash256(encodeEnvelope());
    }
}
