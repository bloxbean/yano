package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.TypedAppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.codec.JacksonCborCodec;
import com.bloxbean.cardano.yano.runtime.appchain.AppChainSubsystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-006 E1.3: a TypedAppStateMachine driving a real chain with typed CBOR
 * payloads — the framework still only ever handles opaque bytes.
 */
@Timeout(60)
class TypedStateMachineTest {

    private static final Logger log = LoggerFactory.getLogger(TypedStateMachineTest.class);
    private static final byte[] KEY_A = seed(91);

    public record Counter(String key, long delta) {
    }

    /** Typed machine: accumulate per-key counters from typed messages. */
    static final class CounterMachine extends TypedAppStateMachine<Counter> {
        CounterMachine() {
            super(JacksonCborCodec.of(Counter.class));
        }

        @Override
        public String id() {
            return "counter";
        }

        @Override
        protected AdmissionResult validateMessage(Counter payload, AppMessage envelope) {
            return payload.delta() != 0 ? AdmissionResult.accept()
                    : AdmissionResult.reject("delta must be non-zero");
        }

        @Override
        protected void applyMessage(Counter payload, AppMessage envelope, AppBlock block, AppStateWriter writer) {
            byte[] key = ("c/" + payload.key()).getBytes(StandardCharsets.UTF_8);
            long current = writer.get(key)
                    .map(b -> Long.parseLong(new String(b, StandardCharsets.UTF_8))).orElse(0L);
            writer.put(key, Long.toString(current + payload.delta()).getBytes(StandardCharsets.UTF_8));
        }
    }

    @TempDir
    Path tempDir;

    private AppChainSubsystem node;

    @AfterEach
    void tearDown() {
        if (node != null) node.stop();
    }

    @Test
    void typedMachine_appliesCborPayloads() throws Exception {
        String pubA = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(KEY_A));
        AppChainConfig config = AppChainConfig.builder("typed-chain")
                .signingKeyHex(HexUtil.encodeHexString(KEY_A))
                .memberKeysHex(Set.of(pubA))
                .proposerKeyHex(pubA)
                .threshold(1)
                .blockIntervalMs(300)
                .build();
        // Library mode: pass the state machine instance directly
        node = new AppChainSubsystem(config, 42, null, new CounterMachine(),
                tempDir.resolve("ledger").toString(), null, log);
        node.start();

        JacksonCborCodec<Counter> codec = JacksonCborCodec.of(Counter.class);
        node.submit("counters", codec.encode(new Counter("visits", 5)));
        node.submit("counters", codec.encode(new Counter("visits", 3)));

        byte[] key = "c/visits".getBytes(StandardCharsets.UTF_8);
        awaitTrue("counter reached 8", () -> node.stateValue(key)
                .map(b -> "8".equals(new String(b, StandardCharsets.UTF_8))).orElse(false));

        // Malformed / non-decodable body rejected at admission
        long tip = node.tipHeight();
        node.submit("counters", "not-cbor-garbage".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(1500);
        assertThat(node.tipHeight()).isEqualTo(tip);

        assertThat(node.stateProof(key)).isPresent();
    }

    private static void awaitTrue(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean())
                return;
            Thread.sleep(200);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }

    private static byte[] seed(int fill) {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) fill);
        return seed;
    }
}
