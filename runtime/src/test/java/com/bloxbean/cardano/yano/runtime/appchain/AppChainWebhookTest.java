package com.bloxbean.cardano.yano.runtime.appchain;

import com.bloxbean.cardano.client.crypto.KeyGenUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yano.api.appchain.AppBlock;
import com.bloxbean.cardano.yano.api.appchain.AppChainConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-006 E3.1: webhook sink delivers finalized blocks in order with a
 * persisted cursor (at-least-once across failures), and subscribeFinalized
 * feeds in-process consumers.
 */
@Timeout(90)
class AppChainWebhookTest {

    private static final Logger log = LoggerFactory.getLogger(AppChainWebhookTest.class);
    private static final byte[] KEY_A = seed(71);

    @TempDir
    Path tempDir;

    private AppChainSubsystem node;
    private HttpServer webhookServer;

    @AfterEach
    void tearDown() {
        if (node != null) node.stop();
        if (webhookServer != null) webhookServer.stop(0);
    }

    @Test
    void webhookSink_deliversInOrder_withRetry_andListenerFires() throws Exception {
        // Local webhook receiver that can be toggled to fail
        List<String> received = new CopyOnWriteArrayList<>();
        AtomicBoolean failMode = new AtomicBoolean(false);
        webhookServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        webhookServer.createContext("/hook", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (failMode.get()) {
                exchange.sendResponseHeaders(500, -1);
            } else {
                received.add(new String(body, StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(200, -1);
            }
            exchange.close();
        });
        webhookServer.start();
        String hookUrl = "http://localhost:" + webhookServer.getAddress().getPort() + "/hook";

        String pubA = HexUtil.encodeHexString(KeyGenUtil.getPublicKeyFromPrivateKey(KEY_A));
        AppChainConfig config = new AppChainConfig(
                "webhook-chain",
                HexUtil.encodeHexString(KEY_A),
                Set.of(pubA),
                List.of(),
                65536, 3600, 600,
                pubA, 1, 300, 100,
                AppChainConfig.DEFAULT_STATE_MACHINE,
                null, null, 0,
                List.of(hookUrl));
        node = new AppChainSubsystem(config, 42, null, null,
                tempDir.resolve("ledger").toString(), null, log);

        // In-process consumer via subscribeFinalized
        List<Long> listenerHeights = new CopyOnWriteArrayList<>();
        node.subscribeFinalized((AppBlock block, byte[] hash) -> listenerHeights.add(block.height()));

        node.start();

        node.submit("t", "m1".getBytes(StandardCharsets.UTF_8));
        awaitTrue("block 1 delivered to webhook", () -> received.size() >= 1);
        assertThat(received.get(0)).contains("\"height\":1").contains("\"chainId\":\"webhook-chain\"");
        assertThat(listenerHeights).contains(1L);

        // Fail the receiver; new blocks queue behind the cursor
        failMode.set(true);
        String id2 = node.submit("t", "m2".getBytes(StandardCharsets.UTF_8));
        String id3 = node.submit("t", "m3".getBytes(StandardCharsets.UTF_8));
        awaitTrue("messages finalized locally",
                () -> node.messageHeight(HexUtil.decodeHexString(id2)).isPresent()
                        && node.messageHeight(HexUtil.decodeHexString(id3)).isPresent());
        long tip = node.tipHeight(); // proposer may batch m2+m3 into one block
        assertThat(tip).isGreaterThanOrEqualTo(2);
        Thread.sleep(6000); // at least one failed delivery tick
        assertThat(received).hasSize(1); // nothing delivered while failing

        // Recover: deliveries resume from the cursor, strictly in order
        failMode.set(false);
        awaitTrue("remaining blocks delivered after recovery", () -> received.size() >= tip);
        for (int i = 0; i < tip; i++) {
            assertThat(received.get(i)).contains("\"height\":" + (i + 1));
        }

        // Listener saw every height too, in order
        assertThat(listenerHeights).containsExactly(
                java.util.stream.LongStream.rangeClosed(1, tip).boxed().toArray(Long[]::new));
    }

    private static void awaitTrue(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 40_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean())
                return;
            Thread.sleep(250);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }

    private static byte[] seed(int fill) {
        byte[] seed = new byte[32];
        Arrays.fill(seed, (byte) fill);
        return seed;
    }
}
