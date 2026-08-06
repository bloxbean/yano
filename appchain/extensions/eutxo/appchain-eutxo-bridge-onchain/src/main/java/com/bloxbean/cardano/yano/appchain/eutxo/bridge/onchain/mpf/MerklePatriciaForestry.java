package com.bloxbean.cardano.yano.appchain.eutxo.bridge.onchain.mpf;

import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.stdlib.lib.ByteStringLib;
import com.bloxbean.cardano.julc.stdlib.lib.CryptoLib;
import com.bloxbean.cardano.yano.appchain.eutxo.bridge.onchain.mpf.ProofStep;
import com.bloxbean.cardano.yano.appchain.eutxo.bridge.onchain.mpf.Neighbor;

import java.util.List;

/**
 * Merkle Patricia Forestry (MPF) on-chain library.
 * <p>
 * Provides membership proofs (has/miss) for a radix-16 Merkle Patricia Trie.
 * Translated from the Aiken merkle-patricia-forestry library.
 * <p>
 * Methods are ordered bottom-up (leaves first, entry methods last) so that
 * callees are always in scope when callers are compiled.
 */
@OnchainLibrary
public class MerklePatriciaForestry {

    // =========================================================================
    // 1. Leaf helpers (no intra-library dependencies)
    // =========================================================================

    /** Blake2b-256 hash of the concatenation of left and right. */
    public static byte[] combine(byte[] left, byte[] right) {
        return CryptoLib.blake2b_256(ByteStringLib.append(left, right));
    }

    /** Get the 4-bit nibble at a given index in a byte path. */
    public static int nibble(byte[] self, int index) {
        int byteVal = (int) ByteStringLib.at(self, index / 2);
        if (index % 2 == 0) {
            return byteVal / 16;
        } else {
            return byteVal % 16;
        }
    }

    /** Extract nibbles from start (inclusive) to end (exclusive) as a bytestring. */
    public static byte[] nibbles(byte[] path, int start, int end) {
        if (start >= end) {
            return ByteStringLib.empty();
        } else {
            int n = nibble(path, start);
            byte[] rest = nibbles(path, start + 1, end);
            return ByteStringLib.cons(n, rest);
        }
    }

    /** Compute the suffix encoding of a path from cursor position. */
    public static byte[] suffix(byte[] path, int cursor) {
        if (cursor % 2 == 0) {
            byte[] dropped = ByteStringLib.drop(path, cursor / 2);
            return ByteStringLib.cons(0xff, dropped);
        } else {
            int n = nibble(path, cursor);
            byte[] dropped = ByteStringLib.drop(path, (cursor + 1) / 2);
            byte[] withNibble = ByteStringLib.cons(n, dropped);
            return ByteStringLib.cons(0, withNibble);
        }
    }

    /** Null hash: 32 zero bytes. */
    public static byte[] nullHash() {
        return ByteStringLib.zeros(32);
    }

    /** combine(nullHash, nullHash) */
    public static byte[] nullHash2() {
        return combine(nullHash(), nullHash());
    }

    /** combine(nullHash2, nullHash2) */
    public static byte[] nullHash4() {
        return combine(nullHash2(), nullHash2());
    }

    /** combine(nullHash4, nullHash4) */
    public static byte[] nullHash8() {
        return combine(nullHash4(), nullHash4());
    }

    /**
     * Combine hash with other based on position parity.
     * Even positions: combine(hash, other). Odd positions: combine(other, hash).
     */
    public static byte[] combineAt(byte[] hash, byte[] other, int position) {
        if (position % 2 == 0) {
            return combine(hash, other);
        } else {
            return combine(other, hash);
        }
    }

    // =========================================================================
    // 2. Merkle tree placement (depends on: combine, combineAt, nullHash*)
    // =========================================================================

    public static byte[] merkle4(int branch, byte[] root, byte[] n2, byte[] n1) {
        if (branch <= 1) {
            if (branch == 0) {
                return combine(combine(root, n1), n2);
            } else {
                return combine(combine(n1, root), n2);
            }
        } else {
            if (branch == 2) {
                return combine(n2, combine(root, n1));
            } else {
                return combine(n2, combine(n1, root));
            }
        }
    }

    public static byte[] merkle8(int branch, byte[] root, byte[] n4, byte[] n2, byte[] n1) {
        if (branch <= 3) {
            return combine(merkle4(branch, root, n2, n1), n4);
        } else {
            return combine(n4, merkle4(branch - 4, root, n2, n1));
        }
    }

