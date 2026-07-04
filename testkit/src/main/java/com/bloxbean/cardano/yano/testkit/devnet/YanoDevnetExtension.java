package com.bloxbean.cardano.yano.testkit.devnet;

import com.bloxbean.cardano.yano.api.ChainQuery;
import com.bloxbean.cardano.yano.api.DevnetControl;
import com.bloxbean.cardano.yano.api.LedgerQuery;
import com.bloxbean.cardano.yano.api.NodeLifecycle;
import com.bloxbean.cardano.yano.api.TxEvaluationGateway;
import com.bloxbean.cardano.yano.api.TxGateway;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * JUnit 5 extension that owns a {@link YanoDevnetTestKit} for each test.
 */
public final class YanoDevnetExtension implements BeforeEachCallback, AfterEachCallback, ParameterResolver {
    private static final String KIT_KEY = YanoDevnetTestKit.class.getName();

    private final Supplier<YanoDevnetTestKit> kitFactory;
    private final UnaryOperator<YanoDevnetTestConfig.Builder> configCustomizer;
    private final boolean timeTravel;
    private final boolean startNode;
    private final ExtensionContext.Namespace namespace =
            ExtensionContext.Namespace.create(YanoDevnetExtension.class, System.identityHashCode(this));

    /**
     * Creates an extension for a standard devnet node with test-safe defaults.
     *
     * @return JUnit extension
     */
    public static YanoDevnetExtension devnet() {
        return configurable(false, UnaryOperator.identity(), false);
    }

    /**
     * Creates an extension for a standard devnet node.
     *
     * @param config devnet configuration
     * @return JUnit extension
     */
    public static YanoDevnetExtension devnet(YanoConfig config) {
        return new YanoDevnetExtension(() -> YanoDevnetTestKit.devnet(config), null, false, false);
    }

    /**
     * Creates an extension for a past-time-travel devnet node with test-safe
     * defaults.
     *
     * @return JUnit extension
     */
    public static YanoDevnetExtension devnetTimeTravel() {
        return configurable(true, builder -> builder.timeTravel(true), false);
    }

    /**
     * Creates an extension for a past-time-travel devnet node.
     *
     * @param config devnet time-travel configuration
     * @return JUnit extension
     */
    public static YanoDevnetExtension devnetTimeTravel(YanoConfig config) {
        return new YanoDevnetExtension(() -> YanoDevnetTestKit.devnetTimeTravel(config), null, true, false);
    }

    /**
     * Creates an extension from a custom test-kit factory.
     *
     * @param kitFactory test-kit factory
     * @return JUnit extension
     */
    public static YanoDevnetExtension managed(Supplier<YanoDevnetTestKit> kitFactory) {
        return new YanoDevnetExtension(kitFactory, null, false, false);
    }

    private YanoDevnetExtension(Supplier<YanoDevnetTestKit> kitFactory,
                                UnaryOperator<YanoDevnetTestConfig.Builder> configCustomizer,
                                boolean timeTravel,
                                boolean startNode) {
        this.kitFactory = Objects.requireNonNull(kitFactory, "kitFactory");
        this.configCustomizer = configCustomizer;
        this.timeTravel = timeTravel;
        this.startNode = startNode;
    }

    /**
     * Returns a copy of this extension that starts the node in
     * {@link #beforeEach(ExtensionContext)}.
     *
     * @return auto-starting extension
     */
    public YanoDevnetExtension startNode() {
        return new YanoDevnetExtension(kitFactory, configCustomizer, timeTravel, true);
    }

    /**
     * Uses temporary RocksDB-backed storage owned by each test. This is the
     * default for configurable extensions.
     *
     * @return extension copy
     */
    public YanoDevnetExtension withTempRocksDbStorage() {
        return withConfigCustomizer(YanoDevnetTestConfig.Builder::temporaryRocksDbStorage);
    }

    /**
     * Uses caller-owned persistent RocksDB-backed storage.
     *
     * @param path chainstate directory
     * @return extension copy
     */
    public YanoDevnetExtension withPersistentRocksDbStorage(Path path) {
        Objects.requireNonNull(path, "path");
        return withConfigCustomizer(builder -> builder.persistentRocksDbStorage(path));
    }

    /**
     * Configures the devnet block interval.
     *
     * @param blockTimeMillis block interval in milliseconds
     * @return extension copy
     */
    public YanoDevnetExtension blockTimeMillis(int blockTimeMillis) {
        return withConfigCustomizer(builder -> builder.blockTimeMillis(blockTimeMillis));
    }

