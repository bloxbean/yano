package com.bloxbean.cardano.yano.api.appchain;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import com.bloxbean.cardano.yano.api.appchain.consensus.ConsensusDigests;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Complete current-format header commitment for offline finality verification.
 * Carries the justification digest, not its potentially large preimage. This
 * authenticates a certified header; it does not validate proposal justification.
 */
public record AppBlockHeader(int version, String chainId, long height,
                             byte[] consensusContextDigest, long view, byte[] prevHash,
                             long l1Slot, byte[] l1BlockHash, long timestamp,
                             byte[] messagesRoot, byte[] stateRoot, byte[] proposer,
                             byte[] justificationDigest) {
    public AppBlockHeader {
        Objects.requireNonNull(chainId, "chainId");
        if (version != AppBlock.BLOCK_VERSION || chainId.isBlank() || chainId.indexOf('\0') >= 0
                || !StandardCharsets.UTF_8.newEncoder().canEncode(chainId)
                || chainId.getBytes(StandardCharsets.UTF_8).length > AppChainConfig.MAX_CHAIN_ID_BYTES
                || height < 1 || view < 0 || l1Slot < 0 || timestamp < 0) {
            throw new IllegalArgumentException("Invalid certified app-block header");
        }
        consensusContextDigest = fixed(consensusContextDigest);
        prevHash = fixed(prevHash);
        messagesRoot = fixed(messagesRoot);
        stateRoot = fixed(stateRoot);
        proposer = fixed(proposer);
        justificationDigest = fixed(justificationDigest);
        l1BlockHash = Objects.requireNonNull(l1BlockHash, "l1BlockHash").clone();
        if (l1BlockHash.length != 0 && l1BlockHash.length != 32) {
            throw new IllegalArgumentException("Invalid L1 block hash");
        }
    }

    public static AppBlockHeader from(AppBlock block) {
        Objects.requireNonNull(block, "block");
        return new AppBlockHeader(block.version(), block.chainId(), block.height(), block.consensusContextDigest(),
                block.view(), block.prevHash(), block.l1Slot(), block.l1BlockHash(), block.timestamp(),
                block.messagesRoot(), block.stateRoot(), block.proposer(),
                Blake2bUtil.blake2bHash256(block.justification()));
    }

    public byte[] blockHash() { return AppBlockCodec.blockHash(this); }
    public byte[] valueHash() { return AppBlockCodec.valueHash(this); }
    public byte[] commitDigest() { return ConsensusDigests.commit(this); }

    @Override public byte[] consensusContextDigest() { return consensusContextDigest.clone(); }
    @Override public byte[] prevHash() { return prevHash.clone(); }
    @Override public byte[] l1BlockHash() { return l1BlockHash.clone(); }
    @Override public byte[] messagesRoot() { return messagesRoot.clone(); }
    @Override public byte[] stateRoot() { return stateRoot.clone(); }
    @Override public byte[] proposer() { return proposer.clone(); }
    @Override public byte[] justificationDigest() { return justificationDigest.clone(); }

    private static byte[] fixed(byte[] value) {
        if (value == null || value.length != 32) throw new IllegalArgumentException("Expected 32-byte header field");
        return value.clone();
    }
}
