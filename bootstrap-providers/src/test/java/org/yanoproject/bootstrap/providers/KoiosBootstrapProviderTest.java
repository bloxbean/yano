package org.yanoproject.bootstrap.providers;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.yanoproject.api.bootstrap.BootstrapBlockInfo;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KoiosBootstrapProviderTest {
    private static final Pattern RANGE = Pattern.compile("block_height=gte\\.(\\d+)&block_height=lte\\.(\\d+)");

    private final List<String> queries = new CopyOnWriteArrayList<>();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void getBlocksFetchesSmallRangeInOneRequest() throws Exception {
        KoiosBootstrapProvider provider = serve(-1);

        List<BootstrapBlockInfo> blocks = provider.getBlocks(10, 12);

        assertEquals(List.of("block_height=gte.10&block_height=lte.12&order=block_height.asc"), queries);
        assertEquals(List.of(10L, 11L, 12L), blocks.stream().map(BootstrapBlockInfo::blockNumber).toList());
        assertEquals("hash-12", blocks.get(2).blockHash());
    }

    @Test
    void getBlocksSplitsRangeAtKoiosRowCap() throws Exception {
        KoiosBootstrapProvider provider = serve(-1);

        List<BootstrapBlockInfo> blocks = provider.getBlocks(4_500_000, 4_501_000);

        assertEquals(List.of(
                "block_height=gte.4500000&block_height=lte.4500999&order=block_height.asc",
                "block_height=gte.4501000&block_height=lte.4501000&order=block_height.asc"), queries);
        assertEquals(1001, blocks.size());
        assertEquals(4_501_000L, blocks.get(1000).blockNumber());
    }

    @Test
    void getBlocksRejectsIncompleteRange() throws Exception {
        KoiosBootstrapProvider provider = serve(12);

        RuntimeException e = assertThrows(RuntimeException.class, () -> provider.getBlocks(10, 12));
        assertTrue(e.getMessage().contains("2 of 3"), e.getMessage());
    }

    /** Serves /blocks for the requested height range, honouring Koios' 1000-row cap and omitting {@code missing}. */
    private KoiosBootstrapProvider serve(long missing) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/blocks", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            queries.add(query);
            Matcher m = RANGE.matcher(query);
            m.find();
            long to = Math.min(Long.parseLong(m.group(2)), Long.parseLong(m.group(1)) + 999);
            StringJoiner json = new StringJoiner(",", "[", "]");
            for (long h = Long.parseLong(m.group(1)); h <= to; h++) {
                if (h != missing) {
                    json.add("{\"hash\":\"hash-" + h + "\",\"block_height\":" + h + ",\"abs_slot\":" + (h * 20) + "}");
                }
            }
            byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return new KoiosBootstrapProvider("http://127.0.0.1:" + server.getAddress().getPort());
    }
}
