package com.bloxbean.cardano.yano.testkit.devnet;

import com.bloxbean.cardano.yano.api.DevnetControl;
import com.bloxbean.cardano.yano.api.config.YanoConfig;
import com.bloxbean.cardano.yano.devnet.YanoDevnetAssembly;
import com.bloxbean.cardano.yano.runtime.assembly.YanoNode;

import java.util.Objects;

/**
 * Small devnet test harness built on the public runtime assembly and
 * {@link DevnetControl} role.
 */
public final class YanoDevnetTestKit implements AutoCloseable {
    private final YanoNode node;
    private final DevnetControl devnet;

    /**
     * Builds a standard devnet test kit.
     *
     * @param config devnet configuration
     * @return managed test kit
     */
    public static YanoDevnetTestKit devnet(YanoConfig config) {
        return from(YanoDevnetAssembly.devnet(config).build());
    }

    /**
     * Builds a past-time-travel devnet test kit.
     *
     * @param config devnet time-travel configuration
     * @return managed test kit
     */
    public static YanoDevnetTestKit devnetTimeTravel(YanoConfig config) {
        return from(YanoDevnetAssembly.devnetTimeTravel(config).build());
    }

    /**
     * Wraps an assembled devnet node.
     *
     * @param node assembled node with {@link DevnetControl}
     * @return managed test kit
     */
    public static YanoDevnetTestKit from(YanoNode node) {
        Objects.requireNonNull(node, "node");
        DevnetControl devnet = node.devnetControl()
                .orElseThrow(() -> new IllegalArgumentException(
                        "YanoDevnetTestKit requires a node assembled with devnet-toolkit"));
        return new YanoDevnetTestKit(node, devnet);
    }

    private YanoDevnetTestKit(YanoNode node, DevnetControl devnet) {
        this.node = Objects.requireNonNull(node, "node");
        this.devnet = Objects.requireNonNull(devnet, "devnet");
    }

    /**
     * Returns the assembled node.
     *
     * @return Yano node
     */
    public YanoNode node() {
        return node;
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
        node.close();
    }
}
