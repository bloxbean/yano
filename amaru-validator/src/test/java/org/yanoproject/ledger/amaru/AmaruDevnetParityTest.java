package org.yanoproject.ledger.amaru;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yanoproject.api.config.YanoPropertyKeys;
import org.yanoproject.tx.gate.DevnetGateNode;
import org.yanoproject.tx.gate.LedgerRulesDevnetMatrix;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-057 Phase C: the ADR-056 Phase 6 devnet matrix ({@link LedgerRulesDevnetMatrix}: dependent chains in the
 * mempool and in one block, an epoch crossing with chains pending, a rollback with chains pending) with
 * {@code engine: amaru-scalus} (Amaru phase one, Scalus phase two), compared with the same matrix under
 * {@code engine: java-julc}: the same admission verdicts, mempool contents, block placement and drop reasons. Every
 * block the Amaru devnet produces is re-validated by the java-julc engine and by Amaru in {@code SYNC} mode, and the two
 * must derive the same effects for every transaction. Runs with {@code -PledgerRulesGate=true}.
 */
@EnabledIfSystemProperty(named = "yano.gate.devnet", matches = "true")
class AmaruDevnetParityTest {

    private static final Logger log = LoggerFactory.getLogger(AmaruDevnetParityTest.class);

    @Test
    void thePhase6DevnetMatrixGivesTheSameVerdictsAndEffectsUnderAmaruAndJava() throws Exception {
        LedgerRulesDevnetMatrix.Report amaru = run(new DevnetGateNode.Settings("amaru-scalus", 500, 100,
                Map.of(YanoPropertyKeys.Validation.AMARU_POOL_SIZE, "2")));
        LedgerRulesDevnetMatrix.Report java = run(DevnetGateNode.Settings.of("java-julc"));

        assertThat(amaru.problems()).as("amaru matrix").isEmpty();
        assertThat(java.problems()).as("java matrix").isEmpty();
        assertThat(amaru.metrics().get("revalidation.engines")).asList().containsExactly("java-julc", "amaru-scalus");
        assertThat(normalise(amaru.lines())).as("same verdicts, mempool states, placements and drop reasons")
                .containsExactlyElementsOf(java.lines());
    }

    /**
     * Recorded divergences (ADR-057 Phase C results; {@link AmaruKnownDivergencesTest}): the line Amaru produces,
     * mapped to the java engine's. Both are rejections.
     */
    private static final Map<String, String> KNOWN_DIVERGENCES = Map.of(
            "A.reject-unregistered-delegator ENGINE.AmaruEngineFailure",
            "A.reject-unregistered-delegator DELEG.StakeKeyNotRegisteredDELEG");

    private static List<String> normalise(List<String> lines) {
        return lines.stream().map(line -> KNOWN_DIVERGENCES.getOrDefault(line, line)).toList();
    }

    private static LedgerRulesDevnetMatrix.Report run(DevnetGateNode.Settings settings) throws Exception {
        try (DevnetGateNode node = DevnetGateNode.start(settings)) {
            LedgerRulesDevnetMatrix.Report report = new LedgerRulesDevnetMatrix(node,
                    LedgerRulesDevnetMatrix.DEVNET_MNEMONIC).run();
            report.lines().forEach(line -> log.info("GATE {} | {}", settings.engine(), line));
            report.metrics().forEach((key, value) -> log.info("GATE {} metric | {} = {}", settings.engine(), key,
                    value));
            report.problems().forEach(problem -> log.warn("GATE {} problem | {}", settings.engine(), problem));
            return report;
        }
    }
}
