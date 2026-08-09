package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppCapabilityManifest;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochStakeContract;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Replay-only consumer of canonical chunked end-of-epoch stake observations. */
public final class EpochStakeStateMachine implements AppStateMachine {
    private final String observerId;
    private final int chunkEntries;

    public EpochStakeStateMachine() {
        this(EpochStakeContract.DEFAULT_OBSERVER_ID, EpochStakeContract.DEFAULT_CHUNK_ENTRIES);
    }

    public EpochStakeStateMachine(String observerId, int chunkEntries) {
        if (observerId == null || observerId.isBlank()) {
            throw new IllegalArgumentException("epoch-stake observer id is required");
        }
        if (chunkEntries <= 0 || chunkEntries > EpochStakeContract.MAX_CHUNK_ENTRIES) {
            throw new IllegalArgumentException("epoch-stake chunk-entries must be between 1 and 25000");
        }
        this.observerId = observerId;
        this.chunkEntries = chunkEntries;
    }

    @Override public String id() { return EpochStakeContract.STATE_MACHINE_ID; }

    @Override
    public AppCapabilityManifest capabilityManifest() {
        return StdlibCapabilityManifests.component(id(), "~l1/" + observerId,
                        List.of(EpochStakeContract.QUERY_PATH, EpochStakeContract.META_QUERY_PATH))
                .proofSubject(new AppCapabilityManifest.ProofSubject(
                        EpochStakeContract.PROOF_SUBJECT, "", "stake/", "state-proof"))
                .build();
    }

    @Override
    public void apply(AppBlockExecutionContext context, AppStateWriter writer,
                      AppEffectEmitter effects) {
        int chunks = 0;
        for (var sequenced : context.l1Observations()) {
            L1Observation observation = sequenced.observation();
            if (!observerId.equals(observation.observerId())
                    || !(observation.anchor() instanceof L1Observation.EpochAnchor anchor)) continue;
            if (applyClaim(anchor.newEpoch(), observation.claim(), writer)) chunks++;
        }
        if (chunks > 1) {
            throw new IllegalArgumentException("At most one epoch-stake chunk is allowed per app block");
        }
    }

    /** @return true only for a data chunk, false for a manifest. */
    private boolean applyClaim(long newEpoch, byte[] claim, AppStateWriter writer) {
        EpochStakeContract.Manifest manifest;
        try {
            manifest = EpochStakeContract.decodeManifest(claim);
        } catch (IllegalArgumentException notManifest) {
            manifest = null;
        }
        if (manifest != null) {
            if (manifest.epoch() + 1 != newEpoch || manifest.chunkEntries() != chunkEntries) {
                throw new IllegalArgumentException("epoch-stake manifest does not match chain profile/anchor");
            }
            applyManifest(manifest, writer);
            return false;
        }
        EpochStakeContract.Chunk chunk = EpochStakeContract.decodeChunk(claim);
        if (chunk.epoch() + 1 != newEpoch) {
            throw new IllegalArgumentException("epoch-stake chunk does not match its anchor");
        }
        applyChunk(chunk, writer);
        return true;
    }

    private static void applyManifest(EpochStakeContract.Manifest manifest, AppStateWriter writer) {
        byte[] key = EpochStakeContract.metaKey(manifest.epoch());
        byte[] current = writer.get(key).orElse(null);
        if (current != null) {
            if (!EpochStakeContract.decodeMeta(current).manifest().equals(manifest)) {
                throw new IllegalStateException("Historical epoch-stake manifest is write-once");
            }
            return;
        }
        writer.put(key, EpochStakeContract.encodeMeta(new EpochStakeContract.Meta(
                manifest, 0, manifest.chunkCount() == 0)));
    }

