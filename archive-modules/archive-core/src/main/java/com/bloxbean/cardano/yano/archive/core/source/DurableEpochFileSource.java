package com.bloxbean.cardano.yano.archive.core.source;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
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
        try {
            Files.createDirectories(this.directory);
            cleanInterruptedWrites();
        }
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

    /** Removes source jobs whose boundary block is no longer canonical. */
    public int discardAfterBlock(long blockInclusive) {
        int discarded = 0;
        try (var files = Files.list(directory)) {
            for (Path path : files.filter(candidate -> candidate.getFileName().toString().endsWith(".properties"))
                    .toList()) {
                EpochArchiveJob job = readManifest(path);
                if (job.boundaryBlockNumber() > blockInclusive) {
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
        // Verify before serving a single row. Boundary evidence is irreproducible, so a
        // truncated file must fail loudly rather than present as a complete epoch with fewer
        // rows - which is exactly what the row loop below would otherwise do at EOF.
        verifyEvidence(job);
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

    /**
     * What a committed job's evidence contains, for the artifact reference that will point at it.
     *
     * @param rowCount rows staged, so the sink can verify the commit was complete
     * @param checksum SHA-256 of the rows file, binding the reference to these exact bytes
     * @param bytes    size of the rows file
     */
    public record StagedEvidence(long rowCount, String checksum, long bytes) { }

    /**
     * Read the integrity metadata of an already-committed job.
     *
     * <p>Called after {@code commit()} so the projection can record a reference bound to the
     * exact evidence on disk. A job without integrity fields is refused here for the same reason
     * it is refused on read.
     */
    public StagedEvidence evidenceOf(EpochArchiveJob job) {
        requireJob(job);
        Properties p;
        try (var in = Files.newInputStream(manifest(job.jobId()))) {
            p = new Properties(); p.load(in);
        } catch (Exception e) {
            throw new ArchiveStoreException("cannot read epoch source manifest for " + job.jobId(), e);
        }
        String checksum = p.getProperty("rowChecksum");
        if (checksum == null || checksum.isBlank()) {
            throw new ArchiveStoreException("epoch source " + job.jobId() + " has no recorded checksum");
        }
        return new StagedEvidence(Long.parseLong(p.getProperty("rowCount", "-1")), checksum,
                Long.parseLong(p.getProperty("rowBytes", "-1")));
    }

    /**
     * Publish already-durable provisional rows under their final canonical job identity.
     * The caller retains the provisional source until sink acknowledgement.
     */
    public StagedEvidence adopt(EpochArchiveJob job, Path sourceRows, StagedEvidence evidence) {
        requireJob(job);
        if (Files.exists(manifest(job.jobId()))) {
            StagedEvidence existing = evidenceOf(job);
            if (!existing.equals(evidence)) {
                throw new ArchiveStoreException("bound epoch source evidence changed for " + job.jobId());
            }
            verifyEvidence(job);
            return existing;
        }

        DurableFiles.verify(sourceRows, evidence.checksum(), evidence.bytes());
        Path temporaryRows = directory.resolve(job.jobId() + ".rows.partial");
        try {
            Files.deleteIfExists(temporaryRows);
            try {
                Files.createLink(temporaryRows, sourceRows);
            } catch (UnsupportedOperationException | IOException unsupported) {
                Files.copy(sourceRows, temporaryRows, StandardCopyOption.REPLACE_EXISTING);
            }
            DurableFiles.publish(temporaryRows, rows(job.jobId()));

            Properties p = properties(job);
            p.setProperty("rowCount", Long.toString(evidence.rowCount()));
            p.setProperty("rowBytes", Long.toString(evidence.bytes()));
            p.setProperty("rowChecksum", evidence.checksum());
            Path temporaryManifest = Files.createTempFile(directory, job.jobId().toString(), ".manifest.tmp");
            try (var out = Files.newOutputStream(temporaryManifest)) {
                p.store(out, "yano epoch source");
            }
            DurableFiles.publish(temporaryManifest, manifest(job.jobId()));
            return evidence;
        } catch (Exception e) {
            throw e instanceof ArchiveStoreException store ? store
                    : new ArchiveStoreException("cannot adopt provisional epoch source " + job.jobId(), e);
        } finally {
            try { Files.deleteIfExists(temporaryRows); } catch (Exception ignored) { }
        }
    }

    /**
     * Check staged evidence against the size and digest its manifest recorded.
     *
     * <p>Only checked once per job: the digest costs a full pass over the file, and a page read
     * happens many times per job. A manifest without integrity fields predates hardening and is
     * refused rather than trusted - accepting it would defeat the guarantee for exactly the files
     * most likely to be damaged.
     */
    private void verifyEvidence(EpochArchiveJob job) {
        if (!verifiedJobs.add(job.jobId())) return;
        Properties p;
        try (var in = Files.newInputStream(manifest(job.jobId()))) {
            p = new Properties(); p.load(in);
        } catch (Exception e) {
            throw new ArchiveStoreException("cannot read epoch source manifest for " + job.jobId(), e);
        }
        String checksum = p.getProperty("rowChecksum");
        if (checksum == null || checksum.isBlank()) {
            throw new ArchiveStoreException("epoch source " + job.jobId() + " has no recorded"
                    + " checksum; it was staged before integrity hardening and cannot be trusted"
                    + " as irreproducible boundary evidence");
        }
        long bytes = Long.parseLong(p.getProperty("rowBytes", "-1"));
        DurableFiles.verify(rows(job.jobId()), checksum, bytes);
    }

    /** Jobs whose evidence has already been verified in this process. */
    private final java.util.Set<UUID> verifiedJobs = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private Path rows(UUID id) { return directory.resolve(id + ".rows"); }
    private Path manifest(UUID id) { return directory.resolve(id + ".properties"); }

    private void cleanInterruptedWrites() throws java.io.IOException {
        try (var files = Files.list(directory)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                String name = path.getFileName().toString();
                if (name.endsWith(".partial") || name.endsWith(".manifest.tmp")) {
                    Files.deleteIfExists(path);
                } else if (name.endsWith(".rows")) {
                    String id = name.substring(0, name.length() - ".rows".length());
                    if (!Files.exists(directory.resolve(id + ".properties"))) Files.deleteIfExists(path);
                } else if (name.contains(".lease-")) {
                    try {
                        long expires = Long.parseLong(Files.readString(path).trim());
                        if (expires <= Instant.now().toEpochMilli()) Files.deleteIfExists(path);
                    } catch (RuntimeException malformed) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        }
    }
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

                // Checksum and size are taken from the file as written, then recorded in the
                // manifest. Boundary evidence is irreproducible: once the epoch has passed there
                // is nothing to recompute from, so a truncated or corrupt file has to be
                // detectable rather than served as a short epoch.
                Properties p = properties(job);
                p.setProperty("rowCount", Long.toString(count));
                p.setProperty("rowBytes", Long.toString(Files.size(temporary)));
                p.setProperty("rowChecksum", DurableFiles.checksum(temporary));

                Path temporaryManifest = Files.createTempFile(directory, job.jobId().toString(), ".manifest.tmp");
                try (var out = Files.newOutputStream(temporaryManifest)) { p.store(out, "yano epoch source"); }

                // Rows first, then the manifest. The manifest is what makes a job discoverable,
                // so publishing it second means a crash in between leaves an orphaned rows file -
                // which cleanInterruptedWrites removes - rather than a discoverable job whose
                // evidence is not yet durable.
                DurableFiles.publish(temporary, rows(job.jobId()));
                DurableFiles.publish(temporaryManifest, manifest(job.jobId()));
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
