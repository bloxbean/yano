package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppCapabilityManifest;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochGovernanceContract;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Replay-only consumer of epoch-pinned proposal lifecycle and DRep distribution claims. */
public final class EpochGovernanceStateMachine implements AppStateMachine {
    private final String observerId;
    private final boolean includeProposals;
    private final boolean includeDReps;
    private final int drepChunkEntries;

    public EpochGovernanceStateMachine() {
        this(EpochGovernanceContract.DEFAULT_OBSERVER_ID, true, false,
                EpochGovernanceContract.DEFAULT_DREP_CHUNK_ENTRIES);
    }
    public EpochGovernanceStateMachine(String observerId, boolean includeProposals,
                                       boolean includeDReps, int drepChunkEntries) {
        if (observerId == null || observerId.isBlank()) throw new IllegalArgumentException("governance observer id is required");
        if (!includeProposals && !includeDReps) throw new IllegalArgumentException("at least one governance dataset is required");
        if (drepChunkEntries <= 0 || drepChunkEntries > EpochGovernanceContract.MAX_DREP_CHUNK_ENTRIES)
            throw new IllegalArgumentException("DRep chunk entries must be between 1 and 25000");
        this.observerId = observerId; this.includeProposals = includeProposals;
        this.includeDReps = includeDReps; this.drepChunkEntries = drepChunkEntries;
    }

    @Override public String id() { return EpochGovernanceContract.STATE_MACHINE_ID; }
    @Override public AppCapabilityManifest capabilityManifest() {
        var builder = StdlibCapabilityManifests.component(id(), "~l1/" + observerId,
                List.of(EpochGovernanceContract.PROPOSAL_QUERY_PATH, EpochGovernanceContract.DREP_QUERY_PATH,
                        EpochGovernanceContract.PROPOSAL_META_QUERY_PATH, EpochGovernanceContract.DREP_META_QUERY_PATH));
        if (includeProposals) builder.proofSubject(new AppCapabilityManifest.ProofSubject(
                EpochGovernanceContract.PROPOSAL_PROOF_SUBJECT, "", "governance/", "state-proof"));
        if (includeDReps) builder.proofSubject(new AppCapabilityManifest.ProofSubject(
                EpochGovernanceContract.DREP_PROOF_SUBJECT, "", "governance/", "state-proof"));
        return builder.build();
    }

    @Override public void apply(AppBlockExecutionContext context, AppStateWriter writer, AppEffectEmitter effects) {
        int dataClaims = 0;
        for (var sequenced : context.l1Observations()) {
            L1Observation observation = sequenced.observation();
            if (!observerId.equals(observation.observerId())
                    || !(observation.anchor() instanceof L1Observation.EpochAnchor anchor)) continue;
            int type = EpochGovernanceContract.claimType(observation.claim());
            switch (type) {
                case EpochGovernanceContract.HEADER -> applyHeader(anchor.newEpoch(),
                        EpochGovernanceContract.decodeHeader(observation.claim()), writer);
                case EpochGovernanceContract.PROPOSAL -> {
                    applyProposal(anchor.newEpoch(), EpochGovernanceContract.decodeProposal(observation.claim()), writer);
                    dataClaims++;
                }
                case EpochGovernanceContract.DREP_CHUNK -> {
                    applyDRepChunk(anchor.newEpoch(), EpochGovernanceContract.decodeDRepChunk(observation.claim()), writer);
                    dataClaims++;
                }
                default -> throw new IllegalArgumentException("unknown governance claim type");
            }
        }
        if (dataClaims > 1) throw new IllegalArgumentException("at most one governance data claim is allowed per app block");
    }

    private void applyHeader(long newEpoch, EpochGovernanceContract.Header header, AppStateWriter writer) {
        if (header.epoch() != newEpoch || header.includeProposals() != includeProposals
                || header.includeDReps() != includeDReps
                || header.drepChunkEntries() != drepChunkEntries)
            throw new IllegalArgumentException("governance header differs from chain profile/anchor");
        if (includeProposals) {
            byte[] key = EpochGovernanceContract.proposalMetaKey(header.epoch());
            EpochGovernanceContract.ProposalMeta meta = new EpochGovernanceContract.ProposalMeta(header.epoch(),
                    header.proposalCount(), header.proposalRoot(), 0, header.proposalCount() == 0);
            putManifestOnce(key, EpochGovernanceContract.encodeProposalMeta(meta), writer, "proposal");
        }
        if (includeDReps) {
            byte[] key = EpochGovernanceContract.drepMetaKey(header.epoch());
            EpochGovernanceContract.DRepMeta meta = new EpochGovernanceContract.DRepMeta(header.epoch(),
                    header.drepCount(), header.drepChunkEntries(), header.drepChunkCount(), header.drepRoot(),
                    0, header.drepChunkCount() == 0);
            putManifestOnce(key, EpochGovernanceContract.encodeDRepMeta(meta), writer, "DRep");
        }
    }

