package com.bloxbean.cardano.yano.api.appchain.consensus;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Canonical identity bound into every certified-consensus signature. */
public record ConsensusContext(int protocolVersion,
                               String chainId,
                               byte[] genesisId,
                               long height,
                               ConsensusQuorum quorum,
                               List<byte[]> memberKeys,
                               byte[] consensusProfileDigest,
                               byte[] observerProfileDigest,
                               byte[] observationProfileDigest) {

    private static final byte[] DOMAIN =
            "yano-appchain-consensus-context\0".getBytes(StandardCharsets.US_ASCII);

    public ConsensusContext {
        if (protocolVersion != 3) {
            throw new IllegalArgumentException("certified consensus protocol version must be 3");
        }
        Objects.requireNonNull(chainId, "chainId");
        if (chainId.isBlank() || height < 1) {
            throw new IllegalArgumentException("invalid consensus chain identity or height");
        }
        genesisId = bytes32(genesisId, "genesisId");
        consensusProfileDigest = bytes32(consensusProfileDigest, "consensusProfileDigest");
        observerProfileDigest = bytes32(observerProfileDigest, "observerProfileDigest");
        observationProfileDigest = bytes32(observationProfileDigest, "observationProfileDigest");
        quorum = Objects.requireNonNull(quorum, "quorum");
        memberKeys = Objects.requireNonNull(memberKeys, "memberKeys").stream()
                .map(key -> bytes32(key, "memberKey"))
                .sorted(ConsensusContext::compareUnsigned)
                .toList();
        if (memberKeys.size() != quorum.members()) {
            throw new IllegalArgumentException("membership size differs from quorum profile");
        }
        for (int index = 1; index < memberKeys.size(); index++) {
            if (Arrays.equals(memberKeys.get(index - 1), memberKeys.get(index))) {
                throw new IllegalArgumentException("duplicate consensus member key");
            }
        }
    }

    @Override public byte[] genesisId() { return genesisId.clone(); }
    @Override public byte[] consensusProfileDigest() { return consensusProfileDigest.clone(); }
    @Override public byte[] observerProfileDigest() { return observerProfileDigest.clone(); }
    @Override public byte[] observationProfileDigest() { return observationProfileDigest.clone(); }
    @Override public List<byte[]> memberKeys() {
        return memberKeys.stream().map(byte[]::clone).toList();
    }

    public byte[] digest() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bytes.write(DOMAIN);
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(protocolVersion);
                writeBytes(out, chainId.getBytes(StandardCharsets.UTF_8));
                out.write(genesisId);
                out.writeLong(height);
                out.writeInt(quorum.members());
                out.writeInt(quorum.threshold());
                out.writeInt(quorum.maxByzantineMembers());
                for (byte[] member : memberKeys) {
                    out.write(member);
                }
                out.write(consensusProfileDigest);
                out.write(observerProfileDigest);
                out.write(observationProfileDigest);
            }
            return Blake2bUtil.blake2bHash256(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    private static void writeBytes(DataOutputStream out, byte[] value) throws IOException {
        out.writeInt(value.length);
        out.write(value);
    }

    private static byte[] bytes32(byte[] value, String name) {
        Objects.requireNonNull(value, name);
        if (value.length != 32) {
            throw new IllegalArgumentException(name + " must be 32 bytes");
        }
        return value.clone();
    }

    private static int compareUnsigned(byte[] left, byte[] right) {
        return Arrays.compareUnsigned(left, right);
    }
}
