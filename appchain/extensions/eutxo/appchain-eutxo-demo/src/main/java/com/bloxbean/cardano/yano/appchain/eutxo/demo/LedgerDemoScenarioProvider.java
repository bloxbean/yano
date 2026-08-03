package com.bloxbean.cardano.yano.appchain.eutxo.demo;

import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.client.ProofVerifier;
import com.bloxbean.cardano.yano.appchain.eutxo.client.EutxoClient;
import com.bloxbean.cardano.yano.appchain.eutxo.client.EutxoGenesisOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.client.EutxoKeyWallet;
import com.bloxbean.cardano.yano.appchain.eutxo.client.EutxoPaymentTransactionBuilder;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoReceipt;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoRecord;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Maintained no-real-funds virtual-ledger scenario. */
public final class LedgerDemoScenarioProvider implements EutxoDemoScenarioProvider {
    private static final long GENESIS_LOVELACE = 100_000_000L;
    private static final long PAYMENT_LOVELACE = 25_000_000L;
    private static final ObjectMapper JSON = new ObjectMapper();

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

    @Override
    public void setup(EutxoDemoWorkspace workspace, EutxoDemoOptions options)
            throws Exception {
        new EutxoDemoCluster(workspace).generateProject(recipe(), Map.of(
                "eutxoGenesisAddress",
                workspace.manifest().publicIdentities().get("ledgerAddress"),
                "eutxoGenesisLovelace", Long.toString(GENESIS_LOVELACE)));
    }

    @Override
    public EutxoDemoResult execute(
            String operation,
            EutxoDemoWorkspace workspace,
            EutxoDemoOptions options) throws Exception {
        if (options.count() != 1 && List.of(
                "transfer", "round-trip", "verify", "reconcile").contains(operation)) {
            throw new IllegalArgumentException(
                    "--count greater than one is currently supported by bridge and zk scenarios");
        }
        EutxoDemoCluster cluster = new EutxoDemoCluster(workspace);
        return switch (operation) {
            case "start", "up" -> {
                cluster.start();
                yield clusterResult("EUTXO_DEMO_CLUSTER_STARTED", workspace, cluster);
            }
            case "stop" -> {
                cluster.stop();
                yield clusterResult("EUTXO_DEMO_CLUSTER_STOPPED", workspace, cluster);
            }
            case "status" ->
                    clusterResult("EUTXO_DEMO_STATUS", workspace, cluster);
            case "transfer", "round-trip" -> roundTrip(workspace, cluster);
            case "verify", "reconcile" -> verify(workspace, cluster);
            default -> throw new UnsupportedOperationException(
                    operation + " is not supported by scenario ledger");
        };
    }

