package org.yanoproject.ledger.conformance.diversity.cbor;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ADR 0003 I7, per sample and not by count, so a rejection lost cannot hide behind one gained: the report misses only
 * samples its baseline missed, and the strict reader alone rejects every invalid sample it rejected in the baseline.
 *
 * <p>The baseline holds both sets' complements on the pinned dataset: the samples the report missed, and the invalid
 * samples the strict reader did not reject. Its first lines say what it was measured on.</p>
 */
final class CborRejectionGate {

    private static final String BASELINE = "decoding-baseline.txt";
    private static final String REPORT_MISS = "report-miss ";
    private static final String STRICT_ACCEPTS = "strict-accepts ";

    private CborRejectionGate() {
    }

    /**
     * @param reportMisses  the samples the report missed
     * @param strictAccepts the invalid samples the strict reader did not reject
     */
    record Baseline(Set<String> reportMisses, Set<String> strictAccepts) {
    }

    /**
     * One sample of a run.
     *
     * @param name           its path under the era's directory
     * @param valid          whether it must decode
     * @param strictRejected whether the strict reader alone rejected it, a crash not being a rejection
     * @param passed         whether it met its expectation in the report
     */
    record Sample(String name, boolean valid, boolean strictRejected, boolean passed) {
    }

    static Baseline baseline() {
        Set<String> reportMisses = new HashSet<>();
        Set<String> strictAccepts = new HashSet<>();
        try (InputStream in = CborRejectionGate.class.getResourceAsStream(BASELINE)) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.startsWith(REPORT_MISS)) {
                    reportMisses.add(line.substring(REPORT_MISS.length()));
                } else if (line.startsWith(STRICT_ACCEPTS)) {
                    strictAccepts.add(line.substring(STRICT_ACCEPTS.length()));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Baseline(reportMisses, strictAccepts);
    }

    /** @return what the run lost against the baseline, one line per sample and check; empty when it passes */
    static List<String> violations(Baseline baseline, List<Sample> run) {
        List<String> violations = new ArrayList<>();
        for (Sample sample : run) {
            if (!sample.passed() && !baseline.reportMisses().contains(sample.name())) {
                violations.add("new report miss: " + sample.name());
            }
            if (!sample.valid() && !sample.strictRejected() && !baseline.strictAccepts().contains(sample.name())) {
                violations.add("strict rejection lost: " + sample.name());
            }
        }
        return violations;
    }
}
