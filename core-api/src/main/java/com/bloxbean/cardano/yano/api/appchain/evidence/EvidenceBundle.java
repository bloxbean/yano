package com.bloxbean.cardano.yano.api.appchain.evidence;

import com.bloxbean.cardano.yano.api.appchain.AppBlock;

import java.util.List;

/**
 * A portable, self-contained proof that a message was finalized on an app
 * chain — and, when anchored, that it chains to a Cardano L1 transaction
 * (ADR app-layer/006 E3.4). Verify offline with {@link EvidenceVerifier};
 * no access to the node is required.
 *
 * @param chainId       app-chain identity
 * @param messageIdHex  the message this bundle is evidence for
 * @param blocks        finalized blocks from the message's block up to (and
 *                      including) the anchored block, in ascending height —
 *                      each independently cert-verified and prev-hash chained.
 *                      When not anchored, a single block (the one containing
 *                      the message)
 * @param memberKeysHex the group's member public keys (to verify the certs)
 * @param anchor        the L1 anchor reference, or null when not yet anchored
 */
public record EvidenceBundle(String chainId,
                             String messageIdHex,
                             List<AppBlock> blocks,
                             List<String> memberKeysHex,
                             AnchorRef anchor) {

    public EvidenceBundle {
        blocks = blocks != null ? List.copyOf(blocks) : List.of();
        memberKeysHex = memberKeysHex != null ? List.copyOf(memberKeysHex) : List.of();
    }

    /**
     * L1 anchor reference: the app-block hash at {@code anchoredHeight} was
     * committed as metadata by {@code txHash} at {@code l1Slot}. An auditor
     * fetches {@code txHash} from Cardano and confirms its metadata carries
     * {@code anchoredBlockHashHex}.
     */
    public record AnchorRef(long anchoredHeight,
                            String anchoredBlockHashHex,
                            String txHash,
                            long l1Slot) {
    }
}
