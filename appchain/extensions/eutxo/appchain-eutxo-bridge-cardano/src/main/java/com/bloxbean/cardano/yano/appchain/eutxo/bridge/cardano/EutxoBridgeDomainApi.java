package com.bloxbean.cardano.yano.appchain.eutxo.bridge.cardano;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApi;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiAccess;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiContext;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiException;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiMediaType;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRequest;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiResponse;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainApiRoute;
import com.bloxbean.cardano.yano.api.plugin.domain.DomainHttpMethod;
import com.bloxbean.cardano.yano.api.plugin.domain.L1TransactionBuilderService;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoBridgeInfo;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoContract;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoL2KeyBinding;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoOutpoint;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoQueryCodec;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoRecord;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoVaultDatum;
import com.bloxbean.cardano.yano.appchain.eutxo.contracts.EutxoWithdrawalDatum;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bundle-owned unsigned transaction API; signing and submission stay external. */
final class EutxoBridgeDomainApi implements DomainApi {
    private static final long TTL_MARGIN_SLOTS = 7_200L;
    private static final long REFUND_DEADLINE = 10_000_000L;
    private static final BigInteger DEFAULT_MAX_DEPOSIT =
            new BigInteger("45000000000000000");
    private static final String INFO = "bridge-info";
    private static final String DEPOSIT_BUILD = "bridge-deposit-build";
    private static final String DEPOSIT_ASSEMBLE = "bridge-deposit-assemble";
    private static final String TRANSFER_BUILD = "bridge-transfer-build";
    private static final String CLAIM_BUILD = "bridge-claim-build";
    private static final List<DomainApiRoute> ROUTES = List.of(
            route(INFO, DomainHttpMethod.GET, "info"),
            route(DEPOSIT_BUILD, DomainHttpMethod.POST, "deposit/build"),
            route(DEPOSIT_ASSEMBLE, DomainHttpMethod.POST, "deposit/assemble"),
            route(TRANSFER_BUILD, DomainHttpMethod.POST, "transfer/build"),
            route(CLAIM_BUILD, DomainHttpMethod.POST, "claim/build"));

    private final DomainApiContext context;
    private final ObjectMapper json = new ObjectMapper();
    private final BigInteger maximumDeposit;
    private final long stabilityDepth;

    EutxoBridgeDomainApi(DomainApiContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.maximumDeposit = positiveBigInteger(
                context.bundleConfig().get("max-deposit-lovelace"),
                DEFAULT_MAX_DEPOSIT);
        this.stabilityDepth = nonNegativeLong(
                context.bundleConfig().get("stability-depth"), 0);
    }

    @Override public List<DomainApiRoute> routes() { return ROUTES; }

    @Override
    public DomainApiResponse handle(DomainApiRequest request) {
        Objects.requireNonNull(request, "request");
        if (!request.pathParameters().keySet().equals(Set.of("chain_id"))
                || !request.queryParameters().isEmpty()) {
            throw invalid("invalid bridge request");
        }
        String chain = request.pathParameters().get("chain_id");
        if (!context.queryService().chainIds().contains(chain)) {
            return response(404, Map.of("error", "chain-not-found"));
        }
        EutxoBridgeInfo bridge = bridge(chain);
        if (!bridge.enabled()) {
            return response(404, Map.of("error", "bridge-unavailable"));
        }
        return switch (request.routeId()) {
            case INFO -> info(request, chain, bridge);
            case DEPOSIT_BUILD -> deposit(request, chain, bridge);
            case DEPOSIT_ASSEMBLE -> assemble(request);
            case TRANSFER_BUILD -> transfer(request, chain);
            case CLAIM_BUILD -> claim(request, chain, bridge);
            default -> throw invalid("unknown bridge route");
        };
    }

    private DomainApiResponse info(
            DomainApiRequest request, String chain, EutxoBridgeInfo bridge
    ) {
        requireMethod(request, DomainHttpMethod.GET);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chainId", chain);
        result.put("vaultAddress", bridge.vaultAddress());
        result.put("vaultScriptHash", bridge.vaultScriptHash());
        result.put("withdrawalAddress", bridge.withdrawalAddress());
        result.put("bridgeEpoch", bridge.bridgeEpoch());
        result.put("maxDepositLovelace", maximumDeposit);
        result.put("withdrawalsPaused", bridge.withdrawalsPaused());
        result.put("stabilityDepth", stabilityDepth);
        return response(200, result);
    }

