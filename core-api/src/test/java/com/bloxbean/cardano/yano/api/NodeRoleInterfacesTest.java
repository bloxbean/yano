package com.bloxbean.cardano.yano.api;

import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yano.api.config.NodeConfig;
import com.bloxbean.cardano.yano.api.listener.NodeEventListener;
import com.bloxbean.cardano.yano.api.model.FundResult;
import com.bloxbean.cardano.yano.api.model.GenesisParameters;
import com.bloxbean.cardano.yano.api.model.NodeStatus;
import com.bloxbean.cardano.yano.api.model.SnapshotInfo;
import com.bloxbean.cardano.yano.api.model.TimeAdvanceResult;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NodeRoleInterfacesTest {

    private static class TestNodeRoles implements NodeLifecycle, ChainQuery, LedgerQuery, TxGateway, DevnetControl {
        private boolean running;
        private boolean syncing;
        private boolean serverRunning;

        @Override
        public void start() {
            running = true;
        }

        @Override
        public void stop() {
            running = false;
            syncing = false;
            serverRunning = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public boolean isSyncing() {
            return syncing;
        }

        @Override
        public boolean isServerRunning() {
            return serverRunning;
        }

        @Override
        public NodeStatus getStatus() {
            return NodeStatus.builder()
                    .running(running)
                    .syncing(syncing)
                    .serverRunning(serverRunning)
                    .timestamp(System.currentTimeMillis())
                    .build();
        }

        @Override
        public NodeConfig getConfig() {
            return null;
        }

        @Override
        public void addNodeEventListener(NodeEventListener listener) {
        }

        @Override
        public void removeNodeEventListener(NodeEventListener listener) {
        }

        @Override
        public ChainTip getLocalTip() {
            return null;
        }

        @Override
        public byte[] getBlock(byte[] blockHash) {
            return null;
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

        @Override
        public UtxoState getUtxoState() {
            return null;
        }

        @Override
        public String getProtocolParameters() {
            return null;
        }

        @Override
        public GenesisParameters getGenesisParameters() {
            return null;
        }

        @Override
        public long slotToUnixTime(long slot) {
            return 0;
        }

        @Override
        public String submitTransaction(byte[] txCbor) {
            return "dummy-hash";
        }

        @Override
        public void rollbackDevnetToSlot(long targetSlot) {
        }

        @Override
        public SnapshotInfo createDevnetSnapshot(String name) {
            return new SnapshotInfo(name, 0, 0, System.currentTimeMillis());
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
            return new FundResult("dummy-hash", 0, lovelace);
        }

        @Override
        public TimeAdvanceResult advanceTimeBySlots(int slots) {
            return new TimeAdvanceResult(slots, slots, slots);
        }

        @Override
        public TimeAdvanceResult advanceTimeBySeconds(int seconds) {
            return new TimeAdvanceResult(seconds, seconds, seconds);
        }
    }

    @Test
    void lifecycleRoleTracksOperationalState() {
        NodeLifecycle lifecycle = new TestNodeRoles();

        assertThat(lifecycle.isRunning()).isFalse();
        assertThat(lifecycle.isSyncing()).isFalse();
        assertThat(lifecycle.isServerRunning()).isFalse();

        lifecycle.start();
        assertThat(lifecycle.isRunning()).isTrue();

        lifecycle.stop();
        assertThat(lifecycle.isRunning()).isFalse();
        assertThat(lifecycle.isSyncing()).isFalse();
        assertThat(lifecycle.isServerRunning()).isFalse();
    }

    @Test
    void lifecycleRoleProvidesStatus() {
        NodeLifecycle lifecycle = new TestNodeRoles();

        NodeStatus status = lifecycle.getStatus();
        assertThat(status).isNotNull();
        assertThat(status.isRunning()).isFalse();
        assertThat(status.isSyncing()).isFalse();
        assertThat(status.isServerRunning()).isFalse();
        assertThat(status.getTimestamp()).isGreaterThan(0);

        lifecycle.start();
        assertThat(lifecycle.getStatus().isRunning()).isTrue();
    }

    @Test
    void lifecycleAndChainRolesSupportListenerManagement() {
        TestNodeRoles node = new TestNodeRoles();
        BlockChainDataListener blockchainListener = new BlockChainDataListener() {};
        NodeEventListener nodeListener = new NodeEventListener() {};

        assertThatCode(() -> {
            node.addBlockChainDataListener(blockchainListener);
            node.addNodeEventListener(nodeListener);
            node.removeBlockChainDataListener(blockchainListener);
            node.removeNodeEventListener(nodeListener);
        }).doesNotThrowAnyException();
    }

    @Test
    void chainAndLifecycleRolesExposeBlockAccessAndConfigSeparately() {
        TestNodeRoles node = new TestNodeRoles();

        assertThat(node.getBlock(new byte[0])).isNull();
        assertThat(node.getBlockByNumber(0)).isNull();
        assertThat(node.getBlockEra(0)).isNull();
        assertThat(node.getLocalTip()).isNull();
        assertThat(node.getConfig()).isNull();
    }

    @Test
    void txEvaluationGatewayDefaultsToUnavailable() {
        TxEvaluationGateway gateway = new TxEvaluationGateway() {};

        assertThat(gateway.isTransactionEvaluationAvailable()).isFalse();
        assertThatThrownBy(() -> gateway.evaluateTransaction(new byte[0]))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("not available");
    }
}
