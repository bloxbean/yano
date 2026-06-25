package com.bloxbean.cardano.yano.testkit.ccl;

import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.exception.ApiException;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.backend.api.AccountService;
import com.bloxbean.cardano.client.backend.api.AddressService;
import com.bloxbean.cardano.client.backend.api.AssetService;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.api.BlockService;
import com.bloxbean.cardano.client.backend.api.EpochService;
import com.bloxbean.cardano.client.backend.api.MetadataService;
import com.bloxbean.cardano.client.backend.api.NetworkInfoService;
import com.bloxbean.cardano.client.backend.api.PoolService;
import com.bloxbean.cardano.client.backend.api.ScriptService;
import com.bloxbean.cardano.client.backend.api.TransactionService;
import com.bloxbean.cardano.client.backend.api.UtxoService;
import com.bloxbean.cardano.client.backend.model.Block;
import com.bloxbean.cardano.client.backend.model.EpochContent;
import com.bloxbean.cardano.client.backend.model.TransactionContent;
import com.bloxbean.cardano.client.backend.model.TxContentOutputAmount;
import com.bloxbean.cardano.client.backend.model.TxContentRedeemers;
import com.bloxbean.cardano.client.backend.model.TxContentUtxo;
import com.bloxbean.cardano.client.backend.model.TxContentUtxoOutputs;
import com.bloxbean.cardano.client.backend.model.TxOutputAmount;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yano.api.config.NodeConfig;
import com.bloxbean.cardano.yano.api.model.ProtocolParamsSnapshot;
import com.bloxbean.cardano.yano.api.model.TxEvaluationResult;
import com.bloxbean.cardano.yano.api.util.EpochSlotCalc;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.api.utxo.model.AssetAmount;
import com.bloxbean.cardano.yano.api.utxo.model.Utxo;
import com.bloxbean.cardano.yano.runtime.tx.ProtocolParamsMapper;
import com.bloxbean.cardano.yano.testkit.devnet.YanoDevnetTestKit;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Embedded Cardano Client Lib {@link BackendService} backed by public Yano
 * testkit roles.
 */
public final class YanoBackendService implements BackendService {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final YanoDevnetTestKit kit;
    private final UtxoService utxoService;
    private final TransactionService transactionService;
    private final EpochService epochService;
    private final BlockService blockService;

    private YanoBackendService(YanoDevnetTestKit kit) {
        this.kit = Objects.requireNonNull(kit, "kit");
        this.utxoService = new EmbeddedUtxoService();
        this.transactionService = new EmbeddedTransactionService();
        this.epochService = new EmbeddedEpochService();
        this.blockService = new EmbeddedBlockService();
    }

    /**
     * Creates an embedded CCL backend adapter for a Yano devnet test kit.
     *
     * @param kit test kit
     * @return backend service
     */
    public static BackendService from(YanoDevnetTestKit kit) {
        return new YanoBackendService(kit);
    }

    @Override
    public AssetService getAssetService() {
        return unsupported(AssetService.class);
    }

    @Override
    public BlockService getBlockService() {
        return blockService;
    }

    @Override
    public NetworkInfoService getNetworkInfoService() {
        return unsupported(NetworkInfoService.class);
    }

    @Override
    public PoolService getPoolService() {
        return unsupported(PoolService.class);
    }

    @Override
    public TransactionService getTransactionService() {
        return transactionService;
    }

    @Override
    public UtxoService getUtxoService() {
        return utxoService;
    }

    @Override
    public AddressService getAddressService() {
        return unsupported(AddressService.class);
    }

    @Override
    public AccountService getAccountService() {
        return unsupported(AccountService.class);
    }

    @Override
    public EpochService getEpochService() {
        return epochService;
    }

    @Override
    public MetadataService getMetadataService() {
        return unsupported(MetadataService.class);
    }

    @Override
    public ScriptService getScriptService() {
        return unsupported(ScriptService.class);
    }

    private final class EmbeddedUtxoService implements UtxoService {
        @Override
        public Result<List<com.bloxbean.cardano.client.api.model.Utxo>> getUtxos(
                String address, int count, int page) throws ApiException {
            return getUtxos(address, count, page, OrderEnum.asc);
        }

