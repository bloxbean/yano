package org.yanoproject.ledger.conformance.diversity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * ADR-060: the pinned corpora and the reports for the Cardano node-diversity site
 * (https://github.com/pragma-org/node-diversity-conformance). The build sets the system properties
 * ({@code ledger-conformance/build.gradle}, task {@code nodeDiversityReports}).
 */
public final class NodeDiversity {

    /** The implementation name every report carries. */
    public static final String IMPLEMENTATION = "yano";

    public static final ObjectMapper JSON = new ObjectMapper();

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private NodeDiversity() {
    }

    /**
     * @param relative a path below the corpora directory, e.g. {@code uplc-conformance}
     * @return the directory, when the corpora are configured and it exists
     */
    public static Optional<Path> corpus(String relative) {
        String root = System.getProperty("node.diversity.corpora");
        if (root == null || root.isBlank()) {
            return Optional.empty();
        }
        Path dir = Path.of(root).resolve(relative);
        return Files.isDirectory(dir) ? Optional.of(dir) : Optional.empty();
    }

    /** @return the corpus directory; skips the calling test when it is absent */
    public static Path requireCorpus(String relative) {
        Optional<Path> dir = corpus(relative);
        Assumptions.assumeTrue(dir.isPresent(), () -> "corpus " + relative + " not configured: pass "
                + "-PnodeDiversityCorpora=<dir> (ledger-conformance/node-diversity/fetch-corpora.sh)");
        return dir.orElseThrow();
    }

    /** @return a report holding the fields every scenario requires */
    public static ObjectNode report(String scenario) {
        ObjectNode report = JSON.createObjectNode();
        report.put("version", "1");
        report.put("scenario", scenario);
        report.put("generated_at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        report.putObject("implementation").put("name", IMPLEMENTATION);
        return report;
    }

    /**
     * Writes {@code yano-<version>-<yyyymmdd>-<scenario>.json}, the release asset name the site's
     * {@code github_releases} lookup matches.
     *
     * @return the file written
     */
    public static Path write(String scenario, ObjectNode report) {
        Path dir = Path.of(System.getProperty("node.diversity.report.dir", "build/node-diversity"));
        String version = System.getProperty("node.diversity.version", "dev");
        Path file = dir.resolve(IMPLEMENTATION + "-" + version + "-" + DAY.format(Instant.now()) + "-" + scenario
                + ".json");
        try {
            Files.createDirectories(dir);
            JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), report);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        System.out.println("Node-diversity report: " + file);
        return file;
    }
}
