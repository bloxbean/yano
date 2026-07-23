package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
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
        String transactionId,
        List<EutxoOutpoint> consumed,
        List<EutxoRecord> created,
        long appHeight,
        int ordinal
) {
    public EutxoValidityTransition {
        previousRoot = copy32(previousRoot, "previous root");
        transactionId = Objects.requireNonNull(transactionId, "transactionId");
        consumed = List.copyOf(Objects.requireNonNull(consumed, "consumed"));
        created = List.copyOf(Objects.requireNonNull(created, "created"));
        if (transactionId.isBlank() || appHeight < 0 || ordinal < 0) {
            throw new IllegalArgumentException("invalid validity transition identity");
        }
    }

    @Override
    public byte[] previousRoot() {
        return previousRoot.clone();
    }

    /** Domain-separated digest used by validity circuits as a public input. */
    public byte[] digest() {
        MessageDigest digest = sha256();
        update(digest, "yano:eutxo:validity-transition:v1");
        update(digest, transactionId);
        digest.update(ByteBuffer.allocate(Long.BYTES + Integer.BYTES)
                .putLong(appHeight).putInt(ordinal).array());

        List<EutxoOutpoint> orderedConsumed = new ArrayList<>(consumed);
        orderedConsumed.sort(Comparator.comparing(EutxoOutpoint::toString));
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(orderedConsumed.size()).array());
        orderedConsumed.forEach(value -> update(digest, value.toString()));

        List<EutxoRecord> orderedCreated = new ArrayList<>(created);
        orderedCreated.sort(Comparator.comparing(value -> value.outpoint().toString()));
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(orderedCreated.size()).array());
        orderedCreated.forEach(value -> {
            update(digest, value.outpoint().toString());
            update(digest, value.address());
            digest.update(ByteBuffer.allocate(Integer.BYTES)
                    .putInt(value.outputCbor().length).array());
            digest.update(value.outputCbor());
            digest.update((byte) value.origin().ordinal());
        });
        return digest.digest();
    }

    private static void update(MessageDigest digest, String value) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(encoded.length).array());
        digest.update(encoded);
    }

    private static byte[] copy32(byte[] value, String label) {
        Objects.requireNonNull(value, label);
        if (value.length != 32) {
            throw new IllegalArgumentException(label + " must contain 32 bytes");
        }
        return value.clone();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
