package com.bloxbean.cardano.yano.api.appchain.evidence;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.FinalityCert;
import com.bloxbean.cardano.yano.api.appchain.codec.AppBlockCodec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MessageInclusionProofTest {
    @Test
    void provesEveryLeafForSingleEvenOddAndNonPowerOfTwoTrees() {
        for (int count : List.of(1, 2, 3, 4, 5, 8, 15)) {
            AppBlock block = block(count);
            for (AppMessage message : block.messages()) {
                MessageInclusionProof proof = MessageInclusionProof.fromBlock(
                        block, message.getMessageId()).orElseThrow();
                assertThat(proof.verifiesRoot()).isTrue();
                assertThat(proof.verifies(block.chainId(), block.height(),
                        AppBlockCodec.blockHash(block), block.messagesRoot(),
                        message.getMessageId())).isTrue();
            }
        }
    }

    @Test
    void rejectsSubstitutedIdentityPathAndOddDuplicate() {
        AppBlock block = block(5);
        MessageInclusionProof proof = MessageInclusionProof.fromBlock(
                block, block.messages().getLast().getMessageId()).orElseThrow();
        assertThat(proof.verifies("other", block.height(),
                proof.blockHash(), proof.messagesRoot(), proof.messageId())).isFalse();
        assertThat(proof.verifies(block.chainId(), block.height(),
                filled(91), proof.messagesRoot(), proof.messageId())).isFalse();

        List<byte[]> corrupted = new ArrayList<>(proof.siblings());
        corrupted.set(0, filled(92));
        MessageInclusionProof malformed = new MessageInclusionProof(
                proof.schemaVersion(), proof.treeId(), proof.chainId(), proof.blockHeight(),
                proof.blockHash(), proof.messagesRoot(), proof.messageId(),
                proof.messageIndex(), proof.leafCount(), corrupted);
        assertThat(malformed.verifiesRoot()).isFalse();
    }

    private static AppBlock block(int count) {
        List<AppMessage> messages = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            byte[] id = new byte[32];
            id[31] = (byte) (index + 1);
            messages.add(AppMessage.builder().messageId(id).chainId("proof-chain")
                    .topic("records").sender(filled(7)).senderSeq(index + 1L)
                    .expiresAt(Long.MAX_VALUE).body(new byte[]{(byte) index})
                    .authScheme(0).authProof(new byte[64]).build());
        }
        AppBlock unsigned = new AppBlock(AppBlock.BLOCK_VERSION, "proof-chain", 7,
                filled(3), 0, new byte[0], 1234, AppBlockCodec.messagesRoot(messages),
                filled(4), messages, filled(5), FinalityCert.empty());
        return unsigned;
    }

    private static byte[] filled(int value) {
        byte[] result = new byte[32];
        Arrays.fill(result, (byte) value);
        return result;
    }
}
