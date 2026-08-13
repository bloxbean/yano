package com.bloxbean.cardano.yano.archive.core.source;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/** Immutable restartable epoch staging with streaming writes and bounded reads. */
public final class DurableEpochFileSource<T> implements EpochArchiveSource<T> {
    private final ArchiveDatasetId dataset;
    private final Path directory;
    private final EpochFactCodec<T> codec;

    public DurableEpochFileSource(ArchiveDatasetId dataset, Path directory, EpochFactCodec<T> codec) {
        if (dataset.sourceKind() != com.bloxbean.cardano.yano.archive.api.SourceKind.EPOCH) {
            throw new IllegalArgumentException("durable epoch source requires epoch dataset");
        }
        this.dataset = dataset;
        this.directory = directory.toAbsolutePath().normalize();
        this.codec = java.util.Objects.requireNonNull(codec, "codec");
        try { Files.createDirectories(this.directory); }
        catch (Exception e) { throw new ArchiveStoreException("cannot create epoch source directory", e); }
    }

    public void stage(EpochArchiveJob job, Iterable<T> facts) {
        try (var writer = open(job)) {
            for (T fact : facts) writer.append(fact);
            writer.commit();
        }
    }

    public StagingWriter<T> open(EpochArchiveJob job) {
        requireJob(job);
        Path rows = rows(job.jobId());
        Path manifest = manifest(job.jobId());
        if (Files.exists(rows) && Files.exists(manifest)) return StagingWriter.noop();
        try {
            Path temporaryRows = directory.resolve(job.jobId() + ".rows.partial");
            Files.deleteIfExists(temporaryRows);
            return new FileStagingWriter(job, temporaryRows);
        } catch (Exception e) {
            throw new ArchiveStoreException("cannot open epoch source staging " + job.jobId(), e);
        }
    }

    @Override public ArchiveDatasetId dataset() { return dataset; }

