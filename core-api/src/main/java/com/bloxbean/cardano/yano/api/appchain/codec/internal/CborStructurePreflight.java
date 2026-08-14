package com.bloxbean.cardano.yano.api.appchain.codec.internal;

import java.util.Arrays;

/**
 * Dependency-free, non-recursive CBOR structure preflight for untrusted
 * app-chain bytes. It rejects malformed, trailing, or over-budget input before
 * a recursive third-party decoder is entered.
 *
 * <p>This scanner validates structure, not application shape or canonical
 * encoding. Callers must use immutable contract-owned limits and then perform
 * their normal typed/canonical decode.</p>
 *
 * @hidden defensive implementation detail, not a wire-format API
 */
public final class CborStructurePreflight {
    private CborStructurePreflight() {
    }

    /** Frozen structural work limits selected by the owning wire contract. */
    public record Limits(int maximumBytes,
                         int maximumDepth,
                         int maximumItems,
                         int maximumContainerItems,
                         int maximumStringBytes) {
        public Limits {
            if (maximumBytes < 1 || maximumDepth < 1 || maximumItems < 1
                    || maximumContainerItems < 1 || maximumStringBytes < 0) {
                throw new IllegalArgumentException("CBOR preflight limits must be positive");
            }
        }
    }

    /**
     * Compatibility overload. New consensus boundaries should use
     * {@link #accepts(byte[], Limits)} and freeze all five limits explicitly.
     */
    public static boolean accepts(byte[] bytes, int maximumBytes,
                                  int maximumDepth, int maximumItems) {
        if (maximumBytes < 1 || maximumDepth < 1 || maximumItems < 1) {
            return false;
        }
        return accepts(bytes, new Limits(maximumBytes, maximumDepth,
                maximumItems, maximumItems, maximumBytes));
    }

