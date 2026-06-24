package com.bloxbean.cardano.yano.testkit.devnet;

import com.bloxbean.cardano.yano.api.DevnetControl;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.runtime.assembly.YanoNode;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * JUnit 5 extension that owns a {@link YanoDevnetTestKit} for each test.
 */
public final class YanoDevnetExtension implements BeforeEachCallback, AfterEachCallback, ParameterResolver {
    private final Supplier<YanoDevnetTestKit> kitFactory;
    private final boolean startNode;
    private YanoDevnetTestKit kit;

    /**
     * Creates an extension for a standard devnet node.
     *
     * @param config devnet configuration
     * @return JUnit extension
     */
    public static YanoDevnetExtension devnet(YanoConfig config) {
        return new YanoDevnetExtension(() -> YanoDevnetTestKit.devnet(config), false);
    }

    /**
     * Creates an extension for a past-time-travel devnet node.
     *
     * @param config devnet time-travel configuration
     * @return JUnit extension
     */
    public static YanoDevnetExtension devnetTimeTravel(YanoConfig config) {
        return new YanoDevnetExtension(() -> YanoDevnetTestKit.devnetTimeTravel(config), false);
    }

    /**
     * Creates an extension from a custom test-kit factory.
     *
     * @param kitFactory test-kit factory
     * @return JUnit extension
     */
    public static YanoDevnetExtension managed(Supplier<YanoDevnetTestKit> kitFactory) {
        return new YanoDevnetExtension(kitFactory, false);
    }

    private YanoDevnetExtension(Supplier<YanoDevnetTestKit> kitFactory, boolean startNode) {
        this.kitFactory = Objects.requireNonNull(kitFactory, "kitFactory");
        this.startNode = startNode;
    }

    /**
     * Returns a copy of this extension that starts the node in
     * {@link #beforeEach(ExtensionContext)}.
     *
     * @return auto-starting extension
     */
    public YanoDevnetExtension startNode() {
        return new YanoDevnetExtension(kitFactory, true);
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        kit = kitFactory.get();
        try {
            if (startNode) {
                kit.start();
            }
        } catch (RuntimeException e) {
            kit.close();
            kit = null;
            throw e;
        }
    }

    @Override
    public void afterEach(ExtensionContext context) {
        if (kit != null) {
            kit.close();
            kit = null;
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        Class<?> type = parameterContext.getParameter().getType();
        return type == YanoDevnetTestKit.class
                || type == YanoNode.class
                || type == DevnetControl.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        if (kit == null) {
            throw new ParameterResolutionException("YanoDevnetTestKit has not been initialized");
        }

        Class<?> type = parameterContext.getParameter().getType();
        if (type == YanoDevnetTestKit.class) {
            return kit;
        }
        if (type == YanoNode.class) {
            return kit.node();
        }
        if (type == DevnetControl.class) {
            return kit.devnet();
        }
        throw new ParameterResolutionException("Unsupported parameter type: " + type.getName());
    }
}
