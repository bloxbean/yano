package com.bloxbean.cardano.yano.api.appchain.evidence;

import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Offline verifier for an {@link EvidenceBundle} (ADR app-layer/006 E3.4).
 * Pure Java, no node access: recomputes block hashes and merkle roots, verifies
 * each finality certificate against the member set, confirms the message is in
 * its block, and (when anchored) that the block chains by prev-hash to the
 * anchored block — leaving only the L1 tx lookup to the auditor.
 */
public final class EvidenceVerifier {

    private EvidenceVerifier() {
    }

    public static Result verify(EvidenceBundle bundle) {
        List<AppBlock> blocks = bundle.blocks();
        if (blocks.isEmpty()) {
            return Result.fail("bundle has no blocks");
        }
        Set<String> members = new HashSet<>();
        for (String key : bundle.memberKeysHex()) {
            members.add(key.toLowerCase(Locale.ROOT));
        }

        // 1. The message is included in the first (its own) block, and that
        //    block's messages-root matches its message list.
        AppBlock target = blocks.get(0);
        byte[] messageId = HexUtil.decodeHexString(bundle.messageIdHex());
        boolean included = target.messages().stream()
                .anyMatch(m -> Arrays.equals(m.getMessageId(), messageId));
        if (!included) {
            return Result.fail("message not in its block");
        }
        if (!Arrays.equals(AppBlockCodec.messagesRoot(target.messages()), target.messagesRoot())) {
            return Result.fail("messages-root mismatch in target block");
        }

        // 2. Every block is validly finalized: at least `threshold` distinct
        //    valid member signatures over the block hash, and the prev-hash
        //    chain is intact. Enforcing the m-of-n threshold (not merely >=1)
        //    is what makes the bundle unforgeable by a single member.
        int threshold = Math.max(1, bundle.threshold());
        byte[] previousHash = null;
        int minCertSignatures = Integer.MAX_VALUE;
        for (AppBlock block : blocks) {
            byte[] blockHash = AppBlockCodec.blockHash(block);
            if (previousHash != null && !Arrays.equals(block.prevHash(), previousHash)) {
                return Result.fail("prev-hash chain broken at height " + block.height());
            }
            int validSignatures = countValidSignatures(block.cert(), blockHash, members);
            if (validSignatures < threshold) {
                return Result.fail("block " + block.height() + " has " + validSignatures
                        + " valid member signature(s), below threshold " + threshold);
            }
            minCertSignatures = Math.min(minCertSignatures, validSignatures);
            previousHash = blockHash;
        }

        // 3. Anchor linkage (optional): the last block's hash equals the
        //    anchored block hash the auditor will confirm on L1.
        boolean anchored = false;
        String anchorTxHash = null;
        if (bundle.anchor() != null) {
            byte[] lastHash = AppBlockCodec.blockHash(blocks.get(blocks.size() - 1));
            byte[] anchoredHash = HexUtil.decodeHexString(bundle.anchor().anchoredBlockHashHex());
            if (!Arrays.equals(lastHash, anchoredHash)) {
                return Result.fail("anchor block-hash mismatch");
            }
            anchored = true;
            anchorTxHash = bundle.anchor().txHash();
        }

        return new Result(true, null, minCertSignatures, anchored, anchorTxHash);
    }

    private static int countValidSignatures(FinalityCert cert, byte[] blockHash, Set<String> members) {
        Set<String> seen = new HashSet<>();
        int valid = 0;
        for (FinalityCert.Signature signature : cert.signatures()) {
            String signer = HexUtil.encodeHexString(signature.signer()).toLowerCase(Locale.ROOT);
            if (!members.contains(signer) || !seen.add(signer)) {
                continue;
            }
            try {
                if (CryptoConfiguration.INSTANCE.getSigningProvider()
                        .verify(signature.signature(), blockHash, signature.signer())) {
                    valid++;
                }
            } catch (Exception ignored) {
                // treat as invalid
            }
        }
        return valid;
    }

    /**
     * @param valid            all structural + crypto checks passed
     * @param failure          reason when not valid
     * @param certSignatures   min valid member signatures across the verified blocks
     * @param anchoredToL1      the evidence chains to an L1 anchor tx
     * @param anchorTxHash      the anchor tx to confirm on Cardano (null if not anchored)
     */
    public record Result(boolean valid, String failure, int certSignatures,
                         boolean anchoredToL1, String anchorTxHash) {
        static Result fail(String reason) {
            return new Result(false, reason, 0, false, null);
        }
    }
}
