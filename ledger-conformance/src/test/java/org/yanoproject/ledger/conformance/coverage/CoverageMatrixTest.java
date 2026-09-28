package org.yanoproject.ledger.conformance.coverage;

import org.junit.jupiter.api.Test;
import org.yanoproject.ledger.conformance.ConformanceSettings;
import org.yanoproject.ledger.conformance.runner.ScenarioCases;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruCorpusNames;
import org.yanoproject.ledger.rules.fixtures.amaru.AmaruScenario.Expected;
import org.yanoproject.ledger.rules.fixtures.conformance.ConwayConstructorCatalogue;
import org.yanoproject.ledger.rules.fixtures.conformance.ConwayConstructorCatalogue.Entry;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-056 §8 coverage matrix: loads the constructor catalogue, scans test classes for {@code @Covers}, maps the
 * Amaru scenarios' expected predicates to constructors, and writes {@code ledger-rules/docs/conway-rule-coverage.md}
 * (under {@code conformanceReport}; always to {@code build/conformance/}).
 *
 * <p>Gaps are reported, not failed, until the Phase 5 gate switches {@code -Pconformance.strict=true} on. A
 * {@code @Covers} value that names no catalogue constructor always fails.</p>
 */
class CoverageMatrixTest {

    private final ConwayConstructorCatalogue catalogue = ConwayConstructorCatalogue.get();

    @Test
    void catalogueMatchesThePinnedTable() {
        // 3d-table at cardano-ledger f649f975: 88 leaf constructors; four cannot occur from protocol version 10
        // (the two bootstrap-only GOV constructors, the Babbage-superseded OutputTooSmallUTxO, and OutsideForecast,
        // whose check extends the epoch info linearly and cannot fail: ADR-056 Phase 3a results).
        assertThat(catalogue.all()).hasSize(88);
        assertThat(catalogue.inScope()).hasSize(84);
        assertThat(catalogue.all().stream().filter(e -> !e.inScope()).map(Entry::qualifiedName))
                .containsExactlyInAnyOrder("GOV.DisallowedProposalDuringBootstrap",
                        "GOV.DisallowedVotesDuringBootstrap", "UTXO.OutputTooSmallUTxO", "UTXO.OutsideForecast");
        assertThat(catalogue.find("UTXOS.ValidationTagMismatch")).get().extracting(Entry::phase).isEqualTo(2);
        assertThat(catalogue.find("DELEG.IncorrectDepositDELEG")).get().extracting(Entry::pvRange).isEqualTo("10");
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
        CoverageMatrix matrix = CoverageMatrix.build(catalogue, scan.coverings(), ScenarioCases.scenarios());

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
        if (ConformanceSettings.strict()) {
            assertThat(scan.skipped()).as("test classes the @Covers scan could not inspect (conformance.strict)")
                    .isEmpty();
            assertThat(missing).as("in-scope constructors without a @Covers test (conformance.strict)").isEmpty();
        } else if (!missing.isEmpty()) {
            System.out.println("  " + missing.size() + " constructors have no @Covers test yet (reported; "
                    + "-Pconformance.strict=true fails on them)");
        }
    }
}