        @Override
        public Result<List<com.bloxbean.cardano.client.api.model.Utxo>> getUtxos(
                String address, int count, int page, OrderEnum order) throws ApiException {
            validatePage(count, page);
            List<Utxo> yanoUtxos = utxoState().getUtxosByAddress(address, page, count);
            List<com.bloxbean.cardano.client.api.model.Utxo> cclUtxos = yanoUtxos.stream()
                    .map(YanoBackendService::toCclUtxo)
                    .toList();
            if (order == OrderEnum.desc) {
                cclUtxos = reversed(cclUtxos);
            }
            return success(cclUtxos);
        }

        @Override
        public Result<List<com.bloxbean.cardano.client.api.model.Utxo>> getUtxos(
                String address, String unit, int count, int page) throws ApiException {
            return getUtxos(address, unit, count, page, OrderEnum.asc);
        }

        @Override
        public Result<List<com.bloxbean.cardano.client.api.model.Utxo>> getUtxos(
                String address, String unit, int count, int page, OrderEnum order) throws ApiException {
            List<com.bloxbean.cardano.client.api.model.Utxo> filtered = getUtxos(address, count, page, order)
                    .getValue()
                    .stream()
                    .filter(utxo -> utxo.getAmount() != null
                            && utxo.getAmount().stream().anyMatch(amount -> unit.equals(amount.getUnit())))
                    .toList();
            return success(filtered);
        }

        @Override
        public Result<com.bloxbean.cardano.client.api.model.Utxo> getTxOutput(
                String txHash, int outputIndex) throws ApiException {
            return utxoState().getUtxoSpentOrUnspent(new com.bloxbean.cardano.yano.api.utxo.model.Outpoint(
                            txHash, outputIndex))
                    .map(YanoBackendService::toCclUtxo)
                    .map(YanoBackendService::success)
                    .orElseGet(() -> notFound("UTXO not found: " + txHash + "#" + outputIndex));
        }
    }

    private final class EmbeddedTransactionService implements TransactionService {
        @Override
        public Result<String> submitTransaction(byte[] cborData) {
            if (cborData == null || cborData.length == 0) {
                return Result.<String>error("Transaction CBOR bytes required").code(400);
            }
            String txHash = kit.transactions().submit(cborData);
            return success(txHash);
        }

        @Override
        public Result<TransactionContent> getTransaction(String txnHash) throws ApiException {
            List<Utxo> outputs = outputsByTxHash(txnHash);
            if (outputs.isEmpty()) {
                return notFound("Transaction not found: " + txnHash);
            }

            TransactionContent content = new TransactionContent();
            content.setHash(txnHash);
            content.setUtxoCount(outputs.size());
            content.setOutputAmount(outputAmounts(outputs));
            outputs.stream().mapToLong(Utxo::slot).max().ifPresent(content::setSlot);
            outputs.stream().mapToLong(Utxo::blockNumber).max().ifPresent(content::setBlockHeight);
            return success(content);
        }

        @Override
        public Result<List<TransactionContent>> getTransactions(List<String> txnHashCollection) throws ApiException {
            if (txnHashCollection == null) {
                return success(List.of());
            }
            List<TransactionContent> transactions = new ArrayList<>();
            for (String txHash : txnHashCollection) {
                Result<TransactionContent> result = getTransaction(txHash);
                if (result.isSuccessful()) {
                    transactions.add(result.getValue());
                }
            }
            return success(transactions);
        }

        @Override
        public Result<TxContentUtxo> getTransactionUtxos(String txnHash) throws ApiException {
            List<Utxo> outputs = outputsByTxHash(txnHash);
            if (outputs.isEmpty()) {
                return notFound("Transaction UTXOs not found: " + txnHash);
            }

            TxContentUtxo txContentUtxo = new TxContentUtxo();
            txContentUtxo.setInputs(List.of());
            txContentUtxo.setOutputs(outputs.stream()
                    .map(YanoBackendService::toTxContentOutput)
                    .toList());
            return success(txContentUtxo);
        }

        @Override
        public Result<List<TxContentRedeemers>> getTransactionRedeemers(String txnHash) {
            throw unsupportedMethod(TransactionService.class, "getTransactionRedeemers");
        }

        @Override
        public Result<List<EvaluationResult>> evaluateTx(byte[] cborData) throws ApiException {
            try {
                List<TxEvaluationResult> results = kit.transactions().evaluate(cborData);
                return success(results.stream()
                        .map(YanoBackendService::toCclEvaluationResult)
                        .toList());
            } catch (UnsupportedOperationException e) {
                throw e;
            } catch (Exception e) {
                throw new ApiException("Transaction evaluation failed", e);
            }
        }
    }

    private final class EmbeddedEpochService implements EpochService {
        @Override
        public Result<EpochContent> getLatestEpoch() {
            return getEpoch(Math.toIntExact(kit.queries().currentEpoch()));
        }

