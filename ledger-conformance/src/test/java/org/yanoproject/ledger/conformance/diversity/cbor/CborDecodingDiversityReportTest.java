package org.yanoproject.ledger.conformance.diversity.cbor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.conformance.diversity.NodeDiversity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-060 §6: the {@code cbor-decoding} report over cardano-cbor-dataset's Conway corpus. Every {@code *.input.cbor}
 * under {@code valid/} must decode and re-encode to its {@code .expected.cbor} (normalized, except for hashed rules,
 * which must reproduce the input), and every one under {@code invalid-zap-<n>/} must be rejected. Counts follow
 * Amaru's report: {@code generated_*} are the valid and {@code invalid-zap-0} samples, {@code zap_*} the mutations.
 */
class CborDecodingDiversityReportTest {

    static final String SCENARIO = "cbor-decoding";
    static final String DATASET = "cardano-cbor-dataset/dataset";
    static final String PROTOCOL_VERSION = "11.0";

    @Test
    void writesTheReport() throws IOException {
        Path dataset = NodeDiversity.requireCorpus(DATASET);
        normalizerMatchesTheDatasetVectors(dataset.resolve("normalization"));
        Path conway = dataset.resolve("conway");
        Map<String, CborRule> rules = CborRule.all();

        Map<String, int[]> counts = new TreeMap<>();
        Map<String, int[]> decoders = new TreeMap<>();
        ArrayNode failures = NodeDiversity.JSON.createArrayNode();
        for (Path sample : samples(conway)) {
            Path relative = conway.relativize(sample);
            String ruleName = relative.getName(0).toString();
            String category = relative.getName(1).toString();
            int[] c = counts.computeIfAbsent(ruleName, k -> new int[Field.values().length]);
            CborRule rule = rules.get(ruleName);
            Optional<String> failure = check(rule, category, sample);
            if (rule != null) {
                tallyDecoders(decoders.computeIfAbsent(ruleName, k -> new int[DECODER_COLUMNS]), rule,
                        category.equals("valid"), read(sample));
            }
            Field expected = category.equals("valid") ? Field.DECODED_EXPECTED
                    : category.equals("invalid-zap-0") ? Field.GENERATED_REJECTED_EXPECTED
                    : Field.ZAP_REJECTED_EXPECTED;
            c[expected.ordinal()]++;
            if (failure.isEmpty()) {
                c[expected.ordinal() + 1]++;
            } else {
                failures.addObject().put("sample", relative.toString().replace('\\', '/'))
                        .put("reason", failure.get());
            }
        }

        ObjectNode report = NodeDiversity.report(SCENARIO);
        report.put("corpus", corpus(dataset));
        report.put("protocol_version", PROTOCOL_VERSION);
        int[] totals = new int[Field.values().length];
        ObjectNode ruleNodes = NodeDiversity.JSON.createObjectNode();
        counts.forEach((rule, c) -> {
            ruleNodes.set(rule, fields(c));
            Arrays.setAll(totals, i -> totals[i] + c[i]);
        });
        report.put("successful", failures.isEmpty());
        report.set("totals", fields(totals));
        report.set("rules", ruleNodes);
        report.set("failures", failures);
        NodeDiversity.write(SCENARIO, report);

        counts.forEach((rule, c) -> System.out.printf("cbor-decoding %-24s %s%n", rule, summary(c)));
        System.out.printf("cbor-decoding %-24s %s%n", "TOTAL", summary(totals));
        printDecoders(decoders);
        assertThat(counts).isNotEmpty();
    }

    /** valid accepted, valid total, invalid rejected, invalid total, for the strict reader and then for CCL. */
    private static final int DECODER_COLUMNS = 8;

    /** Each decoder on its own: which side of the node a difference from Haskell comes from (not reported). */
    private static void tallyDecoders(int[] t, CborRule rule, boolean valid, byte[] input) {
        tally(t, 0, valid, accepts(rule.strict(), input));
        tally(t, 4, valid, accepts(rule.ccl(), input));
    }

    private static void tally(int[] t, int offset, boolean valid, boolean accepted) {
        int column = offset + (valid ? 0 : 2);
        t[column + 1]++;
        if (valid == accepted) {
            t[column]++;
        }
    }

    private static boolean accepts(CborRule.Decoder decoder, byte[] input) {
        try {
            decoder.decode(input);
            return true;
        } catch (Exception | StackOverflowError e) {
            return false;
        }
    }

