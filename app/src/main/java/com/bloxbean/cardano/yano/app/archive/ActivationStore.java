package com.bloxbean.cardano.yano.app.archive;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.core.worker.ArchiveTrack;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.OptionalLong;
import java.util.Properties;

/** Small durable activation-anchor store; values are immutable once selected. */
final class ActivationStore {
    private final Path path;
    private final Properties values = new Properties();

    ActivationStore(Path path) {
        this.path = path;
        try {
            if (Files.exists(path)) try (InputStream input = Files.newInputStream(path)) { values.load(input); }
        } catch (Exception e) {
            throw new IllegalStateException("cannot read history activation anchors", e);
        }
    }

    synchronized OptionalLong start(ArchiveDatasetId dataset) {
        return start(dataset, ArchiveTrack.BACKFILL);
    }

    synchronized OptionalLong start(ArchiveDatasetId dataset, ArchiveTrack track) {
        String value = values.getProperty(key(dataset, track));
        return value == null ? OptionalLong.empty() : OptionalLong.of(Long.parseLong(value));
    }

    synchronized void putIfAbsent(ArchiveDatasetId dataset, long start) {
        putIfAbsent(dataset, ArchiveTrack.BACKFILL, start);
    }

    synchronized void putIfAbsent(ArchiveDatasetId dataset, ArchiveTrack track, long start) {
        String key = key(dataset, track);
        if (start < 0 || values.containsKey(key)) return;
        values.setProperty(key, Long.toString(start));
        try {
            Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                values.store(output, "ADR-034 immutable dataset activation anchors");
            }
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            values.remove(key);
            throw new IllegalStateException("cannot persist history activation anchor", e);
        }
    }

    synchronized void replace(ArchiveDatasetId dataset, ArchiveTrack track, long start) {
        if (start < 0) throw new IllegalArgumentException("activation start must be non-negative");
        String key = key(dataset, track);
        String previous = (String) values.setProperty(key, Long.toString(start));
        try { persist(); }
        catch (RuntimeException e) {
            if (previous == null) values.remove(key); else values.setProperty(key, previous);
            throw e;
        }
    }

    private void persist() {
        try {
            Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                values.store(output, "ADR-034 immutable dataset activation anchors");
            }
            try { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception e) { throw new IllegalStateException("cannot persist history activation anchor", e); }
    }

    private static String key(ArchiveDatasetId dataset, ArchiveTrack track) {
        // Preserve the original backfill key format for phase-10 development
        // archives while adding an independent live anchor.
        return track == ArchiveTrack.BACKFILL ? dataset.name() : dataset.name() + ".LIVE";
    }
}
