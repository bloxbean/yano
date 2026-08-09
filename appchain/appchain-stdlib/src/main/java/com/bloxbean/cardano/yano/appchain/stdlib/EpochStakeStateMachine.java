package com.bloxbean.cardano.yano.appchain.stdlib;

import com.bloxbean.cardano.yano.api.appchain.AppBlockExecutionContext;
import com.bloxbean.cardano.yano.api.appchain.AppCapabilityManifest;
import com.bloxbean.cardano.yano.api.appchain.AppQueryContext;
import com.bloxbean.cardano.yano.api.appchain.AppQueryException;
import com.bloxbean.cardano.yano.api.appchain.AppStateMachine;
import com.bloxbean.cardano.yano.api.appchain.AppStateWriter;
import com.bloxbean.cardano.yano.api.appchain.effects.AppEffectEmitter;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;
import com.bloxbean.cardano.yano.api.appchain.snapshot.AuthenticatedSnapshotSeriesDescriptorV1;
import com.bloxbean.cardano.yano.api.appchain.snapshot.AuthenticatedSnapshotSourceCommitmentV1;
import com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotEntry;
import com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotBuildTokenV1;
import com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotSeriesHandle;
import com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotSourceBoundary;
import com.bloxbean.cardano.yano.api.appchain.state.StateCommitmentProfiles;
import com.bloxbean.cardano.yano.appchain.stdlib.contracts.EpochStakeContract;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Replay-only consumer of canonical chunked end-of-epoch stake observations. */
public final class EpochStakeStateMachine implements AppStateMachine {
    public static final String SNAPSHOT_SERIES_ID = "distribution";
    private static final byte[] SNAPSHOT_NEXT_SEQUENCE =
            "snapshot-series/distribution/next".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private final String observerId;
    private final int chunkEntries;
    private final String snapshotProfile;

    public EpochStakeStateMachine() {
        this(EpochStakeContract.DEFAULT_OBSERVER_ID, EpochStakeContract.DEFAULT_CHUNK_ENTRIES,
                StateCommitmentProfiles.MPF.id());
    }

    public EpochStakeStateMachine(String observerId, int chunkEntries) {
        this(observerId, chunkEntries, StateCommitmentProfiles.MPF.id());
    }

    public EpochStakeStateMachine(String observerId, int chunkEntries, String snapshotProfile) {
        if (observerId == null || observerId.isBlank()) {
            throw new IllegalArgumentException("epoch-stake observer id is required");
        }
        if (chunkEntries <= 0 || chunkEntries > EpochStakeContract.MAX_CHUNK_ENTRIES) {
            throw new IllegalArgumentException("epoch-stake chunk-entries must be between 1 and 25000");
        }
        this.observerId = observerId;
        this.chunkEntries = chunkEntries;
        this.snapshotProfile = StateCommitmentProfiles.require(snapshotProfile).id();
    }

    @Override public String id() { return EpochStakeContract.STATE_MACHINE_ID; }

    @Override
    public List<AuthenticatedSnapshotSeriesDescriptorV1> authenticatedSnapshotSeries() {
        var profile = StateCommitmentProfiles.require(snapshotProfile);
        return List.of(new AuthenticatedSnapshotSeriesDescriptorV1(SNAPSHOT_SERIES_ID,
                "epoch-stake-v1", AuthenticatedSnapshotSeriesDescriptorV1.Trigger.L1_EPOCH_BOUNDARY,
                profile.id(), profile.formatFingerprint(), profile.proofEncodingId(),
                profile.equals(StateCommitmentProfiles.MPF)
                        ? AuthenticatedSnapshotSeriesDescriptorV1.VerificationTarget.ON_CHAIN
                        : AuthenticatedSnapshotSeriesDescriptorV1.VerificationTarget.OFF_CHAIN,
                AuthenticatedSnapshotSeriesDescriptorV1.Visibility.PUBLIC,
                "blake2b256", "epoch-stake-source-v1", chunkEntries,
                4 * 1024 * 1024, 256, 8 * 1024, 10_000_000,
                AuthenticatedSnapshotSeriesDescriptorV1.RecoveryCoverage.DATASET));
    }

