package org.yanoproject.ledger.conformance.coverage;

import org.yanoproject.ledger.conformance.coverage.CoversScanner.Covering;
import org.yanoproject.ledger.rules.conway.ruleset.ConwayRuleSet;
import org.yanoproject.ledger.rules.conway.ruleset.ConwayRuleSets;
import org.yanoproject.ledger.rules.conway.ruleset.ConwayScopes;
import org.yanoproject.ledger.rules.conway.ruleset.RuleUnit;
import org.yanoproject.ledger.rules.conway.ruleset.Scope;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario.Expected;
import org.yanoproject.ledger.rules.fixtures.conformance.ConwayConstructorCatalogue;
import org.yanoproject.ledger.rules.fixtures.conformance.ConwayConstructorCatalogue.Entry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The per-constructor coverage matrix (ADR-056 §8): for every catalogue constructor, the {@code @Covers} tests and
 * the Amaru scenarios whose expected predicate maps to it (through {@code AmaruCorpusNames}), and its status.
 * Rendered to {@code ledger-rules/docs/conway-rule-coverage.md}.
 */
public final class CoverageMatrix {

    /** Coverage status of one constructor. */
    public enum Status {
        TEST_AND_SCENARIO("test + scenario"),
        TEST("test"),
        SCENARIO_ONLY("scenario only"),
        GAP("gap"),
        /** Cannot occur in Conway at the pinned revision (no negative test is possible). */
        UNREACHABLE("unreachable at pin");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * @param entry     the constructor
     * @param tests     the {@code @Covers} tests
     * @param scenarios the Amaru scenario names expecting it
     * @param versions  per supported protocol version where the constructor exists, the tests that reject a
     *                  transaction with it at that version (ADR-056 Phase 5c)
     * @param blueprint per protocol version, the cardano-blueprint vector transactions Haskell rejects that the Java
     *                  engine rejects with this constructor first (verdict-level evidence, ADR-056 Phase 7b; the vectors
     *                  do not carry Haskell's constructor, so it does not count towards the status)
     */
    public record Row(Entry entry, List<String> tests, List<String> scenarios, Map<Integer, List<String>> versions,
                      Map<Integer, Integer> blueprint) {

        public Status status() {
            if (!entry.inScope()) {
                // Every catalogue constructor is in scope at PV 9-11 unless it cannot occur in Conway at all.
                return Status.UNREACHABLE;
            }
            if (!tests.isEmpty()) {
                return scenarios.isEmpty() ? Status.TEST : Status.TEST_AND_SCENARIO;
            }
            return scenarios.isEmpty() ? Status.GAP : Status.SCENARIO_ONLY;
        }
    }

    private final List<Row> rows;
    private final boolean scenariosConfigured;
    private final List<String> unknownCovers;
    private final List<Integer> protocolVersions;
    private final boolean blueprintConfigured;

    private CoverageMatrix(List<Row> rows, boolean scenariosConfigured, List<String> unknownCovers,
                           List<Integer> protocolVersions, boolean blueprintConfigured) {
        this.rows = List.copyOf(rows);
        this.scenariosConfigured = scenariosConfigured;
        this.unknownCovers = List.copyOf(unknownCovers);
        this.protocolVersions = List.copyOf(protocolVersions);
        this.blueprintConfigured = blueprintConfigured;
    }

    /**
     * @param worldEvidence per protocol version, per constructor, the mutation-matrix cases that reject a mutant with it
     *                      in that version's world ({@code Mutations.worldCases}); its keys are the supported versions
     * @param blueprint     per constructor, per protocol version, the cardano-blueprint vector transactions the Java
     *                      engine rejects with it where Haskell rejects too ({@code BlueprintVectorResults}); empty when
     *                      the vectors are not configured
     */
    public static CoverageMatrix build(ConwayConstructorCatalogue catalogue, List<Covering> coverings,
                                       Optional<List<AmaruScenario>> scenarios,
                                       Map<Integer, Map<String, List<String>>> worldEvidence,
                                       Optional<Map<String, Map<Integer, Integer>>> blueprint) {
        Map<String, List<String>> tests = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();
        for (Covering covering : coverings) {
            if (!catalogue.contains(covering.constructor())) {
                unknown.add(covering.test() + " covers unknown " + covering.constructor());
                continue;
            }
            tests.computeIfAbsent(covering.constructor(), k -> new ArrayList<>()).add(covering.test());
        }
        Map<String, List<String>> byConstructor = new LinkedHashMap<>();
        scenarios.ifPresent(list -> list.forEach(s -> {
            if (s.expected() instanceof Expected.Predicate p) {
                byConstructor.computeIfAbsent(p.qualifiedName(), k -> new ArrayList<>()).add(s.name());
            }
        }));
        List<Integer> versions = new ArrayList<>(new TreeMap<>(worldEvidence).keySet());
        List<Row> rows = new ArrayList<>();
        for (Entry e : catalogue.all()) {
            Map<Integer, List<String>> byVersion = new TreeMap<>();
            for (int version : versions) {
                if (!e.existsAt(version)) {
                    continue;
                }
                List<String> evidence = new ArrayList<>(worldEvidence.get(version)
                        .getOrDefault(e.qualifiedName(), List.of()));
                coverings.stream().filter(c -> c.constructor().equals(e.qualifiedName())
                        && c.versions().contains(version)).forEach(c -> evidence.add(c.test()));
                byVersion.put(version, List.copyOf(evidence));
            }
            rows.add(new Row(e, tests.getOrDefault(e.qualifiedName(), List.of()),
                    byConstructor.getOrDefault(e.qualifiedName(), List.of()), byVersion,
                    blueprint.map(b -> b.getOrDefault(e.qualifiedName(), Map.of())).orElse(Map.of())));
        }
        return new CoverageMatrix(rows, scenarios.isPresent(), unknown, versions, blueprint.isPresent());
    }

    /**
     * @return {@code RULE.Constructor at PV n} for every in-scope constructor that no test rejects a transaction with at
     *         a supported protocol version where it exists (the Phase 5c strict gate)
     */
    public List<String> missingPerVersion() {
        List<String> missing = new ArrayList<>();
        for (Row row : rows) {
            if (!row.entry().inScope()) {
                continue;
            }
            row.versions().forEach((version, evidence) -> {
                if (evidence.isEmpty()) {
                    missing.add(row.entry().qualifiedName() + " at PV " + version);
                }
            });
        }
        return missing;
    }

    /** @return the supported protocol versions the matrix counts per version */
    public List<Integer> protocolVersions() {
        return protocolVersions;
    }

    public List<Row> rows() {
        return rows;
    }

    /** @return {@code @Covers} values that name no catalogue constructor */
    public List<String> unknownCovers() {
        return unknownCovers;
    }

    /** @return in-scope constructors without a {@code @Covers} test (the Phase 5 strict gate) */
    public List<String> missingTests() {
        return rows.stream().filter(r -> r.entry().inScope() && r.tests().isEmpty())
                .map(r -> r.entry().qualifiedName()).toList();
    }

    public long count(Status status) {
        return rows.stream().filter(r -> r.status() == status).count();
    }

    public long inScope() {
        return rows.stream().filter(r -> r.entry().inScope()).count();
    }

    /**
     * @return the Java engine units that report the constructor, from the rule sets ({@code ConwayRuleSets}, ADR-056
     *         Phase 5c) with the protocol versions of each, or {@code –} when it is out of scope
     */
    static String javaRule(Entry e) {
        if (!e.inScope()) {
            return "–";
        }
        Map<String, TreeSet<Integer>> units = new LinkedHashMap<>();
        for (ConwayRuleSet set : ConwayRuleSets.all()) {
            for (Scope<?> scope : ConwayScopes.ALL) {
                for (RuleUnit<?> unit : set.units(scope)) {
                    if (unit.reports().stream().anyMatch(p -> p.qualifiedName().equals(e.qualifiedName()))) {
                        String name = unit.getClass().getName();
                        name = name.substring(name.lastIndexOf('.') + 1).replace('$', '.');
                        units.computeIfAbsent(name, k -> new TreeSet<>()).add(set.protocolVersion());
                    }
                }
            }
        }
        if (units.isEmpty()) {
            return "TBD";
        }
        List<String> parts = new ArrayList<>();
        units.forEach((name, versions) -> parts.add("`" + name + "` (" + versions.first()
                + (versions.size() > 1 ? "–" + versions.last() : "") + ")"));
        return String.join("<br>", parts);
    }

    /** @return the matrix as the {@code conway-rule-coverage.md} document */
    public String toMarkdown(String cardanoLedger, String amaruTag) {
        StringBuilder md = new StringBuilder();
        md.append("# Conway rule coverage matrix\n\n");
        md.append("<!-- Generated by ledger-conformance CoverageMatrixTest "
                + "(./gradlew :ledger-conformance:conformanceReport). Do not edit by hand. -->\n\n");
        md.append("ADR-056 §8. One row per Conway leaf predicate-failure constructor at cardano-ledger `")
                .append(cardanoLedger).append("` (cardano-node 11.1.2), from the pinned table in ")
                .append("[adr-056-haskell-pinned-revisions](../../adr/reports/adr-056-haskell-pinned-revisions.md) ")
                .append("(section 3d-table; static/dynamic from 3e). The machine-readable catalogue is ")
                .append("`ledger-rules/src/testFixtures/resources/org/yanoproject/ledger/rules/fixtures/conformance/")
                .append("conway-constructors.json`.\n\n");
        md.append("- **Tests**: test methods annotated `@Covers(\"RULE.Constructor\")` (ledger-conformance and ")
                .append("ledger-rules test classes).\n");
        md.append("- **Amaru scenarios**: scenarios of Amaru's corpus (tag `").append(amaruTag)
                .append("`) whose expected predicate maps to the constructor (`AmaruCorpusNames`).");
        if (!scenariosConfigured) {
            md.append(" *Not configured when this file was generated.*");
        }
        md.append("\n");
        md.append("- **Java rule**: the units of the Java engine's rule sets that report the constructor, with the ")
                .append("protocol versions whose rule set holds each (`ConwayRuleSets`, ADR-056 Phase 5c; the frozen ")
                .append("manifests are `ledger-rules/src/test/resources/org/yanoproject/ledger/rules/conway/ruleset/`).\n");
        md.append("- **Covered at PV**: per supported protocol version where the constructor exists, whether a test ")
                .append("rejects a transaction with it at that version (see *Coverage per protocol version*).\n");
        md.append("- **Blueprint vectors**: per protocol version, the cardano-blueprint vector transactions (ADR-056 ")
                .append("Phase 7b, `BlueprintVectorGateTest`) that Haskell rejects and the Java engine rejects with this ")
                .append("constructor first. Verdict-level evidence: the vectors record only that Haskell rejected the ")
                .append("transaction, not its constructor, so the column does not count towards the status.");
        if (!blueprintConfigured) {
            md.append(" *Not configured when this file was generated.*");
        }
        md.append("\n");
        md.append("- **Status**: `test + scenario`, `test`, `scenario only` (no negative test yet), `gap` ")
                .append("(neither), `unreachable at pin` (cannot occur in Conway at the pinned revision).\n");
        md.append("- Strict since the Phase 5 gate (`conformance.strict`, on by default): every in-scope constructor ")
                .append("must have a `@Covers` test, or `:ledger-conformance:test` fails.\n\n");

        md.append("## Summary\n\n");
        md.append("| Family | Constructors (PV 9–11) | test + scenario | test | scenario only | gap |\n");
        md.append("|---|---:|---:|---:|---:|---:|\n");
        Map<String, List<Row>> byFamily = new LinkedHashMap<>();
        rows.forEach(r -> byFamily.computeIfAbsent(r.entry().family(), k -> new ArrayList<>()).add(r));
        long[] totals = new long[5];
        for (Map.Entry<String, List<Row>> family : byFamily.entrySet()) {
            long[] c = counts(family.getValue());
            for (int i = 0; i < c.length; i++) {
                totals[i] += c[i];
            }
            md.append("| ").append(family.getKey()).append(" | ").append(c[0]).append(" | ").append(c[1])
                    .append(" | ").append(c[2]).append(" | ").append(c[3]).append(" | ").append(c[4]).append(" |\n");
        }
        md.append("| **Total** | **").append(totals[0]).append("** | **").append(totals[1]).append("** | **")
                .append(totals[2]).append("** | **").append(totals[3]).append("** | **").append(totals[4])
                .append("** |\n\n");
        md.append(rows.size()).append(" constructors in the catalogue; ").append(inScope())
                .append(" reachable at protocol version 9 (the bootstrap phase), 10 or 11. Out of scope: ");
        md.append(String.join(", ", rows.stream().filter(r -> !r.entry().inScope())
                .map(r -> "`" + r.entry().qualifiedName() + "` (" + r.entry().pvRange() + ")").toList()));
        md.append(".\n\n");

        md.append("## Coverage per protocol version\n\n");
        md.append("ADR-056 Phase 5c: a constructor must be covered at every supported protocol version where it exists, ")
                .append("by the mutation matrix's world cases (every mutant built and validated in every world, ")
                .append("`MutationWorldMatrixTest`) or by a `@Covers(pv = …)` test. Strict: a gap fails ")
                .append("`:ledger-conformance:test`.\n\n");
        md.append("| PV | Constructors that exist | covered | gaps |\n|---:|---:|---:|---|\n");
        for (int version : protocolVersions) {
            List<String> exist = rows.stream().filter(r -> r.entry().inScope() && r.versions().containsKey(version))
                    .map(r -> r.entry().qualifiedName()).toList();
            List<String> gaps = rows.stream().filter(r -> r.entry().inScope() && r.versions().containsKey(version)
                    && r.versions().get(version).isEmpty()).map(r -> "`" + r.entry().qualifiedName() + "`").toList();
            md.append("| ").append(version).append(" | ").append(exist.size()).append(" | ")
                    .append(exist.size() - gaps.size()).append(" | ").append(gaps.isEmpty() ? "–" : String.join(", ", gaps))
                    .append(" |\n");
        }
        md.append("\n");

        md.append("## Protocol-version gates to test on both sides\n\n");
        md.append("| Constructor | PV | Gate |\n|---|---|---|\n");
        rows.stream().filter(r -> r.entry().inScope() && !r.entry().pvGate().isBlank()
                        && !r.entry().pvGate().startsWith("PV > 4"))
                .forEach(r -> md.append("| `").append(r.entry().qualifiedName()).append("` | ")
                        .append(r.entry().pvRange()).append(" | ").append(escape(r.entry().pvGate())).append(" |\n"));
        md.append("\n");

        md.append("## Matrix\n\n");
        md.append("| Rule | Constructor | PV | Check | Java rule | Tests | Covered at PV | Amaru scenarios "
                + "| Blueprint vectors | Status |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (Row row : rows) {
            Entry e = row.entry();
            md.append("| ").append(e.rule()).append(" | `").append(e.constructor()).append("` | ").append(e.pvRange())
                    .append(" | ").append(e.check()).append(e.phase() == 2 ? " (phase 2)" : "")
                    .append(" | ").append(javaRule(e))
                    .append(" | ").append(row.tests().isEmpty() ? "–" : String.join("<br>", row.tests()))
                    .append(" | ").append(versionCoverage(row))
                    .append(" | ").append(scenarioIds(row.scenarios()))
                    .append(" | ").append(blueprintCounts(row.blueprint()))
                    .append(" | ").append(row.status().label()).append(" |\n");
        }
        return md.toString();
    }

    private static long[] counts(List<Row> rows) {
        long[] c = new long[5];
        for (Row row : rows) {
            if (!row.entry().inScope()) {
                continue;
            }
            c[0]++;
            switch (row.status()) {
                case TEST_AND_SCENARIO -> c[1]++;
                case TEST -> c[2]++;
                case SCENARIO_ONLY -> c[3]++;
                case GAP -> c[4]++;
                case UNREACHABLE -> {
                }
            }
        }
        return c;
    }

    /** e.g. {@code 9 ✓ · 10 ✓ · 11 ✗}: the supported versions where the constructor exists, and whether covered. */
    private static String versionCoverage(Row row) {
        if (!row.entry().inScope() || row.versions().isEmpty()) {
            return "–";
        }
        List<String> parts = new ArrayList<>();
        row.versions().forEach((version, evidence) -> parts.add(version + (evidence.isEmpty() ? " ✗" : " ✓")));
        return String.join(" · ", parts);
    }

    /** e.g. {@code 9: 3 · 10: 1}: blueprint vector transactions per protocol version. */
    private static String blueprintCounts(Map<Integer, Integer> perVersion) {
        if (perVersion.isEmpty()) {
            return "–";
        }
        List<String> parts = new ArrayList<>();
        new TreeMap<>(perVersion).forEach((version, n) -> parts.add(version + ": " + n));
        return String.join(" · ", parts);
    }

    /** Scenario names shortened to their number, e.g. {@code 00056}. */
    private static String scenarioIds(List<String> names) {
        if (names.isEmpty()) {
            return "–";
        }
        Map<String, String> ids = new TreeMap<>();
        names.forEach(n -> ids.put(n.length() >= 5 ? n.substring(0, 5) : n, n));
        return String.join(", ", ids.keySet());
    }

    private static String escape(String text) {
        return text.replace("|", "\\|");
    }
}
