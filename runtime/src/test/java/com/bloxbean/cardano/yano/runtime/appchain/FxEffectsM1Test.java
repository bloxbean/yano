package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.effects.ActivationSchedule;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectIntent;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectLimitExceededException;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectOutcome;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectRecord;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectResult;
import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;
import com.bloxbean.cardano.yano.api.appchain.effects.FxKeys;
import com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.WriteBatch;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FX-M1 consensus plane (ADR app-layer/010 F1–F4, F9; ADR 010.1):
 * deterministic emission, outbox CF staging, effectsRoot commitment, reserved
 * trie prefix, deterministic expiry, and the upgrade replay-matrix.
 */
@Timeout(120)
class FxEffectsM1Test {

    private static final Map<String, String> FX_SETTINGS = Map.of(
            "effects.enabled", "true",
            "effects.max-per-block", "8",
            "effects.max-payload-bytes", "256");

    // ------------------------------------------------------------------
    // Determinism through the conformance harness
    // ------------------------------------------------------------------

    @Test
    void emittingMachine_isDeterministic_includingRestartReplay() {
        StateMachineConformance.builder(provider("emitter", EmittingMachine::new))
                .settings(FX_SETTINGS)
                .blocks(20)
                .messagesPerBlock(3)
                .runs(3)
                .assertDeterministic();
    }

    @Test
    void effectsDisabled_emitThrowsDeterministically() {
        // Without effects.enabled, emit() rejects identically on every node —
        // a hard failure inside apply, never a divergence
        assertThatThrownBy(() -> StateMachineConformance
                .builder(provider("emitter", EmittingMachine::new))
                .blocks(2)
                .runs(2)
                .run())
                .isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("effects.enabled=false");
    }

    // ------------------------------------------------------------------
    // Pipeline mechanics against a real ledger
    // ------------------------------------------------------------------

    @Test
    void emission_landsInOutboxWithEffectsRootLeaf(@TempDir Path dir) {
        try (Pipeline pipeline = new Pipeline(dir, new EmittingMachine(), FX_SETTINGS)) {
            FxKernel.Result fx = pipeline.applyNext(2); // 2 messages → 2 effects

            assertThat(fx.emitted()).hasSize(2);
            EffectRecord first = fx.emitted().get(0);
            assertThat(first.effectId().canonical()).isEqualTo("fx-chain/1/0");
            assertThat(first.gate()).isEqualTo(FinalityGate.APP_FINAL); // CHAIN_DEFAULT resolved
            assertThat(first.result()).isEqualTo(ResultPolicy.CHAIN);

            // Outbox CF rows, committed atomically with the block
            assertThat(pipeline.store.fxRecord(1, 0)).isPresent();
            assertThat(pipeline.store.fxRecord(1, 1)).isPresent();
            assertThat(pipeline.store.fxRecordsFrom(0, 10)).hasSize(2);
            assertThat(pipeline.store.fxOpenCount()).isEqualTo(2);

            // effectsRoot leaf in the AUTHENTICATED state, provable via stateGet
            List<byte[]> hashes = new ArrayList<>();
            fx.emitted().forEach(r -> hashes.add(r.effectHash()));
            Optional<byte[]> leaf = pipeline.store.stateGet(FxKeys.effectsRootKey(1));
            assertThat(leaf).isPresent();
            assertThat(leaf.get()).isEqualTo(FxKeys.effectsRoot(hashes));

            // Block meta row: count + effectsRoot
            byte[] meta = pipeline.store.fxBlockMeta(1).orElseThrow();
            assertThat(meta.length).isEqualTo(36);
        }
    }

