package com.bloxbean.cardano.yano.archive.core.source;

import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Power-loss durability and integrity for staged epoch evidence.
 *
 * <p>An atomic rename makes a file appear whole to a concurrent reader; it does not make it
 * survive power loss. Without an fsync of the contents before the rename, and of the containing
 * directory after it, the operating system is free to have neither the bytes nor the rename on
 * disk when the machine comes back. That is acceptable for a cache and unacceptable for
 * irreproducible boundary evidence: rewards, DRep state and governance decisions cannot be
 * recomputed once the boundary has passed.
 *
 * <p>The checksum is the other half. A file can be present and durable and still be truncated -
 * a short write that completed before the crash - and a truncated reward file is worse than a
 * missing one, because it looks like a complete epoch with fewer rows. The digest is written into
 * the manifest and verified before any row is served, so corruption fails closed instead of
 * quietly shortening an epoch.
 */
public final class DurableFiles {

    private DurableFiles() {}

    /** Flush a file's contents and metadata to stable storage. */
    public static void syncFile(Path path) {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException e) {
            throw new ArchiveStoreException("cannot fsync " + path, e);
        }
    }

    /**
     * Flush a directory entry, so a rename into it survives power loss.
     *
     * <p>Opening a directory for read is not portable everywhere; where it is refused, the rename
     * is still atomic and the failure is not worth aborting a boundary for, so it is ignored
     * rather than raised. On Linux and macOS - the platforms this runs on - it works.
     */
    public static void syncDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException unsupported) {
            // Directory fsync is not universally permitted; the rename remains atomic.
        }
    }

    /**
     * Atomically publish {@code from} as {@code to}, durably.
     *
     * <p>Order matters and is not interchangeable: contents first, then the rename, then the
     * directory. Syncing the directory before the contents would durably record a name pointing
     * at bytes that may not exist.
     */
    public static void publish(Path from, Path to) {
        syncFile(from);
        try {
            try {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new ArchiveStoreException("cannot publish " + from + " as " + to, e);
        }
        syncDirectory(to.getParent());
    }

    /** SHA-256 over a file, streamed so a large epoch never lands in memory. */
    public static String checksum(Path path) {
        try (InputStream in = Files.newInputStream(path);
             DigestInputStream digest = new DigestInputStream(in, sha256())) {
            byte[] buffer = new byte[1 << 16];
            while (digest.read(buffer) >= 0) {
                // consume
            }
            return HexFormat.of().formatHex(digest.getMessageDigest().digest());
        } catch (IOException e) {
            throw new ArchiveStoreException("cannot checksum " + path, e);
        }
    }

    /**
     * Verify staged evidence before it is used, failing closed on any doubt.
     *
     * @throws ArchiveStoreException when the file is absent, the wrong size, or the wrong digest
     */
    public static void verify(Path path, String expectedChecksum, long expectedBytes) {
        if (!Files.isRegularFile(path)) {
            throw new ArchiveStoreException("staged epoch evidence is missing: " + path
                    + "; it cannot be recomputed once the boundary has passed");
        }
        long actualBytes;
        try {
            actualBytes = Files.size(path);
        } catch (IOException e) {
            throw new ArchiveStoreException("cannot size staged epoch evidence " + path, e);
        }
        if (expectedBytes >= 0 && actualBytes != expectedBytes) {
            throw new ArchiveStoreException("staged epoch evidence " + path + " is " + actualBytes
                    + " bytes but its manifest records " + expectedBytes
                    + "; a truncated epoch would look like a complete one with fewer rows");
        }
        if (expectedChecksum != null && !expectedChecksum.isBlank()) {
            String actual = checksum(path);
            if (!actual.equalsIgnoreCase(expectedChecksum)) {
                throw new ArchiveStoreException("staged epoch evidence " + path
                        + " has checksum " + actual + " but its manifest records " + expectedChecksum
                        + "; the evidence is corrupt and cannot be reproduced");
            }
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
