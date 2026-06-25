package com.bloxbean.cardano.yano.testkit.devnet;

import com.bloxbean.cardano.client.api.exception.ApiException;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.model.EpochContent;
import com.bloxbean.cardano.client.backend.model.TransactionContent;
import com.bloxbean.cardano.client.backend.model.TxContentUtxo;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yano.api.ChainQuery;
import com.bloxbean.cardano.yano.api.DevnetControl;
import com.bloxbean.cardano.yano.api.LedgerQuery;
import com.bloxbean.cardano.yano.api.NodeLifecycle;
import com.bloxbean.cardano.yano.api.ProducerControl;
import com.bloxbean.cardano.yano.api.TxEvaluationGateway;
import com.bloxbean.cardano.yano.api.TxGateway;
import com.bloxbean.cardano.yano.api.config.NodeConfig;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.api.listener.NodeEventListener;
import com.bloxbean.cardano.yano.api.model.FundResult;
import com.bloxbean.cardano.yano.api.model.GenesisParameters;
import com.bloxbean.cardano.yano.api.model.NodeStatus;
import com.bloxbean.cardano.yano.api.model.SnapshotInfo;
import com.bloxbean.cardano.yano.api.model.TimeAdvanceResult;
import com.bloxbean.cardano.yano.api.model.TxEvaluationResult;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.api.utxo.model.AssetAmount;
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.runtime.assembly.Yano;
import com.bloxbean.cardano.yano.runtime.kernel.NodeKernel;
import com.bloxbean.cardano.yano.testkit.ccl.YanoBackendService;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YanoBackendServiceTest {
    @Test
    void utxoAndTransactionLookupsUsePublicUtxoState() throws Exception {
        com.bloxbean.cardano.yano.api.utxo.model.Utxo yanoUtxo = yanoUtxo(
                "tx-1", 0, "addr_test1", 2_000_000L,
                List.of(new AssetAmount("policy", "746f6b656e", BigInteger.valueOf(7))));
        try (YanoDevnetTestKit kit = kit(new FakeYano(List.of(yanoUtxo), true))) {
            BackendService backend = YanoBackendService.from(kit);

            Result<List<Utxo>> utxos = backend.getUtxoService().getUtxos("addr_test1", 10, 1);
            Result<Utxo> output = backend.getUtxoService().getTxOutput("tx-1", 0);
            Result<TxContentUtxo> txUtxos = backend.getTransactionService().getTransactionUtxos("tx-1");
            Result<TransactionContent> transaction = backend.getTransactionService().getTransaction("tx-1");

            assertTrue(utxos.isSuccessful());
            assertEquals("tx-1", utxos.getValue().getFirst().getTxHash());
            assertEquals(BigInteger.valueOf(2_000_000L), utxos.getValue().getFirst().getAmount().getFirst().getQuantity());
            assertEquals("policy746f6b656e", utxos.getValue().getFirst().getAmount().get(1).getUnit());
            assertTrue(output.isSuccessful());
            assertTrue(txUtxos.isSuccessful());
            assertEquals(1, txUtxos.getValue().getOutputs().size());
            assertTrue(transaction.isSuccessful());
            assertEquals("tx-1", transaction.getValue().getHash());
        }
    }

    @Test
    void transactionSubmitAndEvaluateUsePublicGateways() throws Exception {
        FakeYano node = new FakeYano(List.of(), true);
        node.txGateway.txHash = "submitted";
        node.txEvaluationGateway.available = true;
        node.txEvaluationGateway.results = List.of(new TxEvaluationResult("spend", 0, 10, 20));

        try (YanoDevnetTestKit kit = kit(node)) {
            BackendService backend = YanoBackendService.from(kit);
            byte[] cbor = new byte[]{1, 2, 3};

            Result<String> submitted = backend.getTransactionService().submitTransaction(cbor);
            cbor[0] = 9;
            Result<List<EvaluationResult>> evaluated = backend.getTransactionService().evaluateTx(new byte[]{4});

            assertTrue(submitted.isSuccessful());
            assertEquals("submitted", submitted.getValue());
            assertArrayEquals(new byte[]{1, 2, 3}, node.txGateway.submittedCbor.getFirst());
            assertTrue(evaluated.isSuccessful());
            assertEquals(BigInteger.valueOf(10), evaluated.getValue().getFirst().getExUnits().getMem());
            assertEquals(BigInteger.valueOf(20), evaluated.getValue().getFirst().getExUnits().getSteps());
        }
    }

    @Test
    void disabledUtxoStateFailsClearly() {
        try (YanoDevnetTestKit kit = kit(new FakeYano(List.of(), false))) {
            BackendService backend = YanoBackendService.from(kit);

            ApiException error = assertThrows(ApiException.class,
                    () -> backend.getUtxoService().getUtxos("addr_test1", 10, 1));

            assertTrue(error.getMessage().contains("UTXO state is not enabled"));
        }
    }

    @Test
    void unsupportedServicesFailClearly() {
        try (YanoDevnetTestKit kit = kit(new FakeYano(List.of(), true))) {
            BackendService backend = YanoBackendService.from(kit);

            UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
                    () -> backend.getAssetService().getAsset("asset"));

            assertTrue(error.getMessage().contains("AssetService.getAsset is not supported"));
        }
    }

    @Test
    void epochContentUsesByronAwareEpochBoundaries() throws Exception {
        YanoConfig config = defaultConfig();
        config.setEpochLength(600L);
        config.setByronSlotsPerEpoch(100L);
        config.setFirstNonByronSlot(300L);

        try (YanoDevnetTestKit kit = kit(new FakeYano(List.of(), true, config, "{}"))) {
            BackendService backend = YanoBackendService.from(kit);

            Result<EpochContent> byronEpoch = backend.getEpochService().getEpoch(2);
            Result<EpochContent> shelleyEpoch = backend.getEpochService().getEpoch(4);

            assertTrue(byronEpoch.isSuccessful());
            assertEquals(200L, byronEpoch.getValue().getStartTime());
            assertEquals(299L, byronEpoch.getValue().getEndTime());
            assertTrue(shelleyEpoch.isSuccessful());
            assertEquals(900L, shelleyEpoch.getValue().getStartTime());
            assertEquals(1499L, shelleyEpoch.getValue().getEndTime());
        }
    }

    @Test
    void protocolParametersFallbackParsesYanoProtocolParamsJsonWithCclMapping() throws Exception {
        String protocolParamsJson = """
                {
                  "min_fee_a": 44,
                  "min_fee_b": 155381,
                  "max_block_size": 90112,
                  "protocol_major_ver": 10,
                  "protocol_minor_ver": 0,
                  "price_mem": 0.0577,
                  "price_step": 0.0000721,
                  "max_tx_ex_mem": "16500000",
                  "max_tx_ex_steps": "10000000000",
                  "coins_per_utxo_size": "4310"
                }
                """;

        try (YanoDevnetTestKit kit = kit(new FakeYano(List.of(), true,
                defaultConfig(), protocolParamsJson))) {
            BackendService backend = YanoBackendService.from(kit);

            Result<ProtocolParams> params = backend.getEpochService().getProtocolParameters();

            assertTrue(params.isSuccessful());
            assertEquals(44, params.getValue().getMinFeeA());
            assertEquals(155381, params.getValue().getMinFeeB());
            assertEquals(90112, params.getValue().getMaxBlockSize());
            assertEquals(10, params.getValue().getProtocolMajorVer());
            assertEquals(0, params.getValue().getProtocolMinorVer());
            assertEquals("16500000", params.getValue().getMaxTxExMem());
            assertEquals("4310", params.getValue().getCoinsPerUtxoSize());
        }
    }

    private static YanoDevnetTestKit kit(FakeYano node) {
        return YanoDevnetTestKit.from(node);
    }

    private static YanoConfig defaultConfig() {
        YanoConfig config = YanoConfig.devnetDefault(42);
        config.setEpochLength(100L);
        config.setByronSlotsPerEpoch(100L);
        config.setFirstNonByronSlot(0L);
        return config;
    }

    private static com.bloxbean.cardano.yano.api.utxo.model.Utxo yanoUtxo(
            String txHash, int index, String address, long lovelace, List<AssetAmount> assets) {
        return new com.bloxbean.cardano.yano.api.utxo.model.Utxo(
                new Outpoint(txHash, index),
                address,
                BigInteger.valueOf(lovelace),
                assets,
                null,
                null,
                null,
                null,
                false,
                7,
                3,
                "block");
    }

    private static final class FakeYano implements Yano {
        final FakeTxGateway txGateway = new FakeTxGateway();
        final FakeTxEvaluationGateway txEvaluationGateway = new FakeTxEvaluationGateway();
        private final List<com.bloxbean.cardano.yano.api.utxo.model.Utxo> utxos;
        private final boolean utxoEnabled;
        private final YanoConfig config;
        private final String protocolParamsJson;

        private FakeYano(List<com.bloxbean.cardano.yano.api.utxo.model.Utxo> utxos, boolean utxoEnabled) {
            this(utxos, utxoEnabled, defaultConfig(), "{}");
        }

        private FakeYano(List<com.bloxbean.cardano.yano.api.utxo.model.Utxo> utxos,
                             boolean utxoEnabled,
                             YanoConfig config,
                             String protocolParamsJson) {
            this.utxos = utxos;
            this.utxoEnabled = utxoEnabled;
            this.config = config;
            this.protocolParamsJson = protocolParamsJson;
        }

        @Override
        public NodeLifecycle lifecycle() {
            return new FakeLifecycle(config);
        }

        @Override
        public ChainQuery chain() {
            return new FakeChainQuery();
        }

        @Override
        public LedgerQuery ledger() {
            return new FakeLedgerQuery(utxos, utxoEnabled, protocolParamsJson);
        }

        @Override
        public TxGateway txGateway() {
            return txGateway;
        }

        @Override
        public TxEvaluationGateway txEvaluationGateway() {
            return txEvaluationGateway;
        }

        @Override
        public Optional<ProducerControl> producerControl() {
            return Optional.empty();
        }

        @Override
        public Optional<DevnetControl> devnetControl() {
            return Optional.of(new FakeDevnetControl());
        }

        @Override
        public Optional<NodeKernel> kernel() {
            return Optional.empty();
        }
    }

    private static final class FakeLifecycle implements NodeLifecycle {
        private final YanoConfig config;

        private FakeLifecycle(YanoConfig config) {
            this.config = config;
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public boolean isSyncing() {
            return false;
        }

        @Override
        public boolean isServerRunning() {
            return false;
        }

        @Override
        public NodeStatus getStatus() {
            return NodeStatus.builder().running(true).timestamp(System.currentTimeMillis()).build();
        }

        @Override
        public NodeConfig getConfig() {
            return config;
        }

        @Override
        public void addNodeEventListener(NodeEventListener listener) {
        }

        @Override
        public void removeNodeEventListener(NodeEventListener listener) {
        }
    }

    private static final class FakeChainQuery implements ChainQuery {
        @Override
        public ChainTip getLocalTip() {
            return new ChainTip(7, new byte[]{1, 2, 3}, 3);
        }

        @Override
        public byte[] getBlockByNumber(long blockNumber) {
            return null;
        }

        @Override
        public Era getBlockEra(long blockNumber) {
            return null;
        }

        @Override
        public byte[] getBlock(byte[] blockHash) {
            return null;
        }

        @Override
        public boolean recoverChain() {
            return false;
        }

        @Override
        public void addBlockChainDataListener(BlockChainDataListener listener) {
        }

        @Override
        public void removeBlockChainDataListener(BlockChainDataListener listener) {
        }

        @Override
        public void registerListeners(Object... listeners) {
        }

        @Override
        public void registerListener(Object listener, SubscriptionOptions sbOptions) {
        }
    }

    private static final class FakeLedgerQuery implements LedgerQuery {
        private final List<com.bloxbean.cardano.yano.api.utxo.model.Utxo> utxos;
        private final boolean utxoEnabled;
        private final String protocolParamsJson;

        private FakeLedgerQuery(List<com.bloxbean.cardano.yano.api.utxo.model.Utxo> utxos,
                                boolean utxoEnabled,
                                String protocolParamsJson) {
            this.utxos = utxos;
            this.utxoEnabled = utxoEnabled;
            this.protocolParamsJson = protocolParamsJson;
        }

        @Override
        public UtxoState getUtxoState() {
            return new FakeUtxoState(utxos, utxoEnabled);
        }

        @Override
        public String getProtocolParameters() {
            return protocolParamsJson;
        }

        @Override
        public GenesisParameters getGenesisParameters() {
            return null;
        }

        @Override
        public long slotToUnixTime(long slot) {
            return slot;
        }
    }

    private static final class FakeUtxoState implements UtxoState {
        private final List<com.bloxbean.cardano.yano.api.utxo.model.Utxo> utxos;
        private final boolean enabled;

        private FakeUtxoState(List<com.bloxbean.cardano.yano.api.utxo.model.Utxo> utxos, boolean enabled) {
            this.utxos = utxos;
            this.enabled = enabled;
        }

        @Override
        public List<com.bloxbean.cardano.yano.api.utxo.model.Utxo> getUtxosByAddress(
                String bech32OrHexAddress, int page, int pageSize) {
            return utxos.stream()
                    .filter(utxo -> bech32OrHexAddress.equals(utxo.address()))
                    .toList();
        }

        @Override
        public List<com.bloxbean.cardano.yano.api.utxo.model.Utxo> getUtxosByPaymentCredential(
                String credentialHexOrAddress, int page, int pageSize) {
            return List.of();
        }

        @Override
        public Optional<com.bloxbean.cardano.yano.api.utxo.model.Utxo> getUtxo(Outpoint outpoint) {
            return utxos.stream()
                    .filter(utxo -> utxo.outpoint().equals(outpoint))
                    .findFirst();
        }

        @Override
        public Optional<com.bloxbean.cardano.yano.api.utxo.model.Utxo> getUtxoSpentOrUnspent(Outpoint outpoint) {
            return getUtxo(outpoint);
        }

        @Override
        public List<com.bloxbean.cardano.yano.api.utxo.model.Utxo> getOutputsByTxHash(String txHash) {
            return utxos.stream()
                    .filter(utxo -> txHash.equals(utxo.outpoint().txHash()))
                    .toList();
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }
    }

    private static final class FakeTxGateway implements TxGateway {
        final List<byte[]> submittedCbor = new ArrayList<>();
        String txHash = "tx";

        @Override
        public String submitTransaction(byte[] txCbor) {
            submittedCbor.add(Arrays.copyOf(txCbor, txCbor.length));
            return txHash;
        }
    }

    private static final class FakeTxEvaluationGateway implements TxEvaluationGateway {
        boolean available;
        List<TxEvaluationResult> results = List.of();

        @Override
        public boolean isTransactionEvaluationAvailable() {
            return available;
        }

        @Override
        public List<TxEvaluationResult> evaluateTransaction(byte[] txCbor) {
            return results;
        }
    }

    private static final class FakeDevnetControl implements DevnetControl {
        @Override
        public void rollbackDevnetToSlot(long targetSlot) {
        }

        @Override
        public SnapshotInfo createDevnetSnapshot(String name) {
            return null;
        }

        @Override
        public void restoreDevnetSnapshot(String name) {
        }

        @Override
        public List<SnapshotInfo> listDevnetSnapshots() {
            return List.of();
        }

        @Override
        public void deleteDevnetSnapshot(String name) {
        }

        @Override
        public FundResult fundAddress(String address, long lovelace) {
            return null;
        }

        @Override
        public TimeAdvanceResult advanceTimeBySlots(int slots) {
            return null;
        }

        @Override
        public TimeAdvanceResult advanceTimeBySeconds(int seconds) {
            return null;
        }
    }
}
