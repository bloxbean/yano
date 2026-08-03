package com.bloxbean.cardano.yano.appchain.eutxo.indexer.jdbc;

import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoIndexStore;
import com.bloxbean.cardano.yano.appchain.eutxo.indexer.EutxoIndexStoreContext;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Builds a complete disposable index beside the active file and activates it atomically. */
public final class SqliteIndexRebuilder {
    private SqliteIndexRebuilder() {
    }

    public static Path rebuild(
            EutxoIndexStoreContext target,
            Consumer<EutxoIndexStore> replay
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(replay, "replay");
        if (!target.settings().getOrDefault("jdbc.url", "").isBlank()) {
            throw new IllegalArgumentException(
                    "shadow rebuild requires the managed default SQLite path");
        }
        Path data = target.dataDirectory();
        Path database = data.resolve(SqliteEutxoIndexStore.DEFAULT_FILE);
        validateExistingOwnership(data, database, target.identity().digest());
        Path parent = Objects.requireNonNull(data.getParent(),
                "index data directory must have a parent");
        Path shadow = null;
        try {
            Files.createDirectories(parent);
            shadow = Files.createTempDirectory(
                    parent, ".eutxo-index-rebuild-");
            EutxoIndexStoreContext shadowContext =
                    new EutxoIndexStoreContext(
                            target.identity(), shadow, Map.of());
            try (EutxoIndexStore store =
                         SqliteEutxoIndexStore.open(shadowContext)) {
                replay.accept(store);
            }
            Path shadowDatabase =
                    shadow.resolve(SqliteEutxoIndexStore.DEFAULT_FILE);
            if (!Files.isRegularFile(shadowDatabase)) {
                throw new IllegalStateException(
                        "shadow rebuild did not produce a database");
            }
            Files.createDirectories(data);
            moveAtomically(shadowDatabase, database);
            Files.copy(
                    shadow.resolve(SqliteEutxoIndexStore.MARKER_FILE),
                    data.resolve(SqliteEutxoIndexStore.MARKER_FILE),
                    StandardCopyOption.REPLACE_EXISTING);
            return database;
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "cannot activate rebuilt EUTxO index", failure);
        } finally {
            deleteShadow(shadow);
        }
    }

    private static void validateExistingOwnership(
            Path data,
            Path database,
            String identityDigest
    ) {
        if (!Files.exists(database)) {
            return;
        }
        Path marker = data.resolve(SqliteEutxoIndexStore.MARKER_FILE);
        try {
            if (!Files.isRegularFile(marker)
                    || !Files.readString(marker).contains(identityDigest)) {
                throw new IllegalStateException(
                        "refusing to replace an unowned SQLite database");
            }
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "cannot validate existing index ownership", failure);
        }
    }

    private static void moveAtomically(Path source, Path target)
            throws IOException {
        try {
            Files.move(
                    source, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(
                    source, target,
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteShadow(Path shadow) {
        if (shadow == null || !shadow.getFileName().toString()
                .startsWith(".eutxo-index-rebuild-")) {
            return;
        }
        try (var paths = Files.walk(shadow)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A stale marker-owned shadow is safe to remove on next startup.
                }
            });
        } catch (IOException ignored) {
            // Rebuild success is not reversed because temp cleanup failed.
        }
    }
}
