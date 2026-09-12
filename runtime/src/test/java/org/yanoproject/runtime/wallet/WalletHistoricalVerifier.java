package org.yanoproject.runtime.wallet;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import org.yanoproject.api.utxo.model.Outpoint;
import org.yanoproject.api.utxo.model.Utxo;
import org.yanoproject.api.chain.ChainPoint;
import org.yanoproject.api.wallet.WalletCredential;
import org.yanoproject.api.wallet.WalletScanRequest;
import org.yanoproject.runtime.db.RocksDbContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.DBOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exports raw bodies and scanner results for the independent Python CBOR oracle. */
public final class WalletHistoricalVerifier {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: completed-filter-database fresh-verification-directory");
        Path output = Path.of(args[1]);
        Files.createDirectory(output);
        RocksDB.loadLibrary();
        try (Source source = new Source(args[0])) {
            ChainPoint end = source.last();
            var coverage = source.indexes.coverage(WalletIndexStore.FILTERS, end);
            if (!coverage.available() || !coverage.completeFromOrigin()) throw new IllegalStateException("Complete origin coverage required: " + coverage);
            if (!source.indexes.scanGenesis().isEmpty()) throw new IllegalStateException("This preprod oracle requires empty Shelley initial funds");
            Set<WalletCredential> selected = new LinkedHashSet<>();
            try (var frames = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(output.resolve("blocks.bin"))))) {
                for (long number = 1; number <= end.blockNumber(); number++) {
                    ChainPoint point = source.point(number);
                    byte[] body = source.bytes(point);
                    frames.writeLong(number);
                    frames.writeLong(point.slot());
                    frames.writeInt(body.length);
                    frames.write(body);
                    if (selected.size() < 200 && body.length > 1 && (body[1] & 255) >= 2) {
                        Block block = BlockSerializer.INSTANCE.deserialize(body);
                        for (var tx : block.getTransactionBodies()) if (tx.getOutputs() != null) {
                            for (var out : tx.getOutputs()) {
                                if (selected.size() >= 200) break;
                                WalletCredentials.address(out.getAddress(), selected);
                            }
                        }
                    }
                    if (number % 100000 == 0) System.out.println("exported block=" + number);
                }
            }
            if (source.db.get(source.columns.get(WalletIndexCf.META), new byte[]{WalletIndexStore.FIRST_SEEN}) != null) {
                try (var writer = Files.newBufferedWriter(output.resolve("first-seen.tsv"), StandardCharsets.US_ASCII);
                     var iterator = source.db.newIterator(source.columns.get(WalletIndexCf.FIRST_SEEN))) {
                    for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                        byte[] key = iterator.key();
                        if ((key[0] & 255) >>> 4 == 8) continue; // Byron is outside this Shelley wallet oracle.
                        writer.write(HexFormat.of().formatHex(key) + ":" + ByteBuffer.wrap(iterator.value()).getLong() + "\n");
                    }
                    iterator.status();
                }
            }
            List<WalletCredential> credentials = new ArrayList<>(selected);
            JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("queries.json").toFile(), credentials);
            List<Map<String, Object>> results = new ArrayList<>();
            for (int size : List.of(1, 10, 200)) {
                if (credentials.size() < size) continue;
                var query = credentials.subList(0, size);
                Set<WalletCredential> querySet = Set.copyOf(query);
                Map<Outpoint, Utxo> tracked = new HashMap<>();
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long transactions = 0, confirmedBlocks = 0, previousBlock = -1;
                source.bodyReads = 0;
                long start = System.nanoTime();
                try (var scan = new WalletScanner(source, new WalletScanRequest(1, query, ChainPoint.ORIGIN, null, List.of()), coverage, end)) {
                    while (!scan.finished()) {
                        for (var event : scan.next()) {
                            if (!event.type().equals("transaction")) continue;
                            transactions++;
                            if (event.point().blockNumber() != previousBlock) confirmedBlocks++;
                            previousBlock = event.point().blockNumber();
                            digest.update((event.point().blockNumber() + ":" + event.point().slot() + ":" + event.txHash() + "\n").getBytes(StandardCharsets.US_ASCII));
                            if (event.inputs() != null) event.inputs().forEach(tracked::remove);
                            if (event.outputs() != null) for (Utxo out : event.outputs()) {
                                Set<WalletCredential> owners = new LinkedHashSet<>();
                                WalletCredentials.address(out.address(), owners);
                                if (owners.stream().anyMatch(querySet::contains)) tracked.put(out.outpoint(), out);
                            }
                        }
                    }
                }
                MessageDigest outputs = MessageDigest.getInstance("SHA-256");
                List<String> rows = new ArrayList<>();
                for (var entry : tracked.entrySet()) {
                    Utxo out = entry.getValue();
                    String assets = String.join(",", out.assets().stream()
                            .map(a -> a.policyId() + a.assetName() + "=" + a.quantity()).sorted().toList());
                    rows.add(entry.getKey().txHash() + "#" + entry.getKey().index() + ":" + out.lovelace() + ":" + assets);
                }
                rows.stream().sorted().forEach(row -> outputs.update((row + "\n").getBytes(StandardCharsets.US_ASCII)));
                results.add(Map.of("credentials", size, "transactions", transactions,
                        "transactionDigest", HexFormat.of().formatHex(digest.digest()),
                        "unspentOutputs", tracked.size(), "outputDigest", HexFormat.of().formatHex(outputs.digest()),
                        "candidateBodies", source.bodyReads, "confirmedBlocks", confirmedBlocks,
                        "scanWallSeconds", (System.nanoTime() - start) / 1e9));
                JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("actual.json").toFile(), results);
            }
        }
    }

    private static final class Source implements AutoCloseable, WalletScanner.Backend {
        private final DBOptions options = new DBOptions().setMaxOpenFiles(128);
        private final List<ColumnFamilyDescriptor> descriptors = new ArrayList<>();
        private final List<ColumnFamilyHandle> handles = new ArrayList<>();
        private final Map<String, ColumnFamilyHandle> columns = new LinkedHashMap<>();
        private final RocksDB db;
        private final WalletIndexStore indexes;
        private long bodyReads;
        Source(String path) throws Exception {
            try (Options listing = new Options()) {
                for (byte[] name : RocksDB.listColumnFamilies(listing, path)) descriptors.add(new ColumnFamilyDescriptor(name));
            }
            db = RocksDB.openReadOnly(options, path, descriptors, handles);
            for (int i = 0; i < handles.size(); i++) columns.put(new String(descriptors.get(i).getName(), StandardCharsets.UTF_8), handles.get(i));
            indexes = new WalletIndexStore(new RocksDbContext(db, columns), false, true);
        }
        ChainPoint last() throws Exception {
            try (var it = db.newIterator(columns.get("slot_by_number"))) {
                it.seekToLast();
                if (!it.isValid()) throw new IllegalStateException("No canonical blocks");
                return point(ByteBuffer.wrap(it.key()).getLong());
            }
        }
        ChainPoint point(long number) throws Exception {
            byte[] slot = db.get(columns.get("slot_by_number"), ByteBuffer.allocate(8).putLong(number).array());
            if (slot == null) throw new IllegalStateException("Missing block " + number);
            byte[] hash = db.get(columns.get("slot_to_hash"), slot);
            if (hash == null) throw new IllegalStateException("Missing hash " + number);
            return new ChainPoint(number, ByteBuffer.wrap(slot).getLong(), HexFormat.of().formatHex(hash));
        }
        byte[] bytes(ChainPoint point) throws Exception {
            byte[] body = db.get(columns.get("blocks"), HexFormat.of().parseHex(point.blockHash()));
            if (body == null) throw new IllegalStateException("Missing body " + point);
            return body;
        }
        @Override public void validate() { /* Read-only RocksDB view of a completed replay. */ }
        @Override public List<WalletIndexStore.FilterRecord> filters(long after, long to, int limit) {
            try { return indexes.readFilters(after, to, limit); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        }
        @Override public Block block(ChainPoint point) {
            try {
                if (!point.equals(point(point.blockNumber()))) throw new IllegalStateException("Noncanonical filter point");
                bodyReads++;
                var block = BlockSerializer.INSTANCE.deserialize(bytes(point));
                if (!block.getHeader().getHeaderBody().getBlockHash().equals(point.blockHash())) throw new IllegalStateException("Body hash mismatch");
                return block;
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }
        @Override public List<Utxo> genesis() { return List.of(); }
        @Override public void close() {
            handles.forEach(ColumnFamilyHandle::close);
            db.close();
            descriptors.forEach(descriptor -> descriptor.getOptions().close());
            options.close();
        }
    }
}
