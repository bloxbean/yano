package com.bloxbean.cardano.yano.api.appchain.evidence;

import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Offline verifier for an {@link EvidenceBundle} (ADR app-layer/006 E3.4).
 * Pure Java, no node access: recomputes block hashes and merkle roots, verifies
 * each finality certificate against a caller-pinned member set, confirms the
 * message id is in its block, and (when anchored) that the block chains by
 * prev-hash to the claimed anchored block. A retained full envelope also has
 * its message id recomputed from content; a canonical retention tombstone
 * proves only signed message-id inclusion and reports
 * {@link Result#messageContentVerified()} as false. Exact L1 script-output and
 * inline-datum verification remains the auditor's separate responsibility.
 *
 * <p>{@link #verifyInternalConsistencyAgainstDeclaredMembers(EvidenceBundle)}
 * checks only internal consistency against membership claimed by the bundle.
 * It is deliberately not named {@code verify}: authenticity callers must use
 * an overload that supplies independently trusted chain identity, membership,
 * and threshold.</p>
 */
public final class EvidenceVerifier {

    private EvidenceVerifier() {
    }

    /**
     * Verifies internal consistency against the bundle-declared member set.
     * This cannot detect an attacker substituting both blocks and membership.
     * Use {@link #verify(EvidenceBundle, TrustContext)} at trust boundaries.
     */
    public static Result verifyInternalConsistencyAgainstDeclaredMembers(
            EvidenceBundle bundle) {
        try {
            return verifyStrict(bundle);
        } catch (RuntimeException malformed) {
            return Result.fail("malformed bundle");
        }
    }

    /** Verifies a bundle against an independently supplied exact trust context. */
    public static Result verify(EvidenceBundle bundle, TrustContext expected) {
        if (expected == null) {
            return Result.fail("missing trust context");
        }
        try {
            if (bundle == null || !expected.chainId().equals(bundle.chainId())
                    || bundle.threshold() != expected.threshold()) {
                return Result.fail("bundle trust context mismatch");
            }
            Set<String> claimedMembers = canonicalMemberSet(bundle.memberKeysHex());
            if (claimedMembers == null || claimedMembers.size() != bundle.memberKeysHex().size()
                    || !claimedMembers.equals(expected.memberKeysHex())) {
                return Result.fail("bundle trust context mismatch");
            }
            return verifyStrict(bundle);
        } catch (RuntimeException malformed) {
            return Result.fail("malformed bundle");
        }
    }

    /** Convenience overload for callers that do not retain a context object. */
    public static Result verify(EvidenceBundle bundle, String expectedChainId,
                                Set<String> expectedMemberKeysHex, int expectedThreshold) {
        try {
            return verify(bundle, new TrustContext(expectedChainId,
                    expectedMemberKeysHex, expectedThreshold));
        } catch (RuntimeException invalidContext) {
            return Result.fail("invalid trust context");
        }
    }

    private static Result verifyStrict(EvidenceBundle bundle) {
        if (bundle == null || !validChainId(bundle.chainId())) {
            return Result.fail("missing chain identity");
        }
        List<AppBlock> blocks = bundle.blocks();
        if (blocks.isEmpty() || blocks.size() > EvidenceBundle.MAX_BLOCKS) {
            return Result.fail("bundle has no blocks");
        }
        if (bundle.messageIdHex() == null
                || !bundle.messageIdHex().matches("[0-9a-f]{64}")) {
            return Result.fail("invalid message identity");
        }
        Set<String> members = canonicalMemberSet(bundle.memberKeysHex());
        int threshold = bundle.threshold();
        if (members == null || members.size() > AppChainConfig.MAX_MEMBERS
                || members.size() != bundle.memberKeysHex().size()
                || threshold < 1 || threshold > members.size()) {
            return Result.fail("invalid member threshold");
        }

        // The envelope is meaningful only when every signed header belongs to
        // one supported, strictly consecutive chain segment. Without these
        // checks, individually valid blocks from another chain or height gap
        // could be presented as one portable history.
        AppBlock first = blocks.getFirst();
        AppBlock last = blocks.getLast();
        if (first == null || first.height() < 1) {
            return Result.fail("invalid first block height");
        }
        if (bundle.anchor() == null && blocks.size() != 1) {
            return Result.fail("unanchored bundle must contain one block");
        }
        if (bundle.anchor() != null
                && (bundle.anchor().anchoredHeight() != last.height()
                || bundle.anchor().anchoredHeight() < first.height()
                || bundle.anchor().l1Slot() < 0
                || bundle.anchor().anchoredBlockHashHex() == null
                || !bundle.anchor().anchoredBlockHashHex().matches("[0-9a-f]{64}")
                || bundle.anchor().txHash() == null
                || !bundle.anchor().txHash().matches("[0-9a-f]{64}"))) {
            return Result.fail("anchor height relationship mismatch");
        }

        // 1. The message is included in the first (its own) block, and that
        //    block's messages-root matches its message list.
        AppBlock target = first;
        if (target.messages() == null
                || target.messages().size() > AppChainConfig.MAX_BLOCK_MESSAGES) {
            return Result.fail("app-block exceeds v1 work bounds");
        }
        byte[] messageId = HexUtil.decodeHexString(bundle.messageIdHex());
        long targetOccurrences = target.messages().stream()
                .filter(m -> m != null && Arrays.equals(m.getMessageId(), messageId))
                .count();
        if (targetOccurrences != 1) {
            return Result.fail("message not in its block");
        }

        // 2. Every block is validly finalized: at least `threshold` distinct
        //    valid member signatures over the block hash, and the prev-hash
        //    chain is intact. The m-of-n threshold rejects a single member
        //    only when the caller independently pins membership and threshold.
        byte[] previousHash = null;
        long previousHeight = 0;
        int minCertSignatures = Integer.MAX_VALUE;
        boolean messageContentVerified = false;
        long totalBlockBytes = 0;
        Set<String> segmentMessageIds = new HashSet<>();
        for (AppBlock block : blocks) {
            if (block == null || block.version() != AppBlock.BLOCK_VERSION) {
                return Result.fail("unsupported app-block version");
            }
            int encodedBlockBytes = serializedBlockBytes(block);
            if (block.messages() == null
                    || block.messages().size() > AppChainConfig.MAX_BLOCK_MESSAGES
                    || encodedBlockBytes < 0
                    || EvidenceBundleCodec.exceedsTotalBlockBudget(
                    totalBlockBytes, encodedBlockBytes)) {
                return Result.fail("app-block exceeds v1 work bounds");
            }
            totalBlockBytes += encodedBlockBytes;
            if (!bundle.chainId().equals(block.chainId())) {
                return Result.fail("app-block chain-id mismatch");
            }
            if (block.height() < 1
                    || previousHash != null && (previousHeight == Long.MAX_VALUE
                    || block.height() != previousHeight + 1)) {
                return Result.fail("app-block heights are not consecutive");
            }
            if (block.prevHash() == null || block.prevHash().length != 32
                    || block.messagesRoot() == null || block.messagesRoot().length != 32
                    || block.stateRoot() == null || block.stateRoot().length != 32
                    || block.proposer() == null || block.proposer().length != 32
                    || block.l1Slot() < 0 || block.timestamp() < 0
                    || block.l1BlockHash() == null
                    || block.l1Slot() == 0 && block.l1BlockHash().length != 0
                    || block.l1Slot() > 0 && block.l1BlockHash().length != 32) {
                return Result.fail("invalid app-block header profile");
            }
            if (block.cert().scheme() != FinalityCert.SCHEME_ED25519) {
                return Result.fail("unsupported finality certificate scheme");
            }
            for (AppMessage message : block.messages()) {
                if (message == null || message.getMessageId() == null
                        || message.getMessageId().length != 32
                        || !segmentMessageIds.add(
                        HexUtil.encodeHexString(message.getMessageId()))) {
                    return Result.fail("duplicate or malformed message identity at height "
                            + block.height());
                }
                boolean tombstone = isCanonicalRetentionTombstone(
                        message, block.chainId(), members);
                boolean contentVerified = !tombstone
                        && validFullMessage(message, block.chainId(), members);
                if (!tombstone && !contentVerified) {
                    return Result.fail("invalid app message at height " + block.height());
                }
                if (block == target && Arrays.equals(message.getMessageId(), messageId)) {
                    messageContentVerified = contentVerified;
                }
            }
            if (!Arrays.equals(AppBlockCodec.messagesRoot(block.messages()),
                    block.messagesRoot())) {
                return Result.fail("messages-root mismatch at height " + block.height());
            }
            byte[] blockHash = AppBlockCodec.blockHash(block);
            if (previousHash != null && !Arrays.equals(block.prevHash(), previousHash)) {
                return Result.fail("prev-hash chain broken at height " + block.height());
            }
            if (previousHash == null && block.height() == 1
                    && !Arrays.equals(block.prevHash(), AppBlock.GENESIS_PREV_HASH)) {
                return Result.fail("genesis prev-hash mismatch");
            }
            int validSignatures = countValidSignatures(block.cert(), blockHash, members);
            if (validSignatures < threshold) {
                return Result.fail("block " + block.height() + " has " + validSignatures
                        + " valid member signature(s), below threshold " + threshold);
            }
            minCertSignatures = Math.min(minCertSignatures, validSignatures);
            previousHash = blockHash;
            previousHeight = block.height();
        }

        // 3. Anchor linkage (optional): the last block's hash equals the
        //    anchored block hash the auditor will confirm on L1.
        boolean anchored = false;
        String anchorTxHash = null;
        if (bundle.anchor() != null) {
            byte[] lastHash = AppBlockCodec.blockHash(last);
            byte[] anchoredHash = HexUtil.decodeHexString(bundle.anchor().anchoredBlockHashHex());
            if (!Arrays.equals(lastHash, anchoredHash)) {
                return Result.fail("anchor block-hash mismatch");
            }
            anchored = true;
            anchorTxHash = bundle.anchor().txHash();
        }

        return new Result(true, null, minCertSignatures, anchored, anchorTxHash,
                messageContentVerified);
    }

    private static boolean validFullMessage(AppMessage message, String chainId,
                                            Set<String> members) {
        if (!validMessageStructure(message, chainId, members)
                || message.getAuthProof().length
                != AppChainConfig.ED25519_SIGNATURE_BYTES
                || !message.hasValidMessageId()) {
            return false;
        }
        try {
            return CryptoConfiguration.INSTANCE.getSigningProvider().verify(
                    message.getAuthProof(), message.signedBodyBytes(), message.getSender());
        } catch (Exception invalidSignature) {
            return false;
        }
    }

    private static boolean isCanonicalRetentionTombstone(AppMessage message,
                                                          String chainId,
                                                          Set<String> members) {
        return validMessageStructure(message, chainId, members)
                && message.getBody().length == 0
                && message.getAuthProof().length == 0;
    }

    private static boolean validMessageStructure(AppMessage message, String chainId,
                                                 Set<String> members) {
        return message != null
                && message.getVersion() == AppMessage.ENVELOPE_VERSION
                && message.getMessageId() != null && message.getMessageId().length == 32
                && chainId.equals(message.getChainId())
                && validTopic(message.getTopic())
                && message.getSender() != null && message.getSender().length == 32
                && members.contains(HexUtil.encodeHexString(message.getSender())
                .toLowerCase(Locale.ROOT))
                && message.getSenderSeq() >= 0 && message.getExpiresAt() >= 0
                && message.getBody() != null
                && message.getBody().length <= AppChainConfig.MAX_MESSAGE_BYTES
                && message.getAuthScheme() == FinalityCert.SCHEME_ED25519
                && message.getAuthProof() != null
                && (message.getAuthProof().length == 0
                || message.getAuthProof().length
                == AppChainConfig.ED25519_SIGNATURE_BYTES);
    }

    private static int countValidSignatures(FinalityCert cert, byte[] blockHash, Set<String> members) {
        if (cert == null || cert.signatures().isEmpty()
                || cert.signatures().size() > AppChainConfig.MAX_MEMBERS) {
            return -1;
        }
        Set<String> seen = new HashSet<>();
        int valid = 0;
        for (FinalityCert.Signature signature : cert.signatures()) {
            if (signature == null || signature.signer() == null
                    || signature.signer().length != 32
                    || signature.signature() == null
                    || signature.signature().length
                    != AppChainConfig.ED25519_SIGNATURE_BYTES) {
                return -1;
            }
            String signer = HexUtil.encodeHexString(signature.signer()).toLowerCase(Locale.ROOT);
            if (!members.contains(signer) || !seen.add(signer)) {
                return -1;
            }
            try {
                if (!CryptoConfiguration.INSTANCE.getSigningProvider()
                        .verify(signature.signature(), blockHash, signature.signer())) {
                    return -1;
                }
                valid++;
            } catch (Exception ignored) {
                return -1;
            }
        }
        return valid;
    }

    private static boolean validTopic(String topic) {
        return topic != null && topic.indexOf('\0') < 0
                && StandardCharsets.UTF_8.newEncoder().canEncode(topic)
                && topic.getBytes(StandardCharsets.UTF_8).length
                <= AppChainConfig.MAX_TOPIC_BYTES;
    }

    private static int serializedBlockBytes(AppBlock block) {
        if (block == null || block.messages() == null) {
            return -1;
        }
        long lowerBound = 1_024L;
        for (AppMessage message : block.messages()) {
            if (message == null || message.getBody() == null
                    || message.getAuthProof() == null || message.getTopic() == null) {
                return -1;
            }
            lowerBound += message.getBody().length;
            lowerBound += message.getAuthProof().length;
            lowerBound += message.getTopic().length();
            if (lowerBound > AppChainConfig.MAX_BLOCK_BYTES) {
                return -1;
            }
        }
        try {
            int encoded = AppBlockCodec.serialize(block).length;
            return encoded <= AppChainConfig.MAX_BLOCK_BYTES ? encoded : -1;
        } catch (RuntimeException malformed) {
            return -1;
        }
    }

    private static Set<String> canonicalMemberSet(List<String> keys) {
        if (keys == null || keys.isEmpty() || keys.size() > AppChainConfig.MAX_MEMBERS) {
            return null;
        }
        Set<String> members = new HashSet<>();
        for (String key : keys) {
            if (key == null || !key.matches("[0-9a-f]{64}") || !members.add(key)) {
                return null;
            }
        }
        return Set.copyOf(members);
    }

    private static boolean validChainId(String chainId) {
        return chainId != null && !chainId.isBlank() && chainId.indexOf('\0') < 0
                && StandardCharsets.UTF_8.newEncoder().canEncode(chainId)
                && chainId.getBytes(StandardCharsets.UTF_8).length
                <= AppChainConfig.MAX_CHAIN_ID_BYTES;
    }

    /** Out-of-band trust anchor for one fixed app-chain membership epoch. */
    public record TrustContext(String chainId, Set<String> memberKeysHex, int threshold) {
        public TrustContext {
            if (!validChainId(chainId)) {
                throw new IllegalArgumentException("chainId is required");
            }
            Set<String> canonical = canonicalMemberSet(
                    memberKeysHex == null ? null : List.copyOf(memberKeysHex));
            if (canonical == null || canonical.size() != memberKeysHex.size()
                    || threshold < 1 || threshold > canonical.size()) {
                throw new IllegalArgumentException("invalid trusted membership");
            }
            memberKeysHex = canonical;
        }
    }

    /**
     * @param valid            all structural + crypto checks passed
     * @param failure          reason when not valid
     * @param certSignatures   min valid member signatures across the verified blocks
     * @param anchoredToL1      the signed segment matches the bundle's claimed anchor block hash;
     *                          this does not authenticate the unsigned transaction reference
     * @param anchorTxHash      claimed anchor tx to verify independently on Cardano
     *                          (null when the bundle has no anchor reference)
     * @param messageContentVerified true when the target envelope body was retained and its
     *                               message id was recomputed; false for a signed retention tombstone
     */
    public record Result(boolean valid, String failure, int certSignatures,
                         boolean anchoredToL1, String anchorTxHash,
                         boolean messageContentVerified) {
        static Result fail(String reason) {
            return new Result(false, reason, 0, false, null, false);
        }
    }
}
