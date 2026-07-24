package com.bloxbean.cardano.yano.appchain.eutxo.zk.prover;

import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoKeyPaymentBatch;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkBatchData;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkProofArtifact;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkStatement;
import com.bloxbean.cardano.yano.appchain.eutxo.zk.contracts.EutxoZkVerificationKey;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Atomic file-backed proving job and artifact store.
 *
 * <p>Witnesses are isolated below {@code witnesses/} and set to owner-only
 * permissions on POSIX filesystems. Job listings never deserialize or expose
 * witness material.</p>
 */
public final class EutxoProverStore {
    private static final int VERSION = 1;
    private static final int MAX_FILE_BYTES = 1_048_576;
    private static final Set<PosixFilePermission> WITNESS_PERMISSIONS =
            EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> WITNESS_DIRECTORY_PERMISSIONS =
            EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);

    private final Path root;
    private final Path jobs;
    private final Path statements;
    private final Path batches;
    private final Path witnesses;
    private final Path proofs;
    private final Path keys;

    public EutxoProverStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
        jobs = this.root.resolve("jobs");
        statements = this.root.resolve("statements");
        batches = this.root.resolve("batches");
        witnesses = this.root.resolve("witnesses");
        proofs = this.root.resolve("proofs");
        keys = this.root.resolve("keys");
        try {
            for (Path directory : List.of(
                    jobs, statements, batches, witnesses, proofs, keys)) {
                Files.createDirectories(directory);
            }
            restrictDirectory(witnesses);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot initialize EUTxO prover store", exception);
        }
    }

    public Path root() {
        return root;
    }

    public synchronized EutxoProverJob create(
            EutxoZkStatement statement,
            EutxoZkBatchData batchData,
            EutxoKeyPaymentBatch witness,
            Instant now,
            int maximumJobs
    ) {
        String id = statement.digestHex();
        Optional<EutxoProverJob> existing = find(id);
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        if (list().stream().filter(job -> job.status()
                != EutxoProverJob.Status.CANCELLED).count() >= maximumJobs) {
            throw new IllegalStateException("prover job capacity reached");
        }
        if (!java.util.Arrays.equals(
                batchData.commitment(), statement.batchDataCommitment())
                || !batchData.commitmentScalar().equals(
                statement.publicInputs().batchDataCommitment())) {
            throw new IllegalArgumentException("batch data does not match statement");
        }
        writeAtomic(statements.resolve(id + ".statement"),
                statement.canonicalBytes(), false);
        writeAtomic(batches.resolve(id + ".batch"),
                batchData.canonicalBytes(), false);
        writeAtomic(witnesses.resolve(id + ".witness"),
                encodeWitness(witness), true);
        EutxoProverJob job = new EutxoProverJob(
                id, EutxoProverJob.Status.QUEUED, 0,
                now, now, "", "");
        save(job);
        return job;
    }

    public synchronized Optional<EutxoProverJob> find(String id) {
        Path path = jobs.resolve(safeId(id) + ".job");
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        return Optional.of(decodeJob(readBounded(path)));
    }

    public synchronized List<EutxoProverJob> list() {
        List<EutxoProverJob> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(jobs, "*.job")) {
            for (Path path : stream) {
                result.add(decodeJob(readBounded(path)));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("cannot list prover jobs", exception);
        }
        result.sort(Comparator.comparing(EutxoProverJob::createdAt)
                .thenComparing(EutxoProverJob::id));
        return List.copyOf(result);
    }

    public synchronized void save(EutxoProverJob job) {
        writeAtomic(jobs.resolve(safeId(job.id()) + ".job"),
                encodeJob(job), false);
    }

    public EutxoZkStatement statement(String id) {
        return EutxoZkStatement.decode(readBounded(
                statements.resolve(safeId(id) + ".statement")));
    }

    public EutxoZkBatchData batchData(String id) {
        return EutxoZkBatchData.decode(readBounded(
                batches.resolve(safeId(id) + ".batch")));
    }

    EutxoKeyPaymentBatch witness(String id) {
        return decodeWitness(readBounded(
                witnesses.resolve(safeId(id) + ".witness")));
    }

    public synchronized void saveProof(
            String id,
            EutxoZkProofArtifact proof
    ) {
        if (!safeId(id).equals(proof.statementDigest())) {
            throw new IllegalArgumentException("proof statement does not match job");
        }
        writeAtomic(proofs.resolve(id + ".proof"),
                proof.canonicalBytes(), false);
    }

    public Optional<EutxoZkProofArtifact> proof(String id) {
        Path path = proofs.resolve(safeId(id) + ".proof");
        return Files.isRegularFile(path)
                ? Optional.of(EutxoZkProofArtifact.decode(readBounded(path)))
                : Optional.empty();
    }

    public synchronized void saveVerificationKey(
            EutxoZkVerificationKey key
    ) {
        writeAtomic(keys.resolve(key.digestHex() + ".vk"),
                key.canonicalBytes(), false);
    }

    public Optional<EutxoZkVerificationKey> verificationKey(String digest) {
        Path path = keys.resolve(safeId(digest) + ".vk");
        return Files.isRegularFile(path)
                ? Optional.of(EutxoZkVerificationKey.decode(readBounded(path)))
                : Optional.empty();
    }

    private static byte[] encodeJob(EutxoProverJob job) {
        return encode(output -> {
            output.writeInt(VERSION);
            writeText(output, job.id());
            output.writeByte(job.status().ordinal());
            output.writeInt(job.attempts());
            output.writeLong(job.createdAt().toEpochMilli());
            output.writeLong(job.updatedAt().toEpochMilli());
            writeText(output, job.proofDigest());
            writeText(output, job.lastError());
        });
    }

    private static EutxoProverJob decodeJob(byte[] encoded) {
        try (DataInputStream input = input(encoded)) {
            if (input.readInt() != VERSION) {
                throw new IllegalArgumentException("unsupported prover-job version");
            }
            String id = readText(input);
            int status = input.readUnsignedByte();
            if (status >= EutxoProverJob.Status.values().length) {
                throw new IllegalArgumentException("invalid prover-job status");
            }
            EutxoProverJob result = new EutxoProverJob(
                    id,
                    EutxoProverJob.Status.values()[status],
                    input.readInt(),
                    Instant.ofEpochMilli(input.readLong()),
                    Instant.ofEpochMilli(input.readLong()),
                    readText(input),
                    readText(input));
            requireEnd(input);
            return result;
        } catch (IOException exception) {
            throw new IllegalArgumentException("invalid prover job", exception);
        }
    }

    private static byte[] encodeWitness(EutxoKeyPaymentBatch witness) {
        return encode(output -> {
            output.writeInt(VERSION);
            output.writeByte(witness.payments().size());
            for (EutxoKeyPaymentBatch.Payment payment : witness.payments()) {
                writeBigInteger(output, payment.inputLovelace());
                writeBigInteger(output, payment.firstOutputLovelace());
                writeBigInteger(output, payment.secondOutputLovelace());
            }
            writeBigInteger(output, witness.ownerSecret());
        });
    }

    private static EutxoKeyPaymentBatch decodeWitness(byte[] encoded) {
        try (DataInputStream input = input(encoded)) {
            if (input.readInt() != VERSION) {
                throw new IllegalArgumentException("unsupported witness version");
            }
            int count = input.readUnsignedByte();
            if (count < 1 || count > 4) {
                throw new IllegalArgumentException("invalid witness batch size");
            }
            List<EutxoKeyPaymentBatch.Payment> payments =
                    new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                payments.add(new EutxoKeyPaymentBatch.Payment(
                        readBigInteger(input),
                        readBigInteger(input),
                        readBigInteger(input)));
            }
            EutxoKeyPaymentBatch result = new EutxoKeyPaymentBatch(
                    payments, readBigInteger(input));
            requireEnd(input);
            return result;
        } catch (IOException exception) {
            throw new IllegalArgumentException("invalid prover witness", exception);
        }
    }

    private static void writeAtomic(
            Path target,
            byte[] content,
            boolean sensitive
    ) {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.write(temporary, content);
            if (sensitive) {
                restrict(temporary);
            }
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target,
                        StandardCopyOption.REPLACE_EXISTING);
            }
            if (sensitive) {
                restrict(target);
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "cannot write prover artifact " + target.getFileName(), exception);
        }
    }

    private static byte[] readBounded(Path path) {
        try {
            long size = Files.size(path);
            if (size <= 0 || size > MAX_FILE_BYTES) {
                throw new IllegalArgumentException(
                        "invalid prover artifact size: " + path.getFileName());
            }
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "cannot read prover artifact " + path.getFileName(), exception);
        }
    }

    private static void restrict(Path path) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, WITNESS_PERMISSIONS);
        }
    }

    private static void restrictDirectory(Path path) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(
                    path, WITNESS_DIRECTORY_PERMISSIONS);
        }
    }

    private static String safeId(String id) {
        if (id == null || !id.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("unsafe prover artifact id");
        }
        return id;
    }

    private static byte[] encode(Writer writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writer.write(output);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory prover encoding failed", impossible);
        }
    }

    private static DataInputStream input(byte[] encoded) {
        if (encoded == null || encoded.length == 0
                || encoded.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("invalid prover artifact size");
        }
        return new DataInputStream(new ByteArrayInputStream(encoded));
    }

    private static void writeText(DataOutputStream output, String value)
            throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > 512) {
            throw new IllegalArgumentException("prover text field is too large");
        }
        output.writeShort(encoded.length);
        output.write(encoded);
    }

    private static String readText(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        if (length > 512) {
            throw new IllegalArgumentException("invalid prover text field");
        }
        return new String(input.readNBytes(length), StandardCharsets.UTF_8);
    }

    private static void writeBigInteger(
            DataOutputStream output,
            BigInteger value
    ) throws IOException {
        byte[] encoded = value.toByteArray();
        if (encoded.length > 33) {
            throw new IllegalArgumentException("prover scalar is too large");
        }
        output.writeByte(encoded.length);
        output.write(encoded);
    }

    private static BigInteger readBigInteger(DataInputStream input)
            throws IOException {
        int length = input.readUnsignedByte();
        if (length < 1 || length > 33) {
            throw new IllegalArgumentException("invalid prover scalar length");
        }
        return new BigInteger(input.readNBytes(length));
    }

    private static void requireEnd(DataInputStream input) throws IOException {
        if (input.read() != -1) {
            throw new IllegalArgumentException("trailing prover artifact bytes");
        }
    }

    @FunctionalInterface
    private interface Writer {
        void write(DataOutputStream output) throws IOException;
    }
}
