package com.bloxbean.cardano.yano.appchain.devtools;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticatedMapSchemaGoldenVectorsTest {
    private static final String RESOURCE =
            "/META-INF/yano/contracts/authenticated-map/v1/schema-vectors.properties";

    @Test
    void checkedInVectorsMatchTheJavaCompilerAndEvaluator() throws Exception {
        Map<String, String> expected = AuthenticatedMapSchemaGoldenVectorGenerator.vectors();
        Map<String, String> actual = properties();

        assertThat(actual).isEqualTo(expected);
        for (String schema : java.util.List.of("product", "sequence", "identifier")) {
            String prefix = "schema." + schema + ".";
            String source = new String(hex(actual.get(prefix + "source.hex")),
                    StandardCharsets.UTF_8);
            AuthenticatedMapCddlCompiler.Compilation compilation =
                    AuthenticatedMapCddlCompiler.compile(source, actual.get(prefix + "root"));
            assertThat(compilation.definition()).isEqualTo(hex(actual.get(prefix + "ir.hex")));
            int accepts = Integer.parseInt(actual.get(prefix + "accept.count"));
            for (int index = 0; index < accepts; index++) {
                assertThat(compilation.schema().accepts(
                        hex(actual.get(prefix + "accept." + index))))
                        .as("%s accepted vector %d", schema, index)
                        .isTrue();
            }
            int rejects = Integer.parseInt(actual.get(prefix + "reject.count"));
            for (int index = 0; index < rejects; index++) {
                assertThat(compilation.schema().accepts(
                        hex(actual.get(prefix + "reject." + index))))
                        .as("%s rejected vector %d", schema, index)
                        .isFalse();
            }
        }
    }

    private static Map<String, String> properties() throws Exception {
        Properties properties = new Properties();
        try (InputStream input = AuthenticatedMapSchemaGoldenVectorsTest.class
                .getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("missing " + RESOURCE);
            properties.load(input);
        }
        Map<String, String> result = new TreeMap<>();
        properties.forEach((key, value) -> result.put(key.toString(), value.toString()));
        return Map.copyOf(result);
    }

    private static byte[] hex(String value) {
        return HexFormat.of().parseHex(value);
    }
}
