package org.yanoproject.runtime.appchain;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AppMsgPoolTest {

    /** A message the proposer cannot include yet stays pooled and does not crowd out later ones. */
    @Test
    void unselectableMessagesStayPooledWithoutBlockingTheQueue() {
        AppMsgPool pool = new AppMsgPool(16);
        List<AppMessage> waiting = List.of(message(1, "joiner-1"), message(2, "joiner-2"));
        AppMessage ready = message(3, "member");
        waiting.forEach(pool::add);
        pool.add(ready);

        List<AppMessage> drained = pool.drainCandidates(2, Long.MAX_VALUE,
                candidate -> !waiting.contains(candidate));

        assertThat(drained).containsExactly(ready);
        assertThat(pool.size()).isEqualTo(3);
    }

    private static AppMessage message(long seq, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        byte[] sender = new byte[32];
        long expiresAt = System.currentTimeMillis() / 1000 + 600;
        return AppMessage.builder()
                .messageId(AppMessage.computeMessageId("pool-chain", "t", sender, seq, expiresAt, bytes))
                .chainId("pool-chain").topic("t").sender(sender).senderSeq(seq)
                .expiresAt(expiresAt).body(bytes).authScheme(0).authProof(new byte[64]).build();
    }
}
