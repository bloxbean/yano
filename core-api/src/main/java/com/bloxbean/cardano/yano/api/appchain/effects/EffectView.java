package com.bloxbean.cardano.yano.api.appchain.effects;

import com.bloxbean.cardano.yaci.core.util.HexUtil;

/**
 * Read-model of one emitted effect for the query surface (REST / gateway,
 * ADR app-layer/010 F12). Consensus-tier fields only in FX-M1; the runtime
 * status view joins in FX-M2.
 */
public record EffectView(String chainId,
                         long height,
                         int ordinal,
                         String type,
                         String scope,
                         String gate,
                         String resultPolicy,
                         long expiryHeight,
                         String payloadHex,
                         String effectHashHex,
                         String effectIdHashHex) {

    public static EffectView of(EffectRecord record) {
        return new EffectView(
                record.chainId(),
                record.height(),
                record.ordinal(),
                record.type(),
                record.scope(),
                record.gate().name(),
                record.result().name(),
                record.expiryHeight(),
                HexUtil.encodeHexString(record.payload()),
                HexUtil.encodeHexString(record.effectHash()),
                record.effectId().hashHex());
    }
}
