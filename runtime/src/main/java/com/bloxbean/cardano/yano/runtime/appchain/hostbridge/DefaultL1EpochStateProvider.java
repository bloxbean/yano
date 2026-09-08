package com.bloxbean.cardano.yano.runtime.appchain.hostbridge;

import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.EpochParamProvider;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochBoundary;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochState;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochStateProvider;
import com.bloxbean.cardano.yano.api.appchain.l1view.ProtocolParamsCanonicalCodec;
import com.bloxbean.cardano.yano.api.appchain.l1view.ProtocolParamsView;
import com.bloxbean.cardano.yano.api.util.EpochSlotCalc;
import com.bloxbean.cardano.yano.ledgerstate.DefaultAccountStateStore;
import com.bloxbean.cardano.yano.ledgerstate.EpochBoundaryProcessor;
import com.bloxbean.cardano.yano.ledgerstate.HistoricalEpochStateView;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Host bridge from the live ledger-state store into the bounded app-chain epoch SPI.
 */
public final class DefaultL1EpochStateProvider implements L1EpochStateProvider {
    private final DefaultAccountStateStore store;
    private final ChainState chainState;
    private final EpochParamProvider epochParams;

    public DefaultL1EpochStateProvider(DefaultAccountStateStore store,
                                       ChainState chainState,
                                       EpochParamProvider epochParams) {
        this.store = Objects.requireNonNull(store, "store");
        this.chainState = Objects.requireNonNull(chainState, "chainState");
        this.epochParams = Objects.requireNonNull(epochParams, "epochParams");
    }

    @Override public boolean persistent() { return store.isEnabled(); }
    @Override public int snapshotRetentionEpochs() { return store.snapshotRetentionEpochs(); }
    @Override public long epochAtSlot(long slot) {
        return epochParams.getEpochSlotCalc().slotToEpoch(slot);
    }

    @Override public long firstObservableEpoch() {
        return firstObservableEpoch(epochParams.getEpochSlotCalc());
    }

    @Override
    public List<L1EpochBoundary> completedBoundaries(long afterNewEpoch, int limit) {
        if (limit <= 0) return List.of();
        int[] state = store.getLastBoundaryState();
        if (state == null || state[1] < EpochBoundaryProcessor.STEP_COMPLETE
                || state[0] <= afterNewEpoch || state[0] <= 0) {
            return List.of();
        }
        long latestNewEpoch = state[0];
        long first = firstBoundaryToScan(afterNewEpoch, latestNewEpoch,
                store.snapshotRetentionEpochs(), firstObservableEpoch());
        List<L1EpochBoundary> result = new ArrayList<>();
        for (long newEpoch = first; newEpoch <= latestNewEpoch && result.size() < limit; newEpoch++) {
            long startSlot = epochParams.getEpochSlotCalc().epochToStartSlot((int) newEpoch);
            Point point = chainState.findNextBlock(new Point(Math.max(0, startSlot - 1), null));
            if (point == null || epochAtSlot(point.getSlot()) != newEpoch) continue;
            Long blockNumber = chainState.getBlockNumberBySlot(point.getSlot());
            if (blockNumber == null || point.getHash() == null) continue;
            byte[] hash = HexUtil.decodeHexString(point.getHash());
            if (hash.length != 32) continue;
            result.add(new L1EpochBoundary(newEpoch - 1, newEpoch,
                    point.getSlot(), hash, blockNumber));
        }
        return List.copyOf(result);
    }

    static long firstObservableEpoch(EpochSlotCalc epochSlotCalc) {
        return Math.max(1, epochSlotCalc.firstNonByronEpoch());
    }

    static long firstBoundaryToScan(long afterNewEpoch, long latestNewEpoch,
                                    int retentionEpochs, long firstObservableEpoch) {
        return Math.max(afterNewEpoch + 1,
                Math.max(firstObservableEpoch, latestNewEpoch - retentionEpochs + 1));
    }

    @Override
    public Optional<L1EpochState> open(L1EpochBoundary boundary) {
        int[] latest = store.getLastBoundaryState();
        if (latest == null || latest[1] < EpochBoundaryProcessor.STEP_COMPLETE
                || boundary.newEpoch() > latest[0]) {
            return Optional.empty();
        }
        return Optional.of(new State(boundary, store.openHistoricalEpochStateView()));
    }

    private final class State implements L1EpochState {
        private final L1EpochBoundary boundary;
        private final HistoricalEpochStateView historical;
        private boolean closed;

        private State(L1EpochBoundary boundary, HistoricalEpochStateView historical) {
            this.boundary = boundary;
            this.historical = historical;
        }
        @Override public long previousEpoch() { requireOpen(); return boundary.previousEpoch(); }
        @Override public long newEpoch() { requireOpen(); return boundary.newEpoch(); }

        @Override
        public ProtocolParamsView protocolParams(long effectiveEpoch) {
            requireOpen();
            if (effectiveEpoch != boundary.newEpoch()) {
                throw new IllegalArgumentException("Protocol parameters are not boundary-pinned");
            }
            var snapshot = store.getProtocolParameters(Math.toIntExact(effectiveEpoch))
                    .orElseThrow(() -> new IllegalStateException(
                            "Protocol parameters unavailable for epoch " + effectiveEpoch));
            return new ProtocolParamsView(effectiveEpoch,
                    ProtocolParamsCanonicalCodec.encode(snapshot));
        }

        @Override public boolean hasStakeSnapshot(long epoch) {
            requireOpen();
            requireStakeEpoch(epoch);
            return historical.hasStakeSnapshot(Math.toIntExact(epoch));
        }
        @Override public void forEachStakeEntry(long epoch, StakeEntryConsumer consumer) {
            requireOpen();
            requireStakeEpoch(epoch);
            historical.forEachStakeEntry(Math.toIntExact(epoch), consumer::accept);
        }
        @Override public boolean hasProposalStatusSnapshot(long epoch) {
            requireOpen();
            requireGovernanceEpoch(epoch);
            return historical.hasProposalStatusSnapshot(Math.toIntExact(epoch));
        }
        @Override public boolean hasDRepDistributionSnapshot(long epoch) {
            requireOpen();
            requireGovernanceEpoch(epoch);
            return historical.hasDRepDistributionSnapshot(Math.toIntExact(epoch));
        }
        @Override public void forEachProposalStatus(long epoch, ProposalStatusConsumer consumer) {
            requireOpen();
            requireGovernanceEpoch(epoch);
            historical.forEachProposalStatus(Math.toIntExact(epoch), consumer::accept);
        }
        @Override public void forEachDRepDistributionEntry(long epoch,
                                                           DRepDistributionConsumer consumer) {
            requireOpen();
            requireGovernanceEpoch(epoch);
            historical.forEachDRepDistributionEntry(Math.toIntExact(epoch), consumer::accept);
        }
        @Override public void close() {
            if (!closed) {
                closed = true;
                historical.close();
            }
        }
        private void requireStakeEpoch(long epoch) {
            if (epoch != boundary.previousEpoch()) {
                throw new IllegalArgumentException("Stake snapshot is not boundary-pinned");
            }
        }
        private void requireGovernanceEpoch(long epoch) {
            if (epoch != boundary.newEpoch()) {
                throw new IllegalArgumentException("Governance snapshot is not boundary-pinned");
            }
        }
        private void requireOpen() {
            if (closed) throw new IllegalStateException("L1 epoch state handle is closed");
        }
    }
}
