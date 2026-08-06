package com.bloxbean.cardano.yano.appchain.eutxo.bridge.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;

/**
 * Conformance probe for {@link MpfLib} (never deployed): the redeemer is
 * Constr0[steps, pathHash, valueHash, includingFlag, expectedRoot] and the
 * spend succeeds only when the computed root equals the expectation —
 * letting tests drive the on-chain MPF arithmetic against real off-chain
 * tries on the julc VM.
 */
@SpendingValidator
public class MpfProbeValidator {

    @Entrypoint
    public static boolean validate(
            PlutusData datum, PlutusData redeemer, ScriptContext ctx) {
        PlutusData fields = Builtins.constrFields(redeemer);
        PlutusData steps = Builtins.headList(fields);
        PlutusData f2 = Builtins.tailList(fields);
        byte[] pathHash = Builtins.unBData(Builtins.headList(f2));
        PlutusData f3 = Builtins.tailList(f2);
        byte[] valueHash = Builtins.unBData(Builtins.headList(f3));
        PlutusData f4 = Builtins.tailList(f3);
        long including = Builtins.unIData(Builtins.headList(f4)).longValue();
        PlutusData f5 = Builtins.tailList(f4);
        byte[] expected = Builtins.unBData(Builtins.headList(f5));
        byte[] computed = computeRoot(
                steps, pathHash, valueHash, including == 1);
        return Builtins.equalsByteString(computed, expected);
    }


    static byte[] nullHash() {
        return Builtins.replicateByte(32, 0);
    }

    /**
     * Root committed by {@code steps} for the key whose blake2b-256 path is
     * {@code pathHash}. With {@code including=false}, {@code valueHash} is
     * ignored and the ABSENCE of the key is proven (result equals the current
     * root); with {@code including=true}, the result is the root WITH the
     * leaf {@code (pathHash, valueHash)} present — the post-insert root when
     * the same steps proved exclusion beforehand.
     */
    static byte[] computeRoot(
            PlutusData steps, byte[] pathHash, byte[] valueHash, boolean including) {
        PlutusData list = Builtins.unListData(steps);
        if (Builtins.nullList(list)) {
            if (!including) {
                return nullHash();
            }
            return commitLeafSuffix(pathHash, 0, valueHash);
        }
        byte[] result = walk(list, 0, pathHash, valueHash, including);
        if (Builtins.lengthOfByteString(result) == 0) {
            return nullHash();
        }
        return result;
    }