        @Override
        public Result<EpochContent> getEpoch(Integer epoch) {
            int epochNumber = epoch != null ? epoch : Math.toIntExact(kit.queries().currentEpoch());
            EpochContent content = new EpochContent();
            content.setEpoch(epochNumber);
            EpochSlotCalc epochSlotCalc = epochSlotCalc();
            long startSlot = epochSlotCalc.epochToStartSlot(epochNumber);
            long nextStartSlot = epochNumber == Integer.MAX_VALUE
                    ? startSlot + epochSlotCalc.shelleyEpochLength()
                    : epochSlotCalc.epochToStartSlot(epochNumber + 1);
            content.setStartTime(slotToUnixTime(startSlot));
            content.setEndTime(slotToUnixTime(Math.max(startSlot, nextStartSlot - 1)));
            return success(content);
        }

        @Override
        public Result<ProtocolParams> getProtocolParameters(Integer epoch) {
            int epochNumber = epoch != null ? epoch : Math.toIntExact(kit.queries().currentEpoch());
            Optional<ProtocolParamsSnapshot> snapshot = kit.queries().protocolParameters(epochNumber);
            if (snapshot.isPresent()) {
                return success(toCclProtocolParams(snapshot.get()));
            }
            return getProtocolParameters();
        }

        @Override
        public Result<ProtocolParams> getProtocolParameters() {
            String json = kit.queries().protocolParameters();
            if (json == null || json.isBlank()) {
                return Result.<ProtocolParams>error("Protocol parameters are not available").code(404);
            }
            try {
                // The no-epoch CCL API has no snapshot key; parse the current
                // Yano/node JSON with Yano's alias-aware protocol-parameter mapper.
                return success(ProtocolParamsMapper.fromNodeProtocolParam(json));
            } catch (IOException e) {
                return Result.<ProtocolParams>error("Protocol parameters could not be parsed: " + e.getMessage())
                        .code(500);
            }
        }

        private EpochSlotCalc epochSlotCalc() {
            NodeConfig config = kit.lifecycle().getConfig();
            return new EpochSlotCalc(
                    config.getEpochLength(),
                    config.getByronSlotsPerEpoch(),
                    config.getFirstNonByronSlot());
        }

        private long slotToUnixTime(long slot) {
            try {
                return kit.ledger().slotToUnixTime(slot);
            } catch (RuntimeException e) {
                return 0L;
            }
        }
    }

    private final class EmbeddedBlockService implements BlockService {
        @Override
        public Result<Block> getLatestBlock() {
            ChainTip tip = kit.queries().tip();
            if (tip == null) {
                return notFound("Latest block is not available");
            }
            return success(blockFromTip(tip));
        }

        @Override
        public Result<Block> getBlockByHash(String blockHash) {
            ChainTip tip = kit.queries().tip();
            if (tip != null && blockHash != null && blockHash.equals(tip.getBlockHashHex())) {
                return success(blockFromTip(tip));
            }
            return notFound("Block lookup by hash is not available: " + blockHash);
        }

        @Override
        public Result<Block> getBlockByNumber(BigInteger blockNumber) {
            ChainTip tip = kit.queries().tip();
            if (tip != null && blockNumber != null && BigInteger.valueOf(tip.getBlockNumber()).equals(blockNumber)) {
                return success(blockFromTip(tip));
            }
            return notFound("Block lookup by number is not available: " + blockNumber);
        }
    }

    private UtxoState utxoState() throws ApiException {
        UtxoState state = kit.queries().utxoState();
        if (state == null || !state.isEnabled()) {
            throw new ApiException("Yano UTXO state is not enabled");
        }
        return state;
    }

    private List<Utxo> outputsByTxHash(String txHash) throws ApiException {
        if (txHash == null || txHash.isBlank()) {
            return List.of();
        }
        return utxoState().getOutputsByTxHash(txHash);
    }

    private static com.bloxbean.cardano.client.api.model.Utxo toCclUtxo(Utxo source) {
        com.bloxbean.cardano.client.api.model.Utxo target = new com.bloxbean.cardano.client.api.model.Utxo();
        target.setTxHash(source.outpoint().txHash());
        target.setOutputIndex(source.outpoint().index());
        target.setAddress(source.address());
        target.setAmount(toCclAmounts(source));
        target.setDataHash(source.datumHash());
        target.setInlineDatum(source.inlineDatum() != null ? bytesToHex(source.inlineDatum()) : null);
        target.setReferenceScriptHash(source.referenceScriptHash());
        return target;
    }

