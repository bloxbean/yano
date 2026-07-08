package com.bloxbean.cardano.yano.appchain.zk;

import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ServiceLoader provider for the E7.3 anonymous-membership machine. Selected by
 * {@code yano.app-chain.state-machine=zk-membership}; reuses the E7.1 circuit
 * registry config ({@code yano.app-chain.zk.circuits[...]}) for the membership
 * circuit's VK.
 */
public final class ZkMembershipProvider implements AppStateMachineProvider {

    private static final Logger log = LoggerFactory.getLogger(ZkMembershipProvider.class);

    @Override
    public String id() {
        return ZkMembershipStateMachine.ID;
    }

    @Override
    public AppStateMachine create() {
        throw new IllegalStateException("The '" + ZkMembershipStateMachine.ID
                + "' state machine requires a membership circuit (yano.app-chain.zk.circuits[...])");
    }

    @Override
    public AppStateMachine create(AppStateMachineContext context) {
        ConfigVkRegistry vkRegistry = new ConfigVkRegistry(context.settings());
        if (vkRegistry.isEmpty()) {
            throw new IllegalStateException("state-machine=zk-membership but no membership circuit is configured "
                    + "(yano.app-chain.zk.circuits[0].id ...) for chain '" + context.chainId() + "'");
        }
        log.info("ZK membership auth enabled for chain '{}': circuits={} (per-nullifier dedup in apply)",
                context.chainId(), vkRegistry.circuitIds());
        return new ZkMembershipStateMachine(new ZkVerificationService(vkRegistry));
    }
}
