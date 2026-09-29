package org.yanoproject.ledger.conformance.coverage;

import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.conformance.ConformanceSettings;
import org.yanoproject.ledger.conformance.blueprint.BlueprintVectorResults;
import org.yanoproject.ledger.conformance.mutation.Mutations;
import org.yanoproject.ledger.conformance.runner.ScenarioCases;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruCorpusNames;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario.Expected;
import org.yanoproject.ledger.rules.fixtures.conformance.ConwayConstructorCatalogue;
import org.yanoproject.ledger.rules.fixtures.conformance.ConwayConstructorCatalogue.Entry;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-056 §8 coverage matrix: loads the constructor catalogue, scans test classes for {@code @Covers}, maps the
 * Amaru scenarios' expected predicates to constructors, and writes {@code ledger-rules/docs/conway-rule-coverage.md}
 * (under {@code conformanceReport}; always to {@code build/conformance/}).
 *
 * <p>Strict by default since the Phase 5 gate: an in-scope constructor without a {@code @Covers} test, or a test class
 * the scan cannot inspect, fails ({@code -Pconformance.strict=false} reports them instead). A {@code @Covers} value that
 * names no catalogue constructor always fails.</p>
 */
class CoverageMatrixTest {

    private final ConwayConstructorCatalogue catalogue = ConwayConstructorCatalogue.get();

    @Test
    void catalogueMatchesThePinnedTable() {
        // 3d-table at cardano-ledger f649f975: 88 leaf constructors; two cannot occur in Conway (the Babbage-superseded
        // OutputTooSmallUTxO, and OutsideForecast, whose check extends the epoch info linearly and cannot fail: ADR-056
        // Phase 3a results). The two bootstrap-only GOV constructors are in scope since Phase 5b (PV 9).
        assertThat(catalogue.all()).hasSize(88);
        assertThat(catalogue.inScope()).hasSize(86);
        assertThat(catalogue.all().stream().filter(e -> !e.inScope()).map(Entry::qualifiedName))
                .containsExactlyInAnyOrder("UTXO.OutputTooSmallUTxO", "UTXO.OutsideForecast");
        assertThat(catalogue.find("UTXOS.ValidationTagMismatch")).get().extracting(Entry::phase).isEqualTo(2);
        assertThat(catalogue.find("GOV.DisallowedProposalDuringBootstrap")).get().extracting(Entry::pvRange)
                .isEqualTo("9");
        assertThat(catalogue.find("GOV.DisallowedVotesDuringBootstrap")).get().extracting(Entry::pvRange)
                .isEqualTo("9");
        assertThat(catalogue.find("GOV.ProposalReturnAccountDoesNotExist")).get().extracting(Entry::pvRange)
                .isEqualTo("10+");
        assertThat(catalogue.find("DELEG.IncorrectDepositDELEG")).get().extracting(Entry::pvRange).isEqualTo("9–10");
        assertThat(catalogue.find("DELEG.DepositIncorrectDELEG")).get().extracting(Entry::pvRange).isEqualTo("11+");
        assertThat(catalogue.find("LEDGER.ConwayMempoolFailure")).get().extracting(Entry::family).isEqualTo("MEMPOOL");
    }

    @Test
    void everyAmaruCorpusNameMapsToACatalogueConstructor() {
        ScenarioCases.scenarios().ifPresent(scenarios -> scenarios.forEach(s -> {
            if (s.expected() instanceof Expected.Predicate p) {
                assertThat(catalogue.contains(p.qualifiedName()))
                        .as("%s expects %s (%s)", s.name(), p.qualifiedName(), p.corpusName()).isTrue();
                assertThat(AmaruCorpusNames.haskellName(p.corpusName())).isPresent();
            }
        }));
    }

    @Test
    void generatesTheCoverageMatrix() throws URISyntaxException {
        Path ownClasses = Path.of(CoverageMatrixTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        CoversScanner.Result scan = CoversScanner.scan(ownClasses, ConformanceSettings.scanDirs(),
                CoverageMatrixTest.class.getClassLoader());
        CoverageMatrix matrix = CoverageMatrix.build(catalogue, scan.coverings(), ScenarioCases.scenarios(),
                worldEvidence(), BlueprintVectorResults.get().map(BlueprintVectorResults.Run::rejectionEvidence));

        assertThat(matrix.unknownCovers()).as("@Covers values that are not catalogue constructors").isEmpty();
        assertThat(scan.coverings()).as("the mutation matrix's @Covers tests are found").isNotEmpty();

        Path written = ConformanceSettings.write("conway-rule-coverage.md", "conformance.coverage.file",
                matrix.toMarkdown(catalogue.cardanoLedger(), ConformanceSettings.AMARU_TAG));
        List<String> missing = matrix.missingTests();
        System.out.printf("Coverage matrix: %d in scope, %d test + scenario, %d test, %d scenario only, %d gap "
                        + "(%s)%n", matrix.inScope(), matrix.count(CoverageMatrix.Status.TEST_AND_SCENARIO),
                matrix.count(CoverageMatrix.Status.TEST), matrix.count(CoverageMatrix.Status.SCENARIO_ONLY),
                matrix.count(CoverageMatrix.Status.GAP), written);
        scan.skipped().forEach(s -> System.out.println("  not inspected: " + s));
        List<String> missingPerVersion = matrix.missingPerVersion();
        System.out.printf("Coverage per protocol version %s: %d gaps %s%n", matrix.protocolVersions(),
                missingPerVersion.size(), missingPerVersion);
        if (ConformanceSettings.strict()) {
            assertThat(scan.skipped()).as("test classes the @Covers scan could not inspect (conformance.strict)")
                    .isEmpty();
            assertThat(missing).as("in-scope constructors without a @Covers test (conformance.strict)").isEmpty();
            assertThat(missingPerVersion).as("constructors not covered at a protocol version where they exist "
                    + "(conformance.strict)").isEmpty();
        } else if (!missing.isEmpty()) {
            System.out.println("  " + missing.size() + " constructors have no @Covers test (reported because "
                    + "-Pconformance.strict=false)");
        }
    }

    /**
     * @return per mutation world, per constructor, the world cases that reject a mutant with it (the mutation matrix's
     *         per-version evidence, {@code MutationWorldMatrixTest})
     */
    private static Map<Integer, Map<String, List<String>>> worldEvidence() {
        Map<Integer, Map<String, List<String>>> evidence = new TreeMap<>();
        for (int world : Mutations.WORLDS) {
            Map<String, List<String>> byConstructor = new LinkedHashMap<>();
            for (Mutations.WorldCase c : Mutations.worldCases(world)) {
                c.expected().forEach(constructor -> byConstructor.computeIfAbsent(constructor,
                        k -> new ArrayList<>()).add(c.id()));
            }
            evidence.put(world, byConstructor);
        }
        return evidence;
    }
}
