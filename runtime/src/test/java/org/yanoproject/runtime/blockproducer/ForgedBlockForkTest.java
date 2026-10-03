package org.yanoproject.runtime.blockproducer;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.events.api.SubscriptionOptions;
import com.bloxbean.cardano.yaci.events.impl.NoopEventBus;
import com.bloxbean.cardano.yaci.helper.PeerClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yanoproject.api.config.RuntimeOptions;
import org.yanoproject.api.config.YanoConfig;
import org.yanoproject.api.events.RollbackEvent;
import org.yanoproject.ledgerstate.AccountStateEventHandler;
import org.yanoproject.ledgerstate.DefaultAccountStateStore;
import org.yanoproject.p2p.peer.PeerRecoveryReason;
import org.yanoproject.runtime.BodyFetchManager;
import org.yanoproject.runtime.HeaderSyncManager;
import org.yanoproject.runtime.PipelineDataListener;
import org.yanoproject.runtime.chain.DirectRocksDBChainState;
import org.yanoproject.runtime.chain.InMemoryChainState;
import org.yanoproject.runtime.events.PropagatingEventBus;
import org.yanoproject.runtime.ledger.LedgerStateSubsystem;
import org.yanoproject.runtime.ledger.canonical.CanonicalStateGate;
import org.yanoproject.runtime.peer.PeerSessionCallbacks;
import org.yanoproject.runtime.producer.SlotLeaderKeyMaterial;
import org.yanoproject.runtime.producer.SlotLeaderSigningComponents;
import org.yanoproject.runtime.server.ServeSubsystem;
import org.yanoproject.runtime.storage.ChainStorageSubsystem;
import org.yanoproject.runtime.sync.SyncSubsystem;
import org.yanoproject.runtime.tx.BlockTransactionSelector;
import org.yanoproject.runtime.tx.TransactionAdmission;
import org.yanoproject.runtime.utxo.DefaultUtxoStore;
import org.yanoproject.runtime.utxo.UtxoEventHandler;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A block this node forged competes with a block of the upstream it follows: the upstream wins. The node follows
 * the upstream through the real header and body pipeline ({@link PipelineDataListener}, {@link HeaderSyncManager},
 * {@link BodyFetchManager}) and, where stated, the runtime's rollback path ({@link SyncSubsystem}), over a RocksDB
 * chain store with the UTxO and account-state stores listening, and forges with {@link SlotLeaderBlockProducer}.
 */
class ForgedBlockForkTest {
    private static final Path DEVNET_FIXTURE = Path.of("src/test/resources/devnet");
    // The system start lies a day ahead, so the producer's own slot ticks never fire during a test.
    private static final SlotClock FUTURE_CLOCK =
            SlotClock.shelleyOnly(System.currentTimeMillis() + 86_400_000L, 1_000);
    private static final String ADDRESS = AddressProvider.getEntAddress(
            Credential.fromKey(new byte[28]), Networks.testnet()).toBech32();
    private static final Logger LOG = LoggerFactory.getLogger(ForgedBlockForkTest.class);

    @TempDir
    Path tempDir;

