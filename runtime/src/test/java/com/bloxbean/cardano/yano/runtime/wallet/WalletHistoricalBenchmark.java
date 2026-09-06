package com.bloxbean.cardano.yano.runtime.wallet;

import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.ByronBlockSerializer;
import com.bloxbean.cardano.yano.api.config.YanoPropertyKeys;
import com.bloxbean.cardano.yano.api.events.BlockAppliedEvent;
import com.bloxbean.cardano.yano.api.events.ByronMainBlockAppliedEvent;
import com.bloxbean.cardano.yano.api.wallet.WalletChainPoint;
import com.bloxbean.cardano.yano.api.wallet.WalletCredential;
import com.bloxbean.cardano.yano.api.wallet.WalletScanRequest;
import com.bloxbean.cardano.yano.runtime.chain.DirectRocksDBChainState;
import com.bloxbean.cardano.yano.runtime.genesis.ByronGenesisParser;
import com.bloxbean.cardano.yano.runtime.genesis.ShelleyGenesisParser;
import com.bloxbean.cardano.yano.runtime.utxo.DefaultUtxoStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.management.OperatingSystemMXBean;
import com.sun.management.ThreadMXBean;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.DBOptions;
import org.rocksdb.FlushOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Opt-in real canonical-body replay. The source DB is opened read-only. */
public final class WalletHistoricalBenchmark {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final OperatingSystemMXBean OS = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Usage: source-db fresh-output-directory genesis-directory block-count");
        Path root = Path.of(args[1]).toAbsolutePath();
        int count = Integer.parseInt(args[3]);
        if (count < 1) throw new IllegalArgumentException("Positive block count required");
        Files.createDirectory(root);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("kind", "historical canonical-body replay through UTxO/index apply; excludes network, consensus, other ledger stores");
        report.put("source", Path.of(args[0]).toAbsolutePath().toString());
        report.put("java", System.getProperty("java.runtime.version"));
        report.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        report.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        report.put("blocks", count);
        report.put("limitations", "fixed mode order; OS cache not flushed; source reads are included; not full-node sync throughput");
        List<Map<String, Object>> modes = new ArrayList<>();
        report.put("modes", modes);
        try (Source source = new Source(args[0])) {
            for (int mode = 0; mode < 4; mode++) {
                modes.add(run(source, root, Path.of(args[2]), count, mode));
                JSON.writerWithDefaultPrettyPrinter().writeValue(root.resolve("report.json").toFile(), report);
            }
        }
        System.out.println("Report: " + root.resolve("report.json"));
    }

    private static Map<String, Object> run(Source source, Path root, Path genesisDirectory, int count, int mode) throws Exception {
        boolean firstSeen = (mode & 1) != 0;
        boolean filters = (mode & 2) != 0;
        String name = firstSeen ? filters ? "both" : "first-seen" : filters ? "filters" : "baseline";
        Path directory = root.resolve(name);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", name);
        var shelley = ShelleyGenesisParser.parse(genesisDirectory.resolve("shelley-genesis.json").toFile());
        var byron = ByronGenesisParser.parse(genesisDirectory.resolve("byron-genesis.json").toFile());
        Map<String, Object> config = Map.of(YanoPropertyKeys.WalletIndex.FIRST_SEEN_ENABLED, firstSeen,
                YanoPropertyKeys.WalletIndex.FILTERS_ENABLED, filters, YanoPropertyKeys.Metrics.ENABLED, false);
        LinkedHashSet<WalletCredential> queries = new LinkedHashSet<>();
        Map<Integer, Long> eras = new LinkedHashMap<>();
        try (var chain = new DirectRocksDBChainState(directory.toString())) {
            var store = new DefaultUtxoStore(chain, LoggerFactory.getLogger(WalletHistoricalBenchmark.class), config);
            try {
                store.wireAllegraBootstrapRemoval(chain);
                store.initializeFreshFullStateGenesis(shelley.initialFunds(), shelley.networkMagic(),
                        byron.nonAvvmBalances(), byron.avvmBalances(), 0, 0, "00".repeat(32));
                long[] latency = new long[count];
                long bytes = 0, peakDisk = 0;
                long start = System.nanoTime(), cpu = OS.getProcessCpuTime(), allocated = allocated(), gc = gcMillis();
                WalletChainPoint last = WalletChainPoint.ORIGIN;
                for (int number = 1; number <= count; number++) {
                    long before = System.nanoTime();
                    byte[] slotBytes = source.get("slot_by_number", number(number));
                    if (slotBytes == null) throw new IllegalStateException("Missing canonical block " + number);
                    long slot = ByteBuffer.wrap(slotBytes).getLong();
                    byte[] hash = source.get("slot_to_hash", slotBytes);
                    byte[] body = hash == null ? null : source.get("blocks", hash);
                    byte[] header = hash == null ? null : source.get("headers", hash);
                    if (body == null || header == null) throw new IllegalStateException("Missing retained body/header " + number);
                    if (body.length < 2 || body[0] != (byte) 0x82) throw new IllegalStateException("Unrecognized era envelope " + number);
                    int era = body[1] & 255;
                    eras.merge(era, 1L, Long::sum);
                    String hashHex = HexFormat.of().formatHex(hash);
                    chain.storeBlockHeader(hash, (long) number, slot, header);
                    chain.storeBlock(hash, (long) number, slot, body);
                    if (era == 1) {
                        var block = ByronBlockSerializer.INSTANCE.deserialize(body);
                        store.applyByronBlock(new ByronMainBlockAppliedEvent(slot, number, hashHex, block));
                    } else if (era >= 2 && era <= 7) {
                        var block = BlockSerializer.INSTANCE.deserialize(body);
                        store.applyBlock(new BlockAppliedEvent(block.getEra(), slot, number, hashHex, block));
                        if (queries.size() < 200 && block.getTransactionBodies() != null) {
                            for (var tx : block.getTransactionBodies()) if (tx.getOutputs() != null) {
                                for (var output : tx.getOutputs()) {
                                    if (queries.size() >= 200) break;
                                    WalletCredentials.address(output.getAddress(), queries);
                                }
                            }
                        }
                    } else throw new IllegalStateException("Unexpected indexed era " + era);
                    if (number % 500 == 0) store.pruneOnce();
                    latency[number - 1] = System.nanoTime() - before;
                    bytes += body.length + header.length;
                    last = new WalletChainPoint(number, slot, hashHex);
                    if (number % 10000 == 0) {
                        peakDisk = Math.max(peakDisk, disk(directory));
                        System.out.println(name + " block=" + number + " seconds=" + ((System.nanoTime() - start) / 1e9));
                    }
                }
                result.put("applyWallSeconds", (System.nanoTime() - start) / 1e9);
                result.put("applyCpuSeconds", (OS.getProcessCpuTime() - cpu) / 1e9);
                result.put("allocatedBytes", allocated() - allocated);
                result.put("gcMillis", gcMillis() - gc);
                result.put("sourceBodyAndHeaderBytes", bytes);
                result.put("eraBlockCounts", eras);
                result.put("indexedThrough", last);
                Arrays.sort(latency);
                result.put("applyP50Micros", latency[(count - 1) / 2] / 1e3);
                result.put("applyP99Micros", latency[(int) ((count - 1) * .99)] / 1e3);
                try (FlushOptions flush = new FlushOptions().setWaitForFlush(true)) { chain.rocks().db().flush(flush, new ArrayList<>(chain.rocks().allHandles().values())); }
                result.put("sampledPeakDiskBytes", Math.max(peakDisk, disk(directory)));
                Map<String, Long> cfSizes = new LinkedHashMap<>();
                for (String cf : List.of(WalletIndexCf.FIRST_SEEN, WalletIndexCf.FILTERS, WalletIndexCf.UNDO, WalletIndexCf.META)) {
                    var handle = chain.rocks().handle(cf);
                    cfSizes.put(cf, chain.rocks().db().getLongProperty(handle, "rocksdb.total-sst-files-size"));
                    Files.writeString(root.resolve(name + "-" + cf + "-stats.txt"), chain.rocks().db().getProperty(handle, "rocksdb.stats"));
                }
                result.put("sstBytes", cfSizes);
                long filterCount = 0;
                try (var iterator = chain.rocks().db().newIterator(chain.rocks().handle(WalletIndexCf.FILTERS))) {
                    for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) filterCount++;
                    iterator.status();
                }
                result.put("physicalFilters", filterCount);
                if (filters && filterCount != count) throw new IllegalStateException("Incomplete physical filter coverage: " + filterCount);
                if (firstSeen && !queries.isEmpty()) {
                    var coverage = new WalletIndexStore(chain.rocks(), true, filters).coverage(WalletIndexStore.FIRST_SEEN, last);
                    if (!coverage.available() || !coverage.completeFromOrigin()) throw new IllegalStateException("First-seen unavailable: " + coverage);
                }
                List<Map<String, Object>> scans = new ArrayList<>();
                if (filters && !queries.isEmpty()) {
                    List<WalletCredential> credentials = new ArrayList<>(queries);
                    for (int size : List.of(1, 10, 200)) {
                        if (size > credentials.size()) continue;
                        long scanStart = System.nanoTime(), scanCpu = OS.getProcessCpuTime();
                        long matches = 0;
                        var request = new WalletScanRequest(1, credentials.subList(0, size), WalletChainPoint.ORIGIN, null, List.of());
                        String resourceLimit = "";
                        try (var scan = store.openWalletScan(request)) {
                            while (!scan.finished()) for (var event : scan.next()) if (event.type().equals("transaction")) matches++;
                        } catch (IllegalStateException failure) {
                            if (!"Scan tracked-output limit exceeded".equals(failure.getMessage())) throw failure;
                            resourceLimit = failure.getMessage();
                        }
                        scans.add(Map.of("credentials", size, "emittedTransactions", matches,
                                "complete", resourceLimit.isEmpty(), "resourceLimit", resourceLimit,
                                "wallSeconds", (System.nanoTime() - scanStart) / 1e9,
                                "cpuSeconds", (OS.getProcessCpuTime() - scanCpu) / 1e9));
                    }
                }
                result.put("warmScans", scans);
            } finally { store.close(); }
        }
        result.put("closedDiskBytes", disk(directory));
        return result;
    }

    private static byte[] number(long value) { return ByteBuffer.allocate(8).putLong(value).array(); }
    private static long allocated() { return THREADS.isThreadAllocatedMemorySupported() ? THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId()) : 0; }
    private static long gcMillis() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> Math.max(0, bean.getCollectionTime())).sum(); }
    private static long disk(Path directory) throws Exception {
        try (var paths = Files.walk(directory)) {
            long total = 0;
            for (Path path : paths.filter(Files::isRegularFile).toList()) total += Files.size(path);
            return total;
        }
    }

    private static final class Source implements AutoCloseable {
        private final DBOptions options = new DBOptions().setMaxOpenFiles(64);
        private final List<ColumnFamilyDescriptor> descriptors = new ArrayList<>();
        private final List<ColumnFamilyHandle> handles = new ArrayList<>();
        private final Map<String, ColumnFamilyHandle> columns = new LinkedHashMap<>();
        private final RocksDB db;
        Source(String path) throws Exception {
            RocksDB.loadLibrary();
            try (Options listing = new Options()) {
                for (byte[] name : RocksDB.listColumnFamilies(listing, path)) descriptors.add(new ColumnFamilyDescriptor(name));
            }
            db = RocksDB.openReadOnly(options, path, descriptors, handles);
            for (int i = 0; i < handles.size(); i++) columns.put(new String(descriptors.get(i).getName(), StandardCharsets.UTF_8), handles.get(i));
        }
        byte[] get(String column, byte[] key) throws Exception { return db.get(columns.get(column), key); }
        @Override public void close() {
            handles.forEach(ColumnFamilyHandle::close);
            db.close();
            descriptors.forEach(descriptor -> descriptor.getOptions().close());
            options.close();
        }
    }
}
