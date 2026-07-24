package com.bloxbean.cardano.yano.appchain.eutxo.demo;

import java.util.LinkedHashMap;
import java.util.Map;
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

    @Override
    public void setup(EutxoDemoWorkspace workspace, EutxoDemoOptions options)
            throws Exception {
        Map<String, String> identities = workspace.manifest().publicIdentities();
        new EutxoDemoCluster(workspace).generateProject(recipe(), Map.of(
                "bridgeVaultAddress", identities.get("vaultAddress"),
                "bridgeVaultScriptHash", identities.get("vaultScriptHash"),
                "bridgeMaxDepositLovelace", "100000000",
                "bridgeWithdrawalAddress", identities.get("payoutAddress"),
                "bridgeEpoch", "1",
                "bridgeMaxWithdrawalLovelace", "50000000",
                "bridgeMaxPendingWithdrawals", "100"));
    }

    @Override
    public EutxoDemoResult execute(
            String operation,
            EutxoDemoWorkspace workspace,
            EutxoDemoOptions options) throws Exception {
        EutxoDemoCluster cluster = new EutxoDemoCluster(workspace);
        return switch (operation) {
            case "start", "up" -> {
                cluster.start();
                yield status("EUTXO_BRIDGE_DEMO_CLUSTER_STARTED", workspace, cluster);
            }
            case "stop" -> {
                cluster.stop();
                yield status("EUTXO_BRIDGE_DEMO_CLUSTER_STOPPED", workspace, cluster);
            }
            case "status" -> status("EUTXO_BRIDGE_DEMO_STATUS", workspace, cluster);
            case "fund", "deposit", "transfer", "settle", "withdraw",
                    "reconcile", "verify", "round-trip" ->
                    new EutxoBridgeDemoWorkflow(workspace, cluster).execute(operation);
            default -> throw new UnsupportedOperationException(
                    operation + " is not supported by scenario bridge");
        };
    }

    private EutxoDemoResult status(
            String code,
            EutxoDemoWorkspace workspace,
            EutxoDemoCluster cluster) throws Exception {
        EutxoDemoCluster.ClusterStatus current = cluster.status();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("scenario", id());
        fields.put("ready", current.ready());
        fields.put("readyNodes", current.readyNodes());
        fields.put("expectedNodes", current.expectedNodes());
        fields.put("trustBoundary", trustBoundary());
        fields.put("workspace", workspace.root().toString());
        fields.put("operations", workspace.journal().read());
        return EutxoDemoResult.of(code, fields);
    }
}
