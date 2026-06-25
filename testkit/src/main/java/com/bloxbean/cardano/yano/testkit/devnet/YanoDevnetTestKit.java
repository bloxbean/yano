package com.bloxbean.cardano.yano.testkit.devnet;

import com.bloxbean.cardano.yano.api.ChainQuery;
import com.bloxbean.cardano.yano.api.DevnetControl;
import com.bloxbean.cardano.yano.api.LedgerQuery;
import com.bloxbean.cardano.yano.api.NodeLifecycle;
import com.bloxbean.cardano.yano.api.ProducerControl;
import com.bloxbean.cardano.yano.api.TxEvaluationGateway;
import com.bloxbean.cardano.yano.api.TxGateway;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.devnet.YanoDevnetAssembly;
import com.bloxbean.cardano.yano.runtime.assembly.YanoNode;

import java.util.Objects;
import java.util.Optional;

/**
 * Small devnet test harness built on the public runtime assembly and
 * {@link DevnetControl} role.
 */
public final class YanoDevnetTestKit implements AutoCloseable {
    private final YanoNode node;
    private final DevnetControl devnet;
    private final AutoCloseable cleanup;
    private final YanoQueries queries;
    private final YanoAwait await;
    private final YanoWallets wallets;
    private final YanoFaucet faucet;
    private final YanoSnapshots snapshots;
    private final YanoTime time;
    private final YanoTransactions transactions;
    private final YanoAssertions assertions;

    /**
     * Builds a standard devnet test kit.
     *
     * @param config devnet configuration
     * @return managed test kit
     */
    public static YanoDevnetTestKit devnet(YanoConfig config) {
        return from(YanoDevnetAssembly.devnet(config)
                .runtimeOptions(YanoDevnetTestConfig.defaultRuntimeOptions())
                .build());
    }

    /**
     * Builds a standard devnet test kit from test-oriented config.
     *
     * @param config test devnet configuration
     * @return managed test kit
     */
    public static YanoDevnetTestKit devnet(YanoDevnetTestConfig config) {
        return build(config, false);
    }

    /**
     * Builds a past-time-travel devnet test kit with test-safe defaults.
     *
     * @return managed test kit
     */
    public static YanoDevnetTestKit devnetTimeTravel() {
        return devnetTimeTravel(YanoDevnetTestConfig.builder()
                .timeTravel(true)
                .build());
    }

    /**
     * Builds a past-time-travel devnet test kit.
     *
     * @param config devnet time-travel configuration
     * @return managed test kit
     */
    public static YanoDevnetTestKit devnetTimeTravel(YanoConfig config) {
        return from(YanoDevnetAssembly.devnetTimeTravel(config)
                .runtimeOptions(YanoDevnetTestConfig.defaultRuntimeOptions())
                .build());
    }

    /**
     * Builds a past-time-travel devnet test kit from test-oriented config.
     *
     * @param config test devnet configuration
     * @return managed test kit
     */
    public static YanoDevnetTestKit devnetTimeTravel(YanoDevnetTestConfig config) {
        return build(config, true);
    }

    static YanoDevnetTestKit from(YanoNode node) {
        Objects.requireNonNull(node, "node");
        DevnetControl devnet = node.devnetControl()
                .orElseThrow(() -> new IllegalArgumentException(
                        "YanoDevnetTestKit requires a node assembled with devnet-toolkit"));
        return new YanoDevnetTestKit(node, devnet);
    }

    private YanoDevnetTestKit(YanoNode node, DevnetControl devnet) {
        this(node, devnet, null);
    }

    private YanoDevnetTestKit(YanoNode node, DevnetControl devnet, AutoCloseable cleanup) {
        this.node = Objects.requireNonNull(node, "node");
        this.devnet = Objects.requireNonNull(devnet, "devnet");
        this.cleanup = cleanup;
        this.queries = new YanoQueries(node);
        this.await = new YanoAwait(queries);
        this.wallets = new YanoWallets(node.lifecycle());
        this.faucet = new YanoFaucet(devnet);
        this.snapshots = new YanoSnapshots(devnet);
        this.time = new YanoTime(devnet, queries);
        this.transactions = new YanoTransactions(node.txGateway(), node.txEvaluationGateway(), await);
        this.assertions = new YanoAssertions(queries, snapshots);
    }

