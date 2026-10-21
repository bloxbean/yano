package org.yanoproject.ledger.rules.util;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * An immutable hash map with structural sharing (a hash array mapped trie), for state that is published as one
 * immutable object and changed by building a new version (ADR-056 §3 overlays, §6 mempool state).
 *
 * <ul>
 *   <li>{@link #plus} and {@link #minus} return a new map in {@code O(log32 n)} and leave the receiver unchanged,
 *       so old versions stay valid and cheap to keep.</li>
 *   <li>Keys must be immutable and have value-based {@code equals}/{@code hashCode}; {@code null} keys and values
 *       are refused.</li>
 *   <li>Iteration order is unspecified (hash order).</li>
 * </ul>
 *
 * @param <K> key type
 * @param <V> value type
 */
public final class PersistentMap<K, V> implements Iterable<Map.Entry<K, V>> {

    private static final PersistentMap<?, ?> EMPTY = new PersistentMap<>(null, 0);
    private static final int BITS = 5;
    private static final int MASK = (1 << BITS) - 1;

    private final Object root;
    private final int size;

    private PersistentMap(Object root, int size) {
        this.root = root;
        this.size = size;
    }

    /** @return the empty map */
    @SuppressWarnings("unchecked")
    public static <K, V> PersistentMap<K, V> empty() {
        return (PersistentMap<K, V>) EMPTY;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** @return the value for {@code key}, or {@code null} */
    @SuppressWarnings("unchecked")
    public V get(Object key) {
        if (key == null || root == null) {
            return null;
        }
        int hash = hash(key);
        Object node = root;
        int shift = 0;
        while (true) {
            if (node instanceof Leaf leaf) {
                return leaf.hash == hash && leaf.key.equals(key) ? (V) leaf.value : null;
            }
            if (node instanceof Collision collision) {
                if (collision.hash != hash) {
                    return null;
                }
                for (Leaf leaf : collision.leaves) {
                    if (leaf.key.equals(key)) {
                        return (V) leaf.value;
                    }
                }
                return null;
            }
            Branch branch = (Branch) node;
            int bit = 1 << ((hash >>> shift) & MASK);
            if ((branch.bitmap & bit) == 0) {
                return null;
            }
            node = branch.children[Integer.bitCount(branch.bitmap & (bit - 1))];
            shift += BITS;
        }
    }

    public boolean containsKey(Object key) {
        return get(key) != null;
    }

    /** @return a map with {@code key} mapped to {@code value}; this map is unchanged */
    public PersistentMap<K, V> plus(K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        boolean[] added = new boolean[1];
        Object newRoot = put(root, 0, hash(key), key, value, added);
        return newRoot == root ? this : new PersistentMap<>(newRoot, added[0] ? size + 1 : size);
    }

    /** @return a map without {@code key}; this map is unchanged */
    public PersistentMap<K, V> minus(Object key) {
        if (key == null || root == null) {
            return this;
        }
        Object newRoot = remove(root, 0, hash(key), key);
        if (newRoot == root) {
            return this;
        }
        return newRoot == null ? empty() : new PersistentMap<>(newRoot, size - 1);
    }

    @SuppressWarnings("unchecked")
    public void forEach(BiConsumer<? super K, ? super V> action) {
        Objects.requireNonNull(action, "action");
        if (root != null) {
            visit(root, leaf -> action.accept((K) leaf.key, (V) leaf.value));
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Iterator<Map.Entry<K, V>> iterator() {
        List<Map.Entry<K, V>> entries = new ArrayList<>(size);
        if (root != null) {
            visit(root, leaf -> entries.add(new AbstractMap.SimpleImmutableEntry<>((K) leaf.key, (V) leaf.value)));
        }
        return entries.iterator();
    }

    /** @return the keys, in iteration order */
    @SuppressWarnings("unchecked")
    public List<K> keys() {
        List<K> keys = new ArrayList<>(size);
        if (root != null) {
            visit(root, leaf -> keys.add((K) leaf.key));
        }
        return keys;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        forEach((k, v) -> {
            if (sb.length() > 1) {
                sb.append(", ");
            }
            sb.append(k).append('=').append(v);
        });
        return sb.append('}').toString();
    }

    // ---------------------------------------------------------------- trie

    private static int hash(Object key) {
        int h = key.hashCode();
        return h ^ (h >>> 16);
    }

    private record Leaf(int hash, Object key, Object value) {
    }

    private record Collision(int hash, Leaf[] leaves) {
    }

    private record Branch(int bitmap, Object[] children) {
    }

    private interface LeafVisitor {
        void visit(Leaf leaf);
    }

    private static void visit(Object node, LeafVisitor visitor) {
        if (node instanceof Leaf leaf) {
            visitor.visit(leaf);
        } else if (node instanceof Collision collision) {
            for (Leaf leaf : collision.leaves) {
                visitor.visit(leaf);
            }
        } else {
            for (Object child : ((Branch) node).children) {
                visit(child, visitor);
            }
        }
    }

    private static Object put(Object node, int shift, int hash, Object key, Object value, boolean[] added) {
        if (node == null) {
            added[0] = true;
            return new Leaf(hash, key, value);
        }
        if (node instanceof Leaf leaf) {
            if (leaf.hash == hash) {
                if (leaf.key.equals(key)) {
                    return leaf.value == value ? leaf : new Leaf(hash, key, value);
                }
                added[0] = true;
                return new Collision(hash, new Leaf[]{leaf, new Leaf(hash, key, value)});
            }
            added[0] = true;
            return merge(leaf, leaf.hash, new Leaf(hash, key, value), hash, shift);
        }
        if (node instanceof Collision collision) {
            if (collision.hash == hash) {
                Leaf[] leaves = collision.leaves;
                for (int i = 0; i < leaves.length; i++) {
                    if (leaves[i].key.equals(key)) {
                        if (leaves[i].value == value) {
                            return collision;
                        }
                        Leaf[] copy = leaves.clone();
                        copy[i] = new Leaf(hash, key, value);
                        return new Collision(hash, copy);
                    }
                }
                Leaf[] copy = Arrays.copyOf(leaves, leaves.length + 1);
                copy[leaves.length] = new Leaf(hash, key, value);
                added[0] = true;
                return new Collision(hash, copy);
            }
            added[0] = true;
            return merge(collision, collision.hash, new Leaf(hash, key, value), hash, shift);
        }
        Branch branch = (Branch) node;
        int bit = 1 << ((hash >>> shift) & MASK);
        int index = Integer.bitCount(branch.bitmap & (bit - 1));
        if ((branch.bitmap & bit) == 0) {
            Object[] children = new Object[branch.children.length + 1];
            System.arraycopy(branch.children, 0, children, 0, index);
            children[index] = new Leaf(hash, key, value);
            System.arraycopy(branch.children, index, children, index + 1, branch.children.length - index);
            added[0] = true;
            return new Branch(branch.bitmap | bit, children);
        }
        Object child = branch.children[index];
        Object newChild = put(child, shift + BITS, hash, key, value, added);
        if (newChild == child) {
            return branch;
        }
        Object[] children = branch.children.clone();
        children[index] = newChild;
        return new Branch(branch.bitmap, children);
    }

    /** Two nodes with different hashes under one branch at {@code shift}. */
    private static Object merge(Object a, int hashA, Object b, int hashB, int shift) {
        int indexA = (hashA >>> shift) & MASK;
        int indexB = (hashB >>> shift) & MASK;
        if (indexA == indexB) {
            return new Branch(1 << indexA, new Object[]{merge(a, hashA, b, hashB, shift + BITS)});
        }
        return new Branch((1 << indexA) | (1 << indexB), indexA < indexB ? new Object[]{a, b} : new Object[]{b, a});
    }

    private static Object remove(Object node, int shift, int hash, Object key) {
        if (node instanceof Leaf leaf) {
            return leaf.hash == hash && leaf.key.equals(key) ? null : leaf;
        }
        if (node instanceof Collision collision) {
            if (collision.hash != hash) {
                return collision;
            }
            Leaf[] leaves = collision.leaves;
            for (int i = 0; i < leaves.length; i++) {
                if (leaves[i].key.equals(key)) {
                    if (leaves.length == 2) {
                        return leaves[1 - i];
                    }
                    Leaf[] copy = new Leaf[leaves.length - 1];
                    System.arraycopy(leaves, 0, copy, 0, i);
                    System.arraycopy(leaves, i + 1, copy, i, leaves.length - i - 1);
                    return new Collision(hash, copy);
                }
            }
            return collision;
        }
        Branch branch = (Branch) node;
        int bit = 1 << ((hash >>> shift) & MASK);
        if ((branch.bitmap & bit) == 0) {
            return branch;
        }
        int index = Integer.bitCount(branch.bitmap & (bit - 1));
        Object child = branch.children[index];
        Object newChild = remove(child, shift + BITS, hash, key);
        if (newChild == child) {
            return branch;
        }
        if (newChild == null) {
            if (branch.children.length == 1) {
                return null;
            }
            Object[] children = new Object[branch.children.length - 1];
            System.arraycopy(branch.children, 0, children, 0, index);
            System.arraycopy(branch.children, index + 1, children, index, branch.children.length - index - 1);
            if (children.length == 1 && !(children[0] instanceof Branch)) {
                // A lone leaf or collision is placed by its own hash, so it can move up a level.
                return children[0];
            }
            return new Branch(branch.bitmap & ~bit, children);
        }
        if (branch.children.length == 1 && !(newChild instanceof Branch)) {
            return newChild;
        }
        Object[] children = branch.children.clone();
        children[index] = newChild;
        return new Branch(branch.bitmap, children);
    }
}
