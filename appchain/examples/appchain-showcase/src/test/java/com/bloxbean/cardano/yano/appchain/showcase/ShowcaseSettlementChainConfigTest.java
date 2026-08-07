package com.bloxbean.cardano.yano.appchain.showcase;

import com.bloxbean.cardano.yano.appchain.eutxo.client.EutxoKeyWallet;
import com.bloxbean.cardano.yano.appchain.eutxo.demo.ShowcaseSettlementPlan;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-UTXO-009: the packaged light profile's settlement chain must stay
 * byte-identical to what {@link ShowcaseSettlementPlan} derives. The plan is
 * a pure function of public demo facts, so any drift — a changed profile,
 * a re-derived vault, a renamed wiring key — fails here rather than at
 * cluster start.
 *
 * <p>Regenerate the block with:
 * {@code ./gradlew :appchain-eutxo-demo:test --tests '*EmitShowcaseYamlTest'}
 */
class ShowcaseSettlementChainConfigTest {
    private static final Path CONFIG = Path.of(
            "src/main/showcase/config/application-appchain.yml");
    /** Must match EmitShowcaseYamlTest.SCRIPT_DIR. */
    private static final String SCRIPT_DIR = "${yano.home}/config/settlement";

    @Test
    void packagedChainBlockMatchesTheDerivedPlan() throws Exception {
        Map<String, String> properties = ShowcaseSettlementPlan.configProperties(
                ShowcaseSettlementPlan.PLAN,
                ShowcaseSettlementPlan.CHAIN_ID,
                EutxoKeyWallet.fromSeed(
                        ShowcaseSettlementPlan.WITHDRAWAL_L2_SEED).address(),
                ShowcaseSettlementPlan.OPERATOR_ADDRESS,
                ShowcaseSettlementPlan.OPERATOR_SEED,
                "devnet",
                SCRIPT_DIR);
        String expected = ShowcaseSettlementPlan.yamlBlock(
                10, ShowcaseSettlementPlan.CHAIN_ID, properties);

        String yml = Files.readString(CONFIG);
        assertThat(yml)
                .as("packaged config must carry the derived settlement block; "
                        + "regenerate with EmitShowcaseYamlTest")
                .contains(expected.stripTrailing());

        // The retired single-key bridge chain must not come back.
        assertThat(yml).doesNotContain("payment-chain-l1bridge");
        assertThat(yml).doesNotContain("yano-eutxo-v2-plutus-v3");
        assertThat(yml).doesNotContain("eutxo-withdrawal-confirmation-v1");

        // Deposits credit L2 owners from the inline datum; a virtual genesis
        // may never coexist with a bridge chain (capability conflict).
        String chain = yml.substring(yml.indexOf("chains[10]:"));
        assertThat(chain).doesNotContain("genesis:");

        // The observers, the batch confirmation path, and the executor only
        // activate when their bundles are allow-listed.
        assertThat(yml).contains(
                "- com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano",
                "- com.bloxbean.cardano.yano.appchain.eutxo.indexer");
    }

    @Test
    void theDemoProfileIsDevnetOnlyAndCannotHoldRealFunds() {
        assertThat(ShowcaseSettlementPlan.PLAN.profile().devnetOnly()).isTrue();
        assertThat(ShowcaseSettlementPlan.PLAN.profile().fallbackDelayMinSlots())
                .isEqualTo(ShowcaseSettlementPlan.FALLBACK_DELAY_SLOTS);
    }
}
