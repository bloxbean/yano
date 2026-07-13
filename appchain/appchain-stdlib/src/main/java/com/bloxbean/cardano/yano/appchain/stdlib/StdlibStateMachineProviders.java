package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;

/**
 * ServiceLoader providers for the standard-library state machines.
 * Select via {@code yano.app-chain.state-machine} (or per chain via
 * {@code yano.app-chain.chains[i].state-machine}).
 * <p>
 * Machines with settings read them from the chain's dynamic plugin config
 * under {@code yano.app-chain.machines.<machine-id>.*} (ADR app-layer/008.1
 * I1.4), e.g. {@code machines.balances.minter}.
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

        @Override
        public AppStateMachine create(AppStateMachineContext context) {
            String format = context.settings().getOrDefault("machines.kv-registry.value-format", "raw");
            return new KvRegistryStateMachine(KvRegistryStateMachine.ValueFormat.parse(format));
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

    public static final class BalancesProvider implements AppStateMachineProvider {
        @Override
        public String id() {
            return BalancesStateMachine.ID;
        }

        @Override
        public AppStateMachine create() {
            return new BalancesStateMachine();
        }

        @Override
        public AppStateMachine create(AppStateMachineContext context) {
            return new BalancesStateMachine(context.settings().getOrDefault("machines.balances.minter", ""));
        }
    }

    public static final class DocTrailProvider implements AppStateMachineProvider {
        @Override
        public String id() {
            return DocTrailStateMachine.ID;
        }

        @Override
        public AppStateMachine create() {
            return new DocTrailStateMachine();
        }
    }
}