    /** Recursive traversal mirroring WireProof.loop; empty bytes = "absent". */
    private static byte[] walk(
            PlutusData rest, long cursor, byte[] pathHash,
            byte[] valueHash, boolean including) {
        if (Builtins.nullList(rest)) {
            if (!including) {
                return Builtins.emptyByteString();
            }
            return commitLeafSuffix(pathHash, cursor, valueHash);
        }
        PlutusData step = Builtins.headList(rest);
        PlutusData tail = Builtins.tailList(rest);
        long tag = Builtins.constrTag(step);
        PlutusData fields = Builtins.constrFields(step);
        long skip = Builtins.unIData(Builtins.headList(fields)).longValue();
        long nextCursor = cursor + 1 + skip;
        byte[] child = walk(tail, nextCursor, pathHash, valueHash, including);
        long nibble = nibbleAt(pathHash, nextCursor - 1);
        boolean lastStep = Builtins.nullList(tail);
        byte[] childHash = Builtins.lengthOfByteString(child) == 0
                ? nullHash() : child;

        if (tag == 0) {
            // Branch{skip, neighbors 128B [, branchValueHash]}
            PlutusData afterSkip = Builtins.tailList(fields);
            byte[] neighbors = Builtins.unBData(Builtins.headList(afterSkip));
            byte[] merkle = aggregate(
                    nibble,
                    childHash,
                    Builtins.sliceByteString(0, 32, neighbors),
                    Builtins.sliceByteString(32, 32, neighbors),
                    Builtins.sliceByteString(64, 32, neighbors),
                    Builtins.sliceByteString(96, 32, neighbors));
            byte[] finalMerkle = withBranchValue(
                    merkle, Builtins.tailList(afterSkip));
            return Builtins.blake2b_256(Builtins.appendByteString(
                    nibblePrefix(pathHash, cursor, nextCursor - 1), finalMerkle));
        }
        if (tag == 1) {
            // Fork{skip, Neighbor{nibble, prefix, root}}
            PlutusData neighbor = Builtins.headList(Builtins.tailList(fields));
            PlutusData neighborFields = Builtins.constrFields(neighbor);
            long forkNibble = Builtins.unIData(
                    Builtins.headList(neighborFields)).longValue();
            byte[] forkPrefix = Builtins.unBData(
                    Builtins.headList(Builtins.tailList(neighborFields)));
            byte[] forkRoot = Builtins.unBData(Builtins.headList(
                    Builtins.tailList(Builtins.tailList(neighborFields))));
            if (!including && lastStep) {
                long queryNibble = nibbleAt(pathHash, cursor + skip);
                if (queryNibble == forkNibble) {
                    Builtins.error();
                }
                return forkRoot;
            }
            byte[] neighborHash = Builtins.blake2b_256(
                    Builtins.appendByteString(forkPrefix, forkRoot));
            if (forkNibble == nibble) {
                Builtins.error();
            }
            return sparseBranch(pathHash, cursor, nextCursor - 1,
                    nibble, childHash, forkNibble, neighborHash);
        }
        // Leaf{skip, keyHash32, valueHash32}
        byte[] leafKeyHash = Builtins.unBData(
                Builtins.headList(Builtins.tailList(fields)));
        byte[] leafValueHash = Builtins.unBData(Builtins.headList(
                Builtins.tailList(Builtins.tailList(fields))));
        if (!samePrefix(pathHash, leafKeyHash, cursor)) {
            Builtins.error();
        }
        long neighborNibble = nibbleAt(leafKeyHash, nextCursor - 1);
        if (neighborNibble == nibble) {
            Builtins.error();
        }
        if (!including && lastStep) {
            return commitLeafSuffix(leafKeyHash, cursor, leafValueHash);
        }
        byte[] neighborHash = commitLeafSuffix(leafKeyHash, nextCursor, leafValueHash);
        return sparseBranch(pathHash, cursor, nextCursor - 1,
                nibble, childHash, neighborNibble, neighborHash);
    }

    /** Optional branch value: fold into the merkle when present. */
    private static byte[] withBranchValue(byte[] merkle, PlutusData afterNeighbors) {
        if (Builtins.nullList(afterNeighbors)) {
            return merkle;
        }
        byte[] branchValueHash =
                Builtins.unBData(Builtins.headList(afterNeighbors));
        byte[] valueCommit = Builtins.blake2b_256(
                Builtins.appendByteString(
                        Builtins.consByteString(0xFF, Builtins.emptyByteString()),
                        branchValueHash));
        return hash2(merkle, valueCommit);
    }

    /** Leaf commitment for the path suffix starting at {@code cursor}. */
    static byte[] commitLeafSuffix(byte[] pathHash, long cursor, byte[] valueHash) {
        return Builtins.blake2b_256(Builtins.appendByteString(
                Builtins.appendByteString(
                        leafHead(pathHash, cursor),
                        leafTail(pathHash, cursor)),
                valueHash));
    }

    private static byte[] leafHead(byte[] pathHash, long cursor) {
        long remaining = 64 - cursor;
        if (remaining == 0) {
            return Builtins.consByteString(0xFF, Builtins.emptyByteString());
        }
        if (remaining % 2 == 1) {
            return Builtins.consByteString(0x00,
                    Builtins.consByteString(nibbleAt(pathHash, cursor),
                            Builtins.emptyByteString()));
        }
        return Builtins.consByteString(0xFF, Builtins.emptyByteString());
    }

    private static byte[] leafTail(byte[] pathHash, long cursor) {
        long remaining = 64 - cursor;
        if (remaining == 0) {
            return Builtins.emptyByteString();
        }
        if (remaining % 2 == 1) {
            return packedTail(pathHash, cursor + 1);
        }
        return packedTail(pathHash, cursor);
    }

    /** Packed bytes of the path nibbles from {@code cursor} (even count) to the end. */
    private static byte[] packedTail(byte[] pathHash, long cursor) {
        // cursor is even here: the remaining nibbles align to whole bytes.
        long startByte = cursor / 2;
        return Builtins.sliceByteString(startByte, 32 - startByte, pathHash);
    }

