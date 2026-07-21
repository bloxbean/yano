package com.bloxbean.cardano.yano.api.appchain.evidence;

import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;

import java.util.List;

/**
 * Portable signed evidence that a message was finalized on an app chain —
 * and, when anchored, associates that signed block segment with a
 * claimed Cardano L1 anchor reference (ADR app-layer/006 E3.4). Verify the signed history offline
 * with {@link EvidenceVerifier} against an independently pinned trust
 * context. An anchored authenticity claim additionally requires fetching the
 * Cardano transaction and matching its script output and inline datum; the
 * bundle alone does not prove what the L1 transaction contains.
 *
 * @param chainId       app-chain identity
 * @param messageIdHex  the message this bundle is evidence for
 * @param blocks        finalized blocks from the message's block up to (and
 *                      including) the anchored block, in ascending height —
 *                      each independently cert-verified and prev-hash chained.
 *                      When not anchored, a single block (the one containing
 *                      the message)
 * @param memberKeysHex the group's member public keys (to verify the certs)
 *                      as claimed by the bundle; callers must compare them to
 *                      an independently trusted membership context
 * @param threshold     the chain's finality threshold (m-of-n); a bundle is
 *                      only valid if each block carries at least this many
 *                      distinct valid member signatures
 * @param anchor        the L1 anchor reference, or null when not yet anchored
 */
public record EvidenceBundle(String chainId,
                             String messageIdHex,
                             List<AppBlock> blocks,
                             List<String> memberKeysHex,
                             int threshold,
                             AnchorRef anchor) {
    /** Maximum anchored block segment carried by the portable v1 envelope. */
    public static final int MAX_BLOCKS = 4_096;
    /**
     * Maximum cumulative canonical block bytes in one portable envelope.
     * Hex JSON roughly doubles this value and therefore remains below the
     * codec's 40 MiB document limit. The cap also guarantees that a single
     * valid maximum-size v1 block remains exportable.
     */
    public static final long MAX_TOTAL_BLOCK_CBOR_BYTES = AppChainConfig.MAX_BLOCK_BYTES;

    public EvidenceBundle {
        blocks = blocks != null ? List.copyOf(blocks) : List.of();
        memberKeysHex = memberKeysHex != null ? List.copyOf(memberKeysHex) : List.of();
    }

    /**
     * Unsigned L1 anchor reference carried by the bundle. The offline verifier
     * checks only that its claimed block hash matches the signed segment's
     * last block; it does not authenticate the transaction hash or slot. An auditor
     * fetches {@code txHash}, confirms its actual slot is {@code l1Slot}, and
     * decodes the unique state-thread script output's inline datum. That datum
     * must bind the exact chain, height, block hash, state root, membership,
     * and threshold under the expected script identity.
     */
    public record AnchorRef(long anchoredHeight,
                            String anchoredBlockHashHex,
                            String txHash,
                            long l1Slot) {
    }
}
