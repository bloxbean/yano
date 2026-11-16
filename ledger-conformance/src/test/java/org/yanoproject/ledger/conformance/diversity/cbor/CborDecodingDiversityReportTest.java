package org.yanoproject.ledger.conformance.diversity.cbor;

import co.nstant.in.cbor.model.ByteString;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.ledger.conformance.CborConformance;
import com.bloxbean.cardano.client.ledger.conformance.CborRule;
import com.bloxbean.cardano.client.ledger.conformance.SampleOutcome;
import com.bloxbean.cardano.client.spec.Era;
import com.bloxbean.cardano.client.transaction.raw.RawInput;
import com.bloxbean.cardano.client.transaction.raw.RawOutput;
import com.bloxbean.cardano.yaci.core.model.serializers.AuxDataSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockHeaderSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.CertificateSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.TransactionBodySerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.TransactionInputSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.TransactionOutputSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.WitnessesSerializer;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.conformance.diversity.NodeDiversity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-060 §6: the {@code cbor-decoding} report over cardano-cbor-dataset's Conway corpus, in the dataset's report
 * format, version 2. Since ADR 0005 (M4) it runs CCL's conformance harness ({@code cardano-client-ledger-cbor-conformance}),
 * the code CCL's release gate runs: every sample through CCL's Haskell-strict reads, which Yano's admission uses, then
 * CCL's models and writers. A valid sample must decode and re-encode to its {@code .expected.cbor}, an invalid one must
 * be rejected.
 *
 * <p>The run's outcomes must be exactly the harness's pinned outcomes file for the dataset revision (ADR 0005 J3), and
 * no stage may be an error (J11). Yaci's decoders are added as diagnostics, in the per-decoder table and the
 * {@code CBOR_DECODER_OUTCOMES} file: the report itself measures the strict reads alone.</p>
 */
class CborDecodingDiversityReportTest {

    static final String SCENARIO = "cbor-decoding";
    static final String DATASET = "cardano-cbor-dataset/dataset";
    static final String PROTOCOL_VERSION = "11.0";

    /** The header rules, which CCL has no model of: Yaci's header decoder is their library column. */
    private static final Set<String> HEADER_RULES = Set.of("header", "header_body", "block_number", "kes_period",
            "kes_vkey", "operational_cert", "vrf_vkey");

    @Test
    void writesTheReport() throws IOException {
        Path dataset = NodeDiversity.requireCorpus(DATASET);
        assertThat(CborConformance.normalizationDifferences(dataset)).isEmpty();
        List<SampleOutcome> outcomes = CborConformance.run(dataset);
        List<String> outcomesFile = CborConformance.outcomesFile(outcomes);

        Map<String, CborRule> rules = CborRule.conway();
        Map<String, Decoder> yaci = yaciDecoders();
        Path conway = dataset.resolve("conway");
        ObjectNode results = NodeDiversity.JSON.createObjectNode();
        Map<String, int[]> counts = new TreeMap<>();
        Map<String, int[]> decoders = new TreeMap<>();
        List<String> perDecoder = new ArrayList<>();
        for (SampleOutcome outcome : outcomes) {
            String rule = outcome.rule();
            byte[] input = read(conway.resolve(outcome.path()));
            int[] c = counts.computeIfAbsent(rule, k -> new int[COUNTS]);
            int offset = outcome.valid() ? 0 : 2;
            c[offset + 1]++;
            if (outcome.passes()) {
                c[offset]++;
                results.put(outcome.path(), true);
            } else {
                results.put(outcome.path(), outcome.failure());
            }
            Boolean strict = outcome.strict() == SampleOutcome.Strict.ERROR ? null
                    : outcome.strict() == SampleOutcome.Strict.ACCEPTED;
            Boolean library = accepts(library(rule, rules.get(rule)), input);
            Boolean yacis = yaci.containsKey(rule) ? accepts(yaci.get(rule), input) : null;
            int[] t = decoders.computeIfAbsent(rule, k -> new int[DECODER_COLUMNS]);
            tally(t, 0, outcome.valid(), strict);
            tally(t, 4, outcome.valid(), library);
            if (yaci.containsKey(rule)) {
                tally(t, 8, outcome.valid(), yacis);
            }
            perDecoder.add(outcome.path() + " " + outcome(strict) + " " + outcome(library) + " "
                    + (yaci.containsKey(rule) ? outcome(yacis) : "-"));
        }
        String perDecoderFile = System.getenv("CBOR_DECODER_OUTCOMES");
        if (perDecoderFile != null) {
            Files.write(Path.of(perDecoderFile), perDecoder);
        }
        Path dir = Path.of(System.getProperty("node.diversity.report.dir", "build/node-diversity"));
        Files.createDirectories(dir);
        Files.write(dir.resolve("cbor-decoding-outcomes.txt"), outcomesFile);

        ObjectNode report = NodeDiversity.JSON.createObjectNode();
        report.put("version", "2");
        report.put("generated_at", System.currentTimeMillis());
        report.put("protocol_version", PROTOCOL_VERSION);
        report.put("dataset_commit", NodeDiversity.commit("node.diversity.cborDatasetCommit"));
        report.put("implementation_commit", NodeDiversity.commit("node.diversity.commit"));
        report.set("results", results);
        NodeDiversity.write(SCENARIO, report);

        int[] totals = new int[COUNTS];
        counts.forEach((rule, c) -> {
            System.out.printf("cbor-decoding %-28s %s%n", rule, summary(c));
            Arrays.setAll(totals, i -> totals[i] + c[i]);
        });
        System.out.printf("cbor-decoding %-28s %s%n", "TOTAL", summary(totals));
        printDecoders(decoders);

        assertThat(NodeDiversity.commit("node.diversity.cborDatasetCommit"))
                .as("corpora.env pins the dataset revision CCL's outcomes file was made for (ADR 0005 D8)")
                .isEqualTo(CborConformance.DATASET_REVISION);
        assertThat(outcomes.stream().filter(SampleOutcome::hasError).map(o -> o.line() + " " + o.detail()))
                .as("ADR 0005 J11").isEmpty();
        assertThat(CborConformance.differences(CborConformance.expectedOutcomes(), outcomesFile))
                .as("ADR 0005 J3: the outcomes CCL's gate pinned (this run's file is in " + dir + ")").isEmpty();
    }