    private static List<Amount> toCclAmounts(Utxo source) {
        List<Amount> amounts = new ArrayList<>();
        amounts.add(Amount.lovelace(source.lovelace() != null ? source.lovelace() : BigInteger.ZERO));
        if (source.assets() != null) {
            for (AssetAmount asset : source.assets()) {
                amounts.add(Amount.asset(assetUnit(asset), asset.quantity()));
            }
        }
        return amounts;
    }

    private static TxContentUtxoOutputs toTxContentOutput(Utxo source) {
        TxContentUtxoOutputs output = new TxContentUtxoOutputs();
        output.setAddress(source.address());
        output.setOutputIndex(source.outpoint().index());
        output.setDataHash(source.datumHash());
        output.setInlineDatum(source.inlineDatum() != null ? bytesToHex(source.inlineDatum()) : null);
        output.setReferenceScriptHash(source.referenceScriptHash());
        output.setAmount(toCclAmounts(source).stream()
                .map(amount -> new TxContentOutputAmount(amount.getUnit(), amount.getQuantity().toString()))
                .toList());
        return output;
    }

    private static List<TxOutputAmount> outputAmounts(List<Utxo> outputs) {
        BigInteger lovelace = outputs.stream()
                .map(Utxo::lovelace)
                .filter(Objects::nonNull)
                .reduce(BigInteger.ZERO, BigInteger::add);
        return List.of(new TxOutputAmount("lovelace", lovelace.toString()));
    }

    private static EvaluationResult toCclEvaluationResult(TxEvaluationResult source) {
        RedeemerTag tag = RedeemerTag.convert(source.tag());
        if (tag == null) {
            throw new IllegalArgumentException("Unsupported redeemer tag: " + source.tag());
        }
        return EvaluationResult.builder()
                .redeemerTag(tag)
                .index(source.index())
                .exUnits(ExUnits.builder()
                        .mem(BigInteger.valueOf(source.memory()))
                        .steps(BigInteger.valueOf(source.steps()))
                        .build())
                .build();
    }