    private DomainApiResponse deposit(
            DomainApiRequest request, String chain, EutxoBridgeInfo bridge
    ) {
        requireMethod(request, DomainHttpMethod.POST);
        DepositBuild body = read(request, DepositBuild.class);
        if (body == null || body.lovelace() < 1_000_000L
                || BigInteger.valueOf(body.lovelace()).compareTo(maximumDeposit) > 0) {
            throw invalid("invalid deposit amount");
        }
        String depositor = address(body.depositorAddress(), "depositorAddress");
        String owner = body.l2OwnerAddress() == null || body.l2OwnerAddress().isBlank()
                ? depositor : address(body.l2OwnerAddress(), "l2OwnerAddress");
        byte[] credential = credential(depositor, "depositorAddress");
        if (!java.util.Arrays.equals(
                credential, credential(owner, "l2OwnerAddress"))) {
            throw invalid("l2OwnerAddress must use the depositor payment credential");
        }
        L1TransactionBuilderService service = context.l1Transactions();
        L1TransactionBuilderService.SpendableInput input;
        try {
            input = service.selectSpendableInput(depositor);
        } catch (RuntimeException noFunds) {
            throw conflict("depositor has no spendable L1 UTxO");
        }
        EutxoVaultDatum datum = new EutxoVaultDatum(
                EutxoVaultDatum.ABI_VERSION,
                chain, owner, nonce(chain, input),
                new EutxoOutpoint(input.transactionId(), input.outputIndex()),
                REFUND_DEADLINE, credential, EutxoL2KeyBinding.none());
        long ttl = Math.addExact(service.tipSlot(), TTL_MARGIN_SLOTS);
        L1TransactionBuilderService.UnsignedTransaction transaction;
        try {
            transaction = service.buildPayment(
                    new L1TransactionBuilderService.PaymentPlan(
                            depositor, input, bridge.vaultAddress(),
                            body.lovelace(), datum.encode(), ttl));
        } catch (RuntimeException failure) {
            throw conflict("cannot build unsigned deposit");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chainId", chain);
        result.put("unsignedTxCborHex", hex(transaction.cbor()));
        result.put("transactionId", transaction.transactionId());
        result.put("vaultAddress", bridge.vaultAddress());
        result.put("depositorAddress", depositor);
        result.put("l2OwnerAddress", owner);
        result.put("lovelace", body.lovelace());
        result.put("fee", transaction.fee());
        result.put("ttlSlot", transaction.ttlSlot());
        result.put("datumHex", hex(datum.encode()));
        return response(200, result);
    }

    private DomainApiResponse assemble(DomainApiRequest request) {
        requireMethod(request, DomainHttpMethod.POST);
        DepositAssemble body = read(request, DepositAssemble.class);
        try {
            Transaction transaction = Transaction.deserialize(parseHex(
                    body.unsignedTxCborHex(), L1TransactionBuilderService.MAX_TRANSACTION_BYTES));
            TransactionWitnessSet provided = TransactionWitnessSet.deserialize(
                    (co.nstant.in.cbor.model.Map) CborSerializationUtil.deserialize(
                            parseHex(body.witnessSetCborHex(), 256 * 1024)));
            if (provided.getVkeyWitnesses() == null
                    || provided.getVkeyWitnesses().isEmpty()) {
                throw invalid("witness set carries no vkey witness");
            }
            TransactionWitnessSet target = transaction.getWitnessSet() == null
                    ? new TransactionWitnessSet() : transaction.getWitnessSet();
            if (target.getVkeyWitnesses() == null) {
                target.setVkeyWitnesses(new ArrayList<>());
            }
            target.getVkeyWitnesses().addAll(provided.getVkeyWitnesses());
            transaction.setWitnessSet(target);
            byte[] signed = transaction.serialize();
            return response(200, Map.of(
                    "signedTxCborHex", hex(signed),
                    "transactionId", TransactionUtil.getTxHash(signed)));
        } catch (DomainApiException failure) {
            throw failure;
        } catch (Exception failure) {
            throw invalid("cannot assemble the signed transaction");
        }
    }

    private DomainApiResponse transfer(DomainApiRequest request, String chain) {
        requireMethod(request, DomainHttpMethod.POST);
        L2Transfer body = read(request, L2Transfer.class);
        return l2Spend(chain, body.fromAddress(), body.toAddress(),
                body.lovelace(), null);
    }

    private DomainApiResponse claim(
            DomainApiRequest request, String chain, EutxoBridgeInfo bridge
    ) {
        requireMethod(request, DomainHttpMethod.POST);
        if (bridge.withdrawalAddress().isBlank()) {
            throw conflict("withdrawals are disabled");
        }
        L2Claim body = read(request, L2Claim.class);
        String from = address(body.fromAddress(), "fromAddress");
        String payout = body.payoutAddress() == null || body.payoutAddress().isBlank()
                ? from : address(body.payoutAddress(), "payoutAddress");
        byte[] nonce = new byte[32];
        new SecureRandom().nextBytes(nonce);
        EutxoWithdrawalDatum datum = new EutxoWithdrawalDatum(
                EutxoWithdrawalDatum.ABI_VERSION,
                chain, bridge.bridgeEpoch(), payout, nonce);
        return l2Spend(chain, from, bridge.withdrawalAddress(),
                body.lovelace(), datum);
    }

    private DomainApiResponse l2Spend(
            String chain,
            String fromRaw,
            String toRaw,
            long lovelace,
            EutxoWithdrawalDatum datum
    ) {
        if (lovelace < 1) throw invalid("lovelace must be positive");
        String from = address(fromRaw, "fromAddress");
        String to = address(toRaw, "toAddress");
        List<EutxoRecord> records = EutxoQueryCodec.decodeRecords(
                context.queryService().query(
                        chain, EutxoQueryCodec.ADDRESS_PATH,
                        EutxoQueryCodec.addressRequest(from)).payload());
        List<com.bloxbean.cardano.client.transaction.spec.TransactionInput> inputs =
                new ArrayList<>();
        BigInteger selected = BigInteger.ZERO;
        BigInteger needed = BigInteger.valueOf(lovelace);
        for (EutxoRecord record : records) {
            inputs.add(new com.bloxbean.cardano.client.transaction.spec.TransactionInput(
                    record.outpoint().transactionId(), record.outpoint().index()));
            selected = selected.add(recordLovelace(record));
            if (selected.compareTo(needed) >= 0) break;
        }
        if (selected.compareTo(needed) < 0) throw conflict("insufficient L2 balance");
        try {
            var paid = com.bloxbean.cardano.client.transaction.spec.TransactionOutput
                    .builder().address(to)
                    .value(com.bloxbean.cardano.client.transaction.spec.Value.fromCoin(needed));
            if (datum != null) paid.inlineDatum(PlutusData.deserialize(datum.encode()));
            List<com.bloxbean.cardano.client.transaction.spec.TransactionOutput> outputs =
                    new ArrayList<>(List.of(paid.build()));
            if (selected.compareTo(needed) > 0) {
                outputs.add(com.bloxbean.cardano.client.transaction.spec.TransactionOutput
                        .builder().address(from)
                        .value(com.bloxbean.cardano.client.transaction.spec.Value.fromCoin(
                                selected.subtract(needed))).build());
            }
            Transaction unsigned = Transaction.builder()
                    .body(com.bloxbean.cardano.client.transaction.spec.TransactionBody.builder()
                            .inputs(inputs).outputs(outputs).fee(BigInteger.ZERO)
                            .ttl(context.l1Transactions().tipSlot() + TTL_MARGIN_SLOTS)
                            .networkId(com.bloxbean.cardano.client.spec.NetworkId.TESTNET)
                            .build())
                    .witnessSet(new TransactionWitnessSet()).isValid(true).build();
            byte[] cbor = unsigned.serialize();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("chainId", chain);
            result.put("unsignedTxCborHex", hex(cbor));
            result.put("transactionId", TransactionUtil.getTxHash(cbor));
            result.put("fromAddress", from);
            result.put("toAddress", to);
            result.put("lovelace", lovelace);
            result.put("submitTopic", EutxoContract.TRANSACTION_TOPIC);
            if (datum != null) {
                result.put("payoutAddress", datum.destinationAddress());
                result.put("bridgeEpoch", datum.bridgeEpoch());
            }
            return response(200, result);
        } catch (Exception failure) {
            throw new DomainApiException(
                    DomainApiException.Code.FAILED,
                    "cannot build unsigned L2 transaction", failure);
        }
    }

    private EutxoBridgeInfo bridge(String chain) {
        try {
            return EutxoBridgeInfo.decode(context.queryService().query(
                    chain, EutxoQueryCodec.BRIDGE_INFO_PATH, new byte[0]).payload());
        } catch (RuntimeException failure) {
            throw new DomainApiException(
                    DomainApiException.Code.NOT_FOUND,
                    "bridge capability is unavailable", failure);
        }
    }

    private <T> T read(DomainApiRequest request, Class<T> type) {
        try {
            return json.readValue(request.body(), type);
        } catch (Exception failure) {
            throw invalid("invalid JSON request");
        }
    }

    private DomainApiResponse response(int status, Object value) {
        try {
            return new DomainApiResponse(
                    status, DomainApiMediaType.JSON, json.writeValueAsBytes(value));
        } catch (Exception failure) {
            throw new DomainApiException(
                    DomainApiException.Code.FAILED,
                    "cannot encode bridge response", failure);
        }
    }

    private static DomainApiRoute route(
            String id, DomainHttpMethod method, String suffix
    ) {
        return new DomainApiRoute(id, method,
                "chains/{chain_id}/bridge/" + suffix, DomainApiAccess.READ);
    }

    private static void requireMethod(
            DomainApiRequest request, DomainHttpMethod expected
    ) {
        if (request.method() != expected
                || (expected == DomainHttpMethod.GET && request.body().length != 0)) {
            throw invalid("invalid bridge method");
        }
    }

    private static String address(String value, String field) {
        try {
            String normalized = Objects.requireNonNull(value, field).trim();
            if (normalized.matches("[0-9a-fA-F]+") && normalized.length() >= 58) {
                return new Address(HexFormat.of().parseHex(
                        normalized.toLowerCase(java.util.Locale.ROOT))).toBech32();
            }
            return new Address(normalized).toBech32();
        } catch (RuntimeException failure) {
            throw invalid(field + " is not a valid Cardano address");
        }
    }

    private static byte[] credential(String address, String field) {
        return new Address(address).getPaymentCredentialHash()
                .orElseThrow(() -> invalid(field + " has no payment credential"));
    }

    private static byte[] nonce(
            String chain, L1TransactionBuilderService.SpendableInput input
    ) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(
                    ("yano-console-deposit\n" + chain + "\n"
                            + input.transactionId() + "#" + input.outputIndex())
                            .getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static BigInteger recordLovelace(EutxoRecord record) {
        try {
            return com.bloxbean.cardano.client.transaction.spec.TransactionOutput
                    .deserialize((co.nstant.in.cbor.model.Array)
                            CborSerializationUtil.deserialize(record.outputCbor()))
                    .getValue().getCoin();
        } catch (Exception failure) {
            throw new IllegalStateException("cannot decode L2 output", failure);
        }
    }

    private static byte[] parseHex(String value, int maximumBytes) {
        if (value == null || value.length() > maximumBytes * 2
                || (value.length() & 1) != 0 || !value.matches("[0-9a-fA-F]+")) {
            throw invalid("invalid transaction hex");
        }
        return HexFormat.of().parseHex(value);
    }

    private static String hex(byte[] value) { return HexFormat.of().formatHex(value); }

    private static BigInteger positiveBigInteger(Object value, BigInteger fallback) {
        try {
            BigInteger parsed = value == null ? fallback
                    : new BigInteger(String.valueOf(value));
            if (parsed.signum() <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("max-deposit-lovelace must be positive");
        }
    }

    private static long nonNegativeLong(Object value, long fallback) {
        try {
            long parsed = value == null ? fallback : Long.parseLong(String.valueOf(value));
            if (parsed < 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("stability-depth must be non-negative");
        }
    }

    private static DomainApiException invalid(String message) {
        return new DomainApiException(DomainApiException.Code.INVALID_REQUEST, message);
    }

    private static DomainApiException conflict(String message) {
        return new DomainApiException(DomainApiException.Code.CONFLICT, message);
    }

    private record DepositBuild(
            String depositorAddress, long lovelace, String l2OwnerAddress) { }
    private record DepositAssemble(
            String unsignedTxCborHex, String witnessSetCborHex) { }
    private record L2Transfer(
            String fromAddress, String toAddress, long lovelace) { }
    private record L2Claim(
            String fromAddress, long lovelace, String payoutAddress) { }
}
