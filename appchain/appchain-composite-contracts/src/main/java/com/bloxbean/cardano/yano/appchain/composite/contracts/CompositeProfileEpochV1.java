package com.bloxbean.cardano.yano.appchain.composite.contracts;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

/** Canonical authenticated history record for one effective composite profile epoch. */
public record CompositeProfileEpochV1(int schemaVersion,
                                      long epochNumber,
                                      long fromHeight,
                                      byte[] previousProfileDigest,
                                      byte[] canonicalProfileBytes,
                                      byte[] proposalHash) {
    public static final int VERSION = 1;
    public static final int MAX_ENCODED_BYTES = CompositeCommitmentV1.MAX_PROFILE_BYTES + 128;

    public CompositeProfileEpochV1 {
        if (schemaVersion != VERSION || epochNumber < 0 || fromHeight < 1) {
            throw malformed();
        }
        previousProfileDigest = exact(previousProfileDigest, 32);
        canonicalProfileBytes = bounded(canonicalProfileBytes, 1,
                CompositeCommitmentV1.MAX_PROFILE_BYTES);
        proposalHash = exact(proposalHash, 32);
        if (epochNumber == 0 && (!allZero(previousProfileDigest) || !allZero(proposalHash))) {
            throw malformed();
        }
    }

    @Override public byte[] previousProfileDigest() { return previousProfileDigest.clone(); }
    @Override public byte[] canonicalProfileBytes() { return canonicalProfileBytes.clone(); }
    @Override public byte[] proposalHash() { return proposalHash.clone(); }

    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(VERSION);
            out.writeLong(epochNumber);
            out.writeLong(fromHeight);
            out.write(previousProfileDigest);
            out.writeInt(canonicalProfileBytes.length);
            out.write(canonicalProfileBytes);
            out.write(proposalHash);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory profile epoch encoding failed", impossible);
        }
    }

    public static CompositeProfileEpochV1 decode(byte[] encoded) {
        byte[] input = bounded(encoded, 1, MAX_ENCODED_BYTES);
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(input));
            int version = in.readInt();
            long epoch = in.readLong();
            long height = in.readLong();
            byte[] previous = in.readNBytes(32);
            int profileLength = in.readInt();
            if (profileLength < 1 || profileLength > CompositeCommitmentV1.MAX_PROFILE_BYTES
                    || profileLength > in.available() - 32) {
                throw malformed();
            }
            byte[] profile = in.readNBytes(profileLength);
            byte[] proposal = in.readNBytes(32);
            CompositeProfileEpochV1 result = new CompositeProfileEpochV1(
                    version, epoch, height, previous, profile, proposal);
            if (in.available() != 0 || !Arrays.equals(input, result.encode())) throw malformed();
            return result;
        } catch (IOException | RuntimeException malformed) {
            throw malformed();
        }
    }

    public byte[] profileDigest() {
        return CompositeCommitmentV1.profileDigest(canonicalProfileBytes);
    }

    private static byte[] exact(byte[] value, int size) {
        return bounded(value, size, size);
    }

    private static byte[] bounded(byte[] value, int minimum, int maximum) {
        if (value == null || value.length < minimum || value.length > maximum) throw malformed();
        return value.clone();
    }

    private static boolean allZero(byte[] bytes) {
        int value = 0;
        for (byte item : bytes) value |= item;
        return value == 0;
    }

    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException("invalid composite profile epoch v1");
    }
}
