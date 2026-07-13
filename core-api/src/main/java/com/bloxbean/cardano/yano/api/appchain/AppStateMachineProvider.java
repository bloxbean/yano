package com.bloxbean.cardano.yano.api.appchain;

/**
 * ServiceLoader SPI for supplying custom app-chain state machines without
 * recompiling Yano (ADR app-layer/005 D1/D10). Drop a jar containing an
 * implementation plus
 * {@code META-INF/services/com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider}
 * into the node's plugins directory (or the classpath) and reference its id
 * via {@code yano.app-chain.state-machine}.
 */
public interface AppStateMachineProvider {

    /** Id matched against {@code yano.app-chain.state-machine}. */
    String id();

    /** Create a fresh state machine instance for a chain. */
    AppStateMachine create();

    /**
     * Create a state machine with access to its chain's config (ADR
     * app-layer/006). Config-driven plugins (e.g. the ZK verifier, which needs a
     * circuit → VK registry) override this; the default ignores the context so
     * existing no-config providers keep working unchanged.
     */
    default AppStateMachine create(AppStateMachineContext context) {
        return create();
    }
}
