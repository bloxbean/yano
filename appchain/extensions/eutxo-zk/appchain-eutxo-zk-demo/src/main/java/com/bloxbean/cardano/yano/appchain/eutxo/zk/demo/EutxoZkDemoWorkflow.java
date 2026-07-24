package com.bloxbean.cardano.yano.appchain.eutxo.zk.demo;

import com.bloxbean.cardano.yano.appchain.eutxo.demo.EutxoDemoCluster;
import com.bloxbean.cardano.yano.appchain.eutxo.demo.EutxoDemoResult;
import com.bloxbean.cardano.yano.appchain.eutxo.demo.EutxoDemoWorkspace;

/** ZK operation implementation completed by DEMO-M5. */
final class EutxoZkDemoWorkflow {
    private final EutxoDemoWorkspace workspace;
    private final EutxoDemoCluster cluster;

    EutxoZkDemoWorkflow(
            EutxoDemoWorkspace workspace,
            EutxoDemoCluster cluster) {
        this.workspace = workspace;
        this.cluster = cluster;
    }

    EutxoDemoResult execute(String operation) {
        throw new IllegalStateException(
                "EUTXO_ZK_DEMO_CEREMONY_REQUIRED_BEFORE_" + operation.toUpperCase(
                        java.util.Locale.ROOT));
    }
}
