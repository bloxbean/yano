package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AuthScheme;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainInfo;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
import com.bloxbean.cardano.yano.api.appchain.AppStateReader;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.effects.EffectRecord;
import org.rocksdb.WriteBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

/**
 * Determinism conformance harness for {@link AppStateMachine} implementations
 * (ADR app-layer/008.1 I1.6; extended for effects by ADR app-layer/010/010.1).
 * The framework re-executes every block on every member and rejects state-root
 * mismatches — a nondeterministic state machine therefore STALLS its chain in
 * production. This harness catches that before deployment: it applies one
 * identical block corpus through the real ledger commit path (the same
 * FxKernel pipeline and MPF batching as production) in N independent runs —
 * plus a kill-and-reopen replay run — and asserts byte-identical state roots
 * AND ordered effect lists at every height.
 *
 * <pre>
 * StateMachineConformance.builder(new MyProvider())
 *         .settings(Map.of("machines.my-machine.key", "value", "effects.enabled", "true"))
 *         .blocks(50).messagesPerBlock(5).seed(42)
 *         .bodyGenerator((height, index, random) -> myRealisticCommand(random))
 *         .assertDeterministic();
 * </pre>
 *
 * The upgrade replay-matrix (ADR 010.1 §3) verifies that a NEW machine
 * version replays OLD history byte-identically below its activation height:
 *
 * <pre>
 * StateMachineConformance.upgrade(new MyProviderV1(), new MyProviderV2())
 *         .settings(commonSettings)
 *         .activationAt("payment-v2-split", 60)
 *         .blocks(100)
 *         .assertReplayStable();
 * </pre>
 */
public final class StateMachineConformance {

    private static final Logger log = LoggerFactory.getLogger(StateMachineConformance.class);

    private final AppStateMachineProvider provider;
    private final Map<String, String> settings;
    private final String chainId;
    private final int blocks;
    private final int messagesPerBlock;
    private final long seed;
    private final BodyGenerator bodyGenerator;
    private final int runs;
    private final long restartAtHeight;

    /** Deterministic command generator: same (height, index, seeded random) → same body. */
    @FunctionalInterface
    public interface BodyGenerator {
        byte[] body(long height, int index, Random random);
    }

    private StateMachineConformance(Builder builder) {
        this.provider = builder.provider;
        this.settings = builder.settings;
        this.chainId = builder.chainId;
        this.blocks = builder.blocks;
        this.messagesPerBlock = builder.messagesPerBlock;
        this.seed = builder.seed;
        this.bodyGenerator = builder.bodyGenerator;
        this.runs = builder.runs;
        this.restartAtHeight = builder.restartAtHeight;
    }

    public static Builder builder(AppStateMachineProvider provider) {
        return new Builder(provider);
    }

    /** Entry point for the ADR-010.1 upgrade replay-matrix. */
    public static UpgradeBuilder upgrade(AppStateMachineProvider oldProvider,
                                         AppStateMachineProvider newProvider) {
        return new UpgradeBuilder(oldProvider, newProvider);
    }

    public static final class Builder {
        private final AppStateMachineProvider provider;
        private Map<String, String> settings = Map.of();
        private String chainId = "conformance-chain";
        private int blocks = 20;
        private int messagesPerBlock = 5;
        private long seed = 42;
        private BodyGenerator bodyGenerator =
                (height, index, random) -> ("msg-" + height + "-" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        private int runs = 3;
        private long restartAtHeight = -1; // default: mid-corpus

        private Builder(AppStateMachineProvider provider) {
            this.provider = provider;
        }

        public Builder settings(Map<String, String> value) { this.settings = Map.copyOf(value); return this; }
        public Builder chainId(String value) { this.chainId = value; return this; }
        public Builder blocks(int value) { this.blocks = value; return this; }
        public Builder messagesPerBlock(int value) { this.messagesPerBlock = value; return this; }
        public Builder seed(long value) { this.seed = value; return this; }
        public Builder bodyGenerator(BodyGenerator value) { this.bodyGenerator = value; return this; }
        /** Independent full replays compared root-by-root (default 3). */
        public Builder runs(int value) { this.runs = Math.max(2, value); return this; }
        /** Close and reopen the ledger at this height in the replay run (default: middle). */
        public Builder restartAtHeight(long value) { this.restartAtHeight = value; return this; }

        public Result run() {
            return new StateMachineConformance(this).execute();
        }

        /** Run and throw an {@link AssertionError} with a precise diff on divergence. */
        public void assertDeterministic() {
            Result result = run();
            if (!result.deterministic()) {
                throw new AssertionError(result.describeDivergence());
            }
        }
    }

