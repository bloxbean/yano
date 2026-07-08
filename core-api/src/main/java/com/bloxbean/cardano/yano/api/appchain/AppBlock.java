package com.bloxbean.cardano.yano.api.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;

import java.util.List;
import java.util.Objects;

/**
 * One finalized (or proposed) app-chain block: a batch of verified app messages
 * given a total order by the sequencer, binding the post-state commitment root.
 * See adr/app-layer/005 §4.3 and cddl/appchain/app-block.cddl.
 *
 * @param version      block format version (currently 1)
 * @param chainId      app-chain identity
 * @param height       global per-chain sequence (genesis app block = 1)
 * @param prevHash     hash of the previous app block (32 zero bytes at height 1)
 * @param l1Slot       stable L1 reference slot observed by the proposer (0 if unavailable)
 * @param l1BlockHash  stable L1 reference block hash (empty if unavailable)
 * @param timestamp    proposer wall clock, unix millis
 * @param messagesRoot merkle root over the ordered message ids
 * @param stateRoot    state commitment root AFTER applying this block
 * @param messages     ordered full message envelopes
 * @param proposer     proposer's Ed25519 public key
 * @param cert         finality certificate (empty signatures while proposed)
 */
public record AppBlock(int version,
                       String chainId,
                       long height,
                       byte[] prevHash,
                       long l1Slot,
                       byte[] l1BlockHash,
                       long timestamp,
                       byte[] messagesRoot,
                       byte[] stateRoot,
                       List<AppMessage> messages,
                       byte[] proposer,
                       FinalityCert cert) {

    public static final int BLOCK_VERSION = 1;
    public static final byte[] GENESIS_PREV_HASH = new byte[32];

    public AppBlock {
        Objects.requireNonNull(chainId, "chainId");
        Objects.requireNonNull(prevHash, "prevHash");
        Objects.requireNonNull(messagesRoot, "messagesRoot");
        Objects.requireNonNull(stateRoot, "stateRoot");
        messages = messages != null ? List.copyOf(messages) : List.of();
        Objects.requireNonNull(proposer, "proposer");
        cert = cert != null ? cert : FinalityCert.empty();
    }

    public AppBlock withCert(FinalityCert newCert) {
        return new AppBlock(version, chainId, height, prevHash, l1Slot, l1BlockHash,
                timestamp, messagesRoot, stateRoot, messages, proposer, newCert);
    }
}
