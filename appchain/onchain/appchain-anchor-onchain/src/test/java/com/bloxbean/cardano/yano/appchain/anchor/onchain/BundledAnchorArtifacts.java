package com.bloxbean.cardano.yano.appchain.anchor.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.flat.UplcFlatDecoder;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loads the checked-in release anchor artifacts as executable UPLC programs. */
final class BundledAnchorArtifacts {

    private static final Pattern CBOR_HEX = Pattern.compile(
            "\"cborHex\"\\s*:\\s*\"([0-9a-fA-F]+)\"");

    private BundledAnchorArtifacts() {
    }

    static Program load(String resource, PlutusData... params) {
        try (InputStream in = BundledAnchorArtifacts.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Bundled artifact not on classpath: " + resource);
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = CBOR_HEX.matcher(json);
            if (!matcher.find()) {
                throw new IllegalStateException("No cborHex in " + resource);
            }
            byte[] doubleWrapped = HexFormat.of().parseHex(matcher.group(1));
            byte[] flat = cborUnwrapBytes(cborUnwrapBytes(doubleWrapped));
            return UplcFlatDecoder.decodeProgram(flat).applyParams(params);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load bundled artifact " + resource, e);
        }
    }

    /** Strip one definite-length CBOR byte-string wrapper (major type 2). */
    private static byte[] cborUnwrapBytes(byte[] cbor) {
        int first = cbor[0] & 0xFF;
        if (first >> 5 != 2) {
            throw new IllegalStateException("Not a CBOR byte string");
        }
        int argument = first & 0x1F;
        int headerLength;
        long length;
        if (argument < 24) {
            headerLength = 1;
            length = argument;
        } else if (argument == 24) {
            headerLength = 2;
            length = cbor[1] & 0xFF;
        } else if (argument == 25) {
            headerLength = 3;
            length = ((cbor[1] & 0xFF) << 8) | (cbor[2] & 0xFF);
        } else if (argument == 26) {
            headerLength = 5;
            length = ((long) (cbor[1] & 0xFF) << 24) | ((cbor[2] & 0xFF) << 16)
                    | ((cbor[3] & 0xFF) << 8) | (cbor[4] & 0xFF);
        } else {
            throw new IllegalStateException("Unsupported CBOR byte-string length encoding");
        }
        byte[] payload = new byte[Math.toIntExact(length)];
        System.arraycopy(cbor, headerLength, payload, 0, payload.length);
        return payload;
    }
}
