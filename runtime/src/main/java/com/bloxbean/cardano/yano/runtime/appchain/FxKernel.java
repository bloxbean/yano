package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectId;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectIntent;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectLimitExceededException;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectOutcome;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectRecord;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectResult;
import com.bloxbean.cardano.yano.api.appchain.effects.FinalityGate;
import com.bloxbean.cardano.yano.api.appchain.effects.FxKeys;
import com.bloxbean.cardano.yano.api.appchain.effects.ResultPolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The deterministic effect kernel (ADR app-layer/010 F1–F4, F9): drives one
 * block's transition with emission, the expiry sweep, and the trie
 * commitments ({@code ~fx/root/H}, per-effect {@code ~fx/done} leaves or a
 * per-block {@code ~fx/results/H} leaf). Shared verbatim by the engine
 * (proposer, follower and catch-up paths) and the conformance harness — one
 * pipeline, one root.
 * <p>
 * Everything here is a pure function of {@code (block, committed state,
 * committed fx records)}; the kernel never touches wall clock, randomness or
 * node-local runtime state. All consensus-tier reads (expiry buckets, open
 * count) happen HERE at apply time — staging ({@code AppLedgerStore.stageFx})
 * is pure writes, so nothing depends on commit-time database state.
 */
final class FxKernel {

    /** Consensus-tier reads the kernel needs during apply — backed by the fx records CF. */
    interface FxReader {

        /** Expiry-bucket entries {@code (height, ordinal)} registered for this height. */
        List<long[]> expiryBucket(long height);

        /** The emission record at (height, ordinal), if it exists. */
        Optional<EffectRecord> record(long height, int ordinal);

        /** True when a terminal outcome for this effect has already been incorporated. */
        boolean closed(long height, int ordinal);

        /** Committed count of open {@link ResultPolicy#CHAIN} effects. */
        long openCount();
    }

    /** One emitted effect with its canonical bytes and hash, computed exactly once. */
    record StagedEffect(EffectRecord record, byte[] encoded, byte[] effectHash) {

        static StagedEffect of(EffectRecord record) {
            byte[] encoded = record.encode();
            return new StagedEffect(record, encoded,
                    com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash256(encoded));
        }
    }

    /** Everything one block's apply produced, for atomic (pure-write) staging at commit. */
    record Result(List<StagedEffect> emitted,
                  byte[] effectsRoot,
                  List<EffectResult> incorporated,
                  Map<Long, byte[]> bucketPuts,
                  long consumedExpiryBucket,
                  long newOpenCount) {

        static final Result NONE = new Result(List.of(), null, List.of(), Map.of(), -1, 0);

        boolean isEmpty() {
            return emitted.isEmpty() && incorporated.isEmpty() && consumedExpiryBucket < 0;
        }

        List<EffectRecord> records() {
            return emitted.stream().map(StagedEffect::record).toList();
        }
    }

    private final EffectsSettings settings;

    FxKernel(EffectsSettings settings) {
        this.settings = settings;
    }

    /**
     * Apply one block through the machine with effects. Order (ADR-010 F8/F9):
     * expiry sweep (outcomes reference strictly older effects) → machine
     * apply → commitment leaves. Trie writes made here bypass the reserved
     * prefix guard; the machine's writer enforces it — ALWAYS, effects
     * enabled or not: the {@code ~fx/} keyspace is reserved from genesis
     * (ADR-010 F4), so enabling effects later can never collide with
     * historical application leaves.
     */
    Result apply(AppStateMachine machine, AppBlock block, MpfTrie trie, FxReader reader) {
        AppStateWriter machineWriter = guardedWriter(trie, settings.strictReservedPrefix());

        if (!settings.enabled()) {
            machine.apply(block, machineWriter, AppEffectEmitter.rejecting(
                    "Effects are disabled for this chain (effects.enabled=false)"));
            return Result.NONE;
        }

        long committedOpen = reader.openCount();

        // 1. Deterministic expiry sweep at this height (FX-M1: the only
        //    incorporation path; ~fx/result messages join in FX-M3). Every
        //    swept effect is ResultPolicy.CHAIN by construction — expiry is
        //    only registrable on CHAIN intents (EffectIntent validation) —
        //    so |incorporated| decrements the open-CHAIN count one-for-one.
        List<EffectResult> incorporated = new ArrayList<>();
        List<long[]> bucket = reader.expiryBucket(block.height());
        for (long[] entry : bucket) {
            long height = entry[0];
            int ordinal = (int) entry[1];
            if (reader.closed(height, ordinal)) {
                continue; // already terminal — nothing to expire
            }
            EffectRecord record = reader.record(height, ordinal).orElseThrow(() ->
                    // Fail LOUDLY: a bucket entry without its record means the
                    // fx CF is inconsistent on THIS node (partial restore /
                    // corruption). Silently skipping would fork the state
                    // root; rebuild the outbox by replay instead (ADR-010 F10).
                    new IllegalStateException("Effect outbox inconsistent: expiry bucket at height "
                            + block.height() + " references missing record " + height + "/" + ordinal
                            + " — rebuild app_fx_records by replay"));
            EffectResult expired = new EffectResult(record.effectId(), record.type(), record.scope(),
                    EffectOutcome.EXPIRED, new byte[0], null, block.height());
            if (settings.outcomeCommitment() == EffectsSettings.OutcomeCommitment.PER_EFFECT) {
                trie.put(FxKeys.doneKey(expired.effectId()), expired.envelopeHash());
            }
            incorporated.add(expired);
            machine.onEffectResult(block, expired, machineWriter);
        }

        // 2. Machine transition with emission.
        BlockEmitter emitter = new BlockEmitter(block, committedOpen, incorporated.size());
        machine.apply(block, machineWriter, emitter);

        // 3. Commitment leaves.
        byte[] effectsRoot = null;
        if (!emitter.emitted.isEmpty()) {
            List<byte[]> hashes = new ArrayList<>(emitter.emitted.size());
            for (StagedEffect staged : emitter.emitted) {
                hashes.add(staged.effectHash());
            }
            effectsRoot = FxKeys.effectsRoot(hashes);
            trie.put(FxKeys.effectsRootKey(block.height()), effectsRoot);
        }
        if (settings.outcomeCommitment() == EffectsSettings.OutcomeCommitment.PER_BLOCK
                && !incorporated.isEmpty()) {
            List<byte[]> outcomeHashes = new ArrayList<>(incorporated.size());
            for (EffectResult result : incorporated) {
                outcomeHashes.add(result.envelopeHash());
            }
            trie.put(FxKeys.resultsRootKey(block.height()), FxKeys.effectsRoot(outcomeHashes));
        }

        // 4. Merge expiry buckets against committed state HERE (apply time),
        //    so staging never reads the database (pipelining-safe).
        Map<Long, List<long[]>> bucketAdds = new LinkedHashMap<>();
        for (StagedEffect staged : emitter.emitted) {
            EffectRecord record = staged.record();
            if (record.expiryHeight() > 0) {
                bucketAdds.computeIfAbsent(record.expiryHeight(), h -> new ArrayList<>())
                        .add(new long[]{record.height(), record.ordinal()});
            }
        }
        Map<Long, byte[]> bucketPuts = new LinkedHashMap<>();
        for (Map.Entry<Long, List<long[]>> add : bucketAdds.entrySet()) {
            List<long[]> merged = reader.expiryBucket(add.getKey());
            merged.addAll(add.getValue());
            bucketPuts.put(add.getKey(), FxBucketCodec.encode(merged));
        }

        long newOpen = committedOpen + emitter.chainEmitted - incorporated.size();
        return new Result(List.copyOf(emitter.emitted), effectsRoot, List.copyOf(incorporated),
                Map.copyOf(bucketPuts), bucket.isEmpty() ? -1 : block.height(),
                Math.max(0, newOpen));
    }