    private static YanoDevnetTestKit build(YanoDevnetTestConfig config, boolean forceTimeTravel) {
        Objects.requireNonNull(config, "config");
        boolean useTimeTravel = forceTimeTravel || config.timeTravel();
        YanoDevnetAssembly.Builder builder = useTimeTravel
                ? YanoDevnetAssembly.devnetTimeTravel(config.yanoConfig())
                : YanoDevnetAssembly.devnet(config.yanoConfig());
        builder.runtimeOptions(config.runtimeOptions());
        config.inMemoryGenesis().ifPresent(builder::inMemoryGenesis);
        YanoNode node = null;
        try {
            node = builder.build();
            return from(node, config);
        } catch (RuntimeException | Error e) {
            closeAfterFailedBuild(node, config, e);
            throw e;
        }
    }

    private static YanoDevnetTestKit from(YanoNode node, AutoCloseable cleanup) {
        Objects.requireNonNull(node, "node");
        DevnetControl devnet = node.devnetControl()
                .orElseThrow(() -> new IllegalArgumentException(
                        "YanoDevnetTestKit requires a node assembled with devnet-toolkit"));
        return new YanoDevnetTestKit(node, devnet, cleanup);
    }

    private static void closeAfterFailedBuild(YanoNode node, AutoCloseable cleanup, Throwable failure) {
        if (node != null) {
            try {
                node.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }

        try {
            cleanup.close();
        } catch (Exception cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    /**
     * Returns devnet controls.
     *
     * @return devnet control role
     */
    public DevnetControl devnet() {
        return devnet;
    }

    /**
     * Returns lifecycle controls.
     *
     * @return lifecycle role
     */
    public NodeLifecycle lifecycle() {
        return node.lifecycle();
    }

    /**
     * Returns chain query access.
     *
     * @return chain query role
     */
    public ChainQuery chain() {
        return node.chain();
    }

    /**
     * Returns ledger query access.
     *
     * @return ledger query role
     */
    public LedgerQuery ledger() {
        return node.ledger();
    }

    /**
     * Returns transaction submission access.
     *
     * @return transaction gateway role
     */
    public TxGateway txGateway() {
        return node.txGateway();
    }

    /**
     * Returns transaction evaluation access.
     *
     * @return transaction evaluation gateway role
     */
    public TxEvaluationGateway txEvaluationGateway() {
        return node.txEvaluationGateway();
    }

    /**
     * Returns producer controls when the assembly exposes them.
     *
     * @return producer controls
     */
    public Optional<ProducerControl> producerControl() {
        return node.producerControl();
    }

    /**
     * Returns query helpers.
     *
     * @return query helpers
     */
    public YanoQueries queries() {
        return queries;
    }

    /**
     * Returns await helpers.
     *
     * @return await helpers
     */
    public YanoAwait await() {
        return await;
    }

    /**
     * Returns wallet fixtures.
     *
     * @return wallet fixtures
     */
    public YanoWallets wallets() {
        return wallets;
    }

    /**
     * Returns faucet helpers.
     *
     * @return faucet helpers
     */
    public YanoFaucet faucet() {
        return faucet;
    }

    /**
     * Returns snapshot helpers.
     *
     * @return snapshot helpers
     */
    public YanoSnapshots snapshots() {
        return snapshots;
    }

    /**
     * Returns deterministic time helpers.
     *
     * @return time helpers
     */
    public YanoTime time() {
        return time;
    }

    /**
     * Returns low-level transaction helpers.
     *
     * @return transaction helpers
     */
    public YanoTransactions transactions() {
        return transactions;
    }

    /**
     * Returns assertion helpers.
     *
     * @return assertion helpers
     */
    public YanoAssertions assertions() {
        return assertions;
    }

    /**
     * Starts the underlying node.
     */
    public void start() {
        node.start();
    }

    /**
     * Stops the underlying node.
     */
    public void stop() {
        node.stop();
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        try {
            node.close();
        } catch (RuntimeException e) {
            failure = e;
        }

        try {
            if (cleanup != null) {
                cleanup.close();
            }
        } catch (Exception e) {
            RuntimeException cleanupFailure = e instanceof RuntimeException runtimeException
                    ? runtimeException
                    : new RuntimeException(e);
            if (failure == null) {
                failure = cleanupFailure;
            } else {
                failure.addSuppressed(cleanupFailure);
            }
        }

        if (failure != null) {
            throw failure;
        }
    }
}
