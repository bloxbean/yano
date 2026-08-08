package com.bloxbean.cardano.yano.api.appchain.evidence;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Compact inclusion path for the ordered message-id tree committed by one app block. */
public record MessageInclusionProof(
        int schemaVersion,
        String treeId,
        String chainId,
        long blockHeight,
        byte[] blockHash,
        byte[] messagesRoot,
        byte[] messageId,
        int messageIndex,
        int leafCount,
        List<byte[]> siblings
) {
    public static final int SCHEMA_VERSION = 1;
    public static final String TREE_ID = "binary-merkle-blake2b256-v1";

    public MessageInclusionProof {
        if (schemaVersion != SCHEMA_VERSION || !TREE_ID.equals(treeId)) {
            throw new IllegalArgumentException("unsupported message inclusion proof profile");
        }
        chainId = Objects.requireNonNull(chainId, "chainId");
        if (chainId.isBlank() || !chainId.equals(chainId.trim()) || blockHeight < 1) {
            throw new IllegalArgumentException("invalid message proof block identity");
        }
        blockHash = exact32(blockHash, "blockHash");
        messagesRoot = exact32(messagesRoot, "messagesRoot");
        messageId = exact32(messageId, "messageId");
        if (leafCount < 1 || leafCount > AppChainConfig.MAX_BLOCK_MESSAGES
                || messageIndex < 0 || messageIndex >= leafCount) {
            throw new IllegalArgumentException("invalid message proof position");
        }
        siblings = immutablePath(siblings, pathLength(leafCount));
    }

    @Override public byte[] blockHash() { return blockHash.clone(); }
    @Override public byte[] messagesRoot() { return messagesRoot.clone(); }
    @Override public byte[] messageId() { return messageId.clone(); }
    @Override public List<byte[]> siblings() {
        return siblings.stream().map(byte[]::clone).toList();
    }

    /** Creates the path for the unique matching message id in a finalized/candidate block. */
    public static Optional<MessageInclusionProof> fromBlock(AppBlock block, byte[] messageId) {
        Objects.requireNonNull(block, "block");
        byte[] target = exact32(messageId, "messageId");
        int found = -1;
        for (int index = 0; index < block.messages().size(); index++) {
            if (Arrays.equals(target, block.messages().get(index).getMessageId())) {
                if (found >= 0) {
                    throw new IllegalArgumentException("block contains a duplicate message id");
                }
                found = index;
            }
        }
        if (found < 0) return Optional.empty();
        List<byte[]> ids = block.messages().stream()
                .map(AppMessage::getMessageId).map(byte[]::clone).toList();
        List<byte[]> siblings = path(ids, found);
        MessageInclusionProof proof = new MessageInclusionProof(
                SCHEMA_VERSION, TREE_ID, block.chainId(), block.height(),
                AppBlockCodec.blockHash(block), block.messagesRoot(), target,
                found, ids.size(), siblings);
        if (!proof.verifiesRoot()) {
            throw new IllegalStateException("block messages root differs from its message ids");
        }
        return Optional.of(proof);
    }

    /** Verifies the path, including the current odd-leaf duplication rule. */
    public boolean verifiesRoot() {
        byte[] node = messageId.clone();
        int index = messageIndex;
        int width = leafCount;
        for (byte[] sibling : siblings) {
            boolean duplicatedLast = (width & 1) == 1 && index == width - 1;
            if (duplicatedLast && !Arrays.equals(node, sibling)) return false;
            node = (index & 1) == 0
                    ? parent(node, sibling)
                    : parent(sibling, node);
            index /= 2;
            width = (width + 1) / 2;
        }
        return width == 1 && Arrays.equals(node, messagesRoot);
    }

    /** Verifies the path and all independently expected block/message identities. */
    public boolean verifies(
            String expectedChainId,
            long expectedHeight,
            byte[] expectedBlockHash,
            byte[] expectedMessagesRoot,
            byte[] expectedMessageId
    ) {
        return chainId.equals(expectedChainId)
                && blockHeight == expectedHeight
                && Arrays.equals(blockHash, expectedBlockHash)
                && Arrays.equals(messagesRoot, expectedMessagesRoot)
                && Arrays.equals(messageId, expectedMessageId)
                && verifiesRoot();
    }

    private static List<byte[]> path(List<byte[]> leaves, int targetIndex) {
        List<byte[]> level = leaves.stream().map(byte[]::clone).collect(
                java.util.stream.Collectors.toCollection(ArrayList::new));
        List<byte[]> result = new ArrayList<>(pathLength(level.size()));
        int index = targetIndex;
        while (level.size() > 1) {
            int siblingIndex = (index & 1) == 0
                    ? Math.min(index + 1, level.size() - 1) : index - 1;
            result.add(level.get(siblingIndex).clone());
            List<byte[]> next = new ArrayList<>((level.size() + 1) / 2);
            for (int position = 0; position < level.size(); position += 2) {
                byte[] left = level.get(position);
                byte[] right = position + 1 < level.size()
                        ? level.get(position + 1) : left;
                next.add(parent(left, right));
            }
            level = next;
            index /= 2;
        }
        return result;
    }

    private static byte[] parent(byte[] left, byte[] right) {
        byte[] input = new byte[64];
        System.arraycopy(left, 0, input, 0, 32);
        System.arraycopy(right, 0, input, 32, 32);
        return Blake2bUtil.blake2bHash256(input);
    }

    private static int pathLength(int leafCount) {
        int result = 0;
        for (int width = leafCount; width > 1; width = (width + 1) / 2) result++;
        return result;
    }

    private static List<byte[]> immutablePath(List<byte[]> value, int expectedSize) {
        if (value == null || value.size() != expectedSize) {
            throw new IllegalArgumentException("message proof path length differs from leaf count");
        }
        List<byte[]> result = value.stream()
                .map(item -> exact32(item, "sibling")).toList();
        return List.copyOf(result);
    }

    private static byte[] exact32(byte[] value, String field) {
        if (value == null || value.length != 32) {
            throw new IllegalArgumentException(field + " must contain 32 bytes");
        }
        return value.clone();
    }
}