    /** Per-run, per-height outcomes (state root + ordered effect hashes) and the first divergence. */
    public record Result(List<Map<Long, HeightOutcome>> outcomesPerRun, Divergence divergence) {

        public boolean deterministic() {
            return divergence == null;
        }

        public String describeDivergence() {
            if (divergence == null) {
                return "deterministic";
            }
            return "State machine is NOT deterministic (" + divergence.kind() + "): at height "
                    + divergence.height()
                    + " run 0 produced " + divergence.baseline()
                    + " but run " + divergence.run() + " produced " + divergence.divergent()
                    + ". Check the apply() implementation for wall-clock time, randomness, I/O,"
                    + " unordered iteration — or, for emission diffs, an unguarded emission-logic"
                    + " change (ADR 010.1).";
        }
    }

    /** State root and the block's ordered effect hashes (hex). */
    public record HeightOutcome(String root, List<String> effectHashes) {
    }

    public record Divergence(String kind, long height, int run, String baseline, String divergent) {
    }

    // ------------------------------------------------------------------

    private Result execute() {
        List<AppBlock> corpus = buildCorpus(chainId, blocks, messagesPerBlock, seed, bodyGenerator);
        List<Map<Long, HeightOutcome>> outcomesPerRun = new ArrayList<>();
        try {
            Path workDir = Files.createTempDirectory("appchain-conformance");
            for (int run = 0; run < runs; run++) {
                // The last run replays across a close/reopen at restartAtHeight
                long restartAt = run == runs - 1
                        ? (restartAtHeight > 0 ? restartAtHeight : Math.max(1, blocks / 2))
                        : -1;
                outcomesPerRun.add(applyCorpus(provider, settings, chainId, corpus,
                        workDir.resolve("run-" + run), restartAt));
            }
        } catch (Exception e) {
            throw new RuntimeException("Conformance harness failed", e);
        }
        return new Result(outcomesPerRun,
                compare(outcomesPerRun.get(0), outcomesPerRun, 1, blocks, 1));
    }

    /** Compare runs [firstRun..] against a baseline over heights [1..maxHeight]. */
    private static Divergence compare(Map<Long, HeightOutcome> baseline,
                                      List<Map<Long, HeightOutcome>> outcomesPerRun,
                                      int firstRun, long maxHeight, long fromHeight) {
        for (int run = firstRun; run < outcomesPerRun.size(); run++) {
            for (long height = fromHeight; height <= maxHeight; height++) {
                HeightOutcome expected = baseline.get(height);
                HeightOutcome actual = outcomesPerRun.get(run).get(height);
                if (expected == null || actual == null) {
                    continue;
                }
                if (!expected.root().equals(actual.root())) {
                    return new Divergence("state-root", height, run, expected.root(), actual.root());
                }
                if (!expected.effectHashes().equals(actual.effectHashes())) {
                    return new Divergence("effect-emission", height, run,
                            String.valueOf(expected.effectHashes()), String.valueOf(actual.effectHashes()));
                }
            }
        }
        return null;
    }

    /** Identical envelopes/blocks for every run: all inputs derive from the seed. */
    private static List<AppBlock> buildCorpus(String chainId, int blocks, int messagesPerBlock,
                                              long seed, BodyGenerator bodyGenerator) {
        Random random = new Random(seed);
        byte[] sender = new byte[32];
        new Random(seed ^ 0x5EED).nextBytes(sender);
        long expiresAt = 4_000_000_000L; // fixed far-future — never expires, identical everywhere

        List<AppBlock> corpus = new ArrayList<>(blocks);
        byte[] prevHash = AppBlock.GENESIS_PREV_HASH;
        long senderSeq = 0;
        for (long height = 1; height <= blocks; height++) {
            List<AppMessage> messages = new ArrayList<>(messagesPerBlock);
            for (int index = 0; index < messagesPerBlock; index++) {
                byte[] body = bodyGenerator.body(height, index, random);
                long sequence = ++senderSeq;
                byte[] messageId = AppMessage.computeMessageId(chainId, "t", sender,
                        sequence, expiresAt, body);
                messages.add(AppMessage.builder()
                        .messageId(messageId)
                        .chainId(chainId)
                        .topic("t")
                        .sender(sender)
                        .senderSeq(sequence)
                        .expiresAt(expiresAt)
                        .body(body)
                        .authScheme(AuthScheme.ED25519.getValue())
                        .authProof(new byte[64]) // apply() never re-verifies transport auth
                        .build());
            }
            AppBlock block = new AppBlock(AppBlock.BLOCK_VERSION, chainId, height, prevHash,
                    0, new byte[0],
                    1_700_000_000_000L + height * 1_000, // fixed deterministic timestamps
                    AppBlockCodec.messagesRoot(messages),
                    new byte[32], messages, sender, FinalityCert.empty());
            corpus.add(block);
            prevHash = AppBlockCodec.blockHash(block);
        }
        return corpus;
    }

