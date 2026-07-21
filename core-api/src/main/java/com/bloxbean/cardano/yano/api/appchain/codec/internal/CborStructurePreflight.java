package com.bloxbean.cardano.yano.api.appchain.codec.internal;

/**
 * Non-recursive CBOR structure preflight used before recursive third-party
 * decoders see portable evidence. It bounds bytes, nesting, and item work,
 * accepts definite containers plus serializer-produced indefinite arrays,
 * and rejects malformed/trailing or unsupported forms.
 *
 * @hidden defensive implementation detail, not a wire-format API
 */
public final class CborStructurePreflight {
    private CborStructurePreflight() {
    }

    public static boolean accepts(byte[] bytes, int maximumBytes,
                                  int maximumDepth, int maximumItems) {
        if (bytes == null || bytes.length == 0 || bytes.length > maximumBytes
                || maximumDepth < 1 || maximumItems < 1) {
            return false;
        }
        try {
            return scan(bytes, maximumDepth, maximumItems);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static boolean scan(byte[] bytes, int maximumDepth, int maximumItems) {
        long[] remaining = new long[maximumDepth + 1];
        boolean[] indefinite = new boolean[maximumDepth + 1];
        int[] indefiniteChildMajor = new int[maximumDepth + 1];
        int[] indefiniteGroupSize = new int[maximumDepth + 1];
        int[] indefiniteChildCount = new int[maximumDepth + 1];
        java.util.Arrays.fill(indefiniteChildMajor, -1);
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
                    if (indefiniteChildCount[depth] % indefiniteGroupSize[depth] != 0) {
                        throw malformed();
                    }
                    offset++;
                    depth--;
                    continue;
                }
                indefiniteChildCount[depth]++;
            } else if (remaining[depth] == 0) {
                depth--;
                continue;
            } else {
                remaining[depth]--;
            }

            if (++items > maximumItems || offset >= bytes.length) {
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
                        depth = push(depth, argument, 1, maximumDepth,
                                maximumItems - items, remaining, indefinite,
                                indefiniteChildMajor, indefiniteGroupSize,
                                indefiniteChildCount, major);
                    } else {
                        if (argument.value() > bytes.length - offset) {
                            throw malformed();
                        }
                        offset += (int) argument.value();
                    }
                }
                case 4 -> depth = push(depth, argument, 1,
                        maximumDepth, maximumItems - items, remaining, indefinite,
                        indefiniteChildMajor, indefiniteGroupSize,
                        indefiniteChildCount, -1);
                case 5 -> depth = push(depth, argument, 2,
                        maximumDepth, maximumItems - items, remaining, indefinite,
                        indefiniteChildMajor, indefiniteGroupSize,
                        indefiniteChildCount, -1);
                case 6 -> {
                    requireDefinite(argument);
                    depth = push(depth, new Argument(1, offset, false), 1,
                            maximumDepth, maximumItems - items, remaining, indefinite,
                            indefiniteChildMajor, indefiniteGroupSize,
                            indefiniteChildCount, -1);
                }
                case 7 -> {
                    requireDefinite(argument);
                    if (additional < 20 || additional > 23) {
                        throw malformed();
                    }
                }
                default -> throw malformed();
            }
        }
        return offset == bytes.length;
    }

    private static int push(int depth, Argument argument, int multiplier,
                            int maximumDepth, int remainingItems,
                            long[] remaining, boolean[] indefinite,
                            int[] indefiniteChildMajor, int[] indefiniteGroupSize,
                            int[] indefiniteChildCount, int childMajor) {
        if (depth >= maximumDepth) {
            throw malformed();
        }
        int childDepth = depth + 1;
        if (argument.indefinite()) {
            indefinite[childDepth] = true;
            indefiniteChildMajor[childDepth] = childMajor;
            indefiniteGroupSize[childDepth] = multiplier;
            indefiniteChildCount[childDepth] = 0;
            remaining[childDepth] = -1;
            return childDepth;
        }
        if (argument.value() > Integer.MAX_VALUE
                || argument.value() > remainingItems / multiplier) {
            throw malformed();
        }
        long children = argument.value() * multiplier;
        if (children == 0) {
            return depth;
        }
        indefinite[childDepth] = false;
        indefiniteChildMajor[childDepth] = -1;
        indefiniteGroupSize[childDepth] = 1;
        indefiniteChildCount[childDepth] = 0;
        remaining[childDepth] = children;
        return childDepth;
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
        if (offset > bytes.length - width
                || width == 8 && (bytes[offset] & 0x80) != 0) {
            throw malformed();
        }
        long value = 0;
        for (int index = 0; index < width; index++) {
            value = (value << 8) | (bytes[offset + index] & 0xffL);
        }
        return new Argument(value, offset + width, false);
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
