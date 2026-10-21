package org.yanoproject.ledger.rules.conway.tx;

import java.util.Arrays;

/**
 * A byte range {@code [start, end)} of the original transaction bytes: one encoded data item, exactly as
 * received.
 *
 * @param start offset of the item's first byte
 * @param end   offset just past the item
 */
public record CborSlice(int start, int end) {

    public CborSlice {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("bad slice [" + start + ", " + end + ")");
        }
    }

    /** @return the number of bytes */
    public int length() {
        return end - start;
    }

    /** @return a copy of the item's bytes from {@code source} */
    public byte[] copy(byte[] source) {
        return Arrays.copyOfRange(source, start, end);
    }
}
