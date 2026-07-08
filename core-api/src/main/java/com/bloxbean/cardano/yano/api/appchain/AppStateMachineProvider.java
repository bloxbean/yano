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
}