    private static ProtocolParams toCclProtocolParams(ProtocolParamsSnapshot snapshot) {
        ProtocolParams params = new ProtocolParams();
        params.setMinFeeA(snapshot.minFeeA());
        params.setMinFeeB(snapshot.minFeeB());
        params.setMaxBlockSize(snapshot.maxBlockSize());
        params.setMaxTxSize(snapshot.maxTxSize());
        params.setMaxBlockHeaderSize(snapshot.maxBlockHeaderSize());
        params.setKeyDeposit(stringValue(snapshot.keyDeposit()));
        params.setPoolDeposit(stringValue(snapshot.poolDeposit()));
        params.setEMax(snapshot.eMax());
        params.setNOpt(snapshot.nOpt());
        params.setA0(snapshot.a0());
        params.setRho(snapshot.rho());
        params.setTau(snapshot.tau());
        params.setDecentralisationParam(snapshot.decentralisationParam());
        params.setExtraEntropy(snapshot.extraEntropy());
        params.setProtocolMajorVer(snapshot.protocolMajorVer());
        params.setProtocolMinorVer(snapshot.protocolMinorVer());
        params.setMinUtxo(stringValue(firstNonNull(snapshot.minUtxo(),
                snapshot.coinsPerUtxoSize(), snapshot.coinsPerUtxoWord())));
        params.setMinPoolCost(stringValue(snapshot.minPoolCost()));
        params.setNonce(snapshot.nonce());
        params.setCostModels(snapshot.costModels() != null
                ? new java.util.LinkedHashMap<>(snapshot.costModels())
                : null);
        params.setCostModelsRaw(snapshot.costModelsRaw() != null
                ? new java.util.LinkedHashMap<>(snapshot.costModelsRaw())
                : null);
        params.setPriceMem(snapshot.priceMem());
        params.setPriceStep(snapshot.priceStep());
        params.setMaxTxExMem(stringValue(snapshot.maxTxExMem()));
        params.setMaxTxExSteps(stringValue(snapshot.maxTxExSteps()));
        params.setMaxBlockExMem(stringValue(snapshot.maxBlockExMem()));
        params.setMaxBlockExSteps(stringValue(snapshot.maxBlockExSteps()));
        params.setMaxValSize(stringValue(snapshot.maxValSize()));
        params.setCollateralPercent(snapshot.collateralPercent() != null
                ? java.math.BigDecimal.valueOf(snapshot.collateralPercent())
                : null);
        params.setMaxCollateralInputs(snapshot.maxCollateralInputs());
        params.setCoinsPerUtxoSize(stringValue(firstNonNull(snapshot.coinsPerUtxoSize(),
                snapshot.coinsPerUtxoWord())));
        params.setCoinsPerUtxoWord(stringValue(firstNonNull(snapshot.coinsPerUtxoWord(),
                snapshot.coinsPerUtxoSize())));
        params.setPvtMotionNoConfidence(snapshot.pvtMotionNoConfidence());
        params.setPvtCommitteeNormal(snapshot.pvtCommitteeNormal());
        params.setPvtCommitteeNoConfidence(snapshot.pvtCommitteeNoConfidence());
        params.setPvtHardForkInitiation(snapshot.pvtHardForkInitiation());
        params.setPvtPPSecurityGroup(snapshot.pvtPPSecurityGroup());
        params.setDvtMotionNoConfidence(snapshot.dvtMotionNoConfidence());
        params.setDvtCommitteeNormal(snapshot.dvtCommitteeNormal());
        params.setDvtCommitteeNoConfidence(snapshot.dvtCommitteeNoConfidence());
        params.setDvtUpdateToConstitution(snapshot.dvtUpdateToConstitution());
        params.setDvtHardForkInitiation(snapshot.dvtHardForkInitiation());
        params.setDvtPPNetworkGroup(snapshot.dvtPPNetworkGroup());
        params.setDvtPPEconomicGroup(snapshot.dvtPPEconomicGroup());
        params.setDvtPPTechnicalGroup(snapshot.dvtPPTechnicalGroup());
        params.setDvtPPGovGroup(snapshot.dvtPPGovGroup());
        params.setDvtTreasuryWithdrawal(snapshot.dvtTreasuryWithdrawal());
        params.setCommitteeMinSize(snapshot.committeeMinSize());
        params.setCommitteeMaxTermLength(snapshot.committeeMaxTermLength());
        params.setGovActionLifetime(snapshot.govActionLifetime());
        params.setGovActionDeposit(snapshot.govActionDeposit());
        params.setDrepDeposit(snapshot.drepDeposit());
        params.setDrepActivity(snapshot.drepActivity());
        params.setMinFeeRefScriptCostPerByte(snapshot.minFeeRefScriptCostPerByte());
        return params;
    }

    private Block blockFromTip(ChainTip tip) {
        Block block = new Block();
        block.setHash(tip.getBlockHashHex());
        block.setHeight(tip.getBlockNumber());
        block.setSlot(tip.getSlot());
        block.setEpoch(Math.toIntExact(kit.queries().currentEpoch()));
        return block;
    }

    private static void validatePage(int count, int page) {
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
        if (page <= 0) {
            throw new IllegalArgumentException("page must be positive");
        }
    }

    private static String assetUnit(AssetAmount asset) {
        String policyId = asset.policyId() != null ? asset.policyId() : "";
        String assetName = asset.assetName() != null ? asset.assetName() : "";
        return policyId + assetName;
    }

    private static <T> Result<T> success(T value) {
        return Result.success(json(value)).withValue(value).code(200);
    }

    private static <T> Result<T> notFound(String message) {
        return Result.<T>error(message).code(404);
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    private static <T> List<T> reversed(List<T> values) {
        List<T> copy = new ArrayList<>(values);
        Collections.reverse(copy);
        return copy;
    }

    private static BigInteger firstNonNull(BigInteger... values) {
        for (BigInteger value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String stringValue(BigInteger value) {
        return value != null ? value.toString() : null;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(Character.forDigit((value >> 4) & 0xF, 16));
            builder.append(Character.forDigit(value & 0xF, 16));
        }
        return builder.toString();
    }

    @SuppressWarnings("unchecked")
    private static <T> T unsupported(Class<T> serviceType) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "Unsupported " + serviceType.getSimpleName()
                            + " from YanoBackendService";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                };
            }
            throw unsupportedMethod(serviceType, method.getName());
        };
        return (T) Proxy.newProxyInstance(
                serviceType.getClassLoader(),
                new Class<?>[]{serviceType},
                handler);
    }

    private static UnsupportedOperationException unsupportedMethod(Class<?> serviceType, String methodName) {
        return new UnsupportedOperationException(serviceType.getSimpleName() + "." + methodName
                + " is not supported by embedded YanoBackendService; use the HTTP app fixture for this surface");
    }
}
