package com.bloxbean.cardano.yano.appchain.eutxo.zk.demo;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.eutxo.client.EutxoClient;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2Domain;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoReceipt;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoValidityTransition;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalDatum;
import com.bloxbean.cardano.yano.appchain.eutxo.demo.EutxoBridgeDemoWorkflow;
import com.bloxbean.cardano.yano.appchain.eutxo.demo.EutxoDemoCluster;
import com.bloxbean.cardano.yano.appchain.eutxo.demo.EutxoDemoJournal;
import com.bloxbean.cardano.yano.appchain.eutxo.demo.EutxoDemoResult;
import com.bloxbean.cardano.yano.appchain.eutxo.demo.EutxoDemoWorkspace;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.client.EutxoL2SessionKey;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.client.EutxoL2TransactionBuilder;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkAuthorizationProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.lifecycle.EutxoValidityLifecycle;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Disposable trusted-prover workflow. The b16 proof is generated and verified
 * before the demo vault pays out; the native demo vault remains explicitly
 * distinct from the production proof-withdrawal validator.
 */
final class EutxoZkDemoWorkflow {
    private static final BigInteger DEPOSIT = BigInteger.valueOf(20_000_000);
    private static final BigInteger WITHDRAWAL = BigInteger.valueOf(3_000_000);
    private final EutxoDemoWorkspace workspace;
    private final EutxoDemoCluster cluster;
    private final EutxoClient client;

    EutxoZkDemoWorkflow(
            EutxoDemoWorkspace workspace,
            EutxoDemoCluster cluster) {
        this.workspace = workspace;
        this.cluster = cluster;
        this.client = new EutxoClient(AppChainClient.builder(cluster.apiBase())
                .chainId(workspace.manifest().chainId()).build());
    }

