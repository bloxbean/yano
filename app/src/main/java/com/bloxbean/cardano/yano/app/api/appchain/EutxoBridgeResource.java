package com.bloxbean.cardano.yano.app.api.appchain;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.TransactionProcessor;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2KeyBinding;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoRecord;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoVaultDatum;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalDatum;
import io.quarkus.runtime.annotations.RegisterForReflection;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Host-owned bridge-chain transaction service (ADR-UTXO-008 D6/BR-M7).
 *
 * <p>The node only assembles bytes: {@code deposit/build} returns the
 * UNSIGNED vault deposit carrying the mandatory inline datum (built from the
 * node's own UTxO store and protocol parameters — the datum logic stays in
 * Java, shared with the maintained flows), {@code deposit/assemble} merges a
 * CIP-30 witness set into it. Custody never moves; signing happens in the
 * user's wallet, submission through the wallet or {@code /api/v1/tx/submit}.
 *
 * <p>Deliberately NOT a plugin domain API: the ADR-011.3 SPI is read-oriented
 * and cannot reach the L1 services transaction building needs.
 */
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RegisterForReflection
public class EutxoBridgeResource {
    private static final long TTL_MARGIN_SLOTS = 7_200L;
    private static final long REFUND_DEADLINE = 10_000_000L;

    /** Chain-scoped bridge facts resolved from the packaged configuration. */
    public record BridgeSettings(
            String vaultAddress,
            String vaultScriptHash,
            String withdrawalAddress,
            long bridgeEpoch,
            BigInteger maxDepositLovelace,
            boolean withdrawalsPaused,
            long stabilityDepth
    ) {
    }

    public record DepositBuildRequest(
            String depositorAddress,
            long lovelace,
            String l2OwnerAddress
    ) {
    }

    public record DepositAssembleRequest(
            String unsignedTxCborHex,
            String witnessSetCborHex
    ) {
    }

    public record L2TransferRequest(
            String fromAddress, String toAddress, long lovelace) {
    }

    public record L2ClaimRequest(
            String fromAddress, long lovelace, String payoutAddress) {
    }

    private final String chainId;
    private final BridgeSettings settings;
    private final UtxoSupplier utxoSupplier;
    private final Supplier<ProtocolParams> protocolParams;
    private final LongSupplier tipSlot;
    private final java.util.function.Function<String, List<EutxoRecord>> l2Utxos;

    EutxoBridgeResource(
            String chainId,
            BridgeSettings settings,
            UtxoSupplier utxoSupplier,
            Supplier<ProtocolParams> protocolParams,
            LongSupplier tipSlot,
            java.util.function.Function<String, List<EutxoRecord>> l2Utxos) {
        this.chainId = chainId;
        this.settings = settings;
        this.utxoSupplier = utxoSupplier;
        this.protocolParams = protocolParams;
        this.tipSlot = tipSlot;
        this.l2Utxos = l2Utxos;
    }

    @POST
    @Path("transfer/build")
    @AppChainAccess(AppChainAccess.Level.READ)
    public Response transferBuild(L2TransferRequest request) {
        if (request == null || isBlank(request.fromAddress())
                || isBlank(request.toAddress())) {
            throw error(Response.Status.BAD_REQUEST,
                    "fromAddress and toAddress are required");
        }
        String to = bech32(request.toAddress(), "toAddress");
        return l2Spend(request.fromAddress(), to, request.lovelace(), null);
    }

    @POST
    @Path("claim/build")
    @AppChainAccess(AppChainAccess.Level.READ)
    public Response claimBuild(L2ClaimRequest request) {
        if (request == null || isBlank(request.fromAddress())) {
            throw error(Response.Status.BAD_REQUEST, "fromAddress is required");
        }
        if (settings.withdrawalAddress().isBlank()) {
            throw error(Response.Status.CONFLICT,
                    "this chain has withdrawals disabled");
        }
        String from = bech32(request.fromAddress(), "fromAddress");
        String payout = request.payoutAddress() == null
                || request.payoutAddress().isBlank()
                ? from : bech32(request.payoutAddress(), "payoutAddress");
        byte[] nonce = new byte[32];
        new java.security.SecureRandom().nextBytes(nonce);
        EutxoWithdrawalDatum datum = new EutxoWithdrawalDatum(
                EutxoWithdrawalDatum.ABI_VERSION,
                chainId, settings.bridgeEpoch(), payout, nonce);
        return l2Spend(request.fromAddress(),
                settings.withdrawalAddress(), request.lovelace(), datum);
    }

