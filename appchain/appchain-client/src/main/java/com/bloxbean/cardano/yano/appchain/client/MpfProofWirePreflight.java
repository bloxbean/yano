package com.bloxbean.cardano.yano.appchain.client;

/**
 * Non-recursive structural preflight for the MPF proof CBOR consumed by the
 * upstream verifier. It accepts the serializer's definite-length v1 grammar
 * (plus legacy untagged branch steps) and rejects nested or trailing data
 * before a general-purpose recursive CBOR decoder sees untrusted bytes.
 */
final class MpfProofWirePreflight {
    private static final long TAG_BRANCH = 121;
    private static final long TAG_FORK = 122;
    private static final long TAG_LEAF = 123;
    private static final int HASH_BYTES = 32;
    private static final int BRANCH_NEIGHBOR_BYTES = 4 * HASH_BYTES;
    private static final int MAX_PATH_NIBBLES = 2 * HASH_BYTES;

    private final byte[] wire;
    private int offset;

    private MpfProofWirePreflight(byte[] wire) {
        this.wire = wire;
    }

    static boolean accepts(byte[] wire) {
        if (wire == null || wire.length == 0) {
            return false;
        }
        try {
            MpfProofWirePreflight parser = new MpfProofWirePreflight(wire);
            int steps = parser.readContainerLength(4);
            if (steps > MAX_PATH_NIBBLES) {
                return false;
            }
            int consumedNibbles = 0;
            for (int index = 0; index < steps; index++) {
                int remainingNibbles = MAX_PATH_NIBBLES - consumedNibbles;
                int skip = parser.readStep(index == steps - 1, remainingNibbles);
                consumedNibbles += 1 + skip;
            }
            return parser.offset == wire.length;
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private int readStep(boolean last, int remainingNibbles) {
        long tag = readOptionalTag();
        int fields = readContainerLength(4);
        if (tag == -1 || tag == TAG_BRANCH) {
            if (fields != 2 && fields != 3) {
                throw malformed();
            }
            int skip = readSkip(remainingNibbles);
            readFixedByteString(BRANCH_NEIGHBOR_BYTES);
            if (fields == 3) {
                readFixedByteString(HASH_BYTES);
            }
            return skip;
        }
        if (tag == TAG_FORK) {
            if (fields != 2) {
                throw malformed();
            }
            int skip = readSkip(remainingNibbles);
            long neighborTag = readOptionalTag();
            if (neighborTag != TAG_BRANCH) {
                throw malformed();
            }
            if (readContainerLength(4) != 3) {
                throw malformed();
            }
            if (readUnsigned(0) > 15) {
                throw malformed();
            }
            readNibbleByteString(remainingNibbles - skip - 1);
            readFixedByteString(HASH_BYTES);
            return skip;
        }
        if (tag == TAG_LEAF) {
            if (!last || fields != 3) {
                throw malformed();
            }
            int skip = readSkip(remainingNibbles);
            readFixedByteString(HASH_BYTES);
            readFixedByteString(HASH_BYTES);
            return skip;
        }
        throw malformed();
    }

    private int readSkip(int remainingNibbles) {
        long skip = readUnsigned(0);
        if (skip >= remainingNibbles) {
            throw malformed();
        }
        return (int) skip;
    }

    private long readOptionalTag() {
        if (offset >= wire.length || ((wire[offset] & 0xff) >>> 5) != 6) {
            return -1;
        }
        return readUnsigned(6);
    }

    private int readContainerLength(int expectedMajor) {
        long length = readUnsigned(expectedMajor);
        if (length > Integer.MAX_VALUE) {
            throw malformed();
        }
        return (int) length;
    }

    private void readFixedByteString(int expectedLength) {
        long length = readUnsigned(2);
        if (length != expectedLength || length > wire.length - offset) {
            throw malformed();
        }
        offset += (int) length;
    }

    private void readNibbleByteString(int maximumLength) {
        long length = readUnsigned(2);
        if (length > maximumLength || length > wire.length - offset) {
            throw malformed();
        }
        int end = offset + (int) length;
        while (offset < end) {
            if (next() > 15) {
                throw malformed();
            }
        }
    }

    private long readUnsigned(int expectedMajor) {
        int initial = next();
        int major = initial >>> 5;
        int additional = initial & 0x1f;
        if (major != expectedMajor || additional >= 28) {
            throw malformed();
        }
        if (additional < 24) {
            return additional;
        }
        int bytes = switch (additional) {
            case 24 -> 1;
            case 25 -> 2;
            case 26 -> 4;
            case 27 -> 8;
            default -> throw malformed();
        };
        if (bytes > wire.length - offset) {
            throw malformed();
        }
        long value = 0;
        for (int index = 0; index < bytes; index++) {
            int next = next();
            if (index == 0 && bytes == 8 && (next & 0x80) != 0) {
                throw malformed();
            }
            value = (value << 8) | next;
        }
        if ((bytes == 1 && value < 24)
                || (bytes == 2 && value <= 0xffL)
                || (bytes == 4 && value <= 0xffffL)
                || (bytes == 8 && value <= 0xffff_ffffL)) {
            throw malformed();
        }
        return value;
    }

    private int next() {
        if (offset >= wire.length) {
            throw malformed();
        }
        return wire[offset++] & 0xff;
    }

    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException("invalid MPF proof wire");
    }
}
