package com.bloxbean.cardano.yano.compat.ccl;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Builds the markdown load-test report. */
public final class Report {
    private final YanoClient client;
    private final Stats regular;
    private final Stats chained;
    private final List<LoadTest.ChainOutcome> chains;
    private final LoadTest.Sanity sanity;
    private final List<Map<String, Object>> samples;
    private final Path dir;

    private long blocksScanned;
    private long blocksWithTx;
    private long txsInBlocks;
    private int maxTxsInBlock;
    private int maxBlockSize;
    private long firstHeight;
    private long lastHeight;

    public Report(YanoClient client, Stats regular, Stats chained,
                  List<LoadTest.ChainOutcome> chains, LoadTest.Sanity sanity,
                  List<Map<String, Object>> samples, Path dir) {
        this.client = client;
        this.regular = regular;
        this.chained = chained;
        this.chains = chains;
        this.sanity = sanity;
        this.samples = samples;
        this.dir = dir;
    }

    public Path write() throws Exception {
        scanBlocks();
        Path out = dir.resolve("REPORT.md");
        Files.writeString(out, build());
        return out;
    }

    /** Walk the blocks produced during the run and aggregate tx counts and sizes. */
    private void scanBlocks() {
        if (samples.isEmpty()) return;
        firstHeight = ((Number) samples.get(0).get("blockHeight")).longValue();
        lastHeight = ((Number) samples.get(samples.size() - 1).get("blockHeight")).longValue();
        long span = lastHeight - firstHeight;
        if (span <= 0) return;
        long cap = Long.parseLong(System.getProperty("load.block.scan.max", "20000"));
        long from = span > cap ? lastHeight - cap : firstHeight;

        ConcurrentLinkedQueue<int[]> results = new ConcurrentLinkedQueue<>();
        AtomicLong failures = new AtomicLong();
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            for (long h = from; h <= lastHeight; h++) {
                long height = h;
                pool.submit(() -> {
                    try {
                        JsonNode b = client.blockByNumber(height);
                        results.add(new int[]{b.get("tx_count").asInt(), b.get("size").asInt()});
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    }
                });
            }
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.MINUTES);
        } catch (Exception e) {
            LoadTest.ANOMALIES.add("block scan interrupted: " + e);
        }
        for (int[] r : results) {
            blocksScanned++;
            if (r[0] > 0) {
                blocksWithTx++;
                txsInBlocks += r[0];
            }
            maxTxsInBlock = Math.max(maxTxsInBlock, r[0]);
            maxBlockSize = Math.max(maxBlockSize, r[1]);
        }
        if (failures.get() > 0) {
            LoadTest.ANOMALIES.add(failures.get() + " block fetches failed during report scan");
        }
    }

    public String summaryText() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("regular : %d submitted, %d accepted (%.1f%%), %.1f tx/s accepted%n",
                regular.submitted(), regular.accepted(), pct(regular.accepted(), regular.submitted()),
                regular.acceptTps()));
        sb.append(String.format("chained : %d submitted, %d accepted (%.1f%%), %d chains, %.1f%% full depth%n",
                chained.submitted(), chained.accepted(), pct(chained.accepted(), chained.submitted()),
                chains.size(), pct(fullDepthChains(), chains.size())));
        sb.append(String.format("blocks  : %d scanned, %d carried txs, %d txs total, max %d txs/block, max %d bytes%n",
                blocksScanned, blocksWithTx, txsInBlocks, maxTxsInBlock, maxBlockSize));
        return sb.toString();
    }

    private long fullDepthChains() {
        return chains.stream().filter(c -> c.acceptedDepth() == c.requestedDepth()).count();
    }

    private String build() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Yano mempool load test report\n\n");
        sb.append("Generated: ").append(Instant.now()).append("\n\n");

        sb.append("## Configuration\n\n");
        sb.append("| setting | value |\n|---|---|\n");
        sb.append(row("api", LoadTest.API));
        sb.append(row("duration (min)", LoadTest.DURATION_MIN));
        sb.append(row("regular workers", LoadTest.WORKERS));
        sb.append(row("utxos per worker", LoadTest.UTXOS_PER_WORKER));
        sb.append(row("chain workers", LoadTest.CHAIN_WORKERS));
        sb.append(row("chain depth", LoadTest.CHAIN_DEPTH));
        sb.append(row("payment (lovelace)", LoadTest.PAY_LOVELACE));
        sb.append(row("throttle (ms)", LoadTest.THROTTLE_MS));
        sb.append(row("chain pause (ms)", LoadTest.CHAIN_PAUSE_MS));
        sb.append("\n");

        sb.append("## Phase 0 - sanity\n\n");
        sb.append("- single payment: `").append(sanity.singlePayment()).append("`\n");
        sb.append("- chain depth 3: `").append(sanity.chainResult()).append("`\n");
        sb.append("- verdict: ").append(sanity.ok() ? "**PASS**" : "**FAIL**").append("\n\n");

        sb.append("## Regular (independent) transactions\n\n");
        appendStats(sb, regular);

        sb.append("## Chained (parent -> child) transactions\n\n");
        appendStats(sb, chained);
        sb.append("### Chain depth distribution\n\n");
        Map<Integer, Long> depth = new TreeMap<>();
        Map<String, Long> stops = new TreeMap<>();
        for (var c : chains) {
            depth.merge(c.acceptedDepth(), 1L, Long::sum);
            stops.merge(c.stopReason(), 1L, Long::sum);
        }
        sb.append("| accepted depth | chains |\n|---|---|\n");
        depth.forEach((d, n) -> sb.append("| ").append(d).append(" | ").append(n).append(" |\n"));
        sb.append("\n| stop reason | chains |\n|---|---|\n");
        stops.forEach((s, n) -> sb.append("| `").append(s).append("` | ").append(n).append(" |\n"));
        sb.append("\n- chains attempted: **").append(chains.size()).append("**\n");
        sb.append("- chains reaching full depth ").append(LoadTest.CHAIN_DEPTH).append(": **")
                .append(fullDepthChains()).append("** (")
                .append(String.format("%.1f%%", pct(fullDepthChains(), chains.size()))).append(")\n");
        double avgChainMs = chains.stream().mapToLong(LoadTest.ChainOutcome::totalMillis).average().orElse(0);
        sb.append("- mean wall time per chain: ").append(String.format("%.0f ms", avgChainMs)).append("\n\n");
        sb.append("> A chain link is only admissible if the mempool overlay resolves its parent's\n")
                .append("> unconfirmed output. Full-depth acceptance is direct evidence the\n")
                .append("> ADR-NET-009 overlay works under load.\n\n");

        sb.append("## Node-side observations\n\n");
        long maxMempool = 0;
        long maxMempoolBytes = 0;
        for (var s : samples) {
            maxMempool = Math.max(maxMempool, ((Number) s.get("mempoolTxs")).longValue());
            maxMempoolBytes = Math.max(maxMempoolBytes, ((Number) s.get("mempoolBytes")).longValue());
        }
        sb.append("| metric | value |\n|---|---|\n");
        sb.append(row("samples taken", samples.size()));
        sb.append(row("peak mempool transactions", maxMempool));
        sb.append(row("peak mempool bytes", maxMempoolBytes));
        sb.append(row("block height start -> end", firstHeight + " -> " + lastHeight));
        sb.append(row("blocks scanned", blocksScanned));
        sb.append(row("blocks carrying >=1 tx", blocksWithTx));
        sb.append(row("transactions in blocks", txsInBlocks));
        sb.append(row("max transactions in one block", maxTxsInBlock));
        sb.append(row("max block size (bytes)", maxBlockSize));
        try {
            sb.append(row("protocol max_block_size", client.protocolParams().get("max_block_size").asInt()));
            sb.append(row("protocol max_tx_size", client.protocolParams().get("max_tx_size").asInt()));
        } catch (Exception ignored) {
            // node may be gone by report time
        }
        sb.append("\nTime series: `samples.jsonl`\n\n");

        sb.append("## Anomalies\n\n");
        if (LoadTest.ANOMALIES.isEmpty()) {
            sb.append("None recorded.\n\n");
        } else {
            List<String> copy = new ArrayList<>(LoadTest.ANOMALIES);
            Map<String, Long> grouped = new LinkedHashMap<>();
            copy.forEach(a -> grouped.merge(a.length() > 160 ? a.substring(0, 160) : a, 1L, Long::sum));
            grouped.forEach((a, n) -> sb.append("- (x").append(n).append(") ").append(a).append("\n"));
            sb.append("\n");
        }

        sb.append("## Totals\n\n```\n").append(summaryText()).append("```\n");
        return sb.toString();
    }

    private void appendStats(StringBuilder sb, Stats s) {
        sb.append("| metric | value |\n|---|---|\n");
        sb.append(row("submitted", s.submitted()));
        sb.append(row("accepted", s.accepted()));
        sb.append(row("rejected", s.rejected()));
        sb.append(row("acceptance rate", String.format("%.2f%%", pct(s.accepted(), s.submitted()))));
        sb.append(row("duration (s)", String.format("%.1f", s.durationSeconds())));
        sb.append(row("submit throughput (tx/s)", String.format("%.2f", s.submitTps())));
        sb.append(row("accepted throughput (tx/s)", String.format("%.2f", s.acceptTps())));
        var lat = s.latencyMillis();
        lat.forEach((k, v) -> sb.append(row("submit latency " + k + " (ms)", String.format("%.2f", v))));
        sb.append("\n**Outcome breakdown**\n\n| category | count |\n|---|---|\n");
        s.categories().forEach((k, v) -> sb.append("| `").append(k).append("` | ").append(v).append(" |\n"));
        sb.append("\n");
    }

    private static String row(String k, Object v) {
        return "| " + k + " | " + v + " |\n";
    }

    private static double pct(long n, long d) {
        return d == 0 ? 0 : (100.0 * n / d);
    }
}
