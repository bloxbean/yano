package com.bloxbean.cardano.yano.appchain.eutxo.demo;

import java.util.Set;

/** Maintained federated devnet bridge scenario. */
public final class BridgeDemoScenarioProvider implements EutxoDemoScenarioProvider {
    @Override public String id() { return "bridge"; }
    @Override public String version() { return "1"; }
    @Override public String maturity() { return "experimental"; }
    @Override public String recipe() { return "eutxo-cardano-bridge"; }
    @Override public String trustBoundary() {
        return "federated disposable-devnet custody; no validity proof";
    }
    @Override public Set<String> operations() {
        return Set.of("setup", "start", "up", "status", "stop", "reset",
                "fund", "deposit", "transfer", "settle", "withdraw",
                "reconcile", "verify", "round-trip");
    }
}
