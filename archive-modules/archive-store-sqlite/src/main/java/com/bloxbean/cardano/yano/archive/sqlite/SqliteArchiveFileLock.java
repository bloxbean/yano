package com.bloxbean.cardano.yano.archive.sqlite;

import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class SqliteArchiveFileLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    SqliteArchiveFileLock(Path databasePath) {
        try {
            Path parent = databasePath.getParent();
            if (parent == null) throw new ArchiveStoreException("SQLite archive path has no parent");
            Files.createDirectories(parent);
            channel = FileChannel.open(parent.resolve(databasePath.getFileName() + ".writer.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                channel.close();
                throw new ArchiveStoreException("SQLite archive already has a writer", e);
            }
            if (lock == null) {
                channel.close();
                throw new ArchiveStoreException("SQLite archive already has a writer");
            }
        } catch (ArchiveStoreException e) {
            throw e;
        } catch (Exception e) {
            throw new ArchiveStoreException("cannot lock SQLite archive", e);
        }
    }

    @Override
    public void close() {
        try { lock.release(); } catch (Exception ignored) { }
        try { channel.close(); } catch (Exception ignored) { }
    }
}
