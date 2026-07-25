package com.bloxbean.cardano.yano.appchain.eutxo.demo;

import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.crypto.VerificationKey;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.TransactionSigner;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptPubkey;
import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.eutxo.client.EutxoClient;
import com.bloxbean.cardano.yano.appchain.eutxo.client.EutxoKeyWallet;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoDepositRecord;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2KeyBinding;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoReceipt;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoSettlementDatum;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoVaultDatum;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalClaim;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalDatum;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalRecord;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Disposable devnet bridge workflow. The native-script vault intentionally
 * models federated demo custody; it is not a production validator deployment.
 */
public final class EutxoBridgeDemoWorkflow {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final BigInteger DEPOSIT = BigInteger.valueOf(20_000_000);
    private static final BigInteger BOB_DEPOSIT = BigInteger.valueOf(10_000_000);
    private static final BigInteger PAYMENT = BigInteger.valueOf(10_000_000);
    private static final BigInteger WITHDRAWAL = BigInteger.valueOf(3_000_000);
    private static final long BRIDGE_EPOCH = 1;
    private final EutxoDemoWorkspace workspace;
    private final EutxoDemoCluster cluster;
    private final BackendService backend;
    private final QuickTxBuilder quickTx;
    private final UtxoSupplier utxos;
    private final EutxoClient eutxo;
    private final Map<String, EutxoL2KeyBinding> keyBindings;

    public EutxoBridgeDemoWorkflow(
            EutxoDemoWorkspace workspace,
            EutxoDemoCluster cluster) {
        this(workspace, cluster, Map.of());
    }

    public EutxoBridgeDemoWorkflow(
            EutxoDemoWorkspace workspace,
            EutxoDemoCluster cluster,
            Map<String, EutxoL2KeyBinding> keyBindings) {
        this.workspace = workspace;
        this.cluster = cluster;
        this.keyBindings = Map.copyOf(keyBindings);
        String api = cluster.apiBase() + "/";
        backend = new BFBackendService(api, "demo");
        quickTx = new QuickTxBuilder(backend);
        utxos = new DefaultUtxoSupplier(backend.getUtxoService());
        eutxo = new EutxoClient(AppChainClient.builder(cluster.apiBase())
                .chainId(workspace.manifest().chainId()).build());
    }

    public EutxoDemoResult execute(String requested) throws Exception {
        return execute(requested, 1);
    }

    public EutxoDemoResult execute(String requested, int count) throws Exception {
        requireReady();
        if (count < 1 || count > 16) {
            throw new IllegalArgumentException("round-trip count must be between 1 and 16");
        }
        for (int round = 1; round <= count; round++) {
            if (List.of("fund", "deposit", "transfer", "settle", "withdraw",
                    "reconcile", "verify", "round-trip").contains(requested)) {
                fund(round);
            }
            if (List.of("deposit", "transfer", "settle", "withdraw",
                    "reconcile", "verify", "round-trip").contains(requested)) {
                deposit(round);
            }
            if (List.of("transfer", "settle", "withdraw",
                    "reconcile", "verify", "round-trip").contains(requested)) {
                transfer(round);
            }
            if (List.of("settle", "withdraw",
                    "reconcile", "verify", "round-trip").contains(requested)) {
                settle(round);
            }
            if (List.of("reconcile", "verify", "round-trip").contains(requested)) {
                verify(round);
            }
        }
        Map<String, Object> fields = reportFields();
        fields.put("trustBoundary",
                "federated disposable-devnet native-script custody; no validity proof");
        fields.put("requestedOperation", requested);
        fields.put("targetRounds", count);
        fields.put("completedRounds", count);
        return EutxoDemoResult.of(
                "round-trip".equals(requested)
                        ? "EUTXO_BRIDGE_DEMO_ROUND_TRIP_PASS"
                        : "EUTXO_BRIDGE_DEMO_OPERATION_PASS",
                fields);
    }