    private static void putManifestOnce(byte[] key, byte[] value, AppStateWriter writer, String label) {
        byte[] existing = writer.get(key).orElse(null);
        if (existing != null) { if (!Arrays.equals(existing, value))
            throw new IllegalStateException("historical " + label + " manifest is write-once"); return; }
        writer.put(key, value);
    }

    private void applyProposal(long newEpoch, EpochGovernanceContract.Proposal proposal, AppStateWriter writer) {
        if (!includeProposals || proposal.epoch() != newEpoch) throw new IllegalArgumentException("proposal claim is not enabled/pinned");
        byte[] metaKey = EpochGovernanceContract.proposalMetaKey(proposal.epoch());
        EpochGovernanceContract.ProposalMeta meta = writer.get(metaKey)
                .map(EpochGovernanceContract::decodeProposalMeta)
                .orElseThrow(() -> new IllegalStateException("proposal arrived before governance header"));
        byte[] hash = EpochGovernanceContract.proposalHash(proposal);
        byte[] claimKey = EpochGovernanceContract.proposalClaimKey(proposal.epoch(), meta.received());
        byte[] existingHash = writer.get(claimKey).orElse(null);
        if (existingHash != null) { if (!Arrays.equals(existingHash, hash))
            throw new IllegalStateException("historical proposal claim is write-once"); return; }
        if (meta.complete()) throw new IllegalArgumentException("proposal snapshot is already complete");
        byte[] order = EpochGovernanceContract.proposalOrderKey(proposal);
        byte[] previous = writer.get(EpochGovernanceContract.proposalCursorKey(proposal.epoch())).orElse(null);
        if (previous != null && Arrays.compareUnsigned(previous, order) >= 0)
            throw new IllegalArgumentException("proposal claims are not globally canonical");
        byte[] entryKey = EpochGovernanceContract.proposalKey(proposal.epoch(), proposal.transactionId(),
                proposal.governanceActionIndex());
        if (writer.get(entryKey).isPresent()) throw new IllegalStateException("historical proposal entry is write-once");
        writer.put(entryKey, EpochGovernanceContract.encodeProposalValue(proposal));
        writer.put(claimKey, hash); writer.put(EpochGovernanceContract.proposalCursorKey(proposal.epoch()), order);
        long received = meta.received() + 1; boolean complete = received == meta.total();
        if (complete) verifyProposalRoot(meta, received, hash, writer);
        writer.put(metaKey, EpochGovernanceContract.encodeProposalMeta(new EpochGovernanceContract.ProposalMeta(
                meta.epoch(), meta.total(), meta.root(), received, complete)));
    }

    private static void verifyProposalRoot(EpochGovernanceContract.ProposalMeta meta, long received,
                                           byte[] currentHash, AppStateWriter writer) {
        List<byte[]> hashes = new ArrayList<>(Math.toIntExact(received));
        for (long index = 0; index < received; index++) hashes.add(index == received - 1 ? currentHash
                : writer.get(EpochGovernanceContract.proposalClaimKey(meta.epoch(), index)).orElseThrow());
        if (!Arrays.equals(EpochGovernanceContract.proposalRoot(hashes), meta.root()))
            throw new IllegalArgumentException("proposal snapshot root mismatch");
    }

    private void applyDRepChunk(long newEpoch, EpochGovernanceContract.DRepChunk chunk, AppStateWriter writer) {
        if (!includeDReps || chunk.epoch() != newEpoch) throw new IllegalArgumentException("DRep claim is not enabled/pinned");
        byte[] metaKey = EpochGovernanceContract.drepMetaKey(chunk.epoch());
        EpochGovernanceContract.DRepMeta meta = writer.get(metaKey).map(EpochGovernanceContract::decodeDRepMeta)
                .orElseThrow(() -> new IllegalStateException("DRep chunk arrived before governance header"));
        if (!Arrays.equals(meta.root(), chunk.distributionRoot()) || chunk.index() >= meta.chunkCount())
            throw new IllegalArgumentException("DRep chunk differs from governance header");
        byte[] hash = EpochGovernanceContract.drepChunkHash(chunk.entries());
        byte[] chunkKey = EpochGovernanceContract.drepChunkKey(chunk.epoch(), chunk.index());
        byte[] existing = writer.get(chunkKey).orElse(null);
        if (existing != null) { if (!Arrays.equals(existing, hash))
            throw new IllegalStateException("historical DRep chunk is write-once"); return; }
        if (meta.complete() || chunk.index() != meta.receivedChunks())
            throw new IllegalArgumentException("DRep chunks must be consecutive");
        int expected = chunk.index() < meta.chunkCount() - 1 ? meta.chunkEntries()
                : Math.toIntExact(meta.total() - (long) chunk.index() * meta.chunkEntries());
        if (chunk.entries().size() != expected) throw new IllegalArgumentException("wrong DRep chunk size");
        byte[] previous = writer.get(EpochGovernanceContract.drepCursorKey(chunk.epoch())).orElse(null);
        if (previous != null && !chunk.entries().isEmpty()
                && Arrays.compareUnsigned(previous, EpochGovernanceContract.drepOrderKey(chunk.entries().getFirst())) >= 0)
            throw new IllegalArgumentException("DRep chunks are not globally canonical");
        for (var entry : chunk.entries()) {
            byte[] key = EpochGovernanceContract.drepKey(chunk.epoch(), entry.drepType(), entry.drepHash());
            if (writer.get(key).isPresent()) throw new IllegalStateException("historical DRep entry is write-once");
            writer.put(key, EpochGovernanceContract.encodeCoin(entry.coin()));
        }
        writer.put(chunkKey, hash);
        if (!chunk.entries().isEmpty()) writer.put(EpochGovernanceContract.drepCursorKey(chunk.epoch()),
                EpochGovernanceContract.drepOrderKey(chunk.entries().getLast()));
        int received = meta.receivedChunks() + 1; boolean complete = received == meta.chunkCount();
        if (complete) verifyDRepRoot(meta, chunk.index(), hash, writer);
        writer.put(metaKey, EpochGovernanceContract.encodeDRepMeta(new EpochGovernanceContract.DRepMeta(meta.epoch(),
                meta.total(), meta.chunkEntries(), meta.chunkCount(), meta.root(), received, complete)));
    }

