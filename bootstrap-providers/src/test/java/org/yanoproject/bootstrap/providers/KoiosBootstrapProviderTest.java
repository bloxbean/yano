package org.yanoproject.bootstrap.providers;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.bootstrap.BootstrapBlockInfo;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KoiosBootstrapProviderTest {
    private HttpServer server;

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void getBlocksFetchesRangeInOneRequest() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        KoiosBootstrapProvider provider = serve(query, block(10, "aa"), block(11, "bb"), block(12, "cc"));

        List<BootstrapBlockInfo> blocks = provider.getBlocks(10, 12);

        assertEquals("block_height=gte.10&block_height=lte.12&order=block_height.asc", query.get());
        assertEquals(List.of(10L, 11L, 12L), blocks.stream().map(BootstrapBlockInfo::blockNumber).toList());
        assertEquals("cc", blocks.get(2).blockHash());
    }

    @Test
    void getBlocksRejectsIncompleteRange() throws Exception {
        KoiosBootstrapProvider provider = serve(new AtomicReference<>(), block(10, "aa"), block(11, "bb"));

        RuntimeException e = assertThrows(RuntimeException.class, () -> provider.getBlocks(10, 12));
        assertTrue(e.getMessage().contains("2 of 3"), e.getMessage());
    }

    private KoiosBootstrapProvider serve(AtomicReference<String> query, String... blocks) throws Exception {
        byte[] body = ("[" + String.join(",", blocks) + "]").getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/blocks", exchange -> {
            query.set(exchange.getRequestURI().getQuery());
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return new KoiosBootstrapProvider("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static String block(long height, String hash) {
        return "{\"hash\":\"" + hash + "\",\"block_height\":" + height + ",\"abs_slot\":" + (height * 20) + "}";
    }
}
