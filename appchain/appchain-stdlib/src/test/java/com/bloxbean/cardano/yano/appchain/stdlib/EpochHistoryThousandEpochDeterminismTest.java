package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.yano.api.appchain.l1view.EpochObservationManifest;
import com.bloxbean.cardano.yano.api.appchain.l1view.GovernanceActionType;
import com.bloxbean.cardano.yano.api.appchain.l1view.GovernanceProposalStatus;
import com.bloxbean.cardano.yano.api.appchain.l1view.GovernanceProposalStatusReason;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochBoundary;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochObserver;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochState;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;
import com.bloxbean.cardano.yano.api.appchain.l1view.ProtocolParamsView;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EpochHistoryThousandEpochDeterminismTest {
    @Test
    void independentlyBuiltMembersEmitIdenticalCanonicalHistoryForOneThousandEpochs() {
        List<L1EpochObserver> first = observers();
        List<L1EpochObserver> second = observers();

        for (long newEpoch = 1; newEpoch <= 1_000; newEpoch++) {
            L1EpochBoundary boundary = new L1EpochBoundary(newEpoch - 1, newEpoch,
                    newEpoch * 1_000, hash(newEpoch), newEpoch * 100);
            SyntheticState memberA = new SyntheticState(boundary, false);
            SyntheticState memberB = new SyntheticState(boundary, true);
            for (int observer = 0; observer < first.size(); observer++) {
                assertThat(observe(first.get(observer), boundary, memberA))
                        .containsExactlyElementsOf(observe(
                                second.get(observer), boundary, memberB));
            }
        }
    }

    private static List<L1EpochObserver> observers() {
        return List.of(
                new EpochParamsObserver("epoch-params"),
                new EpochStakeObserver("epoch-stake", 2),
                new EpochGovernanceObserver("epoch-governance", true, true, 2));
    }

    private static List<byte[]> observe(L1EpochObserver observer, L1EpochBoundary boundary,
                                        L1EpochState state) {
        EpochObservationManifest manifest = observer.prepare(boundary, state);
        List<byte[]> encoded = new ArrayList<>();
        observer.writeObservations(manifest, state, (index, claim) -> encoded.add(
                L1Observation.epoch(observer.observerId(), boundary.newEpoch(),
                        boundary.boundarySlot(), boundary.boundaryBlockHash(), claim).encode()));
        return encoded;
    }

    private record SyntheticState(L1EpochBoundary boundary, boolean reverseInsertion)
            implements L1EpochState {
        @Override public long previousEpoch() { return boundary.previousEpoch(); }
        @Override public long newEpoch() { return boundary.newEpoch(); }

        @Override
        public ProtocolParamsView protocolParams(long effectiveEpoch) {
            return new ProtocolParamsView(effectiveEpoch,
                    ByteBuffer.allocate(Long.BYTES).putLong(effectiveEpoch).array());
        }

        @Override public boolean hasStakeSnapshot(long snapshotEpoch) {
            return snapshotEpoch == previousEpoch();
        }

        @Override
        public void forEachStakeEntry(long snapshotEpoch, StakeEntryConsumer consumer) {
            List<Stake> source = new ArrayList<>(List.of(
                    new Stake(1, hash28(3), BigInteger.valueOf(snapshotEpoch + 30), hash28(30)),
                    new Stake(0, hash28(1), BigInteger.valueOf(snapshotEpoch + 10), hash28(10)),
                    new Stake(0, hash28(2), BigInteger.valueOf(snapshotEpoch + 20), hash28(20))));
            if (reverseInsertion) java.util.Collections.reverse(source);
            source.stream().sorted(Comparator.comparingInt(Stake::type)
                            .thenComparing(Stake::credential, EpochHistoryThousandEpochDeterminismTest::compare))
                    .forEach(value -> consumer.accept(value.type(), value.credential(),
                            value.coin(), value.pool()));
        }

        @Override public boolean hasProposalStatusSnapshot(long snapshotEpoch) {
            return snapshotEpoch == newEpoch();
        }

        @Override public boolean hasDRepDistributionSnapshot(long snapshotEpoch) {
            return snapshotEpoch == newEpoch();
        }

        @Override
        public void forEachProposalStatus(long snapshotEpoch,
                                          ProposalStatusConsumer consumer) {
            List<Proposal> source = new ArrayList<>(List.of(
                    new Proposal(hash(snapshotEpoch + 2), 1),
                    new Proposal(hash(snapshotEpoch + 1), 0)));
            if (reverseInsertion) java.util.Collections.reverse(source);
            source.stream().sorted(Comparator.comparing(Proposal::txId,
                                    EpochHistoryThousandEpochDeterminismTest::compare)
                            .thenComparingInt(Proposal::index))
                    .forEach(value -> consumer.accept(value.txId(), value.index(),
                            GovernanceActionType.PARAMETER_CHANGE,
                            GovernanceProposalStatus.ACTIVE,
                            GovernanceProposalStatusReason.NONE,
                            snapshotEpoch - 1, snapshotEpoch + 2));
        }

        @Override
        public void forEachDRepDistributionEntry(long snapshotEpoch,
                                                  DRepDistributionConsumer consumer) {
            List<DRep> source = new ArrayList<>(List.of(
                    new DRep(0, hash28(4), BigInteger.valueOf(snapshotEpoch + 1)),
                    new DRep(1, hash28(5), BigInteger.valueOf(snapshotEpoch + 2))));
            if (reverseInsertion) java.util.Collections.reverse(source);
            source.stream().sorted(Comparator.comparingInt(DRep::type)
                            .thenComparing(DRep::hash,
                                    EpochHistoryThousandEpochDeterminismTest::compare))
                    .forEach(value -> consumer.accept(value.type(), value.hash(), value.coin()));
        }
    }

    private record Stake(int type, byte[] credential, BigInteger coin, byte[] pool) { }
    private record Proposal(byte[] txId, int index) { }
    private record DRep(int type, byte[] hash, BigInteger coin) { }

    private static byte[] hash(long value) {
        byte[] hash = new byte[32];
        ByteBuffer.wrap(hash, 24, 8).putLong(value);
        return hash;
    }

    private static byte[] hash28(long value) {
        byte[] hash = new byte[28];
        ByteBuffer.wrap(hash, 20, 8).putLong(value);
        return hash;
    }

    private static int compare(byte[] left, byte[] right) {
        return java.util.Arrays.compareUnsigned(left, right);
    }
}
