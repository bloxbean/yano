package com.bloxbean.cardano.yano.runtime.assembly;

import com.bloxbean.cardano.yano.api.ChainQuery;
import com.bloxbean.cardano.yano.api.DevnetControl;
import com.bloxbean.cardano.yano.api.LedgerQuery;
import com.bloxbean.cardano.yano.api.NodeLifecycle;
import com.bloxbean.cardano.yano.api.ProducerControl;
import com.bloxbean.cardano.yano.api.TxEvaluationGateway;
import com.bloxbean.cardano.yano.api.TxGateway;
import com.bloxbean.cardano.yano.runtime.debug.DebugLedgerStateAccess;
import com.bloxbean.cardano.yano.runtime.kernel.NodeKernel;
import com.bloxbean.cardano.yano.runtime.maintenance.RuntimeMaintenanceGate;

import java.util.Optional;

/**
 * Thin handle returned by runtime assembly recipes.
 */
public interface Yano extends AutoCloseable {
    NodeLifecycle lifecycle();

    ChainQuery chain();

    LedgerQuery ledger();

    TxGateway txGateway();

    TxEvaluationGateway txEvaluationGateway();

    Optional<ProducerControl> producerControl();

    Optional<DevnetControl> devnetControl();

    Optional<NodeKernel> kernel();

    default Optional<RuntimeMaintenanceGate> maintenanceGate() {
        return Optional.empty();
    }

    default Optional<DebugLedgerStateAccess> debugLedgerStateAccess() {
        return Optional.empty();
    }

    default void start() {
        lifecycle().start();
    }

    default void stop() {
        lifecycle().stop();
    }

    @Override
    default void close() {
        stop();
        kernel().ifPresent(NodeKernel::close);
    }
}