    // ------------------------------------------------------------------

    private AppStateWriter guardedWriter(MpfTrie trie, boolean strict) {
        return new AppStateWriter() {
            @Override
            public void put(byte[] key, byte[] value) {
                rejectReserved(key);
                trie.put(key, value);
            }

            @Override
            public void delete(byte[] key) {
                rejectReserved(key);
                trie.delete(key);
            }

            @Override
            public Optional<byte[]> get(byte[] key) {
                return Optional.ofNullable(trie.get(key)); // reads of ~fx/* are allowed
            }

            @Override
            public byte[] stateRoot() {
                byte[] root = trie.getRootHash();
                return root != null ? root : new byte[32]; // AppStateReader contract: never null
            }

            private void rejectReserved(byte[] key) {
                if (strict && FxKeys.isReserved(key)) {
                    // Deterministic on every node — a rejected proposal, never a divergence
                    throw new IllegalArgumentException(
                            "Application state keys must not use the reserved '~fx/' prefix (ADR-010 F4)");
                }
            }
        };
    }

    private final class BlockEmitter implements AppEffectEmitter {
        private final AppBlock block;
        private final long committedOpen;
        private final int closedThisBlock;
        private final List<StagedEffect> emitted = new ArrayList<>();
        private int chainEmitted;

        BlockEmitter(AppBlock block, long committedOpen, int closedThisBlock) {
            this.block = block;
            this.committedOpen = committedOpen;
            this.closedThisBlock = closedThisBlock;
        }

        @Override
        public EffectId emit(EffectIntent intent) {
            if (emitted.size() >= settings.maxPerBlock()) {
                throw new EffectLimitExceededException("effects.max-per-block ("
                        + settings.maxPerBlock() + ") exceeded at height " + block.height());
            }
            if (intent.payload().length > settings.maxPayloadBytes()) {
                throw new EffectLimitExceededException("effect payload of " + intent.payload().length
                        + " bytes exceeds effects.max-payload-bytes (" + settings.maxPayloadBytes() + ")");
            }
            if (intent.expiryBlocks() > settings.maxExpiryBlocks()) {
                // Also the overflow guard: maxExpiryBlocks bounds height + expiry
                // far below Long.MAX_VALUE, so expiryHeight can never wrap
                throw new EffectLimitExceededException("effect expiry of " + intent.expiryBlocks()
                        + " blocks exceeds effects.max-expiry-blocks (" + settings.maxExpiryBlocks() + ")");
            }
            FinalityGate gate = intent.gate() == FinalityGate.CHAIN_DEFAULT
                    ? settings.defaultGate() : intent.gate();
            int ordinal = emitted.size();
            long expiryHeight = intent.expiryBlocks() > 0 ? block.height() + intent.expiryBlocks() : 0;
            EffectRecord record = new EffectRecord(EffectRecord.RECORD_VERSION, block.chainId(),
                    block.height(), ordinal, intent.type(), intent.payload(), intent.scope(),
                    gate, intent.result(), expiryHeight, intent.sourceMessageId());
            emitted.add(StagedEffect.of(record));
            if (intent.result() == ResultPolicy.CHAIN) {
                chainEmitted++;
            }
            return record.effectId();
        }

        @Override
        public long pendingCount() {
            return Math.max(0, committedOpen + chainEmitted - closedThisBlock);
        }
    }
}
