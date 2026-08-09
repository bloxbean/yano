package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.yano.api.appchain.l1view.EpochObservationManifest;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochBoundary;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochObservationSink;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochObserver;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1EpochState;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochGovernanceContract;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Canonical two-pass governance-history observer with independently optional datasets. */
public final class EpochGovernanceObserver implements L1EpochObserver {
    private final String observerId;
    private final boolean includeProposals;
    private final boolean includeDReps;
    private final int drepChunkEntries;
    private final Map<Long, EpochGovernanceContract.Header> prepared = new ConcurrentHashMap<>();

    public EpochGovernanceObserver(String observerId, boolean includeProposals,
                                   boolean includeDReps, int drepChunkEntries) {
        if (observerId == null || observerId.isBlank()) throw new IllegalArgumentException("governance observer id is required");
        if (!includeProposals && !includeDReps) throw new IllegalArgumentException("at least one governance dataset is required");
        if (drepChunkEntries <= 0 || drepChunkEntries > EpochGovernanceContract.MAX_DREP_CHUNK_ENTRIES)
            throw new IllegalArgumentException("DRep chunk entries must be between 1 and 25000");
        this.observerId = observerId;
        this.includeProposals = includeProposals;
        this.includeDReps = includeDReps;
        this.drepChunkEntries = drepChunkEntries;
    }

    @Override public String observerId() { return observerId; }

    @Override
    public EpochObservationManifest prepare(L1EpochBoundary boundary, L1EpochState state) {
        long epoch = boundary.newEpoch();
        Pass pass = scan(state, epoch, false, null, null, null);
        EpochGovernanceContract.Header header = pass.header(epoch);
        byte[] root = EpochGovernanceContract.outerRoot(header, pass.proposalHashes, pass.drepHashes);
        prepared.put(boundary.newEpoch(), header);
        return new EpochObservationManifest(EpochObservationManifest.VERSION, observerId,
                boundary.previousEpoch(), boundary.newEpoch(), epoch,
                pass.proposalCount + pass.drepCount, drepChunkEntries,
                Math.addExact(Math.toIntExact(pass.proposalCount), pass.drepHashes.size()), root);
    }

    @Override
    public void writeObservations(EpochObservationManifest manifest, L1EpochState state,
                                  L1EpochObservationSink sink) {
        EpochGovernanceContract.Header header = prepared.get(manifest.newEpoch());
        if (header == null || header.epoch() != manifest.datasetEpoch())
            throw new IllegalStateException("governance observer preparation is unavailable");
        sink.write(0, EpochGovernanceContract.encodeHeader(header));
        int[] index = {1};
        Pass pass = scan(state, manifest.datasetEpoch(), true,
                proposal -> sink.write(index[0]++, EpochGovernanceContract.encodeProposal(proposal)),
                chunk -> sink.write(index[0]++, EpochGovernanceContract.encodeDRepChunk(chunk)),
                header.drepRoot());
        EpochGovernanceContract.Header actual = pass.header(manifest.datasetEpoch());
        byte[] root = EpochGovernanceContract.outerRoot(actual, pass.proposalHashes, pass.drepHashes);
        if (!header.equals(actual) || !Arrays.equals(root, manifest.snapshotRoot())
                || index[0] != manifest.observationCount())
            throw new IllegalStateException("governance snapshot changed between observer passes");
        prepared.remove(manifest.newEpoch(), header);
    }

    @Override public Map<String, Object> status() {
        return Map.of("dataset", "epoch-governance", "wireVersion", 1,
                "includeProposals", includeProposals, "includeDRepDistribution", includeDReps,
                "drepChunkEntries", drepChunkEntries);
    }

    private Pass scan(L1EpochState state, long epoch, boolean emit,
                      java.util.function.Consumer<EpochGovernanceContract.Proposal> proposalSink,
                      java.util.function.Consumer<EpochGovernanceContract.DRepChunk> drepSink,
                      byte[] emissionDrepRoot) {
        Pass pass = new Pass();
        if (includeProposals) {
            if (!state.hasProposalStatusSnapshot(epoch))
                throw new IllegalStateException("proposal lifecycle snapshot is unavailable for epoch " + epoch);
            state.forEachProposalStatus(epoch, (txId, index, action, status, reason, proposed, expires) -> {
                EpochGovernanceContract.Proposal proposal = new EpochGovernanceContract.Proposal(epoch,
                        txId, index, EpochGovernanceContract.ActionType.valueOf(action.name()),
                        EpochGovernanceContract.ProposalStatus.valueOf(status.name()),
                        EpochGovernanceContract.ProposalReason.valueOf(reason.name()), proposed, expires);
                if (pass.previousProposal != null && EpochGovernanceContract.compare(pass.previousProposal, proposal) >= 0)
                    throw new IllegalStateException("host proposal iterator is not canonical");
                pass.previousProposal = proposal;
                pass.proposalCount++;
                pass.proposalHashes.add(EpochGovernanceContract.proposalHash(proposal));
                if (emit) proposalSink.accept(proposal);
            });
        }
        if (includeDReps) {
            if (!state.hasDRepDistributionSnapshot(epoch))
                throw new IllegalStateException("DRep distribution snapshot is unavailable for epoch " + epoch);
            state.forEachDRepDistributionEntry(epoch, (type, hash, coin) -> {
                EpochGovernanceContract.DRepEntry entry = new EpochGovernanceContract.DRepEntry(type, hash, coin);
                if (pass.previousDRep != null && EpochGovernanceContract.compare(pass.previousDRep, entry) >= 0)
                    throw new IllegalStateException("host DRep iterator is not canonical");
                pass.previousDRep = entry;
                pass.drepEntries.add(entry);
                pass.drepCount++;
                if (pass.drepEntries.size() == drepChunkEntries)
                    pass.flushDReps(epoch, emit, drepSink, emissionDrepRoot);
            });
            if (!pass.drepEntries.isEmpty())
                pass.flushDReps(epoch, emit, drepSink, emissionDrepRoot);
        }
        return pass;
    }

    private final class Pass {
        private final List<byte[]> proposalHashes = new ArrayList<>();
        private final List<byte[]> drepHashes = new ArrayList<>();
        private final List<EpochGovernanceContract.DRepEntry> drepEntries = new ArrayList<>(drepChunkEntries);
        private EpochGovernanceContract.Proposal previousProposal;
        private EpochGovernanceContract.DRepEntry previousDRep;
        private long proposalCount;
        private long drepCount;

        private void flushDReps(long epoch, boolean emit,
                               java.util.function.Consumer<EpochGovernanceContract.DRepChunk> sink,
                               byte[] emissionRoot) {
            List<EpochGovernanceContract.DRepEntry> entries = List.copyOf(drepEntries);
            drepHashes.add(EpochGovernanceContract.drepChunkHash(entries));
            if (emit) sink.accept(new EpochGovernanceContract.DRepChunk(epoch,
                    emissionRoot,
                    drepHashes.size() - 1, entries));
            drepEntries.clear();
        }

        private EpochGovernanceContract.Header header(long epoch) {
            return new EpochGovernanceContract.Header(epoch, includeProposals, proposalCount,
                    EpochGovernanceContract.proposalRoot(proposalHashes), includeDReps, drepCount,
                    drepChunkEntries, drepHashes.size(), EpochGovernanceContract.drepRoot(drepHashes));
        }
    }
}
