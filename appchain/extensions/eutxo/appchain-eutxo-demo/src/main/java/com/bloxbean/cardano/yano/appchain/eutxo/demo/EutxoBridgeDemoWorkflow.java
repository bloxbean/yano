package com.bloxbean.cardano.yano.appchain.eutxo.demo;

import com.bloxbean.cardano.client.api.UtxoSupplier;
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
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoReceipt;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoSettlementDatum;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoVaultDatum;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalClaim;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalDatum;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalRecord;
import com.fasterxml.jackson.databind.ObjectMapper;

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
final class EutxoBridgeDemoWorkflow {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final BigInteger DEPOSIT = BigInteger.valueOf(20_000_000);
    private static final BigInteger WITHDRAWAL = BigInteger.valueOf(3_000_000);
    private static final long BRIDGE_EPOCH = 1;
    private final EutxoDemoWorkspace workspace;
    private final EutxoDemoCluster cluster;
    private final BackendService backend;
    private final QuickTxBuilder quickTx;
    private final UtxoSupplier utxos;
    private final EutxoClient eutxo;

    EutxoBridgeDemoWorkflow(
            EutxoDemoWorkspace workspace,
            EutxoDemoCluster cluster) {
        this.workspace = workspace;
        this.cluster = cluster;
        String api = cluster.apiBase() + "/";
        backend = new BFBackendService(api, "demo");
        quickTx = new QuickTxBuilder(backend);
        utxos = new DefaultUtxoSupplier(backend.getUtxoService());
        eutxo = new EutxoClient(AppChainClient.builder(cluster.apiBase())
                .chainId(workspace.manifest().chainId()).build());
    }

    EutxoDemoResult execute(String requested) throws Exception {
        requireReady();
        if (List.of("fund", "deposit", "transfer", "settle", "withdraw",
                "reconcile", "verify", "round-trip").contains(requested)) {
            fund();
        }
        if (List.of("deposit", "transfer", "settle", "withdraw",
                "reconcile", "verify", "round-trip").contains(requested)) {
            deposit();
        }
        if (List.of("transfer", "settle", "withdraw",
                "reconcile", "verify", "round-trip").contains(requested)) {
            transfer();
        }
        if (List.of("settle", "withdraw",
                "reconcile", "verify", "round-trip").contains(requested)) {
            settle();
        }
        verify();
        Map<String, Object> fields = reportFields();
        fields.put("trustBoundary",
                "federated disposable-devnet native-script custody; no validity proof");
        fields.put("requestedOperation", requested);
        return EutxoDemoResult.of(
                "round-trip".equals(requested)
                        ? "EUTXO_BRIDGE_DEMO_ROUND_TRIP_PASS"
                        : "EUTXO_BRIDGE_DEMO_OPERATION_PASS",
                fields);
    }

    private void fund() throws Exception {
        EutxoDemoJournal.Entry entry = entry("bridge-fund-v1");
        if (atLeast(entry, EutxoDemoJournal.State.STABLE)) return;
        String address = identity("operatorAddress");
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
        advance("bridge-fund-v1", EutxoDemoJournal.State.STABLE,
                Map.of("transactionId", tx));
    }

    private void deposit() throws Exception {
        EutxoDemoJournal.Entry entry = entry("bridge-deposit-v1");
        if (atLeast(entry, EutxoDemoJournal.State.STABLE)) return;
        EutxoKeyWallet operator = wallet("ledgerWallet");
        ScriptPubkey vault = vaultScript(operator);
        String vaultAddress = identity("vaultAddress");
        String stagingTx = submit(new Tx()
                .payToAddress(operator.address(), Amount.lovelace(DEPOSIT))
                .from(operator.address()), operator);
        Utxo staged = awaitUtxo(stagingTx, operator.address());
        EutxoOutpoint staging = outpoint(staged);
        EutxoVaultDatum datum = new EutxoVaultDatum(
                EutxoVaultDatum.ABI_VERSION,
                workspace.manifest().chainId(),
                operator.address(),
                HexFormat.of().parseHex("ab".repeat(32)),
                staging,
                10_000_000L);
        String acceptedTx = submit(new Tx()
                .collectFrom(List.of(staged))
                .payToContract(vaultAddress, Amount.lovelace(DEPOSIT),
                        PlutusData.deserialize(datum.encode()))
                .from(operator.address()), operator);
        Utxo accepted = awaitUtxo(acceptedTx, vaultAddress);
        EutxoOutpoint acceptedOutpoint = outpoint(accepted);
        EutxoDepositRecord mirrored = awaitDeposit(acceptedOutpoint);
        advance("bridge-deposit-v1", EutxoDemoJournal.State.STABLE, Map.of(
                "stagingTransactionId", stagingTx,
                "acceptedTransactionId", acceptedTx,
                "acceptedOutpoint", acceptedOutpoint.toString(),
                "mirroredOutpoint", mirrored.mirroredOutpoint().toString()));
    }

