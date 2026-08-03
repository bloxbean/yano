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
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2KeyBinding;
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

import java.io.IOException;
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

    EutxoDemoResult execute(String operation, int count) throws Exception {
        requireReady();
        if (count < 1 || count > 16) {
            throw new IllegalArgumentException("round-trip count must be between 1 and 16");
        }
        EutxoBridgeDemoWorkflow bridge =
                new EutxoBridgeDemoWorkflow(workspace, cluster, keyBindings());
        if (List.of("fund", "deposit", "transfer", "prove", "settle",
                "withdraw", "reconcile", "verify", "round-trip")
                .contains(operation)) {
            bridge.execute("deposit", count);
        }
        if (List.of("transfer", "prove", "settle", "withdraw",
                "reconcile", "verify", "round-trip").contains(operation)) {
            for (int round = 1; round <= count; round++) {
                transfer(round);
            }
        }
        if (List.of("prove", "settle", "withdraw",
                "reconcile", "verify", "round-trip").contains(operation)) {
            for (int round = 1; round <= count; round++) {
                prove(round);
            }
        }
        if (List.of("settle", "withdraw", "reconcile",
                "verify", "round-trip").contains(operation)) {
            bridge.execute("settle", count);
        }
        if (List.of("reconcile", "verify", "round-trip").contains(operation)) {
            bridge.execute("verify", count);
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("scenario", "zk");
        fields.put("workspace", workspace.root().toString());
        fields.put("chainId", workspace.manifest().chainId());
        fields.put("batchProfile", "cardano-payment-b16");
        fields.put("proofSystem", "groth16");
        fields.put("targetRounds", count);
        fields.put("completedRounds", count);
        fields.put("trustBoundary",
                "trusted-prover disposable-devnet; demo-native vault payout");
        fields.put("operations", workspace.journal().read());
        return EutxoDemoResult.of(
                "round-trip".equals(operation)
                        ? "EUTXO_ZK_DEMO_ROUND_TRIP_PASS"
                        : "EUTXO_ZK_DEMO_OPERATION_PASS",
                fields);
    }

    private void transfer(int round) throws Exception {
        EutxoDemoJournal journal = workspace.journal();
        String operationId = id("zk-transfer-v1", round);
        EutxoDemoJournal.Entry current = entry(operationId);
        if (current.state().ordinal() >= EutxoDemoJournal.State.STABLE.ordinal()) {
            return;
        }
        EutxoOutpoint input = EutxoOutpoint.parse(required(
                entry(id("bridge-deposit-v1", round)), "mirroredOutpoint"));
        String alice = identity("aliceAddress");
        String bob = identity("bobAddress");
        String payout = identity("payoutAddress");
        TransactionBody paymentBody = TransactionBody.builder()
                .inputs(List.of(new TransactionInput(
                        input.transactionId(), input.index())))
                .outputs(List.of(
                        TransactionOutput.builder().address(alice)
                                .value(Value.fromCoin(
                                        DEPOSIT.subtract(BigInteger.valueOf(
                                                10_000_000)))).build(),
                        TransactionOutput.builder().address(bob)
                                .value(Value.fromCoin(
                                        BigInteger.valueOf(10_000_000))).build()))
                .fee(BigInteger.ZERO)
                .ttl(10_000_000L)
                .networkId(NetworkId.TESTNET)
                .build();
        SignedL2 payment = sign("alice", paymentBody,
                nonce("payment-domain", round));
        Path paymentArtifact = workspace.root().resolve(
                artifact("artifacts/l2/zk-payment", round, ".l2tx"));
        writeArtifact(paymentArtifact, payment.bytes());
        var paymentSubmitted = client.submit(payment.bytes());
        EutxoReceipt paymentReceipt = awaitReceipt(payment.transactionId());
        EutxoValidityTransition paymentTransition =
                awaitTransition(paymentReceipt);
        Path paymentTransitionFile = workspace.root().resolve(
                artifact("artifacts/proofs/payment-transition",
                        round, ".cbor"));
        writeArtifact(paymentTransitionFile,
                paymentTransition.canonicalBytes());
        EutxoOutpoint bobPayment = new EutxoOutpoint(
                payment.transactionId(), 1);
        EutxoWithdrawalDatum withdrawal = new EutxoWithdrawalDatum(
                EutxoWithdrawalDatum.ABI_VERSION,
                workspace.manifest().chainId(), 1, payout,
                nonce("withdrawal", round));
        TransactionBody body = TransactionBody.builder()
                .inputs(List.of(new TransactionInput(
                        bobPayment.transactionId(), bobPayment.index())))
                .outputs(List.of(
                        TransactionOutput.builder().address(bob)
                                .value(Value.fromCoin(
                                        BigInteger.valueOf(10_000_000)
                                                .subtract(WITHDRAWAL))).build(),
                        TransactionOutput.builder().address(payout)
                                .value(Value.fromCoin(WITHDRAWAL))
                                .inlineDatum(PlutusData.deserialize(
                                        withdrawal.encode())).build()))
                .fee(BigInteger.ZERO)
                .ttl(10_000_000L)
                .networkId(NetworkId.TESTNET)
                .build();
        SignedL2 withdrawalTransaction = sign(
                "bob", body, nonce("withdrawal-domain", round));
        byte[] transaction = withdrawalTransaction.bytes();
        String l2TransactionId = withdrawalTransaction.transactionId();
        Path artifact = workspace.root().resolve(
                artifact("artifacts/l2/zk-withdrawal", round, ".l2tx"));
        writeArtifact(artifact, transaction);
        var submitted = client.submit(transaction);
        EutxoReceipt receipt = awaitReceipt(l2TransactionId);
        EutxoValidityTransition transition = awaitTransition(receipt);
        Path transitionFile = workspace.root().resolve(
                artifact("artifacts/proofs/finalized-transition",
                        round, ".cbor"));
        writeArtifact(transitionFile, transition.canonicalBytes());
        var claim = transition.withdrawals().getFirst();
        journal.advance(operationId, operationId,
                current.requestDigest(), EutxoDemoJournal.State.STABLE,
                Map.of("messageId", submitted.messageId(),
                        "paymentMessageId", paymentSubmitted.messageId(),
                        "paymentTransactionId", payment.transactionId(),
                        "paymentArtifact", artifact(
                                "artifacts/l2/zk-payment",
                                round, ".l2tx"),
                        "paymentTransition", artifact(
                                "artifacts/proofs/payment-transition",
                                round, ".cbor"),
                        "transactionId", receipt.transactionId(),
                        "claimId", claim.claimId(),
                        "transition", artifact(
                                "artifacts/proofs/finalized-transition",
                                round, ".cbor"),
                        "previousRoot", HexFormat.of().formatHex(
                                transition.previousRoot())), null);
        String bridgeOperationId = id("bridge-transfer-v1", round);
        EutxoDemoJournal.Entry bridgeEntry = entry(bridgeOperationId);
        journal.advance(bridgeOperationId, bridgeOperationId,
                bridgeEntry.requestDigest(), EutxoDemoJournal.State.STABLE,
                Map.of("messageId", submitted.messageId(),
                        "transactionId", receipt.transactionId(),
                        "claimId", claim.claimId(),
                        "artifact", artifact(
                                "artifacts/l2/zk-withdrawal",
                                round, ".l2tx")), null);
    }

    private void prove(int round) throws Exception {
        String operationId = id("zk-proof-v1", round);
        EutxoDemoJournal.Entry current = entry(operationId);
        if (current.state().ordinal() >= EutxoDemoJournal.State.VERIFIED.ordinal()) {
            return;
        }
        EutxoDemoJournal.Entry transfer = entry(id("zk-transfer-v1", round));
        EutxoValidityLifecycle lifecycle =
                new EutxoValidityLifecycle(workspace.project());
        if (!"VALIDITY_DOCTOR_PASSED".equals(lifecycle.doctor().status())) {
            throw new IllegalStateException(
                    "EUTXO_ZK_DEMO_CEREMONY_REQUIRED");
        }
        var result = lifecycle.prove(
                List.of(
                        workspace.root().resolve(required(
                                transfer, "paymentTransition")),
                        workspace.root().resolve(required(
                                transfer, "transition"))),
                HexFormat.of().formatHex(EutxoValidityTransition.decode(
                        Files.readAllBytes(workspace.root().resolve(required(
                                transfer, "paymentTransition"))))
                        .previousRoot()));
        String proofId = String.valueOf(result.details().get("proofId"));
        workspace.journal().advance(operationId, operationId,
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

    private String id(String base, int round) {
        return round == 1 ? base : base + "-r" + round;
    }

    private String artifact(String base, int round, String suffix) {
        return round == 1 ? base + suffix : base + "-r" + round + suffix;
    }

    private byte[] nonce(String purpose, int round) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(
                    ("yano-eutxo-zk-demo\n" + workspace.manifest().chainId()
                            + "\n" + purpose + "\n" + round)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private void writeArtifact(Path path, byte[] bytes) throws IOException {
        if (Files.exists(path)) {
            if (!java.util.Arrays.equals(Files.readAllBytes(path), bytes)) {
                throw new IllegalStateException(
                        "retained demo artifact differs from the planned operation: "
                                + workspace.root().relativize(path));
            }
            return;
        }
        Files.write(path, bytes, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
    }

    private void requireReady() {
        if (!cluster.status().ready()) {
            throw new IllegalStateException("DEMO_CLUSTER_NOT_READY");
        }
    }

    private Map<String, EutxoL2KeyBinding> keyBindings()
            throws Exception {
        Map<String, EutxoL2KeyBinding> result = new LinkedHashMap<>();
        for (String user : List.of("alice", "bob")) {
            byte[] envelope = Files.readAllBytes(workspace.root().resolve(
                    "secrets/l2/" + user + "-session-key.enc"));
            char[] password = Files.readString(workspace.root().resolve(
                    "secrets/l2/" + user + "-session-key.password"))
                    .trim().toCharArray();
            try (EutxoL2SessionKey key =
                         EutxoL2SessionKey.decrypt(envelope, password)) {
                result.put(user, new EutxoL2KeyBinding(
                        EutxoZkAuthorizationProfile.JUBJUB_DEVELOPMENT_V1.id(),
                        1, key.publicKey()));
            } finally {
                java.util.Arrays.fill(password, '\0');
                java.util.Arrays.fill(envelope, (byte) 0);
            }
        }
        return Map.copyOf(result);
    }

    private SignedL2 sign(
            String user,
            TransactionBody body,
            byte[] nonce) throws Exception {
        EutxoZkAuthorizationProfile authorization =
                EutxoZkAuthorizationProfile.JUBJUB_DEVELOPMENT_V1;
        EutxoL2Domain domain = new EutxoL2Domain(
                workspace.manifest().chainId(), "devnet",
                EutxoProfile.V1.digestHex(),
                EutxoZkProfile.Z3_VALIDITY_SETTLEMENT.digestHex(),
                authorization.id(), authorization.digestHex(),
                nonce, 10_000_000L);
        byte[] envelope = Files.readAllBytes(workspace.root().resolve(
                "secrets/l2/" + user + "-session-key.enc"));
        char[] password = Files.readString(workspace.root().resolve(
                "secrets/l2/" + user + "-session-key.password"))
                .trim().toCharArray();
        try (EutxoL2SessionKey key =
                     EutxoL2SessionKey.decrypt(envelope, password)) {
            String credential = HexFormat.of().formatHex(
                    new Address(identity(user + "Address"))
                            .getPaymentCredentialHash().orElseThrow());
            var transaction = EutxoL2TransactionBuilder.sign(
                    domain, body, List.of(
                            new EutxoL2TransactionBuilder.Signer(
                                    credential, 1, List.of(0), key)));
            return new SignedL2(
                    transaction.transactionId(),
                    transaction.canonicalBytes());
        } finally {
            java.util.Arrays.fill(password, '\0');
            java.util.Arrays.fill(envelope, (byte) 0);
        }
    }

    private record SignedL2(String transactionId, byte[] bytes) {
        private SignedL2 {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
