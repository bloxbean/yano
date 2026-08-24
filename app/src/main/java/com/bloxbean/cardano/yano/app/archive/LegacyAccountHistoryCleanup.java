package com.bloxbean.cardano.yano.app.archive;

import org.rocksdb.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Offline, explicitly confirmed cleanup for unreleased PR-58 history column families. */
public final class LegacyAccountHistoryCleanup {
    private static final String CONFIRM = "DROP_LEGACY_ACCOUNT_HISTORY";
    private static final Set<String> TARGETS = Set.of("account_history", "account_history_delta");
    private LegacyAccountHistoryCleanup() { }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        Path database = Path.of(Objects.requireNonNull(options.get("--database"), "--database is required"));
        int dropped = cleanup(database, options.get("--confirm"));
        System.out.println("Dropped " + dropped + " legacy account-history column families from "
                + database.toAbsolutePath().normalize());
    }

    static int cleanup(Path requestedDatabase, String confirmation) throws Exception {
        if (!CONFIRM.equals(confirmation)) {
            throw new IllegalArgumentException("required: --confirm " + CONFIRM);
        }
        Path database = Objects.requireNonNull(requestedDatabase, "database")
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(database) || database.getParent() == null || database.getNameCount() < 2) {
            throw new IllegalArgumentException("database must be an existing, explicit RocksDB directory");
        }
        RocksDB.loadLibrary();
        List<byte[]> names;
        try (Options listOptions = new Options()) { names = RocksDB.listColumnFamilies(listOptions, database.toString()); }
        List<ColumnFamilyDescriptor> descriptors = names.stream()
                .map(name -> new ColumnFamilyDescriptor(name, new ColumnFamilyOptions())).toList();
        List<ColumnFamilyHandle> handles = new ArrayList<>();
        try (DBOptions dbOptions = new DBOptions().setCreateIfMissing(false);
             RocksDB db = RocksDB.open(dbOptions, database.toString(), descriptors, handles)) {
            int dropped = 0;
            for (int i = 0; i < names.size(); i++) {
                String name = new String(names.get(i), StandardCharsets.UTF_8);
                if (TARGETS.contains(name)) { db.dropColumnFamily(handles.get(i)); dropped++; }
            }
            return dropped;
        } finally {
            handles.forEach(ColumnFamilyHandle::close);
            descriptors.forEach(descriptor -> descriptor.getOptions().close());
        }
    }

    private static Map<String, String> parse(String[] args) {
        if (args.length % 2 != 0) throw new IllegalArgumentException("arguments must be --name value pairs");
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) result.put(args[i], args[i + 1]);
        return result;
    }
}
