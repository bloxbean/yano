package com.bloxbean.cardano.yano.runtime.blockproducer;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.util.StoredBlockUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.Optional;

/**
 * Repairs {@link EpochNonceState} by replaying nonce-relevant data from block
 * bodies already stored in ChainState. It never republishes ledger events; it
 * only applies the nonce algorithm and persists nonce snapshots/checkpoints.
 */
@Slf4j
public final class NonceReplayService {

    private final ChainState chainState;
    private final NonceStateStore nonceStore;
    private final EpochNonceEvolver evolver;
    private final byte[] defaultGenesisHash;

    public NonceReplayService(ChainState chainState,
                              NonceStateStore nonceStore,
                              EpochNonceEvolver evolver) {
        this(chainState, nonceStore, evolver, null);
    }

    public NonceReplayService(ChainState chainState,
                              NonceStateStore nonceStore,
                              EpochNonceEvolver evolver,
                              byte[] defaultGenesisHash) {
        this.chainState = chainState;
        this.nonceStore = nonceStore;
        this.evolver = evolver;
        this.defaultGenesisHash = defaultGenesisHash != null ? defaultGenesisHash.clone() : null;
    }

    public RepairResult repairToBodyTip(EpochNonceState nonceState, String reason) {
        return repairToBodyTip(nonceState, defaultGenesisHash, reason);
    }

    public RepairResult repairToBodyTip(EpochNonceState nonceState,
                                        byte[] genesisHash,
                                        String reason) {
        if (nonceStore == null) {
            return new RepairResult("no_store", 0, null);
        }

        ChainTip bodyTip = chainState.getTip();
        if (bodyTip == null) {
            if (genesisHash != null) {
                nonceState.initFromGenesisHash(genesisHash);
                nonceStore.storeLatestNonceSnapshot(NonceStateSnapshot.origin(nonceState.serialize()));
                nonceStore.storeEpochNonce(nonceState.getCurrentEpoch(), nonceState.getEpochNonce());
            }
            return new RepairResult("empty_chain", 0, null);
        }

        Optional<NonceStateSnapshot> latest = nonceStore.getLatestNonceSnapshot();
        if (latest.isPresent() && latest.get().sameCursor(
                bodyTip.getSlot(), bodyTip.getBlockNumber(), bodyTip.getBlockHash())) {
            NonceStateSnapshot snapshot = latest.get();
            nonceState.restore(snapshot.nonceState());
            nonceState.saveCheckpoint(snapshot.slot(), snapshot.nonceState());
            nonceStore.storeEpochNonce(nonceState.getCurrentEpoch(), nonceState.getEpochNonce());
            nonceStore.pruneEpochNoncesAfter(nonceState.getCurrentEpoch());
            nonceStore.pruneEpochNonceCheckpointsAfter(nonceState.getCurrentEpoch());
            log.info("Nonce state restored at body tip: block={}, slot={}, reason={}",
                    bodyTip.getBlockNumber(), bodyTip.getSlot(), reason);
            return new RepairResult("latest", 0, snapshot);
        }

        Source source = chooseRepairSource(latest, bodyTip, genesisHash);
        if (source == null) {
            throw new IllegalStateException("Cannot repair epoch nonce state: no usable latest snapshot, "
                    + "epoch checkpoint, or complete block-body history is available up to body tip "
                    + describeTip(bodyTip));
        }

        source.restore(nonceState);
        long replayed = replayForward(nonceState, source.nextBlockNumber(), bodyTip.getBlockNumber());

        NonceStateSnapshot repaired = new NonceStateSnapshot(
                bodyTip.getSlot(),
                bodyTip.getBlockNumber(),
                bodyTip.getBlockHash(),
                nonceState.serialize());
        nonceState.saveCheckpoint(repaired.slot(), repaired.nonceState());
        nonceStore.storeLatestNonceSnapshot(repaired);
        nonceStore.storeEpochNonce(nonceState.getCurrentEpoch(), nonceState.getEpochNonce());
        nonceStore.pruneEpochNoncesAfter(nonceState.getCurrentEpoch());
        nonceStore.pruneEpochNonceCheckpointsAfter(nonceState.getCurrentEpoch());

        log.info("Nonce state repaired to body tip: source={}, replayedBlocks={}, bodyTip={}, reason={}",
                source.label(), replayed, describeTip(bodyTip), reason);
        return new RepairResult(source.label(), replayed, repaired);
    }

