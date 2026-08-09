package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochBoundary;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochState;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;
import com.bloxbean.cardano.yano.api.appchain.l1view.ProtocolParamsView;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochStakeContract;
import com.bloxbean.cardano.yano.runtime.appchain.StateMachineConformance;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EpochStakeStateMachineTest {
    private static final int CHUNK_ENTRIES = 2;

    @Test
    void observerProducesByteIdenticalBoundedTwoPassStream() {
        List<EpochStakeContract.Entry> entries = entries(3, 0);
        EpochStakeObserver observer = new EpochStakeObserver("stake-source", CHUNK_ENTRIES);
        L1EpochBoundary boundary = new L1EpochBoundary(41, 42, 1_000, new byte[32], 100);

        var manifest = observer.prepare(boundary, new Source(entries));
        List<byte[]> first = new ArrayList<>();
        List<byte[]> second = new ArrayList<>();
        observer.writeObservations(manifest, new Source(entries), (index, claim) -> first.add(claim));
        observer.writeObservations(manifest, new Source(entries), (index, claim) -> second.add(claim));

        assertThat(manifest.datasetEpoch()).isEqualTo(41);
        assertThat(manifest.totalEntries()).isEqualTo(3);
        assertThat(manifest.chunkCount()).isEqualTo(2);
        assertThat(first).usingElementComparator(Arrays::compare).containsExactlyElementsOf(second);
        assertThat(EpochStakeContract.decodeManifest(first.getFirst()).epoch()).isEqualTo(41);
        assertThat(EpochStakeContract.decodeChunk(first.get(1)).entries()).hasSize(2);
        assertThat(EpochStakeContract.decodeChunk(first.get(2)).entries()).hasSize(1);
    }

    @Test
    void observerRejectsNonCanonicalHostIteration() {
        EpochStakeObserver observer = new EpochStakeObserver("stake-source", CHUNK_ENTRIES);
        L1EpochBoundary boundary = new L1EpochBoundary(41, 42, 1_000, new byte[32], 100);

        assertThatThrownBy(() -> observer.prepare(boundary,
                new Source(List.of(entry(2, 0), entry(1, 0)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("canonical order");
    }

    @Test
    void incompleteEpochIsHiddenThenCompletesAndDuplicateChunkIsNoOp() {
        Dataset dataset = dataset(41, entries(3, 0));
        EpochStakeStateMachine machine = new EpochStakeStateMachine("stake-source", CHUNK_ENTRIES);
        TestState state = new TestState();

        apply(machine, state, 1, claim(42, EpochStakeContract.encodeManifest(dataset.manifest)));
        apply(machine, state, 2, claim(42, EpochStakeContract.encodeChunk(dataset.chunks.get(0))));
        assertThat(query(machine, state, new EpochStakeContract.Query(41, 0, hash(0)))).isEmpty();

        byte[] finalClaim = claim(42, EpochStakeContract.encodeChunk(dataset.chunks.get(1)));
        apply(machine, state, 3, finalClaim);
        byte[] value = query(machine, state, new EpochStakeContract.Query(41, 0, hash(0)));
        assertThat(EpochStakeContract.decodeValue(value).coin()).isEqualTo(1_000_000);
        byte[] before = state.get(EpochStakeContract.metaKey(41)).orElseThrow();
        apply(machine, state, 4, finalClaim);
        assertThat(state.get(EpochStakeContract.metaKey(41))).contains(before);
        assertThat(EpochStakeContract.decodeMeta(before).complete()).isTrue();
    }

    @Test
    void missingReorderedAndRootMismatchedChunksFailClosed() {
        Dataset dataset = dataset(41, entries(3, 0));
        EpochStakeStateMachine machine = new EpochStakeStateMachine("stake-source", CHUNK_ENTRIES);
        TestState missing = new TestState();
        apply(machine, missing, 1, claim(42, EpochStakeContract.encodeManifest(dataset.manifest)));
        assertThatThrownBy(() -> apply(machine, missing, 2,
                claim(42, EpochStakeContract.encodeChunk(dataset.chunks.get(1)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("consecutively");

        TestState wrongRoot = new TestState();
        apply(machine, wrongRoot, 1, claim(42, EpochStakeContract.encodeManifest(dataset.manifest)));
        EpochStakeContract.Chunk fabricated = new EpochStakeContract.Chunk(
                41, filled(9), 0, dataset.chunks.getFirst().entries());
        assertThatThrownBy(() -> apply(machine, wrongRoot, 2,
                claim(42, EpochStakeContract.encodeChunk(fabricated))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("manifest");

        List<EpochStakeContract.Entry> reverse = List.of(
                entry(0, 0), entry(2, 0), entry(1, 0));
        Dataset reordered = dataset(41, reverse);
        TestState order = new TestState();
        apply(machine, order, 1, claim(42, EpochStakeContract.encodeManifest(reordered.manifest)));
        apply(machine, order, 2, claim(42, EpochStakeContract.encodeChunk(reordered.chunks.get(0))));
        assertThatThrownBy(() -> apply(machine, order, 3,
                claim(42, EpochStakeContract.encodeChunk(reordered.chunks.get(1)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("globally canonical");
    }

    @Test
    void stateMachineConformanceCoversMembersRestartAndSnapshotReplay() {
        StateMachineConformance.Result result = StateMachineConformance.builder(
                        new StdlibStateMachineProviders.EpochStakeProvider())
                .settings(Map.of("machines.epoch-stake.observer-id", "stake-source",
                        "machines.epoch-stake.chunk-entries", "2"))
                .blocks(12).messagesPerBlock(1).runs(3)
                .restartAtHeight(5).snapshotAtHeight(8)
                .messageGenerator((height, index, random) -> corpus(height))
                .stateProbe("epoch-3-meta", EpochStakeContract.metaKey(3))
                .stateProbe("epoch-3-first", EpochStakeContract.entryKey(3, 0, hash(30)))
                .run();

        assertThat(result.deterministic()).isTrue();
        assertThat(result.outcomesPerRun().getFirst().get(12L).stateValues()
                .get("epoch-3-meta")).isNotBlank();
    }

    private static StateMachineConformance.CorpusMessage corpus(long height) {
        long epoch = (height - 1) / 3;
        int position = (int) ((height - 1) % 3);
        Dataset dataset = dataset(epoch, entries(3, Math.toIntExact(epoch * 10)));
        byte[] encoded = position == 0
                ? EpochStakeContract.encodeManifest(dataset.manifest)
                : EpochStakeContract.encodeChunk(dataset.chunks.get(position - 1));
        L1Observation observation = L1Observation.epoch(
                "stake-source", epoch + 1, 0, new byte[32], encoded);
        return new StateMachineConformance.CorpusMessage(observation.topic(), observation.encode());
    }

    private static Dataset dataset(long epoch, List<EpochStakeContract.Entry> entries) {
        List<List<EpochStakeContract.Entry>> parts = new ArrayList<>();
        for (int offset = 0; offset < entries.size(); offset += CHUNK_ENTRIES) {
            parts.add(List.copyOf(entries.subList(offset, Math.min(entries.size(), offset + CHUNK_ENTRIES))));
        }
        List<byte[]> hashes = parts.stream().map(EpochStakeContract::chunkHash).toList();
        byte[] root = EpochStakeContract.snapshotRoot(hashes);
        EpochStakeContract.Manifest manifest = new EpochStakeContract.Manifest(
                epoch, entries.size(), CHUNK_ENTRIES, parts.size(), root);
        List<EpochStakeContract.Chunk> chunks = new ArrayList<>();
        for (int index = 0; index < parts.size(); index++) {
            chunks.add(new EpochStakeContract.Chunk(epoch, root, index, parts.get(index)));
        }
        return new Dataset(manifest, chunks);
    }

    private static byte[] query(EpochStakeStateMachine machine, TestState state,
                                EpochStakeContract.Query query) {
        return machine.query(EpochStakeContract.QUERY_PATH,
                EpochStakeContract.encodeQuery(query), state);
    }

    private static void apply(EpochStakeStateMachine machine, TestState state,
                              long height, byte[] observationBytes) {
        AppMessage message = AppMessage.builder().version(1).messageId(id(height))
                .chainId("history").topic("~l1/stake-source").sender(new byte[0])
                .body(observationBytes).authProof(new byte[0]).build();
        AppBlock block = new AppBlock(AppBlock.BLOCK_VERSION, "history", height,
                new byte[32], 0, new byte[0], 10, new byte[32], new byte[32],
                List.of(message), new byte[32], FinalityCert.empty());
        machine.apply(AppBlockExecutionContext.fromValidatedBlock(block), state,
                AppEffectEmitter.rejecting("effects are not expected"));
        state.height = height;
    }

    private static byte[] claim(long newEpoch, byte[] claim) {
        return L1Observation.epoch("stake-source", newEpoch, 0, new byte[32], claim).encode();
    }

    private static List<EpochStakeContract.Entry> entries(int count, int start) {
        List<EpochStakeContract.Entry> result = new ArrayList<>();
        for (int index = 0; index < count; index++) result.add(entry(start + index, 0));
        return result;
    }

    private static EpochStakeContract.Entry entry(int suffix, int type) {
        return new EpochStakeContract.Entry(type, hash(suffix),
                BigInteger.valueOf(1_000_000L + suffix), hash(suffix % 10));
    }

    private static byte[] hash(int suffix) {
        byte[] hash = new byte[28];
        ByteBuffer.wrap(hash, 24, 4).putInt(suffix);
        return hash;
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static byte[] id(long value) {
        byte[] id = new byte[32];
        ByteBuffer.wrap(id, 24, 8).putLong(value);
        return id;
    }

    private record Dataset(EpochStakeContract.Manifest manifest,
                           List<EpochStakeContract.Chunk> chunks) { }

    private static final class Source implements L1EpochState {
        private final List<EpochStakeContract.Entry> entries;
        private Source(List<EpochStakeContract.Entry> entries) { this.entries = entries; }
        @Override public long previousEpoch() { return 41; }
        @Override public long newEpoch() { return 42; }
        @Override public ProtocolParamsView protocolParams(long epoch) { throw new UnsupportedOperationException(); }
        @Override public boolean hasStakeSnapshot(long epoch) { return epoch == 41; }
        @Override public void forEachStakeEntry(long epoch, StakeEntryConsumer consumer) {
            entries.forEach(value -> consumer.accept(value.credType(), value.credHash(),
                    value.coin(), value.poolHash()));
        }
        @Override public boolean hasProposalStatusSnapshot(long epoch) { return false; }
        @Override public boolean hasDRepDistributionSnapshot(long epoch) { return false; }
        @Override public void forEachProposalStatus(long epoch, ProposalStatusConsumer consumer) {
            throw new UnsupportedOperationException();
        }
        @Override public void forEachDRepDistributionEntry(long epoch, DRepDistributionConsumer consumer) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class TestState implements AppStateWriter, AppQueryContext {
        private final Map<Key, byte[]> values = new LinkedHashMap<>();
        private long height;
        @Override public Optional<byte[]> get(byte[] key) {
            byte[] value = values.get(new Key(key));
            return value == null ? Optional.empty() : Optional.of(value.clone());
        }
        @Override public void put(byte[] key, byte[] value) { values.put(new Key(key), value.clone()); }
        @Override public void delete(byte[] key) { values.remove(new Key(key)); }
        @Override public byte[] stateRoot() { return new byte[32]; }
        @Override public long committedHeight() { return height; }
    }

    private record Key(byte[] bytes) {
        private Key { bytes = bytes.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof Key that && Arrays.equals(bytes, that.bytes);
        }
        @Override public int hashCode() { return Arrays.hashCode(bytes); }
    }
}
