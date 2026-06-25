package com.bloxbean.cardano.yano.testkit.devnet;

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
import com.bloxbean.cardano.yano.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yano.api.utxo.model.Utxo;
import com.bloxbean.cardano.yano.runtime.assembly.YanoNode;
import com.bloxbean.cardano.yano.runtime.kernel.NodeKernel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class TestkitFakes {
    private TestkitFakes() {
    }

    static YanoDevnetTestKit kit(NodeStatus status) {
        return YanoDevnetTestKit.from(new FakeYanoNode(status));
    }

    static YanoDevnetTestKit kit(NodeStatus status, List<Utxo> utxos) {
        return YanoDevnetTestKit.from(new FakeYanoNode(status, utxos));
    }

    static YanoDevnetTestKit kit(NodeStatus status, List<Utxo> utxos, boolean utxoEnabled) {
        return YanoDevnetTestKit.from(new FakeYanoNode(status, utxos, null, utxoEnabled));
    }

    static YanoDevnetTestKit kit(NodeStatus status, List<Utxo> utxos, ChainTip tip) {
        return YanoDevnetTestKit.from(new FakeYanoNode(status, utxos, tip));
    }

    static NodeStatus status(boolean running, boolean degraded) {
        return NodeStatus.builder()
                .running(running)
                .runtimeDegraded(degraded)
                .runtimeDegradedReason(degraded ? "test degradation" : null)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    static final class FakeYanoNode implements YanoNode {
        final FakeLifecycle lifecycle;
        final FakeDevnetControl devnet = new FakeDevnetControl();
        final FakeTxGateway txGateway = new FakeTxGateway();
        final FakeTxEvaluationGateway txEvaluationGateway = new FakeTxEvaluationGateway();
        private final List<Utxo> utxos;
        private ChainTip tip;
        private final boolean utxoEnabled;

        FakeYanoNode(NodeStatus status) {
            this(status, List.of());
        }

        FakeYanoNode(NodeStatus status, List<Utxo> utxos) {
            this(status, utxos, null);
        }

        FakeYanoNode(NodeStatus status, List<Utxo> utxos, ChainTip tip) {
            this(status, utxos, tip, true);
        }

        FakeYanoNode(NodeStatus status, List<Utxo> utxos, ChainTip tip, boolean utxoEnabled) {
            this.lifecycle = new FakeLifecycle(status);
            this.utxos = utxos != null ? List.copyOf(utxos) : List.of();
            this.tip = tip;
            this.utxoEnabled = utxoEnabled;
        }

        @Override
        public NodeLifecycle lifecycle() {
            return lifecycle;
        }

        @Override
        public ChainQuery chain() {
            return new FakeChainQuery(tip);
        }

        @Override
        public LedgerQuery ledger() {
            return new FakeLedgerQuery(utxos, utxoEnabled);
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
            return Optional.of(devnet);
        }

        @Override
        public Optional<NodeKernel> kernel() {
            return Optional.empty();
        }
    }

    static final class FakeLifecycle implements NodeLifecycle {
        private final NodeStatus status;
        boolean running;
        int startCount;
        int stopCount;

        FakeLifecycle(NodeStatus status) {
            this.status = status;
            this.running = status != null && status.isRunning();
        }

        @Override
        public void start() {
            running = true;
            startCount++;
        }

        @Override
        public void stop() {
            running = false;
            stopCount++;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public boolean isSyncing() {
            return false;
        }

        @Override
        public boolean isServerRunning() {
            return running;
        }

        @Override
        public NodeStatus getStatus() {
            return status;
        }

        @Override
        public NodeConfig getConfig() {
            YanoConfig config = YanoConfig.devnetDefault(13337);
            config.setEpochLength(100L);
            config.setByronSlotsPerEpoch(100L);
            config.setFirstNonByronSlot(0L);
            return config;
        }

        @Override
        public void addNodeEventListener(NodeEventListener listener) {
        }

        @Override
        public void removeNodeEventListener(NodeEventListener listener) {
        }
    }

    static final class FakeDevnetControl implements DevnetControl {
        final List<SnapshotInfo> snapshots = new ArrayList<>();
        final List<String> restoredSnapshots = new ArrayList<>();
        final List<String> fundedAddresses = new ArrayList<>();
        final List<Integer> advancedSlots = new ArrayList<>();
        final List<Long> targetSlots = new ArrayList<>();

        @Override
        public void rollbackDevnetToSlot(long targetSlot) {
        }

        @Override
        public SnapshotInfo createDevnetSnapshot(String name) {
            SnapshotInfo snapshot = new SnapshotInfo(name, 0, 0, System.currentTimeMillis());
            snapshots.add(snapshot);
            return snapshot;
        }

        @Override
        public void restoreDevnetSnapshot(String name) {
            restoredSnapshots.add(name);
        }

        @Override
        public List<SnapshotInfo> listDevnetSnapshots() {
            return List.copyOf(snapshots);
        }

        @Override
        public void deleteDevnetSnapshot(String name) {
            snapshots.removeIf(snapshot -> name.equals(snapshot.name()));
        }

        @Override
        public FundResult fundAddress(String address, long lovelace) {
            fundedAddresses.add(address);
            return new FundResult("tx", 0, lovelace);
        }

        @Override
        public TimeAdvanceResult advanceTimeBySlots(int slots) {
            advancedSlots.add(slots);
            return new TimeAdvanceResult(slots, slots, slots);
        }

        @Override
        public TimeAdvanceResult advanceTimeUntilSlot(long targetSlot) {
            targetSlots.add(targetSlot);
            return new TimeAdvanceResult(targetSlot, targetSlot, 1);
        }

        @Override
        public TimeAdvanceResult advanceTimeBySeconds(int seconds) {
            return new TimeAdvanceResult(seconds, seconds, seconds);
        }
    }

    static final class FakeTxGateway implements TxGateway {
        final List<byte[]> submittedCbor = new ArrayList<>();
        String txHash = "tx";

        @Override
        public String submitTransaction(byte[] txCbor) {
            submittedCbor.add(txCbor);
            return txHash;
        }
    }

    static final class FakeTxEvaluationGateway implements TxEvaluationGateway {
        boolean available;
        List<TxEvaluationResult> results = List.of();
        final List<byte[]> evaluatedCbor = new ArrayList<>();

        @Override
        public boolean isTransactionEvaluationAvailable() {
            return available;
        }

        @Override
        public List<TxEvaluationResult> evaluateTransaction(byte[] txCbor) {
            if (!available) {
                throw new UnsupportedOperationException("Transaction evaluation is not available");
            }
            evaluatedCbor.add(txCbor);
            return results;
        }
    }

    private static final class FakeChainQuery implements ChainQuery {
        private final ChainTip tip;

        private FakeChainQuery(ChainTip tip) {
            this.tip = tip;
        }

        @Override
        public ChainTip getLocalTip() {
            return tip;
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
        private final List<Utxo> utxos;
        private final boolean utxoEnabled;

        private FakeLedgerQuery(List<Utxo> utxos, boolean utxoEnabled) {
            this.utxos = utxos;
            this.utxoEnabled = utxoEnabled;
        }

        @Override
        public UtxoState getUtxoState() {
            return new FakeUtxoState(utxos, utxoEnabled);
        }

        @Override
        public String getProtocolParameters() {
            return "{}";
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
        private final List<Utxo> utxos;
        private final boolean enabled;

        private FakeUtxoState(List<Utxo> utxos, boolean enabled) {
            this.utxos = utxos;
            this.enabled = enabled;
        }

        @Override
        public List<Utxo> getUtxosByAddress(String bech32OrHexAddress, int page, int pageSize) {
            List<Utxo> matching = utxos.stream()
                    .filter(utxo -> bech32OrHexAddress.equals(utxo.address()))
                    .toList();
            int from = Math.max(0, (page - 1) * pageSize);
            if (from >= matching.size()) {
                return List.of();
            }
            int to = Math.min(matching.size(), from + pageSize);
            return matching.subList(from, to);
        }

        @Override
        public List<Utxo> getUtxosByPaymentCredential(String credentialHexOrAddress, int page, int pageSize) {
            return List.of();
        }

        @Override
        public Optional<Utxo> getUtxo(Outpoint outpoint) {
            return utxos.stream()
                    .filter(utxo -> utxo.outpoint().equals(outpoint))
                    .findFirst();
        }

        @Override
        public List<Utxo> getOutputsByTxHash(String txHash) {
            return utxos.stream()
                    .filter(utxo -> utxo.outpoint() != null && txHash.equals(utxo.outpoint().txHash()))
                    .toList();
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }
    }
}