    private Source chooseRepairSource(Optional<NonceStateSnapshot> latest,
                                      ChainTip bodyTip,
                                      byte[] genesisHash) {
        if (latest.isPresent()) {
            NonceStateSnapshot snapshot = latest.get();
            if (snapshot.blockNumber() < bodyTip.getBlockNumber()
                    && sourceStillOnLocalChain(snapshot)) {
                return Source.snapshot("latest", snapshot);
            }
            log.info("Latest nonce snapshot is not usable for body tip repair: snapshot={}, bodyTip={}",
                    describeSnapshot(snapshot), describeTip(bodyTip));
        } else if (nonceStore.getEpochNonceState() != null) {
            log.warn("Legacy raw epoch nonce state found without a durable cursor; "
                    + "startup repair will use epoch checkpoints or replay from genesis");
        }

        for (NonceStateSnapshot checkpoint : nonceStore.getEpochNonceCheckpointsAtOrBeforeSlot(bodyTip.getSlot())) {
            if (checkpoint.blockNumber() <= bodyTip.getBlockNumber()
                    && sourceStillOnLocalChain(checkpoint)) {
                return Source.snapshot("epoch_checkpoint", checkpoint);
            }
            log.info("Skipping unusable nonce checkpoint during repair: {}", describeSnapshot(checkpoint));
        }

        if (genesisHash == null) {
            return null;
        }
        Long firstBlock = firstStoredBlockNumber();
        if (firstBlock == null) {
            return null;
        }
        if (firstBlock > 0) {
            throw new IllegalStateException("Cannot repair epoch nonce state from genesis: first stored block is "
                    + firstBlock + ", so earlier block bodies required for nonce replay are missing");
        }
        return Source.genesis(genesisHash);
    }

    private boolean sourceStillOnLocalChain(NonceStateSnapshot snapshot) {
        if (snapshot.isOrigin()) {
            return true;
        }
        Long storedSlot = chainState.getSlotByBlockNumber(snapshot.blockNumber());
        if (storedSlot == null || storedSlot != snapshot.slot()) {
            return false;
        }
        byte[] headerByHash = chainState.getBlockHeader(snapshot.blockHash());
        byte[] headerByNumber = chainState.getBlockHeaderByNumber(snapshot.blockNumber());
        if (headerByHash != null && headerByNumber != null
                && Arrays.equals(headerByHash, headerByNumber)) {
            return true;
        }
        byte[] blockBytes = chainState.getBlockByNumber(snapshot.blockNumber());
        if (blockBytes == null) {
            return false;
        }
        Era storedEra = chainState.getBlockEra(snapshot.blockNumber());
        if (StoredBlockUtil.isStoredByronBlock(storedEra, blockBytes)) {
            return true;
        }
        try {
            Block block = BlockSerializer.INSTANCE.deserialize(blockBytes);
            HeaderBody hb = block.getHeader() != null ? block.getHeader().getHeaderBody() : null;
            if (hb == null || hb.getBlockHash() == null) {
                return false;
            }
            return Arrays.equals(snapshot.blockHash(), HexUtil.decodeHexString(hb.getBlockHash()));
        } catch (Throwable t) {
            return false;
        }
    }

