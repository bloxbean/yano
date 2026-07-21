package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AuthScheme;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.AppChainConsensusProfile;
import com.bloxbean.cardano.yano.api.appchain.AppChainInfo;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachineProvider;
import com.bloxbean.cardano.yano.api.appchain.AppStateReader;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

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
    private final MessageGenerator messageGenerator;
    private final int runs;
    private final long restartAtHeight;
    private final long snapshotAtHeight;
    private final List<StateProbe> stateProbes;

    /** Deterministic command generator: same (height, index, seeded random) → same body. */
    @FunctionalInterface
    public interface BodyGenerator {
        byte[] body(long height, int index, Random random);
    }

    /**
     * Deterministic corpus-message generator. Unlike {@link BodyGenerator},
     * this can select a reserved framework topic such as {@code ~fx/result},
     * allowing upgrade tests to exercise result incorporation and
     * {@link AppStateMachine#onEffectResult} through the real kernel path.
     */
    @FunctionalInterface
    public interface MessageGenerator {
        CorpusMessage message(long height, int index, Random random);
    }

    /** Topic and body used to construct one deterministic corpus envelope. */
    public record CorpusMessage(String topic, byte[] body) {
        public CorpusMessage {
            if (topic == null || topic.isBlank()) {
                throw new IllegalArgumentException("topic must not be blank");
            }
            body = Objects.requireNonNull(body, "body").clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        /** Application command on the harness's conventional {@code t} topic. */
        public static CorpusMessage application(byte[] body) {
            return new CorpusMessage("t", body);
        }
    }

    /** One authenticated state leaf captured after every committed corpus height. */
    public record StateProbe(String name, byte[] key) {
        public StateProbe {
            if (name == null || name.isBlank() || name.length() > 128) {
                throw new IllegalArgumentException("state probe name must contain 1-128 characters");
            }
            key = Objects.requireNonNull(key, "key").clone();
            if (key.length == 0 || key.length > 1024) {
                throw new IllegalArgumentException("state probe key must contain 1-1024 bytes");
            }
        }
        @Override public byte[] key() { return key.clone(); }
    }

    private StateMachineConformance(Builder builder) {
        this.provider = builder.provider;
        this.settings = builder.settings;
        this.chainId = builder.chainId;
        this.blocks = builder.blocks;
        this.messagesPerBlock = builder.messagesPerBlock;
        this.seed = builder.seed;
        this.messageGenerator = builder.messageGenerator;
        this.runs = builder.runs;
        this.restartAtHeight = builder.restartAtHeight;
        this.snapshotAtHeight = builder.snapshotAtHeight;
        this.stateProbes = builder.stateProbes;
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
        private MessageGenerator messageGenerator = (height, index, random) ->
                CorpusMessage.application(("msg-" + height + "-" + index)
                        .getBytes(StandardCharsets.UTF_8));
        private int runs = 3;
        private long restartAtHeight = -1; // default: mid-corpus
        private long snapshotAtHeight = -1;
        private List<StateProbe> stateProbes = List.of();

        private Builder(AppStateMachineProvider provider) {
            this.provider = provider;
        }

        public Builder settings(Map<String, String> value) { this.settings = Map.copyOf(value); return this; }
        public Builder chainId(String value) { this.chainId = value; return this; }
        public Builder blocks(int value) { this.blocks = value; return this; }
        public Builder messagesPerBlock(int value) { this.messagesPerBlock = value; return this; }
        public Builder seed(long value) { this.seed = value; return this; }
        public Builder bodyGenerator(BodyGenerator value) {
            Objects.requireNonNull(value, "value");
            this.messageGenerator = (height, index, random) ->
                    CorpusMessage.application(value.body(height, index, random));
            return this;
        }
        /** Generate topics as well as bodies, including framework messages such as {@code ~fx/result}. */
        public Builder messageGenerator(MessageGenerator value) {
            this.messageGenerator = Objects.requireNonNull(value, "value");
            return this;
        }
        /** Independent full replays compared root-by-root (default 3). */
        public Builder runs(int value) { this.runs = Math.max(2, value); return this; }
        /** Close and reopen the ledger at this height in the replay run (default: middle). */
        public Builder restartAtHeight(long value) { this.restartAtHeight = value; return this; }
        /**
         * Add an independent replay that checkpoints at this height, opens the
         * checkpoint as a fresh ledger, recreates the machine, and continues.
         */
        public Builder snapshotAtHeight(long value) { this.snapshotAtHeight = value; return this; }
        /** Capture one exact state leaf after every commit and compare it across all runs. */
        public Builder stateProbe(String name, byte[] key) {
            List<StateProbe> updated = new ArrayList<>(stateProbes);
            updated.add(new StateProbe(name, key));
            stateProbes = List.copyOf(updated);
            return this;
        }

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
    public record HeightOutcome(
            String root,
            List<String> effectHashes,
            Map<String, String> stateValues
    ) {
    }

    public record Divergence(String kind, long height, int run, String baseline, String divergent) {
    }

    // ------------------------------------------------------------------

    private Result execute() {
        List<AppBlock> corpus = buildCorpus(chainId, blocks, messagesPerBlock, seed, messageGenerator);
        List<Map<Long, HeightOutcome>> outcomesPerRun = new ArrayList<>();
        try {
            Path workDir = Files.createTempDirectory("appchain-conformance");
            for (int run = 0; run < runs; run++) {
                // The last run replays across a close/reopen at restartAtHeight
                long restartAt = run == runs - 1
                        ? (restartAtHeight > 0 ? restartAtHeight : Math.max(1, blocks / 2))
                        : -1;
                outcomesPerRun.add(applyCorpus(provider, settings, chainId, corpus,
                        workDir.resolve("run-" + run), restartAt, -1, stateProbes,
                        messagesPerBlock));
            }
            if (snapshotAtHeight > 0) {
                outcomesPerRun.add(applyCorpus(provider, settings, chainId, corpus,
                        workDir.resolve("snapshot-run"), -1, snapshotAtHeight, stateProbes,
                        messagesPerBlock));
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
                    // A hole is itself a failure (a run that never committed
                    // this height) — never silently pass it
                    return new Divergence("missing-outcome", height, run,
                            expected != null ? expected.root() : "<absent>",
                            actual != null ? actual.root() : "<absent>");
                }
                if (!expected.root().equals(actual.root())) {
                    return new Divergence("state-root", height, run, expected.root(), actual.root());
                }
                if (!expected.effectHashes().equals(actual.effectHashes())) {
                    return new Divergence("effect-emission", height, run,
                            String.valueOf(expected.effectHashes()), String.valueOf(actual.effectHashes()));
                }
                if (!expected.stateValues().equals(actual.stateValues())) {
                    return new Divergence("state-probe", height, run,
                            String.valueOf(expected.stateValues()),
                            String.valueOf(actual.stateValues()));
                }
            }
        }
        return null;
    }

    /** Identical envelopes/blocks for every run: all inputs derive from the seed. */
    private static List<AppBlock> buildCorpus(String chainId, int blocks, int messagesPerBlock,
                                              long seed, MessageGenerator messageGenerator) {
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
                CorpusMessage generated = Objects.requireNonNull(
                        messageGenerator.message(height, index, random),
                        "messageGenerator returned null at height " + height + ", index " + index);
                byte[] body = generated.body();
                long sequence = ++senderSeq;
                byte[] messageId = AppMessage.computeMessageId(chainId, generated.topic(), sender,
                        sequence, expiresAt, body);
                messages.add(AppMessage.builder()
                        .messageId(messageId)
                        .chainId(chainId)
                        .topic(generated.topic())
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
                                                        Path dir, long restartAt,
                                                        long snapshotAt,
                                                        List<StateProbe> stateProbes,
                                                        int maxBlockMessages) throws Exception {
        Map<Long, HeightOutcome> outcomes = new LinkedHashMap<>();
        AppChainConsensusProfile profile = profileFor(
                chainId, settings, Math.max(1, maxBlockMessages));
        AppLedgerStore store = new AppLedgerStore(dir.toString(), log);
        try {
            new ConsensusProfileGuard(profile).verifyRetained(store, chainId);
            AppStateMachine machine = provider.create(contextFor(chainId, settings, profile));
            machine.init(readerFor(store), new AppChainInfo(chainId, "00".repeat(32), 1));
            for (AppBlock block : corpus) {
                if (restartAt > 0 && block.height() == restartAt + 1) {
                    // Kill-and-reopen replay: crash recovery must not change roots
                    store.close();
                    store = new AppLedgerStore(dir.toString(), log);
                    new ConsensusProfileGuard(profile).verifyRetained(store, chainId);
                    machine = provider.create(contextFor(chainId, settings, profile));
                    machine.init(readerFor(store), new AppChainInfo(chainId, "00".repeat(32), 1));
                }
                if (snapshotAt > 0 && block.height() == snapshotAt + 1) {
                    Path checkpoint = dir.resolveSibling(dir.getFileName() + "-checkpoint");
                    store.createSnapshot(checkpoint.toString());
                    store.close();
                    store = new AppLedgerStore(checkpoint.toString(), log);
                    new ConsensusProfileGuard(profile).verifyRetained(store, chainId);
                    machine = provider.create(contextFor(chainId, settings, profile));
                    machine.init(readerFor(store), new AppChainInfo(chainId, "00".repeat(32), 1));
                }
                outcomes.put(block.height(), applyAndCommit(
                        store, machine, settings, profile, block, stateProbes));
            }
        } finally {
            store.close();
        }
        return outcomes;
    }

    /** Same pipeline as production, via the shared applier (FxKernel + MPF batch staging). */
    private static HeightOutcome applyAndCommit(AppLedgerStore store, AppStateMachine machine,
                                                Map<String, String> settings,
                                                AppChainConsensusProfile profile,
                                                AppBlock block,
                                                List<StateProbe> stateProbes) {
        FxKernel kernel = new FxKernel(EffectsSettings.fromSettings(settings),
                new ConsensusProfileGuard(profile));
        FxBlockApplier.Applied applied = FxBlockApplier.applyAndCommit(store, kernel, machine, block);
        List<String> effectHashes = new ArrayList<>(applied.fx().emitted().size());
        for (FxKernel.StagedEffect staged : applied.fx().emitted()) {
            effectHashes.add(HexUtil.encodeHexString(staged.effectHash()));
        }
        Map<String, String> stateValues = new LinkedHashMap<>();
        for (StateProbe probe : stateProbes) {
            String value = store.stateGet(probe.key())
                    .map(HexUtil::encodeHexString).orElse("<absent>");
            if (stateValues.putIfAbsent(probe.name(), value) != null) {
                throw new IllegalArgumentException("duplicate state probe name: " + probe.name());
            }
        }
        return new HeightOutcome(HexUtil.encodeHexString(applied.block().stateRoot()),
                effectHashes, Map.copyOf(stateValues));
    }

    private static AppStateMachineContext contextFor(
            String chainId,
            Map<String, String> settings,
            AppChainConsensusProfile profile
    ) {
        return new AppStateMachineContext() {
            @Override public String chainId() { return chainId; }
            @Override public Map<String, String> settings() { return settings; }
            @Override public Optional<AppChainConsensusProfile> consensusProfile() {
                return Optional.of(profile);
            }
        };
    }

    private static AppChainConsensusProfile profileFor(
            String chainId,
            Map<String, String> settings,
            int maxBlockMessages
    ) {
        String member = "11".repeat(32);
        AppChainConfig config = AppChainConfig.builder(chainId)
                .signingKeyHex("22".repeat(32))
                .memberKeysHex(Set.of(member))
                .proposerKeyHex(member)
                .maxBlockMessages(maxBlockMessages)
                .pluginSettings(settings)
                .build();
        return EffectsSettings.from(config).consensusProfile(config);
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
        private MessageGenerator messageGenerator = (height, index, random) ->
                CorpusMessage.application(("msg-" + height + "-" + index)
                        .getBytes(StandardCharsets.UTF_8));
        private int runs = 2;
        private boolean expectPostActivationDifference;

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
        public UpgradeBuilder bodyGenerator(BodyGenerator value) {
            Objects.requireNonNull(value, "value");
            this.messageGenerator = (height, index, random) ->
                    CorpusMessage.application(value.body(height, index, random));
            return this;
        }
        /** Generate topics as well as bodies, including framework messages such as {@code ~fx/result}. */
        public UpgradeBuilder messageGenerator(MessageGenerator value) {
            this.messageGenerator = Objects.requireNonNull(value, "value");
            return this;
        }
        /** Full determinism replays of the NEW version (default 2, plus boundary restart runs). */
        public UpgradeBuilder runs(int value) { this.runs = Math.max(2, value); return this; }

        /**
         * Require this fixture to produce at least one state-root or ordered
         * effect-list difference from the old version at/after activation.
         * This is opt-in because a compatibility-only or deliberately dormant
         * upgrade can legitimately remain a no-op for a particular corpus.
         */
        public UpgradeBuilder expectPostActivationDifference() {
            this.expectPostActivationDifference = true;
            return this;
        }

        /**
         * ADR-010.1 §3 semantics: (1) baseline with the old version, no
         * activation configured; (2) N runs plus kill-and-reopen runs before
         * and after the activation boundary for the
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
            Set<String> activationKeys = new LinkedHashSet<>();
            activationKeys.add(activationKey(oldProvider.id()));
            activationKeys.add(activationKey(newProvider.id()));
            for (String key : activationKeys) {
                if (settings.containsKey(key)) {
                    throw new IllegalStateException("settings already contain activation key '" + key
                            + "'; remove it and let activationAt() define the upgrade boundary");
                }
            }
            List<AppBlock> corpus = buildCorpus(chainId, blocks, messagesPerBlock, seed, messageGenerator);
            Map<String, String> upgraded = new LinkedHashMap<>(settings);
            upgraded.put(activationKey(newProvider.id()), String.valueOf(activationHeight));
            try {
                Path workDir = Files.createTempDirectory("appchain-upgrade-conformance");
                Map<Long, HeightOutcome> baseline = applyCorpus(oldProvider, settings, chainId,
                        corpus, workDir.resolve("baseline"), -1, -1, List.of(),
                        messagesPerBlock);
                List<Map<Long, HeightOutcome>> newRuns = new ArrayList<>();
                for (int run = 0; run < runs; run++) {
                    newRuns.add(applyCorpus(newProvider, upgraded, chainId, corpus,
                            workDir.resolve("upgraded-" + run), -1, -1, List.of(),
                            messagesPerBlock));
                }
                // Exercise crash recovery on both sides of the boundary. A restart
                // point is the last committed height before reopen, so A-1 reopens
                // exactly at activation and A+1 reopens after new behavior is live.
                Set<Long> restartPoints = new LinkedHashSet<>();
                if (activationHeight - 1 >= 1 && activationHeight - 1 < blocks) {
                    restartPoints.add(activationHeight - 1);
                }
                if (activationHeight + 1 < blocks) {
                    restartPoints.add(activationHeight + 1);
                }
                int restartRun = 0;
                for (long restartAt : restartPoints) {
                    newRuns.add(applyCorpus(newProvider, upgraded, chainId, corpus,
                            workDir.resolve("upgraded-restart-" + restartRun++), restartAt, -1,
                            List.of(), messagesPerBlock));
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
                if (expectPostActivationDifference
                        && !differsAtOrAfter(baseline, newRuns.get(0), activationHeight, blocks)) {
                    throw new AssertionError("Upgrade fixture did not exercise an observable change "
                            + "at/after activation height " + activationHeight
                            + ": old and new state roots and effect lists are identical through height "
                            + blocks + ". Add corpus messages that trigger the activated branch, or "
                            + "remove expectPostActivationDifference() for an intentional no-op upgrade.");
                }
            } catch (AssertionError e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("Upgrade conformance harness failed", e);
            }
        }

        private String activationKey(String machineId) {
            return "machines." + machineId + ".activations." + changeName;
        }

        private static boolean differsAtOrAfter(Map<Long, HeightOutcome> oldOutcomes,
                                                Map<Long, HeightOutcome> newOutcomes,
                                                long fromHeight, long throughHeight) {
            for (long height = fromHeight; height <= throughHeight; height++) {
                if (!Objects.equals(oldOutcomes.get(height), newOutcomes.get(height))) {
                    return true;
                }
            }
            return false;
        }
    }
}