    /** Unsigned L2 spend: fee 0, testnet id, greedy selection, change back. */
    private Response l2Spend(
            String fromRaw, String to, long lovelace, EutxoWithdrawalDatum datum) {
        if (lovelace < 1L) {
            throw error(Response.Status.BAD_REQUEST, "lovelace must be positive");
        }
        String from = bech32(fromRaw, "fromAddress");
        List<EutxoRecord> records;
        try {
            records = l2Utxos.apply(from);
        } catch (RuntimeException failure) {
            throw error(Response.Status.SERVICE_UNAVAILABLE,
                    "cannot read L2 UTxOs: " + safe(failure));
        }
        java.util.List<com.bloxbean.cardano.client.transaction.spec
                .TransactionInput> inputs = new java.util.ArrayList<>();
        BigInteger selected = BigInteger.ZERO;
        BigInteger needed = BigInteger.valueOf(lovelace);
        for (EutxoRecord record : records) {
            inputs.add(new com.bloxbean.cardano.client.transaction.spec
                    .TransactionInput(record.outpoint().transactionId(),
                    record.outpoint().index()));
            selected = selected.add(recordLovelace(record));
            if (selected.compareTo(needed) >= 0) {
                break;
            }
        }
        if (selected.compareTo(needed) < 0) {
            throw error(Response.Status.CONFLICT,
                    "insufficient L2 balance at " + from + ": have "
                            + selected + ", need " + needed);
        }
        try {
            java.util.List<com.bloxbean.cardano.client.transaction.spec
                    .TransactionOutput> outputs = new java.util.ArrayList<>();
            var paid = com.bloxbean.cardano.client.transaction.spec
                    .TransactionOutput.builder()
                    .address(to)
                    .value(com.bloxbean.cardano.client.transaction.spec
                            .Value.fromCoin(needed));
            if (datum != null) {
                paid.inlineDatum(PlutusData.deserialize(datum.encode()));
            }
            outputs.add(paid.build());
            if (selected.compareTo(needed) > 0) {
                outputs.add(com.bloxbean.cardano.client.transaction.spec
                        .TransactionOutput.builder()
                        .address(from)
                        .value(com.bloxbean.cardano.client.transaction.spec
                                .Value.fromCoin(selected.subtract(needed)))
                        .build());
            }
            Transaction unsigned = Transaction.builder()
                    .body(com.bloxbean.cardano.client.transaction.spec
                            .TransactionBody.builder()
                            .inputs(inputs)
                            .outputs(outputs)
                            .fee(BigInteger.ZERO)
                            // Validity is judged against the LIVE L1 slot —
                            // a fixed devnet-scale TTL is instantly EXPIRED
                            // on public networks.
                            .ttl(tipSlot.getAsLong() + TTL_MARGIN_SLOTS)
                            .networkId(com.bloxbean.cardano.client.spec
                                    .NetworkId.TESTNET)
                            .build())
                    .witnessSet(new TransactionWitnessSet())
                    .isValid(true)
                    .build();
            byte[] cbor = unsigned.serialize();
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("chainId", chainId);
            fields.put("unsignedTxCborHex", HexFormat.of().formatHex(cbor));
            fields.put("transactionId", TransactionUtil.getTxHash(cbor));
            fields.put("fromAddress", from);
            fields.put("toAddress", to);
            fields.put("lovelace", lovelace);
            fields.put("submitTopic",
                    com.bloxbean.cardano.yano.appchain.eutxo.contracts
                            .EutxoContract.TRANSACTION_TOPIC);
            if (datum != null) {
                fields.put("payoutAddress", datum.destinationAddress());
                fields.put("bridgeEpoch", settings.bridgeEpoch());
            }
            return Response.ok(fields).build();
        } catch (WebApplicationException failure) {
            throw failure;
        } catch (Exception failure) {
            throw error(Response.Status.INTERNAL_SERVER_ERROR,
                    "cannot build the L2 transaction: " + safe(failure));
        }
    }

    private static BigInteger recordLovelace(EutxoRecord record) {
        try {
            return com.bloxbean.cardano.client.transaction.spec
                    .TransactionOutput.deserialize(
                            (co.nstant.in.cbor.model.Array)
                                    CborSerializationUtil.deserialize(
                                            record.outputCbor()))
                    .getValue().getCoin();
        } catch (Exception failure) {
            throw new IllegalStateException("cannot decode an L2 output", failure);
        }
    }