    private static void applyChunk(EpochStakeContract.Chunk chunk, AppStateWriter writer) {
        byte[] metaKey = EpochStakeContract.metaKey(chunk.epoch());
        EpochStakeContract.Meta meta = writer.get(metaKey)
                .map(EpochStakeContract::decodeMeta)
                .orElseThrow(() -> new IllegalStateException("epoch-stake chunk arrived before manifest"));
        EpochStakeContract.Manifest manifest = meta.manifest();
        if (!Arrays.equals(chunk.snapshotRoot(), manifest.snapshotRoot())
                || chunk.index() >= manifest.chunkCount()) {
            throw new IllegalArgumentException("epoch-stake chunk differs from its manifest");
        }
        byte[] chunkKey = EpochStakeContract.chunkKey(chunk.epoch(), chunk.index());
        byte[] chunkHash = EpochStakeContract.chunkHash(chunk.entries());
        byte[] existing = writer.get(chunkKey).orElse(null);
        if (existing != null) {
            if (!Arrays.equals(existing, chunkHash)) {
                throw new IllegalStateException("Historical epoch-stake chunk is write-once");
            }
            return;
        }
        if (meta.complete() || chunk.index() != meta.receivedChunks()) {
            throw new IllegalArgumentException("epoch-stake chunks must be applied consecutively");
        }
        int expectedEntries = expectedEntries(manifest, chunk.index());
        if (chunk.entries().size() != expectedEntries) {
            throw new IllegalArgumentException("epoch-stake chunk has the wrong entry count");
        }
        byte[] previous = writer.get(EpochStakeContract.cursorKey(chunk.epoch())).orElse(null);
        if (previous != null && !chunk.entries().isEmpty()
                && Arrays.compareUnsigned(previous, orderKey(chunk.entries().getFirst())) >= 0) {
            throw new IllegalArgumentException("epoch-stake chunks are not globally canonical");
        }
        for (EpochStakeContract.Entry entry : chunk.entries()) {
            byte[] entryKey = EpochStakeContract.entryKey(
                    chunk.epoch(), entry.credType(), entry.credHash());
            if (writer.get(entryKey).isPresent()) {
                throw new IllegalStateException("Historical epoch-stake entry is write-once");
            }
            writer.put(entryKey, EpochStakeContract.encodeValue(entry));
        }
        writer.put(chunkKey, chunkHash);
        if (!chunk.entries().isEmpty()) {
            writer.put(EpochStakeContract.cursorKey(chunk.epoch()),
                    orderKey(chunk.entries().getLast()));
        }
        int received = meta.receivedChunks() + 1;
        boolean complete = received == manifest.chunkCount();
        if (complete) verifyRoot(manifest, chunk.index(), chunkHash, writer);
        writer.put(metaKey, EpochStakeContract.encodeMeta(
                new EpochStakeContract.Meta(manifest, received, complete)));
    }

    private static int expectedEntries(EpochStakeContract.Manifest manifest, int index) {
        if (index < manifest.chunkCount() - 1) return manifest.chunkEntries();
        return Math.toIntExact(manifest.totalEntries()
                - (long) index * manifest.chunkEntries());
    }

    private static void verifyRoot(EpochStakeContract.Manifest manifest, int currentIndex,
                                   byte[] currentHash, AppStateWriter writer) {
        List<byte[]> hashes = new ArrayList<>(manifest.chunkCount());
        for (int index = 0; index < manifest.chunkCount(); index++) {
            hashes.add(index == currentIndex ? currentHash
                    : writer.get(EpochStakeContract.chunkKey(manifest.epoch(), index))
                    .orElseThrow(() -> new IllegalStateException("epoch-stake chunk is missing")));
        }
        if (!Arrays.equals(EpochStakeContract.snapshotRoot(hashes), manifest.snapshotRoot())) {
            throw new IllegalArgumentException("epoch-stake snapshot root mismatch");
        }
    }

    private static byte[] orderKey(EpochStakeContract.Entry entry) {
        return EpochStakeContract.credentialOrderKey(entry.credType(), entry.credHash());
    }

    @Override
    public byte[] query(String path, byte[] params, AppQueryContext state) {
        try {
            if (EpochStakeContract.META_QUERY_PATH.equals(path)) {
                long epoch = com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochParamsContract
                        .decodeEpoch(params);
                return state.get(EpochStakeContract.metaKey(epoch))
                        .filter(value -> EpochStakeContract.decodeMeta(value).complete())
                        .orElse(new byte[0]);
            }
            if (EpochStakeContract.QUERY_PATH.equals(path)) {
                EpochStakeContract.Query query = EpochStakeContract.decodeQuery(params);
                boolean complete = state.get(EpochStakeContract.metaKey(query.epoch()))
                        .map(EpochStakeContract::decodeMeta).map(EpochStakeContract.Meta::complete)
                        .orElse(false);
                if (!complete) return new byte[0];
                return state.get(EpochStakeContract.entryKey(
                        query.epoch(), query.credType(), query.credHash())).orElse(new byte[0]);
            }
        } catch (RuntimeException malformed) {
            throw new AppQueryException(AppQueryException.Code.INVALID_REQUEST,
                    "epoch-stake query is malformed");
        }
        throw new AppQueryException(AppQueryException.Code.UNSUPPORTED,
                "unknown epoch-stake query path");
    }
}