    public static byte[] merkle16(int branch, byte[] root, byte[] n8, byte[] n4, byte[] n2, byte[] n1) {
        if (branch <= 7) {
            return combine(merkle8(branch, root, n4, n2, n1), n8);
        } else {
            return combine(n8, merkle8(branch - 8, root, n4, n2, n1));
        }
    }

    // =========================================================================
    // 3. Sparse merkle (depends on: combine, combineAt, merkle*, nullHash*)
    // =========================================================================

    public static byte[] sparseMerkle4(int me, byte[] meHash, int neighbor, byte[] neighborHash) {
        if (me <= 1) {
            if (neighbor <= 1) {
                byte[] meNode = combineAt(meHash, neighborHash, me);
                return combine(meNode, nullHash2());
            } else {
                byte[] meNode = combineAt(meHash, nullHash(), me);
                byte[] neighborNode = combineAt(neighborHash, nullHash(), neighbor);
                return combine(meNode, neighborNode);
            }
        } else {
            if (neighbor >= 2) {
                byte[] meNode = combineAt(meHash, neighborHash, me);
                return combine(nullHash2(), meNode);
            } else {
                byte[] neighborNode = combineAt(neighborHash, nullHash(), neighbor);
                byte[] meNode = combineAt(meHash, nullHash(), me);
                return combine(neighborNode, meNode);
            }
        }
    }

    public static byte[] sparseMerkle8(int me, byte[] meHash, int neighbor, byte[] neighborHash) {
        if (me <= 3) {
            if (neighbor <= 3) {
                return combine(sparseMerkle4(me, meHash, neighbor, neighborHash), nullHash4());
            } else {
                return combine(
                        merkle4(me, meHash, nullHash2(), nullHash()),
                        merkle4(neighbor - 4, neighborHash, nullHash2(), nullHash()));
            }
        } else {
            if (neighbor >= 4) {
                return combine(nullHash4(), sparseMerkle4(me - 4, meHash, neighbor - 4, neighborHash));
            } else {
                return combine(
                        merkle4(neighbor, neighborHash, nullHash2(), nullHash()),
                        merkle4(me - 4, meHash, nullHash2(), nullHash()));
            }
        }
    }

    public static byte[] sparseMerkle16(int me, byte[] meHash, int neighbor, byte[] neighborHash) {
        if (me <= 7) {
            if (neighbor <= 7) {
                return combine(sparseMerkle8(me, meHash, neighbor, neighborHash), nullHash8());
            } else {
                return combine(
                        merkle8(me, meHash, nullHash4(), nullHash2(), nullHash()),
                        merkle8(neighbor - 8, neighborHash, nullHash4(), nullHash2(), nullHash()));
            }
        } else {
            if (neighbor >= 8) {
                return combine(nullHash8(), sparseMerkle8(me - 8, meHash, neighbor - 8, neighborHash));
            } else {
                return combine(
                        merkle8(neighbor, neighborHash, nullHash4(), nullHash2(), nullHash()),
                        merkle8(me - 8, meHash, nullHash4(), nullHash2(), nullHash()));
            }
        }
    }

    // =========================================================================
    // 4. Branch/Fork processing (depends on: nibble, nibbles, combine, merkle16, sparseMerkle16)
    // =========================================================================

    /** Process a branch step. */
    public static byte[] doBranch(byte[] path, int cursor, int nextCursor, byte[] root, byte[] neighbors) {
        int branch = nibble(path, nextCursor);
        byte[] prefix = nibbles(path, cursor, nextCursor);
        byte[] n8 = ByteStringLib.slice(neighbors, 0, 32);
        byte[] n4 = ByteStringLib.slice(neighbors, 32, 32);
        byte[] n2 = ByteStringLib.slice(neighbors, 64, 32);
        byte[] n1 = ByteStringLib.slice(neighbors, 96, 32);
        return combine(prefix, merkle16(branch, root, n8, n4, n2, n1));
    }

    /** Process a fork step. */
    public static byte[] doFork(byte[] path, int cursor, int nextCursor, byte[] root,
                                int neighborNibble, byte[] neighborPrefix, byte[] neighborRoot) {
        int branch = nibble(path, nextCursor);
        byte[] prefix = nibbles(path, cursor, nextCursor);
        byte[] neighborHash = combine(neighborPrefix, neighborRoot);
        return combine(prefix, sparseMerkle16(branch, root, neighborNibble, neighborHash));
    }

    // =========================================================================
    // 5. Recursive proof walks (self-recursive, depends on: doBranch, doFork)
    // =========================================================================

