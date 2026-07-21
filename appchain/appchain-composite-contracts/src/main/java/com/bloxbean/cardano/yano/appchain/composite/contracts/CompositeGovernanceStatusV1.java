package com.bloxbean.cardano.yano.appchain.composite.contracts;

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

/** Bounded canonical committed query view for ADR-015 governance state. */
public record CompositeGovernanceStatusV1(int schemaVersion,
                                          long currentEpoch,
                                          long activeFromHeight,
                                          byte[] activeProfileDigest,
                                          Proposal proposal,
                                          List<Drain> drains) {
    public static final int VERSION = 1;
    public static final int MAX_MEMBERS = 32;
    public static final int MAX_DRAINS = 256;
    public static final int MAX_ENCODED_BYTES = 256 * 1024;

    public CompositeGovernanceStatusV1 {
        if (schemaVersion != VERSION || currentEpoch < 0 || activeFromHeight < 1) throw malformed();
        activeProfileDigest = exact(activeProfileDigest, 32);
        drains = List.copyOf(drains != null ? drains : List.of());
        if (drains.size() > MAX_DRAINS || !drains.equals(drains.stream()
                .sorted(Comparator.comparing(Drain::componentId)
                        .thenComparing(Drain::semanticVersion)
                        .thenComparingLong(Drain::fromHeight)).toList())) {
            throw malformed();
        }
    }

    @Override public byte[] activeProfileDigest() { return activeProfileDigest.clone(); }

    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(VERSION);
            out.writeLong(currentEpoch);
            out.writeLong(activeFromHeight);
            out.write(activeProfileDigest);
            out.writeBoolean(proposal != null);
            if (proposal != null) proposal.write(out);
            out.writeInt(drains.size());
            for (Drain drain : drains) drain.write(out);
            out.flush();
            byte[] encoded = bytes.toByteArray();
            if (encoded.length > MAX_ENCODED_BYTES) throw malformed();
            return encoded;
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory governance status encoding failed", impossible);
        }
    }

    public static CompositeGovernanceStatusV1 decode(byte[] encoded) {
        byte[] input = bounded(encoded, 1, MAX_ENCODED_BYTES);
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(input));
            int version = in.readInt();
            long epoch = in.readLong();
            long from = in.readLong();
            byte[] digest = readExact(in, 32);
            Proposal proposal = in.readBoolean() ? Proposal.read(in) : null;
            int count = in.readInt();
            if (count < 0 || count > MAX_DRAINS) throw malformed();
            List<Drain> drains = new ArrayList<>(count);
            for (int index = 0; index < count; index++) drains.add(Drain.read(in));
            CompositeGovernanceStatusV1 result = new CompositeGovernanceStatusV1(
                    version, epoch, from, digest, proposal, drains);
            if (in.available() != 0 || !Arrays.equals(input, result.encode())) throw malformed();
            return result;
        } catch (IOException | RuntimeException malformed) {
            throw malformed();
        }
    }

    public record Proposal(int statusCode,
                           byte[] proposalId,
                           byte[] proposalHash,
                           byte[] targetProfileDigest,
                           byte[] membershipEpochDigest,
                           long activationHeight,
                           long expiryHeight,
                           List<String> approvals,
                           List<String> readiness,
                           List<String> cancellations) {
        public Proposal {
            if (statusCode < 0 || statusCode > 2 || activationHeight < 2
                    || expiryHeight < activationHeight) throw malformed();
            proposalId = exact(proposalId, 32);
            proposalHash = bounded(proposalHash, 0, 32);
            if (proposalHash.length != 0 && proposalHash.length != 32) throw malformed();
            targetProfileDigest = exact(targetProfileDigest, 32);
            membershipEpochDigest = exact(membershipEpochDigest, 32);
            approvals = members(approvals);
            readiness = members(readiness);
            cancellations = members(cancellations);
        }

        @Override public byte[] proposalId() { return proposalId.clone(); }
        @Override public byte[] proposalHash() { return proposalHash.clone(); }
        @Override public byte[] targetProfileDigest() { return targetProfileDigest.clone(); }
        @Override public byte[] membershipEpochDigest() { return membershipEpochDigest.clone(); }

        private void write(DataOutputStream out) throws IOException {
            out.writeByte(statusCode);
            out.write(proposalId);
            writeBytes(out, proposalHash);
            out.write(targetProfileDigest);
            out.write(membershipEpochDigest);
            out.writeLong(activationHeight);
            out.writeLong(expiryHeight);
            writeMembers(out, approvals);
            writeMembers(out, readiness);
            writeMembers(out, cancellations);
        }

        private static Proposal read(DataInputStream in) throws IOException {
            return new Proposal(in.readUnsignedByte(), readExact(in, 32), readBytes(in, 32),
                    readExact(in, 32), readExact(in, 32), in.readLong(), in.readLong(),
                    readMembers(in), readMembers(in), readMembers(in));
        }
    }

    public record Drain(String componentId,
                        String semanticVersion,
                        long fromHeight,
                        int quota,
                        long throughHeight) {
        public Drain {
            componentId = text(componentId);
            semanticVersion = text(semanticVersion);
            if (fromHeight < 1 || quota < 0 || throughHeight < 1) throw malformed();
        }

        private void write(DataOutputStream out) throws IOException {
            writeText(out, componentId);
            writeText(out, semanticVersion);
            out.writeLong(fromHeight);
            out.writeInt(quota);
            out.writeLong(throughHeight);
        }

        private static Drain read(DataInputStream in) throws IOException {
            return new Drain(readText(in), readText(in), in.readLong(), in.readInt(), in.readLong());
        }
    }

    private static List<String> members(List<String> supplied) {
        List<String> values = List.copyOf(supplied != null ? supplied : List.of());
        if (values.size() > MAX_MEMBERS || !values.equals(values.stream().sorted().toList())
                || values.stream().distinct().count() != values.size()
                || values.stream().anyMatch(value -> value == null
                || !value.matches("[0-9a-f]{64}"))) throw malformed();
        return values;
    }

    private static void writeMembers(DataOutputStream out, List<String> members) throws IOException {
        out.writeByte(members.size());
        for (String member : members) out.write(HexFormat.of().parseHex(member));
    }

    private static List<String> readMembers(DataInputStream in) throws IOException {
        int count = in.readUnsignedByte();
        if (count > MAX_MEMBERS) throw malformed();
        List<String> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            result.add(HexFormat.of().formatHex(readExact(in, 32)));
        }
        return result;
    }

    private static String text(String value) {
        if (value == null || value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length > 128
                || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) throw malformed();
        return value;
    }

    private static void writeText(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static String readText(DataInputStream in) throws IOException {
        int size = in.readUnsignedShort();
        if (size < 1 || size > 128 || size > in.available()) throw malformed();
        byte[] bytes = in.readNBytes(size);
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (!Arrays.equals(bytes, value.getBytes(StandardCharsets.UTF_8))) throw malformed();
        return value;
    }

    private static void writeBytes(DataOutputStream out, byte[] value) throws IOException {
        out.writeByte(value.length);
        out.write(value);
    }

    private static byte[] readBytes(DataInputStream in, int maximum) throws IOException {
        int size = in.readUnsignedByte();
        if (size > maximum || size > in.available()) throw malformed();
        return in.readNBytes(size);
    }

    private static byte[] readExact(DataInputStream in, int size) throws IOException {
        byte[] value = in.readNBytes(size);
        if (value.length != size) throw malformed();
        return value;
    }

    private static byte[] exact(byte[] value, int size) { return bounded(value, size, size); }

    private static byte[] bounded(byte[] value, int minimum, int maximum) {
        if (value == null || value.length < minimum || value.length > maximum) throw malformed();
        return value.clone();
    }

    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException("invalid composite governance status v1");
    }
}