    @Override
    public List<AuthenticatedSnapshotSourceCommitmentV1> authenticatedSnapshotSourceCommitments() {
        return List.of(new AuthenticatedSnapshotSourceCommitmentV1() {
            @Override public String seriesId() { return SNAPSHOT_SERIES_ID; }
            @Override public String algorithm() { return "blake2b256"; }
            @Override public String wireVersion() { return "epoch-stake-source-v1"; }
            @Override public byte[] initial(com.bloxbean.cardano.yano.api.appchain.snapshot.SnapshotDescriptorDraftV1 draft) {
                return EpochStakeContract.initialSnapshotRoot();
            }
            @Override public byte[] append(byte[] accumulator, long chunkIndex, List<SnapshotEntry> entries) {
                List<EpochStakeContract.Entry> sourceEntries = new ArrayList<>(entries.size());
                for (SnapshotEntry entry : entries) {
                    byte[] key = entry.key();
                    if (key.length != 29) throw new IllegalArgumentException("invalid epoch-stake snapshot key");
                    var value = EpochStakeContract.decodeValue(entry.value());
                    sourceEntries.add(new EpochStakeContract.Entry(Byte.toUnsignedInt(key[0]),
                            Arrays.copyOfRange(key, 1, 29), value.coin(), value.poolHash()));
                }
                return EpochStakeContract.appendSnapshotRoot(accumulator,
                        EpochStakeContract.chunkHash(sourceEntries));
            }
            @Override public byte[] finish(byte[] accumulator, long chunks, long entries) {
                return accumulator.clone();
            }
        });
    }

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
            if (applyClaim(anchor.newEpoch(), observation, writer)) chunks++;
        }
        if (chunks > 1) {
            throw new IllegalArgumentException("At most one epoch-stake chunk is allowed per app block");
        }
    }

    /** @return true only for a data chunk, false for a manifest. */
    private boolean applyClaim(long newEpoch, L1Observation observation, AppStateWriter writer) {
        byte[] claim = observation.claim();
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
            applyManifest(manifest, observation, writer);
            return false;
        }
        EpochStakeContract.Chunk chunk = EpochStakeContract.decodeChunk(claim);
        if (chunk.epoch() + 1 != newEpoch) {
            throw new IllegalArgumentException("epoch-stake chunk does not match its anchor");
        }
        applyChunk(chunk, writer);
        return true;
    }

    private static void applyManifest(EpochStakeContract.Manifest manifest,
                                      L1Observation observation, AppStateWriter writer) {
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
        writer.capabilities().snapshotSeries(SNAPSHOT_SERIES_ID).ifPresent(handle -> {
            long sequence = nextSequence(writer);
            writer.put(snapshotEpochSequenceKey(manifest.epoch()), longBytes(sequence));
            writer.put(SNAPSHOT_NEXT_SEQUENCE, longBytes(Math.addExact(sequence, 1)));
            long newEpoch = ((L1Observation.EpochAnchor) observation.anchor()).newEpoch();
            SnapshotBuildTokenV1 token = handle.begin(sequence, "epoch-stake-" + manifest.epoch(),
                    new SnapshotSourceBoundary.L1Epoch(manifest.epoch(), newEpoch,
                            manifest.epoch(), observation.slot(), observation.blockHash()),
                    writer.committedHeight(), writer.committedHeight(), writer.committedHeight() + 1,
                    manifest.snapshotRoot(), manifest.chunkCount(), manifest.totalEntries());
            writer.put(snapshotDraftDigestKey(manifest.epoch()), token.descriptorDraftDigest());
            if (manifest.chunkCount() == 0) handle.seal(token);
        });
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
        SnapshotSeriesHandle snapshot = writer.capabilities().snapshotSeries(SNAPSHOT_SERIES_ID)
                .orElse(null);
        List<SnapshotEntry> snapshotEntries = snapshot != null
                ? new ArrayList<>(chunk.entries().size()) : List.of();
        for (EpochStakeContract.Entry entry : chunk.entries()) {
            byte[] entryKey = EpochStakeContract.entryKey(
                    chunk.epoch(), entry.credType(), entry.credHash());
            if (snapshot == null) {
                if (writer.get(entryKey).isPresent()) {
                    throw new IllegalStateException("Historical epoch-stake entry is write-once");
                }
                writer.put(entryKey, EpochStakeContract.encodeValue(entry));
            } else {
                snapshotEntries.add(new SnapshotEntry(orderKey(entry),
                        EpochStakeContract.encodeValue(entry)));
            }
        }
        if (snapshot != null) {
            long sequence = sequenceForEpoch(writer, chunk.epoch());
            snapshot.appendChunk(snapshotTokenForEpoch(writer, chunk.epoch()),
                    chunk.index(), snapshotEntries);
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
        if (complete && snapshot != null) snapshot.seal(snapshotTokenForEpoch(writer, chunk.epoch()));
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

    private static long nextSequence(AppStateWriter writer) {
        return writer.get(SNAPSHOT_NEXT_SEQUENCE).map(EpochStakeStateMachine::decodeLong).orElse(0L);
    }

    private static long sequenceForEpoch(AppStateWriter writer, long epoch) {
        return writer.get(snapshotEpochSequenceKey(epoch)).map(EpochStakeStateMachine::decodeLong)
                .orElseThrow(() -> new IllegalStateException("epoch-stake snapshot sequence is absent"));
    }

    private static byte[] snapshotEpochSequenceKey(long epoch) {
        return ("snapshot-series/distribution/epoch/" + epoch)
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static byte[] snapshotDraftDigestKey(long epoch) {
        return ("snapshot-series/distribution/draft/" + epoch)
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static SnapshotBuildTokenV1 snapshotTokenForEpoch(AppStateWriter writer, long epoch) {
        return new SnapshotBuildTokenV1(sequenceForEpoch(writer, epoch),
                writer.get(snapshotDraftDigestKey(epoch)).orElseThrow(() ->
                        new IllegalStateException("epoch-stake snapshot draft digest is absent")));
    }

    private static byte[] longBytes(long value) { return java.nio.ByteBuffer.allocate(8).putLong(value).array(); }
    private static long decodeLong(byte[] value) {
        if (value.length != 8) throw new IllegalStateException("invalid snapshot sequence value");
        long decoded = java.nio.ByteBuffer.wrap(value).getLong();
        if (decoded < 0) throw new IllegalStateException("negative snapshot sequence value");
        return decoded;
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
                Optional<byte[]> snapshotSequence = state.get(snapshotEpochSequenceKey(query.epoch()));
                if (snapshotSequence.isPresent()) {
                    long sequence = decodeLong(snapshotSequence.orElseThrow());
                    if (!state.authenticatedSnapshotOnline(SNAPSHOT_SERIES_ID, sequence)) {
                        throw new AppQueryException(AppQueryException.Code.UNAVAILABLE,
                                "epoch-stake authenticated snapshot is not local");
                    }
                    return state.authenticatedSnapshotValue(SNAPSHOT_SERIES_ID, sequence,
                            EpochStakeContract.credentialOrderKey(
                                    query.credType(), query.credHash())).orElse(new byte[0]);
                }
                return state.get(EpochStakeContract.entryKey(
                        query.epoch(), query.credType(), query.credHash())).orElse(new byte[0]);
            }
        } catch (AppQueryException declared) {
            throw declared;
        } catch (RuntimeException malformed) {
            throw new AppQueryException(AppQueryException.Code.INVALID_REQUEST,
                    "epoch-stake query is malformed");
        }
        throw new AppQueryException(AppQueryException.Code.UNSUPPORTED,
                "unknown epoch-stake query path");
    }
}
