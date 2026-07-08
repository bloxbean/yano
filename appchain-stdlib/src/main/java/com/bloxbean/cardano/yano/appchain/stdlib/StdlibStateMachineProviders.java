package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;

/**
 * ServiceLoader providers for the standard-library state machines.
 * Select via {@code yano.app-chain.state-machine} (or per chain via
 * {@code yano.app-chain.chains[i].state-machine}).
 */
public final class StdlibStateMachineProviders {

    private StdlibStateMachineProviders() {
    }

    public static final class KvRegistryProvider implements AppStateMachineProvider {
        @Override
        public String id() {
            return KvRegistryStateMachine.ID;
        }

        @Override
        public AppStateMachine create() {
            return new KvRegistryStateMachine();
        }
    }

    public static final class ApprovalsProvider implements AppStateMachineProvider {
        @Override
        public String id() {
            return ApprovalsStateMachine.ID;
        }

        @Override
        public AppStateMachine create() {
            return new ApprovalsStateMachine();
        }
    }
}
