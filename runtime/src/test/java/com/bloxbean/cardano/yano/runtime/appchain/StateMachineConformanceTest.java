package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR app-layer/008.1 I1.6: the conformance harness passes deterministic
 * machines (including across a kill-and-reopen replay) and pinpoints
 * nondeterministic ones with the exact divergence height.
 */
@Timeout(120)
class StateMachineConformanceTest {

    @Test
    void orderedLog_isDeterministic() {
        StateMachineConformance.builder(provider("ordered-log", OrderedLogStateMachine::new))
                .blocks(20)
                .messagesPerBlock(4)
                .runs(3)
                .assertDeterministic();
    }

    @Test
    void nondeterministicMachine_failsWithPreciseDiff() {
        AppStateMachineProvider bad = provider("wall-clock-machine", WallClockMachine::new);
        StateMachineConformance.Result result = StateMachineConformance.builder(bad)
                .blocks(5)
                .runs(2)
                .run();

        assertThat(result.deterministic()).isFalse();
        assertThat(result.divergence().height()).isEqualTo(1); // diverges immediately
        assertThatThrownBy(() -> StateMachineConformance.builder(bad).blocks(5).assertDeterministic())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("NOT deterministic")
                .hasMessageContaining("height 1");
    }

    @Test
    void upgradeHarness_restartsBeforeAndAfterActivation() {
        AtomicInteger newMachineCreations = new AtomicInteger();
        AppStateMachineProvider oldProvider = provider("restart-probe", OrderedLogStateMachine::new);
        AppStateMachineProvider newProvider = provider("restart-probe", () -> {
            newMachineCreations.incrementAndGet();
            return new OrderedLogStateMachine();
        });

        StateMachineConformance.upgrade(oldProvider, newProvider)
                .activationAt("change-v2", 5)
                .blocks(12)
                .runs(2)
                .assertReplayStable();

        // Two uninterrupted runs plus two boundary runs, each reopened once.
        assertThat(newMachineCreations).hasValue(6);
    }

    private static AppStateMachineProvider provider(String id,
                                                    java.util.function.Supplier<AppStateMachine> factory) {
        return new AppStateMachineProvider() {
            @Override public String id() { return id; }
            @Override public AppStateMachine create() { return factory.get(); }
        };
    }

    /** The classic mistake: state derived from the wall clock. */
    private static final class WallClockMachine implements AppStateMachine {
        @Override
        public String id() {
            return "wall-clock-machine";
        }

        @Override
        public void apply(AppBlock block, AppStateWriter writer) {
            writer.put(("h" + block.height()).getBytes(StandardCharsets.UTF_8),
                    String.valueOf(System.nanoTime()).getBytes(StandardCharsets.UTF_8));
        }
    }
}
