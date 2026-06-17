package com.bloxbean.cardano.yano.api;

import com.bloxbean.cardano.yano.api.config.NodeConfig;
import com.bloxbean.cardano.yano.api.listener.NodeEventListener;
import com.bloxbean.cardano.yano.api.model.NodeStatus;

/**
 * Lifecycle and operational status for a Yano node.
 */
public interface NodeLifecycle {
    void start();

    void stop();

    boolean isRunning();

    boolean isSyncing();

    boolean isServerRunning();

    NodeStatus getStatus();

    NodeConfig getConfig();

    void addNodeEventListener(NodeEventListener listener);

    void removeNodeEventListener(NodeEventListener listener);
}
