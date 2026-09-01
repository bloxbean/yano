package com.bloxbean.cardano.yano.archive.core.source;

import com.bloxbean.cardano.yano.archive.api.ArchiveDatasetId;
import com.bloxbean.cardano.yano.archive.api.ArchiveNetworkIdentity;
import com.bloxbean.cardano.yano.archive.api.ArchiveStoreException;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;

/** Local-producer epoch capture that remains non-canonical until its block is built. */
public final class DurableProvisionalEpochFileSource<T> {
    private final ArchiveDatasetId dataset;
    private final Path directory;
    private final EpochFactCodec<T> codec;

    public DurableProvisionalEpochFileSource(
            ArchiveDatasetId dataset, Path directory, EpochFactCodec<T> codec) {
        this.dataset = dataset;
        this.directory = directory.toAbsolutePath().normalize();
        this.codec = Objects.requireNonNull(codec, "codec");
        try {
            Files.createDirectories(this.directory);
            cleanInterruptedWrites();
        } catch (Exception e) {
            throw new ArchiveStoreException("cannot create provisional epoch source directory", e);
        }
    }

    public DurableEpochFileSource.StagingWriter<T> open(ProvisionalEpochArchiveJob job) {
        requireJob(job);
        Path rows = rows(job.captureId());
        Path manifest = manifest(job.captureId());
        if (Files.exists(rows) && Files.exists(manifest)) {
            return DurableEpochFileSource.StagingWriter.noop();
        }
        try {
            Path temporary = directory.resolve(job.captureId() + ".rows.partial");
            Files.deleteIfExists(temporary);
            return new Writer(job, temporary);
        } catch (Exception e) {
            throw new ArchiveStoreException("cannot open provisional epoch source " + job.captureId(), e);
        }
    }