    /** Apply the corpus through the real ledger commit path; returns outcome per height. */
    private static Map<Long, HeightOutcome> applyCorpus(AppStateMachineProvider provider,
                                                        Map<String, String> settings,
                                                        String chainId,
                                                        List<AppBlock> corpus,
                                                        Path dir, long restartAt) throws Exception {
        Map<Long, HeightOutcome> outcomes = new LinkedHashMap<>();
        AppStateMachine machine = provider.create(contextFor(chainId, settings));
        AppLedgerStore store = new AppLedgerStore(dir.toString(), log);
        try {
            machine.init(readerFor(store), new AppChainInfo(chainId, "00".repeat(32), 1));
            for (AppBlock block : corpus) {
                if (restartAt > 0 && block.height() == restartAt + 1) {
                    // Kill-and-reopen replay: crash recovery must not change roots
                    store.close();
                    store = new AppLedgerStore(dir.toString(), log);
                    machine = provider.create(contextFor(chainId, settings));
                    machine.init(readerFor(store), new AppChainInfo(chainId, "00".repeat(32), 1));
                }
                outcomes.put(block.height(), applyAndCommit(store, machine, settings, block));
            }
        } finally {
            store.close();
        }
        return outcomes;
    }

    /** Mirrors AppChainEngine.applyBlock + commitBlock (same FxKernel pipeline + MPF batch staging). */
    private static HeightOutcome applyAndCommit(AppLedgerStore store, AppStateMachine machine,
                                                Map<String, String> settings, AppBlock block) {
        FxKernel kernel = new FxKernel(EffectsSettings.fromSettings(settings));
        FxKernel.FxReader reader = new FxKernel.FxReader() {
            @Override public List<long[]> expiryBucket(long height) { return store.fxExpiryBucket(height); }
            @Override public Optional<EffectRecord> record(long height, int ordinal) {
                return store.fxRecord(height, ordinal);
            }
            @Override public boolean closed(long height, int ordinal) { return store.fxClosed(height, ordinal); }
            @Override public long openCount() { return store.fxOpenCount(); }
        };
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
            store.commitBlock(applied, AppBlockCodec.blockHash(applied), effectiveRoot, batch);
            List<String> effectHashes = new ArrayList<>(fx[0].emitted().size());
            for (EffectRecord record : fx[0].emitted()) {
                effectHashes.add(HexUtil.encodeHexString(record.effectHash()));
            }
            return new HeightOutcome(HexUtil.encodeHexString(effectiveRoot), effectHashes);
        } catch (RuntimeException e) {
            batch.close();
            throw e;
        } finally {
            batch.close();
        }
    }

    private static AppStateMachineContext contextFor(String chainId, Map<String, String> settings) {
        return new AppStateMachineContext() {
            @Override public String chainId() { return chainId; }
            @Override public Map<String, String> settings() { return settings; }
        };
    }

    private static AppStateReader readerFor(AppLedgerStore store) {
        return new AppStateReader() {
            @Override public Optional<byte[]> get(byte[] key) { return store.stateGet(key); }
            @Override public byte[] stateRoot() {
                byte[] root = store.stateRoot();
                return root != null ? root : new byte[32];
            }
        };
    }

    // ------------------------------------------------------------------
    // Upgrade replay-matrix (ADR 010.1 §3)
    // ------------------------------------------------------------------

