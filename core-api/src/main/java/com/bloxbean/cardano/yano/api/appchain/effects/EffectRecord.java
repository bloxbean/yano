package com.bloxbean.cardano.yano.api.appchain.effects;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.codec.internal.CborStructurePreflight;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The canonical, consensus-derived form of one emitted effect (ADR
 * app-layer/010 F2): a pure function of {@code (block, committed state)},
 * byte-identical on every node, hashed into the block's effectsRoot. Stored in
 * the {@code app_fx_records} column family atomically with the block; NOT an
 * MPF trie leaf (ADR-010 §6).
 * <p>
 * Canonical CBOR (definite-length array, fixed field order, see
 * cddl/appchain/fx-record.cddl):
 * {@code [v, chain-id, height, ordinal, type, payload, scope, gate, result,
 * expiry-height, source-message-id]}.
 */
public record EffectRecord(int version,
                           String chainId,
                           long height,
                           int ordinal,
                           String type,
                           byte[] payload,
                           String scope,
                           FinalityGate gate,
                           ResultPolicy result,
                           long expiryHeight,
                           byte[] sourceMessageId) {

    public static final int RECORD_VERSION = 1;
    private static final CborStructurePreflight.Limits RECORD_CBOR_LIMITS =
            new CborStructurePreflight.Limits(
                    Math.toIntExact(AppChainConfig.MAX_BLOCK_BYTES),
                    4, 32, 16, AppChainConfig.MAX_MESSAGE_BYTES);

    public EffectRecord {
        Objects.requireNonNull(chainId, "chainId");
        Objects.requireNonNull(type, "type");
        payload = payload != null ? payload : new byte[0];
        scope = scope != null ? scope : "";
        Objects.requireNonNull(gate, "gate");
        Objects.requireNonNull(result, "result");
        if (gate == FinalityGate.CHAIN_DEFAULT) {
            throw new IllegalArgumentException("records carry a resolved gate, never CHAIN_DEFAULT");
        }
        sourceMessageId = sourceMessageId != null && sourceMessageId.length > 0 ? sourceMessageId : null;
    }

    public EffectId effectId() {
        return new EffectId(chainId, height, ordinal);
    }

    /** Canonical CBOR bytes — the input to {@link #effectHash()} and the stored form. */
    public byte[] encode() {
        Array arr = new Array();
        arr.add(new UnsignedInteger(version));
        arr.add(new UnicodeString(chainId));
        arr.add(new UnsignedInteger(height));
        arr.add(new UnsignedInteger(ordinal));
        arr.add(new UnicodeString(type));
        arr.add(new ByteString(payload));
        arr.add(new UnicodeString(scope));
        arr.add(new UnsignedInteger(gate.code()));
        arr.add(new UnsignedInteger(result.code()));
        arr.add(new UnsignedInteger(expiryHeight));
        arr.add(new ByteString(sourceMessageId != null ? sourceMessageId : new byte[0]));
        return CborSerializationUtil.serialize(arr);
    }

    /** blake2b-256 over the canonical bytes — the leaf of the block's effectsRoot. */
    public byte[] effectHash() {
        return Blake2bUtil.blake2bHash256(encode());
    }

    public static EffectRecord decode(byte[] bytes) {
        if (!CborStructurePreflight.accepts(bytes, RECORD_CBOR_LIMITS)) {
            throw invalid();
        }
        try {
            Array arr = (Array) CborSerializationUtil.deserializeOne(bytes);
            List<DataItem> items = arr.getDataItems();
            if (items.size() != 11) {
                throw invalid();
            }
            int version = ((UnsignedInteger) items.get(0)).getValue().intValueExact();
            if (version != RECORD_VERSION) {
                throw invalid();
            }
            byte[] source = ((ByteString) items.get(10)).getBytes();
            EffectRecord decoded = new EffectRecord(
                    version,
                    ((UnicodeString) items.get(1)).getString(),
                    ((UnsignedInteger) items.get(2)).getValue().longValueExact(),
                    ((UnsignedInteger) items.get(3)).getValue().intValueExact(),
                    ((UnicodeString) items.get(4)).getString(),
                    ((ByteString) items.get(5)).getBytes(),
                    ((UnicodeString) items.get(6)).getString(),
                    FinalityGate.fromCode(((UnsignedInteger) items.get(7)).getValue().intValueExact()),
                    ResultPolicy.fromCode(((UnsignedInteger) items.get(8)).getValue().intValueExact()),
                    ((UnsignedInteger) items.get(9)).getValue().longValueExact(),
                    source.length > 0 ? source : null);
            if (!Arrays.equals(bytes, decoded.encode())) {
                throw invalid();
            }
            return decoded;
        } catch (RuntimeException malformed) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid bounded canonical effect record");
    }
}
