package compat;

import com.bloxbean.cardano.yaci.events.api.EventBus;
import org.yanoproject.api.ChainBlockReader;
import org.yanoproject.api.TxEvaluationGateway;
import org.yanoproject.api.appchain.AppBlock;
import org.yanoproject.api.appchain.AppChainConfig;
import org.yanoproject.api.appchain.AppQueryException;
import org.yanoproject.api.appchain.codec.AppBlockCodec;
import org.yanoproject.api.appchain.l1view.L1EpochStateProvider;
import org.yanoproject.p2p.peer.PeerClientFactory;
import org.yanoproject.runtime.appchain.AppChainManager;
import org.yanoproject.runtime.appchain.AppChainSubsystem;
import org.yanoproject.runtime.plugins.PluginProviderRegistry;
import org.slf4j.Logger;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/** Frozen pre19 bytecode; the compatibility test loads these bytes without compiling this source. */
public final class Pre19RuntimeClient {
    public static List<String> exercise(AppChainConfig config, String ledgerPath, EventBus events,
                                        Logger log, ChainBlockReader chain,
                                        L1EpochStateProvider epochs, TxEvaluationGateway evaluation)
            throws Exception {
        List<String> completed = new ArrayList<>();
        AppChainSubsystem.AnchorFeeParams linear = new AppChainSubsystem.AnchorFeeParams(44, 155381);
        require(linear.minFeeA() == 44 && linear.minFeeB() == 155381, "linear fee accessors");
        require(linear.priceMem() == null && linear.priceStep() == null && linear.costModelV3() == null,
                "legacy fee defaults");
        AppChainSubsystem.AnchorFeeParams script = new AppChainSubsystem.AnchorFeeParams(
                45, 155382, new BigDecimal("0.0577"), new BigDecimal("0.0000721"), new long[]{1, 2});
        require(script.minFeeA() == 45 && script.minFeeB() == 155382, "script linear fees");
        require(script.priceMem().equals(new BigDecimal("0.0577")), "memory price");
        require(script.priceStep().equals(new BigDecimal("0.0000721")), "step price");
        require(Arrays.equals(script.costModelV3(), new long[]{1, 2}), "cost model");
        completed.add("nested-fee-record");

        try (AppChainSubsystem minimal = new AppChainSubsystem(config, 424242, events, log);
             AppChainSubsystem machine = new AppChainSubsystem(config, 424242, events,
                     null, ledgerPath, log);
             AppChainSubsystem loader = new AppChainSubsystem(config, 424242, events,
                     null, ledgerPath, Pre19RuntimeClient.class.getClassLoader(), log)) {
            require(minimal.chainId().equals(config.chainId()), "minimal constructor");
            require(machine.chainId().equals(config.chainId()), "machine constructor");
            require(loader.chainId().equals(config.chainId()), "loader constructor");
            loader.wireAnchorFees(() -> script);
            loader.wireAnchorProtocolParams(() -> null);
            loader.wireL1(bytes -> "fixture-transaction", () -> null);
            loader.wireL1Chain(chain, null);
            loader.wireL1EpochState(epochs);
            loader.wireTxEvaluation(evaluation);
            loader.registerBridgeDiffusionHandler(message -> { });
        }
        completed.add("constructors-and-cardano-wiring");

        AppChainSubsystem runtime = new AppChainSubsystem(config, 424242, events,
                null, ledgerPath, Pre19RuntimeClient.class.getClassLoader(), PluginProviderRegistry.empty(), log);
        try (AppChainManager manager = new AppChainManager(List.of(runtime), log)) {
            require(manager.byId(config.chainId()).orElseThrow() == runtime, "manager gateway lookup");
            require(manager.all().size() == 1, "manager gateway collection");
            require(manager.name().equals("app-chain"), "manager lifecycle name");
            require(runtime.name() != null, "subsystem lifecycle name");
            manager.wireL1EpochState(epochs);
            require(manager.serverAgentFactories().stream().map(factory -> factory.createAgent().getProtocolId())
                    .toList().equals(List.of(100, 103)), "manager protocol factories");
            require(runtime.serverAgentFactories().size() == 2, "subsystem protocol factories");
            PeerClientFactory factory = (endpoint, point) -> null;
            require(manager.wrapPeerClientFactory(factory, "dedicated", "localhost", 3001) == factory,
                    "manager peer factory");
            completed.add("manager-protocol-and-gateway-api");

            manager.start();
            require(manager.health() != null && runtime.health() != null, "inherited health");
            runtime.pauseSubmissions();
            require(runtime.submissionsPaused(), "inherited pause");
            runtime.resumeSubmissions();
            require(!runtime.submissionsPaused(), "inherited resume");
            byte[] id = HexFormat.of().parseHex(runtime.submit("legacy", "pre19 client".getBytes(StandardCharsets.UTF_8)));
            long deadline = System.nanoTime() + 8_000_000_000L;
            while (runtime.messageHeight(id).isEmpty() && System.nanoTime() < deadline) Thread.sleep(20);
            long height = runtime.messageHeight(id).orElseThrow();
            AppBlock block = runtime.block(height).orElseThrow();
            require(runtime.tipHeight() == height, "inherited tip");
            require(Arrays.equals(runtime.stateRoot(), block.stateRoot()), "inherited root");
            require(runtime.stateCommitmentIdentity().isPresent(), "inherited identity");
            require(runtime.messageInclusionProof(id).isPresent(), "inherited message proof");
            require(runtime.members().equals(config.memberKeysHex()), "inherited membership");
            require(runtime.effectiveThreshold() == config.threshold(), "inherited threshold");
            require(!runtime.status().isEmpty(), "inherited status");
            try {
                runtime.query("unsupported", new byte[0]);
                throw new AssertionError("default ordered-log query must remain unsupported");
            } catch (AppQueryException expected) {
                require(expected.code() == AppQueryException.Code.UNSUPPORTED, "inherited query");
            }
            completed.add("inherited-finality-and-query-api");

            runtime.stop();
            startAfterDrain(runtime::start, config.chainId());
            manager.stop();
            startAfterDrain(manager::start, config.chainId());
            require(Arrays.equals(AppBlockCodec.blockHash(runtime.block(height).orElseThrow()),
                    AppBlockCodec.blockHash(block)), "persisted block across inherited lifecycle");
            completed.add("inherited-restart-persistence");
        }
        completed.add("inherited-close");
        return completed;
    }

    private static void startAfterDrain(Runnable start, String chainId) throws InterruptedException {
        String expected = "App-chain '" + chainId
                + "' is still draining a previous runtime callback; retry start after shutdown completes";
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (true) {
            try {
                start.run();
                return;
            } catch (IllegalStateException draining) {
                if (!expected.equals(draining.getMessage()) || draining.getCause() != null
                        || draining.getSuppressed().length != 0 || System.nanoTime() - deadline >= 0) {
                    throw draining;
                }
                Thread.sleep(10);
                if (System.nanoTime() - deadline >= 0) {
                    throw draining;
                }
            }
        }
    }

    private static void require(boolean condition, String operation) {
        if (!condition) throw new AssertionError(operation);
    }
}
