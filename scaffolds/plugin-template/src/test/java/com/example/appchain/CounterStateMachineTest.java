package com.example.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-test a custom state machine's apply() with a tiny in-memory writer —
 * no node required. This is the fastest inner loop while developing a plugin;
 * for full sequenced end-to-end tests use the appchain-testkit.
 */
class CounterStateMachineTest {

    @Test
    void countsPerKey_deterministically() {
        CounterStateMachine machine = new CounterStateMachine();
        MapWriter writer = new MapWriter();

        machine.apply(block("visits", "visits", "signups"), writer);
        assertThat(new String(writer.map.get("c/visits"), StandardCharsets.UTF_8)).isEqualTo("2");
        assertThat(new String(writer.map.get("c/signups"), StandardCharsets.UTF_8)).isEqualTo("1");

        machine.apply(block("visits"), writer);
        assertThat(new String(writer.map.get("c/visits"), StandardCharsets.UTF_8)).isEqualTo("3");
    }

    @Test
    void committedQueryReadsOnlyTheSuppliedRootFixedContext() {
        CounterStateMachine machine = new CounterStateMachine();
        MapWriter committed = new MapWriter();
        machine.apply(block("visits", "visits"), committed);

        assertThat(machine.query("counter/read",
                "visits".getBytes(StandardCharsets.US_ASCII), committed))
                .asString(StandardCharsets.UTF_8)
                .isEqualTo("2");
        assertThatThrownBy(() -> machine.query(
                "unknown", new byte[0], committed))
                .isInstanceOfSatisfying(AppQueryException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(AppQueryException.Code.UNSUPPORTED));
        assertThatThrownBy(() -> machine.query(
                "counter/read", new byte[]{'/'}, committed))
                .isInstanceOfSatisfying(AppQueryException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(AppQueryException.Code.INVALID_REQUEST));
    }

    private static AppBlock block(String... bodies) {
        List<AppMessage> messages = java.util.Arrays.stream(bodies)
                .map(b -> AppMessage.builder()
                        .version(2).chainId("t").topic("counter")
                        .sender(new byte[32]).senderSeq(0).expiresAt(0)
                        .body(b.getBytes(StandardCharsets.UTF_8))
                        .build())
                .toList();
        return new AppBlock(2, "t", 1, new byte[32], 0, new byte[0], 0L,
                new byte[32], new byte[32], messages, new byte[32],
                new com.bloxbean.cardano.yano.api.appchain.FinalityCert(0, List.of()));
    }

    /** Minimal AppStateWriter backed by a HashMap. */
    static final class MapWriter implements AppStateWriter, AppQueryContext {
        final Map<String, byte[]> map = new HashMap<>();

        @Override
        public Optional<byte[]> get(byte[] key) {
            return Optional.ofNullable(map.get(new String(key, StandardCharsets.UTF_8)));
        }

        @Override
        public byte[] stateRoot() {
            return new byte[32];
        }

        @Override
        public long committedHeight() {
            return 1;
        }

        @Override
        public void put(byte[] key, byte[] value) {
            map.put(new String(key, StandardCharsets.UTF_8), value);
        }

        @Override
        public void delete(byte[] key) {
            map.remove(new String(key, StandardCharsets.UTF_8));
        }
    }
}
