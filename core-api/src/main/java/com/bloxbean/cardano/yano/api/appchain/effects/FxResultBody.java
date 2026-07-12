package com.bloxbean.cardano.yano.api.appchain.effects;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

import java.util.List;

/**
 * Wire body of a {@code ~fx/result} message (ADR app-layer/010 F8): a
 * member-attested terminal outcome for one effect, sequenced like any chain
 * message and incorporated exactly once by the framework interpreter (first
 * result wins). The effect is addressed by chain position; the chain id is
 * bound by the (verified) envelope.
 * <p>
 * CBOR: {@code [v, height, ordinal, outcome, external-ref, detail-hash]}.
 * {@code outcome} ∈ {1 CONFIRMED, 2 FAILED, 3 CANCELLED} — EXPIRED is never a
 * message outcome (the deterministic sweep generates it). Canonical handles
 * only in {@code external-ref} (≤ {@link #MAX_EXTERNAL_REF_BYTES}); raw
 * response bodies never go on chain.
 *
 * @param version     body version (= 1)
 * @param height      emission height of the effect being closed
 * @param ordinal     emission ordinal of the effect being closed
 * @param outcome     terminal outcome (never EXPIRED)
 * @param externalRef canonical external handle (txHash, CID, resource id) or
 *                    failure reason code
 * @param detailHash  optional 32-byte commitment to an off-chain detail doc
 */
public record FxResultBody(int version,
                           long height,
                           int ordinal,
                           EffectOutcome outcome,
                           byte[] externalRef,
                           byte[] detailHash) {

    public static final String TOPIC = "~fx/result";
    public static final int BODY_VERSION = 1;
    public static final int MAX_EXTERNAL_REF_BYTES = 128;

    public FxResultBody {
        if (outcome == EffectOutcome.EXPIRED) {
            throw new IllegalArgumentException("EXPIRED is sweep-generated, never a message outcome");
        }
        externalRef = externalRef != null ? externalRef : new byte[0];
        if (externalRef.length > MAX_EXTERNAL_REF_BYTES) {
            throw new IllegalArgumentException("externalRef exceeds " + MAX_EXTERNAL_REF_BYTES + " bytes");
        }
        detailHash = detailHash != null && detailHash.length > 0 ? detailHash : null;
        if (detailHash != null && detailHash.length != 32) {
            throw new IllegalArgumentException("detailHash must be 32 bytes when present");
        }
    }

    public byte[] encode() {
        Array arr = new Array();
        arr.add(new UnsignedInteger(version));
        arr.add(new UnsignedInteger(height));
        arr.add(new UnsignedInteger(ordinal));
        arr.add(new UnsignedInteger(outcome.code()));
        arr.add(new ByteString(externalRef));
        arr.add(new ByteString(detailHash != null ? detailHash : new byte[0]));
        return CborSerializationUtil.serialize(arr);
    }

    /**
     * Strict decode for the interpreter — throws on ANY malformation; the
     * caller turns that into a deterministic audit no-op (fail-closed,
     * never a stall).
     */
    public static FxResultBody decode(byte[] bytes) {
        Array arr = (Array) CborSerializationUtil.deserializeOne(bytes);
        List<DataItem> items = arr.getDataItems();
        if (items.size() < 6) {
            throw new IllegalArgumentException("~fx/result body must have 6 fields");
        }
        int version = ((UnsignedInteger) items.get(0)).getValue().intValue();
        if (version != BODY_VERSION) {
            throw new IllegalArgumentException("Unsupported ~fx/result version: " + version);
        }
        byte[] detail = ((ByteString) items.get(5)).getBytes();
        return new FxResultBody(
                version,
                ((UnsignedInteger) items.get(1)).getValue().longValue(),
                ((UnsignedInteger) items.get(2)).getValue().intValue(),
                EffectOutcome.fromCode(((UnsignedInteger) items.get(3)).getValue().intValue()),
                ((ByteString) items.get(4)).getBytes(),
                detail.length > 0 ? detail : null);
    }
}
