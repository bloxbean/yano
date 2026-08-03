package com.bloxbean.cardano.yano.appchain.eutxo.demo;

import java.util.Set;

/**
 * Installed scenario contribution. Providers own orchestration; the workspace
 * owns lifecycle, safety, and durable operation state.
 */
public interface EutxoDemoScenarioProvider {
    String id();

    String version();

    String maturity();

    String recipe();

    String trustBoundary();

    Set<String> operations();

    default void setup(
            EutxoDemoWorkspace workspace,
            EutxoDemoOptions options) throws Exception {
    }

    default EutxoDemoResult execute(
            String operation,
            EutxoDemoWorkspace workspace,
            EutxoDemoOptions options) throws Exception {
        throw new UnsupportedOperationException(
                operation + " is not supported by scenario " + id());
    }
}
