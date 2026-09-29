package org.yanoproject.tx.gate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-056 Phase 6b gate: the Phase 6 devnet matrix ({@link LedgerRulesDevnetMatrix}) on an in-process devnet
 * producer whose admission engine is {@code java-julc} (experimental flag), with the ledger-state mempool and the
 * block-production overlay; every produced block is re-validated independently (java-julc engine, rule {@code LEDGER},
 * origin {@code SYNC}, full validation). Runs with {@code -PledgerRulesGate=true}.
 */
@EnabledIfSystemProperty(named = "yano.gate.devnet", matches = "true")
class JavaEngineDevnetGateTest {

    private static final Logger log = LoggerFactory.getLogger(JavaEngineDevnetGateTest.class);

    @Test
    void thePhase6DevnetMatrixPassesWithTheJavaEngine() throws Exception {
        try (DevnetGateNode node = DevnetGateNode.start(DevnetGateNode.Settings.of("java-julc"))) {
            LedgerRulesDevnetMatrix.Report report = new LedgerRulesDevnetMatrix(node,
                    LedgerRulesDevnetMatrix.DEVNET_MNEMONIC).run();
            report.lines().forEach(line -> log.info("GATE java-julc | {}", line));
            report.metrics().forEach((key, value) -> log.info("GATE java-julc metric | {} = {}", key, value));
            assertThat(report.problems()).isEmpty();
        }
    }
}
