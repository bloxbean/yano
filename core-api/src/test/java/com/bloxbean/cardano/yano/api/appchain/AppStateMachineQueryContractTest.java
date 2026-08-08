package com.bloxbean.cardano.yano.api.appchain;

import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppStateMachineQueryContractTest {

    @Test
    void contextualQueryDefaultUsesTypedUnsupportedReason() {
        AppStateMachine machine = new AppStateMachine() {
            @Override
            public String id() {
                return "no-queries";
            }

            @Override
            public void apply(
                    AppBlockExecutionContext execution,
                    AppStateWriter writer,
                    AppEffectEmitter emitter
            ) {
            }
        };

        AppQueryContext context = new AppQueryContext() {
            @Override
            public long committedHeight() {
                return 0;
            }

            @Override
            public Optional<byte[]> get(byte[] key) {
                return Optional.empty();
            }

            @Override
            public byte[] stateRoot() {
                return new byte[32];
            }
        };

        assertThatThrownBy(() -> machine.query("status", new byte[0], context))
                .isInstanceOf(AppQueryException.class)
                .extracting(failure -> ((AppQueryException) failure).code())
                .isEqualTo(AppQueryException.Code.UNSUPPORTED);
    }
}