    private final List<AutoCloseable> closeables = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        for (int i = closeables.size() - 1; i >= 0; i--) {
            closeables.get(i).close();
        }
        BlockProducerHelper.resetEpochTrackingToSlot(-1);
    }

    @Test
    void anUpstreamBlockAtTheForgedHeightRollsTheForgedBlockBackAndReplacesIt() throws Exception {
        Upstream upstream = new Upstream();
        byte[] tx1 = tx("00".repeat(32), 0, 5_000_000, 3_000_000);
        var genesis = upstream.block(0, 0, null, List.of());
        var block1 = upstream.block(1, 1, genesis.blockHash(), List.of(tx1));
        byte[] forgedTx = tx(txHash(tx1), 0, 4_800_000);
        byte[] upstreamTx = tx(txHash(tx1), 1, 2_800_000);
        var block2 = upstream.block(2, 2, block1.blockHash(), List.of(upstreamTx));

        Node node = new Node(tempDir.resolve("node"), null);
        node.storeGenesis(genesis);
        node.receive(block1);
        SlotLeaderBlockProducer producer = node.producer(List.of(forgedTx));
        producer.checkSlot(3);
        ChainTip forged = node.chainState.getTip();
        assertThat(forged.getBlockNumber()).isEqualTo(2);
        assertThat(forged.getSlot()).isEqualTo(3);
        assertThat(node.utxos()).as("the forged block was applied").contains(txHash(forgedTx) + "#0:4800000");

        // The upstream does not have the forged block: its next block follows block 1, at the forged height.
        node.receive(block2);

        assertThat(node.rollbacks).containsExactly(new Point(1, hex(block1.blockHash())));
        assertThat(node.chainState.getTip().getBlockHash()).isEqualTo(block2.blockHash());
        assertThat(node.chainState.getHeaderTip().getBlockHash()).isEqualTo(block2.blockHash());
        assertThat(node.chainState.hasPoint(new Point(3, hex(forged.getBlockHash()))))
                .as("a downstream chain-sync server rolls a client at the forged block back").isFalse();
        assertThat(node.chainState.getBlock(forged.getBlockHash())).isNull();

        // Ledger state equals that of a node that never saw the forged block.
        Node reference = new Node(tempDir.resolve("reference"), null);
        reference.storeGenesis(genesis);
        reference.receive(block1);
        reference.receive(block2);
        assertThat(node.utxos()).isEqualTo(reference.utxos())
                .containsExactlyInAnyOrder(txHash(tx1) + "#0:5000000", txHash(upstreamTx) + "#0:2800000");
        assertThat(node.accounts.getPoolBlockCounts(0)).isEqualTo(reference.accounts.getPoolBlockCounts(0))
                .containsValue(3L);
        assertThat(node.accounts.getEpochFees(0)).isEqualTo(reference.accounts.getEpochFees(0));
        assertThat(node.accounts.getOpCertCounterState(upstream.issuerHash()))
                .isEqualTo(reference.accounts.getOpCertCounterState(upstream.issuerHash()));

        // The slot stays forged, and the producer goes on from the upstream block.
        producer.checkSlot(3);
        assertThat(node.chainState.getTip().getBlockHash()).isEqualTo(block2.blockHash());
        producer.checkSlot(4);
        assertThat(node.chainState.getTip().getBlockNumber()).isEqualTo(3);
        assertThat(node.rollbacks).hasSize(1);
    }

    @Test
    void normalSyncAndAnAdoptedForgedBlockNeverRollBack() throws Exception {
        Upstream upstream = new Upstream();
        var genesis = upstream.block(0, 0, null, List.of());
        var block1 = upstream.block(1, 1, genesis.blockHash(), List.of());
        var block2 = upstream.block(2, 2, block1.blockHash(), List.of());
        RecordingCallbacks callbacks = new RecordingCallbacks();
        Node node = new Node(tempDir.resolve("node"), callbacks);
        node.storeGenesis(genesis);
        node.receive(block1);
        node.receive(block2);

        // The upstream adopts the next forged block, sends it back, then extends it.
        node.producer(List.of()).checkSlot(3);
        ChainTip forged = node.chainState.getTip();
        node.receiveHeader(new DevnetBlockBuilder.BlockBuildResult(node.chainState.getBlock(forged.getBlockHash()),
                node.chainState.getBlockHeader(forged.getBlockHash()), forged.getBlockHash(), 3, 3));
        var block4 = upstream.block(4, 5, forged.getBlockHash(), List.of());
        node.receive(block4);

        assertThat(callbacks.rollbacks).isEmpty();
        assertThat(callbacks.recoveries).isEmpty();
        assertThat(node.chainState.getTip().getBlockHash()).isEqualTo(block4.blockHash());
        assertThat(node.chainState.getBlock(forged.getBlockHash())).isNotNull();
    }

    @Test
    void aCompetingHeaderWhoseParentIsNotLocalStoresNothingAndRequestsPeerRecovery() throws Exception {
        Upstream upstream = new Upstream();
        var genesis = upstream.block(0, 0, null, List.of());
        var block1 = upstream.block(1, 1, genesis.blockHash(), List.of());
        var otherBlock1 = upstream.block(1, 2, genesis.blockHash(), List.of());
        var block2 = upstream.block(2, 3, otherBlock1.blockHash(), List.of());
        RecordingCallbacks callbacks = new RecordingCallbacks();
        Node node = new Node(tempDir.resolve("node"), callbacks);
        node.storeGenesis(genesis);
        node.receive(block1);
        node.producer(List.of()).checkSlot(2);
        ChainTip forged = node.chainState.getTip();

        node.receiveHeader(block2);

        assertThat(callbacks.recoveries).containsExactly(PeerRecoveryReason.APPLY_FAILED);
        assertThat(callbacks.rollbacks).isEmpty();
        assertThat(node.chainState.getHeaderTip().getBlockHash()).isEqualTo(forged.getBlockHash());
        assertThat(node.chainState.getTip().getBlockHash()).isEqualTo(forged.getBlockHash());
        assertThat(node.chainState.getBlockHeader(block2.blockHash())).isNull();
    }

    @Test
    void theProducerDiscardsItsBlockWhileAnUpstreamHeaderIsBeingStored() throws Exception {
        Upstream upstream = new Upstream();
        var genesis = upstream.block(0, 0, null, List.of());
        Node node = new Node(tempDir.resolve("node"), new RecordingCallbacks());
        node.storeGenesis(genesis);
        SlotLeaderBlockProducer producer = node.producer(List.of());

        var extension = CanonicalStateGate.of(node.chainState).chainExtensionLock();
        Thread holder = Thread.ofPlatform().start(extension::lock);
        holder.join();
        producer.checkSlot(1);

        assertThat(node.chainState.getTip().getBlockHash()).isEqualTo(genesis.blockHash());
        assertThat(node.chainState.getLastForgedSlot()).as("nothing was forged").isEqualTo(-1);
    }

    // ---------------------------------------------------------------- fixture

    /** Builds the upstream's blocks with the devnet keys, independent of any node's nonce state. */
    private static final class Upstream {
        private final SignedBlockBuilder builder = signing(newNonceState(), new InMemoryChainState())
                .signedBlockBuilder();

        DevnetBlockBuilder.BlockBuildResult block(long number, long slot, byte[] prevHash, List<byte[]> txs) {
            var result = builder.buildBlock(number, slot, prevHash, txs);
            builder.rollbackPendingNonceState();
            return result;
        }

        String issuerHash() {
            return HexUtil.encodeHexString(Blake2bUtil.blake2bHash224(
                    HexUtil.decodeHexString(builder.getIssuerVkeyHex())));
        }
    }

    /**
     * A node that follows the upstream through the pipeline and forges with the slot-leader producer. Its
     * rollbacks run through the runtime's {@link SyncSubsystem}, or through {@code callbacks} when given.
     */
    private final class Node {
        final DirectRocksDBChainState chainState;
        final PropagatingEventBus bus = new PropagatingEventBus();
        final DefaultUtxoStore utxoStore;
        final DefaultAccountStateStore accounts;
        final PipelineDataListener listener;
        final List<Point> rollbacks = new CopyOnWriteArrayList<>();
        final EpochNonceState nonceState = newNonceState();
        final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        Node(Path dir, PeerSessionCallbacks callbacks) {
            YanoConfig config = YanoConfig.builder().protocolMagic(42L).enableServer(false).enableClient(false)
                    .useRocksDB(true).rocksDBPath(dir.toString()).build();
            RuntimeOptions options = new RuntimeOptions(null, null, Map.of("yano.account-state.enabled", false));
            ChainStorageSubsystem storage = new ChainStorageSubsystem(config, options, LOG);
            chainState = (DirectRocksDBChainState) storage.chainState();
            closeables.add(() -> storage.closeAfterRuntimeDrain(false));
            closeables.add(bus::close);
            closeables.add(scheduler::shutdownNow);

            utxoStore = new DefaultUtxoStore(chainState, LOG, Map.of("yano.utxo.enabled", true));
            new UtxoEventHandler(bus, utxoStore);
            var rocks = chainState.rocks();
            accounts = new DefaultAccountStateStore(rocks.db(), rocks::handle, LOG, true);
            new AccountStateEventHandler(bus, accounts);
            bus.subscribe(RollbackEvent.class, ctx -> rollbacks.add(ctx.event().target()),
                    SubscriptionOptions.builder().build());

            PeerClient peerClient = new PeerClient("upstream", 3001, 42, Point.ORIGIN);
            listener = new PipelineDataListener(new HeaderSyncManager(peerClient, chainState),
                    new BodyFetchManager(peerClient, chainState, bus),
                    callbacks != null ? callbacks : syncSubsystem(config, storage));
        }

        private SyncSubsystem syncSubsystem(YanoConfig config, ChainStorageSubsystem storage) {
            RuntimeOptions options = new RuntimeOptions(null, null, Map.of("yano.account-state.enabled", false));
            LedgerStateSubsystem ledgerState = new LedgerStateSubsystem(config, options, chainState,
                    new NoopEventBus(), LOG, null, null, null, null, () -> null, () -> null, () -> null, null);
            ServeSubsystem serve = new ServeSubsystem(0, config.getProtocolMagic(), chainState, noAdmission(),
                    false, LOG);
            SyncSubsystem sync = new SyncSubsystem(config, chainState, bus, scheduler, serve, ledgerState, storage,
                    () -> false, ledgerState::epochParamProvider, ledgerState::currentGenesisBootstrapData,
                    "localhost", 3001, config.getProtocolMagic(), LOG);
            closeables.add(ledgerState::close);
            closeables.add(serve::close);
            closeables.add(sync::close);
            return sync;
        }

        void storeGenesis(DevnetBlockBuilder.BlockBuildResult genesis) {
            chainState.storeBlockHeader(genesis.blockHash(), 0L, 0L, genesis.wrappedHeaderCbor());
            chainState.storeBlock(genesis.blockHash(), 0L, 0L, genesis.blockCbor());
            BlockProducerHelper.publishEvent(bus, genesis, 0, "test", false);
        }

        /** The upstream's chain-sync header, then its block-fetch body. */
        void receive(DevnetBlockBuilder.BlockBuildResult result) {
            receiveHeader(result);
            listener.onBlock(Era.Conway, decode(result), List.of());
        }

        void receiveHeader(DevnetBlockBuilder.BlockBuildResult result) {
            Tip tip = new Tip(new Point(result.slot(), hex(result.blockHash())), result.blockNumber());
            listener.rollforward(tip, decode(result).getHeader(), result.wrappedHeaderCbor());
        }

        SlotLeaderBlockProducer producer(List<byte[]> txs) throws Exception {
            SlotLeaderSigningComponents signing = signing(nonceState, chainState);
            SlotLeaderBlockProducer producer = new SlotLeaderBlockProducer(chainState, once(txs), () -> null, bus,
                    scheduler, signing.signedBlockBuilder(), nonceState, signing.slotLeaderCheck(), stake(), "pool",
                    FUTURE_CLOCK, tip -> true);
            producer.start();
            closeables.add(producer::stop);
            return producer;
        }

        SortedSet<String> utxos() {
            SortedSet<String> out = new TreeSet<>();
            utxoStore.forEachUtxoRecord(u -> out.add(u.outpoint().txHash() + "#" + u.outpoint().index() + ":"
                    + u.lovelace()));
            return out;
        }
    }

    /** Session callbacks that record rollbacks and recovery requests and do nothing else. */
    private static final class RecordingCallbacks implements PeerSessionCallbacks {
        final List<Point> rollbacks = new CopyOnWriteArrayList<>();
        final List<PeerRecoveryReason> recoveries = new CopyOnWriteArrayList<>();

        @Override
        public void resumeBodyFetchOnHeaderFlow() {
        }

        @Override
        public void updateSyncProgress(long slot, long blockNumber) {
        }

        @Override
        public void notifyServerNewBlockStored() {
        }

        @Override
        public void onIntersectionFound() {
        }

        @Override
        public void maybeFastTransitionToSteadyState(Tip remoteTip) {
        }

        @Override
        public void handleChainSyncRollback(Point point) {
            rollbacks.add(point);
        }

        @Override
        public void requestPeerRecovery(PeerRecoveryReason reason) {
            recoveries.add(reason);
        }
    }

    private static EpochNonceState newNonceState() {
        EpochNonceState state = new EpochNonceState(1200, 100, 1.0, Constants.BYRON_SLOTS_PER_EPOCH);
        state.initFromGenesis("forged-block-fork-test".getBytes());
        return state;
    }

    private static SlotLeaderSigningComponents signing(EpochNonceState nonceState, NonceStateStore nonceStore) {
        try {
            return SlotLeaderSigningComponents.create(
                    SlotLeaderKeyMaterial.load(DEVNET_FIXTURE.resolve("vrf.skey"), DEVNET_FIXTURE.resolve("kes.skey"),
                            DEVNET_FIXTURE.resolve("opcert.cert")),
                    129600, 60, nonceState, nonceStore, ProtocolVersionSupplier.fixed(10, 0), 1.0);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A transaction spending {@code txId#index} into outputs of the given amounts, with a fee of 0.2 ADA. */
    private static byte[] tx(String txId, int index, long... amounts) throws Exception {
        List<TransactionOutput> outputs = new ArrayList<>();
        for (long amount : amounts) {
            outputs.add(new TransactionOutput(ADDRESS, Value.builder().coin(BigInteger.valueOf(amount)).build()));
        }
        TransactionBody body = TransactionBody.builder()
                .inputs(List.of(new TransactionInput(txId, index)))
                .outputs(outputs)
                .fee(BigInteger.valueOf(200_000))
                .build();
        return Transaction.builder().body(body).witnessSet(new TransactionWitnessSet()).build().serialize();
    }

    private static String txHash(byte[] tx) {
        return TransactionUtil.getTxHash(tx);
    }

    private static String hex(byte[] bytes) {
        return HexUtil.encodeHexString(bytes);
    }

    /** The block as block fetch delivers it, with its CBOR. */
    private static Block decode(DevnetBlockBuilder.BlockBuildResult result) {
        Block block = BlockSerializer.INSTANCE.deserialize(result.blockCbor());
        return new Block(block.getEra(), block.getHeader(), block.getTransactionBodies(),
                block.getTransactionWitness(), block.getAuxiliaryDataMap(), block.getInvalidTransactions(),
                hex(result.blockCbor()));
    }

    private static BlockTransactionSelector once(List<byte[]> txs) {
        List<byte[]> pending = new ArrayList<>(txs);
        return new BlockTransactionSelector() {
            @Override
            public boolean hasPendingTransactions() {
                return !pending.isEmpty();
            }

            @Override
            public List<byte[]> drainForBlock() {
                List<byte[]> drained = List.copyOf(pending);
                pending.clear();
                return drained;
            }
        };
    }

    private static StakeDataProvider stake() {
        return new StakeDataProvider() {
            @Override
            public BigInteger getPoolStake(String poolHash, int epoch) {
                return BigInteger.ONE;
            }

            @Override
            public BigInteger getTotalStake(int epoch) {
                return BigInteger.ONE;
            }
        };
    }

    private static TransactionAdmission noAdmission() {
        return new TransactionAdmission() {
            @Override
            public String admitTransaction(byte[] txCbor, String origin) {
                return "tx";
            }

            @Override
            public int mempoolSize() {
                return 0;
            }
        };
    }
}