    private void transfer() throws Exception {
        EutxoDemoJournal.Entry entry = entry("bridge-transfer-v1");
        if (atLeast(entry, EutxoDemoJournal.State.STABLE)) return;
        EutxoOutpoint input = EutxoOutpoint.parse(
                required(entry("bridge-deposit-v1"), "mirroredOutpoint"));
        EutxoKeyWallet operator = wallet("ledgerWallet");
        EutxoWithdrawalDatum datum = new EutxoWithdrawalDatum(
                EutxoWithdrawalDatum.ABI_VERSION,
                workspace.manifest().chainId(),
                BRIDGE_EPOCH,
                identity("payoutAddress"),
                HexFormat.of().parseHex("cd".repeat(32)));
        TransactionBody body = TransactionBody.builder()
                .inputs(List.of(new TransactionInput(
                        input.transactionId(), input.index())))
                .outputs(List.of(
                        TransactionOutput.builder()
                                .address(operator.address())
                                .value(Value.fromCoin(DEPOSIT.subtract(WITHDRAWAL)))
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
                unsigned, operator.signingKey());
        byte[] cbor = signed.serialize();
        Path artifact = workspace.root().resolve(
                "artifacts/l2/bridge-withdrawal.cbor");
        Files.write(artifact, cbor, StandardOpenOption.CREATE_NEW);
        var submitted = eutxo.submit(cbor);
        EutxoReceipt receipt = awaitReceipt(
                com.bloxbean.cardano.client.transaction.util.TransactionUtil
                        .getTxHash(cbor));
        EutxoWithdrawalClaim claim = awaitClaim(receipt);
        advance("bridge-transfer-v1", EutxoDemoJournal.State.STABLE, Map.of(
                "messageId", submitted.messageId(),
                "transactionId", claim.withdrawalOutpoint().transactionId(),
                "claimId", claim.claimId(),
                "artifact", "artifacts/l2/bridge-withdrawal.cbor"));
    }

    private void settle() throws Exception {
        EutxoDemoJournal.Entry entry = entry("bridge-settle-v1");
        if (atLeast(entry, EutxoDemoJournal.State.STABLE)) return;
        String claimId = required(entry("bridge-transfer-v1"), "claimId");
        EutxoWithdrawalClaim claim = eutxo.withdrawalSnapshot(claimId).value()
                .orElseThrow(() -> new IllegalStateException(
                        "WITHDRAWAL_CLAIM_UNAVAILABLE")).claim();
        EutxoKeyWallet operator = wallet("ledgerWallet");
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
        advance("bridge-settle-v1", EutxoDemoJournal.State.STABLE, Map.of(
                "transactionId", tx,
                "claimId", confirmed.claim().claimId(),
                "status", confirmed.status().name()));
    }

    private void verify() throws Exception {
        EutxoDemoJournal.Entry settled = entry("bridge-settle-v1");
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
        advance("bridge-verify-v1", EutxoDemoJournal.State.VERIFIED, Map.of(
                "claimId", record.claim().claimId(),
                "status", record.status().name()));
    }

    private EutxoWithdrawalClaim awaitClaim(EutxoReceipt receipt)
            throws InterruptedException {
        EutxoWithdrawalClaim expected = new EutxoWithdrawalClaim(
                EutxoWithdrawalClaim.ABI_VERSION,
                workspace.manifest().chainId(),
                BRIDGE_EPOCH,
                new EutxoOutpoint(receipt.transactionId(), 1),
                identity("payoutAddress"),
                WITHDRAWAL,
                HexFormat.of().parseHex("cd".repeat(32)),
                0,
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
}
