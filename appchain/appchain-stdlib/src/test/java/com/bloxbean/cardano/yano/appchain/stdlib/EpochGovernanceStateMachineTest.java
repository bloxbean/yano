package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.*;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.l1view.*;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochGovernanceContract;
import com.bloxbean.cardano.yano.runtime.appchain.StateMachineConformance;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EpochGovernanceStateMachineTest {
    private static final int CHUNK_ENTRIES = 2;

    @Test
    void proposalOnlyConfigurationNeverTouchesDRepState() {
        Source source = source(42);
        source.failOnDRepAccess = true;
        EpochGovernanceObserver observer = new EpochGovernanceObserver("governance-source", true, false, CHUNK_ENTRIES);
        var boundary = boundary(42);
        var manifest = observer.prepare(boundary, source);
        List<byte[]> claims = new ArrayList<>();
        observer.writeObservations(manifest, source, (index, claim) -> claims.add(claim));

        assertThat(source.drepAccesses).isZero();
        assertThat(manifest.chunkCount()).isEqualTo(2);
        var header = EpochGovernanceContract.decodeHeader(claims.getFirst());
        assertThat(header.includeProposals()).isTrue();
        assertThat(header.includeDReps()).isFalse();
        assertThat(claims).hasSize(3);
    }

    @Test
    void proposalAndDRepCompletenessAreIndependentAndQueriesFailClosed() {
        Dataset dataset = dataset(42);
        var machine = new EpochGovernanceStateMachine("governance-source", true, true, CHUNK_ENTRIES);
        TestState state = new TestState();
        apply(machine, state, 1, dataset.claims.get(0));
        apply(machine, state, 2, dataset.claims.get(1));
        assertThat(proposalQuery(machine, state, 42, 0)).isEmpty();
        apply(machine, state, 3, dataset.claims.get(2));
        assertThat(proposalQuery(machine, state, 42, 0)).isNotEmpty();
        assertThat(drepQuery(machine, state, 42, 0)).isEmpty();
        apply(machine, state, 4, dataset.claims.get(3));
        assertThat(drepQuery(machine, state, 42, 0)).isEmpty();
        apply(machine, state, 5, dataset.claims.get(4));
        assertThat(drepQuery(machine, state, 42, 0)).isNotEmpty();
        assertThat(EpochGovernanceContract.decodeProposalMeta(state.get(
                EpochGovernanceContract.proposalMetaKey(42)).orElseThrow()).complete()).isTrue();
        assertThat(EpochGovernanceContract.decodeDRepMeta(state.get(
                EpochGovernanceContract.drepMetaKey(42)).orElseThrow()).complete()).isTrue();
    }

    @Test
    void reorderedMissingAndFabricatedClaimsAreRejected() {
        Dataset dataset = dataset(42);
        var machine = new EpochGovernanceStateMachine("governance-source", true, true, CHUNK_ENTRIES);
        TestState reordered = new TestState();
        apply(machine, reordered, 1, dataset.claims.get(0));
        apply(machine, reordered, 2, dataset.claims.get(2));
        assertThatThrownBy(() -> apply(machine, reordered, 3, dataset.claims.get(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("canonical");

        TestState missing = new TestState();
        apply(machine, missing, 1, dataset.claims.get(0));
        assertThatThrownBy(() -> apply(machine, missing, 2, dataset.claims.get(4)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("consecutive");

        TestState fabricated = new TestState();
        apply(machine, fabricated, 1, dataset.claims.get(0));
        apply(machine, fabricated, 2, dataset.claims.get(1));
        var second = EpochGovernanceContract.decodeProposal(
                L1Observation.decode(dataset.claims.get(2)).claim());
        var changed = new EpochGovernanceContract.Proposal(second.epoch(), second.transactionId(),
                second.governanceActionIndex(), second.actionType(), EpochGovernanceContract.ProposalStatus.DROPPED,
                EpochGovernanceContract.ProposalReason.REMOVED, second.proposedEpoch(), second.expiresAfterEpoch());
        assertThatThrownBy(() -> apply(machine, fabricated, 3, observation(42,
                EpochGovernanceContract.encodeProposal(changed))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("root mismatch");
    }

    @Test
    void stateMachineConformanceCoversCompositionRestartAndReplay() {
        StateMachineConformance.Result result = StateMachineConformance.builder(
                        new StdlibStateMachineProviders.EpochGovernanceProvider())
                .settings(Map.of("machines.epoch-governance.observer-id", "governance-source",
                        "machines.epoch-governance.include-proposals", "true",
                        "machines.epoch-governance.include-drep-distribution", "true",
                        "machines.epoch-governance.drep-chunk-entries", "2"))
                .blocks(10).messagesPerBlock(1).runs(3).restartAtHeight(4).snapshotAtHeight(7)
                .messageGenerator((height, ignored, random) -> {
                    long epoch = 42 + (height - 1) / 5;
                    int position = (int) ((height - 1) % 5);
                    byte[] encoded = dataset(epoch).claims.get(position);
                    L1Observation observation = L1Observation.decode(encoded);
                    return new StateMachineConformance.CorpusMessage(observation.topic(), encoded);
                })
                .stateProbe("proposal-meta", EpochGovernanceContract.proposalMetaKey(43))
                .stateProbe("drep-meta", EpochGovernanceContract.drepMetaKey(43)).run();
        assertThat(result.deterministic()).isTrue();
    }

    private static Dataset dataset(long epoch) {
        Source source = source(epoch);
        var observer = new EpochGovernanceObserver("governance-source", true, true, CHUNK_ENTRIES);
        var manifest = observer.prepare(boundary(epoch), source);
        List<byte[]> claims = new ArrayList<>();
        observer.writeObservations(manifest, source, (index, claim) ->
                claims.add(observation(epoch, claim)));
        return new Dataset(claims);
    }

    private static Source source(long epoch) {
        byte[] tx0 = hash32(0); byte[] tx1 = hash32(1);
        return new Source(epoch, List.of(
                new EpochGovernanceContract.Proposal(epoch, tx0, 0,
                        EpochGovernanceContract.ActionType.PARAMETER_CHANGE,
                        EpochGovernanceContract.ProposalStatus.RATIFIED,
                        EpochGovernanceContract.ProposalReason.RATIFIED, epoch - 1, epoch + 4),
                new EpochGovernanceContract.Proposal(epoch, tx1, 1,
                        EpochGovernanceContract.ActionType.INFO_ACTION,
                        EpochGovernanceContract.ProposalStatus.ACTIVE,
                        EpochGovernanceContract.ProposalReason.NONE, epoch - 1, epoch + 5)),
                List.of(drep(0), drep(1), drep(2)));
    }
    private static EpochGovernanceContract.DRepEntry drep(int suffix) {
        return new EpochGovernanceContract.DRepEntry(0, hash28(suffix), BigInteger.valueOf(1_000_000 + suffix));
    }
    private static L1EpochBoundary boundary(long epoch) { return new L1EpochBoundary(epoch - 1, epoch,
            1_000 + epoch, new byte[32], 100 + epoch); }
    private static byte[] observation(long newEpoch, byte[] claim) { return L1Observation.epoch(
            "governance-source", newEpoch, 0, new byte[32], claim).encode(); }
    private static byte[] proposalQuery(EpochGovernanceStateMachine m, TestState s, long epoch, int suffix) {
        return m.query(EpochGovernanceContract.PROPOSAL_QUERY_PATH,
                EpochGovernanceContract.encodeProposalQuery(new EpochGovernanceContract.ProposalQuery(
                        epoch, hash32(suffix), suffix)), s);
    }
    private static byte[] drepQuery(EpochGovernanceStateMachine m, TestState s, long epoch, int suffix) {
        return m.query(EpochGovernanceContract.DREP_QUERY_PATH,
                EpochGovernanceContract.encodeDRepQuery(new EpochGovernanceContract.DRepQuery(
                        epoch, 0, hash28(suffix))), s);
    }
    private static void apply(EpochGovernanceStateMachine machine, TestState state, long height, byte[] observation) {
        AppMessage message = AppMessage.builder().version(1).messageId(hash32(Math.toIntExact(height)))
                .chainId("history").topic("~l1/governance-source").sender(new byte[0])
                .body(observation).authProof(new byte[0]).build();
        AppBlock block = new AppBlock(AppBlock.BLOCK_VERSION, "history", height,
                new byte[32], 0, new byte[0], 10, new byte[32], new byte[32],
                List.of(message), new byte[32], FinalityCert.empty());
        machine.apply(AppBlockExecutionContext.fromValidatedBlock(block), state,
                AppEffectEmitter.rejecting("effects are not expected")); state.height = height;
    }
    private static byte[] hash32(int suffix) { byte[] b = new byte[32]; ByteBuffer.wrap(b, 28, 4).putInt(suffix); return b; }
    private static byte[] hash28(int suffix) { byte[] b = new byte[28]; ByteBuffer.wrap(b, 24, 4).putInt(suffix); return b; }
    private record Dataset(List<byte[]> claims) { }

    private static final class Source implements L1EpochState {
        private final long epoch; private final List<EpochGovernanceContract.Proposal> proposals;
        private final List<EpochGovernanceContract.DRepEntry> dreps; private int drepAccesses;
        private boolean failOnDRepAccess;
        private Source(long epoch, List<EpochGovernanceContract.Proposal> proposals,
                       List<EpochGovernanceContract.DRepEntry> dreps) { this.epoch = epoch;
            this.proposals = proposals; this.dreps = dreps; }
        @Override public long previousEpoch() { return epoch - 1; }
        @Override public long newEpoch() { return epoch; }
        @Override public ProtocolParamsView protocolParams(long e) { throw new UnsupportedOperationException(); }
        @Override public boolean hasStakeSnapshot(long e) { return false; }
        @Override public void forEachStakeEntry(long e, StakeEntryConsumer c) { throw new UnsupportedOperationException(); }
        @Override public boolean hasProposalStatusSnapshot(long e) { return e == epoch; }
        @Override public boolean hasDRepDistributionSnapshot(long e) { touchDReps(); return e == epoch; }
        @Override public void forEachProposalStatus(long e, ProposalStatusConsumer c) { proposals.forEach(p -> c.accept(
                p.transactionId(), p.governanceActionIndex(), GovernanceActionType.valueOf(p.actionType().name()),
                GovernanceProposalStatus.valueOf(p.status().name()), GovernanceProposalStatusReason.valueOf(p.reason().name()),
                p.proposedEpoch(), p.expiresAfterEpoch())); }
        @Override public void forEachDRepDistributionEntry(long e, DRepDistributionConsumer c) { touchDReps();
            dreps.forEach(d -> c.accept(d.drepType(), d.drepHash(), d.coin())); }
        private void touchDReps() { drepAccesses++; if (failOnDRepAccess) throw new AssertionError("DRep traversal forbidden"); }
    }
    private static final class TestState implements AppStateWriter, AppQueryContext {
        private final Map<Key, byte[]> values = new LinkedHashMap<>(); private long height;
        @Override public Optional<byte[]> get(byte[] key) { byte[] v = values.get(new Key(key));
            return v == null ? Optional.empty() : Optional.of(v.clone()); }
        @Override public void put(byte[] key, byte[] value) { values.put(new Key(key), value.clone()); }
        @Override public void delete(byte[] key) { values.remove(new Key(key)); }
        @Override public byte[] stateRoot() { return new byte[32]; }
        @Override public long committedHeight() { return height; }
    }
    private record Key(byte[] bytes) { private Key { bytes = bytes.clone(); }
        @Override public boolean equals(Object o) { return o instanceof Key k && Arrays.equals(bytes, k.bytes); }
        @Override public int hashCode() { return Arrays.hashCode(bytes); } }
}
