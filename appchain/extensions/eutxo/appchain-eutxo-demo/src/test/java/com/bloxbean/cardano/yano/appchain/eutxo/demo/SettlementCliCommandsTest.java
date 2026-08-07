package com.bloxbean.cardano.yano.appchain.eutxo.demo;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

/** The settlement CLI surface the showcase drives. */
class SettlementCliCommandsTest {

    @Test
    void infoRunsWithoutAWorkspaceAndReportsTheDeployIdentity() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit;
        try (PrintWriter o = new PrintWriter(out); PrintWriter e = new PrintWriter(err)) {
            exit = new EutxoDemoCli().run(
                    new String[] {"settlement-info"}, o, e);
        }
        assertThat(exit).as(err.toString()).isZero();
        assertThat(out.toString())
                .contains("payment-chain-settlement")
                .contains("yano-eutxo-v3-bridge-settlement-devnet")
                .contains(ShowcaseSettlementPlan.PLAN.vaultAddress());
    }

    @Test
    void bootstrapRequiresItsInputs() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit;
        try (PrintWriter o = new PrintWriter(out); PrintWriter e = new PrintWriter(err)) {
            exit = new EutxoDemoCli().run(
                    new String[] {"settlement-bootstrap"}, o, e);
        }
        assertThat(exit).isNotZero();
    }
}