    private static void verifyDRepRoot(EpochGovernanceContract.DRepMeta meta, int current,
                                       byte[] currentHash, AppStateWriter writer) {
        List<byte[]> hashes = new ArrayList<>(meta.chunkCount());
        for (int i = 0; i < meta.chunkCount(); i++) hashes.add(i == current ? currentHash
                : writer.get(EpochGovernanceContract.drepChunkKey(meta.epoch(), i)).orElseThrow());
        if (!Arrays.equals(EpochGovernanceContract.drepRoot(hashes), meta.root()))
            throw new IllegalArgumentException("DRep distribution root mismatch");
    }

    @Override public byte[] query(String path, byte[] params, AppQueryContext state) {
        try {
            if (EpochGovernanceContract.PROPOSAL_META_QUERY_PATH.equals(path)) return completeProposalMeta(params, state);
            if (EpochGovernanceContract.DREP_META_QUERY_PATH.equals(path)) return completeDRepMeta(params, state);
            if (EpochGovernanceContract.PROPOSAL_QUERY_PATH.equals(path)) {
                var q = EpochGovernanceContract.decodeProposalQuery(params);
                if (!proposalComplete(q.epoch(), state)) return new byte[0];
                return state.get(EpochGovernanceContract.proposalKey(q.epoch(), q.transactionId(),
                        q.governanceActionIndex())).orElse(new byte[0]);
            }
            if (EpochGovernanceContract.DREP_QUERY_PATH.equals(path)) {
                var q = EpochGovernanceContract.decodeDRepQuery(params);
                if (!drepComplete(q.epoch(), state)) return new byte[0];
                return state.get(EpochGovernanceContract.drepKey(q.epoch(), q.drepType(), q.drepHash())).orElse(new byte[0]);
            }
        } catch (RuntimeException malformed) { throw new AppQueryException(AppQueryException.Code.INVALID_REQUEST,
                "epoch-governance query is malformed"); }
        throw new AppQueryException(AppQueryException.Code.UNSUPPORTED, "unknown epoch-governance query path");
    }
    private static byte[] completeProposalMeta(byte[] params, AppQueryContext state) {
        long epoch = com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochParamsContract.decodeEpoch(params);
        return state.get(EpochGovernanceContract.proposalMetaKey(epoch))
                .filter(v -> EpochGovernanceContract.decodeProposalMeta(v).complete()).orElse(new byte[0]);
    }
    private static byte[] completeDRepMeta(byte[] params, AppQueryContext state) {
        long epoch = com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochParamsContract.decodeEpoch(params);
        return state.get(EpochGovernanceContract.drepMetaKey(epoch))
                .filter(v -> EpochGovernanceContract.decodeDRepMeta(v).complete()).orElse(new byte[0]);
    }
    private static boolean proposalComplete(long epoch, AppQueryContext state) { return state
            .get(EpochGovernanceContract.proposalMetaKey(epoch)).map(EpochGovernanceContract::decodeProposalMeta)
            .map(EpochGovernanceContract.ProposalMeta::complete).orElse(false); }
    private static boolean drepComplete(long epoch, AppQueryContext state) { return state
            .get(EpochGovernanceContract.drepMetaKey(epoch)).map(EpochGovernanceContract::decodeDRepMeta)
            .map(EpochGovernanceContract.DRepMeta::complete).orElse(false); }
}
