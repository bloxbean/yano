package org.yanoproject.ledger.conformance.diversity.cbor;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.conformance.diversity.NodeDiversity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-060 §6: the {@code cbor-decoding} report over cardano-cbor-dataset's Conway corpus, in the dataset's report
 * format, version 2. Every {@code *.input.cbor} under {@code valid/} or {@code manual-valid/} must decode and re-encode
 * to its {@code .expected.cbor} (both normalized, except for the rules the dataset compares exactly), and every other
 * one must be rejected.
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
        Map<String, CborRule.Decoder> yaci = CborRule.yaciDecoders();

        Map<String, int[]> counts = new TreeMap<>();
        Map<String, int[]> decoders = new TreeMap<>();
        ObjectNode results = NodeDiversity.JSON.createObjectNode();
        List<String> outcomes = new ArrayList<>();
        List<CborRejectionGate.Sample> gated = new ArrayList<>();
        for (Path sample : samples(conway)) {
            Path relative = conway.relativize(sample);
            String ruleName = relative.getName(0).toString();
            boolean valid = isValid(relative.getName(1).toString());
            int[] c = counts.computeIfAbsent(ruleName, k -> new int[COUNTS]);
            CborRule rule = rules.get(ruleName);
            assertThat(rule).as("the harness must have a decoder for rule %s", ruleName).isNotNull();
            Optional<String> failure = check(rule, valid, sample);
            tallyDecoders(decoders.computeIfAbsent(ruleName, k -> new int[DECODER_COLUMNS]), rule, yaci.get(ruleName),
                    valid, read(sample));
            outcomes.add(relative.toString().replace('\\', '/') + " " + outcomes(rule, yaci.get(ruleName), read(sample)));
            int offset = valid ? 0 : 2;
            c[offset + 1]++;
            String name = relative.toString().replace('\\', '/');
            boolean strictRejected = Boolean.FALSE.equals(accepts(rule.strict(), read(sample)));
            gated.add(new CborRejectionGate.Sample(name, valid, strictRejected, failure.isEmpty()));
            if (failure.isEmpty()) {
                c[offset]++;
                results.put(name, true);
            } else {
                results.put(name, failure.get());
            }
        }
        assertThat(counts).isNotEmpty();
        String outcomesFile = System.getenv("CBOR_DECODER_OUTCOMES");
        if (outcomesFile != null) {
            Files.write(Path.of(outcomesFile), outcomes);
        }

        ObjectNode report = NodeDiversity.JSON.createObjectNode();
        report.put("version", "2");
        report.put("generated_at", System.currentTimeMillis());
        report.put("protocol_version", PROTOCOL_VERSION);
        report.put("dataset_commit", commit("node.diversity.cborDatasetCommit"));
        report.put("implementation_commit", commit("node.diversity.commit"));
        report.set("results", results);
        NodeDiversity.write(SCENARIO, report);

        int[] totals = new int[COUNTS];
        counts.forEach((rule, c) -> {
            System.out.printf("cbor-decoding %-28s %s%n", rule, summary(c));
            Arrays.setAll(totals, i -> totals[i] + c[i]);
        });
        System.out.printf("cbor-decoding %-28s %s%n", "TOTAL", summary(totals));
        printDecoders(decoders);
        assertThat(CborRejectionGate.violations(CborRejectionGate.baseline(), gated)).isEmpty();
    }

    /** valid passed, valid total, invalid rejected, invalid total. */
    private static final int COUNTS = 4;

    /** @return whether a sample in this directory must decode; the dataset's other directories must be rejected */
    static boolean isValid(String category) {
        return category.equals("valid") || category.equals("manual-valid");
    }

    private static String commit(String property) {
        String commit = System.getProperty(property, "");
        assertThat(commit).as(property).matches("[0-9a-f]{40}");
        return commit;
    }

    /** valid accepted, valid total, invalid rejected, invalid total, for the strict reader, the library and yaci. */
    private static final int DECODER_COLUMNS = 12;

    /** Each decoder on its own: which side of the node a difference from Haskell comes from (not reported). */
    private static void tallyDecoders(int[] t, CborRule rule, CborRule.Decoder yaci, boolean valid, byte[] input) {
        tally(t, 0, valid, accepts(rule.strict(), input));
        tally(t, 4, valid, accepts(rule.library(), input));
        if (yaci != null) {
            tally(t, 8, valid, accepts(yaci, input));
        }
    }

    /** ADR 0003 I7: each decoder's outcome on one sample, A accepted, R rejected, C crashed, - no decoder. */
    private static String outcomes(CborRule rule, CborRule.Decoder yaci, byte[] input) {
        return outcome(accepts(rule.strict(), input)) + " " + outcome(accepts(rule.library(), input)) + " "
                + (yaci == null ? "-" : outcome(accepts(yaci, input)));
    }

    private static String outcome(Boolean accepted) {
        return accepted == null ? "C" : accepted ? "A" : "R";
    }

    /** {@code accepted} is null for a crash, which meets no expectation. */
    private static void tally(int[] t, int offset, boolean valid, Boolean accepted) {
        int column = offset + (valid ? 0 : 2);
        t[column + 1]++;
        if (accepted != null && valid == accepted) {
            t[column]++;
        }
    }

    /** @return whether the decoder accepts the input, or null when it crashes */
    private static Boolean accepts(CborRule.Decoder decoder, byte[] input) {
        try {
            decoder.decode(input);
            return true;
        } catch (Error e) {
            return null;
        } catch (Exception e) {
            return false;
        }
    }

    private static void printDecoders(Map<String, int[]> decoders) {
        int[] total = new int[DECODER_COLUMNS];
        System.out.println("cbor-decoding, each decoder alone (diagnostic; the report counts Yano's alone):");
        System.out.printf("cbor-decoding %-28s %-36s %-56s %s%n", "rule", "Yano strict: valid ok / invalid ok",
                "library (CCL; yaci for headers): valid ok / invalid ok", "yaci (sync): valid ok / invalid ok");
        decoders.forEach((rule, t) -> {
            System.out.printf("cbor-decoding %-28s %s%n", rule, decoderSummary(t));
            Arrays.setAll(total, i -> total[i] + t[i]);
        });
        System.out.printf("cbor-decoding %-28s %s%n", "TOTAL", decoderSummary(total));
    }

    private static String decoderSummary(int[] t) {
        return String.format("%-36s %-56s %s", String.format("%d/%d / %d/%d", t[0], t[1], t[2], t[3]),
                String.format("%d/%d / %d/%d", t[4], t[5], t[6], t[7]),
                t[9] + t[11] == 0 ? "-" : String.format("%d/%d / %d/%d", t[8], t[9], t[10], t[11]));
    }

    /**
     * @return empty when the sample meets its expectation, otherwise why not, as {@code decode: } or
     *         {@code encode: } and a message: a valid sample must decode and re-encode to the reference, an invalid
     *         one must be rejected
     */
    static Optional<String> check(CborRule rule, boolean valid, Path sample) {
        byte[] input = read(sample);
        try {
            rule.read(input);
        } catch (Error e) {
            return Optional.of("decode: crash: " + e);
        } catch (Exception e) {
            return valid ? Optional.of("decode: " + e) : Optional.empty();
        }
        if (!valid) {
            return Optional.of("decode: succeeded when expected to fail");
        }
        try {
            byte[] expected = read(sample.resolveSibling(sample.getFileName().toString()
                    .replace(".input.cbor", ".expected.cbor")));
            byte[] encoded = rule.encode(input);
            boolean same = rule.exact() ? Arrays.equals(encoded, expected)
                    : Arrays.equals(CborNormalizer.normalize(encoded), CborNormalizer.normalize(expected));
            return same ? Optional.empty() : Optional.of("encode: re-encoding differs from the reference");
        } catch (Error e) {
            return Optional.of("encode: crash: " + e);
        } catch (Exception e) {
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

    private static String summary(int[] c) {
        return String.format("valid %d/%d  invalid %d/%d", c[0], c[1], c[2], c[3]);
    }
}