    /** One byte per nibble of the path between [from, to). */
    private static byte[] nibblePrefix(byte[] pathHash, long from, long to) {
        byte[] out = Builtins.emptyByteString();
        long index = from;
        while (index < to) {
            out = Builtins.appendByteString(out,
                    Builtins.consByteString(nibbleAt(pathHash, index),
                            Builtins.emptyByteString()));
            index = index + 1;
        }
        return out;
    }

    private static byte[] sparseBranch(
            byte[] pathHash, long prefixFrom, long prefixTo,
            long meNibble, byte[] meHash, long neighborNibble, byte[] neighborHash) {
        byte[] merkle = merkle16(meNibble, meHash, neighborNibble, neighborHash);
        return Builtins.blake2b_256(Builtins.appendByteString(
                nibblePrefix(pathHash, prefixFrom, prefixTo), merkle));
    }

    private static byte[] slot(long index, long meNibble, byte[] meHash,
                               long otherNibble, byte[] otherHash) {
        if (index == meNibble) {
            return meHash;
        }
        if (index == otherNibble) {
            return otherHash;
        }
        return nullHash();
    }

    private static byte[] merkle16(
            long meNibble, byte[] meHash, long otherNibble, byte[] otherHash) {
        byte[] h01 = hash2(slot(0, meNibble, meHash, otherNibble, otherHash),
                slot(1, meNibble, meHash, otherNibble, otherHash));
        byte[] h23 = hash2(slot(2, meNibble, meHash, otherNibble, otherHash),
                slot(3, meNibble, meHash, otherNibble, otherHash));
        byte[] h45 = hash2(slot(4, meNibble, meHash, otherNibble, otherHash),
                slot(5, meNibble, meHash, otherNibble, otherHash));
        byte[] h67 = hash2(slot(6, meNibble, meHash, otherNibble, otherHash),
                slot(7, meNibble, meHash, otherNibble, otherHash));
        byte[] h89 = hash2(slot(8, meNibble, meHash, otherNibble, otherHash),
                slot(9, meNibble, meHash, otherNibble, otherHash));
        byte[] hab = hash2(slot(10, meNibble, meHash, otherNibble, otherHash),
                slot(11, meNibble, meHash, otherNibble, otherHash));
        byte[] hcd = hash2(slot(12, meNibble, meHash, otherNibble, otherHash),
                slot(13, meNibble, meHash, otherNibble, otherHash));
        byte[] hef = hash2(slot(14, meNibble, meHash, otherNibble, otherHash),
                slot(15, meNibble, meHash, otherNibble, otherHash));
        byte[] q0 = hash2(h01, h23);
        byte[] q1 = hash2(h45, h67);
        byte[] q2 = hash2(h89, hab);
        byte[] q3 = hash2(hcd, hef);
        return hash2(hash2(q0, q1), hash2(q2, q3));
    }

    /** Sibling aggregation for full branch steps (4 pre-hashed levels). */
    static byte[] aggregate(
            long nibble, byte[] me, byte[] lvl1, byte[] lvl2,
            byte[] lvl3, byte[] lvl4) {
        byte[] step1 = nibble % 2 == 0 ? hash2(me, lvl4) : hash2(lvl4, me);
        byte[] step2 = (nibble / 2) % 2 == 0
                ? hash2(step1, lvl3) : hash2(lvl3, step1);
        byte[] step3 = (nibble / 4) % 2 == 0
                ? hash2(step2, lvl2) : hash2(lvl2, step2);
        return nibble / 8 == 0 ? hash2(step3, lvl1) : hash2(lvl1, step3);
    }

    static byte[] hash2(byte[] left, byte[] right) {
        return Builtins.blake2b_256(Builtins.appendByteString(left, right));
    }

    static long nibbleAt(byte[] pathHash, long index) {
        long b = Builtins.indexByteString(pathHash, index / 2);
        if (index % 2 == 0) {
            return b / 16;
        }
        return b % 16;
    }

    private static boolean samePrefix(byte[] pathHash, byte[] otherHash, long nibbles) {
        long index = 0;
        boolean same = true;
        while (index < nibbles) {
            if (nibbleAt(pathHash, index) != nibbleAt(otherHash, index)) {
                same = false;
                index = nibbles;
            } else {
                index = index + 1;
            }
        }
        return same;
    }
}