    public static boolean accepts(byte[] bytes, Limits limits) {
        if (bytes == null || bytes.length == 0 || limits == null
                || bytes.length > limits.maximumBytes()) {
            return false;
        }
        try {
            return scan(bytes, limits);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static boolean scan(byte[] bytes, Limits limits) {
        int maximumDepth = limits.maximumDepth();
        long[] remaining = new long[maximumDepth + 1];
        boolean[] indefinite = new boolean[maximumDepth + 1];
        int[] indefiniteChildMajor = new int[maximumDepth + 1];
        int[] indefiniteGroupSize = new int[maximumDepth + 1];
        int[] childCount = new int[maximumDepth + 1];
        long[] stringBytes = new long[maximumDepth + 1];
        Arrays.fill(indefiniteChildMajor, -1);
        remaining[0] = 1;
        int depth = 0;
        int offset = 0;
        int items = 0;

        while (depth >= 0) {
            if (indefinite[depth]) {
                if (offset >= bytes.length) {
                    throw malformed();
                }
                if ((bytes[offset] & 0xff) == 0xff) {
                    if (childCount[depth] % indefiniteGroupSize[depth] != 0) {
                        throw malformed();
                    }
                    offset++;
                    depth--;
                    continue;
                }
                if (++childCount[depth] > maximumChildren(
                        limits.maximumContainerItems(), indefiniteGroupSize[depth])) {
                    throw malformed();
                }
            } else if (remaining[depth] == 0) {
                depth--;
                continue;
            } else {
                remaining[depth]--;
            }

            if (++items > limits.maximumItems() || offset >= bytes.length) {
                throw malformed();
            }
            int initial = bytes[offset++] & 0xff;
            int major = initial >>> 5;
            if (indefiniteChildMajor[depth] >= 0
                    && major != indefiniteChildMajor[depth]) {
                throw malformed();
            }
            int additional = initial & 0x1f;
            Argument argument = readArgument(bytes, offset, additional);
            offset = argument.nextOffset();

            switch (major) {
                case 0, 1 -> requireDefinite(argument);
                case 2, 3 -> {
                    if (argument.indefinite()) {
                        if (indefiniteChildMajor[depth] >= 0) {
                            throw malformed();
                        }
                        depth = push(depth, argument, 1, limits,
                                remaining, indefinite, indefiniteChildMajor,
                                indefiniteGroupSize, childCount, stringBytes, major);
                    } else {
                        if (argument.value() > limits.maximumStringBytes()
                                || argument.value() > bytes.length - offset) {
                            throw malformed();
                        }
                        if (indefiniteChildMajor[depth] >= 0) {
                            stringBytes[depth] += argument.value();
                            if (stringBytes[depth] > limits.maximumStringBytes()) {
                                throw malformed();
                            }
                        }
                        offset += (int) argument.value();
                    }
                }
                case 4 -> depth = push(depth, argument, 1, limits,
                        remaining, indefinite, indefiniteChildMajor,
                        indefiniteGroupSize, childCount, stringBytes, -1);
                case 5 -> depth = push(depth, argument, 2, limits,
                        remaining, indefinite, indefiniteChildMajor,
                        indefiniteGroupSize, childCount, stringBytes, -1);
                case 6 -> {
                    requireDefinite(argument);
                    depth = push(depth, new Argument(1, offset, false), 1, limits,
                            remaining, indefinite, indefiniteChildMajor,
                            indefiniteGroupSize, childCount, stringBytes, -1);
                }
                case 7 -> validateSimple(additional, argument);
                default -> throw malformed();
            }
        }
        return offset == bytes.length;
    }

    private static int push(int depth, Argument argument, int multiplier,
                            Limits limits, long[] remaining, boolean[] indefinite,
                            int[] indefiniteChildMajor, int[] indefiniteGroupSize,
                            int[] childCount, long[] stringBytes, int childMajor) {
        if (depth >= limits.maximumDepth()) {
            throw malformed();
        }
        int childDepth = depth + 1;
        childCount[childDepth] = 0;
        stringBytes[childDepth] = 0;
        if (argument.indefinite()) {
            indefinite[childDepth] = true;
            indefiniteChildMajor[childDepth] = childMajor;
            indefiniteGroupSize[childDepth] = multiplier;
            remaining[childDepth] = -1;
            return childDepth;
        }
        if (argument.value() > limits.maximumContainerItems()
                || argument.value() > Integer.MAX_VALUE
                || argument.value() > (limits.maximumItems() / multiplier)) {
            throw malformed();
        }
        long children = argument.value() * multiplier;
        if (children == 0) {
            return depth;
        }
        indefinite[childDepth] = false;
        indefiniteChildMajor[childDepth] = -1;
        indefiniteGroupSize[childDepth] = 1;
        remaining[childDepth] = children;
        return childDepth;
    }

    private static int maximumChildren(int maximumContainerItems, int groupSize) {
        long maximum = (long) maximumContainerItems * groupSize;
        return maximum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) maximum;
    }

    private static Argument readArgument(byte[] bytes, int offset, int additional) {
        if (additional < 24) {
            return new Argument(additional, offset, false);
        }
        if (additional == 31) {
            return new Argument(-1, offset, true);
        }
        int width = switch (additional) {
            case 24 -> 1;
            case 25 -> 2;
            case 26 -> 4;
            case 27 -> 8;
            default -> throw malformed();
        };
        if (offset > bytes.length - width) {
            throw malformed();
        }
        if (width == 8 && (bytes[offset] & 0x80) != 0) {
            // Scalar uint/nint/tag arguments may use the full unsigned range.
            // Container and string callers will reject this sentinel against
            // their much smaller structural limits.
            return new Argument(Long.MAX_VALUE, offset + width, false);
        }
        long value = 0;
        for (int index = 0; index < width; index++) {
            value = (value << 8) | (bytes[offset + index] & 0xffL);
        }
        return new Argument(value, offset + width, false);
    }

    private static void validateSimple(int additional, Argument argument) {
        if (argument.indefinite()) {
            throw malformed();
        }
        // 0..23 are simple values; 24 carries one simple-value byte; 25..27
        // carry IEEE-754 values. Additional values 28..30 are rejected by
        // readArgument and break (31) is consumed only by an indefinite frame.
        if (additional > 27) {
            throw malformed();
        }
    }

    private static void requireDefinite(Argument argument) {
        if (argument.indefinite()) {
            throw malformed();
        }
    }

    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException("invalid bounded CBOR structure");
    }

    private record Argument(long value, int nextOffset, boolean indefinite) {
    }
}
