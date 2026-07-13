package com.bloxbean.cardano.yano.appchain.anchor.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.flat.UplcFlatDecoder;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads the CHECKED-IN julc artifacts (src/main/resources/META-INF/plutus/)
 * as julc {@link Program}s — the same bytes the runtime ships and the same
 * loading path as {@link AikenArtifacts}. The conformance suites run against
 * these artifacts so they are environment-independent: compiling from source
 * on CI would tie the results to whatever julc version CI resolves (found
 * 2026-07-12: the RELEASED julc 0.1.0-pre14 miscompiles AnchorValidator —
 * fixed post-release in julc main; see JulcSourceCompile* drift tests).
 */
final class BundledJulcArtifacts {

    private static final Pattern CBOR_HEX = Pattern.compile("\"cborHex\"\\s*:\\s*\"([0-9a-fA-F]+)\"");

    private BundledJulcArtifacts() {
    }

    static Program load(String resource, PlutusData... params) {
        try (InputStream in = BundledJulcArtifacts.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null)
                throw new IllegalStateException("Bundled artifact not on classpath: " + resource);
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = CBOR_HEX.matcher(json);
            if (!matcher.find())
                throw new IllegalStateException("No cborHex in " + resource);
            byte[] doubleWrapped = HexFormat.of().parseHex(matcher.group(1));
            byte[] flat = AikenArtifacts.cborUnwrapBytes(AikenArtifacts.cborUnwrapBytes(doubleWrapped));
            return UplcFlatDecoder.decodeProgram(flat).applyParams(params);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load bundled artifact " + resource, e);
        }
    }
}
