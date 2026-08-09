package com.bloxbean.cardano.yano.appchain.proofs;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Bounded normalization of an MPF non-membership proof.
 *
 * <p>The terminal is either the empty child hash or a conflicting leaf. Folds
 * are ordered from that terminal back to the root, matching
 * {@link MpfNormalizedProof}.</p>
 */
public record MpfNormalizedNonMembershipProof(
        byte[] stateRoot,
        byte[] key,
        int terminalCursor,
        byte[] conflictingLeafSuffix,
        byte[] conflictingKeyHash,
        byte[] conflictingValueHash,
        List<MpfNormalizedProof.FoldStep> folds,
        long committedHeight
) {
    public MpfNormalizedNonMembershipProof {
        stateRoot = exact(stateRoot, 32, "state root");
        key = bounded(key, 1, MpfNormalizedProof.MAX_KEY_BYTES, "key");
        if (terminalCursor < 0 || terminalCursor > MpfNormalizedProof.PATH_NIBBLES) {
            throw new IllegalArgumentException("MPF absence terminal cursor is out of range");
        }
        conflictingLeafSuffix = conflictingLeafSuffix == null
                ? new byte[0] : conflictingLeafSuffix.clone();
        conflictingKeyHash = conflictingKeyHash == null
                ? new byte[0] : conflictingKeyHash.clone();
        conflictingValueHash = conflictingValueHash == null
                ? new byte[0] : conflictingValueHash.clone();
        boolean conflicting = conflictingLeafSuffix.length != 0
                || conflictingKeyHash.length != 0 || conflictingValueHash.length != 0;
        if (conflicting) {
            bounded(conflictingLeafSuffix, 1, 33, "conflicting leaf suffix");
            exact(conflictingKeyHash, 32, "conflicting key hash");
            exact(conflictingValueHash, 32, "conflicting value hash");
        }
        folds = List.copyOf(Objects.requireNonNull(folds, "folds"));
        if (folds.size() > MpfNormalizedProof.MAX_FOLDS) {
            throw new IllegalArgumentException("MPF proof contains too many folds");
        }
        if (committedHeight < 0) {
            throw new IllegalArgumentException("MPF committed height cannot be negative");
        }
    }

    public boolean conflictingLeaf() {
        return conflictingLeafSuffix.length != 0;
    }

    public boolean verify() {
        byte[] queryPath = MpfNormalizedProof.nibbles(Blake2bUtil.blake2bHash256(key));
        byte[] child;
        if (conflictingLeaf()) {
            if (Arrays.equals(Blake2bUtil.blake2bHash256(key), conflictingKeyHash)) {
                return false;
            }
            byte[] conflictPath = MpfNormalizedProof.nibbles(conflictingKeyHash);
            byte[] expectedSuffix = MpfNormalizedProof.encodeLeafSuffix(
                    Arrays.copyOfRange(conflictPath, terminalCursor, conflictPath.length));
            if (!Arrays.equals(expectedSuffix, conflictingLeafSuffix)) {
                return false;
            }
            child = MpfNormalizedProof.commitLeaf(conflictingLeafSuffix,
                    conflictingValueHash);
        } else {
            child = new byte[32];
        }
        int cursorEnd = terminalCursor;
        for (MpfNormalizedProof.FoldStep fold : folds) {
            int cursor = fold.cursor();
            if (cursor + fold.prefix().length + 1 != cursorEnd
                    || !Arrays.equals(fold.prefix(), Arrays.copyOfRange(
                    queryPath, cursor, cursor + fold.prefix().length))
                    || (queryPath[cursor + fold.prefix().length] & 0xFF) != fold.nibble()) {
                return false;
            }
            byte[] merkle = MpfNormalizedProof.aggregateSiblingHashes(
                    fold.nibble(), child, fold.neighbors());
            if (fold.branchValueHash().length == 32) {
                merkle = hash(merkle, MpfNormalizedProof.commitLeaf(
                        MpfNormalizedProof.encodeLeafSuffix(new byte[0]),
                        fold.branchValueHash()));
            }
            child = hash(fold.prefix(), merkle);
            cursorEnd = cursor;
        }
        return cursorEnd == 0 && Arrays.equals(stateRoot, child);
    }

    @Override public byte[] stateRoot() { return stateRoot.clone(); }
    @Override public byte[] key() { return key.clone(); }
    @Override public byte[] conflictingLeafSuffix() { return conflictingLeafSuffix.clone(); }
    @Override public byte[] conflictingKeyHash() { return conflictingKeyHash.clone(); }
    @Override public byte[] conflictingValueHash() { return conflictingValueHash.clone(); }

    private static byte[] hash(byte[]... values) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] value : values) output.writeBytes(value);
        return Blake2bUtil.blake2bHash256(output.toByteArray());
    }

    private static byte[] exact(byte[] value, int length, String field) {
        if (value == null || value.length != length) {
            throw new IllegalArgumentException("MPF " + field + " must contain " + length + " bytes");
        }
        return value.clone();
    }

    private static byte[] bounded(byte[] value, int minimum, int maximum, String field) {
        if (value == null || value.length < minimum || value.length > maximum) {
            throw new IllegalArgumentException("MPF " + field + " length is outside bounds");
        }
        return value.clone();
    }
}
