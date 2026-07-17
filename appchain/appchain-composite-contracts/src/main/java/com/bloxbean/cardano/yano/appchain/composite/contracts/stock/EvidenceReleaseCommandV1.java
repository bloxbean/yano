package com.bloxbean.cardano.yano.appchain.composite.contracts.stock;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yano.appchain.examples.evidence.EvidenceContract;
import com.bloxbean.cardano.yano.appchain.examples.evidence.command.EvidenceCommandCodec;
import com.bloxbean.cardano.yano.appchain.examples.evidence.command.SubmitEvidenceCommandV1;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

/** Canonical bounded command for the stock {@code evidence-release-v1} workflow. */
public record EvidenceReleaseCommandV1(
        String releaseId,
        byte[] registryKey,
        String approvalItemId,
        String documentEntityId,
        byte[] documentHash,
        String documentRef,
        byte[] evidenceCommand
) {
    public static final int VERSION = 1;
    public static final String TOPIC = "evidence.release.v1";
    public static final int MAX_ENCODED_BYTES = 8_192;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,126}");

    public EvidenceReleaseCommandV1 {
        releaseId = identifier(releaseId, "releaseId");
        registryKey = boundedBytes(registryKey, 1, 192, "registryKey");
        approvalItemId = identifier(approvalItemId, "approvalItemId");
        documentEntityId = identifier(documentEntityId, "documentEntityId");
        documentHash = boundedBytes(documentHash, 32, 32, "documentHash");
        documentRef = boundedText(documentRef, 0, 512, "documentRef");
        evidenceCommand = boundedBytes(evidenceCommand, 1,
                EvidenceContract.MAX_COMMAND_BYTES, "evidenceCommand");
        if (!(EvidenceCommandCodec.decode(evidenceCommand) instanceof SubmitEvidenceCommandV1)) {
            throw new IllegalArgumentException("evidenceCommand must be a v1 submit command");
        }
    }

    @Override
    public byte[] registryKey() {
        return registryKey.clone();
    }

    @Override
    public byte[] documentHash() {
        return documentHash.clone();
    }

    @Override
    public byte[] evidenceCommand() {
        return evidenceCommand.clone();
    }

    public SubmitEvidenceCommandV1 evidenceSubmit() {
        return (SubmitEvidenceCommandV1) EvidenceCommandCodec.decode(evidenceCommand);
    }

    public byte[] evidenceCommandHash() {
        return Blake2bUtil.blake2bHash256(evidenceCommand);
    }

    public byte[] commandHash() {
        return Blake2bUtil.blake2bHash256(encode());
    }

    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(VERSION);
            writeString(out, releaseId);
            writeBytes(out, registryKey);
            writeString(out, approvalItemId);
            writeString(out, documentEntityId);
            writeBytes(out, documentHash);
            writeString(out, documentRef);
            writeBytes(out, evidenceCommand);
            out.flush();
            byte[] encoded = bytes.toByteArray();
            if (encoded.length > MAX_ENCODED_BYTES) {
                throw new IllegalArgumentException("evidence-release command exceeds encoded bound");
            }
            return encoded;
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory evidence-release encoding failed", impossible);
        }
    }

    public static EvidenceReleaseCommandV1 decode(byte[] encoded) {
        byte[] input = boundedBytes(encoded, 1, MAX_ENCODED_BYTES, "encoded command");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(input));
            if (in.readInt() != VERSION) {
                throw new IllegalArgumentException("unsupported evidence-release command version");
            }
            EvidenceReleaseCommandV1 command = new EvidenceReleaseCommandV1(
                    readString(in, 127), readBytes(in, 192), readString(in, 127),
                    readString(in, 127), readBytes(in, 32), readString(in, 512),
                    readBytes(in, EvidenceContract.MAX_COMMAND_BYTES));
            if (in.available() != 0 || !Arrays.equals(input, command.encode())) {
                throw new IllegalArgumentException("non-canonical evidence-release command");
            }
            return command;
        } catch (IOException | RuntimeException malformed) {
            if (malformed instanceof IllegalArgumentException invalid) {
                throw invalid;
            }
            throw new IllegalArgumentException("invalid evidence-release command", malformed);
        }
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof EvidenceReleaseCommandV1 command
                && releaseId.equals(command.releaseId)
                && Arrays.equals(registryKey, command.registryKey)
                && approvalItemId.equals(command.approvalItemId)
                && documentEntityId.equals(command.documentEntityId)
                && Arrays.equals(documentHash, command.documentHash)
                && documentRef.equals(command.documentRef)
                && Arrays.equals(evidenceCommand, command.evidenceCommand);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(releaseId, approvalItemId, documentEntityId, documentRef);
        result = 31 * result + Arrays.hashCode(registryKey);
        result = 31 * result + Arrays.hashCode(documentHash);
        return 31 * result + Arrays.hashCode(evidenceCommand);
    }

    private static String identifier(String value, String field) {
        Objects.requireNonNull(value, field);
        if (!ID.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is not a bounded identifier");
        }
        return value;
    }

    private static String boundedText(String value, int minimum, int maximum, String field) {
        Objects.requireNonNull(value, field);
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < minimum || bytes.length > maximum || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field + " is outside its UTF-8 bound");
        }
        return value;
    }

    private static byte[] boundedBytes(byte[] value, int minimum, int maximum, String field) {
        Objects.requireNonNull(value, field);
        if (value.length < minimum || value.length > maximum) {
            throw new IllegalArgumentException(field + " is outside its byte bound");
        }
        return value.clone();
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        writeBytes(out, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(DataOutputStream out, byte[] value) throws IOException {
        out.writeInt(value.length);
        out.write(value);
    }

    private static String readString(DataInputStream in, int maximum) throws IOException {
        byte[] bytes = readBytes(in, maximum);
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(bytes, value.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("non-canonical UTF-8 in evidence-release command");
        }
        return value;
    }

    private static byte[] readBytes(DataInputStream in, int maximum) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > maximum || length > in.available()) {
            throw new IllegalArgumentException("invalid evidence-release field length");
        }
        return in.readNBytes(length);
    }
}
