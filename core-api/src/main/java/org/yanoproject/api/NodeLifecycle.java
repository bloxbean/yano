package org.yanoproject.api;

import org.yanoproject.api.config.NodeConfig;
import org.yanoproject.api.listener.NodeEventListener;
import org.yanoproject.api.model.NodePeers;
import org.yanoproject.api.model.NodeStatus;

import java.util.List;

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

    default NodePeers getPeers() {
        return new NodePeers(
                System.currentTimeMillis(),
                null,
                null,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0,
                0, 0, 0, 0,
                0, 0,
                List.of());
    }

    NodeConfig getConfig();

    void addNodeEventListener(NodeEventListener listener);

    void removeNodeEventListener(NodeEventListener listener);
}
