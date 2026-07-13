package com.example.appchain;

import com.bloxbean.cardano.yano.runtime.appchain.StateMachineConformance;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

/**
 * Determinism conformance (ADR app-layer/008.1 I1.6): applies an identical
 * block corpus through the real ledger commit path in several independent
 * runs (plus a kill-and-reopen replay) and asserts byte-identical state roots
 * at every height. A nondeterministic apply() would STALL your chain in
 * production — keep this test in every state-machine plugin.
 */
class CounterConformanceTest {

    @Test
    void counterMachine_isDeterministic() {
        StateMachineConformance.builder(new CounterStateMachineProvider())
                .blocks(30)
                .messagesPerBlock(5)
                .seed(7)
                // Realistic commands for THIS machine: bodies are counter keys
                .bodyGenerator((height, index, random) ->
                        ("key-" + random.nextInt(5)).getBytes(StandardCharsets.UTF_8))
                .assertDeterministic();
    }
}
