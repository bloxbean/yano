package com.bloxbean.cardano.yano.api.appchain.l1view;

import com.bloxbean.cardano.yaci.core.model.Block;

import java.util.List;
import java.util.Map;

/**
 * An L1 observer (ADR app-layer/008.4 §3.1): a PURE function from an L1
 * block to observations. Every app-chain member runs the same configured
 * observers over its own streamed L1 blocks — the proposer injects the
 * results as {@code ~l1/<observer-id>} messages, and followers verify
 * proposed observations against their own recomputation before voting
 * (consensus-critical, fail-closed). Determinism is therefore a hard
 * requirement: same block in, same observations out, on every member.
 *
 * <p>Built-ins: {@code metadata-label} (watch a tx metadata label) and
 * {@code address-deposit} (watch payments to an address). Custom observers
 * plug in via {@link L1ObserverProvider} (ServiceLoader — same pattern as
 * sequencer modes and state machines).
 */
public interface L1Observer {

    /** The configured instance id — the {@code ~l1/<observer-id>} topic suffix. */
    String observerId();

    /**
     * Inspect one L1 block and return the observations it yields (empty for
     * most blocks). Must be deterministic and side-effect free.
     *
     * @param slot      the block's slot
     * @param blockHash the block's hash (32B)
     * @param block     the parsed L1 block
     */
    List<L1Observation> observe(long slot, byte[] blockHash, Block block);

    /** Status/diagnostics surface (shown in app-chain status). */
    default Map<String, Object> status() {
        return Map.of();
    }
}
