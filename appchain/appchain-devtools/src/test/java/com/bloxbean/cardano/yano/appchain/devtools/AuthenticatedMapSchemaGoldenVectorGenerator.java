package com.bloxbean.cardano.yano.appchain.devtools;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Reviewed cross-language vectors for cddl-yano-subset-v1 and schema IR v1. */
public final class AuthenticatedMapSchemaGoldenVectorGenerator {
    private AuthenticatedMapSchemaGoldenVectorGenerator() {
    }

    public static void main(String[] args) {
        vectors().forEach((key, value) -> System.out.println(key + "=" + value));
    }

    static Map<String, String> vectors() {
        Map<String, String> vectors = new TreeMap<>();
        vectors.put("schema.version", "1");
        vectors.put("authoring.language", "cddl-yano-subset-v1");
        vectors.put("ir.catalog", "yano-cbor-schema-ir-v1");

        add(vectors, "product", "root", """
                root = {
                  id: short-text,
                  count: 1..10,
                  ? ok: bool,
                  state: "active" / "held"
                }
                short-text = tstr .size (1..8)
                """,
                List.of(
                        "a3626964616165636f756e74056573746174656468656c64",
                        "a46269646161626f6bf565636f756e740a65737461746566616374697665"),
                List.of(
                        "a2626964616165636f756e7405",
                        "a36269646065636f756e74056573746174656468656c64",
                        "a3626964616165636f756e740b6573746174656468656c64"));

        add(vectors, "sequence", "root", """
                root = [version, 1*3 label, ? bool]
                version = 1
                label = tstr .size (1..4)
                """,
                List.of("82016161", "840161616162f5"),
                List.of("8101", "85016161616261636164", "8261616162"));

        add(vectors, "identifier", "record", """
                record = {
                  id: bstr .size 4,
                  score: int .ge -5 .lt 10,
                  kind: "a" / "bb",
                  ? enabled: true
                }
                """,
                List.of(
                        "a36269644401020304646b696e6461616573636f726524",
                        "a462696444ffffffff646b696e646262626573636f72650967656e61626c6564f5"),
                List.of(
                        "a362696443010203646b696e6461616573636f726500",
                        "a36269644401020304646b696e6461616573636f72650a",
                        "a46269644401020304646b696e6461616573636f72650067656e61626c6564f4"));
        return Map.copyOf(vectors);
    }

    private static void add(
            Map<String, String> vectors,
            String name,
            String root,
            String source,
            List<String> accepted,
            List<String> rejected
    ) {
        AuthenticatedMapCddlCompiler.Compilation compilation =
                AuthenticatedMapCddlCompiler.compile(source, root);
        String prefix = "schema." + name + ".";
        vectors.put(prefix + "root", root);
        vectors.put(prefix + "source.hex", hex(source.getBytes(StandardCharsets.UTF_8)));
        vectors.put(prefix + "ir.hex", hex(compilation.definition()));
        vectors.put(prefix + "accept.count", Integer.toString(accepted.size()));
        for (int index = 0; index < accepted.size(); index++) {
            vectors.put(prefix + "accept." + index, accepted.get(index));
        }
        vectors.put(prefix + "reject.count", Integer.toString(rejected.size()));
        for (int index = 0; index < rejected.size(); index++) {
            vectors.put(prefix + "reject." + index, rejected.get(index));
        }
    }

    private static String hex(byte[] value) {
        return HexFormat.of().formatHex(value);
    }
}
