package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectExecutor;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecution;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectIntent;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectOutcome;
import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;
import com.bloxbean.cardano.yano.api.appchain.effects.FxResultBody;
import com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect;
import com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FX-M5 hardening (ADR app-layer/010 F8/F13): the designated result-signer
 * policy and the ZK_SETTLED finality gate.
 */
@Timeout(120)
class FxEffectsM5Test {

    private static final byte[] EXECUTOR_MEMBER = member("executor-member");
    private static final byte[] OTHER_MEMBER = member("other-member");

    private static byte[] member(String name) {
        return Arrays.copyOf(name.getBytes(StandardCharsets.UTF_8), 32);
    }

    @Test
    void designatedSignerPolicy_noOpsOtherMembers(@TempDir Path dir) {
        Map<String, String> settings = Map.of(
                "effects.enabled", "true",
                "effects.result.signers", HexUtil.encodeHexString(EXECUTOR_MEMBER));
        RecordingMachine machine = new RecordingMachine();
        try (MsgPipeline pipeline = new MsgPipeline(dir, machine, settings)) {
            pipeline.apply(msg("t", "emit-1".getBytes(StandardCharsets.UTF_8), OTHER_MEMBER));

            byte[] result = new FxResultBody(1, 1, 0, EffectOutcome.CONFIRMED,
                    "ref".getBytes(StandardCharsets.UTF_8), null).encode();
            // Result signed by a NON-designated member: deterministic no-op
            FxKernel.Result rejected = pipeline.apply(
                    msg(FxResultBody.TOPIC, result, OTHER_MEMBER));
            assertThat(rejected.incorporated()).isEmpty();
            assertThat(pipeline.store.fxClosed(1, 0)).isFalse();

            // Same body from the designated signer: incorporated
            FxKernel.Result accepted = pipeline.apply(
                    msg(FxResultBody.TOPIC, result, EXECUTOR_MEMBER));
            assertThat(accepted.incorporated()).hasSize(1);
            assertThat(pipeline.store.fxClosed(1, 0)).isTrue();
        }
    }

    @Test
    void zkSettledGate_waitsForTheSettlementHighWaterMark(@TempDir Path dir) throws Exception {
        Map<String, String> settings = Map.of("effects.enabled", "true");
        ConcurrentLinkedQueue<PendingEffect> executed = new ConcurrentLinkedQueue<>();
        AppEffectExecutor executor = new AppEffectExecutor() {
            @Override public String id() { return "recording"; }
            @Override public boolean supports(String type) { return "test.action".equals(type); }
            @Override public EffectExecution execute(EffectExecutionContext ctx, PendingEffect effect) {
                executed.add(effect);
                return EffectExecution.confirmed(new byte[0]);
            }
        };
        RecordingMachine machine = new RecordingMachine(FinalityGate.ZK_SETTLED);
        try (MsgPipeline pipeline = new MsgPipeline(dir, machine, settings);
             EffectRuntime runtime = new EffectRuntime(pipeline.store, "fx-chain",
                     new EffectRuntime.Settings(true, Set.of(), 50, 2, 3, 1, 5, 0, 100),
                     List.of(executor), Map.of(), LoggerFactory.getLogger("fx"))) {
            pipeline.apply(msg("t", "emit-1".getBytes(StandardCharsets.UTF_8), OTHER_MEMBER));
            runtime.tick();
            Thread.sleep(100);
            assertThat(executed).isEmpty(); // zk_settled_height = 0 < height 1

            pipeline.store.metaPutLong("zk_settled_height", 1);
            runtime.tick();
            long deadline = System.currentTimeMillis() + 10_000;
            while (executed.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertThat(executed).hasSize(1);
            // Claim path honors the gate identically
            assertThat(runtime.claim("w", Set.of(), 10, 60)).isEmpty(); // already executed/inFlight-done
        }
    }

    // ------------------------------------------------------------------

    private static final class RecordingMachine implements AppStateMachine {
        final FinalityGate gate;

        RecordingMachine() {
            this(FinalityGate.CHAIN_DEFAULT);
        }

        RecordingMachine(FinalityGate gate) {
            this.gate = gate;
        }

        @Override public String id() { return "recording"; }
        @Override public void apply(AppBlock block, AppStateWriter writer) { }

        @Override
        public void apply(AppBlock block, AppStateWriter writer, AppEffectEmitter effects) {
            for (AppMessage message : block.messages()) {
                String body = new String(message.getBody(), StandardCharsets.UTF_8);
                if (message.getTopic().startsWith("~") || !body.startsWith("emit-")) {
                    continue;
                }
                effects.emit(EffectIntent.of("test.action", message.getBody())
                        .scope(body)
                        .gate(gate)
                        .result(ResultPolicy.CHAIN)
                        .build());
            }
        }
    }

    private record Msg(String topic, byte[] body, byte[] sender) {
    }

    private static Msg msg(String topic, byte[] body, byte[] sender) {
        return new Msg(topic, body, sender);
    }

    private static final class MsgPipeline implements AutoCloseable {
        final AppLedgerStore store;
        final AppStateMachine machine;
        final FxKernel kernel;
        byte[] prevHash = AppBlock.GENESIS_PREV_HASH;
        long height;
        long senderSeq;

        MsgPipeline(Path dir, AppStateMachine machine, Map<String, String> settings) {
            this.store = new AppLedgerStore(dir.toString(), LoggerFactory.getLogger("fx-test"));
            this.machine = machine;
            this.kernel = new FxKernel(EffectsSettings.fromSettings(settings));
        }

        FxKernel.Result apply(Msg... msgs) {
            height++;
            List<AppMessage> list = new ArrayList<>();
            for (Msg msg : msgs) {
                long seq = ++senderSeq;
                byte[] id = AppMessage.computeMessageId("fx-chain", msg.topic(), msg.sender(), seq,
                        4_000_000_000L, msg.body());
                list.add(AppMessage.builder()
                        .messageId(id).chainId("fx-chain").topic(msg.topic()).sender(msg.sender())
                        .senderSeq(seq).expiresAt(4_000_000_000L).body(msg.body())
                        .authScheme(0).authProof(new byte[64]).build());
            }
            AppBlock block = new AppBlock(AppBlock.BLOCK_VERSION, "fx-chain", height, prevHash,
                    0, new byte[0], 1_700_000_000_000L + height * 1_000,
                    AppBlockCodec.messagesRoot(list), new byte[32], list,
                    new byte[32], FinalityCert.empty());
            FxBlockApplier.Applied applied = FxBlockApplier.applyAndCommit(store, kernel, machine, block);
            prevHash = applied.blockHash();
            return applied.fx();
        }

        @Override
        public void close() {
            store.close();
        }
    }
}