    private void fund(int round) throws Exception {
        String operationId = id("bridge-fund-v1", round);
        EutxoDemoJournal.Entry entry = entry(operationId);
        if (atLeast(entry, EutxoDemoJournal.State.STABLE)) return;
        Map<String, String> transactions = new LinkedHashMap<>();
        for (String user : List.of("alice", "bob", "operator")) {
            String address = identity(user + "Address");
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(cluster.apiBase() + "/devnet/fund"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    JSON.writeValueAsString(Map.of(
                                            "address", address, "ada", 100))))
                            .build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("DEVNET_FUND_FAILED");
            }
            String tx = JSON.readTree(response.body()).path("tx_hash").asText();
            awaitUtxo(tx, address);
            transactions.put(user + "TransactionId", tx);
        }
        advance(operationId, EutxoDemoJournal.State.STABLE,
                transactions);
    }

    private void deposit(int round) throws Exception {
        String operationId = id("bridge-deposit-v1", round);
        EutxoDemoJournal.Entry entry = entry(operationId);
        if (atLeast(entry, EutxoDemoJournal.State.STABLE)) return;
        EutxoDepositRecord alice = depositUser(
                "alice", DEPOSIT, round);
        EutxoDepositRecord bob = depositUser(
                "bob", BOB_DEPOSIT, round);
        advance(operationId, EutxoDemoJournal.State.STABLE, Map.of(
                "mirroredOutpoint", alice.mirroredOutpoint().toString(),
                "aliceMirroredOutpoint", alice.mirroredOutpoint().toString(),
                "bobMirroredOutpoint", bob.mirroredOutpoint().toString()));
    }

    private EutxoDepositRecord depositUser(
            String user,
            BigInteger lovelace,
            int round) throws Exception {
        String operationId = id("bridge-deposit-" + user + "-v2", round);
        EutxoDemoJournal.Entry entry = entry(operationId);
        if (atLeast(entry, EutxoDemoJournal.State.STABLE)) {
            return eutxo.depositSnapshot(EutxoOutpoint.parse(
                            required(entry, "acceptedOutpoint")))
                    .value().orElseThrow(() -> new IllegalStateException(
                            "DEPOSIT_RECORD_UNAVAILABLE"));
        }
        EutxoKeyWallet depositor = wallet(user + "Wallet");
        String vaultAddress = identity("vaultAddress");
        String stagingTx = submit(new Tx()
                .payToAddress(depositor.address(), Amount.lovelace(lovelace))
                .from(depositor.address()), depositor);
        Utxo staged = awaitUtxo(stagingTx, depositor.address());
        EutxoOutpoint staging = outpoint(staged);
        EutxoVaultDatum datum = new EutxoVaultDatum(
                EutxoVaultDatum.ABI_VERSION,
                workspace.manifest().chainId(),
                depositor.address(),
                nonce("deposit-" + user, round),
                staging,
                10_000_000L,
                paymentCredential(depositor.address()),
                keyBindings.getOrDefault(user, EutxoL2KeyBinding.none()));
        String acceptedTx = submit(new Tx()
                .collectFrom(List.of(staged))
                .payToContract(vaultAddress, Amount.lovelace(lovelace),
                        PlutusData.deserialize(datum.encode()))
                .from(depositor.address()), depositor);
        Utxo accepted = awaitUtxo(acceptedTx, vaultAddress);
        EutxoOutpoint acceptedOutpoint = outpoint(accepted);
        EutxoDepositRecord mirrored = awaitDeposit(acceptedOutpoint);
        advance(operationId, EutxoDemoJournal.State.STABLE, Map.of(
                "stagingTransactionId", stagingTx,
                "acceptedTransactionId", acceptedTx,
                "acceptedOutpoint", acceptedOutpoint.toString(),
                "mirroredOutpoint", mirrored.mirroredOutpoint().toString()));
        return mirrored;
    }

    private void transfer(int round) throws Exception {
        String operationId = id("bridge-transfer-v1", round);
        EutxoDemoJournal.Entry entry = entry(operationId);
        if (atLeast(entry, EutxoDemoJournal.State.STABLE)) return;
        EutxoOutpoint input = EutxoOutpoint.parse(
                required(entry(id("bridge-deposit-v1", round)), "mirroredOutpoint"));
        EutxoKeyWallet alice = wallet("aliceWallet");
        EutxoKeyWallet bob = wallet("bobWallet");
        TransactionBody paymentBody = TransactionBody.builder()
                .inputs(List.of(new TransactionInput(
                        input.transactionId(), input.index())))
                .outputs(List.of(
                        TransactionOutput.builder()
                                .address(alice.address())
                                .value(Value.fromCoin(
                                        DEPOSIT.subtract(PAYMENT))).build(),
                        TransactionOutput.builder()
                                .address(bob.address())
                                .value(Value.fromCoin(PAYMENT)).build()))
                .fee(BigInteger.ZERO)
                .ttl(10_000_000L)
                .networkId(com.bloxbean.cardano.client.spec.NetworkId.TESTNET)
                .build();
        Transaction paymentUnsigned = Transaction.builder()
                .body(paymentBody).witnessSet(new TransactionWitnessSet())
                .isValid(true).build();
        byte[] paymentCbor = TransactionSigner.INSTANCE.sign(
                paymentUnsigned, alice.signingKey()).serialize();
        var paymentSubmission = eutxo.submit(paymentCbor);
        String paymentTransactionId =
                com.bloxbean.cardano.client.transaction.util.TransactionUtil
                        .getTxHash(paymentCbor);
        awaitReceipt(paymentTransactionId);
        EutxoOutpoint bobPayment = new EutxoOutpoint(paymentTransactionId, 1);
        EutxoWithdrawalDatum datum = new EutxoWithdrawalDatum(
                EutxoWithdrawalDatum.ABI_VERSION,
                workspace.manifest().chainId(),
                BRIDGE_EPOCH,
                identity("payoutAddress"),
                nonce("withdrawal", round));
        TransactionBody body = TransactionBody.builder()
                .inputs(List.of(new TransactionInput(
                        bobPayment.transactionId(), bobPayment.index())))
                .outputs(List.of(
                        TransactionOutput.builder()
                                .address(bob.address())
                                .value(Value.fromCoin(PAYMENT.subtract(WITHDRAWAL)))
                                .build(),
                        TransactionOutput.builder()
                                .address(identity("payoutAddress"))
                                .value(Value.fromCoin(WITHDRAWAL))
                                .inlineDatum(PlutusData.deserialize(datum.encode()))
                                .build()))
                .fee(BigInteger.ZERO)
                .ttl(10_000_000L)
                .networkId(com.bloxbean.cardano.client.spec.NetworkId.TESTNET)
                .build();
        Transaction unsigned = Transaction.builder()
                .body(body)
                .witnessSet(new TransactionWitnessSet())
                .isValid(true).build();
        Transaction signed = TransactionSigner.INSTANCE.sign(
                unsigned, bob.signingKey());
        byte[] cbor = signed.serialize();
        Path artifact = workspace.root().resolve(
                artifact("artifacts/l2/bridge-withdrawal", round, ".cbor"));
        writeArtifact(artifact, cbor);
        var submitted = eutxo.submit(cbor);
        EutxoReceipt receipt = awaitReceipt(
                com.bloxbean.cardano.client.transaction.util.TransactionUtil
                        .getTxHash(cbor));
        EutxoWithdrawalClaim claim = awaitClaim(receipt, round);
        advance(operationId, EutxoDemoJournal.State.STABLE, Map.of(
                "paymentMessageId", paymentSubmission.messageId(),
                "paymentTransactionId", paymentTransactionId,
                "messageId", submitted.messageId(),
                "transactionId", claim.withdrawalOutpoint().transactionId(),
                "claimId", claim.claimId(),
                "artifact", artifact("artifacts/l2/bridge-withdrawal",
                        round, ".cbor")));
    }

    private void settle(int round) throws Exception {
        String operationId = id("bridge-settle-v1", round);
        EutxoDemoJournal.Entry entry = entry(operationId);
        if (atLeast(entry, EutxoDemoJournal.State.STABLE)) return;
        String claimId = required(entry(id("bridge-transfer-v1", round)), "claimId");
        EutxoWithdrawalClaim claim = eutxo.withdrawalSnapshot(claimId).value()
                .orElseThrow(() -> new IllegalStateException(
                        "WITHDRAWAL_CLAIM_UNAVAILABLE")).claim();
        EutxoKeyWallet operator = wallet("operatorWallet");
        ScriptPubkey vault = vaultScript(operator);
        Utxo vaultInput = utxos.getAll(identity("vaultAddress")).stream()
                .filter(value -> lovelace(value).compareTo(WITHDRAWAL) > 0)
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "VAULT_INPUT_UNAVAILABLE"));
        BigInteger continuing = lovelace(vaultInput).subtract(WITHDRAWAL)
                .subtract(BigInteger.valueOf(500_000));
        EutxoSettlementDatum marker = EutxoSettlementDatum.forAddress(
                EutxoSettlementDatum.ABI_VERSION,
                workspace.manifest().chainId(),
                BRIDGE_EPOCH,
                claimId,
                identity("payoutAddress"),
                WITHDRAWAL);
        String tx = submit(new Tx()
                .collectFrom(List.of(vaultInput))
                .payToAddress(identity("payoutAddress"), Amount.lovelace(WITHDRAWAL))
                .payToContract(identity("vaultAddress"),
                        Amount.lovelace(continuing),
                        PlutusData.deserialize(marker.encode()))
                .attachNativeScript(vault)
                .from(operator.address()), operator);
        awaitUtxo(tx, identity("payoutAddress"));
        EutxoWithdrawalRecord confirmed = awaitWithdrawal(claimId);
        advance(operationId, EutxoDemoJournal.State.STABLE, Map.of(
                "transactionId", tx,
                "claimId", confirmed.claim().claimId(),
                "status", confirmed.status().name()));
    }

    private void verify(int round) throws Exception {
        String operationId = id("bridge-verify-v1", round);
        if (atLeast(entry(operationId), EutxoDemoJournal.State.VERIFIED)) {
            return;
        }
        EutxoDemoJournal.Entry settled = entry(id("bridge-settle-v1", round));
        if (!atLeast(settled, EutxoDemoJournal.State.STABLE)) {
            throw new IllegalStateException("BRIDGE_ROUND_TRIP_INCOMPLETE");
        }
        EutxoWithdrawalRecord record = eutxo.withdrawalSnapshot(
                        required(settled, "claimId")).value()
                .orElseThrow(() -> new IllegalStateException(
                        "WITHDRAWAL_RECORD_UNAVAILABLE"));
        if (record.status() != EutxoWithdrawalRecord.Status.CONFIRMED) {
            throw new IllegalStateException("WITHDRAWAL_NOT_CONFIRMED");
        }
        advance(operationId, EutxoDemoJournal.State.VERIFIED, Map.of(
                "claimId", record.claim().claimId(),
                "status", record.status().name()));
    }

    private EutxoWithdrawalClaim awaitClaim(EutxoReceipt receipt, int round)
            throws InterruptedException {
        EutxoWithdrawalClaim expected = new EutxoWithdrawalClaim(
                EutxoWithdrawalClaim.ABI_VERSION,
                workspace.manifest().chainId(),
                BRIDGE_EPOCH,
                new EutxoOutpoint(receipt.transactionId(), 1),
                identity("payoutAddress"),
                WITHDRAWAL,
                nonce("withdrawal", round),
                round - 1L,
                receipt.appHeight());
        for (int i = 0; i < 120; i++) {
            try {
                var record = eutxo.withdrawalSnapshot(
                        expected.claimId()).value();
                if (record.isPresent()) {
                    return record.orElseThrow().claim();
                }
            } catch (RuntimeException ignored) {
                // Withdrawal query is asynchronous.
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("WITHDRAWAL_CLAIM_TIMEOUT");
    }

    private EutxoDepositRecord awaitDeposit(EutxoOutpoint accepted)
            throws InterruptedException {
        for (int i = 0; i < 240; i++) {
            try {
                var record = eutxo.depositSnapshot(accepted).value();
                if (record.isPresent()) return record.orElseThrow();
            } catch (RuntimeException ignored) {
                // Stable L1 observation is asynchronous.
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("DEPOSIT_STABILITY_TIMEOUT");
    }

    private EutxoReceipt awaitReceipt(String transactionId)
            throws InterruptedException {
        for (int i = 0; i < 180; i++) {
            try {
                var receipt = eutxo.transaction(transactionId);
                if (receipt.isPresent()) {
                    if (receipt.orElseThrow().status() != EutxoReceipt.Status.ACCEPTED) {
                        throw new IllegalStateException("L2_TRANSACTION_REJECTED");
                    }
                    return receipt.orElseThrow();
                }
            } catch (IllegalStateException failure) {
                throw failure;
            } catch (RuntimeException ignored) {
                // Finalization is asynchronous.
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("L2_FINALITY_TIMEOUT");
    }

    private EutxoWithdrawalRecord awaitWithdrawal(String claimId)
            throws InterruptedException {
        for (int i = 0; i < 240; i++) {
            try {
                EutxoWithdrawalRecord record =
                        eutxo.withdrawalSnapshot(claimId).value().orElse(null);
                if (record != null
                        && record.status() == EutxoWithdrawalRecord.Status.CONFIRMED) {
                    return record;
                }
            } catch (RuntimeException ignored) {
                // L1 confirmation observation is asynchronous.
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("WITHDRAWAL_RECONCILIATION_TIMEOUT");
    }

    private String submit(Tx transaction, EutxoKeyWallet wallet)
            throws Exception {
        Result<String> result = quickTx.compose(transaction)
                .withSigner(SignerProviders.signerFrom(wallet.signingKey()))
                .complete();
        if (!result.isSuccessful()) {
            String diagnostic = String.valueOf(result.getResponse())
                    .replaceAll("[\\r\\n]+", " ");
            if (diagnostic.length() > 240) {
                diagnostic = diagnostic.substring(0, 240);
            }
            throw new IllegalStateException(
                    "CARDANO_TRANSACTION_FAILED: " + diagnostic);
        }
        awaitTransaction(result.getValue());
        return result.getValue();
    }

    private void awaitTransaction(String transactionId) throws Exception {
        for (int i = 0; i < 120; i++) {
            try {
                if (backend.getTransactionService().getTransaction(
                        transactionId).isSuccessful()) return;
            } catch (RuntimeException ignored) {
                // Retry.
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("CARDANO_TRANSACTION_TIMEOUT");
    }

    private Utxo awaitUtxo(String transactionId, String address)
            throws InterruptedException {
        for (int i = 0; i < 120; i++) {
            try {
                Utxo found = utxos.getAll(address).stream()
                        .filter(value -> transactionId.equals(value.getTxHash()))
                        .findFirst().orElse(null);
                if (found != null) return found;
            } catch (RuntimeException ignored) {
                // Retry.
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("CARDANO_UTXO_TIMEOUT");
    }

    private EutxoKeyWallet wallet(String reference) throws Exception {
        byte[] seed = workspace.readSecretSeed(reference);
        try {
            return EutxoKeyWallet.fromSeed(seed);
        } finally {
            java.util.Arrays.fill(seed, (byte) 0);
        }
    }

    private ScriptPubkey vaultScript(EutxoKeyWallet wallet) {
        try {
            return ScriptPubkey.create(VerificationKey.create(
                    wallet.verificationKey().getBytes()));
        } catch (Exception failure) {
            throw new IllegalStateException("DEMO_VAULT_IDENTITY_INVALID", failure);
        }
    }

    private String identity(String name) {
        String value = workspace.manifest().publicIdentities().get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("DEMO_IDENTITY_UNAVAILABLE");
        }
        return value;
    }

    private void requireReady() {
        if (!cluster.status().ready()) {
            throw new IllegalStateException("DEMO_CLUSTER_NOT_READY");
        }
    }

    private EutxoDemoJournal.Entry entry(String id) throws Exception {
        EutxoDemoJournal.Entry entry = workspace.journal().read().get(id);
        return entry == null
                ? workspace.journal().plan(id, id, id + "\n"
                + workspace.manifest().chainId()) : entry;
    }

    private void advance(
            String id,
            EutxoDemoJournal.State state,
            Map<String, String> fields) throws Exception {
        EutxoDemoJournal.Entry old = entry(id);
        workspace.journal().advance(id, id, old.requestDigest(),
                state, fields, null);
    }

    private static boolean atLeast(
            EutxoDemoJournal.Entry entry,
            EutxoDemoJournal.State state) {
        return entry.state().ordinal() >= state.ordinal();
    }

    private static String required(
            EutxoDemoJournal.Entry entry,
            String key) {
        String value = entry.publicArtifacts().get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("DEMO_JOURNAL_INCOMPLETE");
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
                    ("yano-eutxo-demo\n" + workspace.manifest().chainId()
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
        Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
    }

    private Map<String, Object> reportFields() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenario", "bridge");
        result.put("workspace", workspace.root().toString());
        result.put("chainId", workspace.manifest().chainId());
        result.put("operations", workspace.journal().read());
        return result;
    }

    private static EutxoOutpoint outpoint(Utxo utxo) {
        return new EutxoOutpoint(utxo.getTxHash(), utxo.getOutputIndex());
    }

    private static BigInteger lovelace(Utxo utxo) {
        return utxo.getAmount().stream()
                .filter(amount -> "lovelace".equals(amount.getUnit()))
                .map(Amount::getQuantity)
                .findFirst().orElse(BigInteger.ZERO);
    }

    private static byte[] paymentCredential(String address) {
        return new Address(address).getPaymentCredentialHash()
                .orElseThrow(() -> new IllegalArgumentException(
                        "demo user address has no payment credential"));
    }
}