    @Test
    void expirySweep_incorporatesExpiredDeterministically(@TempDir Path dir) {
        EmittingMachine machine = new EmittingMachine(3); // expiryBlocks=3
        try (Pipeline pipeline = new Pipeline(dir, machine, FX_SETTINGS)) {
            pipeline.applyNext(1);      // height 1: emit, expiry registered at height 4
            assertThat(pipeline.store.fxExpiryBucket(4)).hasSize(1);
            pipeline.applyNext(0);      // heights 2..3: nothing
            pipeline.applyNext(0);
            assertThat(pipeline.store.fxOpenCount()).isEqualTo(1);

            FxKernel.Result fx = pipeline.applyNext(0); // height 4: sweep fires

            assertThat(fx.incorporated()).hasSize(1);
            EffectResult expired = fx.incorporated().get(0);
            assertThat(expired.outcome()).isEqualTo(EffectOutcome.EXPIRED);
            assertThat(expired.resultHeight()).isEqualTo(4);

            // Closure row + consumed bucket + open count back to zero
            assertThat(pipeline.store.fxClosed(1, 0)).isTrue();
            assertThat(pipeline.store.fxExpiryBucket(4)).isEmpty();
            assertThat(pipeline.store.fxOpenCount()).isZero();

            // per-effect commitment: ~fx/done leaf carries the envelope hash
            byte[] done = pipeline.store.stateGet(FxKeys.doneKey(expired.effectId())).orElseThrow();
            assertThat(done).isEqualTo(expired.envelopeHash());

            // the machine SAW the expiry via onEffectResult (marker written in-root)
            assertThat(pipeline.store.stateGet(key("expired/fx-chain/1/0"))).isPresent();
        }
    }

    @Test
    void perBlockOutcomeCommitment_writesResultsRootLeaf(@TempDir Path dir) {
        Map<String, String> settings = new java.util.LinkedHashMap<>(FX_SETTINGS);
        settings.put("effects.outcome-commitment", "per-block");
        EmittingMachine machine = new EmittingMachine(2);
        try (Pipeline pipeline = new Pipeline(dir, machine, settings)) {
            pipeline.applyNext(1);      // height 1: emit, expires at 3
            pipeline.applyNext(0);      // height 2
            FxKernel.Result fx = pipeline.applyNext(0); // height 3: sweep

            assertThat(fx.incorporated()).hasSize(1);
            // No per-effect done leaf in per-block mode…
            assertThat(pipeline.store.stateGet(
                    FxKeys.doneKey(fx.incorporated().get(0).effectId()))).isEmpty();
            // …but a resultsRoot leaf over the block's incorporated outcomes
            byte[] resultsRoot = pipeline.store.stateGet(FxKeys.resultsRootKey(3)).orElseThrow();
            assertThat(resultsRoot).isEqualTo(
                    FxKeys.effectsRoot(List.of(fx.incorporated().get(0).envelopeHash())));
            // Closure row exists either way (consensus-tier dedup source)
            assertThat(pipeline.store.fxClosed(1, 0)).isTrue();
        }
    }

    @Test
    void reservedPrefixGuard_rejectsApplicationWrites(@TempDir Path dir) {
        AppStateMachine trespasser = new AppStateMachine() {
            @Override public String id() { return "trespasser"; }
            @Override public void apply(AppBlock block, AppStateWriter writer) {
                writer.put("~fx/root/hijack".getBytes(StandardCharsets.UTF_8), new byte[]{1});
            }
        };
        try (Pipeline pipeline = new Pipeline(dir, trespasser, FX_SETTINGS)) {
            assertThatThrownBy(() -> pipeline.applyNext(1))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasStackTraceContaining("~fx/");
        }
    }

    @Test
    void emissionCaps_throwDeterministically(@TempDir Path dir) {
        AppStateMachine flooder = machineOf((block, writer, effects) -> {
            for (int i = 0; i < 100; i++) {
                effects.emit(EffectIntent.of("webhook.post", new byte[]{1}).build());
            }
        });
        try (Pipeline pipeline = new Pipeline(dir, flooder, FX_SETTINGS)) {
            assertThatThrownBy(() -> pipeline.applyNext(1))
                    .hasRootCauseInstanceOf(EffectLimitExceededException.class)
                    .hasStackTraceContaining("max-per-block");
        }

        AppStateMachine bloater = machineOf((block, writer, effects) ->
                effects.emit(EffectIntent.of("webhook.post", new byte[4096]).build()));
        try (Pipeline pipeline = new Pipeline(dir.resolve("bloat"), bloater, FX_SETTINGS)) {
            assertThatThrownBy(() -> pipeline.applyNext(1))
                    .hasRootCauseInstanceOf(EffectLimitExceededException.class)
                    .hasStackTraceContaining("max-payload-bytes");
        }
    }

