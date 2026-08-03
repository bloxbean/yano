package com.bloxbean.cardano.yano.appchain.eutxo.contracts;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Bounded, on-chain-friendly normalization of a Yano MPF inclusion proof.
 *
 * <p>Steps are ordered from the leaf back to the root. The cursor and prefix
 * fields are retained and verified against {@code blake2b_256(key)} so proof
 * conversion cannot substitute a different trie path.</p>
 */
public record EutxoMpfProof(
        byte[] stateRoot,
        byte[] key,
        byte[] value,
        byte[] leafSuffix,
        List<FoldStep> folds,
        long committedHeight
) {
    public static final int HASH_BYTES = 32;
    public static final int PATH_NIBBLES = HASH_BYTES * 2;
    public static final int MAX_FOLDS = PATH_NIBBLES;
    public static final int MAX_KEY_BYTES = 256;
    public static final int MAX_VALUE_BYTES = 16 * 1024;

    public EutxoMpfProof {
        stateRoot = exact(stateRoot, HASH_BYTES, "state root");
        key = bounded(key, 1, MAX_KEY_BYTES, "key");
        value = bounded(value, 1, MAX_VALUE_BYTES, "value");
        leafSuffix = bounded(
                leafSuffix, 1, HASH_BYTES + 1, "encoded leaf suffix");
        validateEncodedLeafSuffix(leafSuffix);
        folds = List.copyOf(Objects.requireNonNull(folds, "folds"));
        if (folds.size() > MAX_FOLDS) {
            throw new IllegalArgumentException("MPF proof contains too many folds");
        }
        if (committedHeight < 0) {
            throw new IllegalArgumentException(
                    "MPF committed height cannot be negative");
        }
    }

    public boolean verify() {
        byte[] path = nibbles(Blake2bUtil.blake2bHash256(key));
        byte[] suffix = decodeLeafSuffix(leafSuffix);
        int cursorEnd = PATH_NIBBLES - suffix.length;
        if (!Arrays.equals(
                suffix,
                Arrays.copyOfRange(path, cursorEnd, PATH_NIBBLES))) {
            return false;
        }
        byte[] child = commitLeaf(
                leafSuffix, Blake2bUtil.blake2bHash256(value));
        for (FoldStep fold : folds) {
            int prefixLength = fold.prefix().length;
            int cursor = fold.cursor();
            if (cursor < 0
                    || cursor + prefixLength + 1 != cursorEnd
                    || cursor + prefixLength >= PATH_NIBBLES
                    || !Arrays.equals(
                    fold.prefix(),
                    Arrays.copyOfRange(path, cursor, cursor + prefixLength))
                    || (path[cursor + prefixLength] & 0xFF) != fold.nibble()) {
                return false;
            }
            byte[] merkle = aggregateSiblingHashes(
                    fold.nibble(), child, fold.neighbors());
            if (fold.branchValueHash().length == HASH_BYTES) {
                merkle = hash(
                        merkle,
                        commitLeaf(
                                encodeLeafSuffix(new byte[0]),
                                fold.branchValueHash()));
            }
            child = hash(fold.prefix(), merkle);
            cursorEnd = cursor;
        }
        return cursorEnd == 0 && Arrays.equals(stateRoot, child);
    }

    @Override
    public byte[] stateRoot() {
        return stateRoot.clone();
    }

    @Override
    public byte[] key() {
        return key.clone();
    }

    @Override
    public byte[] value() {
        return value.clone();
    }

    @Override
    public byte[] leafSuffix() {
        return leafSuffix.clone();
    }

    public record FoldStep(
            int cursor,
            byte[] prefix,
            int nibble,
            List<byte[]> neighbors,
            byte[] branchValueHash
    ) {
        public FoldStep {
            if (cursor < 0 || cursor >= PATH_NIBBLES) {
                throw new IllegalArgumentException("MPF fold cursor is out of range");
            }
            prefix = bounded(prefix, 0, PATH_NIBBLES, "fold prefix");
            requireNibbles(prefix, "fold prefix");
            if (nibble < 0 || nibble > 15) {
                throw new IllegalArgumentException("MPF fold nibble is out of range");
            }
            neighbors = Objects.requireNonNull(neighbors, "neighbors").stream()
                    .map(value -> exact(value, HASH_BYTES, "neighbor hash"))
                    .toList();
            if (neighbors.size() != 4) {
                throw new IllegalArgumentException(
                        "MPF fold requires four neighbor hashes");
            }
            branchValueHash = bounded(
                    branchValueHash, 0, HASH_BYTES, "branch value hash");
            if (branchValueHash.length != 0
                    && branchValueHash.length != HASH_BYTES) {
                throw new IllegalArgumentException(
                        "branch value hash must be absent or 32 bytes");
            }
        }

        @Override
        public byte[] prefix() {
            return prefix.clone();
        }

        @Override
        public List<byte[]> neighbors() {
            return neighbors.stream().map(byte[]::clone).toList();
        }

        @Override
        public byte[] branchValueHash() {
            return branchValueHash.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof FoldStep fold
                    && cursor == fold.cursor
                    && Arrays.equals(prefix, fold.prefix)
                    && nibble == fold.nibble
                    && arrayListEquals(neighbors, fold.neighbors)
                    && Arrays.equals(branchValueHash, fold.branchValueHash);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(cursor, nibble);
            result = 31 * result + Arrays.hashCode(prefix);
            for (byte[] neighbor : neighbors) {
                result = 31 * result + Arrays.hashCode(neighbor);
            }
            return 31 * result + Arrays.hashCode(branchValueHash);
        }
    }

    public static byte[] encodeLeafSuffix(byte[] suffix) {
        requireNibbles(suffix, "leaf suffix");
        byte[] encoded;
        int offset;
        if ((suffix.length & 1) == 1) {
            encoded = new byte[2 + (suffix.length - 1) / 2];
            encoded[0] = 0;
            encoded[1] = suffix[0];
            offset = 1;
        } else {
            encoded = new byte[1 + suffix.length / 2];
            encoded[0] = (byte) 0xFF;
            offset = 0;
        }
        int encodedOffset = offset == 1 ? 2 : 1;
        for (int i = offset; i < suffix.length; i += 2) {
            encoded[encodedOffset + (i - offset) / 2] =
                    (byte) (((suffix[i] & 0x0F) << 4)
                            | (suffix[i + 1] & 0x0F));
        }
        return encoded;
    }

    private static byte[] decodeLeafSuffix(byte[] encoded) {
        validateEncodedLeafSuffix(encoded);
        boolean odd = encoded[0] == 0;
        byte[] suffix = new byte[
                odd ? 1 + (encoded.length - 2) * 2
                        : (encoded.length - 1) * 2];
        int encodedOffset = odd ? 2 : 1;
        int suffixOffset = odd ? 1 : 0;
        if (odd) {
            suffix[0] = encoded[1];
        }
        for (int i = encodedOffset; i < encoded.length; i++) {
            suffix[suffixOffset++] =
                    (byte) ((encoded[i] >>> 4) & 0x0F);
            suffix[suffixOffset++] = (byte) (encoded[i] & 0x0F);
        }
        return suffix;
    }

    private static void validateEncodedLeafSuffix(byte[] encoded) {
        int marker = encoded[0] & 0xFF;
        if (marker != 0 && marker != 0xFF) {
            throw new IllegalArgumentException(
                    "encoded leaf suffix has an invalid marker");
        }
        if (marker == 0
                && (encoded.length < 2 || (encoded[1] & 0xFF) > 15)) {
            throw new IllegalArgumentException(
                    "encoded odd leaf suffix has an invalid first nibble");
        }
    }

    static byte[] commitLeaf(byte[] encodedSuffix, byte[] valueHash) {
        return hash(encodedSuffix, valueHash);
    }

    static byte[] aggregateSiblingHashes(
            int nibble,
            byte[] me,
            List<byte[]> neighbors
    ) {
        byte[] lvl1 = neighbors.get(0);
        byte[] lvl2 = neighbors.get(1);
        byte[] lvl3 = neighbors.get(2);
        byte[] lvl4 = neighbors.get(3);
        return switch (nibble) {
            case 0 -> hash(hash(hash(hash(me, lvl4), lvl3), lvl2), lvl1);
            case 1 -> hash(hash(hash(hash(lvl4, me), lvl3), lvl2), lvl1);
            case 2 -> hash(hash(hash(lvl3, hash(me, lvl4)), lvl2), lvl1);
            case 3 -> hash(hash(hash(lvl3, hash(lvl4, me)), lvl2), lvl1);
            case 4 -> hash(hash(lvl2, hash(hash(me, lvl4), lvl3)), lvl1);
            case 5 -> hash(hash(lvl2, hash(hash(lvl4, me), lvl3)), lvl1);
            case 6 -> hash(hash(lvl2, hash(lvl3, hash(me, lvl4))), lvl1);
            case 7 -> hash(hash(lvl2, hash(lvl3, hash(lvl4, me))), lvl1);
            case 8 -> hash(lvl1, hash(hash(hash(me, lvl4), lvl3), lvl2));
            case 9 -> hash(lvl1, hash(hash(hash(lvl4, me), lvl3), lvl2));
            case 10 -> hash(lvl1, hash(hash(lvl3, hash(me, lvl4)), lvl2));
            case 11 -> hash(lvl1, hash(hash(lvl3, hash(lvl4, me)), lvl2));
            case 12 -> hash(lvl1, hash(lvl2, hash(hash(me, lvl4), lvl3)));
            case 13 -> hash(lvl1, hash(lvl2, hash(hash(lvl4, me), lvl3)));
            case 14 -> hash(lvl1, hash(lvl2, hash(lvl3, hash(me, lvl4))));
            case 15 -> hash(lvl1, hash(lvl2, hash(lvl3, hash(lvl4, me))));
            default -> throw new IllegalArgumentException(
                    "MPF nibble is out of range");
        };
    }

    public static List<byte[]> sparseNeighbors(
            int meNibble,
            int neighborNibble,
            byte[] neighborHash
    ) {
        if (meNibble < 0 || meNibble > 15
                || neighborNibble < 0 || neighborNibble > 15
                || meNibble == neighborNibble) {
            throw new IllegalArgumentException("invalid sparse MPF branch");
        }
        byte[][] nodes = new byte[16][HASH_BYTES];
        nodes[neighborNibble] = exact(
                neighborHash, HASH_BYTES, "neighbor hash");
        java.util.ArrayList<byte[]> neighbors = new java.util.ArrayList<>(4);
        int pivot = 8;
        int width = 8;
        while (width >= 1) {
            if (meNibble < pivot) {
                neighbors.add(merkleRoot(nodes, pivot, pivot + width));
                pivot -= width >> 1;
            } else {
                neighbors.add(merkleRoot(nodes, pivot - width, pivot));
                pivot += width >> 1;
            }
            width >>= 1;
        }
        return List.copyOf(neighbors);
    }

    private static byte[] merkleRoot(byte[][] nodes, int start, int end) {
        java.util.ArrayList<byte[]> layer = new java.util.ArrayList<>(end - start);
        for (int i = start; i < end; i++) {
            layer.add(nodes[i].clone());
        }
        while (layer.size() > 1) {
            java.util.ArrayList<byte[]> next =
                    new java.util.ArrayList<>(layer.size() / 2);
            for (int i = 0; i < layer.size(); i += 2) {
                next.add(hash(layer.get(i), layer.get(i + 1)));
            }
            layer = next;
        }
        return layer.getFirst();
    }

    public static byte[] nibbles(byte[] hash) {
        byte[] out = new byte[hash.length * 2];
        for (int i = 0; i < hash.length; i++) {
            out[i * 2] = (byte) ((hash[i] >>> 4) & 0x0F);
            out[i * 2 + 1] = (byte) (hash[i] & 0x0F);
        }
        return out;
    }

    private static byte[] hash(byte[]... values) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] value : values) {
            output.writeBytes(value);
        }
        return Blake2bUtil.blake2bHash256(output.toByteArray());
    }

    private static byte[] exact(byte[] value, int length, String field) {
        return bounded(value, length, length, field);
    }

    private static byte[] bounded(
            byte[] value,
            int minimum,
            int maximum,
            String field
    ) {
        byte[] copy = Objects.requireNonNull(value, field).clone();
        if (copy.length < minimum || copy.length > maximum) {
            throw new IllegalArgumentException(
                    field + " must contain " + minimum + "-" + maximum + " bytes");
        }
        return copy;
    }

    private static void requireNibbles(byte[] value, String field) {
        for (byte nibble : value) {
            if ((nibble & 0xFF) > 15) {
                throw new IllegalArgumentException(
                        field + " contains a value outside 0-15");
            }
        }
    }

    private static boolean arrayListEquals(
            List<byte[]> left,
            List<byte[]> right
    ) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            if (!Arrays.equals(left.get(i), right.get(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EutxoMpfProof proof
                && Arrays.equals(stateRoot, proof.stateRoot)
                && Arrays.equals(key, proof.key)
                && Arrays.equals(value, proof.value)
                && Arrays.equals(leafSuffix, proof.leafSuffix)
                && folds.equals(proof.folds)
                && committedHeight == proof.committedHeight;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(folds, committedHeight);
        result = 31 * result + Arrays.hashCode(stateRoot);
        result = 31 * result + Arrays.hashCode(key);
        result = 31 * result + Arrays.hashCode(value);
        return 31 * result + Arrays.hashCode(leafSuffix);
    }
}
