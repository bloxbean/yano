package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Canonical Yano L2 transaction.
 *
 * <p>The contained body uses Cardano transaction-body CBOR, but this envelope
 * is not a Cardano L1 transaction and its Jubjub witnesses must never be put
 * in a Cardano VKey witness set.</p>
 */
public record EutxoL2Transaction(
        EutxoL2Domain domain,
        byte[] transactionBody,
        List<EutxoL2Authorization> authorizations
) {
    public static final int VERSION = 1;
    public static final byte[] MAGIC =
            "YANO-EUTXO-L2".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final int MAX_AUTHORIZATIONS = 16;
    private static final int MAX_ENCODED_BYTES = 128 * 1024;

    public EutxoL2Transaction {
        domain = Objects.requireNonNull(domain, "domain");
        transactionBody = Objects.requireNonNull(
                transactionBody, "transactionBody").clone();
        if (transactionBody.length == 0
                || transactionBody.length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException(
                    "L2 transaction body is empty or exceeds its bound");
        }
        requireCanonicalBody(transactionBody, domain.expiry());
        authorizations = List.copyOf(Objects.requireNonNull(
                authorizations, "authorizations"));
        if (authorizations.isEmpty()
                || authorizations.size() > MAX_AUTHORIZATIONS) {
            throw new IllegalArgumentException(
                    "L2 transaction requires 1-16 authorizations");
        }
        List<EutxoL2Authorization> sorted = authorizations.stream()
                .sorted(Comparator.comparing(EutxoL2Authorization::paymentCredential))
                .toList();
        if (!authorizations.equals(sorted)) {
            throw new IllegalArgumentException(
                    "L2 authorizations must be ordered by payment credential");
        }
        String previous = null;
        for (EutxoL2Authorization authorization : authorizations) {
            if (authorization.paymentCredential().equals(previous)) {
                throw new IllegalArgumentException(
                        "L2 authorization repeats a payment credential");
            }
            previous = authorization.paymentCredential();
        }
    }

    @Override
    public byte[] transactionBody() {
        return transactionBody.clone();
    }

    public byte[] signingBytes() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(VERSION);
                write(output, MAGIC);
                write(output, domain.canonicalBytes());
                write(output, transactionBody);
                output.writeInt(authorizations.size());
                for (EutxoL2Authorization authorization : authorizations) {
                    writeText(output, authorization.paymentCredential());
                    output.writeLong(authorization.keyEpoch());
                    write(output, authorization.publicKey());
                    output.writeInt(authorization.inputIndexes().size());
                    for (Integer inputIndex : authorization.inputIndexes()) {
                        output.writeInt(inputIndex);
                    }
                }
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(
                    "in-memory L2 signing encoding failed", impossible);
        }
    }

    /** BLAKE2b-256 message bound by every Jubjub authorization. */
    public byte[] signingCommitment() {
        return Blake2bUtil.blake2bHash256(signingBytes());
    }

    public String transactionId() {
        return HexFormat.of().formatHex(signingCommitment());
    }

    public byte[] canonicalBytes() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                write(output, signingBytes());
                output.writeInt(authorizations.size());
                for (EutxoL2Authorization authorization : authorizations) {
                    write(output, authorization.rPoint());
                    write(output, authorization.s());
                }
            }
            byte[] encoded = bytes.toByteArray();
            if (encoded.length > MAX_ENCODED_BYTES) {
                throw new IllegalArgumentException(
                        "L2 transaction exceeds its encoded bound");
            }
            return encoded;
        } catch (IOException impossible) {
            throw new IllegalStateException(
                    "in-memory L2 transaction encoding failed", impossible);
        }
    }

    public static EutxoL2Transaction decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length == 0 || encoded.length > MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException("invalid L2 transaction size");
        }
        try (DataInputStream outer = new DataInputStream(
                new ByteArrayInputStream(encoded))) {
            byte[] signingBytes = read(outer, MAX_ENCODED_BYTES);
            List<byte[]> signatures = new ArrayList<>();
            int signatureCount = boundedCount(outer.readInt());
            for (int index = 0; index < signatureCount; index++) {
                byte[] r = read(outer, 32);
                byte[] s = read(outer, 32);
                if (r.length != 32 || s.length != 32) {
                    throw new IllegalArgumentException(
                            "invalid L2 signature encoding");
                }
                signatures.add(concat(r, s));
            }
            if (outer.available() != 0) {
                throw new IllegalArgumentException(
                        "trailing L2 transaction bytes");
            }
            try (DataInputStream input = new DataInputStream(
                    new ByteArrayInputStream(signingBytes))) {
                if (input.readInt() != VERSION
                        || !Arrays.equals(read(input, MAGIC.length), MAGIC)) {
                    throw new IllegalArgumentException(
                            "unsupported L2 transaction");
                }
                EutxoL2Domain domain = EutxoL2Domain.decode(read(input, 4_096));
                byte[] body = read(input, MAX_BODY_BYTES);
                int count = boundedCount(input.readInt());
                if (count != signatureCount) {
                    throw new IllegalArgumentException(
                            "L2 authorization/signature count differs");
                }
                List<EutxoL2Authorization> authorizations = new ArrayList<>(count);
                for (int index = 0; index < count; index++) {
                    String credential = readText(input, 56);
                    long keyEpoch = input.readLong();
                    byte[] publicKey = read(input, 32);
                    if (publicKey.length != 32) {
                        throw new IllegalArgumentException(
                                "invalid L2 public-key encoding");
                    }
                    int indexes = boundedCount(input.readInt());
                    List<Integer> inputIndexes = new ArrayList<>(indexes);
                    for (int item = 0; item < indexes; item++) {
                        inputIndexes.add(input.readInt());
                    }
                    byte[] signature = signatures.get(index);
                    authorizations.add(new EutxoL2Authorization(
                            credential, keyEpoch, publicKey,
                            Arrays.copyOfRange(signature, 0, 32),
                            Arrays.copyOfRange(signature, 32, 64),
                            inputIndexes));
                }
                if (input.available() != 0) {
                    throw new IllegalArgumentException(
                            "trailing L2 signing bytes");
                }
                EutxoL2Transaction transaction =
                        new EutxoL2Transaction(domain, body, authorizations);
                if (!Arrays.equals(signingBytes, transaction.signingBytes())
                        || !Arrays.equals(encoded, transaction.canonicalBytes())) {
                    throw new IllegalArgumentException(
                            "non-canonical L2 transaction");
                }
                return transaction;
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException(
                    "invalid L2 transaction", failure);
        }
    }

    public TransactionBody decodedBody() {
        return decodeBody(transactionBody);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EutxoL2Transaction transaction
                && domain.equals(transaction.domain)
                && Arrays.equals(transactionBody, transaction.transactionBody)
                && authorizations.equals(transaction.authorizations);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(domain, authorizations)
                + Arrays.hashCode(transactionBody);
    }

    private static void requireCanonicalBody(byte[] body, long expiry) {
        try {
            TransactionBody decoded = decodeBody(body);
            byte[] canonical = CborSerializationUtil.serialize(decoded.serialize());
            if (!Arrays.equals(body, canonical)) {
                throw new IllegalArgumentException(
                        "L2 transaction body CBOR is not canonical");
            }
            if (decoded.getTtl() != expiry) {
                throw new IllegalArgumentException(
                        "L2 body TTL differs from its domain expiry");
            }
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException(
                    "L2 transaction body cannot be decoded", failure);
        }
    }

    private static TransactionBody decodeBody(byte[] body) {
        try {
            var item = CborSerializationUtil.deserialize(body);
            if (!(item instanceof co.nstant.in.cbor.model.Map map)) {
                throw new IllegalArgumentException(
                        "L2 transaction body must be a CBOR map");
            }
            return TransactionBody.deserialize(map);
        } catch (Exception failure) {
            throw new IllegalArgumentException(
                    "L2 transaction body cannot be decoded", failure);
        }
    }

    private static int boundedCount(int count) {
        if (count < 1 || count > MAX_AUTHORIZATIONS) {
            throw new IllegalArgumentException(
                    "L2 authorization count is outside 1-16");
        }
        return count;
    }

    private static void writeText(DataOutputStream output, String value)
            throws IOException {
        write(output, value.getBytes(StandardCharsets.US_ASCII));
    }

    private static void write(DataOutputStream output, byte[] value)
            throws IOException {
        output.writeInt(value.length);
        output.write(value);
    }

    private static String readText(DataInputStream input, int maximum)
            throws IOException {
        return new String(read(input, maximum), StandardCharsets.US_ASCII);
    }

    private static byte[] read(DataInputStream input, int maximum)
            throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maximum || length > input.available()) {
            throw new IllegalArgumentException("invalid L2 field length");
        }
        return input.readNBytes(length);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