    EutxoDemoResult execute(String operation) throws Exception {
        requireReady();
        EutxoBridgeDemoWorkflow bridge =
                new EutxoBridgeDemoWorkflow(workspace, cluster);
        if (List.of("fund", "deposit", "transfer", "prove", "settle",
                "withdraw", "reconcile", "verify", "round-trip")
                .contains(operation)) {
            bridge.execute("deposit");
        }
        if (List.of("transfer", "prove", "settle", "withdraw",
                "reconcile", "verify", "round-trip").contains(operation)) {
            transfer();
        }
        if (List.of("prove", "settle", "withdraw",
                "reconcile", "verify", "round-trip").contains(operation)) {
            prove();
        }
        if (List.of("settle", "withdraw", "reconcile",
                "verify", "round-trip").contains(operation)) {
            bridge.execute("settle");
        }
        if (List.of("reconcile", "verify", "round-trip").contains(operation)) {
            bridge.execute("verify");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("scenario", "zk");
        fields.put("workspace", workspace.root().toString());
        fields.put("chainId", workspace.manifest().chainId());
        fields.put("batchProfile", "cardano-payment-b16");
        fields.put("proofSystem", "groth16");
        fields.put("trustBoundary",
                "trusted-prover disposable-devnet; demo-native vault payout");
        fields.put("operations", workspace.journal().read());
        return EutxoDemoResult.of(
                "round-trip".equals(operation)
                        ? "EUTXO_ZK_DEMO_ROUND_TRIP_PASS"
                        : "EUTXO_ZK_DEMO_OPERATION_PASS",
                fields);
    }

    private void transfer() throws Exception {
        EutxoDemoJournal journal = workspace.journal();
        EutxoDemoJournal.Entry current = entry("zk-transfer-v1");
        if (current.state().ordinal() >= EutxoDemoJournal.State.STABLE.ordinal()) {
            return;
        }
        EutxoOutpoint input = EutxoOutpoint.parse(required(
                entry("bridge-deposit-v1"), "mirroredOutpoint"));
        String operator = identity("operatorAddress");
        String payout = identity("payoutAddress");
        EutxoWithdrawalDatum withdrawal = new EutxoWithdrawalDatum(
                EutxoWithdrawalDatum.ABI_VERSION,
                workspace.manifest().chainId(), 1, payout,
                HexFormat.of().parseHex("cd".repeat(32)));
        TransactionBody body = TransactionBody.builder()
                .inputs(List.of(new TransactionInput(
                        input.transactionId(), input.index())))
                .outputs(List.of(
                        TransactionOutput.builder().address(operator)
                                .value(Value.fromCoin(
                                        DEPOSIT.subtract(WITHDRAWAL))).build(),
                        TransactionOutput.builder().address(payout)
                                .value(Value.fromCoin(WITHDRAWAL))
                                .inlineDatum(PlutusData.deserialize(
                                        withdrawal.encode())).build()))
                .fee(BigInteger.ZERO)
                .ttl(10_000_000L)
                .networkId(NetworkId.TESTNET)
                .build();
        EutxoZkAuthorizationProfile authorization =
                EutxoZkAuthorizationProfile.JUBJUB_DEVELOPMENT_V1;
        EutxoL2Domain domain = new EutxoL2Domain(
                workspace.manifest().chainId(), "devnet",
                EutxoProfile.V1.digestHex(),
                EutxoZkProfile.Z3_VALIDITY_SETTLEMENT.digestHex(),
                authorization.id(), authorization.digestHex(),
                new byte[32], 10_000_000L);
        byte[] envelope = Files.readAllBytes(workspace.root().resolve(
                "secrets/l2/session-key.enc"));
        char[] password = Files.readString(workspace.root().resolve(
                "secrets/l2/session-key.password")).trim().toCharArray();
        byte[] transaction;
        String l2TransactionId;
        try (EutxoL2SessionKey key =
                     EutxoL2SessionKey.decrypt(envelope, password)) {
            String credential = HexFormat.of().formatHex(
                    new Address(operator).getPaymentCredentialHash()
                            .orElseThrow());
            var l2Transaction = EutxoL2TransactionBuilder.sign(
                    domain, body, List.of(
                            new EutxoL2TransactionBuilder.Signer(
                                    credential, 1, List.of(0), key)));
            transaction = l2Transaction.canonicalBytes();
            l2TransactionId = l2Transaction.transactionId();
        } finally {
            java.util.Arrays.fill(password, '\0');
            java.util.Arrays.fill(envelope, (byte) 0);
        }
        Path artifact = workspace.root().resolve(
                "artifacts/l2/zk-withdrawal.l2tx");
        Files.write(artifact, transaction,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        var submitted = client.submit(transaction);
        EutxoReceipt receipt = awaitReceipt(l2TransactionId);
        EutxoValidityTransition transition = awaitTransition(receipt);
        Path transitionFile = workspace.root().resolve(
                "artifacts/proofs/finalized-transition.cbor");
        Files.write(transitionFile, transition.canonicalBytes(),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        var claim = transition.withdrawals().getFirst();
        journal.advance("zk-transfer-v1", "zk-transfer-v1",
                current.requestDigest(), EutxoDemoJournal.State.STABLE,
                Map.of("messageId", submitted.messageId(),
                        "transactionId", receipt.transactionId(),
                        "claimId", claim.claimId(),
                        "transition", "artifacts/proofs/finalized-transition.cbor",
                        "previousRoot", HexFormat.of().formatHex(
                                transition.previousRoot())), null);
        EutxoDemoJournal.Entry bridgeEntry = entry("bridge-transfer-v1");
        journal.advance("bridge-transfer-v1", "bridge-transfer-v1",
                bridgeEntry.requestDigest(), EutxoDemoJournal.State.STABLE,
                Map.of("messageId", submitted.messageId(),
                        "transactionId", receipt.transactionId(),
                        "claimId", claim.claimId(),
                        "artifact", "artifacts/l2/zk-withdrawal.l2tx"), null);
    }

    private void prove() throws Exception {
        EutxoDemoJournal.Entry current = entry("zk-proof-v1");
        if (current.state().ordinal() >= EutxoDemoJournal.State.VERIFIED.ordinal()) {
            return;
        }
        EutxoDemoJournal.Entry transfer = entry("zk-transfer-v1");
        EutxoValidityLifecycle lifecycle =
                new EutxoValidityLifecycle(workspace.project());
        if (!"VALIDITY_DOCTOR_PASSED".equals(lifecycle.doctor().status())) {
            throw new IllegalStateException(
                    "EUTXO_ZK_DEMO_CEREMONY_REQUIRED");
        }
        var result = lifecycle.prove(
                List.of(workspace.root().resolve(required(
                        transfer, "transition"))),
                required(transfer, "previousRoot"));
        String proofId = String.valueOf(result.details().get("proofId"));
        workspace.journal().advance("zk-proof-v1", "zk-proof-v1",
                current.requestDigest(), EutxoDemoJournal.State.VERIFIED,
                Map.of("proofId", proofId,
                        "batchProfile", "cardano-payment-b16",
                        "status", result.status()), null);
    }

    private EutxoReceipt awaitReceipt(String transactionId)
            throws InterruptedException {
        for (int i = 0; i < 180; i++) {
            try {
                var receipt = client.transaction(transactionId);
                if (receipt.isPresent()) {
                    if (receipt.orElseThrow().status()
                            != EutxoReceipt.Status.ACCEPTED) {
                        throw new IllegalStateException(
                                "ZK_L2_TRANSACTION_REJECTED");
                    }
                    return receipt.orElseThrow();
                }
            } catch (IllegalStateException failure) {
                throw failure;
            } catch (RuntimeException ignored) {
                // Finality is asynchronous.
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("ZK_L2_FINALITY_TIMEOUT");
    }

    private EutxoValidityTransition awaitTransition(EutxoReceipt receipt)
            throws InterruptedException {
        for (int i = 0; i < 120; i++) {
            try {
                var transition = client.finalizedValidityTransition(
                        receipt.appHeight(), receipt.ordinal()).value();
                if (transition.isPresent()) return transition.orElseThrow();
            } catch (RuntimeException ignored) {
                // Root-fixed transition publication is asynchronous.
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("ZK_TRANSITION_TIMEOUT");
    }

    private EutxoDemoJournal.Entry entry(String id) throws Exception {
        EutxoDemoJournal.Entry entry = workspace.journal().read().get(id);
        return entry == null
                ? workspace.journal().plan(id, id,
                id + "\n" + workspace.manifest().chainId()) : entry;
    }

    private static String required(
            EutxoDemoJournal.Entry entry,
            String key) {
        String value = entry.publicArtifacts().get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("ZK_DEMO_JOURNAL_INCOMPLETE");
        }
        return value;
    }

    private String identity(String key) {
        String value = workspace.manifest().publicIdentities().get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("ZK_DEMO_IDENTITY_UNAVAILABLE");
        }
        return value;
    }

    private void requireReady() {
        if (!cluster.status().ready()) {
            throw new IllegalStateException("DEMO_CLUSTER_NOT_READY");
        }
    }
}
