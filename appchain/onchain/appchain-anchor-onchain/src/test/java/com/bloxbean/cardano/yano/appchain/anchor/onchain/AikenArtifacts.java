package com.bloxbean.cardano.yano.appchain.anchor.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.flat.UplcFlatDecoder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads the checked-in Aiken artifacts (double-CBOR cborHex envelopes under
 * {@code ../aiken/appchain-anchor/artifacts/}) as julc {@link Program}s so
 * the conformance vectors run identically against both implementations.
 */
final class AikenArtifacts {

    static final Path ARTIFACT_DIR = Path.of("../aiken/appchain-anchor/artifacts");

    private static final Pattern CBOR_HEX = Pattern.compile("\"cborHex\"\\s*:\\s*\"([0-9a-fA-F]+)\"");

    private AikenArtifacts() {
    }

    static Program load(String artifactFile, PlutusData... params) {
        try {
            String json = Files.readString(ARTIFACT_DIR.resolve(artifactFile), StandardCharsets.UTF_8);
            Matcher matcher = CBOR_HEX.matcher(json);
            if (!matcher.find())
                throw new IllegalStateException("No cborHex in " + artifactFile);
            byte[] doubleWrapped = HexFormat.of().parseHex(matcher.group(1));
            byte[] flat = cborUnwrapBytes(cborUnwrapBytes(doubleWrapped));
            return UplcFlatDecoder.decodeProgram(flat).applyParams(params);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load Aiken artifact " + artifactFile, e);
        }
    }

    /** Strip one definite-length CBOR byte-string wrapper (major type 2). */
    private static byte[] cborUnwrapBytes(byte[] cbor) {
        int first = cbor[0] & 0xFF;
        if (first >> 5 != 2)
            throw new IllegalStateException("Not a CBOR byte string (major type "
                    + (first >> 5) + ")");
        int argument = first & 0x1F;
        int headerLen;
        long length;
        if (argument < 24) {
            headerLen = 1;
            length = argument;
        } else if (argument == 24) {
            headerLen = 2;
            length = cbor[1] & 0xFF;
        } else if (argument == 25) {
            headerLen = 3;
            length = ((cbor[1] & 0xFF) << 8) | (cbor[2] & 0xFF);
        } else if (argument == 26) {
            headerLen = 5;
            length = ((long) (cbor[1] & 0xFF) << 24) | ((cbor[2] & 0xFF) << 16)
                    | ((cbor[3] & 0xFF) << 8) | (cbor[4] & 0xFF);
        } else {
            throw new IllegalStateException("Unsupported CBOR byte-string length encoding");
        }
        byte[] payload = new byte[(int) length];
        System.arraycopy(cbor, headerLen, payload, 0, (int) length);
        return payload;
    }
}
