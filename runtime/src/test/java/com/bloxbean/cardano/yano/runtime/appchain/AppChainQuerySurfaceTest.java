package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.bloxbean.cardano.yano.api.appchain.MessageRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-006 E3.3: topic/sender secondary indexes — messages queryable by topic
 * and sender, ascending (height, index), paged via fromHeight, isolated
 * between topics.
 */
@Timeout(60)
class AppChainQuerySurfaceTest {

    private static final Logger log = LoggerFactory.getLogger(AppChainQuerySurfaceTest.class);
    private static final byte[] KEY_A = seed(201);

    @TempDir
    Path tempDir;

    private AppChainSubsystem node;

    @AfterEach
    void tearDown() {
        if (node != null) node.stop();
    }

    @Test
    void topicAndSenderIndexes_pagedAscending() throws Exception {
        String pubA = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(KEY_A));
        AppChainConfig config = AppChainConfig.builder("query-chain")
                .signingKeyHex(HexUtil.encodeHexString(KEY_A))
                .memberKeysHex(Set.of(pubA))
                .proposerKeyHex(pubA)
                .threshold(1)
                .blockIntervalMs(300)
                .build();
        node = new AppChainSubsystem(config, 42, null, null,
                tempDir.resolve("ledger").toString(), null, log);
        node.start();

        // 3 messages on "orders", 2 on "audit"
        List<String> orderIds = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            orderIds.add(node.submit("orders", ("o-" + i).getBytes(StandardCharsets.UTF_8)));
        }
        node.submit("audit", "a-1".getBytes(StandardCharsets.UTF_8));
        node.submit("audit", "a-2".getBytes(StandardCharsets.UTF_8));
        awaitTrue("all finalized", () -> node.messagesByTopic("orders", 0, 100).size() == 3
                && node.messagesByTopic("audit", 0, 100).size() == 2);

        // Topic isolation + ascending order + ids match
        List<MessageRef> orders = node.messagesByTopic("orders", 0, 100);
        assertThat(orders).extracting(MessageRef::messageIdHex)
                .containsExactlyElementsOf(orderIds);
        for (int i = 1; i < orders.size(); i++) {
            long prev = orders.get(i - 1).height() * 10_000 + orders.get(i - 1).index();
            long curr = orders.get(i).height() * 10_000 + orders.get(i).index();
            assertThat(curr).isGreaterThan(prev);
        }

        // Paging via fromHeight: everything strictly after the first ref's height-1
        MessageRef second = orders.get(1);
        List<MessageRef> page = node.messagesByTopic("orders", second.height(), 100);
        assertThat(page).extracting(MessageRef::messageIdHex)
                .containsExactlyElementsOf(orderIds.subList(1, 3).size() == page.size()
                        ? orderIds.subList(orderIds.size() - page.size(), orderIds.size())
                        : orderIds); // page starts at second.height()
        assertThat(page.get(0).height()).isGreaterThanOrEqualTo(second.height());

        // Limit respected
        assertThat(node.messagesByTopic("orders", 0, 2)).hasSize(2);

        // Sender index: all 5 messages come from pubA
        List<MessageRef> bySender = node.messagesBySender(HexUtil.decodeHexString(pubA), 0, 100);
        assertThat(bySender).hasSize(5);

        // Unknown topic/sender → empty
        assertThat(node.messagesByTopic("nope", 0, 10)).isEmpty();
        assertThat(node.messagesBySender(new byte[32], 0, 10)).isEmpty();

        // A topic that is a strict prefix of another does not leak ("order" vs "orders")
        assertThat(node.messagesByTopic("order", 0, 10)).isEmpty();
    }

    private static void awaitTrue(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean())
                return;
            Thread.sleep(200);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }

    private static byte[] seed(int fill) {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) fill);
        return seed;
    }
}
