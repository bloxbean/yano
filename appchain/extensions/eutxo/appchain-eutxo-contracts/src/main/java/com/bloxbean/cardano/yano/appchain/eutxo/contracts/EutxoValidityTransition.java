package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Canonical, ZeroJ-neutral description of one accepted EUTxO transition.
 *
 * <p>The regular Yano MPF root remains authoritative for application queries.
 * Optional validity engines consume this bounded descriptor to maintain a
 * second, proof-friendly commitment without entering the base module's
 * dependency graph.</p>
 */
public record EutxoValidityTransition(
        byte[] previousRoot,
        String chainId,
        String network,
        String profileDigest,
        String validityProfileDigest,
        byte[] domainCommitment,
        String transactionId,
        byte[] canonicalTransaction,
        List<EutxoRecord> resolvedInputs,
        List<EutxoOutpoint> consumed,
        List<EutxoRecord> created,
        long l1Slot,
        long appHeight,
        int ordinal
) {
    private static final int VERSION = 2;
    private static final int MAX_TRANSACTION_BYTES = 64 * 1024;
    private static final int MAX_RECORDS = 16;
    private static final int MAX_ENCODED_BYTES = 1024 * 1024;

    public EutxoValidityTransition {
        previousRoot = copy32(previousRoot, "previous root");
        chainId = text(chainId, "chain id", 63);
        network = text(network, "network", 16);
        if (!List.of("devnet", "preview", "preprod").contains(network)) {
            throw new IllegalArgumentException(
                    "validity transition requires a supported test network");
        }
        profileDigest = digest(profileDigest, "profile digest");
        validityProfileDigest = digest(
                validityProfileDigest, "validity profile digest");
        domainCommitment = copy32(
                domainCommitment, "domain commitment");
        transactionId = digest(transactionId, "transaction id");
        canonicalTransaction = Objects.requireNonNull(
                canonicalTransaction, "canonicalTransaction").clone();
        if (canonicalTransaction.length == 0
                || canonicalTransaction.length > MAX_TRANSACTION_BYTES) {
            throw new IllegalArgumentException(
                    "canonical transaction is empty or exceeds the profile bound");
        }
        resolvedInputs = records(resolvedInputs, "resolvedInputs");
        consumed = List.copyOf(Objects.requireNonNull(consumed, "consumed"));
        created = records(created, "created");
        if (consumed.isEmpty() || consumed.size() > MAX_RECORDS
                || resolvedInputs.size() != consumed.size()) {
            throw new IllegalArgumentException(
                    "resolved inputs must match 1-16 consumed outpoints");
        }
        for (int index = 0; index < consumed.size(); index++) {
            if (!consumed.get(index).equals(
                    resolvedInputs.get(index).outpoint())) {
                throw new IllegalArgumentException(
                        "resolved input order differs from consumed outpoints");
            }
        }
        if (created.isEmpty() || l1Slot < 0 || appHeight < 1 || ordinal < 0) {
            throw new IllegalArgumentException("invalid validity transition identity");
        }
        validateCardanoTransaction(
                canonicalTransaction, chainId, network, profileDigest,
                validityProfileDigest, domainCommitment, transactionId,
                resolvedInputs, consumed, created);
    }

    @Override
    public byte[] previousRoot() {
        return previousRoot.clone();
    }

    @Override
    public byte[] canonicalTransaction() {
        return canonicalTransaction.clone();
    }

    @Override
    public byte[] domainCommitment() {
        return domainCommitment.clone();
    }

    /**
     * Exact deterministic prover input committed by the validity root.
     *
     * <p>Input, consumed, and output order is transaction order. It must not be
     * sorted independently because the circuit proves the canonical Cardano
     * transaction's ordered semantics.</p>
     */
    public byte[] canonicalBytes() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(VERSION);
                writeBytes(output, "yano:eutxo:validity-transition:v2"
                        .getBytes(StandardCharsets.US_ASCII));
                writeBytes(output, previousRoot);
                writeText(output, chainId);
                writeText(output, network);
                writeText(output, profileDigest);
                writeText(output, validityProfileDigest);
                writeBytes(output, domainCommitment);
                writeText(output, transactionId);
                writeBytes(output, canonicalTransaction);
                output.writeLong(l1Slot);
                output.writeLong(appHeight);
                output.writeInt(ordinal);
                output.writeInt(resolvedInputs.size());
                for (EutxoRecord record : resolvedInputs) {
                    writeBytes(output, record.encode());
                }
                output.writeInt(consumed.size());
                for (EutxoOutpoint outpoint : consumed) {
                    writeText(output, outpoint.toString());
                }
                output.writeInt(created.size());
                for (EutxoRecord record : created) {
                    writeBytes(output, record.encode());
                }
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(
                    "in-memory validity transition encoding failed", impossible);
        }
    }

    /** BLAKE2b-256 public commitment consumed by the ZeroJ circuit. */
    public byte[] digest() {
        return Blake2bUtil.blake2bHash256(canonicalBytes());
    }

    public static EutxoValidityTransition decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length == 0 || encoded.length > MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException(
                    "invalid validity transition size");
        }
        try (DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(encoded))) {
            if (input.readInt() != VERSION) {
                throw new IllegalArgumentException(
                        "unsupported validity transition version");
            }
            byte[] domain = readBytes(input, 128);
            if (!java.util.Arrays.equals(domain,
                    "yano:eutxo:validity-transition:v2"
                            .getBytes(StandardCharsets.US_ASCII))) {
                throw new IllegalArgumentException(
                        "invalid validity transition domain");
            }
            byte[] previousRoot = readBytes(input, 32);
            String chainId = readText(input, 63);
            String network = readText(input, 16);
            String profileDigest = readText(input, 64);
            String validityProfileDigest = readText(input, 64);
            byte[] domainCommitment = readBytes(input, 32);
            String transactionId = readText(input, 64);
            byte[] transaction = readBytes(input, MAX_TRANSACTION_BYTES);
            long l1Slot = input.readLong();
            long height = input.readLong();
            int ordinal = input.readInt();
            List<EutxoRecord> resolved = readRecords(input);
            int consumedCount = boundedCount(input.readInt());
            java.util.ArrayList<EutxoOutpoint> consumed =
                    new java.util.ArrayList<>(consumedCount);
            for (int index = 0; index < consumedCount; index++) {
                consumed.add(EutxoOutpoint.parse(readText(input, 66)));
            }
            List<EutxoRecord> created = readRecords(input);
            if (input.read() != -1) {
                throw new IllegalArgumentException(
                        "trailing validity transition bytes");
            }
            return new EutxoValidityTransition(
                    previousRoot, chainId, network, profileDigest,
                    validityProfileDigest, domainCommitment,
                    transactionId, transaction, resolved, consumed, created,
                    l1Slot, height, ordinal);
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "invalid validity transition encoding", exception);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EutxoValidityTransition transition
                && java.util.Arrays.equals(
                        previousRoot, transition.previousRoot)
                && chainId.equals(transition.chainId)
                && network.equals(transition.network)
                && profileDigest.equals(transition.profileDigest)
                && validityProfileDigest.equals(
                transition.validityProfileDigest)
                && java.util.Arrays.equals(
                domainCommitment, transition.domainCommitment)
                && transactionId.equals(transition.transactionId)
                && java.util.Arrays.equals(
                        canonicalTransaction,
                        transition.canonicalTransaction)
                && resolvedInputs.equals(transition.resolvedInputs)
                && consumed.equals(transition.consumed)
                && created.equals(transition.created)
                && l1Slot == transition.l1Slot
                && appHeight == transition.appHeight
                && ordinal == transition.ordinal;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                chainId, network, profileDigest,
                validityProfileDigest, transactionId,
                resolvedInputs, consumed, created,
                l1Slot, appHeight, ordinal);
        result = 31 * result + java.util.Arrays.hashCode(previousRoot);
        result = 31 * result
                + java.util.Arrays.hashCode(domainCommitment);
        return 31 * result
                + java.util.Arrays.hashCode(canonicalTransaction);
    }

    private static byte[] copy32(byte[] value, String label) {
        Objects.requireNonNull(value, label);
        if (value.length != 32) {
            throw new IllegalArgumentException(label + " must contain 32 bytes");
        }
        return value.clone();
    }

    private static List<EutxoRecord> records(
            List<EutxoRecord> records,
            String label
    ) {
        List<EutxoRecord> copy = List.copyOf(
                Objects.requireNonNull(records, label));
        if (copy.isEmpty() || copy.size() > MAX_RECORDS) {
            throw new IllegalArgumentException(
                    label + " must contain 1-16 records");
        }
        return copy;
    }

    private static String text(String value, String label, int maximum) {
        value = Objects.requireNonNull(value, label).trim();
        if (value.isEmpty()
                || value.getBytes(StandardCharsets.UTF_8).length > maximum) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return value;
    }

    private static String digest(String value, String label) {
        value = text(value, label, 64);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    label + " must be a lowercase 32-byte digest");
        }
        return value;
    }

    private static void writeText(DataOutputStream output, String value)
            throws IOException {
        writeBytes(output, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(DataOutputStream output, byte[] value)
            throws IOException {
        output.writeInt(value.length);
        output.write(value);
    }

    private static String readText(DataInputStream input, int maximum)
            throws IOException {
        return new String(readBytes(input, maximum), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(DataInputStream input, int maximum)
            throws IOException {
        int length = input.readInt();
        if (length < 1 || length > maximum) {
            throw new IllegalArgumentException(
                    "invalid validity transition field length");
        }
        byte[] value = input.readNBytes(length);
        if (value.length != length) {
            throw new IllegalArgumentException(
                    "truncated validity transition field");
        }
        return value;
    }

    private static List<EutxoRecord> readRecords(DataInputStream input)
            throws IOException {
        int count = boundedCount(input.readInt());
        java.util.ArrayList<EutxoRecord> records =
                new java.util.ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            records.add(EutxoRecord.decode(readBytes(input, 32 * 1024)));
        }
        return List.copyOf(records);
    }

    private static int boundedCount(int count) {
        if (count < 1 || count > MAX_RECORDS) {
            throw new IllegalArgumentException(
                    "invalid validity transition record count");
        }
        return count;
    }

    private static void validateCardanoTransaction(
            byte[] transactionCbor,
            String chainId,
            String network,
            String profileDigest,
            String validityProfileDigest,
            byte[] domainCommitment,
            String transactionId,
            List<EutxoRecord> resolvedInputs,
            List<EutxoOutpoint> consumed,
            List<EutxoRecord> created
    ) {
        try {
            Transaction transaction = Transaction.deserialize(transactionCbor);
            if (!java.util.Arrays.equals(
                    transactionCbor, transaction.serialize())) {
                throw new IllegalArgumentException(
                        "validity transition transaction is not canonical CBOR");
            }
            if (!transactionId.equals(
                    TransactionUtil.getTxHash(transactionCbor))) {
                throw new IllegalArgumentException(
                        "validity transition transaction id does not match CBOR");
            }
            EutxoTransactionDomain domain =
                    EutxoTransactionDomain.from(transaction);
            domain.requireExpected(
                    chainId, network, profileDigest,
                    validityProfileDigest);
            if (!java.util.Arrays.equals(
                    domainCommitment, domain.commitment())) {
                throw new IllegalArgumentException(
                        "validity transition domain commitment does not match");
            }
            if (transaction.getBody() == null
                    || transaction.getBody().getInputs() == null
                    || transaction.getBody().getOutputs() == null
                    || transaction.getBody().getInputs().size()
                    != resolvedInputs.size()
                    || transaction.getBody().getOutputs().size()
                    != created.size()) {
                throw new IllegalArgumentException(
                        "validity transition shape differs from transaction CBOR");
            }
            for (int index = 0;
                 index < transaction.getBody().getInputs().size();
                 index++) {
                var input = transaction.getBody().getInputs().get(index);
                EutxoOutpoint expected = new EutxoOutpoint(
                        input.getTransactionId(), input.getIndex());
                if (!expected.equals(consumed.get(index))) {
                    throw new IllegalArgumentException(
                            "consumed outpoint differs from transaction input");
                }
            }
            for (int index = 0;
                 index < transaction.getBody().getOutputs().size();
                 index++) {
                EutxoRecord record = created.get(index);
                byte[] expected = CborSerializationUtil.serialize(
                        transaction.getBody().getOutputs().get(index).serialize());
                if (!record.outpoint().equals(
                        new EutxoOutpoint(transactionId, index))
                        || !java.util.Arrays.equals(
                        expected, record.outputCbor())) {
                    throw new IllegalArgumentException(
                            "created record differs from transaction output");
                }
            }
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException(
                    "validity transition contains invalid Cardano CBOR",
                    failure);
        }
    }
}
