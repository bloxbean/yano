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
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import org.rocksdb.WriteBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * Determinism conformance harness for {@link AppStateMachine} implementations
 * (ADR app-layer/008.1 I1.6). The framework re-executes every block on every
 * member and rejects state-root mismatches — a nondeterministic state machine
 * therefore STALLS its chain in production. This harness catches that before
 * deployment: it applies one identical block corpus through the real ledger
 * commit path (same MPF batching as production) in N independent runs — plus
 * a kill-and-reopen replay run — and asserts byte-identical state roots at
 * every height.
 *
 * <pre>
 * StateMachineConformance.builder(new MyProvider())
 *         .settings(Map.of("machines.my-machine.key", "value"))
 *         .blocks(50).messagesPerBlock(5).seed(42)
 *         .bodyGenerator((height, index, random) -> myRealisticCommand(random))
 *         .assertDeterministic();
 * </pre>
 *
 * Forbidden inside {@code apply()} (see the {@link AppStateMachine} contract):
 * wall-clock time, randomness, network/file I/O, environment reads, iteration
 * over unordered collections, and locale/charset-dependent or
 * library-default serialization.
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

    /** Per-run, per-height state roots and the first divergence if any. */
    public record Result(List<Map<Long, String>> rootsPerRun, Divergence divergence) {

        public boolean deterministic() {
            return divergence == null;
        }

        public String describeDivergence() {
            if (divergence == null) {
                return "deterministic";
            }
            return "State machine is NOT deterministic: at height " + divergence.height()
                    + " run 0 produced root " + divergence.baselineRoot()
                    + " but run " + divergence.run() + " produced " + divergence.divergentRoot()
                    + ". Check the apply() implementation for wall-clock time, randomness, I/O,"
                    + " or unordered iteration.";
        }
    }

    public record Divergence(long height, int run, String baselineRoot, String divergentRoot) {
    }

    // ------------------------------------------------------------------

    private Result execute() {
        List<AppBlock> corpus = buildCorpus();
        List<Map<Long, String>> rootsPerRun = new ArrayList<>();
        try {
            Path workDir = Files.createTempDirectory("appchain-conformance");
            for (int run = 0; run < runs; run++) {
                // The last run replays across a close/reopen at restartAtHeight
                long restartAt = run == runs - 1
                        ? (restartAtHeight > 0 ? restartAtHeight : Math.max(1, blocks / 2))
                        : -1;
                rootsPerRun.add(applyCorpus(corpus, workDir.resolve("run-" + run), restartAt));
            }
        } catch (Exception e) {
            throw new RuntimeException("Conformance harness failed", e);
        }

        Map<Long, String> baseline = rootsPerRun.get(0);
        for (int run = 1; run < rootsPerRun.size(); run++) {
            for (long height = 1; height <= blocks; height++) {
                String expected = baseline.get(height);
                String actual = rootsPerRun.get(run).get(height);
                if (!expected.equals(actual)) {
                    return new Result(rootsPerRun, new Divergence(height, run, expected, actual));
                }
            }
        }
        return new Result(rootsPerRun, null);
    }

    /** Identical envelopes/blocks for every run: all inputs derive from the seed. */
    private List<AppBlock> buildCorpus() {
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

    /** Apply the corpus through the real ledger commit path; returns root per height. */
    private Map<Long, String> applyCorpus(List<AppBlock> corpus, Path dir, long restartAt)
            throws Exception {
        Map<Long, String> roots = new LinkedHashMap<>();
        AppStateMachine machine = provider.create(new AppStateMachineContext() {
            @Override public String chainId() { return chainId; }
            @Override public Map<String, String> settings() { return settings; }
        });
        AppLedgerStore store = new AppLedgerStore(dir.toString(), log);
        try {
            machine.init(readerFor(store), new AppChainInfo(chainId, "00".repeat(32), 1));
            for (AppBlock block : corpus) {
                if (restartAt > 0 && block.height() == restartAt + 1) {
                    // Kill-and-reopen replay: crash recovery must not change roots
                    store.close();
                    store = new AppLedgerStore(dir.toString(), log);
                    machine = provider.create(new AppStateMachineContext() {
                        @Override public String chainId() { return chainId; }
                        @Override public Map<String, String> settings() { return settings; }
                    });
                    machine.init(readerFor(store), new AppChainInfo(chainId, "00".repeat(32), 1));
                }
                roots.put(block.height(), HexUtil.encodeHexString(applyAndCommit(store, machine, block)));
            }
        } finally {
            store.close();
        }
        return roots;
    }

    /** Mirrors AppChainEngine.applyBlock + commitBlock (same MPF batch staging). */
    private byte[] applyAndCommit(AppLedgerStore store, AppStateMachine machine, AppBlock block) {
        WriteBatch batch = new WriteBatch();
        byte[] committedRoot = store.stateRoot();
        try {
            AppStateMachine finalMachine = machine;
            byte[] newRoot = store.mpfNodeStore().withBatch(batch, () -> {
                MpfTrie trie = committedRoot != null
                        ? new MpfTrie(store.mpfNodeStore(), committedRoot)
                        : new MpfTrie(store.mpfNodeStore());
                finalMachine.apply(block, writerFor(trie));
                return trie.getRootHash();
            });
            byte[] effectiveRoot = newRoot != null ? newRoot : new byte[32];
            AppBlock applied = new AppBlock(block.version(), block.chainId(), block.height(),
                    block.prevHash(), block.l1Slot(), block.l1BlockHash(), block.timestamp(),
                    block.messagesRoot(), effectiveRoot, block.messages(), block.proposer(),
                    block.cert());
            store.commitBlock(applied, AppBlockCodec.blockHash(applied), effectiveRoot, batch);
            return effectiveRoot;
        } catch (RuntimeException e) {
            batch.close();
            throw e;
        } finally {
            batch.close();
        }
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

    private static AppStateWriter writerFor(MpfTrie trie) {
        return new AppStateWriter() {
            @Override public void put(byte[] key, byte[] value) { trie.put(key, value); }
            @Override public void delete(byte[] key) { trie.delete(key); }
            @Override public Optional<byte[]> get(byte[] key) { return Optional.ofNullable(trie.get(key)); }
            @Override public byte[] stateRoot() {
                byte[] root = trie.getRootHash();
                return root != null ? root : new byte[32];
            }
        };
    }
}