    @GET
    @Path("info")
    @AppChainAccess(AppChainAccess.Level.READ)
    public Response info() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("chainId", chainId);
        fields.put("vaultAddress", settings.vaultAddress());
        fields.put("vaultScriptHash", settings.vaultScriptHash());
        fields.put("withdrawalAddress", settings.withdrawalAddress());
        fields.put("bridgeEpoch", settings.bridgeEpoch());
        fields.put("maxDepositLovelace", settings.maxDepositLovelace());
        fields.put("withdrawalsPaused", settings.withdrawalsPaused());
        fields.put("stabilityDepth", settings.stabilityDepth());
        return Response.ok(fields).build();
    }

    @POST
    @Path("deposit/build")
    @AppChainAccess(AppChainAccess.Level.READ)
    public Response depositBuild(DepositBuildRequest request) {
        if (request == null || request.depositorAddress() == null
                || request.depositorAddress().isBlank()) {
            throw error(Response.Status.BAD_REQUEST, "depositorAddress is required");
        }
        long lovelace = request.lovelace();
        if (lovelace < 1_000_000L) {
            throw error(Response.Status.BAD_REQUEST,
                    "lovelace must be at least 1000000");
        }
        if (BigInteger.valueOf(lovelace)
                .compareTo(settings.maxDepositLovelace()) > 0) {
            throw error(Response.Status.BAD_REQUEST,
                    "lovelace exceeds the chain's deposit bound "
                            + settings.maxDepositLovelace());
        }
        String depositor = bech32(request.depositorAddress(), "depositorAddress");
        String l2Owner = request.l2OwnerAddress() == null
                || request.l2OwnerAddress().isBlank()
                ? depositor : bech32(request.l2OwnerAddress(), "l2OwnerAddress");
        byte[] depositorCredential = credential(depositor, "depositorAddress");
        if (!java.util.Arrays.equals(depositorCredential,
                credential(l2Owner, "l2OwnerAddress"))) {
            throw error(Response.Status.BAD_REQUEST,
                    "l2OwnerAddress must use the depositor payment credential");
        }
        Utxo source = utxoSupplier.getPage(depositor, 40, 0, null).stream()
                .filter(value -> value.getAmount() != null
                        && value.getAmount().size() == 1)
                .findFirst()
                .orElseThrow(() -> error(Response.Status.CONFLICT,
                        "depositor has no pure-ADA UTxO on this node"));
        EutxoVaultDatum datum = new EutxoVaultDatum(
                EutxoVaultDatum.ABI_VERSION,
                chainId,
                l2Owner,
                nonce(source),
                new EutxoOutpoint(source.getTxHash(), source.getOutputIndex()),
                REFUND_DEADLINE,
                depositorCredential,
                EutxoL2KeyBinding.none());
        long ttlSlot = tipSlot.getAsLong() + TTL_MARGIN_SLOTS;
        Transaction unsigned;
        try {
            unsigned = new QuickTxBuilder(
                    utxoSupplier, noEvaluation(), NO_SUBMIT)
                    .compose(new Tx()
                            .collectFrom(List.of(source))
                            .payToContract(settings.vaultAddress(),
                                    Amount.lovelace(BigInteger.valueOf(lovelace)),
                                    PlutusData.deserialize(datum.encode()))
                            .from(depositor))
                    .additionalSignersCount(1)
                    .validTo(ttlSlot)
                    .build();
        } catch (WebApplicationException failure) {
            throw failure;
        } catch (Exception failure) {
            throw error(Response.Status.CONFLICT,
                    "cannot build the deposit: " + safe(failure));
        }
        byte[] cbor;
        try {
            cbor = unsigned.serialize();
        } catch (Exception failure) {
            throw error(Response.Status.INTERNAL_SERVER_ERROR,
                    "cannot serialize the deposit transaction");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("chainId", chainId);
        fields.put("unsignedTxCborHex", HexFormat.of().formatHex(cbor));
        fields.put("transactionId", TransactionUtil.getTxHash(cbor));
        fields.put("vaultAddress", settings.vaultAddress());
        fields.put("depositorAddress", depositor);
        fields.put("l2OwnerAddress", l2Owner);
        fields.put("lovelace", lovelace);
        fields.put("fee", unsigned.getBody().getFee());
        fields.put("ttlSlot", ttlSlot);
        fields.put("datumHex", HexFormat.of().formatHex(datum.encode()));
        return Response.ok(fields).build();
    }

    @POST
    @Path("deposit/assemble")
    @AppChainAccess(AppChainAccess.Level.READ)
    public Response depositAssemble(DepositAssembleRequest request) {
        if (request == null || isBlank(request.unsignedTxCborHex())
                || isBlank(request.witnessSetCborHex())) {
            throw error(Response.Status.BAD_REQUEST,
                    "unsignedTxCborHex and witnessSetCborHex are required");
        }
        try {
            Transaction transaction = Transaction.deserialize(
                    HexFormat.of().parseHex(request.unsignedTxCborHex().trim()));
            TransactionWitnessSet provided = TransactionWitnessSet.deserialize(
                    (co.nstant.in.cbor.model.Map) CborSerializationUtil.deserialize(
                            HexFormat.of().parseHex(
                                    request.witnessSetCborHex().trim())));
            if (provided.getVkeyWitnesses() == null
                    || provided.getVkeyWitnesses().isEmpty()) {
                throw error(Response.Status.BAD_REQUEST,
                        "witness set carries no vkey witness");
            }
            TransactionWitnessSet target = transaction.getWitnessSet() == null
                    ? new TransactionWitnessSet() : transaction.getWitnessSet();
            if (target.getVkeyWitnesses() == null) {
                target.setVkeyWitnesses(new java.util.ArrayList<>());
            }
            target.getVkeyWitnesses().addAll(provided.getVkeyWitnesses());
            transaction.setWitnessSet(target);
            byte[] signed = transaction.serialize();
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("signedTxCborHex", HexFormat.of().formatHex(signed));
            fields.put("transactionId", TransactionUtil.getTxHash(signed));
            return Response.ok(fields).build();
        } catch (WebApplicationException failure) {
            throw failure;
        } catch (Exception failure) {
            throw error(Response.Status.BAD_REQUEST,
                    "cannot assemble the signed transaction: " + safe(failure));
        }
    }

    /** Deposit construction never submits or evaluates through CCL. */
    private static final TransactionProcessor NO_SUBMIT = new TransactionProcessor() {
        @Override
        public com.bloxbean.cardano.client.api.model.Result<String>
        submitTransaction(byte[] cborData) {
            throw new UnsupportedOperationException(
                    "the bridge resource never submits transactions");
        }

        @Override
        public com.bloxbean.cardano.client.api.model.Result<
                java.util.List<com.bloxbean.cardano.client.api.model.EvaluationResult>>
        evaluateTx(byte[] cbor,
                   java.util.Set<Utxo> inputUtxos) {
            throw new UnsupportedOperationException(
                    "deposits carry no scripts to evaluate");
        }
    };

    private ProtocolParamsSupplier noEvaluation() {
        return () -> {
            ProtocolParams params = protocolParams.get();
            if (params == null) {
                throw error(Response.Status.SERVICE_UNAVAILABLE,
                        "protocol parameters are unavailable");
            }
            return params;
        };
    }

    private byte[] nonce(Utxo source) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(
                    ("yano-console-deposit\n" + chainId + "\n"
                            + source.getTxHash() + "#" + source.getOutputIndex())
                            .getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String bech32(String value, String field) {
        String normalized = value.trim();
        try {
            if (normalized.matches("[0-9a-fA-F]+") && normalized.length() >= 58) {
                // CIP-30 returns hex-encoded address bytes.
                return new Address(HexFormat.of().parseHex(
                        normalized.toLowerCase(java.util.Locale.ROOT))).toBech32();
            }
            return new Address(normalized).toBech32();
        } catch (RuntimeException invalid) {
            throw error(Response.Status.BAD_REQUEST,
                    field + " is not a valid Cardano address");
        }
    }

    private static byte[] credential(String address, String field) {
        return new Address(address).getPaymentCredentialHash()
                .orElseThrow(() -> error(Response.Status.BAD_REQUEST,
                        field + " has no payment credential"));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String safe(Exception failure) {
        String message = String.valueOf(failure.getMessage());
        String flat = message.replaceAll("[\\r\\n\\t]+", " ");
        return flat.substring(0, Math.min(flat.length(), 200));
    }

    private static WebApplicationException error(
            Response.Status status, String message) {
        return new WebApplicationException(Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("error", message))
                .build());
    }
}
