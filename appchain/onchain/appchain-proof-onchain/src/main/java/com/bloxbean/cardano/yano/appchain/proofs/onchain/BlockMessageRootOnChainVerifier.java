package com.bloxbean.cardano.yano.appchain.proofs.onchain;

import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;

import java.math.BigInteger;

/** Bounded nested MPF block-record and binary message-path verifier. */
@OnchainLibrary
public final class BlockMessageRootOnChainVerifier {
    public static final long MAX_MESSAGES = 10000;
    public static final long MAX_PATH_DEPTH = 14;

    public record Claim(MpfOnChainVerifier.Proof blockRecordProof,
                        byte[] expectedBlockKey, BigInteger expectedHeight,
                        byte[] messageId, BigInteger messageIndex,
                        BigInteger messageCount, JulcList<byte[]> siblings) { }
    private record UInt(BigInteger value, long next) { }
    private record BlockRecord(BigInteger height, byte[] messagesRoot,
                               BigInteger messageCount, boolean valid) { }

    private BlockMessageRootOnChainVerifier() {
    }

    /** Verify the nested proof after the calling validator authenticates {@code stateRoot}. */
    public static boolean verifyAtRoot(Claim claim, byte[] stateRoot) {
        if (Builtins.lengthOfByteString(stateRoot) != 32
                || !Builtins.equalsByteString(claim.blockRecordProof().key(),
                claim.expectedBlockKey())
                || !MpfOnChainVerifier.verifyInclusion(claim.blockRecordProof(), stateRoot)) {
            return false;
        }
        BlockRecord record = decodeRecord(claim.blockRecordProof().value());
        long count = claim.messageCount().longValue();
        long index = claim.messageIndex().longValue();
        if (!record.valid() || !record.height().equals(claim.expectedHeight())
                || !record.messageCount().equals(claim.messageCount())
                || count < 1 || count > MAX_MESSAGES || index < 0 || index >= count
                || Builtins.lengthOfByteString(claim.messageId()) != 32
                || claim.siblings().size() != pathLength(count)
                || claim.siblings().size() > MAX_PATH_DEPTH) {
            return false;
        }
        byte[] node = claim.messageId();
        long width = count;
        boolean valid = true;
        for (byte[] sibling : claim.siblings()) {
            if (Builtins.lengthOfByteString(sibling) != 32) {
                valid = false;
                break;
            }
            boolean duplicatedLast = width % 2 == 1 && index == width - 1;
            if (duplicatedLast && !Builtins.equalsByteString(node, sibling)) {
                valid = false;
                break;
            }
            node = index % 2 == 0 ? parent(node, sibling) : parent(sibling, node);
            index /= 2;
            width = (width + 1) / 2;
        }
        return valid && width == 1 && Builtins.equalsByteString(node, record.messagesRoot());
    }

    private static BlockRecord decodeRecord(byte[] value) {
        long length = Builtins.lengthOfByteString(value);
        if (length < 38 || Builtins.indexByteString(value, 0) != 132) {
            return invalidRecord();
        }
        UInt version = readUInt(value, 1);
        UInt height = readUInt(value, version.next());
        long rootOffset = height.next();
        if (!version.value().equals(BigInteger.ONE) || height.value().signum() <= 0
                || rootOffset + 34 > length
                || Builtins.indexByteString(value, rootOffset) != 88
                || Builtins.indexByteString(value, rootOffset + 1) != 32) {
            return invalidRecord();
        }
        byte[] root = Builtins.sliceByteString(rootOffset + 2, 32, value);
        UInt count = readUInt(value, rootOffset + 34);
        return new BlockRecord(height.value(), root, count.value(),
                count.next() == length && count.value().signum() >= 0
                        && count.value().compareTo(BigInteger.valueOf(MAX_MESSAGES)) <= 0);
    }

    private static long pathLength(long count) {
        long result = 0;
        long width = count;
        while (width > 1) {
            result += 1;
            width = (width + 1) / 2;
        }
        return result;
    }

    private static byte[] parent(byte[] left, byte[] right) {
        return Builtins.blake2b_256(Builtins.appendByteString(left, right));
    }

    private static BlockRecord invalidRecord() {
        return new BlockRecord(BigInteger.valueOf(-1), Builtins.emptyByteString(),
                BigInteger.valueOf(-1), false);
    }

    private static UInt readUInt(byte[] encoded, long offset) {
        long length = Builtins.lengthOfByteString(encoded);
        if (offset < 0 || offset >= length) return new UInt(BigInteger.valueOf(-1), length + 1);
        long head = Builtins.indexByteString(encoded, offset);
        if (head < 24) return new UInt(BigInteger.valueOf(head), offset + 1);
        if (head == 24 && offset + 2 <= length) {
            long value = Builtins.indexByteString(encoded, offset + 1);
            return value >= 24 ? new UInt(BigInteger.valueOf(value), offset + 2)
                    : new UInt(BigInteger.valueOf(-1), length + 1);
        }
        if (head == 25 && offset + 3 <= length) {
            long value = Builtins.indexByteString(encoded, offset + 1) * 256
                    + Builtins.indexByteString(encoded, offset + 2);
            return value >= 256 ? new UInt(BigInteger.valueOf(value), offset + 3)
                    : new UInt(BigInteger.valueOf(-1), length + 1);
        }
        if (head == 26 && offset + 5 <= length) {
            long value = Builtins.indexByteString(encoded, offset + 1) * 16777216
                    + Builtins.indexByteString(encoded, offset + 2) * 65536
                    + Builtins.indexByteString(encoded, offset + 3) * 256
                    + Builtins.indexByteString(encoded, offset + 4);
            return value >= 65536 ? new UInt(BigInteger.valueOf(value), offset + 5)
                    : new UInt(BigInteger.valueOf(-1), length + 1);
        }
        if (head == 27 && offset + 9 <= length) {
            long high = Builtins.indexByteString(encoded, offset + 1);
            long value = high * 72057594037927936L
                    + Builtins.indexByteString(encoded, offset + 2) * 281474976710656L
                    + Builtins.indexByteString(encoded, offset + 3) * 1099511627776L
                    + Builtins.indexByteString(encoded, offset + 4) * 4294967296L
                    + Builtins.indexByteString(encoded, offset + 5) * 16777216L
                    + Builtins.indexByteString(encoded, offset + 6) * 65536L
                    + Builtins.indexByteString(encoded, offset + 7) * 256L
                    + Builtins.indexByteString(encoded, offset + 8);
            return high < 128 && value >= 4294967296L
                    ? new UInt(BigInteger.valueOf(value), offset + 9)
                    : new UInt(BigInteger.valueOf(-1), length + 1);
        }
        return new UInt(BigInteger.valueOf(-1), length + 1);
    }
}
