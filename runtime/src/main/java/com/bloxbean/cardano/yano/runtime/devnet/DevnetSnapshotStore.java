package com.bloxbean.cardano.yano.runtime.devnet;

import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yano.api.model.SnapshotInfo;
import com.bloxbean.cardano.yano.runtime.blockproducer.BlockProducerService;
import com.bloxbean.cardano.yano.runtime.chain.ChainStateSnapshots;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * File-system backed devnet snapshot catalog.
 */
@Slf4j
public final class DevnetSnapshotStore {
    private static final Pattern SNAPSHOT_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private final ChainState chainState;
    private final ChainStateSnapshots snapshots;
    private final BlockProducerService blockProducerService;

    public DevnetSnapshotStore(ChainState chainState, ChainStateSnapshots snapshots,
                               BlockProducerService blockProducerService) {
        this.chainState = chainState;
        this.snapshots = snapshots;
        this.blockProducerService = blockProducerService;
    }

    public SnapshotInfo create(String name) {
        validateName(name);

        Path snapshotDir = snapshotDir(name);
        Path checkpointDir = snapshotDir.resolve("checkpoint");

        if (Files.exists(snapshotDir)) {
            throw new IllegalArgumentException("Snapshot '" + name + "' already exists");
        }

        boolean wasRunning = blockProducerService != null && blockProducerService.isRunning();
        if (wasRunning) blockProducerService.stop();

        try {
            Files.createDirectories(snapshotDir);
            snapshots.createSnapshot(checkpointDir.toString());

            ChainTip tip = chainState.getTip();
            long slot = tip != null ? tip.getSlot() : 0;
            long blockNumber = tip != null ? tip.getBlockNumber() : 0;
            long createdAt = System.currentTimeMillis();

            var metaJson = String.format(
                    "{\"name\":\"%s\",\"slot\":%d,\"blockNumber\":%d,\"createdAt\":%d}",
                    name, slot, blockNumber, createdAt);
            Files.writeString(snapshotDir.resolve("snapshot-meta.json"), metaJson);

            log.info("Snapshot '{}' created: slot={}, block={}", name, slot, blockNumber);
            return new SnapshotInfo(name, slot, blockNumber, createdAt);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create snapshot '" + name + "'", e);
        } finally {
            if (wasRunning) blockProducerService.start();
        }
    }

    public List<SnapshotInfo> list() {
        Path snapshotsDir = snapshotsDir();
        if (!Files.isDirectory(snapshotsDir)) {
            return List.of();
        }

        var results = new ArrayList<SnapshotInfo>();
        try (var dirs = Files.list(snapshotsDir)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                Path metaFile = dir.resolve("snapshot-meta.json");
                if (Files.exists(metaFile)) {
                    try {
                        String json = Files.readString(metaFile);
                        var info = parseSnapshotMeta(json);
                        if (info != null) results.add(info);
                    } catch (Exception e) {
                        log.warn("Failed to read snapshot metadata: {}", metaFile, e);
                    }
                }
            });
        } catch (Exception e) {
            log.warn("Failed to list snapshots", e);
        }

        results.sort(Comparator.comparingLong(SnapshotInfo::createdAt));
        return results;
    }

    public void delete(String name) {
        validateName(name);

        Path snapshotDir = snapshotDir(name);
        if (!Files.isDirectory(snapshotDir)) {
            throw new IllegalArgumentException("Snapshot '" + name + "' does not exist");
        }

        try {
            deleteRecursively(snapshotDir);
            log.info("Snapshot '{}' deleted", name);
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete snapshot '" + name + "'", e);
        }
    }

    public Path checkpointDir(String name) {
        validateName(name);
        return snapshotDir(name).resolve("checkpoint");
    }

    private Path snapshotsDir() {
        Path dbPath = Path.of(snapshots.getDbPath()).toAbsolutePath().normalize();
        Path parent = dbPath.getParent();
        if (parent == null) {
            throw new IllegalStateException("Cannot resolve snapshot root for " + dbPath);
        }
        return parent.resolve("snapshots").normalize();
    }

    private Path snapshotDir(String name) {
        validateName(name);
        Path root = snapshotsDir();
        Path snapshotDir = root.resolve(name).normalize();
        if (!snapshotDir.startsWith(root)) {
            throw new IllegalArgumentException("Invalid snapshot name: " + name);
        }
        return snapshotDir;
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Snapshot name must not be empty");
        }
        if (".".equals(name) || "..".equals(name) || !SNAPSHOT_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid snapshot name: " + name);
        }
    }

    static SnapshotInfo parseSnapshotMeta(String json) {
        try {
            String name = extractJsonString(json, "name");
            long slot = extractJsonLong(json, "slot");
            long blockNumber = extractJsonLong(json, "blockNumber");
            long createdAt = extractJsonLong(json, "createdAt");
            return new SnapshotInfo(name, slot, blockNumber, createdAt);
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }

    private static long extractJsonLong(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start < 0) return 0;
        start += search.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        return Long.parseLong(json.substring(start, end));
    }

    private static void deleteRecursively(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
