package org.yanoproject.tx.gate;

import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.yanoproject.api.config.YanoConfig;
import org.yanoproject.devnet.YanoDevnetAssembly;
import org.yanoproject.runtime.assembly.Yano;
import org.yanoproject.runtime.internal.RuntimeNode;
import org.yanoproject.runtime.ledger.canonical.CanonicalStateGate;
import org.yanoproject.runtime.tx.TransactionBootstrapOptions;
import org.yanoproject.runtime.validation.ValidationEngines;
import org.yanoproject.runtime.validation.shadowsync.ShadowSyncValidator;
import org.yanoproject.testkit.devnet.YanoDevnetTestConfig;
import org.yanoproject.tx.DefaultTransactionServicesFactory;

import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * An in-process Yano that follows a {@link DevnetGateNode} producer over node-to-node chain sync (ADR-056 Phase 7a
 * gate): the producer's genesis files and resolved genesis timestamp, its own temporary RocksDB, no block production,
 * no server, and the given runtime options on top of the producer's (for example {@code yano.validation.shadow-sync}).
 * Blocks arrive through the follower apply path ({@code BodyFetchManager.applyBlock}): the epoch boundary and the
 * block in one write section.
 */
public final class DevnetFollowerNode implements AutoCloseable {

    private final YanoDevnetTestConfig config;
    private final Yano node;
    private final RuntimeNode runtime;

    private DevnetFollowerNode(DevnetGateNode producer, Map<String, Object> options) {
        YanoConfig follower = YanoConfig.copyOf(producer.config().yanoConfig());
        follower.setEnableClient(true);
        follower.setRemoteHost("localhost");
        follower.setRemotePort(producer.config().yanoConfig().getServerPort());
        follower.setEnableBlockProducer(false);
        follower.setDevMode(false);
        follower.setEnableServer(false);
        // The packaged node's sync settings (YanoConfig.defaultForNetwork): pipelined header and body fetch.
        YanoConfig sync = YanoConfig.defaultForNetwork("preprod");
        follower.setFullSyncThreshold(sync.getFullSyncThreshold());
        follower.setEnablePipelinedSync(sync.isEnablePipelinedSync());
        follower.setHeaderPipelineDepth(sync.getHeaderPipelineDepth());
        follower.setBodyBatchSize(sync.getBodyBatchSize());
        follower.setMaxParallelBodies(sync.getMaxParallelBodies());
        follower.setServerPort(0);
        // The producer wrote its systemStart into the shared genesis file; an explicit timestamp never rewrites it.
        follower.setGenesisTimestamp(producer.genesisTimestamp());
        YanoDevnetTestConfig.Builder builder = YanoDevnetTestConfig.builder().yanoConfig(follower)
                .runtimeOptions(producer.config().runtimeOptions())
                .temporaryRocksDbStorage();
        options.forEach(builder::runtimeOption);
        this.config = builder.build();
        this.node = YanoDevnetAssembly.fromConfig(config.yanoConfig())
                .runtimeOptions(config.runtimeOptions())
                .transactionBootstrap(TransactionBootstrapOptions.enabled(true, false, "scalus"),
                        DefaultTransactionServicesFactory::create)
                .build();
        node.start();
        this.runtime = (RuntimeNode) node.chain();
    }

    /** Starts a follower of {@code producer} with {@code options} added to the producer's runtime options. */
    public static DevnetFollowerNode start(DevnetGateNode producer, Map<String, Object> options) {
        return new DevnetFollowerNode(Objects.requireNonNull(producer, "producer"), options);
    }

    public Yano node() {
        return node;
    }

    public CanonicalStateGate gate() {
        return runtime.getCanonicalStateGate();
    }

    /** @return the follower's shadow-sync validator, or {@code null} when shadow sync is off */
    public ShadowSyncValidator shadowSync() {
        ValidationEngines engines = runtime.getValidationEngines();
        return engines != null ? engines.shadowSync() : null;
    }

    /** @return the follower's tip block hash, or {@code null} */
    public String tipHash() {
        ChainTip tip = node.chain().getLocalTip();
        return tip != null && tip.getBlockHash() != null ? HexUtil.encodeHexString(tip.getBlockHash()) : null;
    }

    public long tipBlockNumber() {
        ChainTip tip = node.chain().getLocalTip();
        return tip != null ? tip.getBlockNumber() : -1;
    }

    /** Waits until the follower's tip is {@code producer}'s. */
    public void awaitTip(DevnetGateNode producer, long timeoutMillis) throws InterruptedException {
        BooleanSupplier same = () -> {
            ChainTip theirs = producer.node().chain().getLocalTip();
            return theirs != null && theirs.getBlockHash() != null
                    && HexUtil.encodeHexString(theirs.getBlockHash()).equals(tipHash());
        };
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!same.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("the follower did not reach the producer's tip: follower #"
                        + tipBlockNumber() + " " + tipHash() + ", producer #" + producer.tipBlockNumber());
            }
            Thread.sleep(100);
        }
    }

    @Override
    public void close() {
        try {
            node.close();
        } finally {
            config.close();
        }
    }
}