    public List<ProvisionalEpochArchiveJob> pending() {
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".properties"))
                    .map(this::readManifest)
                    .sorted(Comparator.comparingLong(ProvisionalEpochArchiveJob::boundaryBlockNumber)
                            .thenComparing(ProvisionalEpochArchiveJob::sourceReference))
                    .toList();
        } catch (Exception e) {
            throw e instanceof ArchiveStoreException store ? store
                    : new ArchiveStoreException("cannot list provisional epoch sources", e);
        }
    }

    public DurableEpochFileSource.StagedEvidence bind(
            ProvisionalEpochArchiveJob capture,
            DurableEpochFileSource<T> destination,
            EpochArchiveJob job) {
        requireJob(capture);
        requireBinding(capture, job);
        Path captureManifest = manifest(capture.captureId());
        Properties values = readProperties(captureManifest);
        Optional<UUID> previous = boundJobId(values);
        if (previous.isPresent() && !previous.orElseThrow().equals(job.jobId())) {
            destination.find(previous.orElseThrow()).ifPresent(destination::acknowledge);
            values.remove("boundJobId");
        }
        persistBinding(captureManifest, values, job.jobId());
        var evidence = evidence(values, capture.captureId());
        return destination.adopt(job, rows(capture.captureId()), evidence);
    }

    public List<ProvisionalEpochArchiveJob> acknowledgeBound(UUID jobId) {
        List<ProvisionalEpochArchiveJob> acknowledged = new ArrayList<>();
        for (ProvisionalEpochArchiveJob capture : pending()) {
            Properties values = readProperties(manifest(capture.captureId()));
            if (boundJobId(values).filter(jobId::equals).isPresent()) {
                acknowledge(capture);
                acknowledged.add(capture);
            }
        }
        return List.copyOf(acknowledged);
    }

    public void acknowledge(ProvisionalEpochArchiveJob job) {
        requireJob(job);
        try {
            Files.deleteIfExists(manifest(job.captureId()));
            Files.deleteIfExists(rows(job.captureId()));
        } catch (Exception e) {
            throw new ArchiveStoreException("cannot acknowledge provisional epoch source "
                    + job.captureId(), e);
        }
    }

    public int discardAfterEpoch(long epochInclusive, DurableEpochFileSource<T> destination) {
        int discarded = 0;
        for (ProvisionalEpochArchiveJob capture : pending()) {
            if (capture.epoch() > epochInclusive) {
                discard(capture, destination);
                discarded++;
            }
        }
        return discarded;
    }

    public int discardAfterBlock(long blockInclusive, DurableEpochFileSource<T> destination) {
        int discarded = 0;
        for (ProvisionalEpochArchiveJob capture : pending()) {
            if (capture.boundaryBlockNumber() > blockInclusive) {
                discard(capture, destination);
                discarded++;
            }
        }
        return discarded;
    }

    private void discard(ProvisionalEpochArchiveJob capture, DurableEpochFileSource<T> destination) {
        Properties values = readProperties(manifest(capture.captureId()));
        boundJobId(values).flatMap(destination::find).ifPresent(destination::acknowledge);
        acknowledge(capture);
    }

    private void requireJob(ProvisionalEpochArchiveJob job) {
        if (job.dataset() != dataset) throw new IllegalArgumentException("epoch source dataset mismatch");
    }

    private void requireBinding(ProvisionalEpochArchiveJob capture, EpochArchiveJob job) {
        if (!capture.networkIdentity().equals(job.networkIdentity())
                || capture.dataset() != job.dataset()
                || capture.projectionVersion() != job.projectionVersion()
                || capture.epoch() != job.epoch()
                || capture.boundaryBlockNumber() != job.boundaryBlockNumber()
                || capture.boundarySlot() != job.boundarySlot()
                || !capture.sourceStateVersion().equals(job.sourceStateVersion())
                || !capture.sourceReference().equals(job.sourceReference())) {
            throw new IllegalArgumentException("canonical epoch job does not match provisional capture");
        }
    }

    private Properties properties(ProvisionalEpochArchiveJob job) {
        Properties p = new Properties();
        p.setProperty("captureId", job.captureId().toString());
        p.setProperty("magic", Integer.toString(job.networkIdentity().networkMagic()));
        p.setProperty("genesis", job.networkIdentity().genesisHash());
        p.setProperty("dataset", job.dataset().name());
        p.setProperty("projection", Integer.toString(job.projectionVersion()));
        p.setProperty("epoch", Long.toString(job.epoch()));
        p.setProperty("block", Long.toString(job.boundaryBlockNumber()));
        p.setProperty("slot", Long.toString(job.boundarySlot()));
        p.setProperty("state", job.sourceStateVersion());
        p.setProperty("reference", job.sourceReference());
        p.setProperty("created", Long.toString(job.createdAt().toEpochMilli()));
        return p;
    }

    private ProvisionalEpochArchiveJob readManifest(Path path) {
        Properties p = readProperties(path);
        try {
            return new ProvisionalEpochArchiveJob(UUID.fromString(p.getProperty("captureId")),
                    new ArchiveNetworkIdentity(Integer.parseInt(p.getProperty("magic")), p.getProperty("genesis")),
                    ArchiveDatasetId.valueOf(p.getProperty("dataset")), Integer.parseInt(p.getProperty("projection")),
                    Long.parseLong(p.getProperty("epoch")), Long.parseLong(p.getProperty("block")),
                    Long.parseLong(p.getProperty("slot")), p.getProperty("state"), p.getProperty("reference"),
                    Instant.ofEpochMilli(Long.parseLong(p.getProperty("created"))));
        } catch (Exception e) {
            throw new ArchiveStoreException("invalid provisional epoch source manifest " + path, e);
        }
    }

    private Properties readProperties(Path path) {
        try (var in = Files.newInputStream(path)) {
            Properties values = new Properties();
            values.load(in);
            return values;
        } catch (Exception e) {
            throw new ArchiveStoreException("cannot read provisional epoch source manifest " + path, e);
        }
    }

    private DurableEpochFileSource.StagedEvidence evidence(Properties values, UUID id) {
        String checksum = values.getProperty("rowChecksum");
        if (checksum == null || checksum.isBlank()) {
            throw new ArchiveStoreException("provisional epoch source " + id + " has no recorded checksum");
        }
        return new DurableEpochFileSource.StagedEvidence(
                Long.parseLong(values.getProperty("rowCount", "-1")), checksum,
                Long.parseLong(values.getProperty("rowBytes", "-1")));
    }

    private Optional<UUID> boundJobId(Properties values) {
        String value = values.getProperty("boundJobId");
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(UUID.fromString(value));
    }

    private void persistBinding(Path path, Properties values, UUID jobId) {
        if (boundJobId(values).filter(jobId::equals).isPresent()) return;
        values.setProperty("boundJobId", jobId.toString());
        publishProperties(values, path, jobId.toString());
    }

    private void publishProperties(Properties values, Path target, String prefix) {
        Path temporary = null;
        try {
            temporary = Files.createTempFile(directory, prefix, ".manifest.tmp");
            try (var out = Files.newOutputStream(temporary)) {
                values.store(out, "yano provisional epoch source");
            }
            DurableFiles.publish(temporary, target);
        } catch (Exception e) {
            throw e instanceof ArchiveStoreException store ? store
                    : new ArchiveStoreException("cannot publish provisional epoch manifest", e);
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (Exception ignored) { }
        }
    }

    private Path rows(UUID id) {
        return directory.resolve(id + ".rows");
    }

    private Path manifest(UUID id) {
        return directory.resolve(id + ".properties");
    }

    private void cleanInterruptedWrites() throws IOException {
        try (var files = Files.list(directory)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                String name = path.getFileName().toString();
                if (name.endsWith(".partial") || name.endsWith(".manifest.tmp")) {
                    Files.deleteIfExists(path);
                } else if (name.endsWith(".rows")) {
                    String id = name.substring(0, name.length() - ".rows".length());
                    if (!Files.exists(directory.resolve(id + ".properties"))) Files.deleteIfExists(path);
                }
            }
        }
    }

    private final class Writer implements DurableEpochFileSource.StagingWriter<T> {
        private final ProvisionalEpochArchiveJob job;
        private final Path temporary;
        private final DataOutputStream output;
        private long count;
        private boolean committed;

        private Writer(ProvisionalEpochArchiveJob job, Path temporary) throws IOException {
            this.job = job;
            this.temporary = temporary;
            output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)));
        }

        @Override
        public void append(T fact) {
            if (committed) throw new IllegalStateException("provisional epoch source writer is closed");
            try {
                byte[] encoded = codec.encode(fact);
                output.writeInt(encoded.length);
                output.write(encoded);
                count++;
            } catch (Exception e) {
                throw new ArchiveStoreException("cannot append provisional epoch source "
                        + job.captureId(), e);
            }
        }

        @Override
        public void commit() {
            if (committed) return;
            Path temporaryManifest = null;
            try {
                output.flush();
                output.close();
                Properties values = properties(job);
                values.setProperty("rowCount", Long.toString(count));
                values.setProperty("rowBytes", Long.toString(Files.size(temporary)));
                values.setProperty("rowChecksum", DurableFiles.checksum(temporary));
                temporaryManifest = Files.createTempFile(directory, job.captureId().toString(),
                        ".manifest.tmp");
                try (var out = Files.newOutputStream(temporaryManifest)) {
                    values.store(out, "yano provisional epoch source");
                }
                DurableFiles.publish(temporary, rows(job.captureId()));
                DurableFiles.publish(temporaryManifest, manifest(job.captureId()));
                committed = true;
            } catch (Exception e) {
                throw new ArchiveStoreException("cannot commit provisional epoch source "
                        + job.captureId(), e);
            } finally {
                if (temporaryManifest != null) {
                    try { Files.deleteIfExists(temporaryManifest); } catch (Exception ignored) { }
                }
            }
        }

        @Override
        public void close() {
            if (committed) return;
            try { output.close(); } catch (Exception ignored) { }
            try { Files.deleteIfExists(temporary); } catch (Exception ignored) { }
        }
    }
}
