package com.bloxbean.cardano.yano.appchain.effects.cardano;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.api.appchain.codec.internal.CborStructurePreflight;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * {@code cardano.payment} effect payload: a CBOR map (canonical, what the
 * generic approvals on-approved flow can emit) or a minimal JSON object for
 * hand-authored
 * effects — {@code {"to": bech32, "lovelace": uint, "memo"?: tstr}}.
 * Returns null on any malformation (the executor fails the effect
 * definitively — a broken payload can never succeed).
 */
record PaymentCommand(String to, long lovelace, String memo) {
    private static final CborStructurePreflight.Limits CBOR_LIMITS =
            new CborStructurePreflight.Limits(4096, 4, 16, 8, 2048);

    static PaymentCommand decode(byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > 4096) {
            return null;
        }
        PaymentCommand cbor = decodeCbor(payload);
        if (cbor != null) {
            return cbor;
        }
        return decodeJson(new String(payload, StandardCharsets.UTF_8));
    }

    private static PaymentCommand decodeCbor(byte[] payload) {
        if (!CborStructurePreflight.accepts(payload, CBOR_LIMITS)) {
            return null;
        }
        try {
            List<DataItem> items = new CborDecoder(new ByteArrayInputStream(payload)).decode();
            if (items.isEmpty() || !(items.get(0) instanceof co.nstant.in.cbor.model.Map map)) {
                return null;
            }
            String to = string(map, "to");
            Long lovelace = uint(map, "lovelace");
            if (to == null || to.isBlank() || lovelace == null || lovelace <= 0) {
                return null;
            }
            return new PaymentCommand(to, lovelace, string(map, "memo"));
        } catch (Exception e) {
            return null;
        }
    }

    /** Deliberately tiny JSON reader — flat string/number fields only, no nesting. */
    private static PaymentCommand decodeJson(String text) {
        try {
            String trimmed = text.trim();
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
                return null;
            }
            String to = jsonString(trimmed, "to");
            Long lovelace = jsonNumber(trimmed, "lovelace");
            if (to == null || to.isBlank() || lovelace == null || lovelace <= 0) {
                return null;
            }
            return new PaymentCommand(to, lovelace, jsonString(trimmed, "memo"));
        } catch (Exception e) {
            return null;
        }
    }

    private static String string(co.nstant.in.cbor.model.Map map, String key) {
        DataItem value = map.get(new UnicodeString(key));
        return value instanceof UnicodeString text ? text.getString() : null;
    }

    private static Long uint(co.nstant.in.cbor.model.Map map, String key) {
        DataItem value = map.get(new UnicodeString(key));
        return value instanceof UnsignedInteger number ? number.getValue().longValue() : null;
    }

    private static String jsonString(String json, String key) {
        var matcher = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(json);
        return matcher.find() ? matcher.group(1).replace("\\\"", "\"") : null;
    }

    private static Long jsonNumber(String json, String key) {
        var matcher = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*(\\d+)")
                .matcher(json);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : null;
    }
}