    /** valid passed, valid total, invalid rejected, invalid total. */
    private static final int COUNTS = 4;

    /** valid accepted, valid total, invalid rejected, invalid total, for the strict reader, the library and yaci. */
    private static final int DECODER_COLUMNS = 12;

    @FunctionalInterface
    interface Decoder {
        Object decode(byte[] cbor) throws Exception;
    }

    /**
     * @return the library column's decoder: CCL's model, or for the header rules, which CCL has no model of, yaci's
     *         header decoding (the only one with header validation off, the default)
     */
    private static Decoder library(String name, CborRule rule) {
        if (HEADER_RULES.contains(name)) {
            return cbor -> yaciHeader(name.equals("header") ? cbor : CborRule.headerFor(name, cbor));
        }
        return rule.model()::decode;
    }

    /**
     * @return yaci's decoder for the rules it has one for, apart from headers, which the library column decodes with
     *         yaci. Sync decodes with yaci by default, so this gives the sync path a number. It is a diagnostic: the
     *         report measures transaction admission, which does not use these. As in sync, an item yaci recovers from
     *         (a block, witness set or auxiliary data with a datum or metadata it cannot parse) counts as accepted.
     *         Each is the reader sync uses: a credential has none, as yaci reads one only in its certificate.
     */
    static Map<String, Decoder> yaciDecoders() {
        return Map.of(
                "auxiliary_data", AuxDataSerializer.INSTANCE::deserialize,
                "block", cbor -> BlockSerializer.INSTANCE.deserialize(cat("8207", cbor)),
                "certificate", cbor -> CertificateSerializer.INSTANCE.read(CborSpan.of(cbor)),
                "transaction_body", cbor -> TransactionBodySerializer.INSTANCE.read(cbor, Era.Conway),
                "transaction_input", cbor -> TransactionInputSerializer.INSTANCE.read(RawInput.of(CborSpan.of(cbor))),
                "transaction_output",
                cbor -> TransactionOutputSerializer.INSTANCE.read(RawOutput.tolerant(CborSpan.of(cbor))),
                "transaction_witness_set", WitnessesSerializer.INSTANCE::deserialize);
    }

    /** A header as chain sync decodes one with header validation off: yaci's decoding alone. */
    private static Object yaciHeader(byte[] header) throws Exception {
        return BlockHeaderSerializer.INSTANCE.deserialize(CborSerializationUtil.serialize(new ByteString(header)));
    }

    private static byte[] cat(String prefix, byte[] item) {
        byte[] head = HexFormat.of().parseHex(prefix);
        byte[] out = Arrays.copyOf(head, head.length + item.length);
        System.arraycopy(item, 0, out, head.length, item.length);
        return out;
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
    private static Boolean accepts(Decoder decoder, byte[] input) {
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
        System.out.println("cbor-decoding, each decoder alone (diagnostic; the report counts the strict reads alone):");
        System.out.printf("cbor-decoding %-28s %-36s %-56s %s%n", "rule", "strict (CCL): valid ok / invalid ok",
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
