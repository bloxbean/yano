package com.bloxbean.cardano.yano.testkit.devnet;

import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yano.api.config.NodeConfig;
import com.bloxbean.cardano.yano.api.NodeLifecycle;
import com.bloxbean.cardano.yano.api.model.GenesisParameters;
import com.bloxbean.cardano.yano.api.model.NodeStatus;
import com.bloxbean.cardano.yano.api.model.ProtocolParamsSnapshot;
import com.bloxbean.cardano.yano.api.utxo.UtxoState;
import com.bloxbean.cardano.yano.api.util.EpochSlotCalc;
import com.bloxbean.cardano.yano.runtime.assembly.YanoNode;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Convenience read facade over public Yano query roles.
 */
public final class YanoQueries {
    private final YanoNode node;

    YanoQueries(YanoNode node) {
        this.node = Objects.requireNonNull(node, "node");
    }

    /**
     * Returns the current node status.
     *
     * @return node status
     */
    public NodeStatus status() {
        return node.lifecycle().getStatus();
    }

    /**
     * Returns the current local chain tip.
     *
     * @return chain tip, or null when no tip is available yet
     */
    public ChainTip tip() {
        return node.chain().getLocalTip();
    }

    /**
     * Returns the best known local slot.
     *
     * @return current slot, or 0 before any progress is available
     */
    public long currentSlot() {
        ChainTip tip = tip();
        if (tip != null) {
            return tip.getSlot();
        }

        NodeStatus status = status();
        if (status == null) {
            return 0;
        }
        Long localTipSlot = status.getLocalTipSlot();
        return localTipSlot != null ? localTipSlot : status.getLastProcessedSlot();
    }

    /**
     * Returns the best known local block number.
     *
     * @return current block number, or 0 before any progress is available
     */
    public long currentBlockNumber() {
        ChainTip tip = tip();
        if (tip != null) {
            return tip.getBlockNumber();
        }

        NodeStatus status = status();
        if (status == null || status.getLocalTipBlockNumber() == null) {
            return 0;
        }
        return status.getLocalTipBlockNumber();
    }

    /**
     * Returns the current epoch using the runtime config's epoch length.
     *
     * @return current epoch
     */
    public long currentEpoch() {
        return epochSlotCalc().slotToEpoch(currentSlot());
    }

    long epochStartSlot(long epoch) {
        if (epoch < 0 || epoch > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("epoch must be between 0 and " + Integer.MAX_VALUE);
        }
        return epochSlotCalc().epochToStartSlot((int) epoch);
    }

    /**
     * Returns the current UTXO state role.
     *
     * @return UTXO state
     */
    public UtxoState utxoState() {
        return node.ledger().getUtxoState();
    }

    /**
     * Returns current protocol parameters as JSON.
     *
     * @return protocol parameters JSON
     */
    public String protocolParameters() {
        return node.ledger().getProtocolParameters();
    }

    /**
     * Returns protocol parameters for an epoch when the runtime can provide a
     * snapshot.
     *
     * @param epoch epoch number
     * @return protocol parameter snapshot
     */
    public Optional<ProtocolParamsSnapshot> protocolParameters(int epoch) {
        return node.ledger().getProtocolParameters(epoch);
    }

    /**
     * Returns genesis parameters.
     *
     * @return genesis parameters
     */
    public GenesisParameters genesisParameters() {
        return node.ledger().getGenesisParameters();
    }

    /**
     * Returns epoch nonce details when available.
     *
     * @return epoch nonce details
     */
    public Map<String, Object> epochNonceInfo() {
        return node.ledger().getEpochNonceInfo();
    }

    /**
     * Returns epoch calculation status when available.
     *
     * @return epoch calculation status
     */
    public Map<String, Object> epochCalcStatus() {
        return node.ledger().getEpochCalcStatus();
    }

    NodeLifecycle lifecycle() {
        return node.lifecycle();
    }

    private EpochSlotCalc epochSlotCalc() {
        NodeConfig config = node.lifecycle().getConfig();
        return new EpochSlotCalc(
                config.getEpochLength(),
                config.getByronSlotsPerEpoch(),
                config.getFirstNonByronSlot());
    }
}
