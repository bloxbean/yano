package com.bloxbean.cardano.yano.appchain.eutxo.demo;

import java.util.Set;

/** Maintained no-real-funds virtual-ledger scenario. */
public final class LedgerDemoScenarioProvider implements EutxoDemoScenarioProvider {
    @Override public String id() { return "ledger"; }
    @Override public String version() { return "1"; }
    @Override public String maturity() { return "preview"; }
    @Override public String recipe() { return "eutxo-ledger"; }
    @Override public String trustBoundary() {
        return "virtual genesis; no Cardano L1 custody or settlement";
    }
    @Override public Set<String> operations() {
        return Set.of("setup", "start", "up", "status", "stop", "reset",
                "transfer", "reconcile", "verify", "round-trip");
    }
}
