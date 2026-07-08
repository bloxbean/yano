package com.bloxbean.cardano.yano.appchain.zk;

import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ServiceLoader provider for the E7.1 ZK gate. Selected by
 * {@code yano.app-chain.state-machine=zk-gate}; configured via
 * {@code yano.app-chain.zk.*}:
 * <pre>
 *   yano.app-chain.zk.circuits[0].id           = credit-limit
 *   yano.app-chain.zk.circuits[0].vk-file      = /etc/yano/credit-limit.vk
 *   yano.app-chain.zk.circuits[0].vk-hash      = &lt;blake2b-256 hex of the vk file&gt;
 *   yano.app-chain.zk.circuits[0].proof-system = groth16      # or plonk
 *   yano.app-chain.zk.circuits[0].curve        = bls12_381
 *   yano.app-chain.zk.max-proofs-per-block     = 200          # advisory; bound via block.max-messages
 * </pre>
 * Proofs are verified by every member in {@code apply()} (consensus enforcement),
 * not merely at the proposer's mempool.
 */
public final class ZkGateProvider implements AppStateMachineProvider {

    private static final Logger log = LoggerFactory.getLogger(ZkGateProvider.class);

    @Override
    public String id() {
        return ZkGateStateMachine.ID;
    }

    @Override
    public AppStateMachine create() {
        throw new IllegalStateException("The '" + ZkGateStateMachine.ID
                + "' state machine requires configuration (yano.app-chain.zk.circuits[...]); "
                + "it cannot be created without a chain context");
    }

    @Override
    public AppStateMachine create(AppStateMachineContext context) {
        ConfigVkRegistry vkRegistry = new ConfigVkRegistry(context.settings());
        if (vkRegistry.isEmpty()) {
            throw new IllegalStateException("state-machine=zk-gate but no circuits are configured "
                    + "(yano.app-chain.zk.circuits[0].id ...) for chain '" + context.chainId() + "'");
        }
        String maxProofs = context.settings().getOrDefault("zk.max-proofs-per-block", "(bounded by block.max-messages)");
        log.info("ZK gate enabled for chain '{}': circuits={}, max-proofs/block={} "
                        + "(proofs verified by every member in apply())",
                context.chainId(), vkRegistry.circuitIds(), maxProofs);
        return new ZkGateStateMachine(new ZkVerificationService(vkRegistry));
    }
}
