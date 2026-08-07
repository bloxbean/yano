package com.bloxbean.cardano.yano.appchain.eutxo.demo;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-UTXO-009: the packaged showcase settlement identity is a stable pure
 * function of public demo facts — pin the moving parts so config drift
 * fails loudly before a cluster ever starts.
 */
class ShowcaseSettlementPlanTest {

    @Test
    void planIsDeterministicAndConfigCarriesTheFullWiring() {
        // Deterministic: recomputation matches the static singletons.
        assertThat(ShowcaseSettlementPlan.SEED_TX_ID)
                .isEqualTo(com.bloxbean.cardano.client.transaction.util
                        .TransactionUtil.getTxHash(
                                ShowcaseSettlementPlan.SEED_TX_BYTES));
        assertThat(ShowcaseSettlementPlan.PLAN.vaultAddress())
                .startsWith("addr_test1w");
        assertThat(ShowcaseSettlementPlan.PLAN.shardAddress())
                .startsWith("addr_test1w")
                .isNotEqualTo(ShowcaseSettlementPlan.PLAN.vaultAddress());
        assertThat(ShowcaseSettlementPlan.OPERATOR_ADDRESS)
                .startsWith("addr_test1v");
        assertThat(ShowcaseSettlementPlan.CLUSTER_MEMBERS).hasSize(3);

        Map<String, String> config = ShowcaseSettlementPlan.configProperties(
                ShowcaseSettlementPlan.PLAN,
                ShowcaseSettlementPlan.CHAIN_ID,
                com.bloxbean.cardano.yano.appchain.eutxo.client.EutxoKeyWallet
                        .fromSeed(ShowcaseSettlementPlan.WITHDRAWAL_L2_SEED)
                        .address(),
                ShowcaseSettlementPlan.OPERATOR_ADDRESS,
                ShowcaseSettlementPlan.OPERATOR_SEED);
        assertThat(config)
                .containsEntry("machines.eutxo.profile",
                        "yano-eutxo-v3-bridge-settlement")
                .containsEntry("observers.bridge-withdrawals.type",
                        "eutxo-batch-withdrawal-confirmation-v1")
                .containsEntry("effects.executors.eutxo-settlement.owner",
                        "false");
        assertThat(config.get("effects.executors.eutxo-settlement.vault-script"))
                .isNotBlank();
        assertThat(ShowcaseSettlementPlan.yamlBlock(
                10, ShowcaseSettlementPlan.CHAIN_ID, config))
                .contains("chains[10]:")
                .contains("payment-chain-settlement");
        System.out.println("[showcase-settlement] seedTx="
                + ShowcaseSettlementPlan.SEED_TX_ID);
        System.out.println("[showcase-settlement] vault="
                + ShowcaseSettlementPlan.PLAN.vaultAddress());
        System.out.println("[showcase-settlement] operator="
                + ShowcaseSettlementPlan.OPERATOR_ADDRESS);
    }
}
