package com.bloxbean.cardano.yano.api.appchain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Immutable, height-versioned membership view exposed to deterministic machines. */
public record AppChainMembershipEpoch(long fromHeight, List<String> members, int threshold) {
    private static final byte[] DIGEST_DOMAIN = "yano-appchain-membership-epoch-v1\0"
            .getBytes(StandardCharsets.US_ASCII);

    public AppChainMembershipEpoch {
        if (fromHeight < 0 || members == null || members.isEmpty()
                || members.size() > AppChainConfig.MAX_MEMBERS
                || threshold < 1 || threshold > members.size()) {
            throw new IllegalArgumentException("invalid app-chain membership epoch");
        }
        members = members.stream()
                .map(member -> Objects.requireNonNull(member, "member").toLowerCase(Locale.ROOT))
                .sorted().toList();
        if (members.stream().anyMatch(member -> !member.matches("[0-9a-f]{64}"))
                || members.stream().distinct().count() != members.size()) {
            throw new IllegalArgumentException("invalid app-chain membership keys");
        }
    }

    /** Domain-separated digest used to bind governance to one exact membership epoch. */
    public byte[] digest() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(DIGEST_DOMAIN);
            digest.update(ByteBuffer.allocate(Long.BYTES + Integer.BYTES + Integer.BYTES)
                    .putLong(fromHeight).putInt(threshold).putInt(members.size()).array());
            members.forEach(member -> digest.update(
                    java.util.HexFormat.of().parseHex(member)));
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