    private long replayForward(EpochNonceState nonceState, long startBlock, long tipBlock) {
        if (startBlock > tipBlock) {
            return 0;
        }

        long replayed = 0;
        for (long blockNumber = startBlock; blockNumber <= tipBlock; blockNumber++) {
            byte[] blockBytes = chainState.getBlockByNumber(blockNumber);
            if (blockBytes == null) {
                throw new IllegalStateException("Cannot repair epoch nonce state: missing local block body "
                        + blockNumber + " while replaying to " + tipBlock);
            }

            Era storedEra = chainState.getBlockEra(blockNumber);
            if (StoredBlockUtil.isStoredByronBlock(storedEra, blockBytes)) {
                continue;
            }

            Block block;
            try {
                block = BlockSerializer.INSTANCE.deserialize(blockBytes);
            } catch (Throwable t) {
                throw new RuntimeException("Cannot repair epoch nonce state: failed to deserialize block "
                        + blockNumber, t);
            }

            HeaderBody hb = block.getHeader() != null ? block.getHeader().getHeaderBody() : null;
            if (hb == null) {
                throw new IllegalStateException("Cannot repair epoch nonce state: block " + blockNumber
                        + " has no header body");
            }
            if (hb.getBlockNumber() != blockNumber) {
                throw new IllegalStateException("Cannot repair epoch nonce state: expected block "
                        + blockNumber + " but decoded block " + hb.getBlockNumber());
            }

            Era era = block.getEra() != null ? block.getEra() : storedEra;
            var result = evolver.evolve(nonceState, era, hb.getSlot(), hb);
            if (!result.applied()) {
                throw new IllegalStateException("Cannot repair epoch nonce state: block " + blockNumber
                        + " could not evolve nonce state: " + result.skipReason());
            }

            byte[] serialized = nonceState.serialize();
            nonceState.saveCheckpoint(hb.getSlot(), serialized);
            NonceStateSnapshot snapshot = NonceStateSnapshot.of(
                    hb.getSlot(), blockNumber, hb.getBlockHash(), serialized);

            if (result.epochTransition()) {
                nonceStore.storeEpochNonce(result.epochAfter(), nonceState.getEpochNonce());
                nonceStore.storeEpochNonceCheckpoint(result.epochAfter(), snapshot);
                log.info("Nonce replay stored epoch checkpoint: epoch={}, block={}, slot={}",
                        result.epochAfter(), blockNumber, hb.getSlot());
            }

            replayed++;
            if (replayed % 10_000 == 0) {
                log.info("Nonce replay progress: block {}/{} ({} replayed)",
                        blockNumber, tipBlock, replayed);
            }
        }
        return replayed;
    }

    private Long firstStoredBlockNumber() {
        Point first = chainState.getFirstBlock();
        if (first == null) {
            return null;
        }
        return chainState.getBlockNumberBySlot(first.getSlot());
    }

    private static String describeTip(ChainTip tip) {
        if (tip == null) return "null";
        return "block=" + tip.getBlockNumber()
                + ", slot=" + tip.getSlot()
                + ", hash=" + HexUtil.encodeHexString(tip.getBlockHash());
    }

    private static String describeSnapshot(NonceStateSnapshot snapshot) {
        if (snapshot == null) return "null";
        return "block=" + snapshot.blockNumber()
                + ", slot=" + snapshot.slot()
                + ", hash=" + snapshot.blockHashHex();
    }

    public record RepairResult(String source, long replayedBlocks, NonceStateSnapshot snapshot) {
    }

    private sealed interface Source permits SnapshotSource, GenesisSource {
        String label();
        long nextBlockNumber();
        void restore(EpochNonceState nonceState);

        static Source snapshot(String label, NonceStateSnapshot snapshot) {
            return new SnapshotSource(label, snapshot);
        }

        static Source genesis(byte[] genesisHash) {
            return new GenesisSource(genesisHash);
        }
    }

    private record SnapshotSource(String label, NonceStateSnapshot snapshot) implements Source {
        @Override
        public long nextBlockNumber() {
            return snapshot.blockNumber() + 1;
        }

        @Override
        public void restore(EpochNonceState nonceState) {
            nonceState.restore(snapshot.nonceState());
            nonceState.saveCheckpoint(snapshot.slot(), snapshot.nonceState());
        }
    }

    private record GenesisSource(byte[] genesisHash) implements Source {
        @Override
        public String label() {
            return "genesis";
        }

        @Override
        public long nextBlockNumber() {
            return 0;
        }

        @Override
        public void restore(EpochNonceState nonceState) {
            nonceState.initFromGenesisHash(genesisHash);
        }
    }
}