    private static void printDecoders(Map<String, int[]> decoders) {
        int[] total = new int[DECODER_COLUMNS];
        System.out.println("cbor-decoding, each decoder alone (diagnostic; the report counts them together):");
        System.out.printf("cbor-decoding %-24s %-36s %s%n", "rule", "Yano strict: valid ok / invalid ok",
                "CCL: valid ok / invalid ok");
        decoders.forEach((rule, t) -> {
            System.out.printf("cbor-decoding %-24s %s%n", rule, decoderSummary(t));
            Arrays.setAll(total, i -> total[i] + t[i]);
        });
        System.out.printf("cbor-decoding %-24s %s%n", "TOTAL", decoderSummary(total));
    }

    private static String decoderSummary(int[] t) {
        return String.format("%-36s %s", String.format("%d/%d / %d/%d", t[0], t[1], t[2], t[3]),
                String.format("%d/%d / %d/%d", t[4], t[5], t[6], t[7]));
    }

    /**
     * @return empty when the sample meets its expectation, otherwise why not: a valid sample must decode and
     *         re-encode to the reference, an invalid one must be rejected
     */
    static Optional<String> check(CborRule rule, String category, Path sample) {
        if (rule == null) {
            return Optional.of("unsupported: no decoder for this rule");
        }
        byte[] input = read(sample);
        byte[] encoded;
        try {
            encoded = rule.decodeAndEncode(input);
        } catch (Exception | StackOverflowError e) {
            return category.equals("valid") ? Optional.of("decode: " + e) : Optional.empty();
        }
        if (!category.equals("valid")) {
            return Optional.of("decode: succeeded when expected to fail");
        }
        byte[] expected = read(sample.resolveSibling(sample.getFileName().toString()
                .replace(".input.cbor", ".expected.cbor")));
        try {
            byte[] actual = rule.hashed() ? encoded : CborNormalizer.normalize(encoded);
            return Arrays.equals(actual, expected) ? Optional.empty()
                    : Optional.of("encode: re-encoding differs from the reference");
        } catch (RuntimeException e) {
            return Optional.of("encode: " + e);
        }
    }

    private static void normalizerMatchesTheDatasetVectors(Path vectors) throws IOException {
        try (Stream<Path> files = Files.list(vectors)) {
            List<Path> inputs = files.filter(p -> p.toString().endsWith(".input.cbor")).sorted().toList();
            for (Path input : inputs) {
                Path expected = input.resolveSibling(input.getFileName().toString()
                        .replace(".input.cbor", ".expected.cbor"));
                assertThat(CborNormalizer.normalize(read(input))).as(input.getFileName().toString())
                        .isEqualTo(read(expected));
            }
        }
    }

    /** @return the corpus id, {@code <era>-<seed>-<samples>} from {@code corpus.json} */
    private static String corpus(Path dataset) throws IOException {
        JsonNode config = NodeDiversity.JSON.readTree(dataset.resolve("corpus.json").toFile());
        return "conway-" + config.get("seed").asText() + "-" + config.get("samples").asText();
    }

    private static List<Path> samples(Path conway) throws IOException {
        try (Stream<Path> files = Files.walk(conway)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".input.cbor")).sorted().toList();
        }
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The report's per-rule counts, each expected count followed by its actual count. */
    private enum Field {
        DECODED_EXPECTED("generated_decoded_reencoded_expected"),
        DECODED_ACTUAL("generated_decoded_reencoded_actual"),
        GENERATED_REJECTED_EXPECTED("generated_must_be_rejected_expected"),
        GENERATED_REJECTED_ACTUAL("generated_must_be_rejected_actual"),
        ZAP_REJECTED_EXPECTED("zap_must_be_rejected_expected"),
        ZAP_REJECTED_ACTUAL("zap_must_be_rejected_actual");

        final String key;

        Field(String key) {
            this.key = key;
        }
    }

    private static ObjectNode fields(int[] c) {
        ObjectNode node = NodeDiversity.JSON.createObjectNode();
        node.put("generated_total", c[Field.DECODED_EXPECTED.ordinal()]
                + c[Field.GENERATED_REJECTED_EXPECTED.ordinal()]);
        for (Field field : Field.values()) {
            node.put(field.key, c[field.ordinal()]);
        }
        return node;
    }

    private static String summary(int[] c) {
        return String.format("valid %d/%d  generated rejected %d/%d  zapped rejected %d/%d",
                c[1], c[0], c[3], c[2], c[5], c[4]);
    }
}