    /**
     * Configures the devnet epoch length.
     *
     * @param epochLength epoch length in slots
     * @return extension copy
     */
    public YanoDevnetExtension epochLength(long epochLength) {
        return withConfigCustomizer(builder -> builder.epochLength(epochLength));
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        YanoDevnetTestKit kit = kitFactory.get();
        try {
            if (startNode) {
                kit.start();
            }
        } catch (RuntimeException e) {
            try {
                kit.close();
            } catch (RuntimeException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
        context.getStore(namespace).put(KIT_KEY, kit);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        YanoDevnetTestKit kit = context.getStore(namespace).remove(KIT_KEY, YanoDevnetTestKit.class);
        if (kit != null) {
            kit.close();
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        Class<?> type = parameterContext.getParameter().getType();
        return type == YanoDevnetTestKit.class
                || type == DevnetControl.class
                || type == NodeLifecycle.class
                || type == ChainQuery.class
                || type == LedgerQuery.class
                || type == TxGateway.class
                || type == TxEvaluationGateway.class
                || type == YanoQueries.class
                || type == YanoAwait.class
                || type == YanoWallets.class
                || type == YanoFaucet.class
                || type == YanoSnapshots.class
                || type == YanoTime.class
                || type == YanoTransactions.class
                || type == YanoAssertions.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        YanoDevnetTestKit kit = extensionContext.getStore(namespace).get(KIT_KEY, YanoDevnetTestKit.class);
        if (kit == null) {
            throw new ParameterResolutionException("YanoDevnetTestKit has not been initialized");
        }

        Class<?> type = parameterContext.getParameter().getType();
        if (type == YanoDevnetTestKit.class) {
            return kit;
        }
        if (type == DevnetControl.class) {
            return kit.devnet();
        }
        if (type == NodeLifecycle.class) {
            return kit.lifecycle();
        }
        if (type == ChainQuery.class) {
            return kit.chain();
        }
        if (type == LedgerQuery.class) {
            return kit.ledger();
        }
        if (type == TxGateway.class) {
            return kit.txGateway();
        }
        if (type == TxEvaluationGateway.class) {
            return kit.txEvaluationGateway();
        }
        if (type == YanoQueries.class) {
            return kit.queries();
        }
        if (type == YanoAwait.class) {
            return kit.await();
        }
        if (type == YanoWallets.class) {
            return kit.wallets();
        }
        if (type == YanoFaucet.class) {
            return kit.faucet();
        }
        if (type == YanoSnapshots.class) {
            return kit.snapshots();
        }
        if (type == YanoTime.class) {
            return kit.time();
        }
        if (type == YanoTransactions.class) {
            return kit.transactions();
        }
        if (type == YanoAssertions.class) {
            return kit.assertions();
        }
        throw new ParameterResolutionException("Unsupported parameter type: " + type.getName());
    }

    private YanoDevnetExtension withConfigCustomizer(
            UnaryOperator<YanoDevnetTestConfig.Builder> additionalCustomizer) {
        Objects.requireNonNull(additionalCustomizer, "additionalCustomizer");
        if (configCustomizer == null) {
            throw new IllegalStateException("Storage/config builder methods are only supported by devnet() factories");
        }
        UnaryOperator<YanoDevnetTestConfig.Builder> composed =
                builder -> additionalCustomizer.apply(configCustomizer.apply(builder));
        return configurable(timeTravel, composed, startNode);
    }

    private static YanoDevnetExtension configurable(boolean timeTravel,
                                                    UnaryOperator<YanoDevnetTestConfig.Builder> configCustomizer,
                                                    boolean startNode) {
        Objects.requireNonNull(configCustomizer, "configCustomizer");
        Supplier<YanoDevnetTestKit> kitFactory = () -> {
            YanoDevnetTestConfig.Builder builder = configCustomizer.apply(YanoDevnetTestConfig.builder());
            if (timeTravel) {
                builder.timeTravel(true);
            }
            YanoDevnetTestConfig config = builder.build();
            return timeTravel
                    ? YanoDevnetTestKit.devnetTimeTravel(config)
                    : YanoDevnetTestKit.devnet(config);
        };
        return new YanoDevnetExtension(kitFactory, configCustomizer, timeTravel, startNode);
    }
}
