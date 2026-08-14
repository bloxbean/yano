package com.bloxbean.cardano.yano.api.appchain.effects;

import java.util.Map;

/**
 * Node-side context for one executor attempt (ADR app-layer/010 F5). Lives on
 * the execution plane — wall clock and I/O are fine here, unlike inside
 * {@code apply()}.
 */
public interface EffectExecutionContext {

    /** The chain the effect belongs to. */
    String chainId();

    /** Current committed tip height. */
    long tipHeight();

    /** L1-confirmed anchor high-water-mark height (0 = nothing anchored). */
    long anchoredHeight();

    /** 1-based attempt number for this effect on this node. */
    int attempt();

    /**
     * External ref persisted by a prior {@code EffectExecution.Submitted}
     * (e.g. a submitted tx hash), empty on the first attempt. A re-polled
     * executor MUST probe the external system by this ref instead of acting
     * again — re-acting on a re-poll is the double-execution bug.
     */
    default byte[] submittedRef() {
        return new byte[0];
    }

    /** Executor config sub-map ({@code effects.executors.<scheme>.*}, prefix stripped). */
    Map<String, String> settings();
}