    @Test
    void disabledEffects_produceNoFxFootprint(@TempDir Path dir) {
        try (Pipeline pipeline = new Pipeline(dir, new OrderedLogStateMachine(), Map.of())) {
            FxKernel.Result fx = pipeline.applyNext(2);
            assertThat(fx.isEmpty()).isTrue();
            assertThat(pipeline.store.fxRecordsFrom(0, 10)).isEmpty();
            assertThat(pipeline.store.fxOpenCount()).isZero();
        }
    }

    // ------------------------------------------------------------------
    // Upgrade replay-matrix (ADR 010.1 §3)
    // ------------------------------------------------------------------

    @Test
    void unguardedEmissionChange_failsReplayStability() {
        // v2 changes WHAT is emitted without gating on an activation height
        assertThatThrownBy(() ->
                StateMachineConformance.upgrade(
                                provider("emitter", EmittingMachine::new),
                                provider("emitter", () -> new EmittingMachine("payment.v2")))
                        .settings(FX_SETTINGS)
                        .activationAt("payment-v2", 10)
                        .blocks(20)
                        .assertReplayStable())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("NOT replay-stable");
    }

    @Test
    void activationGatedEmissionChange_isReplayStable() {
        StateMachineConformance.upgrade(
                        provider("emitter", EmittingMachine::new),
                        provider("emitter", GatedV2Machine::new))
                .settings(FX_SETTINGS)
                .activationAt("payment-v2", 10)
                .blocks(20)
                .assertReplayStable();
    }

    // ------------------------------------------------------------------
    // Test machines and plumbing
    // ------------------------------------------------------------------

    /** Emits one CHAIN effect per message; optional expiry; configurable type. */
    private static class EmittingMachine implements AppStateMachine {
        final long expiryBlocks;
        final String type;

        EmittingMachine() {
            this(0, "payment.v1");
        }

        EmittingMachine(long expiryBlocks) {
            this(expiryBlocks, "payment.v1");
        }

        EmittingMachine(String type) {
            this(0, type);
        }

        EmittingMachine(long expiryBlocks, String type) {
            this.expiryBlocks = expiryBlocks;
            this.type = type;
        }

        @Override
        public String id() {
            return "emitter";
        }

        @Override
        public void apply(AppBlock block, AppStateWriter writer) {
            throw new UnsupportedOperationException("engine always calls the 3-arg apply");
        }

        @Override
        public void apply(AppBlock block, AppStateWriter writer, AppEffectEmitter effects) {
            int index = 0;
            for (var message : block.messages()) {
                writer.put(key("m/" + block.height() + "/" + index), message.getBody());
                effects.emit(EffectIntent.of(type, message.getBody())
                        .scope("m/" + block.height() + "/" + index)
                        .result(ResultPolicy.CHAIN)
                        .expiryBlocks(expiryBlocks)
                        .sourceMessageId(message.getMessageId())
                        .build());
                index++;
            }
        }

