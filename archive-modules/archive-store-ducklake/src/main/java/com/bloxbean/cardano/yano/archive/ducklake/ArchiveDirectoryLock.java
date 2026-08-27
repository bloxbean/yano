package com.bloxbean.cardano.yano.archive.ducklake;

import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class ArchiveDirectoryLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    ArchiveDirectoryLock(Path catalogPath) {
        try {
            Path parent = catalogPath.toAbsolutePath().normalize().getParent();
            if (parent == null) throw new IOException("catalog path has no parent");
            Files.createDirectories(parent);
            Path lockPath = parent.resolve(catalogPath.getFileName() + ".writer.lock");
            channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                channel.close();
                throw new ArchiveStoreException("DuckLake archive already has a writer: " + catalogPath, e);
            }
            if (lock == null) {
                channel.close();
                throw new ArchiveStoreException("DuckLake archive already has a writer: " + catalogPath);
            }
        } catch (ArchiveStoreException e) {
            throw e;
        } catch (Exception e) {
            throw new ArchiveStoreException("cannot acquire DuckLake writer lock for " + catalogPath, e);
        }
    }

    @Override
    public void close() {
        try {
            lock.release();
        } catch (IOException ignored) {
        }
        try {
            channel.close();
        } catch (IOException ignored) {
        }
    }
}
