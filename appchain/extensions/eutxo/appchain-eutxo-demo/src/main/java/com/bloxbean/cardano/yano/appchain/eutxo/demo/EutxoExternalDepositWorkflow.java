package com.bloxbean.cardano.yano.appchain.eutxo.demo;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yano.appchain.client.AppChainClient;
import com.bloxbean.cardano.yano.appchain.eutxo.client.EutxoClient;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2KeyBinding;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoProfile;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoVaultDatum;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Offline-signing boundary for externally owned demo deposits. */
public final class EutxoExternalDepositWorkflow {
    private final EutxoDemoWorkspace workspace;
    private final EutxoDemoCluster cluster;
    private final BackendService backend;

    public EutxoExternalDepositWorkflow(
            EutxoDemoWorkspace workspace,
            EutxoDemoCluster cluster) {
        this.workspace = workspace;
        this.cluster = cluster;
        backend = new BFBackendService(cluster.apiBase() + "/", "demo");
    }

    public EutxoDemoResult build(EutxoDemoOptions options) throws Exception {
        requireReady();
        if (options.address() == null || options.output() == null) {
            throw new IllegalArgumentException(
                    "deposit-build requires --address and --output");
        }
        String l2Address = options.l2Address() == null
                ? options.address() : options.l2Address();
        byte[] depositor = credential(options.address());
        if (!Arrays.equals(depositor, credential(l2Address))) {
            throw new IllegalArgumentException(
                    "external L2 address must use the depositor payment credential");
        }
        var source = new DefaultUtxoSupplier(
                backend.getUtxoService()).getAll(options.address())
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "external depositor has no devnet UTXO"));
        EutxoL2KeyBinding binding = binding(options);
        EutxoVaultDatum datum = new EutxoVaultDatum(
                EutxoVaultDatum.ABI_VERSION,
                workspace.manifest().chainId(),
                l2Address,
                digest("external-deposit\n" + source.getTxHash()
                        + "#" + source.getOutputIndex()),
                new EutxoOutpoint(
                        source.getTxHash(), source.getOutputIndex()),
                10_000_000L,
                depositor,
                binding);
        Transaction transaction = new QuickTxBuilder(backend)
                .compose(new Tx()
                        .collectFrom(List.of(source))
                        .payToContract(identity("vaultAddress"),
                                Amount.lovelace(java.math.BigInteger.valueOf(
                                        options.amount())),
                                PlutusData.deserialize(datum.encode()))
                        .from(options.address()))
                .additionalSignersCount(1)
                .build();
        byte[] cbor = transaction.serialize();
        Path output = options.output().toAbsolutePath().normalize();
        if (Files.exists(output)) {
            throw new IllegalArgumentException(
                    "unsigned transaction output already exists");
        }
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        Files.write(output, cbor, StandardOpenOption.CREATE_NEW);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("unsignedTransaction", output.toString());
        fields.put("depositorAddress", options.address());
        fields.put("l2Address", l2Address);
        fields.put("lovelace", options.amount());
        fields.put("authorizationProfile",
                binding.present() ? binding.authorizationProfile()
                        : "cardano-vkey");
        fields.put("next",
                "Sign the CBOR with CIP-30, cardano-cli, or CCL, then run deposit-submit");
        return EutxoDemoResult.of(
                "EUTXO_EXTERNAL_DEPOSIT_UNSIGNED", fields);
    }

    public EutxoDemoResult submit(EutxoDemoOptions options) throws Exception {
        requireReady();
        if (options.signedTransaction() == null) {
            throw new IllegalArgumentException(
                    "deposit-submit requires --signed-transaction");
        }
        long fileBytes = Files.size(options.signedTransaction());
        if (fileBytes < 1
                || fileBytes > EutxoProfile.V1.maxTransactionBytes()) {
            throw new IllegalArgumentException(
                    "signed deposit exceeds the EUTxO transaction bound");
        }
        byte[] cbor = Files.readAllBytes(options.signedTransaction());
        Transaction transaction = Transaction.deserialize(cbor);
        if (transaction.getWitnessSet() == null
                || transaction.getWitnessSet().getVkeyWitnesses() == null
                || transaction.getWitnessSet().getVkeyWitnesses().isEmpty()) {
            throw new IllegalArgumentException(
                    "deposit transaction has no Cardano VKey witness");
        }
        int vaultIndex = -1;
        for (int index = 0;
             index < transaction.getBody().getOutputs().size(); index++) {
            if (identity("vaultAddress").equals(
                    transaction.getBody().getOutputs().get(index).getAddress())) {
                if (vaultIndex != -1) {
                    throw new IllegalArgumentException(
                            "signed deposit contains multiple demo vault outputs");
                }
                vaultIndex = index;
            }
        }
        if (vaultIndex == -1
                || transaction.getBody().getOutputs().get(
                vaultIndex).getInlineDatum() == null) {
            throw new IllegalArgumentException(
                    "signed deposit must contain exactly one inline-datum demo vault output");
        }
        EutxoVaultDatum datum = EutxoVaultDatum.decode(
                transaction.getBody().getOutputs().get(
                        vaultIndex).getInlineDatum().serializeToBytes());
        if (!workspace.manifest().chainId().equals(datum.chainId())) {
            throw new IllegalArgumentException(
                    "signed deposit targets a different app chain");
        }
        String transactionId = TransactionUtil.getTxHash(cbor);
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(
                                cluster.apiBase() + "/tx/submit"))
                        .header("Content-Type", "application/cbor")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(cbor))
                        .build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "external deposit submission failed (HTTP "
                            + response.statusCode() + ")");
        }
        EutxoOutpoint accepted = new EutxoOutpoint(
                transactionId, vaultIndex);
        EutxoClient client = new EutxoClient(
                AppChainClient.builder(cluster.apiBase())
                        .chainId(workspace.manifest().chainId()).build());
        boolean mirrored = false;
        for (int attempt = 0; attempt < 240; attempt++) {
            try {
                if (client.depositSnapshot(accepted).value().isPresent()) {
                    mirrored = true;
                    break;
                }
            } catch (RuntimeException ignored) {
                // Stable observation is asynchronous.
            }
            Thread.sleep(500);
        }
        if (!mirrored) {
            throw new IllegalStateException(
                    "external deposit stability timed out");
        }
        return EutxoDemoResult.of(
                "EUTXO_EXTERNAL_DEPOSIT_SUBMITTED",
                Map.of("transactionId", transactionId,
                        "acceptedOutpoint", accepted.toString(),
                        "signedTransaction",
                        options.signedTransaction().toAbsolutePath()
                                .normalize().toString(),
                        "next",
                        "Wait for L1 stability, then query the mirrored L2 UTXO"));
    }

    private EutxoL2KeyBinding binding(EutxoDemoOptions options) {
        if (!"zk".equals(workspace.manifest().scenario())) {
            if (options.l2PublicKey() != null) {
                throw new IllegalArgumentException(
                        "--l2-public-key is only valid for the ZK demo");
            }
            return EutxoL2KeyBinding.none();
        }
        if (options.l2PublicKey() == null
                || !options.l2PublicKey().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "external ZK deposits require a lowercase 32-byte --l2-public-key");
        }
        return new EutxoL2KeyBinding(
                "zeroj-jubjub-dev-v1",
                1, HexFormat.of().parseHex(options.l2PublicKey()));
    }

    private String identity(String name) {
        String value = workspace.manifest().publicIdentities().get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("demo identity unavailable");
        }
        return value;
    }

    private void requireReady() {
        if (!cluster.status().ready()) {
            throw new IllegalStateException("DEMO_CLUSTER_NOT_READY");
        }
    }

    private static byte[] credential(String value) {
        return new Address(value).getPaymentCredentialHash()
                .orElseThrow(() -> new IllegalArgumentException(
                        "address has no payment credential"));
    }

    private static byte[] digest(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(
                value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
