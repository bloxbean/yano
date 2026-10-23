package org.yanoproject.ledger.conformance.diversity.uplc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.conformance.diversity.NodeDiversity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-060 §4: the {@code uplc-conformance} report, julc over the scenario's vectors with the cost models they ship.
 * Mismatches with the scenario's expectations are printed for triage; they never fail the test.
 */
class UplcConformanceDiversityReportTest {

    static final String SCENARIO = "uplc-conformance";
    static final String EXPECTATIONS = "node-diversity-conformance/scenarios/uplc-conformance";

    @Test
    void writesTheReport() throws IOException {
        Path corpus = NodeDiversity.requireCorpus(SCENARIO);
        List<UplcVector> vectors = UplcVector.list(corpus);
        Map<String, long[]> costModels = new HashMap<>();

        ObjectNode report = NodeDiversity.report(SCENARIO);
        ArrayNode entries = report.putArray("scenarios");
        for (UplcVector vector : vectors) {
            long[] costModel = costModels.computeIfAbsent(vector.protocolMajor() + "/" + vector.language(),
                    k -> costModel(corpus, vector));
            Path flat = vector.flat();
            UplcEvaluation.Outcome outcome = UplcEvaluation.evaluate(
                    Files.isRegularFile(flat) ? Files.readString(flat) : null, Files.readString(vector.program()),
                    Files.readString(vector.expected()), vector.language(), vector.protocolMajor(), costModel);
            ObjectNode entry = entries.addObject()
                    .put("id", vector.id())
                    .put("title", vector.title())
                    .put("group", vector.group())
                    .put("plutus_version", vector.plutusVersion())
                    .put("protocol_version", vector.protocolVersion())
                    .put("evaluation", outcome.evaluation());
            if (outcome.budget() == null) {
                entry.putNull("budget");
            } else {
                entry.putObject("budget").put("cpu", outcome.budget().cpuSteps())
                        .put("mem", outcome.budget().memoryUnits());
            }
        }
        NodeDiversity.write(SCENARIO, report);

        NodeDiversity.corpus(EXPECTATIONS).map(dir -> dir.resolve("expectations.json")).filter(Files::isRegularFile)
                .ifPresent(file -> compare(entries, file));
        assertThat(entries).hasSize(vectors.size()).isNotEmpty();
    }

    private static long[] costModel(Path corpus, UplcVector vector) {
        return UplcEvaluation.costModel(corpus.resolve("cost-models/protocol-version-" + vector.protocolMajor() + "/v"
                + vector.language() + ".json"));
    }

    /** Prints the entries that differ from the scenario's expectations (evaluation, whitespace aside, and budget). */
    private static void compare(ArrayNode entries, Path expectationsFile) {
        Map<String, JsonNode> actual = new HashMap<>();
        entries.forEach(e -> actual.put(key(e), e));
        JsonNode expectations;
        try {
            expectations = NodeDiversity.JSON.readTree(expectationsFile.toFile()).get("scenarios");
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + expectationsFile, e);
        }
        int passed = 0;
        for (JsonNode expected : expectations) {
            JsonNode entry = actual.get(key(expected));
            boolean evaluation = entry != null && normalize(entry.get("evaluation").asText())
                    .equals(normalize(expected.get("evaluation").asText()));
            boolean budget = expected.get("budget").isNull() || entry != null && sameBudget(expected, entry);
            if (evaluation && budget) {
                passed++;
            } else {
                System.out.printf("uplc-conformance mismatch %s %s/%s: expected %s %s, got %s%n", expected.get("id")
                        .asText(), expected.get("plutus_version").asText(), expected.get("protocol_version").asText(),
                        expected.get("evaluation").asText(), expected.get("budget"),
                        Optional.ofNullable(entry).map(e -> e.get("evaluation").asText() + " " + e.get("budget"))
                                .orElse("no entry"));
            }
        }
        System.out.printf("uplc-conformance: %d of %d match the expectations%n", passed, expectations.size());
    }

    private static boolean sameBudget(JsonNode expected, JsonNode entry) {
        JsonNode a = expected.get("budget");
        JsonNode b = entry.get("budget");
        return !b.isNull() && a.get("cpu").asLong() == b.get("cpu").asLong()
                && a.get("mem").asLong() == b.get("mem").asLong();
    }

    private static String key(JsonNode entry) {
        return entry.get("id").asText() + "|" + entry.get("plutus_version").asText() + "|"
                + entry.get("protocol_version").asText();
    }

    private static String normalize(String text) {
        return text.replaceAll("\\s+", " ").replaceAll("([\\[({]) ", "$1").replaceAll(" ([)\\]}])", "$1")
                .replaceAll(" ?, ?", ",").trim();
    }
}