    /** Recursive proof walk for inclusion. Self-recursive via Z-combinator. */
    public static byte[] doIncluding(byte[] path, byte[] value, int cursor, JulcList<ProofStep> proof) {
        if (proof.isEmpty()) {
            return combine(suffix(path, cursor), value);
        } else {
            ProofStep step = proof.head();
            JulcList<ProofStep> steps = proof.tail();
            if (step instanceof ProofStep.Branch b) {
                int nc = cursor + b.skip();
                byte[] root = doIncluding(path, value, nc + 1, steps);
                return doBranch(path, cursor, nc, root, b.neighbors());
            } else if (step instanceof ProofStep.Fork f) {
                int nc = cursor + f.skip();
                byte[] root = doIncluding(path, value, nc + 1, steps);
                return doFork(path, cursor, nc, root, f.neighbor().nibble(),
                        f.neighbor().prefix(), f.neighbor().root());
            } else {
                ProofStep.Leaf l = (ProofStep.Leaf) step;
                int nc = cursor + l.skip();
                byte[] root = doIncluding(path, value, nc + 1, steps);
                return doFork(path, cursor, nc, root, nibble(l.key(), nc),
                        suffix(l.key(), nc + 1), l.value());
            }
        }
    }

    /**
     * Recursive proof walk for exclusion. Self-recursive via Z-combinator.
     * Terminal and non-terminal cases are inlined to avoid mutual recursion.
     */
    public static byte[] doExcluding(byte[] path, int cursor, JulcList<ProofStep> proof) {
        if (proof.isEmpty()) {
            return nullHash();
        } else {
            ProofStep step = proof.head();
            JulcList<ProofStep> steps = proof.tail();

            if (steps.isEmpty()) {
                // Terminal case: last step in the proof
                if (step instanceof ProofStep.Branch b) {
                    int nc = cursor + b.skip();
                    byte[] root = nullHash();
                    return doBranch(path, cursor, nc, root, b.neighbors());
                } else if (step instanceof ProofStep.Fork f) {
                    byte[] neighborPrefix = ByteStringLib.cons(f.neighbor().nibble(), f.neighbor().prefix());
                    byte[] prefix = (f.skip() == 0) ? neighborPrefix
                            : ByteStringLib.append(nibbles(path, cursor, cursor + f.skip()), neighborPrefix);
                    return combine(prefix, f.neighbor().root());
                } else {
                    ProofStep.Leaf l = (ProofStep.Leaf) step;
                    return combine(suffix(l.key(), cursor), l.value());
                }
            } else {
                // Non-terminal case: more steps follow
                if (step instanceof ProofStep.Branch b) {
                    int nc = cursor + b.skip();
                    byte[] root = doExcluding(path, nc + 1, steps);
                    return doBranch(path, cursor, nc, root, b.neighbors());
                } else if (step instanceof ProofStep.Fork f) {
                    int nc = cursor + f.skip();
                    byte[] root = doExcluding(path, nc + 1, steps);
                    return doFork(path, cursor, nc, root, f.neighbor().nibble(),
                            f.neighbor().prefix(), f.neighbor().root());
                } else {
                    ProofStep.Leaf l = (ProofStep.Leaf) step;
                    int nc = cursor + l.skip();
                    byte[] root = doExcluding(path, nc + 1, steps);
                    return doFork(path, cursor, nc, root, nibble(l.key(), nc),
                            suffix(l.key(), nc + 1), l.value());
                }
            }
        }
    }

    // =========================================================================
    // 6. Entry methods (depends on: doIncluding, doExcluding)
    // =========================================================================

    /** Compute root hash with element included. */
    public static byte[] including(byte[] key, byte[] value, JulcList<ProofStep> proof) {
        byte[] path = CryptoLib.blake2b_256(key);
        byte[] hashedValue = CryptoLib.blake2b_256(value);
        return doIncluding(path, hashedValue, 0, proof);
    }

    /** Compute root hash with element excluded. */
    public static byte[] excluding(byte[] key, JulcList<ProofStep> proof) {
        byte[] path = CryptoLib.blake2b_256(key);
        return doExcluding(path, 0, proof);
    }

    // =========================================================================
    // 7. Public API (depends on: including, excluding)
    // =========================================================================

    /** Check if the trie contains a key with a given value. */
    public static boolean has(byte[] trieRoot, byte[] key, byte[] value, JulcList<ProofStep> proof) {
        byte[] computedRoot = including(key, value, proof);
        return ByteStringLib.equals(computedRoot, trieRoot);
    }

    /** Check if a key is missing from the trie. */
    public static boolean miss(byte[] trieRoot, byte[] key, JulcList<ProofStep> proof) {
        byte[] computedRoot = excluding(key, proof);
        return ByteStringLib.equals(computedRoot, trieRoot);
    }
}
