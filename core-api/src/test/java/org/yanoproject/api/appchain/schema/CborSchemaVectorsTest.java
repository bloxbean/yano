package org.yanoproject.api.appchain.schema;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.HexFormat;
import java.util.Properties;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V4 (ADR app-layer/039): Yano X's authenticated-map schema vectors ({@code schema-vectors.properties},
 * copied unchanged from {@code stdlib-contracts}) pass against the core evaluator.
 */
class CborSchemaVectorsTest {

    @Test
    void yanoXSchemaVectorsPassUnchanged() throws IOException {
        Properties vectors = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/schema/v1/schema-vectors.properties")) {
            vectors.load(in);
        }
        TreeSet<String> schemas = new TreeSet<>();
        for (String key : vectors.stringPropertyNames()) {
            if (key.startsWith("schema.") && key.endsWith(".ir.hex")) {
                schemas.add(key.substring("schema.".length(), key.length() - ".ir.hex".length()));
            }
        }
        assertThat(schemas).isNotEmpty();
        int values = 0;
        for (String name : schemas) {
            byte[] ir = hex(vectors.getProperty("schema." + name + ".ir.hex"));
            CborSchema.Schema schema = CborSchema.decode(ir);
            assertThat(schema.definition()).as(name).isEqualTo(ir);
            values += check(vectors, schema, name, "accept", true);
            values += check(vectors, schema, name, "reject", false);
        }
        assertThat(values).isGreaterThanOrEqualTo(2 * schemas.size());
    }

    private static int check(Properties vectors, CborSchema.Schema schema, String name, String kind,
                             boolean expected) {
        int count = Integer.parseInt(vectors.getProperty("schema." + name + "." + kind + ".count", "0"));
        for (int index = 0; index < count; index++) {
            byte[] value = hex(vectors.getProperty("schema." + name + "." + kind + "." + index));
            assertThat(schema.accepts(value)).as(name + " " + kind + " " + index).isEqualTo(expected);
        }
        return count;
    }

    private static byte[] hex(String value) {
        return HexFormat.of().parseHex(value);
    }
}
