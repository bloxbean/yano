package com.bloxbean.cardano.yano.appchain.eutxo.zk.demo;

import com.bloxbean.cardano.yano.appchain.eutxo.demo.EutxoDemoCluster;
import com.bloxbean.cardano.yano.appchain.eutxo.demo.EutxoDemoOptions;
import com.bloxbean.cardano.yano.appchain.eutxo.demo.EutxoDemoResult;
import com.bloxbean.cardano.yano.appchain.eutxo.demo.EutxoDemoScenarioProvider;
import com.bloxbean.cardano.yano.appchain.eutxo.demo.EutxoDemoWorkspace;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.client.EutxoL2SessionKey;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.lifecycle.EutxoValidityLifecycle;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Optional trusted-prover disposable-devnet ZeroJ scenario. */
public final class EutxoZkDemoScenarioProvider
        implements EutxoDemoScenarioProvider {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override public String id() { return "zk"; }
    @Override public String version() { return "1"; }
    @Override public String maturity() { return "experimental"; }
    @Override public String recipe() { return "eutxo-zeroj-preview"; }
    @Override public String trustBoundary() {
        return "trusted-prover disposable-devnet b16 profile; test funds only";
    }
    @Override public Set<String> operations() {
        return Set.of("setup", "start", "up", "status", "stop", "reset",
                "fund", "deposit", "transfer", "ceremony", "prove",
                "settle", "withdraw", "reconcile", "verify", "round-trip");
    }

    @Override
    public void setup(EutxoDemoWorkspace workspace, EutxoDemoOptions options)
            throws Exception {
        Path passwordFile = workspace.root().resolve(
                "secrets/l2/session-key.password");
        Path keyFile = workspace.root().resolve(
                "secrets/l2/session-key.enc");
        byte[] entropy = new byte[24];
        RANDOM.nextBytes(entropy);
        char[] password = HexFormat.of().formatHex(entropy).toCharArray();
        String publicKey;
        try (EutxoL2SessionKey key = EutxoL2SessionKey.random()) {
            Files.write(keyFile, key.encrypt(password),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.writeString(passwordFile, new String(password) + "\n",
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            publicKey = HexFormat.of().formatHex(key.publicKey());
        } finally {
            java.util.Arrays.fill(password, '\0');
            java.util.Arrays.fill(entropy, (byte) 0);
        }
        Map<String, String> identity = workspace.manifest().publicIdentities();
        Map<String, String> answers = new LinkedHashMap<>();
        answers.put("eutxoL2Address", identity.get("operatorAddress"));
        answers.put("eutxoL2PublicKey", publicKey);
        answers.put("bridgeVaultAddress", identity.get("vaultAddress"));
        answers.put("bridgeVaultScriptHash", identity.get("vaultScriptHash"));
        answers.put("bridgeMaxDepositLovelace", "100000000");
        answers.put("bridgeWithdrawalAddress", identity.get("payoutAddress"));
        answers.put("bridgeEpoch", "1");
        answers.put("bridgeMaxWithdrawalLovelace", "50000000");
        answers.put("bridgeMaxPendingWithdrawals", "100");
        new EutxoDemoCluster(workspace).generateProject(recipe(), answers);
        Files.writeString(workspace.root().resolve(
                        "artifacts/proofs/l2-public-identity.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                        "authorizationProfile", "zeroj-jubjub-dev-v1",
                        "publicKey", publicKey,
                        "encryptedKey", "secrets/l2/session-key.enc")),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
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
                yield status("EUTXO_ZK_DEMO_CLUSTER_STARTED", workspace, cluster);
            }
            case "stop" -> {
                cluster.stop();
                yield status("EUTXO_ZK_DEMO_CLUSTER_STOPPED", workspace, cluster);
            }
            case "status" ->
                    status("EUTXO_ZK_DEMO_STATUS", workspace, cluster);
            case "ceremony" -> {
                if (!options.confirmed()) {
                    throw new IllegalArgumentException("ceremony requires --yes");
                }
                var result = new EutxoValidityLifecycle(
                        workspace.project()).bootstrap(true, true);
                yield EutxoDemoResult.of("EUTXO_ZK_DEMO_CEREMONY_READY",
                        Map.of("stage", result.status(),
                                "trustBoundary", trustBoundary(),
                                "workspace", workspace.root().toString()));
            }
            case "fund", "deposit", "transfer", "prove", "settle",
                    "withdraw", "reconcile", "verify", "round-trip" ->
                    new EutxoZkDemoWorkflow(workspace, cluster)
                            .execute(operation);
            default -> throw new UnsupportedOperationException(
                    operation + " is not supported by scenario zk");
        };
    }

    private EutxoDemoResult status(
            String code,
            EutxoDemoWorkspace workspace,
            EutxoDemoCluster cluster) throws Exception {
        var clusterStatus = cluster.status();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("scenario", id());
        fields.put("ready", clusterStatus.ready());
        fields.put("readyNodes", clusterStatus.readyNodes());
        fields.put("expectedNodes", clusterStatus.expectedNodes());
        fields.put("trustBoundary", trustBoundary());
        try {
            fields.put("validity",
                    new EutxoValidityLifecycle(workspace.project()).status());
        } catch (RuntimeException ignored) {
            fields.put("validity", "CEREMONY_NOT_BOOTSTRAPPED");
        }
        return EutxoDemoResult.of(code, fields);
    }
}