    @Override
    public List<EpochArchiveJob> pendingAfter(long epochExclusive, int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".properties"))
                    .map(this::readManifest).filter(job -> job.epoch() > epochExclusive)
                    .sorted(Comparator.comparingLong(EpochArchiveJob::epoch)).limit(limit).toList();
        } catch (Exception e) { throw new ArchiveStoreException("cannot list epoch sources", e); }
    }

    @Override public Optional<EpochArchiveJob> find(UUID jobId) {
        Path manifest = manifest(jobId);
        return Files.exists(manifest) ? Optional.of(readManifest(manifest)) : Optional.empty();
    }

    @Override
    public void acknowledge(EpochArchiveJob job) {
        requireJob(job);
        try {
            Files.deleteIfExists(manifest(job.jobId()));
            Files.deleteIfExists(rows(job.jobId()));
        } catch (Exception e) {
            throw new ArchiveStoreException("cannot acknowledge epoch source " + job.jobId(), e);
        }
    }

    /**
     * Removes uncommitted source material made non-canonical by a rollback.
     * This is intentionally source-local: committed archive rows are
     * invalidated separately by the backend generation protocol.
     */
    public int discardAfterEpoch(long epochInclusive) {
        int discarded = 0;
        try (var files = Files.list(directory)) {
            for (Path path : files.filter(candidate -> candidate.getFileName().toString().endsWith(".properties"))
                    .toList()) {
                EpochArchiveJob job = readManifest(path);
                if (job.epoch() > epochInclusive) {
                    acknowledge(job);
                    discarded++;
                }
            }
            return discarded;
        } catch (Exception e) {
            throw e instanceof ArchiveStoreException store ? store
                    : new ArchiveStoreException("cannot discard rolled-back epoch sources", e);
        }
    }

    @Override public ArchiveSourceLease acquire(EpochArchiveJob job, Instant expiresAt) {
        requireJob(job);
        if (!expiresAt.isAfter(Instant.now()) || find(job.jobId()).isEmpty()) {
            throw new ArchiveStoreException("cannot lease missing/expired epoch source");
        }
        UUID leaseId = UUID.randomUUID();
        Path lease = directory.resolve(job.jobId() + ".lease-" + leaseId);
        try { Files.writeString(lease, Long.toString(expiresAt.toEpochMilli())); }
        catch (Exception e) { throw new ArchiveStoreException("cannot persist epoch source lease", e); }
        return new ArchiveSourceLease() {
            private Instant expiry = expiresAt;
            private boolean closed;
            public UUID leaseId() { return leaseId; }
            public Instant expiresAt() { return expiry; }
            public ArchiveSourceLease renew(Instant value) {
                if (closed || !value.isAfter(Instant.now())) throw new IllegalStateException("invalid lease renewal");
                try { Files.writeString(lease, Long.toString(value.toEpochMilli())); expiry = value; return this; }
                catch (Exception e) { throw new ArchiveStoreException("cannot renew epoch lease", e); }
            }
            public void close() { if (!closed) { closed = true; try { Files.deleteIfExists(lease); } catch (Exception ignored) { } } }
        };
    }

    @Override
    public EpochSourcePage<T> read(EpochArchiveJob job, Optional<String> cursor, int limit,
                                   ArchiveSourceLease lease) {
        requireJob(job);
        if (limit < 1 || !lease.expiresAt().isAfter(Instant.now())) throw new ArchiveStoreException("invalid epoch read lease");
        long skip = cursor.map(Long::parseLong).orElse(0L);
        List<T> result = new ArrayList<>(limit);
        long position = 0;
        boolean more = false;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(rows(job.jobId()))))) {
            while (true) {
                int length;
                try { length = in.readInt(); } catch (java.io.EOFException eof) { break; }
                if (length < 0 || length > 64 * 1024 * 1024) throw new ArchiveStoreException("invalid epoch fact length");
                byte[] encoded = in.readNBytes(length);
                if (encoded.length != length) throw new ArchiveStoreException("truncated epoch fact");
                if (position++ < skip) continue;
                if (result.size() == limit) { more = true; break; }
                result.add(codec.decode(encoded));
            }
        } catch (Exception e) { throw new ArchiveStoreException("cannot read epoch source page", e); }
        Optional<String> next = more ? Optional.of(Long.toString(skip + result.size())) : Optional.empty();
        return new EpochSourcePage<>(job, result, limit, next);
    }

    private void requireJob(EpochArchiveJob job) {
        if (job.dataset() != dataset) throw new IllegalArgumentException("epoch source dataset mismatch");
    }

    private Properties properties(EpochArchiveJob job) {
        Properties p = new Properties();
        p.setProperty("jobId", job.jobId().toString()); p.setProperty("magic", Integer.toString(job.networkIdentity().networkMagic()));
        p.setProperty("genesis", job.networkIdentity().genesisHash()); p.setProperty("dataset", job.dataset().name());
        p.setProperty("projection", Integer.toString(job.projectionVersion())); p.setProperty("epoch", Long.toString(job.epoch()));
        p.setProperty("block", Long.toString(job.boundaryBlockNumber())); p.setProperty("slot", Long.toString(job.boundarySlot()));
        p.setProperty("blockTime", Long.toString(job.boundaryBlockTime()));
        p.setProperty("hash", java.util.HexFormat.of().formatHex(job.boundaryBlockHash()));
        p.setProperty("state", job.sourceStateVersion()); p.setProperty("reference", job.sourceReference());
        p.setProperty("created", Long.toString(job.createdAt().toEpochMilli())); return p;
    }

    private EpochArchiveJob readManifest(Path path) {
        try (var in = Files.newInputStream(path)) {
            Properties p = new Properties(); p.load(in);
            return new EpochArchiveJob(UUID.fromString(p.getProperty("jobId")),
                    new ArchiveNetworkIdentity(Integer.parseInt(p.getProperty("magic")), p.getProperty("genesis")),
                    ArchiveDatasetId.valueOf(p.getProperty("dataset")), Integer.parseInt(p.getProperty("projection")),
                    Long.parseLong(p.getProperty("epoch")), Long.parseLong(p.getProperty("block")),
                    Long.parseLong(p.getProperty("slot")), Long.parseLong(p.getProperty("blockTime")),
                    java.util.HexFormat.of().parseHex(p.getProperty("hash")),
                    p.getProperty("state"), p.getProperty("reference"),
                    Instant.ofEpochMilli(Long.parseLong(p.getProperty("created"))));
        } catch (Exception e) { throw new ArchiveStoreException("invalid epoch source manifest " + path, e); }
    }

    private Path rows(UUID id) { return directory.resolve(id + ".rows"); }
    private Path manifest(UUID id) { return directory.resolve(id + ".properties"); }
    private void move(Path from, Path to) throws java.io.IOException {
        try { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public interface StagingWriter<T> extends AutoCloseable {
        void append(T fact);
        void commit();
        @Override void close();
        static <T> StagingWriter<T> noop() {
            return new StagingWriter<>() {
                public void append(T ignored) { }
                public void commit() { }
                public void close() { }
            };
        }
    }

    private final class FileStagingWriter implements StagingWriter<T> {
        private final EpochArchiveJob job;
        private final Path temporary;
        private final DataOutputStream output;
        private long count;
        private boolean committed;

        private FileStagingWriter(EpochArchiveJob job, Path temporary) throws java.io.IOException {
            this.job = job;
            this.temporary = temporary;
            this.output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)));
        }

        public void append(T fact) {
            if (committed) throw new IllegalStateException("epoch source writer is closed");
            try {
                byte[] encoded = codec.encode(fact);
                output.writeInt(encoded.length);
                output.write(encoded);
                count++;
            } catch (Exception e) {
                throw new ArchiveStoreException("cannot append epoch source " + job.jobId(), e);
            }
        }

        public void commit() {
            if (committed) return;
            try {
                output.flush();
                output.close();
                Properties p = properties(job);
                p.setProperty("rowCount", Long.toString(count));
                Path temporaryManifest = Files.createTempFile(directory, job.jobId().toString(), ".manifest.tmp");
                try (var out = Files.newOutputStream(temporaryManifest)) { p.store(out, "yano epoch source"); }
                move(temporary, rows(job.jobId()));
                move(temporaryManifest, manifest(job.jobId()));
                committed = true;
            } catch (Exception e) {
                throw new ArchiveStoreException("cannot commit epoch source " + job.jobId(), e);
            }
        }

        public void close() {
            if (committed) return;
            try { output.close(); } catch (Exception ignored) { }
            try { Files.deleteIfExists(temporary); } catch (Exception ignored) { }
        }
    }
}
