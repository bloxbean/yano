package com.bloxbean.cardano.yano.api.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yano.api.appchain.l1view.L1Observation;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppBlockExecutionContextTest {

    @Test
    void decodesL1ObservationsOnceAtTheirGlobalPositions() {
        AppMessage ordinary = message(1, "orders", new byte[]{1});
        L1Observation first = observation("epoch", 10, 2);
        L1Observation second = observation("stake", 11, 3);
        AppMessage firstEnvelope = message(2, first.topic(), first.encode());
        AppMessage secondEnvelope = message(3, second.topic(), second.encode());

        AppBlockExecutionContext context = AppBlockExecutionContext.fromValidatedBlock(
                block(ordinary, firstEnvelope, secondEnvelope));

        assertThat(context.messages()).hasSize(3);
        assertThat(context.l1Observations())
                .extracting(SequencedL1Observation::originalMessageIndex)
                .containsExactly(1, 2);
        assertThat(context.l1ObservationAt(1)).get()
                .extracting(SequencedL1Observation::messageId)
                .isEqualTo(firstEnvelope.getMessageId());
        assertThat(context.l1ObservationAt(0)).isEmpty();
    }

    @Test
    void routedViewRetainsOriginalBlockIdentityAndIndexes() {
        L1Observation observation = observation("epoch", 10, 2);
        AppBlock source = block(
                message(1, "orders", new byte[]{1}),
                message(2, observation.topic(), observation.encode()),
                message(3, "documents", new byte[]{3}));
        AppBlockExecutionContext routed = AppBlockExecutionContext
                .fromValidatedBlock(source)
                .routeToMessageIndexes(List.of(1, 2));

        assertThat(routed.block().height()).isEqualTo(source.height());
        assertThat(routed.block().messagesRoot()).isEqualTo(source.messagesRoot());
        assertThat(routed.messages()).extracting(AppMessage::getTopic)
                .containsExactly(observation.topic(), "documents");
        assertThat(routed.originalMessageIndex(0)).isEqualTo(1);
        assertThat(routed.originalMessageIndex(1)).isEqualTo(2);
        assertThat(routed.l1Observations()).singleElement()
                .extracting(SequencedL1Observation::originalMessageIndex)
                .isEqualTo(1);
    }

    @Test
    void snapshotsAreDefensiveAndRoutesCannotEscapeTheirParent() {
        AppBlockExecutionContext context = AppBlockExecutionContext.fromValidatedBlock(
                block(message(1, "orders", new byte[]{7})));
        AppBlock returned = context.block();
        returned.prevHash()[0] = 9;
        returned.messages().getFirst().getBody()[0] = 9;

        assertThat(context.block().prevHash()[0]).isZero();
        assertThat(context.messages().getFirst().getBody()).containsExactly(7);
        AppBlockExecutionContext routed = context.routeToMessageIndexes(List.of(0));
        assertThatThrownBy(() -> routed.routeToMessageIndexes(List.of(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside");
        assertThatThrownBy(() -> context.routeToMessageIndexes(List.of(0, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ordered, unique");
    }

    @Test
    void malformedOrTopicMismatchedL1EnvelopeFailsClosed() {
        assertThatThrownBy(() -> AppBlockExecutionContext.fromValidatedBlock(block(
                message(1, "~l1/epoch", new byte[]{1, 2, 3}))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("index 0");

        L1Observation observation = observation("stake", 10, 2);
        assertThatThrownBy(() -> AppBlockExecutionContext.fromValidatedBlock(block(
                message(1, "~l1/epoch", observation.encode()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("index 0");
    }

    private static L1Observation observation(String id, long slot, int value) {
        return L1Observation.transaction(id, filled(value), slot, filled(value + 1),
                new byte[]{(byte) value});
    }

    private static AppMessage message(int id, String topic, byte[] body) {
        return AppMessage.builder()
                .version(1)
                .messageId(filled(id))
                .chainId("chain")
                .topic(topic)
                .sender(filled(9))
                .senderSeq(id)
                .expiresAt(Long.MAX_VALUE)
                .body(body)
                .authScheme(0)
                .authProof(new byte[64])
                .build();
    }

    private static AppBlock block(AppMessage... messages) {
        return new AppBlock(
                AppBlock.BLOCK_VERSION,
                "chain",
                7,
                new byte[32],
                20,
                filled(8),
                100,
                filled(6),
                filled(7),
                List.of(messages),
                filled(9),
                FinalityCert.empty());
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