        @Override
        public void onEffectResult(AppBlock block, EffectResult result, AppStateWriter writer) {
            writer.put(key("expired/" + result.effectId().canonical()),
                    String.valueOf(result.outcome().code()).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** v2 behavior correctly gated on the ActivationSchedule (010.1 §2.2). */
    private static final class GatedV2Machine extends EmittingMachine {
        private ActivationSchedule activations = ActivationSchedule.empty();

        @Override
        public void apply(AppBlock block, AppStateWriter writer, AppEffectEmitter effects) {
            int index = 0;
            for (var message : block.messages()) {
                writer.put(key("m/" + block.height() + "/" + index), message.getBody());
                String effectType = activations.isActive("payment-v2", block.height())
                        ? "payment.v2" : "payment.v1";
                effects.emit(EffectIntent.of(effectType, message.getBody())
                        .scope("m/" + block.height() + "/" + index)
                        .result(ResultPolicy.CHAIN)
                        .sourceMessageId(message.getMessageId())
                        .build());
                index++;
            }
        }

        void activations(ActivationSchedule schedule) {
            this.activations = schedule;
        }
    }

    private static AppStateMachineProvider provider(String id,
                                                    java.util.function.Supplier<AppStateMachine> factory) {
        return new AppStateMachineProvider() {
            @Override public String id() { return id; }
            @Override public AppStateMachine create() { return factory.get(); }
            @Override public AppStateMachine create(
                    com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext context) {
                AppStateMachine machine = factory.get();
                if (machine instanceof GatedV2Machine gated) {
                    gated.activations(ActivationSchedule.from(context.settings(), id));
                }
                return machine;
            }
        };
    }

    @FunctionalInterface
    private interface TriApply {
        void apply(AppBlock block, AppStateWriter writer, AppEffectEmitter effects);
    }

    private static AppStateMachine machineOf(TriApply body) {
        return new AppStateMachine() {
            @Override public String id() { return "inline"; }
            @Override public void apply(AppBlock block, AppStateWriter writer) { }
            @Override public void apply(AppBlock block, AppStateWriter writer, AppEffectEmitter effects) {
                body.apply(block, writer, effects);
            }
        };
    }

    private static byte[] key(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** Minimal engine-equivalent pipeline over a real AppLedgerStore. */
    private static final class Pipeline implements AutoCloseable {
        final AppLedgerStore store;
        final AppStateMachine machine;
        final FxKernel kernel;
        final FxKernel.FxReader reader;
        byte[] prevHash = AppBlock.GENESIS_PREV_HASH;
        long height;
        long senderSeq;

        Pipeline(Path dir, AppStateMachine machine, Map<String, String> settings) {
            this.store = new AppLedgerStore(dir.toString(), LoggerFactory.getLogger("fx-test"));
            this.machine = machine;
            this.kernel = new FxKernel(EffectsSettings.fromSettings(settings));
            this.reader = new FxKernel.FxReader() {
                @Override public List<long[]> expiryBucket(long h) { return store.fxExpiryBucket(h); }
                @Override public Optional<EffectRecord> record(long h, int o) { return store.fxRecord(h, o); }
                @Override public boolean closed(long h, int o) { return store.fxClosed(h, o); }
                @Override public long openCount() { return store.fxOpenCount(); }
            };
        }

        /** Apply + commit the next block with N generated messages; returns the kernel result. */
        FxKernel.Result applyNext(int messages) {
            height++;
            byte[] sender = new byte[32];
            List<com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage> list = new ArrayList<>();
            for (int i = 0; i < messages; i++) {
                byte[] body = ("body-" + height + "-" + i).getBytes(StandardCharsets.UTF_8);
                long seq = ++senderSeq;
                byte[] id = com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage
                        .computeMessageId("fx-chain", "t", sender, seq, 4_000_000_000L, body);
                list.add(com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage.builder()
                        .messageId(id).chainId("fx-chain").topic("t").sender(sender)
                        .senderSeq(seq).expiresAt(4_000_000_000L).body(body)
                        .authScheme(0).authProof(new byte[64]).build());
            }
            AppBlock block = new AppBlock(AppBlock.BLOCK_VERSION, "fx-chain", height, prevHash,
                    0, new byte[0], 1_700_000_000_000L + height * 1_000,
                    AppBlockCodec.messagesRoot(list), new byte[32], list, sender,
                    FinalityCert.empty());

            WriteBatch batch = new WriteBatch();
            byte[] committedRoot = store.stateRoot();
            try {
                FxKernel.Result[] fx = new FxKernel.Result[1];
                byte[] newRoot = store.mpfNodeStore().withBatch(batch, () -> {
                    MpfTrie trie = committedRoot != null
                            ? new MpfTrie(store.mpfNodeStore(), committedRoot)
                            : new MpfTrie(store.mpfNodeStore());
                    fx[0] = kernel.apply(machine, block, trie, reader);
                    return trie.getRootHash();
                });
                byte[] effectiveRoot = newRoot != null ? newRoot : new byte[32];
                AppBlock applied = new AppBlock(block.version(), block.chainId(), block.height(),
                        block.prevHash(), block.l1Slot(), block.l1BlockHash(), block.timestamp(),
                        block.messagesRoot(), effectiveRoot, block.messages(), block.proposer(),
                        block.cert());
                store.stageFx(batch, block.height(), fx[0]);
                byte[] blockHash = AppBlockCodec.blockHash(applied);
                store.commitBlock(applied, blockHash, effectiveRoot, batch);
                prevHash = blockHash;
                return fx[0];
            } finally {
                batch.close();
            }
        }

        @Override
        public void close() {
            store.close();
        }
    }
}
