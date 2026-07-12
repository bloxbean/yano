package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectIntent;
import com.bloxbean.cardano.yano.api.appchain.effects.PendingEffect;
import com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FX-M4 external-executor mode (ADR app-layer/010 F5): REST-style
 * claim/report against the Effect Runtime — leases, holder validation,
 * expiry-based failover, and the handoff into the FX-M3 injection loop.
 */
@Timeout(120)
class FxEffectsM4Test {

    private static final Map<String, String> FX_SETTINGS = Map.of(
            "effects.enabled", "true",
            "effects.max-per-block", "8",
            "effects.max-payload-bytes", "4096");

    private static EffectRuntime runtime(AppLedgerStore store) {
        // No in-process executors — pure external mode
        return new EffectRuntime(store, "fx-chain",
                new EffectRuntime.Settings(true, Set.of(), 50, 2, 3, 1, 5, 0, 100),
                List.of(), Map.of(), LoggerFactory.getLogger("fx"));
    }

    @Test
    void claimReport_confirmsAndFeedsInjection(@TempDir Path dir) {
        try (Pipeline pipeline = new Pipeline(dir, emitting(ResultPolicy.CHAIN), FX_SETTINGS);
             EffectRuntime runtime = runtime(pipeline.store)) {
            pipeline.applyNext(1);
            runtime.tick(); // intake

            List<PendingEffect> claimed = runtime.claim("worker-1", Set.of("test.action"), 10, 60);
            assertThat(claimed).hasSize(1);
            assertThat(claimed.get(0).effectId().canonical()).isEqualTo("fx-chain/1/0");
            assertThat(claimed.get(0).idHash()).hasSize(32);

            // Second claim while the lease is live: nothing eligible
            assertThat(runtime.claim("worker-2", Set.of("test.action"), 10, 60)).isEmpty();

            // Report success → local DONE → injectable as CONFIRMED (FX-M3 loop)
            assertThat(runtime.report("worker-1", 1, 0, true,
                    "ext-ref".getBytes(StandardCharsets.UTF_8), null)).isTrue();
            assertThat(pipeline.store.fxRuntimeStatus(1, 0).orElseThrow().status())
                    .isEqualTo(FxStatusRecord.DONE);
            var injections = runtime.pendingInjections(10, 0);
            assertThat(injections).hasSize(1);
            assertThat(injections.get(0).confirmed()).isTrue();
        }
    }

    @Test
    void report_rejectsNonHolderUnknownAndClosed(@TempDir Path dir) {
        try (Pipeline pipeline = new Pipeline(dir, emitting(ResultPolicy.CHAIN), FX_SETTINGS);
             EffectRuntime runtime = runtime(pipeline.store)) {
            pipeline.applyNext(1);
            runtime.tick();
            assertThat(runtime.claim("worker-1", Set.of(), 10, 60)).hasSize(1);

            // Wrong holder / unclaimed / unknown position all rejected
            assertThat(runtime.report("worker-2", 1, 0, true, null, null)).isFalse();
            assertThat(runtime.report("worker-1", 1, 5, true, null, null)).isFalse();

            assertThat(runtime.report("worker-1", 1, 0, false, null, "target said no")).isTrue();
            // CHAIN + definitive failure → locally terminal FAILED, injectable
            var injections = runtime.pendingInjections(10, 0);
            assertThat(injections).hasSize(1);
            assertThat(injections.get(0).confirmed()).isFalse();
            // A second report after the terminal is rejected
            assertThat(runtime.report("worker-1", 1, 0, true, null, null)).isFalse();
        }
    }

    @Test
    void expiredLease_reopensTheEffect(@TempDir Path dir) throws Exception {
        try (Pipeline pipeline = new Pipeline(dir, emitting(ResultPolicy.NONE), FX_SETTINGS);
             EffectRuntime runtime = runtime(pipeline.store)) {
            pipeline.applyNext(1);
            runtime.tick();

            assertThat(runtime.claim("worker-1", Set.of(), 10, 1)).hasSize(1); // 1s lease
            assertThat(runtime.claim("worker-2", Set.of(), 10, 60)).isEmpty(); // lease live
            Thread.sleep(1100);
            // Lease expired → the effect is claimable again (failover)
            List<PendingEffect> reclaimed = runtime.claim("worker-2", Set.of(), 10, 60);
            assertThat(reclaimed).hasSize(1);
            // The original worker's late report is now rejected (fencing)
            assertThat(runtime.report("worker-1", 1, 0, true, null, null)).isFalse();
            assertThat(runtime.report("worker-2", 1, 0, true, null, null)).isTrue();
        }
    }

    @Test
    void claim_respectsTypeFilterAndGate(@TempDir Path dir) {
        try (Pipeline pipeline = new Pipeline(dir, emitting(ResultPolicy.NONE), FX_SETTINGS);
             EffectRuntime runtime = runtime(pipeline.store)) {
            pipeline.applyNext(1);
            runtime.tick();
            assertThat(runtime.claim("worker-1", Set.of("other.type"), 10, 60)).isEmpty();
            assertThat(runtime.claim("worker-1", Set.of("test.action"), 10, 60)).hasSize(1);
        }
    }

    // ------------------------------------------------------------------

    private static AppStateMachine emitting(ResultPolicy policy) {
        return new AppStateMachine() {
            @Override public String id() { return "emitter"; }
            @Override public void apply(AppBlock block, AppStateWriter writer) { }
            @Override public void apply(AppBlock block, AppStateWriter writer, AppEffectEmitter effects) {
                for (AppMessage message : block.messages()) {
                    effects.emit(EffectIntent.of("test.action", message.getBody())
                            .scope("s/" + block.height())
                            .result(policy)
                            .build());
                }
            }
        };
    }

    private static final class Pipeline implements AutoCloseable {
        final AppLedgerStore store;
        final AppStateMachine machine;
        final FxKernel kernel;
        byte[] prevHash = AppBlock.GENESIS_PREV_HASH;
        long height;
        long senderSeq;

        Pipeline(Path dir, AppStateMachine machine, Map<String, String> settings) {
            this.store = new AppLedgerStore(dir.toString(), LoggerFactory.getLogger("fx-test"));
            this.machine = machine;
            this.kernel = new FxKernel(EffectsSettings.fromSettings(settings));
        }

        void applyNext(int messages) {
            height++;
            byte[] sender = new byte[32];
            List<AppMessage> list = new ArrayList<>();
            for (int i = 0; i < messages; i++) {
                byte[] body = ("body-" + height + "-" + i).getBytes(StandardCharsets.UTF_8);
                long seq = ++senderSeq;
                byte[] id = AppMessage.computeMessageId("fx-chain", "t", sender, seq,
                        4_000_000_000L, body);
                list.add(AppMessage.builder()
                        .messageId(id).chainId("fx-chain").topic("t").sender(sender)
                        .senderSeq(seq).expiresAt(4_000_000_000L).body(body)
                        .authScheme(0).authProof(new byte[64]).build());
            }
            AppBlock block = new AppBlock(AppBlock.BLOCK_VERSION, "fx-chain", height, prevHash,
                    0, new byte[0], 1_700_000_000_000L + height * 1_000,
                    AppBlockCodec.messagesRoot(list), new byte[32], list, sender,
                    FinalityCert.empty());
            FxBlockApplier.Applied applied = FxBlockApplier.applyAndCommit(store, kernel, machine, block);
            prevHash = applied.blockHash();
        }

        @Override
        public void close() {
            store.close();
        }
    }
}
