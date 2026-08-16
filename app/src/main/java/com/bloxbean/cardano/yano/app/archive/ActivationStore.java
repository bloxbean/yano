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
    private static final String LEGACY_ADDRESS_SUBJECTS =
            "address,payment_credential,stake_credential";
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

    synchronized OptionalLong hotStart(ArchiveDatasetId dataset) {
        String value = values.getProperty(dataset.name() + ".HOT_START");
        return value == null ? OptionalLong.empty() : OptionalLong.of(Long.parseLong(value));
    }

    synchronized void setHotStart(ArchiveDatasetId dataset, long start) {
        if (start < 0) throw new IllegalArgumentException("hot start must be non-negative");
        String key = dataset.name() + ".HOT_START";
        String previous = (String) values.setProperty(key, Long.toString(start));
        try { persist(); }
        catch (RuntimeException e) {
            if (previous == null) values.remove(key); else values.setProperty(key, previous);
            throw e;
        }
    }

    /**
     * Resolve a table projection cutoff without ever backfilling a table that
     * was enabled after its parent dataset. Disabled tables retain old rows;
     * re-enabling starts a new live interval at {@code currentTip + 1}.
     */
    synchronized OptionalLong configureTable(ArchiveDatasetId dataset, String table, boolean enabled,
                                              boolean datasetAlreadyActivated, long datasetStart,
                                              long currentTip, long firstCanonicalBlock) {
        String enabledKey = tableKey(dataset, table, "enabled");
        String startKey = tableKey(dataset, table, "start");
        String previousEnabled = values.getProperty(enabledKey);
        if (!enabled) {
            if (!"false".equals(previousEnabled)) {
                values.setProperty(enabledKey, "false");
                try {
                    persist();
                } catch (RuntimeException e) {
                    if (previousEnabled == null) values.remove(enabledKey);
                    else values.setProperty(enabledKey, previousEnabled);
                    throw e;
                }
            }
            return OptionalLong.empty();
        }

        String existingStart = values.getProperty(startKey);
        if ("true".equals(previousEnabled) && existingStart != null) {
            return OptionalLong.of(Long.parseLong(existingStart));
        }

        long liveStart = Math.max(firstCanonicalBlock, Math.addExact(currentTip, 1));
        long start = previousEnabled == null && !datasetAlreadyActivated && datasetStart >= 0
                ? datasetStart : liveStart;
        if (start < 0) throw new IllegalArgumentException("table activation start must be non-negative");
        String previousStart = values.getProperty(startKey);
        values.setProperty(enabledKey, "true");
        values.setProperty(startKey, Long.toString(start));
        try {
            persist();
        } catch (RuntimeException e) {
            if (previousEnabled == null) values.remove(enabledKey);
            else values.setProperty(enabledKey, previousEnabled);
            if (previousStart == null) values.remove(startKey);
            else values.setProperty(startKey, previousStart);
            throw e;
        }
        return OptionalLong.of(start);
    }

    /**
     * Pins address subject selection for the lifetime of an activated archive.
     * Archives created before subject selection existed are treated as all-subject.
     */
    synchronized void configureAddressSubjects(String fingerprint, boolean datasetAlreadyActivated) {
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new IllegalArgumentException("address subject fingerprint is required");
        }
        String key = ArchiveDatasetId.ADDRESS_TRANSACTION.name() + ".SUBJECTS";
        String stored = values.getProperty(key);
        String effectiveStored = stored == null && datasetAlreadyActivated
                ? LEGACY_ADDRESS_SUBJECTS : stored;
        if (effectiveStored != null && !effectiveStored.equals(fingerprint) && datasetAlreadyActivated) {
            throw new IllegalStateException("address-transactions subject selection changed from "
                    + effectiveStored + " to " + fingerprint
                    + "; use a new history directory or rebuild the archive");
        }
        if (fingerprint.equals(stored)) return;
        String previous = (String) values.setProperty(key, fingerprint);
        try {
            persist();
        } catch (RuntimeException e) {
            if (previous == null) values.remove(key);
            else values.setProperty(key, previous);
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
        // Track-specific keys remain readable for unreleased development
        // archives. New block-history composition uses the unqualified
        // activation plus the explicit HOT_START phase boundary.
        return track == ArchiveTrack.BACKFILL ? dataset.name() : dataset.name() + ".LIVE";
    }

    private static String tableKey(ArchiveDatasetId dataset, String table, String suffix) {
        return dataset.name() + ".TABLE." + table + '.' + suffix;
    }
}