    private static EutxoDemoResult roundTrip(
            EutxoDemoWorkspace workspace,
            EutxoDemoCluster cluster) throws Exception {
        requireReady(cluster);
        String requestDigest = sha256("ledger-round-trip-v1\n"
                + workspace.manifest().chainId() + "\n"
                + workspace.manifest().publicIdentities().get("ledgerAddress") + "\n"
                + workspace.manifest().publicIdentities().get("recipientAddress"));
        String operationId = "ledger-round-trip-v1";
        EutxoDemoJournal journal = workspace.journal();
        EutxoDemoJournal.Entry current = journal.read().get(operationId);
        if (current == null) {
            current = journal.plan(operationId, "round-trip", requestDigest);
        } else if (!requestDigest.equals(current.requestDigest())) {
            throw new IllegalStateException("ledger round-trip request identity changed");
        }
        if (current.state() == EutxoDemoJournal.State.VERIFIED) {
            return pass(workspace, current.publicArtifacts());
        }

        EutxoClient client = client(workspace, cluster);
        Path transactionFile = workspace.root().resolve(
                "artifacts/l2/ledger-transfer.cbor");
        byte[] transactionCbor;
        String transactionId;
        if (current.state().ordinal() < EutxoDemoJournal.State.BUILT.ordinal()) {
            String source = workspace.manifest().publicIdentities().get("ledgerAddress");
            List<EutxoRecord> inputs = client.utxos(source);
            EutxoOutpoint genesis = inputs.stream()
                    .filter(record -> record.origin() == EutxoRecord.Origin.GENESIS)
                    .map(EutxoRecord::outpoint)
                    .findFirst()
                    .orElseGet(() -> EutxoGenesisOutpoint.singleOutput(
                            source, BigInteger.valueOf(GENESIS_LOVELACE)));
            byte[] seed = workspace.readSecretSeed("ledgerWallet");
            try {
                var transaction = EutxoPaymentTransactionBuilder.signedPayment(
                        genesis, EutxoKeyWallet.fromSeed(seed),
                        List.of(
                                new EutxoPaymentTransactionBuilder.Payment(
                                        workspace.manifest().publicIdentities()
                                                .get("recipientAddress"),
                                        BigInteger.valueOf(PAYMENT_LOVELACE)),
                                new EutxoPaymentTransactionBuilder.Payment(
                                        source,
                                        BigInteger.valueOf(
                                                GENESIS_LOVELACE - PAYMENT_LOVELACE))),
                        0, 10_000_000);
                transactionCbor = transaction.serialize();
            } finally {
                java.util.Arrays.fill(seed, (byte) 0);
            }
            Files.write(transactionFile, transactionCbor,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            transactionId = TransactionUtil.getTxHash(transactionCbor);
            current = journal.advance(operationId, "round-trip", requestDigest,
                    EutxoDemoJournal.State.BUILT, Map.of(
                            "transactionId", transactionId,
                            "transactionDigest", sha256(transactionCbor),
                            "transactionArtifact", "artifacts/l2/ledger-transfer.cbor"),
                    null);
        } else {
            transactionCbor = Files.readAllBytes(transactionFile);
            transactionId = current.publicArtifacts().get("transactionId");
            if (!sha256(transactionCbor).equals(
                    current.publicArtifacts().get("transactionDigest"))) {
                throw new IllegalStateException("built L2 transaction digest changed");
            }
        }

        if (current.state().ordinal() < EutxoDemoJournal.State.SUBMITTED.ordinal()) {
            AppChainClient.SubmitResult submitted = client.submit(transactionCbor);
            current = journal.advance(operationId, "round-trip", requestDigest,
                    EutxoDemoJournal.State.SUBMITTED,
                    Map.of("messageId", submitted.messageId()), null);
        }
        EutxoReceipt receipt = awaitReceipt(client, transactionId, Duration.ofSeconds(90));
        EutxoOutpoint recipient = new EutxoOutpoint(transactionId, 0);
        var snapshot = client.utxoSnapshot(recipient);
        if (snapshot.value().isEmpty()) {
            throw new IllegalStateException("recipient EUTxO is not finalized");
        }
        current = journal.advance(operationId, "round-trip", requestDigest,
                EutxoDemoJournal.State.OBSERVED, Map.of(
                        "appHeight", Long.toString(snapshot.committedHeight()),
                        "stateRoot", snapshot.stateRootHex(),
                        "receiptCode", receipt.code(),
                        "outpoint", recipient.toString()), null);
        AppChainClient.Proof proof = client.proof(recipient)
                .orElseThrow(() -> new IllegalStateException(
                        "recipient MPF proof is unavailable"));
        if (!ProofVerifier.verify(proof, snapshot.stateRootHex())) {
            throw new IllegalStateException("recipient MPF proof is invalid");
        }
        current = journal.advance(operationId, "round-trip", requestDigest,
                EutxoDemoJournal.State.VERIFIED,
                Map.of("proofRoot", proof.stateRootHex()), null);
        writeReport(workspace, current.publicArtifacts());
        return pass(workspace, current.publicArtifacts());
    }

    private static EutxoDemoResult verify(
            EutxoDemoWorkspace workspace,
            EutxoDemoCluster cluster) throws Exception {
        requireReady(cluster);
        EutxoDemoJournal.Entry entry =
                workspace.journal().read().get("ledger-round-trip-v1");
        if (entry == null || entry.state() != EutxoDemoJournal.State.VERIFIED) {
            throw new IllegalStateException("ledger round trip has not completed");
        }
        EutxoOutpoint outpoint =
                EutxoOutpoint.parse(entry.publicArtifacts().get("outpoint"));
        EutxoClient client = client(workspace, cluster);
        AppChainClient.Proof proof = client.proof(outpoint)
                .orElseThrow(() -> new IllegalStateException("MPF proof is unavailable"));
        if (!ProofVerifier.verify(proof, entry.publicArtifacts().get("stateRoot"))) {
            throw new IllegalStateException("stored ledger result no longer verifies");
        }
        return pass(workspace, entry.publicArtifacts());
    }

    private static EutxoClient client(
            EutxoDemoWorkspace workspace,
            EutxoDemoCluster cluster) {
        return new EutxoClient(AppChainClient.builder(cluster.apiBase())
                .chainId(workspace.manifest().chainId()).build());
    }

    private static EutxoReceipt awaitReceipt(
            EutxoClient client,
            String transactionId,
            Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<EutxoReceipt> receipt = client.transaction(transactionId);
            if (receipt.isPresent()) {
                if (receipt.get().status() != EutxoReceipt.Status.ACCEPTED) {
                    throw new IllegalStateException(
                            "L2 transaction was rejected: " + receipt.get().code());
                }
                return receipt.get();
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("L2 transaction did not finalize before timeout");
    }

    private static EutxoDemoResult clusterResult(
            String status,
            EutxoDemoWorkspace workspace,
            EutxoDemoCluster cluster) throws Exception {
        EutxoDemoCluster.ClusterStatus state = cluster.status();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("scenario", "ledger");
        fields.put("readyNodes", state.readyNodes());
        fields.put("expectedNodes", state.expectedNodes());
        fields.put("ready", state.ready());
        fields.put("project", workspace.project().toString());
        fields.put("trustBoundary", new LedgerDemoScenarioProvider().trustBoundary());
        fields.put("console", "http://127.0.0.1:"
                + workspace.manifest().httpPortBase() + "/ui/app-chain/");
        fields.put("operations", workspace.journal().read());
        return EutxoDemoResult.of(status, fields);
    }

    private static EutxoDemoResult pass(
            EutxoDemoWorkspace workspace,
            Map<String, String> artifacts) {
        Map<String, Object> fields = new LinkedHashMap<>(artifacts);
        fields.put("scenario", "ledger");
        fields.put("console", "http://127.0.0.1:"
                + workspace.manifest().httpPortBase() + "/ui/app-chain/");
        return EutxoDemoResult.of("EUTXO_LEDGER_DEMO_ROUND_TRIP_PASS", fields);
    }

    private static void writeReport(
            EutxoDemoWorkspace workspace,
            Map<String, String> artifacts) throws Exception {
        Path report = workspace.root().resolve("artifacts/reports/ledger.json");
        JSON.writerWithDefaultPrettyPrinter().writeValue(report.toFile(),
                Map.of("status", "EUTXO_LEDGER_DEMO_ROUND_TRIP_PASS",
                        "scenario", "ledger", "artifacts", artifacts));
    }

    private static void requireReady(EutxoDemoCluster cluster) {
        if (!cluster.status().ready()) {
            throw new IllegalStateException("demo cluster is not ready; run start first");
        }
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