    public static final class UpgradeBuilder {
        private final AppStateMachineProvider oldProvider;
        private final AppStateMachineProvider newProvider;
        private Map<String, String> settings = Map.of();
        private String chainId = "conformance-chain";
        private String changeName;
        private long activationHeight;
        private int blocks = 40;
        private int messagesPerBlock = 5;
        private long seed = 42;
        private BodyGenerator bodyGenerator =
                (height, index, random) -> ("msg-" + height + "-" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        private int runs = 2;

        private UpgradeBuilder(AppStateMachineProvider oldProvider, AppStateMachineProvider newProvider) {
            this.oldProvider = Objects.requireNonNull(oldProvider, "oldProvider");
            this.newProvider = Objects.requireNonNull(newProvider, "newProvider");
        }

        public UpgradeBuilder settings(Map<String, String> value) { this.settings = Map.copyOf(value); return this; }
        public UpgradeBuilder chainId(String value) { this.chainId = value; return this; }
        /** The change under test: its name and the height it activates at. */
        public UpgradeBuilder activationAt(String name, long height) {
            this.changeName = name;
            this.activationHeight = height;
            return this;
        }
        public UpgradeBuilder blocks(int value) { this.blocks = value; return this; }
        public UpgradeBuilder messagesPerBlock(int value) { this.messagesPerBlock = value; return this; }
        public UpgradeBuilder seed(long value) { this.seed = value; return this; }
        public UpgradeBuilder bodyGenerator(BodyGenerator value) { this.bodyGenerator = value; return this; }
        /** Post-activation determinism replays of the NEW version (default 2, plus a restart run). */
        public UpgradeBuilder runs(int value) { this.runs = Math.max(2, value); return this; }

        /**
         * ADR-010.1 §3 semantics: (1) baseline with the old version, no
         * activation configured; (2) N runs + one kill-and-reopen run of the
         * new version WITH the activation — heights below the activation must
         * match the baseline byte-for-byte (roots AND effect lists), heights
         * at/after it must be deterministic across the new version's runs.
         */
        public void assertReplayStable() {
            if (changeName == null || activationHeight <= 0) {
                throw new IllegalStateException("activationAt(name, height) is required");
            }
            if (activationHeight > blocks) {
                throw new IllegalStateException("activation height " + activationHeight
                        + " is beyond the corpus (" + blocks + " blocks) — nothing would be tested");
            }
            List<AppBlock> corpus = buildCorpus(chainId, blocks, messagesPerBlock, seed, bodyGenerator);
            Map<String, String> upgraded = new LinkedHashMap<>(settings);
            upgraded.put("machines." + newProvider.id() + ".activations." + changeName,
                    String.valueOf(activationHeight));
            try {
                Path workDir = Files.createTempDirectory("appchain-upgrade-conformance");
                Map<Long, HeightOutcome> baseline = applyCorpus(oldProvider, settings, chainId,
                        corpus, workDir.resolve("baseline"), -1);
                List<Map<Long, HeightOutcome>> newRuns = new ArrayList<>();
                for (int run = 0; run < runs; run++) {
                    long restartAt = run == runs - 1
                            ? Math.max(1, activationHeight - 1) // reopen across the boundary
                            : -1;
                    newRuns.add(applyCorpus(newProvider, upgraded, chainId, corpus,
                            workDir.resolve("upgraded-" + run), restartAt));
                }
                // Replay stability below the activation: new version vs OLD baseline
                List<Map<Long, HeightOutcome>> vsBaseline = new ArrayList<>();
                vsBaseline.add(baseline);
                vsBaseline.addAll(newRuns);
                Divergence replay = compare(baseline, vsBaseline, 1, activationHeight - 1, 1);
                if (replay != null) {
                    throw new AssertionError("Upgrade is NOT replay-stable below activation height "
                            + activationHeight + " (" + replay.kind() + " at height " + replay.height()
                            + "): old version produced " + replay.baseline() + ", new version produced "
                            + replay.divergent() + ". Gate the change on ActivationSchedule (ADR 010.1).");
                }
                // Determinism of the new branch at/after the activation
                Divergence post = compare(newRuns.get(0), newRuns, 1, blocks, activationHeight);
                if (post != null) {
                    throw new AssertionError("New version is NOT deterministic at/after its activation ("
                            + post.kind() + " at height " + post.height() + "): " + post.baseline()
                            + " vs " + post.divergent());
                }
            } catch (AssertionError e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("Upgrade conformance harness failed", e);
            }
        }
    }
}
