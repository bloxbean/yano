package com.bloxbean.cardano.yano.runtime.appchain;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.vds.core.NibblePath;
import com.bloxbean.cardano.vds.core.nibbles.Nibbles;
import com.bloxbean.cardano.vds.mpf.commitment.MpfCommitmentScheme;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Root-reachable semantic commitment verification for one persisted MPF snapshot. */
final class SnapshotMpfIntegrity {
    private final java.util.function.Function<byte[], byte[]> lookup;
    private final long maximumNodes;
    private final long maximumBytes;
    private final MpfCommitmentScheme commitments = new MpfCommitmentScheme(
            Blake2bUtil::blake2bHash256);
    private final Set<Key> verified = new HashSet<>();
    private final Set<Key> visiting = new HashSet<>();
    private long bytes;
    private long entries;
    private int maximumFoldDepth;

    SnapshotMpfIntegrity(java.util.function.Function<byte[], byte[]> lookup,
                         long maximumNodes, long maximumBytes) {
        this.lookup = java.util.Objects.requireNonNull(lookup, "lookup");
        this.maximumNodes = maximumNodes;
        this.maximumBytes = maximumBytes;
    }

    Result verify(byte[] root) {
        requireHash(root, "root");
        verifyNode(root, 0);
        return new Result(verified.size(), entries, bytes, maximumFoldDepth,
                java.util.Collections.unmodifiableSet(verified));
    }

    private void verifyNode(byte[] expected, int foldDepth) {
        Key key = new Key(expected);
        if (verified.contains(key)) return;
        if (!visiting.add(key)) throw corrupt("MPF node cycle");
        byte[] encoded = encoded(expected);
        Node node = decode(encoded);
        bytes = Math.addExact(bytes, expected.length + encoded.length);
        if (verified.size() + visiting.size() > maximumNodes || bytes > maximumBytes) {
            throw corrupt("MPF integrity limits exceeded");
        }
        byte[] actual = commitment(node);
        if (!Arrays.equals(actual, expected)) throw corrupt("MPF node commitment mismatch");
        if (node instanceof Branch branch) {
            maximumFoldDepth = Math.max(maximumFoldDepth, foldDepth + 1);
            if (branch.value.length > 0) entries++;
            for (byte[] child : branch.children) if (child.length > 0) verifyNode(child, foldDepth + 1);
        } else if (node instanceof Extension extension) {
            verifyNode(extension.child, foldDepth);
        } else {
            entries++;
        }
        visiting.remove(key);
        verified.add(key);
    }

    private byte[] commitment(Node node) {
        if (node instanceof Leaf leaf) {
            return commitments.commitLeaf(path(leaf.hp), Blake2bUtil.blake2bHash256(leaf.value));
        }
        if (node instanceof Branch branch) {
            return commitments.commitBranch(NibblePath.EMPTY, nulls(branch.children),
                    branch.value.length == 0 ? null : Blake2bUtil.blake2bHash256(branch.value));
        }
        Extension extension = (Extension) node;
        NibblePath prefix = path(extension.hp);
        byte[] child = extension.child;
        Set<Key> chain = new HashSet<>();
        while (true) {
            if (!chain.add(new Key(child))) throw corrupt("MPF extension cycle");
            Node current = node(child);
            if (current instanceof Extension next) {
                prefix = prefix.concat(path(next.hp));
                child = next.child;
            } else if (current instanceof Branch branch) {
                return commitments.commitBranch(prefix, nulls(branch.children),
                        branch.value.length == 0 ? null : Blake2bUtil.blake2bHash256(branch.value));
            } else {
                Leaf leaf = (Leaf) current;
                return commitments.commitLeaf(prefix.concat(path(leaf.hp)),
                        Blake2bUtil.blake2bHash256(leaf.value));
            }
        }
    }

    private Node node(byte[] hash) {
        requireHash(hash, "node hash");
        return decode(encoded(hash));
    }

    private byte[] encoded(byte[] hash) {
        byte[] encoded = lookup.apply(hash.clone());
        if (encoded == null) throw corrupt("missing MPF node");
        return encoded;
    }

    private static Node decode(byte[] encoded) {
        try {
            List<DataItem> values = new CborDecoder(new ByteArrayInputStream(encoded)).decode();
            if (values.size() != 1 || !(values.get(0) instanceof Array array)) throw corrupt("invalid MPF CBOR");
            List<DataItem> fields = array.getDataItems();
            if (fields.size() == 17) {
                byte[][] children = new byte[16][];
                for (int index = 0; index < 16; index++) {
                    children[index] = bytes(fields.get(index));
                    if (children[index].length != 0) requireHash(children[index], "branch child");
                }
                return new Branch(children, bytes(fields.get(16)));
            }
            if (fields.size() != 2 && fields.size() != 3) throw corrupt("invalid MPF node arity");
            byte[] hp = bytes(fields.get(0));
            Nibbles.HP unpacked = Nibbles.unpackHP(hp);
            if (unpacked.isLeaf) {
                byte[] key = fields.size() == 3 ? bytes(fields.get(2)) : new byte[0];
                return new Leaf(hp, bytes(fields.get(1)), key);
            }
            if (fields.size() != 2) throw corrupt("extension node contains an original key");
            byte[] child = bytes(fields.get(1));
            requireHash(child, "extension child");
            return new Extension(hp, child);
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("invalid MPF node encoding", failure);
        }
    }

    private static NibblePath path(byte[] hp) {
        return NibblePath.of(Nibbles.unpackHP(hp).nibbles);
    }

    private static byte[][] nulls(byte[][] children) {
        byte[][] result = new byte[16][];
        for (int index = 0; index < 16; index++) {
            result[index] = children[index].length == 0 ? null : children[index];
        }
        return result;
    }

    private static byte[] bytes(DataItem value) {
        if (!(value instanceof ByteString bytes)) throw corrupt("MPF node field is not bytes");
        return bytes.getBytes();
    }

    private static void requireHash(byte[] value, String field) {
        if (value == null || value.length != 32) throw corrupt(field + " must contain 32 bytes");
    }

    private static IllegalArgumentException corrupt(String message) {
        return new IllegalArgumentException(message);
    }

    record Result(long nodeCount, long entryCount, long bytes, int maximumFoldDepth,
                  Set<Key> reachable) {
        boolean contains(byte[] key) { return reachable.contains(new Key(key)); }
    }

    private sealed interface Node permits Branch, Extension, Leaf { }
    private record Branch(byte[][] children, byte[] value) implements Node { }
    private record Extension(byte[] hp, byte[] child) implements Node { }
    private record Leaf(byte[] hp, byte[] value, byte[] key) implements Node { }

    record Key(byte[] value) {
        Key { value = value.clone(); }
        private byte[] bytes() { return value.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof Key that && Arrays.equals(value, that.value);
        }
        @Override public int hashCode() { return Arrays.hashCode(value); }
    }
}
