package com.bloxbean.cardano.yano.appchain.eutxo.demo;

import com.bloxbean.cardano.yano.appchain.eutxo.client.EutxoKeyWallet;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regenerates the showcase settlement chain block. The packaged
 * {@code application-appchain.yml} must match this output — run this test
 * and copy {@code build/showcase-settlement-chain.yml} whenever the plan,
 * the profile, or the wiring keys change.
 */
class EmitShowcaseYamlTest {
    /** Where the bootstrap writes the parameterized validators. */
    static final String SCRIPT_DIR = "${yano.home}/config/settlement";

    @Test
    void emitPackagedChainBlock() throws Exception {
        Map<String, String> config = ShowcaseSettlementPlan.configProperties(
                ShowcaseSettlementPlan.PLAN,
                ShowcaseSettlementPlan.CHAIN_ID,
                EutxoKeyWallet.fromSeed(
                        ShowcaseSettlementPlan.WITHDRAWAL_L2_SEED).address(),
                ShowcaseSettlementPlan.OPERATOR_ADDRESS,
                ShowcaseSettlementPlan.OPERATOR_SEED,
                "devnet",
                SCRIPT_DIR);
        String block = ShowcaseSettlementPlan.yamlBlock(
                10, ShowcaseSettlementPlan.CHAIN_ID, config);

        // The block stays small enough to read: the 22 KB of parameterized
        // validators live in files the bootstrap writes, not in config.
        assertThat(block.length()).isLessThan(8_000);
        assertThat(block)
                .contains("yano-eutxo-v3-bridge-settlement-devnet")
                .contains("eutxo-batch-withdrawal-confirmation-v1")
                .contains("vault-script-file")
                .doesNotContain("vault-script:");

        Path out = Path.of("build/showcase-settlement-chain.yml");
        Files.createDirectories(out.getParent());
        Files.writeString(out, block);
        System.out.println("[emit] " + out.toAbsolutePath());
    }
}
