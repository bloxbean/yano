package com.bloxbean.cardano.yano.runtime.wallet;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.yano.api.wallet.WalletChainPoint;
import com.bloxbean.cardano.yano.runtime.chain.DirectRocksDBChainState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.management.OperatingSystemMXBean;
import com.sun.management.ThreadMXBean;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.FlushOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Opt-in physical storage spike. Synthetic inputs, not a historical sync benchmark. */
public final class WalletIndexBenchmark {
    private static final int UNDO_WINDOW = 86_400;
    private static final byte[] BASELINE_CURSOR = "wallet-benchmark-cursor".getBytes(StandardCharsets.UTF_8);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final OperatingSystemMXBean OS = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: output-directory block-count");
        Path root = Path.of(args[0]).toAbsolutePath();
        int count = Integer.parseInt(args[1]);
        if (count < 1) throw new IllegalArgumentException("Positive block count required");
        Files.createDirectory(root); // Never overwrite an existing benchmark/database.
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("kind", "synthetic-index-storage-spike; excludes ledger/body I/O and credential extraction");
        report.put("java", System.getProperty("java.runtime.version"));
        report.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        report.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        report.put("blocks", count);
        report.put("distinctAddresses", count);
        report.put("entropy", "Java Random seed 119: uniform address credentials and block hashes, identical across modes");
        report.put("createdAddressesPerBlock", 4);
        report.put("filterElementPattern", List.of(0, 8, 40, 80));
        report.put("undoWindowBlocks", UNDO_WINDOW);
        report.put("scanCacheNote", "reopened is cold RocksDB cache, not a flushed operating-system page cache");
        List<Map<String, Object>> modes = new ArrayList<>();
        report.put("modes", modes);
        for (int mode = 0; mode < 4; mode++) {
            modes.add(run(root, count, (mode & 1) != 0, (mode & 2) != 0));
            JSON.writerWithDefaultPrettyPrinter().writeValue(root.resolve("report.json").toFile(), report);
        }
        System.out.println("Report: " + root.resolve("report.json"));
    }

    private static Map<String, Object> run(Path root, int count, boolean firstSeen, boolean filters) throws Exception {
        String name = firstSeen ? filters ? "both" : "first-seen" : filters ? "filters" : "baseline";
        Path directory = root.resolve(name);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", name);
        Random random = new Random(119);
        String[] addresses = new String[count];
        for (int i = 0; i < count; i++) {
            byte[] raw = new byte[57];
            random.nextBytes(raw);
            raw[0] = 0;
            addresses[i] = new Address(raw).toBech32();
        }
        long peakDisk = 0;
        try (DirectRocksDBChainState chain = new DirectRocksDBChainState(directory.toString());
             WriteOptions writes = new WriteOptions()) {
            RocksDB db = chain.rocks().db();
            WalletIndexStore index = new WalletIndexStore(chain.rocks(), firstSeen, filters);
            try (WriteBatch batch = new WriteBatch()) {
                index.stageGenesis(batch, "synthetic-119", List.of());
                db.write(writes, batch);
            }
            WalletChainPoint previous = WalletChainPoint.ORIGIN;
            long[] latency = new long[count];
            long logicalBytes = 0;
            long start = System.nanoTime();
            long cpu = OS.getProcessCpuTime();
            long allocations = allocated();
            long gcMillis = gcMillis();
            for (int block = 1; block <= count; block++) {
                long before = System.nanoTime();
                byte[] hash = new byte[32];
                random.nextBytes(hash);
                byte[] seed = Arrays.copyOf(hash, 16);
                WalletChainPoint point = new WalletChainPoint(block, block * 20L, HexFormat.of().formatHex(hash));
                byte[] filter = filters ? CredentialFilter.encode(seed, elements(block)) : null;
                List<String> created = firstSeen ? List.of(addresses[(int) ((block * 4L) % count)],
                        addresses[(int) ((block * 4L + 1) % count)], addresses[(int) ((block * 4L + 2) % count)],
                        addresses[(int) ((block * 4L + 3) % count)]) : List.of();
                try (WriteBatch batch = new WriteBatch()) {
                    // Every mode makes the same base progress write and one durable batch.
                    batch.put(BASELINE_CURSOR, hash);
                    index.stageBlock(batch, previous, point, created, filter, null);
                    if (block > UNDO_WINDOW) index.stagePruneUndo(batch, block - UNDO_WINDOW);
                    logicalBytes += batch.getDataSize();
                    db.write(writes, batch);
                }
                latency[block - 1] = System.nanoTime() - before;
                previous = point;
                if (block % 100_000 == 0) {
                    peakDisk = Math.max(peakDisk, disk(directory));
                    System.out.printf("%s %,d / %,d blocks, %.1f seconds%n", name, block, count,
                            (System.nanoTime() - start) / 1e9);
                }
            }
            double seconds = (System.nanoTime() - start) / 1e9;
            result.put("applySeconds", seconds);
            result.put("blocksPerSecond", count / seconds);
            result.put("processCpuSeconds", (OS.getProcessCpuTime() - cpu) / 1e9);
            result.put("mainThreadAllocatedBytes", allocated() - allocations);
            result.put("gcMillis", gcMillis() - gcMillis);
            result.put("logicalBatchBytes", logicalBytes);
            Arrays.sort(latency);
            result.put("applyP50Micros", latency[count / 2] / 1000.0);
            result.put("applyP99Micros", latency[Math.min(count - 1, (int) (count * 0.99))] / 1000.0);
            try (FlushOptions flush = new FlushOptions().setWaitForFlush(true)) {
                db.flush(flush, new ArrayList<>(chain.rocks().allHandles().values()));
            }
            peakDisk = Math.max(peakDisk, disk(directory));
            Map<String, Long> sst = new LinkedHashMap<>();
            for (String cf : List.of(WalletIndexCf.FIRST_SEEN, WalletIndexCf.FILTERS, WalletIndexCf.META, WalletIndexCf.UNDO)) {
                ColumnFamilyHandle handle = chain.rocks().handle(cf);
                db.compactRange(handle);
                sst.put(cf, db.getLongProperty(handle, "rocksdb.total-sst-files-size"));
                Files.writeString(root.resolve(name + "-" + cf + "-rocksdb-stats.txt"), db.getProperty(handle, "rocksdb.stats"));
            }
            result.put("compactedSstBytes", sst);
            result.put("sampledPeakDirectoryBytes", Math.max(peakDisk, disk(directory)));
        }
        result.put("closedDirectoryBytes", disk(directory));
        if (filters) {
            List<Map<String, Object>> scans = new ArrayList<>();
            result.put("scans", scans);
            for (int queries : new int[]{1, 10, 200}) {
                try (DirectRocksDBChainState chain = new DirectRocksDBChainState(directory.toString())) {
                    WalletIndexStore index = new WalletIndexStore(chain.rocks(), firstSeen, true);
                    scans.add(scan(index, count, queries, "reopened"));
                    scans.add(scan(index, count, queries, "repeat"));
                }
            }
        }
        System.out.println(name + " complete: " + result.get("blocksPerSecond") + " blocks/s");
        return result;
    }

    private static Map<String, Object> scan(WalletIndexStore index, int blocks, int queries, String temperature) throws Exception {
        List<byte[]> keys = new ArrayList<>();
        // Disjoint synthetic query IDs: every candidate is a known false positive.
        for (int i = 0; i < queries; i++) keys.add(element(1_000_000_000L + i));
        long cursor = 0, candidates = 0, records = 0;
        long start = System.nanoTime(), cpu = OS.getProcessCpuTime(), allocation = allocated();
        while (cursor < blocks) {
            List<WalletIndexStore.FilterRecord> batch = index.readFilters(cursor, blocks, 256);
            if (batch.isEmpty()) throw new IllegalStateException("Benchmark filter gap");
            for (var record : batch) {
                if (record.point().blockNumber() != cursor + 1) throw new IllegalStateException("Noncontinuous benchmark filters");
                if (CredentialFilter.matches(record.filter(), keys)) candidates++;
                cursor = record.point().blockNumber();
                records++;
            }
        }
        if (records != blocks) throw new IllegalStateException("Incorrect physical filter count");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("queryCount", queries);
        result.put("cache", temperature);
        result.put("recordsRead", records);
        result.put("seconds", (System.nanoTime() - start) / 1e9);
        result.put("cpuSeconds", (OS.getProcessCpuTime() - cpu) / 1e9);
        result.put("mainThreadAllocatedBytes", allocated() - allocation);
        result.put("falsePositiveCandidates", candidates);
        result.put("bodyReads", "not measured; no bodies in this synthetic spike");
        System.out.println("scan " + queries + " " + temperature + ": " + result.get("seconds") + " seconds, " + candidates + " candidates");
        return result;
    }

    private static List<byte[]> elements(int block) {
        int count = new int[]{0, 8, 40, 80}[(block - 1) % 4];
        List<byte[]> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) result.add(element(block * 80L + i));
        return result;
    }

    private static byte[] element(long id) {
        byte[] element = new byte[29];
        ByteBuffer.wrap(element, 1, 8).putLong(id);
        return element;
    }

    private static long allocated() { return THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId()); }
    private static long gcMillis() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> Math.max(0, b.getCollectionTime())).sum(); }
    private static long disk(Path directory) throws Exception {
        try (var files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile).mapToLong(file -> {
                try { return Files.size(file); } catch (NoSuchFileException compactedAway) { return 0; } catch (Exception e) { throw new IllegalStateException(e); }
            }).sum();
        }
    }
}
